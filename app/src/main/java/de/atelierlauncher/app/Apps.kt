package de.atelierlauncher.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import android.util.Xml
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

// ============================================================================
//  Apps laden, Icons (Original, Einfarbig, Punkte, Icon-Pakete) und Zwischenspeicher
// ============================================================================

private const val ICON_PX = 128

/** Ein fertiges Icon. maske = weisse Form, wird in Theme-Farbe eingefaerbt (Einfarbig) */
class IconBild(val bild: ImageBitmap, val maske: Boolean)

/** Icons getrennt von der Liste: Namen und Alphabet sind sofort da, Icons kommen nach */
object IconSpeicher {
    val icons = mutableStateMapOf<String, IconBild>()
    val punkte = mutableStateMapOf<String, Color>()     // Punkt-Farbe je App (Stil "Punkte")
}

// ---------------------------------------------------------------- Icon-Pakete
data class IconPaket(val name: String, val paket: String)

/** Liest Icon-Pakete im ADW/Nova-Format (appfilter.xml), wie sie im Play Store zu finden sind */
object IconPakete {
    private val AKTIONEN = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME", "com.gau.go.launcherex.theme", "app.lawnchair.icons.THEMED_ICON",
    )
    private val zuordnungen = HashMap<String, Map<String, String>>()
    private val listen = HashMap<String, List<String>>()
    private val bilder = LruCache<String, ImageBitmap>(260)

    fun installiert(ctx: Context): List<IconPaket> {
        val pm = ctx.packageManager
        val gefunden = LinkedHashMap<String, IconPaket>()
        for (a in AKTIONEN) {
            try {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA).forEach { ri ->
                    val p = ri.activityInfo.packageName
                    if (p !in gefunden) gefunden[p] = IconPaket(ri.loadLabel(pm).toString(), p)
                }
            } catch (e: Exception) { }
        }
        return gefunden.values.sortedBy { it.name.lowercase() }
    }

    private fun parser(ctx: Context, paket: String, datei: String): XmlPullParser? {
        return try {
            val res = ctx.packageManager.getResourcesForApplication(paket)
            @Suppress("DiscouragedApi")
            val id = res.getIdentifier(datei, "xml", paket)
            if (id != 0) res.getXml(id)
            else {
                val ein = ctx.createPackageContext(paket, 0).assets.open("$datei.xml")
                Xml.newPullParser().apply { setInput(ein, "UTF-8") }
            }
        } catch (e: Exception) { null }
    }

    /** App-Schluessel (paket/activity) -> Bildname im Paket */
    @Synchronized
    fun zuordnung(ctx: Context, paket: String): Map<String, String> = zuordnungen.getOrPut(paket) {
        val m = HashMap<String, String>()
        val p = parser(ctx, paket, "appfilter") ?: return@getOrPut m
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG || p.name != "item") continue
                val comp = p.getAttributeValue(null, "component") ?: continue
                val bild = p.getAttributeValue(null, "drawable") ?: continue
                val innen = comp.substringAfter("{", "").substringBefore("}")
                val pkg = innen.substringBefore("/"); var akt = innen.substringAfter("/", "")
                if (pkg.isEmpty() || akt.isEmpty()) continue
                if (akt.startsWith(".")) akt = pkg + akt
                m.putIfAbsent("$pkg/$akt", bild)
            }
        } catch (e: Exception) { }
        m
    }

    /** Alle Bildnamen eines Pakets (fuer den Icon-Assistenten) */
    @Synchronized
    fun alleBilder(ctx: Context, paket: String): List<String> = listen.getOrPut(paket) {
        val namen = LinkedHashSet<String>()
        parser(ctx, paket, "drawable")?.let { p ->
            try {
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "item")
                        p.getAttributeValue(null, "drawable")?.let { namen += it }
                }
            } catch (e: Exception) { }
        }
        if (namen.isEmpty()) namen += zuordnung(ctx, paket).values
        namen.sorted()
    }

    fun drawable(ctx: Context, paket: String, name: String): Drawable? = try {
        val res = ctx.packageManager.getResourcesForApplication(paket)
        @Suppress("DiscouragedApi")
        val id = res.getIdentifier(name, "drawable", paket)
        if (id == 0) null else androidx.core.content.res.ResourcesCompat.getDrawable(res, id, null)
    } catch (e: Exception) { null }

    /** Kleines Vorschaubild (fuer den Assistenten), zwischengespeichert */
    fun vorschau(ctx: Context, paket: String, name: String): ImageBitmap? {
        val key = "$paket:$name"
        bilder.get(key)?.let { return it }
        val b = drawable(ctx, paket, name)?.let { zeichne(it, 96) }?.asImageBitmap() ?: return null
        bilder.put(key, b)
        return b
    }

    fun vergessen() { synchronized(this) { zuordnungen.clear(); listen.clear() }; bilder.evictAll() }
}

