package de.atelierlauncher.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
//  Die Wellen als Live-Hintergrund fuer das ganze Handy (Startbildschirm und,
//  wenn Samsung es erlaubt, Sperrbildschirm). Gleiche Vektor-Wellen wie im
//  Launcher, jedes Bild neu gerechnet - kein Video. Laeuft nur, solange er zu sehen ist.
// ============================================================================

class WellenTapete : WallpaperService() {
    override fun onCreateEngine(): Engine = Maler()

    inner class Maler : Engine() {
        // eigener Zeichen-Faden: das Rechnen der Bilder blockiert nie den Hauptfaden
        private lateinit var faden: HandlerThread
        private lateinit var haupt: Handler
        private val sperre = Any()
        @Volatile private var flaecheDa = false          // nur mit gueltiger Flaeche zeichnen
        private val zeichner = WellenZeichner(resources.displayMetrics.density)
        private val andere = AndereZeichner(resources.displayMetrics.density)
        private val geld by lazy { GeldZeichner(resources.displayMetrics.density) }
        private val glasSh by lazy { Glas.shader(Glas.HG) }
        private val glasP = Paint()
        // Theme im Launcher geaendert (z. B. Hell/Dunkel, anderer Hintergrund): sofort neu malen,
        // nicht erst beim naechsten Sperren - sonst bleibt hinter dem Launcher der alte Hintergrund stehen
        private val themaHoerer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if ((k == "thema" || k == "welleAkzent" || k == "glasFoto" || k == "sperrMedium") && ::haupt.isInitialized)
                haupt.post { artLesen(); neuStarten() }
        }
        private val sperrHoerer = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) { if (::haupt.isInitialized) haupt.post { artLesen(); neuStarten() } }
        }
        private var art = "wellen"          // was im Launcher als Hintergrund gewaehlt ist
        private var dunkel = true
        private var akzent = 0
        private fun artLesen() {
            standbildSpeicher = null     // evtl. neues Video
            val p = applicationContext.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
            val t = try { org.json.JSONObject(p.getString("thema", "{}") ?: "{}") } catch (e: Exception) { org.json.JSONObject() }
            art = t.optString("hintergrund", "wellen")
            // eigenes Video nur fuer den Sperrbildschirm: solange gesperrt, laeuft das statt des Hintergrunds
            val km = getSystemService(android.app.KeyguardManager::class.java)
            if (p.getString("sperrMedium", "") == "video" && km?.isKeyguardLocked == true && EigeneDateien.sperrVideo(applicationContext).exists()) art = "sperrvideo"
            // hell/dunkel wie im Launcher (2 = wie das Handy)
            dunkel = when (t.optInt("modus", 1)) {
                0 -> false; 1 -> true
                else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            akzent = t.optInt("akzent", 0)
            // Samsung/Android sagen, ob der Hintergrund hell ist -> Uhr und Symbole auf dem Sperrbildschirm werden dunkel
            if (Build.VERSION.SDK_INT >= 27) try { notifyColorsChanged() } catch (e: Exception) { }
            andere.dunkel = dunkel
            // Wellen im hellen Theme: weisser Grund, dunkle Wellen (wie im Launcher)
            zeichner.grund = if (dunkel) Color.BLACK else Color.WHITE
            zeichner.farbe = wellenFarbe(t.optInt("welle", 0), dunkel)
            andere.swissRot = if (akzent != 0) akzent else 0xFFE2001A.toInt()
            andere.eisFarbe = p.getInt("eisFarbe", 0xFFA8E6FF.toInt())
            andere.pixelFarbe = p.getInt("pixelFarbe", 0xFF4FE3FF.toInt())
            andere.schnee = p.getBoolean("eisSchnee", true)
        }
        // die folgenden Werte werden nur auf dem Zeichen-Faden benutzt
        private var sichtbar = false
        private var zeit = 0L                 // gelaufene Zeit (steht, solange unsichtbar)
        private var letzter = 0L
        /** einfarbige Designs: einmal malen reicht, kein Bild-Takt */
        private fun stillstehend() = art == "bild" || art == "sperrvideo" || art == "schwarz" || art == "system" || art == "swiss" || art == "lottie" || art == "code" || art == "video" || art == "papier" || art == "marmor" ||
            (art == "glas" && GlasFoto.datei(applicationContext).exists())      // Glas mit deinem Foto steht still

        // ---- Video als Hintergrund: direkt auf die Flaeche abspielen (stumm, in Schleife)
        private var spieler: android.media.MediaPlayer? = null
        private var spielerDatei = ""
        private fun videoDatei() = if (art == "sperrvideo") EigeneDateien.sperrVideo(applicationContext) else EigeneDateien.video(applicationContext)
        private fun videoStarten() {
            val datei = videoDatei()
            if (spieler != null && spielerDatei != datei.path) videoWeg()        // gesperrt <-> entsperrt: anderes Video
            if (spieler != null) { try { spieler?.start() } catch (e: Exception) { }; return }
            spielerDatei = datei.path
            if (!datei.exists()) return
            synchronized(sperre) {
                if (!flaecheDa) return
                try {
                    spieler = android.media.MediaPlayer().apply {
                        setSurface(surfaceHolder.surface)
                        setDataSource(datei.path)
                        isLooping = true
                        setVolume(0f, 0f)
                        setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                        setOnPreparedListener { it.start() }
                        // Flaeche nicht nutzbar (z. B. vorher schon drauf gemalt): stattdessen erstes Bild zeigen
                        setOnErrorListener { _, _, _ -> haupt.post { videoWeg(); malen() }; true }
                        prepareAsync()
                    }
                } catch (e: Exception) { videoWeg() }
            }
            if (spieler == null) malen()
        }
        private var standbildSpeicher: android.graphics.Bitmap? = null
        private var standbildDatei = ""
        private fun videoStandbild(): android.graphics.Bitmap? {
            val d = videoDatei()
            if (standbildDatei == d.path + d.lastModified()) standbildSpeicher?.let { return it }
            val r = android.media.MediaMetadataRetriever()
            return try {
                r.setDataSource(d.path)
                r.getFrameAtTime(0).also { standbildSpeicher = it; standbildDatei = d.path + d.lastModified() }
            } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) { } }
        }
        // eigenes Bild: einmal laden, solange die Datei gleich bleibt
        private var bildSpeicher: android.graphics.Bitmap? = null
        private var bildStand = 0L
        private fun bildJetzt(b: Int, h: Int): android.graphics.Bitmap? {
            val f = EigeneDateien.bild(applicationContext)
            if (bildSpeicher == null || bildStand != f.lastModified()) { bildSpeicher = EigeneDateien.bildLaden(f, b, h); bildStand = f.lastModified() }
            return bildSpeicher
        }
        private fun videoPause() { try { spieler?.pause() } catch (e: Exception) { } }
        private fun videoWeg() { try { spieler?.release() } catch (e: Exception) { }; spieler = null }

        private val schritt = object : Runnable {
            override fun run() {
                val jetzt = SystemClock.uptimeMillis()
                zeit += (jetzt - letzter).coerceAtMost(100)
                letzter = jetzt
                malen()
                // Wellen ca. 30 Bilder/s, die ruhigeren Designs reichen mit ca. 16 Bilder/s
                if (sichtbar && flaecheDa && !stillstehend())
                    haupt.postDelayed(this, if (art == "wellen" || art == "polar") 33L else 60L)
            }
        }

        /** Takt neu starten (oder bei einfarbigen Designs genau ein Bild) - nur auf dem Zeichen-Faden */
        private fun neuStarten() {
            haupt.removeCallbacks(schritt)
            if (art != "video" && art != "sperrvideo") videoWeg()
            if (!sichtbar) { videoPause(); return }
            if (art == "video" || art == "sperrvideo") { videoStarten(); return }     // das Video malt selbst
            letzter = SystemClock.uptimeMillis()
            schritt.run()
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            faden = HandlerThread("WellenTapete").apply { start() }
            haupt = Handler(faden.looper)
            applicationContext.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(themaHoerer)
            try {
                val f = android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_USER_PRESENT); addAction(android.content.Intent.ACTION_SCREEN_OFF); addAction(android.content.Intent.ACTION_SCREEN_ON)
                }
                if (Build.VERSION.SDK_INT >= 33) applicationContext.registerReceiver(sperrHoerer, f, android.content.Context.RECEIVER_NOT_EXPORTED)
                else applicationContext.registerReceiver(sperrHoerer, f)
            } catch (e: Exception) { }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            haupt.removeCallbacks(schritt)
            haupt.post {
                sichtbar = visible
                if (visible) { artLesen(); neuStarten() } else haupt.removeCallbacks(schritt)
            }
            // 3D beim Neigen: nur solange sichtbar
            if (visible && Neigung.an(applicationContext)) neigung.start(applicationContext) {
                // stehende Designs: bei Bewegung neu malen (hoechstens ca. 30x pro Sekunde)
                val j = SystemClock.uptimeMillis()
                if (j - neigungZuletzt > 30 && ::haupt.isInitialized) { neigungZuletzt = j; haupt.post { if (sichtbar && stillstehend() && spieler == null) malen() } }
            } else neigung.stop()
        }
        private val neigung = Neigung()
        @Volatile private var neigungZuletzt = 0L

        /** Farben des Hintergrunds fuer das System (helle Flaeche = dunkle Schrift auf dem Sperrbildschirm) */
        override fun onComputeColors(): android.app.WallpaperColors? {
            if (Build.VERSION.SDK_INT < 27) return null
            val hellerGrund = (art == "papier" && !Skizze.tafel) || (!dunkel && art in setOf("swiss", "geld", "wellen", "schwarz", "system", "lottie", "code"))
            val grund = if (hellerGrund) Color.valueOf(Color.WHITE) else Color.valueOf(Color.rgb(11, 11, 12))
            return if (Build.VERSION.SDK_INT >= 31)
                android.app.WallpaperColors(grund, null, null,
                    if (hellerGrund) android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT
                    else android.app.WallpaperColors.HINT_SUPPORTS_DARK_THEME)
            else android.app.WallpaperColors(grund, null, null)
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            synchronized(sperre) { flaecheDa = true }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            synchronized(sperre) { flaecheDa = true }
            haupt.post { neuStarten() }                  // neue Groesse: sofort neu malen
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            // erst zurueckkehren, wenn der Zeichen-Faden die Flaeche sicher nicht mehr benutzt
            synchronized(sperre) { flaecheDa = false }
            haupt.removeCallbacks(schritt)
            haupt.post { videoWeg() }
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            synchronized(sperre) { flaecheDa = false }
            haupt.removeCallbacks(schritt)
            haupt.post { videoWeg() }
            try { applicationContext.unregisterReceiver(sperrHoerer) } catch (e: Exception) { }
            applicationContext.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(themaHoerer)
            faden.quitSafely()
            super.onDestroy()
        }

        private fun malen() {
            synchronized(sperre) {
                if (!flaecheDa) return
                val h = surfaceHolder
                val c = try {
                    if (Build.VERSION.SDK_INT >= 26) h.lockHardwareCanvas() else h.lockCanvas()
                } catch (e: Exception) { null } ?: return
                try {
                    val nx = neigung.wx; val ny = neigung.wy
                    val d3 = resources.displayMetrics.density
                    c.save()
                    when (art) {
                        "eis" -> andere.eis(c, zeit.toDouble())
                        "pixel" -> andere.pixel(c, zeit.toDouble())
                        "sterne" -> andere.sterne(c, zeit.toDouble())
                        "polar" -> andere.polar(c, zeit.toDouble())
                        "swiss" -> andere.swiss(c)
                        "geld" -> geld.hintergrund(c, c.width.toFloat(), c.height.toFloat(), dunkel, zeit.toDouble())
                        "papier" -> { Skizze.laden(applicationContext); Skizze.papier(c, c.width.toFloat(), c.height.toFloat()) }
                        "marmor" -> Deko.marmor(c, c.width.toFloat(), c.height.toFloat())
                        "glas" -> {
                            val foto = GlasFoto.sofort(applicationContext)
                            val sh = glasSh
                            if (foto != null) GlasFoto.malen(c, foto, c.width.toFloat(), c.height.toFloat()) else
                            if (sh != null && c.isHardwareAccelerated) {
                                Glas.gemeinsam(sh, c.width.toFloat(), c.height.toFloat(), zeit / 1000f, dunkel)
                                glasP.shader = sh as android.graphics.Shader
                                c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), glasP)
                            } else Glas.hintergrundCpu(c, c.width.toFloat(), c.height.toFloat(), dunkel)
                        }
                        // Lottie / eigener Code laufen nur im Launcher -> hier ruhig einfarbig
                        "schwarz", "system", "lottie", "code" -> c.drawColor(if (dunkel) Color.BLACK else Color.WHITE)
                        "bild" -> {
                            c.drawColor(Color.BLACK)
                            bildJetzt(c.width, c.height)?.let { EigeneDateien.fuellend(c, it, c.width.toFloat(), c.height.toFloat()) }
                        }
                        "video", "sperrvideo" -> {
                            c.drawColor(Color.BLACK)
                            videoStandbild()?.let { f ->
                                val s = maxOf(c.width / f.width.toFloat(), c.height / f.height.toFloat())
                                val bw = f.width * s; val bh = f.height * s
                                c.drawBitmap(f, null, android.graphics.RectF((c.width - bw) / 2f, (c.height - bh) / 2f, (c.width + bw) / 2f, (c.height + bh) / 2f),
                                             Paint(Paint.FILTER_BITMAP_FLAG))
                            }
                        }
                        else -> zeichner.male(c, zeit.toDouble())       // Wellen
                    }
                    c.restore()
                }
                finally { try { h.unlockCanvasAndPost(c) } catch (e: Exception) { } }
            }
        }
    }
}

