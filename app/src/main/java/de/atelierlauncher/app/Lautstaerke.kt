package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
//  Eigener Lautstaerke-Regler (statt Samsungs Panel)
//  - Bedienungshilfe-Dienst faengt NUR die Lautstaerketasten ab (sonst nichts).
//  - Android kennt fuer Musik nur ~15 Stufen. Unterhalb von Stufe 1 wird zusaetzlich
//    leiser gedreht (Daempfung bis -30 dB), damit "ganz ganz leise" fein einstellbar ist.
//  - Unteres Drittel des Reglers = dieser Feinbereich -> gross und gut treffbar.
//  - Bei Anrufen / Klingeln bleibt alles wie bei Samsung.
// ============================================================================

/** Feine Daempfung ueber einen globalen Audio-Effekt (nicht jedes Handy erlaubt das) */
object Fein {
    private var dyn: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    /** null = noch nicht probiert, true = geht, false = Handy erlaubt es nicht */
    @Volatile var geht: Boolean? = null
        private set
    var db = 0f
        private set

    fun setzen(wert: Float): Boolean {
        val d = wert.coerceIn(-30f, 0f)
        if (geht == false) { db = 0f; return false }
        if (geht == true && kotlin.math.abs(d - db) < 0.01f) return true      // nichts zu tun
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val dp = dyn ?: DynamicsProcessing(
                    Int.MAX_VALUE, 0,
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                        false, 0, false, 0, false, 0, false,
                    ).build(),
                ).also { dyn = it }
                dp.setInputGainAllChannelsTo(d)
                dp.setEnabled(d < -0.05f)
                db = d; geht = true
                return true
            } catch (e: Throwable) {
                try { dyn?.release() } catch (e2: Throwable) { }
                dyn = null
            }
        }
        // Ersatz: alle Equalizer-Baender gleich weit runter (meist bis -15 dB)
        return try {
            val e = eq ?: Equalizer(Int.MAX_VALUE, 0).also { eq = it }
            val min = e.bandLevelRange[0].toInt()
            val mb = maxOf(min, (d * 100).roundToInt()).toShort()
            for (b in 0 until e.numberOfBands) e.setBandLevel(b.toShort(), mb)
            e.setEnabled(d < -0.05f)
            db = maxOf(d, min / 100f); geht = true
            true
        } catch (e: Throwable) {
            try { eq?.release() } catch (e2: Throwable) { }
            eq = null; geht = false; db = 0f
            false
        }
    }

    fun freigeben() {
        try { dyn?.release() } catch (e: Throwable) { }
        try { eq?.release() } catch (e: Throwable) { }
        dyn = null; eq = null; db = 0f
    }
}

class LautDienst : AccessibilityService() {
    private lateinit var audio: AudioManager
    private lateinit var wm: WindowManager
    private val haupt = Handler(Looper.getMainLooper())
    private var regler: LautRegler? = null
    private var lp: WindowManager.LayoutParams? = null
    /** eigenes kleines Fenster NUR fuer den unscharfen Hintergrund - liegt unter dem Regler, faengt nichts ab */
    private var blurFenster: android.app.Dialog? = null
    private var sichtbar = false
    private val ausblenden = Runnable { verstecken() }

    // gemerkter Stand -> beim Ziehen keine Systemabfragen
    private var stufe = 0
    private var maxStufe = 15
    private var zieht = false
    private var eigenBis = 0L          // eigene Aenderung: System-Nachricht bis dahin ignorieren
    private var planStufe = -1         // per Taste geplant, aber noch nicht gesetzt
    private var planDb = 0f
    @Volatile private var modus = AudioManager.MODE_NORMAL   // Anruf? (gemerkt, nicht bei jedem Tastendruck fragen)
    private val prefs by lazy { getSharedPreferences("launcher", MODE_PRIVATE) }