/** Drawable in eine Bitmap zeichnen */
fun zeichne(d: Drawable, px: Int): Bitmap = d.toBitmap(px, px, Bitmap.Config.ARGB_8888)

// ---------------------------------------------------------------- Laden
/**
 * Laedt die App-Liste und die Icons im Hintergrund (ein einziger Arbeits-Thread,
 * damit sich nichts ueberholt). Beim Start kommt alles aus dem Zwischenspeicher.
 */
class AppLader(private val akt: MainActivity, private val z: Zustand) {
    private val pm = akt.packageManager
    private val arbeiter = Executors.newSingleThreadExecutor()
    /** Activity weg -> Arbeits-Thread beenden (haelt sonst die alte Activity fest) */
    fun ende() {
        try { akt.getSystemService(LauncherApps::class.java)?.unregisterCallback(paketMelder) } catch (e: Exception) { }
        arbeiter.shutdownNow()
    }
    @Volatile private var generation = 0
    @Volatile private var laedt = false
    @Volatile private var nochmal = false          // waehrend des Ladens kam eine neue Aenderung
    @Volatile private var geaendert = false        // Android hat eine App-Aenderung gemeldet
    @Volatile private var zuletzt = 0L             // letzte Abfrage (SystemClock.elapsedRealtime)
    private val listenDatei = File(akt.filesDir, "appliste.txt")
    private val iconWurzel = File(akt.filesDir, "icons")

    /**
     * Android meldet installierte / entfernte / aktualisierte Apps. Ist der Launcher zu sehen,
     * sofort neu laden, sonst nur vormerken (naechstes onResume laedt dann).
     */
    private val paketMelder = object : LauncherApps.Callback() {
        private fun melden() {
            if (Sichtbar.an) aktualisieren() else geaendert = true
        }
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = melden()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = melden()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = melden()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = melden()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = melden()
    }