private const val T_WELLEN = 3
private const val T_PUNKTE = 36
private const val T_ZYKLUS = 13000.0

private fun tAusrollen(x: Double) = 1 - (1 - x).pow(3)
private fun tWeich(x: Double) = (1 - cos(PI * x)) / 2
private fun tGlatt(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t)
}
private fun tRauschen(u: Double, t: Double, s: Double) =
    0.55 * sin(2 * PI * (u * 1.3 + s) + t / 5200) +
    0.30 * sin(2 * PI * (u * 3.1 + s * 1.7) - t / 3700) +
    0.18 * sin(2 * PI * (u * 6.7 + s * 2.3) + t / 2300) +
    0.08 * sin(2 * PI * (u * 13.3 + s * 3.1) - t / 1500)

private fun weiss(a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), 255, 255, 255)

/** Zeichnet die Wellen auf eine normale Android-Leinwand (gleiche Rechnung wie im Launcher) */
class WellenZeichner(dichte: Float, private val reichweite: Float = 1f) {
    var grund = Color.BLACK
    var farbe = Color.WHITE
    private fun farbig(a: Float) =
        Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), Color.red(farbe), Color.green(farbe), Color.blue(farbe))

    private val k = dichte * 0.55f
    private val xs = Array(T_WELLEN) { FloatArray(T_PUNKTE + 1) }
    private val spur = Array(T_WELLEN) { FloatArray(T_PUNKTE + 1) }
    private val spurDa = BooleanArray(T_WELLEN)
    private val pfad = Path()
    private val flaeche = Paint(Paint.ANTI_ALIAS_FLAG)
    private var flaecheVerlauf: LinearGradient? = null
    private var flaecheFarbe = 0
    private val flaecheLage = android.graphics.Matrix()
    private val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val punkt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spitzen1 = DashPathEffect(floatArrayOf(3f, 7f, 1f, 11f, 6f, 9f, 2f, 14f), 0f)
    private val spitzen2 = DashPathEffect(floatArrayOf(1f, 9f, 2f, 17f, 1f, 6f), 0f)
    private val blasenY = Array(T_WELLEN) { w -> FloatArray(32) { i -> zufall(w * 100.0 + i).toFloat() } }
    private val blasenH = Array(T_WELLEN) { w -> FloatArray(32) { i -> zufall(w * 100.0 + i + 50).pow(1.6).toFloat() } }
    private val blasenR = Array(T_WELLEN) { w -> FloatArray(32) { i -> (0.6 + 2.2 * zufall(w * 100.0 + i + 90)).toFloat() } }

    fun male(c: Canvas, t: Double) = maleIn(c, t, c.width.toFloat(), c.height.toFloat())

    /** b = Richtung, in die die Wellen laufen (von b nach 0), h = Laenge der Kueste */
    fun maleIn(c: Canvas, t: Double, b: Float, h: Float) {
        c.drawColor(grund)
        for (w in 0 until T_WELLEN) welle(c, b, h, t, w)
    }

    private fun kurve(p: Path, xs: FloatArray, h: Float, versatz: Float) {
        val n = xs.size - 1
        fun y(i: Int) = h * i / n
        p.moveTo(xs[0] + versatz, -4f)
        for (i in 0 until n) {
            val i0 = maxOf(0, i - 1); val i3 = minOf(n, i + 2)
            p.cubicTo(
                xs[i] + (xs[i + 1] - xs[i0]) / 6 + versatz, y(i) + (y(i + 1) - y(i0)) / 6,
                xs[i + 1] - (xs[i3] - xs[i]) / 6 + versatz, y(i + 1) - (y(i3) - y(i)) / 6,
                xs[i + 1] + versatz, y(i + 1),
            )
        }
    }

    private fun welle(c: Canvas, b: Float, h: Float, t: Double, w: Int) {
        val lauf = t + w * T_ZYKLUS / T_WELLEN
        val runde = floor(lauf / T_ZYKLUS)
        val u = (lauf % T_ZYKLUS) / T_ZYKLUS
        val weit = b * (0.20 + 0.05 * zufall(runde * 7 + w)) * reichweite
        val kurz = b * 0.04 * reichweite
        val gipfel = 0.32
        val r = if (u < gipfel) kurz + (weit - kurz) * tAusrollen(u / gipfel)
                else weit - (weit - kurz) * tWeich((u - gipfel) / (1 - gipfel))
        val rueckzug = if (u < gipfel) 0.0 else (u - gipfel) / (1 - gipfel)
        val amp = b * (0.016 + 0.022 * rueckzug)
        val s = zufall(runde * 13 + w * 3)
        val neigung = 0.15 * sin(runde * 2.3 + w)
        val x = xs[w]
        var links = b
        for (i in 0..T_PUNKTE) {
            val v = i.toDouble() / T_PUNKTE
            val form = 0.68 + 0.32 * sin(PI * v) + neigung * (v - 0.5)
            x[i] = (b - r * form - amp * tRauschen(v, t, s)).toFloat()
            links = minOf(links, x[i])
        }
        val sichtbar = (1 - tGlatt(0.72, 1.0, u)).toFloat()
        val rz = rueckzug.toFloat()

        if (u < gipfel) { x.copyInto(spur[w]); spurDa[w] = true }
        else if (spurDa[w]) {
            val a = (0.16 * (1 - tGlatt(0.3, 0.95, u))).toFloat()
            if (a > 0.005f) {
                pfad.reset(); kurve(pfad, spur[w], h, 0f)
                linie.pathEffect = null; linie.color = farbig(a); linie.strokeWidth = 1.2f * k
                c.drawPath(pfad, linie)
            }
        }
        if (sichtbar < 0.01f) return

        pfad.reset(); kurve(pfad, x, h, 0f)
        pfad.lineTo(b + 4, h + 4); pfad.lineTo(b + 4, -4f); pfad.close()
        // Verlauf nur einmal je Farbe bauen (0..1), pro Welle per Matrix auf links..b legen, Durchsichtigkeit per alpha
        if (flaecheVerlauf == null || flaecheFarbe != farbe) {
            flaecheFarbe = farbe
            flaecheVerlauf = LinearGradient(0f, 0f, 1f, 0f,
                intArrayOf(farbig(0.62f), farbig(0.30f), farbig(0.12f), farbig(0.04f)),
                floatArrayOf(0f, 0.12f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        flaecheLage.setScale(maxOf(b - links, 1f), 1f)
        flaecheLage.postTranslate(links, 0f)
        flaecheVerlauf?.setLocalMatrix(flaecheLage)
        flaeche.shader = flaecheVerlauf
        flaeche.alpha = (sichtbar.coerceIn(0f, 1f) * 255).roundToInt()
        c.drawPath(pfad, flaeche)

        pfad.reset(); kurve(pfad, x, h, 0f)
        linie.pathEffect = null; linie.color = farbig(0.9f * sichtbar * (1 - 0.6f * rz)); linie.strokeWidth = (1.5f + 3f * (1 - rz)) * k
        c.drawPath(pfad, linie)
        pfad.reset(); kurve(pfad, x, h, (6f + 6f * rz) * k)
        linie.pathEffect = spitzen1; linie.color = farbig(0.55f * sichtbar); linie.strokeWidth = 1.6f * k
        c.drawPath(pfad, linie)
        pfad.reset(); kurve(pfad, x, h, (14f + 14f * rz) * k)
        linie.pathEffect = spitzen2; linie.color = farbig(0.35f * sichtbar); linie.strokeWidth = 1f * k
        c.drawPath(pfad, linie)
        linie.pathEffect = null

        for (i in 0 until 32) {
            val j = minOf(T_PUNKTE, (blasenY[w][i] * T_PUNKTE).roundToInt())
            val bx = x[j] + (5f + blasenH[w][i] * (24f + 50f * rz)) * k
            val by = blasenY[w][i] * h + 4f * k * sin(t / 1900 + i).toFloat()
            punkt.color = farbig(sichtbar * (0.75f - 0.5f * blasenH[w][i]))
            c.drawCircle(bx, by, blasenR[w][i] * (1 - 0.4f * rz) * k, punkt)
        }
    }
}

// ============================================================================
//  Die anderen Designs fuer den Live-Hintergrund (gleiche Formen wie im Launcher)
// ============================================================================
class AndereZeichner(private val dichte: Float) {
    var dunkel = true
    var swissRot = 0xFFE2001A.toInt()
    var eisFarbe = 0xFFA8E6FF.toInt()
    var pixelFarbe = 0xFF4FE3FF.toInt()
    var schnee = true
    private val stift = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val form = Path()
    // eigener Stift fuer die Zacken-Facetten (Deckkraft per alpha, Verlauf per Matrix ausgerichtet)
    private val zackenStift = Paint(Paint.ANTI_ALIAS_FLAG)
    private var verlaufFarbe = 0
    private var lichtVerlauf: LinearGradient? = null
    private var schattenVerlauf: LinearGradient? = null
    private val richtung = android.graphics.Matrix()
    // Schnee-Verlauf: nur neu bei anderer Breite/Farbe
    private var schneeVerlauf: LinearGradient? = null
    private var schneeB = -1f
    private var schneeF = 0

    /** Facetten-Verlaeufe (Richtung 0..1) einmal je Farbe bauen */
    private fun verlaeufe(f: Int, hell: Int, dunkel: Int) {
        if (f == verlaufFarbe && lichtVerlauf != null) return
        verlaufFarbe = f
        lichtVerlauf = LinearGradient(0f, 0f, 1f, 0f, mit(f, 0.5f), mit(hell, 0.9f), Shader.TileMode.CLAMP)
        schattenVerlauf = LinearGradient(0f, 0f, 1f, 0f, mit(dunkel, 0.55f), mit(f, 0.75f), Shader.TileMode.CLAMP)
    }

    private fun mit(f: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), Color.red(f), Color.green(f), Color.blue(f))
    private fun misch(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt())

    // ---- Eis-Zacken
    private class Z(val y: Float, val br: Float, val la: Float, val ne: Float, val eb: Int)
    private val zacken: List<Z> by lazy {
        val l = ArrayList<Z>()
        for (i in 0 until 9) l += Z(((i + 0.5f) / 9f + 0.05f * (zufall(i * 3.1) - 0.5).toFloat()).coerceIn(0.02f, 0.98f),
            (0.02 + 0.016 * zufall(i * 7.7)).toFloat(), (0.22 + 0.18 * zufall(i * 5.3)).toFloat(), (0.30 * (zufall(i * 9.1) - 0.5)).toFloat(), 0)
        for (i in 0 until 14) l += Z(((i + 0.5f) / 14f + 0.04f * (zufall(i * 4.7 + 50) - 0.5).toFloat()).coerceIn(0.01f, 0.99f),
            (0.011 + 0.013 * zufall(i * 6.1 + 50)).toFloat(), (0.12 + 0.18 * zufall(i * 2.9 + 50)).toFloat(), (0.30 * (zufall(i * 8.3 + 50) - 0.5)).toFloat(), 1)
        for (i in 0 until 22) l += Z(zufall(i * 3.3 + 100).toFloat(), (0.006 + 0.008 * zufall(i * 1.7 + 100)).toFloat(),
            (0.025 + 0.05 * zufall(i * 4.1 + 100)).toFloat(), (0.5 * (zufall(i * 2.2 + 100) - 0.5)).toFloat(), 2)
        l
    }

    private val schneeForm = Path()

    /** eine Zacke: Basis (bx,by) am Rand, Richtung (dx,dy) nach innen - wie im Launcher */
    private fun zacke(c: Canvas, bx: Float, by: Float, dx: Float, dy: Float, len: Float, w: Float, neig: Float,
                      deck: Float, f: Int, hell: Int, dunkel: Int, kanten: Boolean) {
        val nx = -dy; val ny = dx
        val tx = bx + dx * len - nx * len * neig; val ty = by + dy * len - ny * len * neig
        val ox = bx + nx * w; val oy = by + ny * w
        val ux = bx - nx * w; val uy = by - ny * w
        val mx = bx - nx * w * 0.15f; val my = by - ny * w * 0.15f
        verlaeufe(f, hell, dunkel)
        val ddx = tx - bx; val ddy = ty - by
        val l = kotlin.math.sqrt(ddx * ddx + ddy * ddy)
        if (l < 0.01f) richtung.setScale(0.01f, 0.01f)
        else { richtung.setSinCos(ddy / l, ddx / l); richtung.postScale(l, l) }
        richtung.postTranslate(bx, by)
        zackenStift.alpha = (deck.coerceIn(0f, 1f) * 255).roundToInt()
        form.reset(); form.moveTo(ox, oy); form.lineTo(tx, ty); form.lineTo(mx, my); form.close()
        lichtVerlauf?.setLocalMatrix(richtung)
        zackenStift.shader = lichtVerlauf
        c.drawPath(form, zackenStift)
        form.reset(); form.moveTo(mx, my); form.lineTo(tx, ty); form.lineTo(ux, uy); form.close()
        schattenVerlauf?.setLocalMatrix(richtung)
        zackenStift.shader = schattenVerlauf
        c.drawPath(form, zackenStift)
        if (kanten) {
            linie.strokeWidth = 1.1f * dichte; linie.color = weiss(0.75f * deck); c.drawLine(ox, oy, tx, ty, linie)
            linie.strokeWidth = 0.8f * dichte; linie.color = weiss(0.32f * deck); c.drawLine(mx, my, tx, ty, linie)
            linie.color = mit(dunkel, 0.45f * deck); c.drawLine(ux, uy, tx, ty, linie)
        }
    }

    fun eis(c: Canvas, t: Double) {
        c.drawColor(Color.BLACK)
        val b = c.width.toFloat(); val h = c.height.toFloat()
        val f = eisFarbe
        val hell = misch(f, Color.WHITE, 0.65f); val dunkel = misch(f, Color.BLACK, 0.35f)
        // vereinzelte kleine Eiszapfen oben, unten, links
        for (z in RAND_ZACKEN) {
            when (z[0].toInt()) {
                0 -> zacke(c, z[1] * b, -2f, 0f, 1f, z[2] * dichte, z[3] * dichte, z[4], 0.8f, f, hell, dunkel, true)
                1 -> zacke(c, z[1] * b, h + 2f, 0f, -1f, z[2] * dichte, z[3] * dichte, z[4], 0.8f, f, hell, dunkel, true)
                else -> zacke(c, -2f, z[1] * h, 1f, 0f, z[2] * dichte, z[3] * dichte, z[4], 0.8f, f, hell, dunkel, true)
            }
        }
        var vorne = 0
        var schneeGemalt = false
        for (z in zacken) {
            if (schnee && !schneeGemalt && z.eb >= 1) {
                schneeGemalt = true
                schneeForm.reset(); schneeForm.moveTo(b + 2f, -2f)
                var yy = -2f
                while (yy <= h + 2f) { schneeForm.lineTo(b - schneeRand(yy, dichte), yy); yy += 5f * dichte }
                schneeForm.lineTo(b + 2f, h + 2f); schneeForm.close()
                if (schneeVerlauf == null || schneeB != b || schneeF != f) {
                    schneeB = b; schneeF = f
                    schneeVerlauf = LinearGradient(b - 34f * dichte, 0f, b, 0f, mit(misch(Color.WHITE, f, 0.25f), 0.92f), weiss(0.97f), Shader.TileMode.CLAMP)
                }
                stift.shader = schneeVerlauf
                c.drawPath(schneeForm, stift)
                stift.shader = null
                for (i in 0 until 40) {
                    val gy = zufall(i * 6.1 + 900).toFloat() * h
                    val gx = b - schneeRand(gy, dichte) * (0.15f + 0.8f * zufall(i * 2.9 + 900).toFloat())
                    stift.color = mit(misch(f, Color.WHITE, 0.3f), 0.55f)
                    c.drawCircle(gx, gy, 0.9f * dichte, stift)
                }
            }
            val y = z.y * h; val len = z.la * b * 0.8f; val w = z.br * h
            val deck = when (z.eb) { 0 -> 0.5f; 1 -> 0.95f; else -> 0.75f }
            val ba = b + 4f
            zacke(c, ba, y, -1f, 0f, len, w, z.ne, deck, f, hell, dunkel, z.eb < 2)
            if (z.eb == 1) {
                val periode = 2600.0
                if (((t / periode).toInt()) % 14 == vorne) {
                    val tx = ba - len; val ty = y + len * z.ne; val m = y + w * 0.15f
                    val ph = ((t % periode) / periode).toFloat()
                    val sx = ba + (tx - ba) * (0.35f + 0.55f * ph); val sy = m + (ty - m) * (0.35f + 0.55f * ph)
                    val s = sin(PI * ph).toFloat(); val r = 7f * dichte * s
                    if (s > 0.02f) {
                        linie.strokeWidth = 1.2f * dichte; linie.color = weiss(0.9f * s)
                        c.drawLine(sx - r, sy, sx + r, sy, linie); c.drawLine(sx, sy - r, sx, sy + r, linie)
                    }
                }
                vorne++
            }
        }
    }

    // ---- Pixel
    private val hsv = FloatArray(3)
    private val tonHsv = FloatArray(3)          // wiederverwendet statt je Pixel ein neues Feld
    private fun pixelTon(u: Float, hell: Float, t: Double): Int {
        val ton = (hsv[0] + 22f * sin(t / 4200.0 + u * 13.0).toFloat() + 360f) % 360f
        tonHsv[0] = ton; tonHsv[1] = hsv[1]; tonHsv[2] = hsv[2]
        val f = Color.HSVToColor(tonHsv)
        return mit(f, hell)
    }

    fun pixel(c: Canvas, t: Double) {
        c.drawColor(Color.BLACK)
        val b = c.width.toFloat(); val h = c.height.toFloat()
        val zelle = 9f * dichte; val px = zelle - 1.6f * dichte
        val zeilen = (h / zelle).toInt() + 1
        val k = 5.5f
        Color.colorToHSV(pixelFarbe, hsv)
        // vereinzelt in den linken Ecken
        for (ecke in 0..1) for (r in 0 until 7) for (col in 0 until 7) {
            val ab = kotlin.math.sqrt((r * r + col * col).toFloat())
            val d = kotlin.math.exp(-ab / 1.6f) * 0.9f
            val u = zufall(col * 53.1 + r * 17.9 + ecke * 400.0).toFloat()
            if (u > d) continue
            val funkel = 0.55f + 0.45f * sin(t / 1300.0 + u * 40.0).toFloat()
            stift.color = pixelTon(u, (0.35f + 0.65f * d) * funkel, t)
            val y = if (ecke == 0) r * zelle else h - (r + 1) * zelle
            c.drawRect(col * zelle, y, col * zelle + px, y + px, stift)
        }
        for (r in 0 until zeilen) {
            val y = h - (r + 1) * zelle
            for (col in 0 until 40) {
                val d = kotlin.math.exp(-col / k)
                if (d < 0.015f) break
                val u = zufall(col * 91.7 + r * 7.3).toFloat()
                if (u > d) continue
                val funkel = 0.55f + 0.45f * sin(t / 1300.0 + u * 40.0).toFloat()
                stift.color = pixelTon(u, (0.35f + 0.65f * d) * funkel, t)
                val x = b - (col + 1) * zelle
                c.drawRect(x, y, x + px, y + px, stift)
            }
        }
    }

    // ---- Sternenhimmel (ohne Sternschnuppe)
    private val sterne by lazy {
        Array(150) { i -> floatArrayOf(zufall(i * 1.0).toFloat(), zufall(i + 500.0).toFloat().pow(1.25f),
            (0.5 + 1.6 * zufall(i + 900.0).pow(3)).toFloat(), (zufall(i + 1300.0) * 2 * PI).toFloat(), (0.4 + 1.2 * zufall(i + 1700.0)).toFloat()) }
    }
    // ---- Swiss: gleiches quadratisches Raster wie im Launcher (steht still)
    fun swiss(c: Canvas) {
        val b = c.width.toFloat(); val h = c.height.toFloat(); val d = dichte
        c.drawColor(if (dunkel) Color.rgb(11, 11, 12) else Color.WHITE)
        val tinte = if (dunkel) Color.WHITE else Color.rgb(17, 17, 17)
        val l = Paint().apply { color = tinte; alpha = if (dunkel) 18 else 23; strokeWidth = 1f }
        val rand = 24f * d
        val zelle = (b - 2 * rand) / 4f
        for (k in 0..4) { val x = rand + zelle * k; c.drawLine(x, 0f, x, h, l) }
        var y = rand
        while (y <= h) { c.drawLine(0f, y, b, y, l); y += zelle }
        val m = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tinte; alpha = 90; strokeWidth = 1.5f * d }
        val o = 14f * d; val lg = 14f * d
        for (e in listOf(floatArrayOf(o, o + 30 * d, 1f, 1f), floatArrayOf(b - o, o + 30 * d, -1f, 1f),
                         floatArrayOf(o, h - o - 40 * d, 1f, -1f), floatArrayOf(b - o, h - o - 40 * d, -1f, -1f))) {
            c.drawLine(e[0], e[1], e[0] + e[2] * lg, e[1], m)
            c.drawLine(e[0], e[1], e[0], e[1] + e[3] * lg, m)
        }
        val rot = Paint().apply { color = swissRot or 0xFF000000.toInt() }
        c.drawRect(rand - 2.5f * d, rand + zelle, rand + 2.5f * d, rand + 2 * zelle, rot)
    }

    // ---- Polarlicht: wie im Launcher (drei leuchtende Baender, oben ein paar Sterne)
    private val polarFarben = intArrayOf(Color.rgb(108, 245, 176), Color.rgb(69, 211, 224), Color.rgb(155, 123, 255))
    fun polar(c: Canvas, tMs: Double) {
        val b = c.width.toFloat(); val h = c.height.toFloat()
        stift.shader = LinearGradient(0f, 0f, 0f, h,
            if (dunkel) intArrayOf(Color.rgb(2, 6, 10), Color.rgb(4, 18, 24), Color.rgb(7, 22, 28))
            else intArrayOf(Color.rgb(234, 244, 242), Color.rgb(243, 241, 247), Color.rgb(246, 242, 236)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, b, h, stift)
        stift.shader = null
        if (dunkel) for (i in 0 until 60) {
            stift.color = weiss(0.35f * zufall(i + 40.0).toFloat())
            c.drawCircle(zufall(i + 7.0).toFloat() * b, zufall(i + 77.0).toFloat() * h * 0.7f, 0.9f * dichte, stift)
        }
        val t = tMs / 1000.0
        val schritte = 28
        for (band in 0 until 3) {
            val basis = h * (0.16f + 0.11f * band)
            val dicke = h * (0.20f + 0.05f * band)
            val amp = h * 0.045f
            fun oben(v: Double) = (basis + amp * (sin(2 * PI * v * 1.2 + t * 0.21 + band * 1.7) +
                    0.5 * sin(2 * PI * v * 2.7 - t * 0.33 + band))).toFloat()
            form.reset()
            for (i in 0..schritte) {
                val v = i.toDouble() / schritte
                if (i == 0) form.moveTo((v * b).toFloat(), oben(v)) else form.lineTo((v * b).toFloat(), oben(v))
            }
            for (i in schritte downTo 0) {
                val v = i.toDouble() / schritte
                form.lineTo((v * b).toFloat(), oben(v) + dicke * (0.75f + 0.25f * sin(2 * PI * v * 1.6 + t * 0.17 + band).toFloat()))
            }
            form.close()
            val staerke = (if (dunkel) 0.42f else 0.30f) * (0.75f + 0.25f * sin(t * 0.4 + band * 2).toFloat())
            val f = polarFarben[band]
            stift.shader = LinearGradient(0f, basis - amp * 1.5f, 0f, basis + amp * 1.5f + dicke,
                intArrayOf(mit(f, 0f), mit(f, 0.55f * staerke), mit(f, staerke), mit(f, 0f)),
                floatArrayOf(0f, 0.55f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(form, stift)
            stift.shader = null
        }
    }

    fun sterne(c: Canvas, t: Double) {
        c.drawColor(Color.rgb(5, 10, 28))
        val b = c.width.toFloat(); val h = c.height.toFloat(); val s = (t / 1000.0).toFloat()
        for (st in sterne) {
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(s * st[4] + st[3]))
            stift.color = weiss(a)
            c.drawCircle(st[0] * b, st[1] * h, st[2] * dichte * 0.8f, stift)
        }
    }
}


// ============================================================================
//  (auf dem Startbildschirm liegt er nicht unter dem Launcher). Daten = letzter geladener
// ============================================================================
/** Ist unser Live-Hintergrund eingestellt? (Systemabfrage - nur alle 30 s wirklich nachfragen) */
object EigeneTapete {
    @Volatile private var wert = false
    @Volatile private var zeit = 0L
    fun aktiv(ctx: android.content.Context): Boolean {
        val jetzt = SystemClock.uptimeMillis()
        if (zeit == 0L || jetzt - zeit > 30_000L) {
            wert = try { android.app.WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName } catch (e: Exception) { false }
            zeit = jetzt
        }
        return wert
    }
}



// ============================================================================
//  Sperrbildschirm als Bild: Samsung zeigt den Live-Hintergrund auf dem Sperrbildschirm nicht immer
//  und als Sperrbildschirm-Hintergrund gesetzt. Nur wenn sich etwas geaendert hat.
// ============================================================================
object SperrBild {
    @Volatile private var letzterSchluessel = ""
    @Volatile var eingabe = false
    private val EINGABE = Regex("Pattern|PIN|Pin(View|Pad)|Password|Bouncer|Muster|Passwort|Kennwort")

    /** vom Bedienungshilfe-Dienst: erscheint die Muster-/PIN-Eingabe auf dem Sperrbildschirm? */
    fun ereignis(e: android.view.accessibility.AccessibilityEvent?, ctx: android.content.Context) {
        if (e == null || eingabe) return
        if (e.packageName?.toString() != "com.android.systemui") return
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java) ?: return
        if (!km.isKeyguardLocked) return
        val hinweis = (e.className?.toString() ?: "") + " " + e.text.joinToString(" ") + " " + (e.contentDescription ?: "")
        if (!EINGABE.containsMatchIn(hinweis)) return
        eingabe = true
    }

    /** Bildschirm aus: naechstes Mal wieder mit Plan */
    fun zuruecksetzen(ctx: android.content.Context) {
        eingabe = false
        aktualisieren(ctx)
    }
    private val faden by lazy { android.os.HandlerThread("SperrBild").apply { start() } }
    private val arbeit by lazy { Handler(faden.looper) }

    fun an(ctx: android.content.Context) = ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).let {
        // Atelier: erst nach Einschalten (setzt sonst ungefragt das Sperrbild); eigenes Sperr-Bild/-Video hat Vorrang
        it.getBoolean("sperrBild", false) && (it.getString("sperrMedium", "") ?: "") == ""
    }

    /** im Hintergrund malen und setzen (vom Dienst beim Bildschirm-Aus aufgerufen) */
    fun aktualisieren(ctx: android.content.Context, erzwingen: Boolean = false) {
        val app = ctx.applicationContext
        if (!an(app)) return
        arbeit.post { try { setzen(app, erzwingen) } catch (e: Throwable) { } }
    }

    private fun setzen(ctx: android.content.Context, erzwingen: Boolean) {
        val p = ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
        val thema = p.getString("thema", "{}") ?: "{}"
        val schluessel = thema.hashCode().toString() + "|" + Papier.istAn(ctx) + "|" + p.getInt("welleAkzent", 0) + "|" + GlasFoto.datei(ctx).lastModified() + "|" + EigeneDateien.bild(ctx).lastModified()
        if (!erzwingen && schluessel == letzterSchluessel) return
        val t = try { org.json.JSONObject(thema) } catch (e: Exception) { org.json.JSONObject() }
        val art = t.optString("hintergrund", "wellen")
        val dunkel = when (t.optInt("modus", 1)) {
            0 -> false; 1 -> true
            else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        // Bewegte Wellen: laeuft unser Live-Hintergrund, eigenes Sperrbild entfernen -> der Sperrbildschirm zeigt
        // dann den Live-Hintergrund (Wellen bewegen sich). Den Plan malt die Live-Ebene bzw. der Live-Hintergrund.
        val tapeten = android.app.WallpaperManager.getInstance(ctx)
        val eigeneTapete = try { tapeten.wallpaperInfo?.packageName == ctx.packageName } catch (e: Exception) { false }
        if (p.getBoolean("sperrBewegt", true) && eigeneTapete && art in setOf("wellen", "eis", "pixel", "polar", "sterne", "swiss", "geld", "glas")) {
            if (letzterSchluessel != "bewegt" || erzwingen) {
                try { tapeten.clear(android.app.WallpaperManager.FLAG_LOCK) } catch (e: Exception) { }
                letzterSchluessel = "bewegt"
            }
            return
        }
        val akzent = t.optInt("akzent", 0)
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        val (b, h) = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
                     else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val bild = android.graphics.Bitmap.createBitmap(b, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bild)
        val dichte = ctx.resources.displayMetrics.density
        val andere = AndereZeichner(dichte).apply {
            this.dunkel = dunkel
            swissRot = if (akzent != 0) akzent else 0xFFE2001A.toInt()
            eisFarbe = p.getInt("eisFarbe", 0xFFA8E6FF.toInt())
            pixelFarbe = p.getInt("pixelFarbe", 0xFF4FE3FF.toInt())
            schnee = p.getBoolean("eisSchnee", true)
        }
        when (art) {
            "swiss" -> andere.swiss(c)
            "geld" -> GeldZeichner(dichte).hintergrund(c, b.toFloat(), h.toFloat(), dunkel, 0.0)
            "papier" -> { Skizze.laden(ctx); Skizze.papier(c, b.toFloat(), h.toFloat()) }
            "marmor" -> Deko.marmor(c, b.toFloat(), h.toFloat())
            "glas" -> GlasFoto.sofort(ctx)?.let { GlasFoto.malen(c, it, b.toFloat(), h.toFloat()) }
                ?: Glas.hintergrundCpu(c, b.toFloat(), h.toFloat(), dunkel)
            "polar" -> andere.polar(c, 0.0)
            "sterne" -> andere.sterne(c, 0.0)
            "eis" -> andere.eis(c, 0.0)
            "pixel" -> andere.pixel(c, 0.0)
            "wellen" -> WellenZeichner(dichte).apply {
                grund = if (dunkel) Color.BLACK else Color.WHITE
                farbe = wellenFarbe(t.optInt("welle", 0), dunkel)
            }.male(c, 0.0)
            "bild" -> { c.drawColor(Color.BLACK); EigeneDateien.bildLaden(EigeneDateien.bild(ctx), b, h)?.let { EigeneDateien.fuellend(c, it, b.toFloat(), h.toFloat()); it.recycle() } }
            // Video: erstes Bild des Videos, bildschirmfuellend
            "video" -> {
                c.drawColor(Color.BLACK)
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(EigeneDateien.video(ctx).path)
                    r.getFrameAtTime(0)?.let { f ->
                        val s = maxOf(b / f.width.toFloat(), h / f.height.toFloat())
                        val bw = f.width * s; val bh = f.height * s
                        c.drawBitmap(f, null, android.graphics.RectF((b - bw) / 2f, (h - bh) / 2f, (b + bw) / 2f, (h + bh) / 2f),
                                     Paint(Paint.FILTER_BITMAP_FLAG))
                        f.recycle()
                    }
                } catch (e: Exception) { } finally { try { r.release() } catch (e: Exception) { } }
            }
            else -> c.drawColor(if (dunkel) Color.BLACK else Color.WHITE)
        }
        if (Papier.istAn(ctx)) try { Papier.malen(c) } catch (e: Exception) { }
        android.app.WallpaperManager.getInstance(ctx).setBitmap(bild, null, true, android.app.WallpaperManager.FLAG_LOCK)
        letzterSchluessel = schluessel
        bild.recycle()
    }
}