    // Feinbereich: unteres Drittel des Reglers = Stufe 1 mit -30..0 dB
    private val ZONE = 0.32f
    private val TIEF = -30f

    private val lautAenderung = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1) != AudioManager.STREAM_MUSIC) return
            // eigene Aenderungen (und alles waehrend der Finger drauf ist) nicht nochmal verarbeiten
            if (zieht || SystemClock.uptimeMillis() < eigenBis) return
            // jemand anderes (z. B. Samsung auf dem Sperrbildschirm): Stand uebernehmen,
            // ausserhalb von Stufe 1 keine Extra-Daempfung mehr
            stufe = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", stufe)
            if (stufe != 1 && Fein.db < 0f) Fein.setzen(0f)
            regler?.let { it.stand = stand(); it.invalidate() }
        }
    }

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
        wm = getSystemService(WindowManager::class.java)
        maxStufe = maxOf(2, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        modus = audio.mode
        if (Build.VERSION.SDK_INT >= 31) try {
            val l = AudioManager.OnModeChangedListener { modus = it }
            audio.addOnModeChangedListener(mainExecutor, l)
            modusHoerer = l
        } catch (e: Exception) { }
        stufe = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(lautAenderung, IntentFilter("android.media.VOLUME_CHANGED_ACTION"), RECEIVER_NOT_EXPORTED)
            else registerReceiver(lautAenderung, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        } catch (e: Exception) { }
        // vorbereiten, damit der erste Tastendruck nicht haengt
        Fein.setzen(0f)
        fensterAnlegen()
        // Randgesten (eigene Einhand-Bedienung) laufen im selben Dienst
        Randgesten.starten(this)
        // Sperrbildschirm-Bild: beim Ausschalten des Bildschirms aktuell malen
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(bildschirmAus, f, RECEIVER_NOT_EXPORTED) else registerReceiver(bildschirmAus, f)
        } catch (e: Exception) { }
        SperrSeite.starten(this)
        // Reste der frueheren Now-Bar-Anzeige wegraeumen
        try {
            val nm = getSystemService(android.app.NotificationManager::class.java)
            nm?.cancel(4711)
        } catch (e: Exception) { }
        SperrBild.aktualisieren(this)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Randgesten.neuMessen()
    }



    private var modusHoerer: Any? = null
    private val bildschirmAus = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> SperrBild.zuruecksetzen(c)
            }
            SperrSeite.pruefen()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(lautAenderung) } catch (e: Exception) { }
        try { unregisterReceiver(bildschirmAus) } catch (e: Exception) { }
        if (Build.VERSION.SDK_INT >= 31) (modusHoerer as? AudioManager.OnModeChangedListener)?.let {
            try { audio.removeOnModeChangedListener(it) } catch (e: Exception) { }
        }
        modusHoerer = null
        regler?.let { try { wm.removeView(it) } catch (e: Exception) { } }
        regler = null
        try { blurFenster?.dismiss() } catch (e: Exception) { }
        blurFenster = null
        Randgesten.stoppen()
        SperrSeite.stoppen()
        Fein.freigeben()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        SperrBild.ereignis(event, this); Randgesten.fensterEreignis(event, this)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || SperrBild.eingabe) { SperrSeite.pruefenBald() }
    }
    override fun onInterrupt() {}

    override fun onKeyEvent(e: KeyEvent): Boolean {
        val hoch = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (!hoch && e.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (!prefs.getBoolean("lautAn", true)) return false
        // anderer Launcher ist Startbildschirm (z. B. Atelier zum Testen): dessen Regler gilt, dieser ruht
        if (!Standard.bin(this)) return false
        // Anruf, Klingeln, Sprachnachricht: Samsung macht das
        val m = if (Build.VERSION.SDK_INT >= 31) modus else audio.mode
        if (m != AudioManager.MODE_NORMAL) return false
        // sofort antworten, die Arbeit danach erledigen -> Android wartet nicht auf uns
        if (e.action == KeyEvent.ACTION_DOWN) haupt.post { taste(hoch) }
        return true
    }

    // ---------------------------------------------------------------- Lautstaerke-Logik
    /** gemerkter Stand als Position auf dem Regler (0 unten .. 1 oben) - ohne Systemaufruf */
    private fun stand(): Float = when {
        stufe <= 0 -> 0f
        stufe == 1 -> ZONE * (1f - Fein.db / TIEF).coerceIn(0.03f, 1f)
        else -> ZONE + (stufe - 1f) / (maxStufe - 1f) * (1f - ZONE)
    }

    /** setzt nur, was sich wirklich aendert: Stufe nur bei neuer Stufe, Effekt nur in 0,5-dB-Spruengen */
    private fun anwenden(neu: Int, db: Float) {
        val s = neu.coerceIn(0, maxStufe)
        val ziel = if (s == 1) (db * 2f).roundToInt() / 2f else 0f
        if (ziel < Fein.db) Fein.setzen(ziel)          // erst leiser, dann Stufe -> kein kurzes Lautwerden
        if (s != stufe) {
            eigenBis = SystemClock.uptimeMillis() + 400
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, s, 0)
            stufe = s
        }
        Fein.setzen(ziel)
    }

    /** Stand auf dem Regler fuer Stufe + Daempfung (fuer die Anzeige vor dem Setzen) */
    private fun standFuer(s: Int, db: Float): Float = when {
        s <= 0 -> 0f
        s == 1 -> ZONE * (1f - db / TIEF).coerceIn(0.03f, 1f)
        else -> ZONE + (s - 1f) / (maxStufe - 1f) * (1f - ZONE)
    }

    /**
     * Taste: oberhalb von Stufe 1 normale Stufen, darunter feine 3-dB-Schritte.
     * Reihenfolge: erst der Regler (sofort sichtbar), dann im naechsten Durchlauf die Lautstaerke.
     */
    private fun taste(hoch: Boolean) {
        // nur wenn der Regler zu ist einmal echt nachfragen (koennte sich woanders geaendert haben)
        if (!sichtbar && planStufe < 0) stufe = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        // schnell hintereinander gedrueckt: vom geplanten (noch nicht gesetzten) Stand weiterrechnen
        val s = if (planStufe >= 0) planStufe else stufe
        val alt = if (planStufe >= 0) planDb else Fein.db
        val fein = Fein.geht != false
        val erster = if (!sichtbar) 1.5f else 3f
        val (neu, db) = if (hoch) when {
            // ganz leise: der ERSTE Druck (Regler war zu) geht nur halb so weit, danach normal 3 dB
            s == 0 -> 1 to (if (fein) TIEF + erster else 0f)
            s == 1 && alt < -0.05f -> 1 to minOf(0f, alt + erster)
            else -> minOf(maxStufe, s + 1) to 0f
        } else when {
            s > 1 -> (s - 1) to 0f
            s == 1 && fein && alt - 3f >= TIEF -> 1 to (alt - 3f)
            else -> 0 to 0f
        }
        planStufe = neu; planDb = db
        // keine Musik an und Regler zu: still verstellen, kein Pop-up - ausser auf dem Startbildschirm
        if (sichtbar || audio.isMusicActive || Sichtbar.vorne) zeigen(standFuer(neu, db))
        haupt.post {
            anwenden(neu, db)
            if (planStufe == neu && planDb == db) planStufe = -1
        }
    }

    /** Finger auf dem Regler: Position -> Stufe + Feindaempfung */
    private fun auf(x: Float) {
        if (x <= 0.015f) anwenden(0, 0f)
        else if (x <= ZONE) anwenden(1, if (Fein.geht == false) 0f else TIEF * (1f - x / ZONE))
        else anwenden(1 + ((x - ZONE) / (1f - ZONE) * (maxStufe - 1)).roundToInt(), 0f)
    }

    // ---------------------------------------------------------------- Anzeige
    /** Fenster einmal anlegen und dauerhaft behalten (unsichtbar + nicht anfassbar, wenn zu) */
    private fun fensterAnlegen() {
        if (regler != null) return
        val d = resources.displayMetrics.density
        val r = LautRegler(this, ZONE,
            onZiehen = { x -> zieht = true; auf(x) },
            onLos = { zieht = false; haupt.removeCallbacks(ausblenden); haupt.postDelayed(ausblenden, 1800) },
            onBeruehrt = { zieht = true; haupt.removeCallbacks(ausblenden) },
            onDraussen = { zieht = false; haupt.removeCallbacks(ausblenden); verstecken() },
        )
        blurFensterAnlegen()                       // zuerst: liegt dann UNTER dem Regler
        val p = WindowManager.LayoutParams(
            (64 * d).roundToInt(), (320 * d).roundToInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0                                   // ganz am Rand
        }
        r.alpha = 0f
        r.visibility = View.INVISIBLE
        try { wm.addView(r, p); regler = r; lp = p } catch (e: Exception) { }
    }

    private fun blurFensterAnlegen() {
        if (blurFenster != null || Build.VERSION.SDK_INT < 31) return
        try {
            val dlg = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)   // ohne Mindestbreite / Abdunkeln
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            val w = dlg.window ?: return
            w.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                       WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) })
            w.decorView.setPadding(0, 0, 0, 0)
            dlg.setContentView(View(this))
            dlg.setCancelable(false)
            blurFenster = dlg
            blurFormSetzen(0)
            dlg.show()
        } catch (t: Throwable) { blurFenster = null; Konsole.schreib("laut", "Unschärfe-Fenster: " + t.message) }
    }

    /** Groesse und Lage wie die Kapsel des Designs; radius 0 = aus (unsichtbar) */
    private fun blurFormSetzen(radius: Int) {
        val dlg = blurFenster ?: return
        val w = dlg.window ?: return
        if (Build.VERSION.SDK_INT < 31) return
        val d = resources.displayMetrics.density
        val breite = LautDesign.blurBreite(LautDesign.art(this)) ?: 46f
        val a = w.attributes
        a.width = (breite * d).roundToInt(); a.height = ((320 - 20) * d).roundToInt()
        a.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        a.x = (6 * d).roundToInt()
        a.dimAmount = 0f
        w.attributes = a
        // runde Ecken der Unschaerfe kommen vom Hintergrund des Fensters
        w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.TRANSPARENT); cornerRadius = breite * d / 2f })
        try { w.setBackgroundBlurRadius(radius) } catch (t: Throwable) { Konsole.schreib("laut", "Unschärfe: " + t.message) }
    }

    private fun anfassbar(an: Boolean) {
        val r = regler ?: return; val p = lp ?: return
        val f = if (an) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (f != p.flags) { p.flags = f; try { wm.updateViewLayout(r, p) } catch (e: Exception) { } }
    }

    private fun zeigen(neuerStand: Float) {
        haupt.removeCallbacks(ausblenden)
        fensterAnlegen()
        val r = regler ?: return
        r.stand = neuerStand
        if (!sichtbar) {
            sichtbar = true
            r.farbenLaden()
            // unscharfer Hintergrund (nur Optik) - Regler-Fenster und Bedienung bleiben genau wie vorher
            if (LautDienst.blurMoeglich(this, r.design)) {
                blurFensterAnlegen()
                blurFormSetzen((LautDesign.blurStaerke(this) * resources.displayMetrics.density).roundToInt())
            }
            r.visibility = View.VISIBLE
            r.translationX = 24 * resources.displayMetrics.density
            r.animate().alpha(1f).translationX(0f).setDuration(120).start()
            // Fenster anfassbar machen erst nach dem Start der Animation (Systemaufruf)
            // Startbildschirm vorne: nur anzeigen, Finger gehen durch zum Launcher
            haupt.post { if (sichtbar && !Sichtbar.vorne) anfassbar(true) }
        }
        r.invalidate()
        haupt.postDelayed(ausblenden, 1800)
    }

    private fun verstecken() {
        val r = regler ?: return
        if (!sichtbar || zieht) return
        sichtbar = false
        anfassbar(false)
        if (blurFenster != null) blurFormSetzen(0)
        r.animate().alpha(0f).translationX(24 * resources.displayMetrics.density).setDuration(180)
            .withEndAction { if (!sichtbar) r.visibility = View.INVISIBLE }.start()
    }

    companion object {
        /** unscharfer Hintergrund: eingeschaltet, Android 12+, Handy erlaubt Fenster-Unschaerfe, Design hat eine Kapsel */
        fun blurMoeglich(ctx: Context, design: String): Boolean {
            if (Build.VERSION.SDK_INT < 31 || !LautDesign.blurAn(ctx) || LautDesign.blurBreite(design) == null) return false
            return try { ctx.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true } catch (t: Throwable) { false }
        }
        fun istAn(ctx: Context): Boolean {
            val an = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val ich = ComponentName(ctx, LautDienst::class.java)
            return an.split(':').any { ComponentName.unflattenFromString(it) == ich }
        }
    }
}