    init {
        // alte Icon-Dateien der ersten Version (direkt im Ordner) aufraeumen
        try { iconWurzel.listFiles { f -> f.isFile }?.forEach { it.delete() } } catch (e: Exception) { }
        try {
            akt.getSystemService(LauncherApps::class.java)?.registerCallback(paketMelder, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { geaendert = true }
    }

    /** Beim Zurueckkehren: nur neu abfragen, wenn Android eine Aenderung gemeldet hat oder es laenger her ist */
    fun wennNoetig() {
        if (geaendert || zuletzt == 0L || SystemClock.elapsedRealtime() - zuletzt > 10 * 60_000L) aktualisieren()
    }

    private fun ordnerFuer(stil: String) = File(iconWurzel, when {
        stil.startsWith("paket:") -> "p_" + stil.removePrefix("paket:").replace(Regex("[^A-Za-z0-9]"), "_")
        stil == "einfarbig" -> "mono"
        stil == "motiv" -> "motiv"
        stil == "umriss" || stil == "skizze" || stil == "kintsugi" -> "umriss2"          // neuer Ordner: duennere Linien (alte Bilder nicht weiterverwenden)
        else -> "orig"
    }).apply { mkdirs() }

    private fun dateiName(key: String) = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** Sofort beim Start: letzte bekannte Liste und Icons von der Platte */
    fun start() {
        z.rohApps = try {
            listenDatei.readLines().mapNotNull { zeile ->
                val t = zeile.split("\t")
                if (t.size == 3) AppEintrag(t[2], t[0], t[1]) else null
            }
        } catch (e: Exception) { emptyList() }
        val liste = z.rohApps
        val gen = generation
        // Prozess lebt noch (nur die Activity war weg): Icons sind schon im Speicher
        if (IconSpeicher.icons.isNotEmpty()) return
        // erst der Schnellspeicher (eine Datei, roh), sonst die einzelnen Bilder
        arbeiter.execute { if (!buendelLesen(liste, gen)) icons(liste, null, gen, nurPlatte = true) }
    }

    // ---------------------------------------------------------------- Schnellspeicher
    // Alle Icons eines Stils in EINER Datei, als rohe Pixel (kein PNG-Entpacken) -> beim Kaltstart
    // sind alle Icons mit einem Lesevorgang da und kommen auf einmal (statt in 16er-Haeppchen).
    private val BUENDEL_KENNUNG = 0x4F534B31          // "OSK1"
    private fun buendelDatei(stil: String) = File(ordnerFuer(stil), "alle.bin")
    /** seit dem letzten Schreiben wurde ein Icon neu gemalt -> Schnellspeicher neu schreiben */
    @Volatile private var neuGemalt = false

    private fun buendelLesen(apps: List<AppEintrag>, gen: Int): Boolean {
        val stil = z.thema.icons
        if (stil == "keine" || stil == "swiss" || stil == "punkte") return false
        val f = buendelDatei(stil)
        if (!f.exists()) return false
        val gueltig = apps.mapTo(HashSet()) { it.schluessel }
        val liste = ArrayList<Pair<String, IconBild>>()
        try {
            DataInputStream(BufferedInputStream(FileInputStream(f), 1 shl 16)).use { ein ->
                if (ein.readInt() != BUENDEL_KENNUNG) return false
                val n = ein.readInt()
                var puffer = ByteArray(0)
                repeat(n) {
                    if (gen != generation) return false
                    val key = ein.readUTF(); val maske = ein.readBoolean()
                    val w = ein.readInt(); val h = ein.readInt()
                    if (w <= 0 || h <= 0 || w > 1024 || h > 1024) return false
                    val groesse = w * h * 4
                    if (puffer.size != groesse) puffer = ByteArray(groesse)
                    ein.readFully(puffer)
                    if (key in gueltig) {
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(puffer))
                        bmp.prepareToDraw()                       // schon mal zur Grafikkarte
                        liste += key to IconBild(bmp.asImageBitmap(), maske)
                    }
                }
            }
        } catch (e: Exception) { return false }
        if (liste.isEmpty()) return false
        akt.runOnUiThread {
            if (gen != generation) return@runOnUiThread
            liste.forEach { (k, v) -> if (!IconSpeicher.icons.containsKey(k)) IconSpeicher.icons[k] = v }
        }
        return true
    }

    /** nach einem vollen Durchlauf: aktuellen Stand in den Schnellspeicher (nur wenn sich etwas geaendert hat) */
    private fun buendelSchreiben(gen: Int) {
        val stil = z.thema.icons
        if (stil == "keine" || stil == "swiss" || stil == "punkte") return
        val f = buendelDatei(stil)
        if (!neuGemalt && f.exists()) return
        // ueber den Haupt-Thread, damit die letzten abgelieferten Icons sicher dabei sind
        akt.runOnUiThread {
            if (gen != generation) return@runOnUiThread
            val gueltig = z.rohApps.mapTo(HashSet()) { it.schluessel }
            val schnapp = IconSpeicher.icons.filterKeys { it in gueltig }.toList()
            neuGemalt = false
            try { arbeiter.execute {
                if (gen != generation) return@execute
                val tmp = File(f.parentFile, "alle.bin.neu")
                try {
                    DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { aus ->
                        aus.writeInt(BUENDEL_KENNUNG)
                        val teile = schnapp.mapNotNull { (k, v) ->
                            val b = try { v.bild.asAndroidBitmap() } catch (e: Exception) { null }
                            if (b == null || b.config != Bitmap.Config.ARGB_8888 || b.isRecycled) null else Triple(k, v.maske, b)
                        }
                        aus.writeInt(teile.size)
                        var puffer: ByteBuffer? = null
                        for ((k, m, b) in teile) {
                            aus.writeUTF(k); aus.writeBoolean(m); aus.writeInt(b.width); aus.writeInt(b.height)
                            val groesse = b.width * b.height * 4
                            val pb = puffer?.takeIf { it.capacity() == groesse } ?: ByteBuffer.allocate(groesse).also { puffer = it }
                            pb.rewind(); b.copyPixelsToBuffer(pb)
                            aus.write(pb.array(), 0, groesse)
                        }
                    }
                    if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                } catch (e: Exception) { tmp.delete(); neuGemalt = true }
            } } catch (e: Exception) { }
        }
    }

    /** Apps bei Android abfragen (bei jedem Zurueckkehren zum Startbildschirm) */
    fun aktualisieren() {
        if (arbeiter.isShutdown) return
        if (laedt) { nochmal = true; return }
        laedt = true
        geaendert = false
        zuletzt = SystemClock.elapsedRealtime()
        try { arbeiter.execute {
            try {
                zeiten.clear()          // Update-Zeiten neu holen -> geaenderte App-Icons werden neu gezeichnet
                @Suppress("DEPRECATION")
                val gefunden = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .filter { it.activityInfo.packageName != akt.packageName }
                val liste = gefunden.map { ri ->
                    AppEintrag(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri.activityInfo.name)
                }.sortedBy { it.name.lowercase() }
                val alt = z.rohApps.sortedBy { it.name.lowercase() }
                val gleich = alt.size == liste.size &&
                    alt.indices.all { alt[it].schluessel == liste[it].schluessel && alt[it].name == liste[it].name }
                val gueltig = liste.mapTo(HashSet()) { it.schluessel }
                akt.runOnUiThread {
                    if (!gleich) z.rohApps = liste
                    z.aufraeumen(gueltig)
                }
                if (!gleich) try {
                    listenDatei.writeText(liste.joinToString("\n") { "${it.paket}\t${it.activity}\t${it.name}" })
                } catch (e: Exception) { }
                icons(liste, null, generation, nurPlatte = false)
            } finally {
                laedt = false
                // kam waehrend des Ladens eine Aenderung, noch einmal laden
                if (nochmal) { nochmal = false; akt.runOnUiThread { aktualisieren() } }
            }
        } } catch (e: Exception) { laedt = false }      // Arbeits-Thread schon beendet
    }

    /** Icons neu machen: null = alle (z. B. anderer Icon-Stil), sonst nur diese Apps */
    fun iconsNeuLaden(keys: Set<String>?) {
        if (keys == null) {
            generation++
            IconSpeicher.icons.clear()
            IconSpeicher.punkte.clear()
            IconPakete.vergessen()
        }
        val gen = generation
        arbeiter.execute { icons(z.rohApps, keys, gen, nurPlatte = false, erzwingen = keys != null) }
    }

    private fun icons(apps: List<AppEintrag>, nur: Set<String>?, gen: Int, nurPlatte: Boolean, erzwingen: Boolean = false) {
        val stil = z.thema.icons
        if (stil == "keine" || stil == "swiss" || apps.isEmpty()) return     // Swiss: gezeichnet, nichts zu laden
        val eigene = z.eigeneIcons
        val paket = if (stil.startsWith("paket:")) stil.removePrefix("paket:") else null
        val zuordnung = paket?.let { IconPakete.zuordnung(akt, it) }
        val paketZeit = paket?.let { zeitVon(it) } ?: 0L
        val ordner = ordnerFuer(stil)
        val fertig = ArrayList<Pair<String, IconBild?>>()
        val farben = ArrayList<Pair<String, Color>>()

        fun abliefern() {
            if (fertig.isEmpty() && farben.isEmpty()) return
            val f = ArrayList(fertig); val c = ArrayList(farben)
            fertig.clear(); farben.clear()
            akt.runOnUiThread {
                if (gen != generation) return@runOnUiThread
                f.forEach { (k, v) -> if (v == null) IconSpeicher.icons.remove(k) else IconSpeicher.icons.put(k, v) }
                c.forEach { (k, v) -> IconSpeicher.punkte[k] = v }
            }
        }

        for (app in apps) {
            if (gen != generation) return
            val key = app.schluessel
            if (nur != null && key !in nur) continue
            try {
                // eigenes Icon aus dem Icon-Assistenten hat Vorrang
                val eigen = eigene[key]
                if (eigen != null) {
                    if (nurPlatte) continue
                    val teile = eigen.removePrefix("paket:").split(":", limit = 2)
                    val d = if (teile.size == 2) IconPakete.drawable(akt, teile[0], teile[1]) else null
                    if (d != null) { fertig += key to IconBild(zeichne(d, ICON_PX).asImageBitmap(), false); neuGemalt = true; continue }
                }
                val datei = File(ordner, dateiName(key) + ".png")
                val maskenDatei = File(ordner, dateiName(key) + ".m.png")
                val vorhanden = if (maskenDatei.exists()) maskenDatei else datei
                // beim Start (nurPlatte) nicht bei Android nachfragen, ob die App neuer ist - das kostet
                // pro App eine Abfrage; das prueft gleich danach der normale Durchlauf
                val veraltet = erzwingen || !vorhanden.exists() ||
                    (!nurPlatte && vorhanden.lastModified() < maxOf(zeitVon(app.paket), paketZeit))
                if (veraltet && nurPlatte) continue
                if (!veraltet && !erzwingen && nur == null && !nurPlatte && schonDa(key, stil)) continue

                var maske = vorhanden === maskenDatei
                val bmp: Bitmap = if (!veraltet) {
                    BitmapFactory.decodeFile(vorhanden.path) ?: continue
                } else {
                    val (neu, istMaske) = erzeuge(app, stil, paket, zuordnung)
                    maske = istMaske
                    neuGemalt = true
                    val ziel = if (istMaske) maskenDatei else datei
                    (if (istMaske) datei else maskenDatei).delete()
                    try { FileOutputStream(ziel).use { neu.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Exception) { }
                    neu
                }
                if (stil == "punkte") {
                    farben += key to hauptfarbe(bmp)
                    if (eigen == null) fertig += key to null
                } else {
                    fertig += key to IconBild(bmp.asImageBitmap(), maske)
                }
            } catch (e: Exception) { }
            if (fertig.size + farben.size >= 16) abliefern()
        }
        abliefern()
        // voller Durchlauf fertig -> Schnellspeicher fuer den naechsten Kaltstart
        if (!nurPlatte && nur == null && gen == generation) buendelSchreiben(gen)
        else if (nur != null && !nurPlatte) { neuGemalt = true; buendelSchreiben(gen) }
    }

    private fun schonDa(key: String, stil: String) =
        if (stil == "punkte") IconSpeicher.punkte.containsKey(key) else IconSpeicher.icons.containsKey(key)

    private val zeiten = HashMap<String, Long>()
    private fun zeitVon(paket: String): Long = zeiten.getOrPut(paket) {
        try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(paket, 0).lastUpdateTime
        } catch (e: Exception) { 0L }
    }.also { if (zeiten.size > 600) zeiten.clear() }

    /** Icon im gewuenschten Stil zeichnen. Ergebnis: Bild und ob es eine Maske ist */
    private fun erzeuge(app: AppEintrag, stil: String, paket: String?, zuordnung: Map<String, String>?): Pair<Bitmap, Boolean> {
        val original: Drawable = try {
            pm.getActivityIcon(ComponentName(app.paket, app.activity))
        } catch (e: Exception) { pm.defaultActivityIcon }

        if (paket != null && zuordnung != null) {
            zuordnung[app.schluessel]?.let { name ->
                IconPakete.drawable(akt, paket, name)?.let { return zeichne(it, ICON_PX) to false }
            }
            // gleiche App, andere Activity im Paket gefunden?
            zuordnung.entries.firstOrNull { it.key.startsWith(app.paket + "/") }?.let { (_, name) ->
                IconPakete.drawable(akt, paket, name)?.let { return zeichne(it, ICON_PX) to false }
            }
            return zeichne(original, ICON_PX) to false
        }
        if (stil == "umriss" || stil == "skizze" || stil == "kintsugi") return umrissVon(original) to true
        if (stil == "motiv") return motivVon(original) to true
        if (stil == "einfarbig") {
            if (Build.VERSION.SDK_INT >= 33 && original is AdaptiveIconDrawable) {
                original.monochrome?.let { mono ->
                    val m = mono.mutate()
                    m.setTint(android.graphics.Color.WHITE)
                    return zeichne(m, ICON_PX) to true
                }
            }
            // App hat kein einfarbiges Icon: Original in Graustufen
            val farbig = zeichne(original, ICON_PX)
            val grau = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            Canvas(grau).drawBitmap(farbig, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            })
            return grau to false
        }
        return zeichne(original, ICON_PX) to false
    }