/**
 * Sperrbildschirm mit eigenem Bild oder Video (Design → Sperrbildschirm → "Sperrbildschirm zeigt").
 * Bild: wird als Sperrbild gesetzt. Video: das Sperrbild wird entfernt, dann zeigt der Sperrbildschirm
 * den Live-Hintergrund - und der spielt, solange gesperrt ist, das eigene Video.
 */
object SperrMedium {
    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
    fun art(ctx: android.content.Context) = prefs(ctx).getString("sperrMedium", "") ?: ""
    fun liveAktiv(ctx: android.content.Context) = try {
        android.app.WallpaperManager.getInstance(ctx).wallpaperInfo?.packageName == ctx.packageName
    } catch (e: Exception) { false }

    fun setzen(ctx: android.content.Context, a: String) {
        prefs(ctx).edit().putString("sperrMedium", a).apply()
        if (a == "") SperrBild.aktualisieren(ctx, erzwingen = true) else anwenden(ctx)
    }

    /** gewaehlte Datei auf den Sperrbildschirm bringen (im Hintergrund) */
    fun anwenden(ctx: android.content.Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                val wm = android.app.WallpaperManager.getInstance(app)
                when (art(app)) {
                    "bild" -> {
                        val dm = app.resources.displayMetrics
                        EigeneDateien.bildLaden(EigeneDateien.sperrBild(app), dm.widthPixels, dm.heightPixels)?.let { bm ->
                            wm.setBitmap(bm, null, true, android.app.WallpaperManager.FLAG_LOCK)
                        }
                    }
                    "video" -> if (EigeneDateien.sperrVideo(app).exists()) wm.clear(android.app.WallpaperManager.FLAG_LOCK)
                }
            } catch (e: Throwable) { }
        }.start()
    }
}
