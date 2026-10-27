package de.atelierlauncher.app

import android.Manifest
import androidx.compose.foundation.layout.fillMaxSize
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.AlarmClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.roundToInt

/** Ein App-Kurzbefehl (z. B. "Neue Nachricht"); info ist ein ShortcutInfo (ab Android 7.1) */
class Kurzbefehl(val info: Any, val name: String, val bild: ImageBitmap?)

/**
 * Ist der Launcher gerade im Vordergrund (zwischen onResume und onPause)?
 * Animationen und Lade-Schleifen laufen nur dann - spart Akku, wenn eine andere App offen ist.
 */
object Sichtbar {
    var an by mutableStateOf(true)
    /** Launcher wirklich vorne (nur onResume/onPause) - startet false, weil die Dienste auch ohne Launcher laufen */
    @Volatile var vorne = false
}

class MainActivity : ComponentActivity() {
    /** Entwickler (nur Atelier, nur per USB): adb shell dumpsys activity <paket> standard -> Aussehen/Anordnung ohne Persoenliches */
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        if (args?.contains("standard") == true) { writer.println(StandardExport.text(this)); return }
        super.dump(prefix, fd, writer, args)
    }

    lateinit var z: Zustand
    private lateinit var lader: AppLader
    private val intern by lazy { getSharedPreferences("intern", Context.MODE_PRIVATE) }
    /** true, wenn gerade eine App gestartet wurde -> beim Zurueckkommen die Lande-Animation zeigen */
    var vonApp = false
    private var tapeteAn: Boolean? = null

    private val kalenderRecht = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        Kalender.laden(this)
        z.fortsetzen++
        if (!ok && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
            Toast.makeText(this, Sprache.tr("Bitte unter Berechtigungen → Kalender erlauben"), Toast.LENGTH_LONG).show()
            info(packageName)
        }
    }
    private val schriftWahl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) schriftUebernehmen(uri)
    }

    // ---- allgemeine Dateiauswahl (Video, Lottie, Code-Dateien)
    private var dateiFertig: ((Uri) -> Unit)? = null
    /** Atelier: Bild aus der Galerie direkt als Hintergrundbild des Handys setzen ("start", "sperre" oder "beide") */
    private var hgZiel = "beide"
    private val galerieWahl = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data ?: return@registerForActivityResult
        val app = applicationContext
        Thread {
            val ok = try {
                contentResolver.openInputStream(uri)?.use {
                    val wm = android.app.WallpaperManager.getInstance(app)
                    val flags = when (hgZiel) {
                        "start" -> android.app.WallpaperManager.FLAG_SYSTEM
                        "sperre" -> android.app.WallpaperManager.FLAG_LOCK
                        else -> android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK
                    }
                    wm.setStream(it, null, true, flags)
                } != null
            } catch (e: Exception) { false }
            runOnUiThread {
                android.widget.Toast.makeText(this, Sprache.tr(if (ok) "Hintergrund gesetzt" else "Das Bild ließ sich nicht setzen"), android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    fun galerieHintergrund(ziel: String) {
        hgZiel = ziel
        val galerie = android.content.Intent(android.content.Intent.ACTION_PICK)
            .setDataAndType(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        try { galerieWahl.launch(galerie) }
        catch (e: Exception) {
            // keine Galerie-App: Android-Bildauswahl
            try { galerieWahl.launch(android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")) } catch (e2: Exception) { }
        }
    }

    private val dateiWahl = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = dateiFertig; dateiFertig = null
        val zweck = intern.getString("dateiZweck", null)
        intern.edit().remove("dateiZweck").apply()
        if (uri == null) return@registerForActivityResult
        if (f != null) f(uri)
        else when (zweck) {
            // Android hat den Launcher waehrend der Auswahl beendet -> Zweck aus dem Speicher
            "video" -> dateiUebernehmen(uri, EigeneDateien.video(this)) {}
            "lottie" -> dateiUebernehmen(uri, EigeneDateien.lottie(this)) {}
            "glasfoto" -> glasFotoUebernehmen(this, uri)
            "bild" -> dateiUebernehmen(uri, EigeneDateien.bild(this)) {}
            "sperrbild" -> dateiUebernehmen(uri, EigeneDateien.sperrBild(this)) { SperrMedium.anwenden(this) }
            "sperrvideo" -> dateiUebernehmen(uri, EigeneDateien.sperrVideo(this)) { SperrMedium.anwenden(this) }
        }
    }
    /** zweck: "video"/"lottie" ueberlebt auch, wenn Android den Launcher waehrend der Auswahl beendet */
    fun dateiWaehlen(typen: Array<String>, zweck: String? = null, fertig: (Uri) -> Unit) {
        dateiFertig = fertig
        intern.edit().apply { if (zweck != null) putString("dateiZweck", zweck) else remove("dateiZweck") }.apply()
        try { dateiWahl.launch(typen) } catch (e: Exception) { dateiFertig = null }
    }
    // ---- Datei anlegen (Sicherung speichern)
    private var speichernFertig: ((Uri) -> Unit)? = null
    private val dateiAnlegen = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val f = speichernFertig; speichernFertig = null
        if (uri != null) f?.invoke(uri)
    }
    fun dateiSpeichern(name: String, fertig: (Uri) -> Unit) {
        speichernFertig = fertig
        try { dateiAnlegen.launch(name) } catch (e: Exception) { speichernFertig = null }
    }
    // ---- Bastel-Ordner (Ordner in "Eigene Dateien", in dem der eigene Code liegt)
    private var ordnerFertig: (() -> Unit)? = null
    private val ordnerWahl = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val f = ordnerFertig; ordnerFertig = null
        if (uri != null) {
            BastelOrdner.setzen(this, uri)
            AlphabetSkript.neuLesen = true
            z.eigenStand++
            Toast.makeText(this, Sprache.tr("Ordner gewählt – Beispiele werden hineingelegt"), Toast.LENGTH_LONG).show()
            f?.invoke()
        }
    }
    fun ordnerWaehlen(fertig: () -> Unit = {}) {
        ordnerFertig = fertig
        try { ordnerWahl.launch(null) } catch (e: Exception) { ordnerFertig = null }
    }

    /** gewaehlte Datei in den App-Speicher kopieren (im Hintergrund), danach Meldung */
    fun dateiUebernehmen(uri: Uri, ziel: File, fertig: () -> Unit) {
        Thread {
            val ok = try {
                contentResolver.openInputStream(uri)?.use { ein -> ziel.outputStream().use { aus -> ein.copyTo(aus) } } != null
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, Sprache.tr(if (ok) "Übernommen" else "Datei ließ sich nicht lesen"), Toast.LENGTH_SHORT).show()
                if (ok) { z.eigenStand++; fertig() }
            }
        }.start()
    }
    /** Textdatei lesen (fuer Code aus einer Datei) */
    fun textLesen(uri: Uri, fertig: (String) -> Unit) {
        Thread {
            val t = try { contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { null }
            runOnUiThread { if (t != null) fertig(t) else Toast.makeText(this, Sprache.tr("Datei ließ sich nicht lesen"), Toast.LENGTH_SHORT).show() }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        absturzSchutz()
        ohneEinschieben()
        enableEdgeToEdge()
        z = Zustand(this)
        z.aktivitaet = this
        Erweiterung.laden(this)
        Sprache.laden(this)
        SwissIcons.laden(this)
        Gefuehl.laden(this)
        Designs.start(this, z)
        MeineDesigns.beimStart(this)        // einmal: jetzigen Stand als "Mein Design" merken
        intent?.getStringExtra("oeffne")?.let { z.oeffnenWunsch = it }              // Atelier: geladene Designs, erster Start = Schwarz & Weiss
        Variante.appName = try { applicationInfo.loadLabel(packageManager).toString() } catch (e: Exception) { Variante.appName }
        if (z.taskleiste == "eigen") { z.taskleisteSetzen("unsichtbar"); Navileiste.setzen(this, true) }
        // Objekte mit Compose-State hier anlegen (Hauptthread, ausserhalb einer Komposition)
        Kalender.termine; Wetter.daten; Musik.info; IconSpeicher.icons
        widgetsAltAufraeumen()
        lader = AppLader(this, z)
        lader.start()                       // Liste und Icons sofort aus dem Zwischenspeicher
        Taschenlampe.start(this)
        setContent {
            CompositionLocalProvider(LocalZustand provides z) {
                LauncherTheme(z.thema) {
                    // eigene Lieder: Menue von oben ueber allem - alles dahinter wird dabei weichgezeichnet
                    EigeneMusik.hinterEbene = androidx.compose.ui.graphics.rememberGraphicsLayer()
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().hinterMusikMenue()) { Launcher() }
                        EigeneMusikMenue()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Liegen auf der rechten Seite -> spiegeln
    private var schwerX = 0f
    private var schwerY = 9.81f
    private var schwerZ = 0f
    private val lage = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            // weich filtern, dann den echten Kipp-Winkel zur Seite ausrechnen:
            // 0 Grad = aufrecht, 90 Grad = rechte Handykante zeigt genau nach unten.
            // (Haengt nicht davon ab, wie weit man das Handy nach hinten neigt.)
            schwerX = schwerX * 0.8f + e.values[0] * 0.2f
            schwerY = schwerY * 0.8f + e.values[1] * 0.2f
            schwerZ = schwerZ * 0.8f + e.values[2] * 0.2f
            val g = kotlin.math.sqrt(schwerX * schwerX + schwerY * schwerY + schwerZ * schwerZ)
            if (g < 5f) return                                       // geschuettelt / freier Fall: ignorieren
            val nachRechts = Math.toDegrees(kotlin.math.asin((-schwerX / g).coerceIn(-1f, 1f).toDouble())).toFloat()
            // bei 90 Grad kann es nicht weiter - ueber 90 hinaus (Kopf nach unten) zeigt y nach oben
            val winkel = if (schwerY < 0f && nachRechts > 0f) 180f - nachRechts else nachRechts
            val gerundet = winkel.roundToInt()
            if (gerundet != z.lageWinkel) z.lageWinkel = gerundet
            // Alphabet links NUR bei 80 bis 100 Grad (auf der rechten Seite liegen), sonst normal.
            // 5 Grad Puffer beim Verlassen, damit es an der Grenze nicht flackert.
            val r = z.gespiegelt
            val rJetzt = if (r) winkel in 75f..105f else winkel in 80f..100f
            if (rJetzt != r) z.gespiegelt = rJetzt
            if (z.linksLiegend) z.linksLiegend = false                 // Einruecken beim Liegen links: abgeschaltet
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }
    private fun lageStart() {
        if (!z.spiegelnAn) { z.gespiegelt = false; z.linksLiegend = false; return }
        val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        sm.registerListener(lage, s, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }
    /** Schalter in den Einstellungen geaendert */
    fun lageNeu() { lageStop(); lageStart() }
    private fun lageStop() {
        try { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(lage) } catch (e: Exception) { }
    }

    /**
     * Zurueck zum Startbildschirm (Hochwischen / Home-Taste): Android schob den Launcher sonst wie eine
     * normale App herein. Ohne Animation steht er einfach da (ab Android 14 dauerhaft fuer dieses Fenster).
     */
    private fun ohneEinschieben() {
        // alle Fenster-Animationen des Launchers auf "keine" (auch "wieder nach vorne holen" beim Hochwischen)
        try {
            val id = resources.getIdentifier("OhneAnimation", "style", packageName)
            if (id != 0) window.setWindowAnimations(id)
        } catch (e: Throwable) { }
        if (Build.VERSION.SDK_INT >= 34) try {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } catch (e: Throwable) { }
    }
    @Suppress("DEPRECATION")
    private fun ohneEinschiebenAlt() {
        if (Build.VERSION.SDK_INT < 34) try { overridePendingTransition(0, 0) } catch (e: Throwable) { }
    }

    override fun onRestart() {
        super.onRestart()
        ohneEinschiebenAlt()
        Heim.leicht = true                      // zurueckgekommen: erstes Bild ohne schwere Effekte
        fotoZeigen()
    }

    // ---------------------------------------------------------------------------------------------
    //  Foto vom Startbildschirm: beim Verlassen wird der Launcher fotografiert. Kommt man zurueck, ist im
    //  allerersten Bild nur dieses Foto zu sehen (sofort fertig - Android wartet mit dem Uebergang aufs erste
    //  Bild). Ein paar Bilder spaeter liegt der echte Startbildschirm darunter und das Foto verschwindet.
    // ---------------------------------------------------------------------------------------------
    private var foto: android.graphics.Bitmap? = null
    @Volatile private var fotoGut = false
    private var fotoZeit = 0L
    private var fotoModus = 0
    private var fotoZeigt: android.graphics.drawable.BitmapDrawable? = null

    private fun fotoMachen() {
        fotoGut = false
        if (Build.VERSION.SDK_INT < 26 || fotoZeigt != null || !Heim.fotoErlaubt()) return
        try { if (!getSystemService(android.os.PowerManager::class.java).isInteractive) return } catch (e: Exception) { }
        val v = window.decorView; val w = v.width; val h = v.height
        if (w <= 0 || h <= 0) return
        val b = foto?.takeIf { it.width == w && it.height == h } ?: try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) { return }
        foto = b
        fotoModus = resources.configuration.uiMode
        try {
            android.view.PixelCopy.request(window, b, { r ->
                if (r == android.view.PixelCopy.SUCCESS) { fotoGut = true; fotoZeit = android.os.SystemClock.uptimeMillis() }
            }, nachBildHandler)
        } catch (e: Throwable) { }
    }

    private fun fotoZeigen() {
        val b = foto ?: return
        if (!fotoGut || fotoZeigt != null) return
        fotoGut = false                                     // jedes Foto nur einmal
        if (android.os.SystemClock.uptimeMillis() - fotoZeit > 30 * 60_000L) return
        if (resources.configuration.uiMode != fotoModus) return
        val v = window.decorView
        if (b.width != v.width || b.height != v.height) return
        val inhalt = findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0) ?: return
        val d = android.graphics.drawable.BitmapDrawable(resources, b).apply { setBounds(0, 0, b.width, b.height) }
        try { v.overlay.add(d) } catch (e: Exception) { return }
        fotoZeigt = d
        inhalt.visibility = android.view.View.INVISIBLE    // erstes Bild: nur das Foto zeichnen (geht sofort)
        val c = android.view.Choreographer.getInstance()
        v.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                v.viewTreeObserver.removeOnPreDrawListener(this)
                // naechstes Bild: echter Startbildschirm darunter, zwei Bilder spaeter Foto weg
                c.postFrameCallback {
                    inhalt.visibility = android.view.View.VISIBLE
                    c.postFrameCallback { c.postFrameCallback { fotoWeg() } }
                }
                return true
            }
        })
        nachBildHandler.postDelayed({ fotoWeg() }, 1500)    // zur Sicherheit
    }

    private fun fotoWeg() {
        val d = fotoZeigt ?: return
        fotoZeigt = null
        try { window.decorView.overlay.remove(d) } catch (e: Exception) { }
        findViewById<android.view.ViewGroup>(android.R.id.content)?.getChildAt(0)?.visibility = android.view.View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        gestartet = true
        // erst NACH dem ersten Bild: so steht der Startbildschirm beim Hochwischen sofort da
        nachDemBild {
            if (!gestartet) return@nachDemBild
            lageStart()
            Musik.start(this)
            // Nach einem Update verbindet Android den Benachrichtigungs-Dienst manchmal nicht neu
            if (Musik.hatRecht(this) && Lauscher.verbunden == null) {
                try {
                    android.service.notification.NotificationListenerService.requestRebind(ComponentName(this, Lauscher::class.java))
                } catch (e: Exception) { }
            }
        }
    }

    private var gestartet = false
    private val nachBildHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Arbeit, die das Zurueckkommen zum Startbildschirm bremsen wuerde: erst nach dem ersten gezeichneten Bild + kurz warten */
    private fun nachDemBild(block: () -> Unit) {
        try {
            android.view.Choreographer.getInstance().postFrameCallback { nachBildHandler.postDelayed({ block() }, 300) }
        } catch (e: Exception) { nachBildHandler.postDelayed({ block() }, 400) }
    }

    override fun onStop() {
        super.onStop()
        gestartet = false
        lageStop()
        Musik.stop()
        z.privatSperrZaehler++
        z.musterBereitBis = 0L
        z.musterPunkt = false
    }

    private var kalenderZuletzt = 0L

    override fun onResume() {
        super.onResume()
        if (UntenWisch.heimZeit != 0L) {
            android.util.Log.d("LauncherUnten", "Launcher vorne nach ${android.os.SystemClock.uptimeMillis() - UntenWisch.heimZeit} ms")
            UntenWisch.heimZeit = 0L
        }
        Sichtbar.an = true; Sichtbar.vorne = true; Heim.zurueck()
        // Statusleiste sofort wieder weg (nicht erst nach dem ersten Bild)
        if (z.statusAus) try {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        } catch (e: Exception) { }
        AlphabetSkript.neuLesen = true
        // alles Weitere (Rand-Fenster, Mithoeren, Sensoren, Code-Bausteine, App-Liste, Kalender) erst nach dem
        // ersten Bild - vorher hielt es den Wechsel zum Startbildschirm auf
        nachDemBild {
            Heim.leicht = false                 // Unschaerfe / Glas / Wellen weich einblenden
            if (!Sichtbar.vorne) return@nachDemBild
            Randgesten.launcherVorne(true); if (Neigung.an(this)) Neigung.launcher.start(this)
            EigeneBausteine.alleAnhalten(true)
            Beat.starten(this)                  // falls Musik-Ring / Alphabet-Musik es brauchen (sonst passiert nichts)
            IconSpeicher.vorwaermen()           // Icons wieder in den Grafikspeicher, bevor man das Alphabet anfasst
            // App-Liste pruefen erst etwas spaeter - nicht genau dann, wenn man gleich uebers Alphabet wischt
            nachBildHandler.postDelayed({ if (Sichtbar.vorne) lader.wennNoetig() }, 2500)    // nur bei Aenderung oder nach 10 Minuten
            z.fortsetzen++
            Kalender.erlaubt = Kalender.hatRecht(this)
            // Kalender hoechstens alle 5 Minuten (die Startseite laedt ihn sonst ohnehin regelmaessig)
            if (z.kopf.termin && System.currentTimeMillis() - kalenderZuletzt > 5 * 60_000L) {
                kalenderZuletzt = System.currentTimeMillis(); Kalender.laden(this)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (!isChangingConfigurations) fotoMachen()     // Startbildschirm fuers Zurueckkommen fotografieren
        Sichtbar.an = false; Sichtbar.vorne = false; Randgesten.launcherVorne(false); Neigung.launcher.stop()
        Beat.pause()                        // Mithoeren sofort aus
        EigeneBausteine.alleAnhalten(false) // Code-Bausteine: Timer / Internet anhalten
    }

    /**
     * Hintergrundbild des Handys (z. B. Live-Wellen) nur hinter dem Launcher anzeigen, wenn es
     * auch durchscheinen soll. Sonst malt der Launcher alles selbst - und die Tapete dahinter
     * darf schlafen (Android meldet ihr dann "unsichtbar").
     */
    fun tapeteZeigen(an: Boolean) {
        if (tapeteAn == an) return
        tapeteAn = an
        try {
            if (an) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        } catch (e: Exception) { }
    }

    /**
     * Stuerzt der Launcher ab, waehrend eine neue Spielerei an ist (z. B. Glas-Uhr), wird die beim naechsten
     * Start ausgeschaltet - sonst wuerde er immer wieder beim Oeffnen abstuerzen.
     */
    private fun absturzSchutz() {
        val vorher = Thread.getDefaultUncaughtExceptionHandler()
        if (vorher is AbsturzSchutz) return
        Thread.setDefaultUncaughtExceptionHandler(AbsturzSchutz(applicationContext, vorher))
    }
    private class AbsturzSchutz(val ctx: Context, val vorher: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
                val th = org.json.JSONObject(p.getString("thema", "{}") ?: "{}")
                val uhr = th.optJSONObject("uhr")
                if (uhr != null && uhr.optString("material") == "glas") {
                    uhr.put("material", ""); th.put("uhr", uhr)
                    p.edit().putString("thema", th.toString()).commit()
                }
            } catch (x: Throwable) { }
            vorher?.uncaughtException(t, e)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ohneEinschiebenAlt()
        // vom Sperrbildschirm (lange gedrueckt): gleich die gewuenschte Einstellung oeffnen statt nur nach Hause
        val w = intent.getStringExtra("oeffne")
        if (w != null) z.oeffnenWunsch = w else z.heim++
    }

    override fun onDestroy() {
        super.onDestroy()
        lader.ende()
        if (z.aktivitaet === this) z.aktivitaet = null
    }

    // ---------------------------------------------------------------- Icons
    fun iconsNeuLaden(keys: Set<String>?) = lader.iconsNeuLaden(keys)

    // ---------------------------------------------------------------- Apps
    private fun oeffne(intent: Intent) {
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    fun starte(app: AppEintrag) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(app.paket, app.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opt = StartRahmen.optionen(this, intent)
        // der App die volle Leistung lassen: eigene Animationen / Mithoeren sofort anhalten (onResume schaltet wieder an)
        Sichtbar.an = false; Beat.pause()
        try { startActivity(intent, opt); vonApp = true } catch (e: Exception) { Sichtbar.an = true; lader.aktualisieren() }
    }

    fun info(paket: String) =
        oeffne(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", paket, null)))

    @Suppress("DEPRECATION")
    fun deinstallieren(paket: String) = oeffne(Intent(Intent.ACTION_DELETE, Uri.parse("package:$paket")))

    /** Nach unten wischen: Benachrichtigungen aufklappen (wie Niagara) */
    @SuppressLint("WrongConstant")
    fun benachrichtigungen() {
        // Einstellungen → Anzeige → "Runterwischen öffnet Benachrichtigungen" (ab Werk aus)
        if (!getSharedPreferences("launcher", MODE_PRIVATE).getBoolean("runterBenachr", false)) return
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (e: Exception) { }
    }

    /** Androids Schnelleinstellungen (die grossen Kacheln) aufklappen - fuer Flugmodus und Bluetooth */
    @SuppressLint("WrongConstant")
    fun schnelleinstellungen() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandSettingsPanel").invoke(sb)
        } catch (e: Exception) {
            oeffne(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS))
        }
    }

    fun uhr() = oeffne(Intent(AlarmClock.ACTION_SHOW_ALARMS))

    fun kalenderOeffnen() =
        oeffne(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")))

    /** Wellen als Live-Hintergrund des Handys einstellen (Android zeigt dafuer seine Vorschau) */
    fun wellenTapete() {
        try {
            startActivity(Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(this, WellenTapete::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            oeffne(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    fun standardLauncher() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            oeffne(Intent(Settings.ACTION_SETTINGS))
        }
    }

    /** Diese App als Startbildschirm: direkt fragen (Android-Dialog), sonst Androids Auswahl */
    fun alsStandardFragen() {
        if (Build.VERSION.SDK_INT >= 29) try {
            val rm = getSystemService(android.app.role.RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) && !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME), 4242)
                return
            }
        } catch (e: Exception) { }
        standardLauncher()
    }

    /** alle Startbildschirm-Apps auf dem Handy: (Paket, Name) */
    fun startbildschirmApps(): List<Pair<String, String>> = try {
        @Suppress("DEPRECATION")
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != "com.android.settings" && !it.first.contains("fallbackhome", true) }
            .distinctBy { it.first }
    } catch (e: Exception) { emptyList() }

    fun standardPaket(): String? = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                                                                  PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName

    fun istStandard(): Boolean {
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                                               PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    // ---------------------------------------------------------------- Rechte
    fun kalenderErlauben() {
        if (Kalender.hatRecht(this)) { Kalender.laden(this); return }
        kalenderRecht.launch(Manifest.permission.READ_CALENDAR)
    }

    fun benachrichtigungsZugriff() {
        Toast.makeText(this, Sprache.tr("„${applicationInfo.loadLabel(packageManager)}“ einschalten"), Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                              ComponentName(this, Lauscher::class.java).flattenToString())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { }
        }
        oeffne(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    // ---------------------------------------------------------------- Eigene Schrift
    fun eigeneSchrift() {
        try { schriftWahl.launch(arrayOf("*/*")) } catch (e: Exception) { }
    }

    private fun schriftUebernehmen(uri: Uri) {
        Thread {
            var name = "schrift.ttf"
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            } catch (e: Exception) { }
            val endung = name.substringAfterLast('.', "").lowercase()
            val meldung: String
            if (endung != "ttf" && endung != "otf") {
                meldung = "Bitte eine .ttf- oder .otf-Datei wählen"
            } else {
                val ordner = File(filesDir, "fonts").apply { mkdirs() }
                val ziel = File(ordner, name.replace(Regex("[^A-Za-z0-9._ -]"), "_"))
                meldung = try {
                    contentResolver.openInputStream(uri)!!.use { ein -> ziel.outputStream().use { ein.copyTo(it) } }
                    Typeface.createFromFile(ziel)            // wirft, wenn die Datei keine Schrift ist
                    runOnUiThread {
                        z.themaSetzen(z.thema.copy(schrift = "datei:" + ziel.path))
                        Schriften.stand.intValue++
                        z.fortsetzen++
                    }
                    "Schrift „${ziel.nameWithoutExtension}“ übernommen"
                } catch (e: Exception) {
                    ziel.delete()
                    "Die Datei ist keine gültige Schrift"
                }
            }
            runOnUiThread { Toast.makeText(this, Sprache.tr(meldung), Toast.LENGTH_SHORT).show() }
        }.start()
    }

    // ---------------------------------------------------------------- App-Kurzbefehle
    /** Kurzbefehle einer App (geht nur, wenn dieser Launcher der Startbildschirm ist). Im Hintergrund aufrufen */
    fun kurzbefehle(paket: String): List<Kurzbefehl> {
        if (Build.VERSION.SDK_INT < 25) return emptyList()
        return try {
            val la = getSystemService(LauncherApps::class.java) ?: return emptyList()
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery().setPackage(paket).setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            val dpi = resources.displayMetrics.densityDpi
            la.getShortcuts(q, Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled }
                .sortedWith(compareBy({ if (it.isDeclaredInManifest) 0 else 1 }, { it.rank }))
                .distinctBy { it.id }
                .take(5)
                .map { si ->
                    val bild = try { la.getShortcutIconDrawable(si, dpi)?.let { zeichne(it, 72).asImageBitmap() } } catch (e: Exception) { null }
                    Kurzbefehl(si, (si.shortLabel ?: si.longLabel ?: "").toString(), bild)
                }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Letzte Chats einer Messenger-App (WhatsApp, Signal ...): die Apps melden ihre aktuellen
     * Unterhaltungen als Kurzbefehle an Android - die kann der Startbildschirm lesen und oeffnen.
     * Nur Name + Bild, keine Nachrichten. Im Hintergrund aufrufen.
     */
    fun kontakte(paket: String, anzahl: Int = 5): List<Kurzbefehl> {
        if (Build.VERSION.SDK_INT < 25) return emptyList()
        return try {
            val la = getSystemService(LauncherApps::class.java) ?: return emptyList()
            if (!la.hasShortcutHostPermission()) return emptyList()
            var flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            if (Build.VERSION.SDK_INT >= 30) flags = flags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
            val q = LauncherApps.ShortcutQuery().setPackage(paket).setQueryFlags(flags)
            val dpi = resources.displayMetrics.densityDpi
            la.getShortcuts(q, Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled && !it.isDeclaredInManifest }      // feste Eintraege wie "Kamera" weglassen
                .distinctBy { it.id }
                .sortedByDescending { it.lastChangedTimestamp }          // zuletzt aktive Unterhaltung zuerst
                .take(anzahl)
                .map { si ->
                    val bild = try { la.getShortcutIconDrawable(si, dpi)?.let { zeichne(it, 96).asImageBitmap() } } catch (e: Exception) { null }
                    Kurzbefehl(si, (si.shortLabel ?: si.longLabel ?: "").toString(), bild)
                }
        } catch (e: Exception) { emptyList() }
    }

    /** Chat-Kurzbefehl nach Namen suchen (ohne Bilder - schnell genug zum Antippen) */
    fun kontaktFinden(paket: String, name: String): Kurzbefehl? {
        if (Build.VERSION.SDK_INT < 25) return null
        return try {
            val la = getSystemService(LauncherApps::class.java) ?: return null
            if (!la.hasShortcutHostPermission()) return null
            var flags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            if (Build.VERSION.SDK_INT >= 30) flags = flags or LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED
            val q = LauncherApps.ShortcutQuery().setPackage(paket).setQueryFlags(flags)
            la.getShortcuts(q, Process.myUserHandle()).orEmpty()
                .filter { it.isEnabled && !it.isDeclaredInManifest }
                .sortedByDescending { it.lastChangedTimestamp }
                .firstOrNull { (it.shortLabel ?: it.longLabel ?: "").toString().trim().equals(name.trim(), ignoreCase = true) }
                ?.let { Kurzbefehl(it, name, null) }
        } catch (e: Exception) { null }
    }

    fun kurzbefehlStarten(k: Kurzbefehl) {
        if (Build.VERSION.SDK_INT < 25) return
        try {
            getSystemService(LauncherApps::class.java)?.startShortcut(k.info as ShortcutInfo, null, null)
            vonApp = true
        } catch (e: Exception) {
            Toast.makeText(this, Sprache.tr("Kurzbefehl geht gerade nicht"), Toast.LENGTH_SHORT).show()
        }
    }

    /** Widgets der Vorversion bei Android abmelden und alte Einstellungen loeschen (nur einmal) */
    private fun widgetsAltAufraeumen() {
        if (intern.getBoolean("widgetsWeg", false)) return
        try { android.appwidget.AppWidgetHost(applicationContext, 1024).deleteHost() } catch (e: Exception) { }
        getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
            .remove("stapel").remove("stapelHoehe").remove("popupWidgets").remove("ordner").apply()
        intern.edit().putBoolean("widgetsWeg", true).remove("wartendId").remove("wartendZiel").apply()
    }

}