    /**
     * Umriss des Original-Logos (Swiss-Stil): das bekannte Logo bleibt erkennbar, wird aber nur als
     * Linie gezeichnet. Quelle: einfarbiges Logo der App (Android 13+), sonst der Vordergrund des
     * Icons ohne Hintergrund, sonst das ganze Icon. Kanten aus Form UND Helligkeit, gleich dick.
     */
    private fun umrissVon(original: Drawable): Bitmap {
        val n = ICON_PX
        // Ebene von Adaptive-Icons ist 108 dp, sichtbar sind die mittleren 72 -> 1,5x gross zeichnen, Mitte nehmen
        fun ebene(d: Drawable, weiss: Boolean): Bitmap {
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val gross = (n * 1.5f).toInt(); val rand = (gross - n) / 2
            val dd = d.mutate()
            if (weiss) dd.setTint(android.graphics.Color.WHITE)
            dd.setBounds(-rand, -rand, gross - rand, gross - rand)
            dd.draw(c)
            return b
        }
        val quelle: Bitmap = when {
            Build.VERSION.SDK_INT >= 33 && original is AdaptiveIconDrawable && original.monochrome != null ->
                ebene(original.monochrome!!, true)
            Build.VERSION.SDK_INT >= 26 && original is AdaptiveIconDrawable && original.foreground != null ->
                ebene(original.foreground, false)
            else -> zeichne(original, n)
        }
        val px = IntArray(n * n)
        quelle.getPixels(px, 0, n, 0, 0, n, n)
        val alpha = FloatArray(n * n) { ((px[it] ushr 24) and 0xFF) / 255f }
        val hell = FloatArray(n * n) {
            val c = px[it]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)) / 255f * alpha[it]
        }
        fun sobel(v: FloatArray, x: Int, y: Int): Float {
            fun g(i: Int, j: Int) = v[(y + j).coerceIn(0, n - 1) * n + (x + i).coerceIn(0, n - 1)]
            val gx = -g(-1, -1) - 2 * g(-1, 0) - g(-1, 1) + g(1, -1) + 2 * g(1, 0) + g(1, 1)
            val gy = -g(-1, -1) - 2 * g(0, -1) - g(1, -1) + g(-1, 1) + 2 * g(0, 1) + g(1, 1)
            return kotlin.math.sqrt(gx * gx + gy * gy)
        }
        // Kante: Rand der Form, dazu deutliche Helligkeitsspruenge im Logo
        val kante = BooleanArray(n * n)
        for (y in 0 until n) for (x in 0 until n) {
            val i = y * n + x
            kante[i] = sobel(alpha, x, y) > 0.9f || sobel(hell, x, y) > 0.55f
        }
        // duenne Linie: nur die Kante selbst (nicht verdicken), weich ausgeblendet
        val out = IntArray(n * n)
        for (i in 0 until n * n) out[i] = if (kante[i]) android.graphics.Color.WHITE else 0
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /**
     * Nur das Motiv, ohne Hintergrund, reines Schwarz/Weiss (wird in der Textfarbe eingefaerbt):
     * 1. einfarbiges Logo der App (Android 13+), 2. Vordergrund des Icons ohne Hintergrund-Ebene,
     * 3. altes Icon: was sich deutlich von der Hintergrundfarbe abhebt. Keine Zwischentoene.
     */
    private fun motivVon(original: Drawable): Bitmap {
        val n = ICON_PX
        fun ebene(d: Drawable): Bitmap {
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
            val gross = (n * 1.5f).toInt(); val rand = (gross - n) / 2
            val dd = d.mutate()
            dd.setBounds(-rand, -rand, gross - rand, gross - rand)
            dd.draw(Canvas(b))
            return b
        }
        val px = IntArray(n * n)
        val maske: BooleanArray
        if (Build.VERSION.SDK_INT >= 33 && original is AdaptiveIconDrawable && original.monochrome != null) {
            ebene(original.monochrome!!).getPixels(px, 0, n, 0, 0, n, n)
            maske = BooleanArray(n * n) { ((px[it] ushr 24) and 0xFF) > 110 }
        } else {
            val vorne = Build.VERSION.SDK_INT >= 26 && original is AdaptiveIconDrawable && original.foreground != null
            (if (vorne) ebene((original as AdaptiveIconDrawable).foreground) else zeichne(original, n)).getPixels(px, 0, n, 0, 0, n, n)
            // haeufigste Farbe der deckenden Pixel = Hintergrund des Logos (bei Vordergrund-Ebenen oft eine Flaeche)
            val zaehl = HashMap<Int, Int>()
            for (c in px) if (((c ushr 24) and 0xFF) > 200) { val q = c and 0xF0F0F0; zaehl[q] = (zaehl[q] ?: 0) + 1 }
            val deckend = zaehl.values.sum()
            // nur wenn das Bild wirklich eine volle Flaeche hat (sonst ist die haeufigste Farbe das Motiv selbst)
            val voll = deckend > n * n * (if (vorne) 0.8f else 0.5f)
            val grund = if (voll) zaehl.maxByOrNull { it.value }?.key else null
            fun abstand(a: Int, b: Int): Int {
                val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF); val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF); val db = (a and 0xFF) - (b and 0xFF)
                return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
            }
            maske = BooleanArray(n * n) {
                val c = px[it]
                ((c ushr 24) and 0xFF) > 110 && (grund == null || abstand(c and 0xF0F0F0, grund) > 90)
            }
        }
        // nichts uebrig (z. B. Motiv in Hintergrundfarbe): dann die Form selbst
        if (maske.none { it }) for (i in 0 until n * n) maske[i] = ((px[i] ushr 24) and 0xFF) > 110
        val out = IntArray(n * n) { if (maske[it]) android.graphics.Color.WHITE else 0 }
        return Bitmap.createBitmap(out, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Kraeftigste Farbe eines Icons (fuer die Punkte) */
    private fun hauptfarbe(b: Bitmap): Color {
        val klein = Bitmap.createScaledBitmap(b, 16, 16, true)
        val summe = Array(12) { DoubleArray(4) }   // je Farbton: Gewicht, r, g, b
        var gr = 0.0; var gg = 0.0; var gb = 0.0; var gn = 0
        val hsv = FloatArray(3)
        for (y in 0 until 16) for (x in 0 until 16) {
            val c = klein.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 140) continue
            val r = android.graphics.Color.red(c); val g = android.graphics.Color.green(c); val bl = android.graphics.Color.blue(c)
            gr += r; gg += g; gb += bl; gn++
            android.graphics.Color.colorToHSV(c, hsv)
            val w = (hsv[1] * hsv[2]).toDouble()
            if (w < 0.12) continue
            val s = summe[(hsv[0] / 30f).toInt().coerceIn(0, 11)]
            s[0] += w; s[1] += r * w; s[2] += g * w; s[3] += bl * w
        }
        val beste = summe.maxByOrNull { it[0] }
        return if (beste != null && beste[0] > 2.0)
            Color((beste[1] / beste[0]).toInt(), (beste[2] / beste[0]).toInt(), (beste[3] / beste[0]).toInt())
        else if (gn > 0) Color((gr / gn).toInt(), (gg / gn).toInt(), (gb / gn).toInt())
        else Color.Gray
    }
}