/** Der Regler selbst: schmale Pille, Fuellung mit Wellenkante, Feinbereich unten markiert */
internal class LautRegler(
    ctx: Context,
    private val zone: Float,
    private val onZiehen: (Float) -> Unit,
    private val onLos: () -> Unit,
    private val onBeruehrt: () -> Unit,
    private val onDraussen: () -> Unit,
) : View(ctx) {
    var stand = 0f
    private val d = resources.displayMetrics.density
    private var startY = 0f
    private var startStand = 0f
    private var zieht = false
    private var letzteMarke = -1
    // Aussehen (nur Zeichnen - Bedienung bleibt gleich)
    var design = "pille"
    private var farben: LautDesign.Farben? = null
    private val maler = LautDesign.Maler(d)
    /** Design und Theme-Farben neu lesen (beim Einblenden, nicht bei jedem Bild) */
    fun farbenLaden() {
        Skizze.schriftLaden(context); Skizze.laden(context); Deko.schriftLaden(context)
        design = LautDesign.art(context)
        farben = LautDesign.farben(context)
        maler.durchsicht = if (LautDienst.blurMoeglich(context, design)) 0.4f else 1f
    }

    override fun onDraw(c: Canvas) {
        val f = farben ?: LautDesign.farben(context).also { farben = it }
        maler.malen(c, width.toFloat(), height.toFloat(), stand, zone, design, f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> { onDraussen(); return false }   // daneben getippt -> sofort zu
            MotionEvent.ACTION_DOWN -> {
                // Antippen aendert nichts - erst Ziehen verschiebt den Stand (relativ, wie ein normaler Regler)
                onBeruehrt()
                startY = e.y; startStand = stand; zieht = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val pad = 8f * d
                if (!zieht) {
                    if (kotlin.math.abs(e.y - startY) < 6f * d) return true
                    zieht = true; startY = e.y            // ab hier zaehlt der Weg - kein Sprung
                }
                val x = (startStand + (startY - e.y) / (height - 2 * pad)).coerceIn(0f, 1f)
                if (kotlin.math.abs(x - stand) < 0.002f) return true   // Finger zittert nur -> nichts tun
                stand = x
                onZiehen(x)
                // feines Ticken: im Feinbereich alle ~3 dB, darueber je Stufe
                val marke = if (x <= zone) (x / zone * 10).toInt() else 100 + ((x - zone) / (1 - zone) * 14).toInt()
                if (marke != letzteMarke) { letzteMarke = marke; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { onLos(); return true }
        }
        return super.onTouchEvent(e)
    }
}