// ---------------------------------------------------------------- Anzeige
/** App-Icon im eingestellten Stil (solange es laedt: freier Platz gleicher Groesse) */
@Composable
fun AppIcon(key: String, groesse: Dp, transparenz: Float = 1f) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val stil = z.thema.icons
    val bild = IconSpeicher.icons[key]
    val mod = Modifier.size(groesse).graphicsLayer { alpha = transparenz }
    if (stil == "swiss") { Box(mod) { SwissIcon(key, groesse) }; return }
    // Umriss: Linien des Original-Logos in Textfarbe, ohne Kreis dahinter
    // Gezeichnet: Logo-Linien in Tinte, leicht verbogen, in einem handgemalten Kreis
    // Kintsugi: Goldring, darin das Logo - zerbrochen und mit Gold gekittet
    if (stil == "kintsugi") { KintsugiIcon(bild?.bild, groesse, key.hashCode(), Modifier.graphicsLayer { alpha = transparenz }); return }
    if (stil == "skizze") {
        Box(mod.skizzeRahmen(kreis = true, saat = key.hashCode()), contentAlignment = Alignment.Center) {
            if (bild != null) Image(bild.bild, contentDescription = null, modifier = Modifier.size(groesse * 0.68f).wackeln(key.hashCode()),
                                    colorFilter = ColorFilter.tint(p.text))
        }
        return
    }
    if (stil == "umriss" || stil == "motiv") {
        if (bild != null) Image(bild.bild, contentDescription = null, modifier = mod, colorFilter = ColorFilter.tint(p.text))
        else Spacer(mod)
        return
    }
    when {
        bild != null && !bild.maske -> Image(bild.bild, contentDescription = null, modifier = mod)
        bild != null -> Box(mod.clip(CircleShape).background(p.karte), contentAlignment = Alignment.Center) {
            Box(Modifier.size(groesse).background(p.akzent.copy(alpha = 0.20f)))
            Image(bild.bild, contentDescription = null, modifier = Modifier.size(groesse),
                  colorFilter = ColorFilter.tint(p.akzent))
        }
        stil == "punkte" -> Box(mod, contentAlignment = Alignment.Center) {
            Box(Modifier.size(groesse * 0.34f).clip(CircleShape).background(IconSpeicher.punkte[key] ?: p.leise))
        }
        else -> Spacer(mod)
    }
}
