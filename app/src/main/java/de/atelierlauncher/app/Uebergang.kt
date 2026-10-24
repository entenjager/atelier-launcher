package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

// ============================================================================
//  Grosse Kante bei Randgesten (Stil "Grosse Welle" / "Grosse Swiss-Kante" in den Randgesten-Einstellungen):
//  laeuft mit dem Finger bis zu einem Fuenftel der Breite rein und wird dabei durchsichtiger.
//  Bei Home ist dahinter der Startbildschirm (Foto vom letzten Mal), sonst die Themenfarbe.
//  Ausgeloest = Kante loest sich auf, abgebrochen = Kante zieht sich zurueck.
// ============================================================================
object Uebergang {
    private val haupt = Handler(Looper.getMainLooper())
    /** je Rand eine eigene Kante -> links und rechts gleichzeitig geht ohne Durcheinander */
    private val ansichten = HashMap<Int, Wischer>()        // Schluessel: 0 rechts, 1 links, 2 unten
    private fun schl(vonLinks: Boolean, unten: Boolean) = if (unten) 2 else if (vonLinks) 1 else 0
    /** Foto vom Startbildschirm (wird gemacht, wenn der Launcher vorne ist) */
    private var homeBild: Bitmap? = null
    private var fotoZeit = 0L

    fun aktiv(vonLinks: Boolean, unten: Boolean = false) = ansichten[schl(vonLinks, unten)]?.let { !it.endet && !it.ruht } == true

    /** Launcher ist gerade vorne: kurz danach ein frisches Foto vom Startbildschirm machen */
    fun homeFotoSpaeter(s: AccessibilityService?, nochVorne: () -> Boolean) {
        if (s == null || Build.VERSION.SDK_INT < 30) return
        // nur bei "Grosse Welle"/"Swiss" (sonst nie gebraucht) und hoechstens alle 10 Minuten -
        // ein Bildschirmfoto direkt nach dem Entsperren hat sichtbar geruckelt
        if (Randgesten.gross == null) return
        if (homeBild != null && SystemClock.uptimeMillis() - fotoZeit < 10 * 60_000L) return
        haupt.postDelayed({
            if (!nochVorne() || ansichten.values.any { !it.ruht }) return@postDelayed
            if (SystemClock.uptimeMillis() - fotoZeit < 3000) return@postDelayed
            fotoZeit = SystemClock.uptimeMillis()
            try {
                s.takeScreenshot(Display.DEFAULT_DISPLAY, s.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                        val b = try { Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace) } catch (e: Exception) { null }
                        try { r.hardwareBuffer.close() } catch (e: Exception) { }
                        if (b != null && nochVorne()) homeBild = b
                    }
                    override fun onFailure(code: Int) { }
                })
            } catch (e: Exception) { }
        }, 2500)
    }

    /** Finger zieht: Kante folgt; zug = wie weit der Finger vom Rand weg ist; art = "welle" / "swiss" */
    fun ziehen(s: AccessibilityService, vonLinks: Boolean, zug: Float, art: String, home: Boolean, unten: Boolean = false) {
        val wm = s.getSystemService(WindowManager::class.java) ?: return
        val gr = if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds else Rect(0, 0, s.resources.displayMetrics.widthPixels, s.resources.displayMetrics.heightPixels)
        var v = ansichten[schl(vonLinks, unten)]
        if (v == null) {
            // Fenster nur EINMAL anlegen (Anlegen kostet ~150 ms) - danach nur noch gross (Geste) / 1 Pixel (Ruhe)
            val neu = Wischer(s, art, vonLinks || unten, unten)
            val lp = WindowManager.LayoutParams(
                gr.width(), gr.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                windowAnimations = 0
                title = "Randkante"
            }
            neu.fenster = lp; neu.wm = wm
            try { wm.addView(neu, lp) } catch (e: Exception) { return }
            ansichten[schl(vonLinks, unten)] = neu
            v = neu
        }
        // neue Geste (oder anderer Stil): frisch anfangen, Fenster wieder auf volle Groesse
        if (v.ruht || v.endet || v.art != art) v.neuStart(art, gr.width(), gr.height())
        // bei Home: dahinter der Startbildschirm, sonst die Themenfarbe (wohin Zurueck fuehrt, weiss vorher keiner)
        v.bild = if (home) homeBild else null
        v.ziel = zug * 1.1f
    }

    /** zu weit gezogen: Kante verschwindet sofort (ohne Funktion) */
    fun ausblenden(vonLinks: Boolean, unten: Boolean = false) { ansichten[schl(vonLinks, unten)]?.let { if (!it.endet && !it.ruht) it.ausblenden() } }

    /** Geste abgebrochen: Kante zieht sich zurueck */
    fun zurueck(vonLinks: Boolean, unten: Boolean = false) { ansichten[schl(vonLinks, unten)]?.let { if (!it.endet && !it.ruht) it.zurueck() } }

    /** ganz schnell ausgeloest: Kante faehrt von selbst schnell rein (schnell gezeichnet), dann wie ausgeloest */
    fun blitz(s: AccessibilityService, vonLinks: Boolean, art: String, home: Boolean, unten: Boolean = false, aktion: () -> Unit) {
        ziehen(s, vonLinks, 0f, art, home, unten)          // Fenster sicher da (und frisch, falls es ruhte)
        val v = ansichten[schl(vonLinks, unten)]
        if (v == null) { aktion(); return }
        v.blitz(aktion)
    }

    /** Geste ausgeloest: Aktion ausfuehren, Kante loest sich auf */
    fun durch(vonLinks: Boolean, unten: Boolean = false, aktion: () -> Unit) {
        val v = ansichten[schl(vonLinks, unten)]
        if (v == null || v.endet || v.ruht) { aktion(); return }
        v.durch(aktion)
    }

    /** vonUnten: alles wird gedreht gemalt - der "linke Rand" ist dann der untere Bildschirmrand */
    internal class Wischer(c: Context, var art: String, val vonLinks: Boolean, val vonUnten: Boolean = false) : View(c) {
        var fenster: WindowManager.LayoutParams? = null
        var wm: WindowManager? = null
        /** in Ruhe: Fenster ist nur 1 Pixel gross und malt nichts */
        var ruht = true            // frisch angelegt: erste Geste ruft neuStart (Farben laden)
        /** Zaehler: Animationen einer alten Geste duerfen die neue nicht beenden */
        private var nr = 0
        private var lauf: ValueAnimator? = null

        /** neue Geste: alles zuruecksetzen, Fenster auf volle Groesse */
        fun neuStart(neueArt: String, b: Int, h: Int) {
            nr++
            lauf?.cancel(); lauf = null
            art = neueArt
            auffrischen()
            endet = false; animiert = false; aus = 1f
            e = 0f; tempo = 0f; letzte = 0L; ziel = 0f; bild = null
            schwellen = FloatArray(0); spalten = 0; zeilen = 0; felder.clear(); saat = zufall.nextInt()
            bruchStart = 0L
            SK_DAUER = SK_NORMAL
            val lp = fenster
            if (lp != null && (lp.width != b || lp.height != h)) {
                lp.width = b; lp.height = h
                try { wm?.updateViewLayout(this, lp) } catch (ex: Exception) { }
            }
            ruht = false
            invalidate()
        }

        /** Geste vorbei: Fenster auf 1 Pixel (bleibt bestehen, kostet so nichts) */
        private fun ruhen() {
            if (ruht) return
            ruht = true; endet = true
            lauf = null
            val lp = fenster ?: return
            lp.width = 1; lp.height = 1
            try { wm?.updateViewLayout(this, lp) } catch (ex: Exception) { }
        }

        var bild: Bitmap? = null
            set(b) { if (field !== b) { field = b; invalidate() } }
        var endet = false
        /** Ziel-Abstand der Kante vom Rand (Finger) */
        var ziel = 0f
            set(w) { field = w.coerceIn(0f, maxWeg); postInvalidateOnAnimation() }
        private var e = 0f; private var tempo = 0f
        private var animiert = false
        /** zusaetzliches Ausblenden beim Loslassen (1 = voll da) */
        private var aus = 1f
        /** weiter als ein Fuenftel der Breite geht die Kante nie rein */
        private val maxWeg get() = (if (width > 1) width else resources.displayMetrics.widthPixels) / 5f
        private var letzte = 0L
        private val dp = c.resources.displayMetrics.density
        private val pfad = Path()
        private val kante = Path()
        private val rahmen = Rect()
        private val stift = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fuell = Paint(Paint.ANTI_ALIAS_FLAG)
        private val schleier = Paint()
        // Farben aus dem Theme - bei jeder neuen Geste frisch (Theme kann sich inzwischen geaendert haben)
        private var dunkel = true
        private var wellenF = 0
        private var akzentF = 0
        private var grund = 0
        private var tinte = 0
        private val geld by lazy { GeldZeichner(dp) }
        private fun auffrischen() {
            val thema = try {
                org.json.JSONObject(context.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("thema", "{}") ?: "{}")
            } catch (ex: Exception) { org.json.JSONObject() }
            dunkel = Hell.dunkel(context)
            wellenF = wellenFarbe(thema.optInt("welle", 0), dunkel)
            akzentF = thema.optInt("akzent", 0).let { a ->
                if (a != 0) a else if (Build.VERSION.SDK_INT >= 31) context.getColor(android.R.color.system_accent1_400) else 0xFFFF3B30.toInt()
            }
            grund = if (art == "swiss" || art == "felder") (if (dunkel) 0xFF0B0B0C.toInt() else 0xFFFFFFFF.toInt())
                    else (if (dunkel) 0xFF101418.toInt() else 0xFFF2F0EA.toInt())
            tinte = if (dunkel) Color.WHITE else 0xFF111111.toInt()
            if (art == "skizze") {
                Skizze.laden(context)
                grund = Skizze.PAPIER; tinte = Skizze.TINTE
                Skizze.nachfaerben(skStift); Skizze.papierFaerben(skPapier)
            }
        }
        // Swiss: Felder im Raster des Swiss-Hintergrunds (24 dp Rand, 4 Spalten, jede Spalte halbiert).
        // Jedes Feld hat eine eigene Zufallsschwelle -> die Felder ploppen unregelmaessig auf, keine gerade Kante.
        private val rasterRand = 24f * dp
        private val zelle get() = ((width - 2 * rasterRand) / 4f / 2f).coerceAtLeast(8f * dp)
        private val zufall = java.util.Random()
        private var schwellen = FloatArray(0)
        private var spalten = 0; private var zeilen = 0
        // Swiss-Linien: aufgetauchte Felder (x1, y1, x2, y2, Spalte, Zeile) - manche Seiten bleiben offen
        private val felder = ArrayList<FloatArray>()
        // Gezeichnet: jede Rasterkante einmal als Kuli-Linie gebaut und gemerkt (Nachbarfelder teilen sich die Kante)
        private val skPfade = HashMap<Long, Path>()
        private var skSaat = 0; private var skB = 0f; private var skH = 0f
        private val skStift = Skizze.stift(dp, 1.6f)
        private val skPapier = Paint().apply { color = Skizze.PAPIER }
        private val skZeit = HashMap<Long, Long>()
        private val skMass = android.graphics.PathMeasure()
        private val skTeil = Path()
        private val skPos = FloatArray(2)
        private val SK_NORMAL = 150L
        /** so lange wird eine Kante gezeichnet - beim schnellen Ausloesen viel kuerzer */
        private var SK_DAUER = SK_NORMAL
        private fun skSchl(i: Int, j: Int, richtung: Int) = (i.toLong() shl 32) or ((j.toLong() and 0x7FFFFFFF) shl 1) or richtung.toLong()
        private fun skKante(i: Int, j: Int, richtung: Int, ax: Float, ay: Float, bx: Float, by: Float): Path {
            val k = skSchl(i, j, richtung)
            return skPfade.getOrPut(k) {
                Path().also { Skizze.linie(it, ax, ay, bx, by, (k xor saat.toLong()).hashCode(), dp, 0.7f) }
            }
        }
        private var saat = zufall.nextInt()
        /** etwa jede vierte Rasterlinie fehlt -> manche Felder haben nur 3 (oder 2) Seiten */
        private fun offen(a: Int, b: Int, richtung: Int): Boolean {
            var x = a * 73856093 xor (b * 19349663) xor (richtung * 83492791) xor saat
            x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15)
            return (x and 0xFF) < 70
        }

        fun ausblenden() {
            endet = true; animiert = true
            val n = nr
            lauf = ValueAnimator.ofFloat(aus, 0f).apply {
                duration = 110
                addUpdateListener { aus = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (n == nr) ruhen() } })
                start()
            }
        }

        fun zurueck() {
            endet = true; animiert = true
            // faehrt sichtbar ganz an den Rand zurueck (auch aus kleiner Entfernung nicht schlagartig weg)
            val n = nr
            lauf = ValueAnimator.ofFloat(e, 0f).apply {
                duration = (180 + 160 * (e / maxWeg).coerceIn(0f, 1f)).toLong()
                interpolator = android.view.animation.PathInterpolator(0.3f, 0f, 0.2f, 1f)
                addUpdateListener { e = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (n == nr) ruhen() } })
                start()
            }
        }

        /** schnell ausgeloest: Kante faehrt in 170 ms bis ganz rein (Linien in 45 ms gezeichnet), Aktion sofort */
        fun blitz(aktion: () -> Unit) {
            endet = true; animiert = true
            SK_DAUER = 45L
            aktion()
            val e0 = e
            val e1 = maxOf(maxWeg, e0)
            nr++                              // alte Animationen duerfen das Fenster nicht mehr schliessen
            val n = nr
            lauf?.cancel()
            lauf = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 170L
                interpolator = android.view.animation.DecelerateInterpolator(1.6f)
                addUpdateListener { e = e0 + (e1 - e0) * (it.animatedValue as Float); invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { if (n == nr) durch { } }
                })
                start()
            }
        }

        fun durch(aktion: () -> Unit) {
            endet = true; animiert = true
            if (art == "riss") { bruchStart = SystemClock.uptimeMillis(); bruchE = e }
            aktion()
            // Kante laeuft mit dem Schwung des Fingers weiter (ueber das Fuenftel hinaus) und klingt weich aus:
            // erst gleitet sie, dann verblasst sie - kein abruptes Ende
            val e0 = e
            val schwung = (tempo * 0.10f).coerceIn(0f, maxWeg * 0.6f)
            // Probe: nach dem Ausloesen noch deutlich weiter nach innen (bis gut 2/5 der Breite), dabei verschwinden
            val e1 = maxOf(maxWeg, e0) + maxWeg * 0.9f + schwung
            val n = nr
            lauf = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (art == "welle") 560L else 480L
                interpolator = android.view.animation.PathInterpolator(0.2f, 0.7f, 0.3f, 1f)
                addUpdateListener {
                    val t = it.animatedValue as Float
                    e = e0 + (e1 - e0) * t
                    // Ausblenden beginnt sanft und wird erst zum Schluss ganz weg
                    val f = (it.animatedFraction).coerceIn(0f, 1f)
                    aus = 1f - f * f * (3f - 2f * f)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (n == nr) ruhen() } })
                start()
            }
            postDelayed({ if (n == nr) ruhen() }, 2000)   // Sicherheit
        }

        // ---- Kintsugi-Risse
        private var bruchStart = 0L
        private var bruchE = 0f
        private var rsSaat = 0; private var rsB = 0f; private var rsH = 0f
        private val rsRisse = Path()
        private val rsPlatte = Path()
        private val rsFront = Path()
        private val rsBild = Path()
        private val rsSpiegel = android.graphics.Matrix()
        private var rsSprung = FloatArray(0)
        private var rsTeil1 = 0.33f; private var rsTeil2 = 0.66f
        private val rsFuge = Deko.fugeStift()
        private val rsGlanz = Deko.glanzStift()
        private var rsGold: Paint? = null
        private val rsSchatten = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; color = Color.BLACK
            maskFilter = android.graphics.BlurMaskFilter(6f * c.resources.displayMetrics.density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val rsBildP = Paint(Paint.FILTER_BITMAP_FLAG)
        private val rsRahmen = android.graphics.RectF()

        /**
         * Gezeichnet in "Abstand vom Rand" (u) und y: u = 0 ist der Gesten-Rand. Rechts wird gespiegelt.
         * Die Platte reicht vom Rand bis zur zackigen Bruchkante (dem Finger); darin Marmor und Goldrisse.
         */
        private fun riss(cv: Canvas, w: Float, h: Float) {
            val jetzt = SystemClock.uptimeMillis()
            if (rsSaat != saat || rsB != w || rsH != h) {
                rsSaat = saat; rsB = w; rsH = h
                val z = Skizze.Zufall(saat)
                rsSprung = FloatArray(64) { z.z(-1f, 1f) }
                rsTeil1 = z.z(0.25f, 0.42f); rsTeil2 = z.z(0.58f, 0.75f)
                rsRisse.reset()
                // 6 Risse vom Rand nach innen, dazu Abzweige
                repeat(6) { i ->
                    val y0 = (i + z.z(0.15f, 0.85f)) / 6f * h
                    Deko.riss(rsRisse, 0f, y0, z.z(-0.55f, 0.55f), z.z(0.7f, 1.5f) * maxWeg * 1.4f, z, dp)
                }
                rsGold = Deko.goldStift(w, h)
            }
            val bruch = bruchStart != 0L
            val t = if (bruch) ((jetzt - bruchStart) / 480f).coerceIn(0f, 1f) else 0f
            if (bruch && t < 1f) postInvalidateOnAnimation()
            val eF = if (bruch) bruchE else e
            if (eF < 0.5f || (!bruch && aus <= 0.01f) || t >= 1f) return
            // Bruchkante: zackig, je weiter drin, desto groessere Zacken
            val schritt = 22f * dp
            val n = kotlin.math.ceil(h / schritt).toInt() + 1
            val amp = kotlin.math.min(eF * 0.28f, 15f * dp)
            rsPlatte.reset(); rsFront.reset()
            rsPlatte.moveTo(-2f * dp, -2f * dp)
            for (j in 0..n) {
                val y = j * schritt
                val u = eF + rsSprung[j % rsSprung.size] * amp
                rsPlatte.lineTo(u, y)
                if (j == 0) rsFront.moveTo(u, y) else rsFront.lineTo(u, y)
            }
            rsPlatte.lineTo(-2f * dp, h + schritt); rsPlatte.close()
            rsSpiegel.reset()
            if (!vonLinks) { rsSpiegel.setScale(-1f, 1f); rsSpiegel.postTranslate(w, 0f) }
            val marmor = Deko.marmorBild(width, height)
            rsRahmen.set(0f, 0f, w, h)
            val gold = rsGold ?: return

            if (!bruch) {
                val a = (255 * aus).toInt().coerceIn(0, 255)
                val n0 = cv.save()
                cv.concat(rsSpiegel)
                if (a < 255) cv.saveLayerAlpha(0f, 0f, w, h, a)
                // Schatten unter der Platte (sie liegt ueber der App)
                rsSchatten.strokeWidth = 8f * dp; rsSchatten.alpha = 120
                cv.save(); cv.translate(3f * dp, 2f * dp); cv.drawPath(rsFront, rsSchatten); cv.restore()
                cv.save(); cv.clipPath(rsPlatte)
                cv.drawBitmap(marmor, null, rsRahmen, rsBildP)
                Deko.rissMalen(cv, rsRisse, dp, rsFuge, gold, rsGlanz)
                cv.restore()
                // Bruchkante: Goldsaum
                Deko.rissMalen(cv, rsFront, dp, rsFuge, gold, rsGlanz, breite = 1.25f)
                cv.restoreToCount(n0)
                return
            }
            // ausgeloest: dahinter (bei Home) der Startbildschirm, die Platte zerbricht in drei Stuecke und faellt
            val b = bild
            if (b != null) {
                rsBild.reset(); rsPlatte.transform(rsSpiegel, rsBild)
                cv.save(); cv.clipPath(rsBild); fotoMalen(cv, b); cv.restore()
            }
            val n0 = cv.save()
            cv.concat(rsSpiegel)
            val grenzen = floatArrayOf(-schritt, rsTeil1 * h, rsTeil2 * h, h + schritt)
            val tt = t * t
            for (i in 0 until 3) {
                val y0 = grenzen[i]; val y1 = grenzen[i + 1]
                val cy = (y0 + y1) / 2f; val cu = eF / 2f
                val dreh = ((i - 1) * 9f + 5f) * t * (if (i % 2 == 0) 1f else -1f)
                val fallY: Float; val fallU: Float
                if (vonUnten) {
                    // von unten: die Stuecke fallen zurueck nach unten (zum Rand hin) und etwas zur Seite
                    fallU = -(eF + 120f * dp) * tt * (0.9f + 0.15f * i)
                    fallY = (i - 1) * 30f * dp * t
                } else {
                    fallY = h * 0.85f * tt * (0.8f + 0.2f * i)
                    fallU = -(14f * dp + i * 6f * dp) * t
                }
                val a = (255 * (1f - t * t * t)).toInt().coerceIn(0, 255)
                val n1 = cv.save()
                cv.translate(fallU, fallY)
                cv.rotate(dreh, cu, cy)
                cv.saveLayerAlpha(-w, -h, 2 * w, 2 * h, a)
                cv.clipRect(-w, y0, w, y1)
                cv.clipPath(rsPlatte)
                cv.drawBitmap(marmor, null, rsRahmen, rsBildP)
                Deko.rissMalen(cv, rsRisse, dp, rsFuge, gold, rsGlanz)
                Deko.rissMalen(cv, rsFront, dp, rsFuge, gold, rsGlanz, breite = 1.25f)
                cv.restoreToCount(n1)
            }
            cv.restoreToCount(n0)
        }

        override fun onDraw(cv: Canvas) {
            if (!vonUnten) { malen(cv, width.toFloat(), height.toFloat()); return }
            // von unten: um 90 Grad drehen -> "Abstand vom linken Rand" = Abstand vom unteren Rand
            val n = cv.save()
            cv.translate(0f, height.toFloat()); cv.rotate(-90f)
            malen(cv, height.toFloat(), width.toFloat())
            cv.restoreToCount(n)
        }

        /** Startbildschirm-Foto immer gerade (nicht mitgedreht) ueber den ganzen Bildschirm */
        private fun fotoMalen(cv: Canvas, b: Bitmap) {
            rahmen.set(0, 0, width, height)
            if (!vonUnten) { cv.drawBitmap(b, null, rahmen, null); return }
            cv.save(); cv.rotate(90f); cv.translate(0f, -height.toFloat())
            cv.drawBitmap(b, null, rahmen, null)
            cv.restore()
        }

        private fun malen(cv: Canvas, w: Float, h: Float) {
            if (ruht || width <= 1) return
            if (!animiert) {
                // weiche Feder Richtung Finger
                val jetzt = SystemClock.uptimeMillis()
                val dt = if (letzte == 0L) 0.016f else ((jetzt - letzte) / 1000f).coerceIn(0.004f, 0.034f)
                letzte = jetzt
                val a = 900f * (ziel - e) - 2f * kotlin.math.sqrt(900f) * 0.85f * tempo
                tempo += a * dt; e += tempo * dt
                if (kotlin.math.abs(ziel - e) > 0.5f || kotlin.math.abs(tempo) > 1f) postInvalidateOnAnimation()
            }
            // Kintsugi: eigenes Zeichnen (Marmorplatte mit Goldrissen, bricht beim Ausloesen heraus)
            if (art == "riss") { riss(cv, w, h); return }
            val f = (e / maxWeg).coerceIn(0f, 1f)          // 0 = am Rand, 1 = ganz drin (Fuenftel)
            if (e < 0.5f || aus <= 0.01f) return
            // je weiter gezogen, desto durchsichtiger
            val deck = ((1f - 0.8f * f) * aus).coerceIn(0f, 1f)
            cv.saveLayerAlpha(0f, 0f, w, h, (255 * deck).toInt())
            // x der Kante (vom Gesten-Rand aus gemessen)
            val x0 = if (vonLinks) e else w - e
            val bogen = (f * 2.5f).coerceIn(0.25f, 1f)
            val sgn = if (vonLinks) 1f else -1f      // Richtung in die App hinein
            pfad.reset(); kante.reset()
            val rand = if (vonLinks) 0f else w
            if (art == "geld") {
                // Banknote: hinter der Kante Guilloche-Papier (bei Home der Startbildschirm), vorne ein Zierband
                schleier.color = Color.BLACK; schleier.alpha = (60 * f).toInt()
                cv.drawRect(0f, 0f, w, h, schleier)
                cv.save()
                if (vonLinks) cv.clipRect(0f, 0f, x0, h) else cv.clipRect(x0, 0f, w, h)
                val b = bild
                if (b != null) fotoMalen(cv, b)
                else cv.drawBitmap(geld.statisch(w.toInt(), h.toInt(), dunkel), 0f, 0f, null)
                cv.restore()
                geld.band(cv, x0, h, sgn, dunkel, 1f, e)
                cv.restore()
                return
            }
            if (art == "swiss" || art == "felder" || art == "skizze") {
                val z = zelle
                // Spalten vom Gesten-Rand aus: erste = schmaler Rand (bis zur ersten Rasterlinie), dann ganze Felder
                val reichMax = maxOf(maxWeg, e)                 // beim Ausklingen auch weiter als ein Fuenftel
                val nS = 2 + kotlin.math.ceil((reichMax - rasterRand) / z).toInt()
                val nZ = kotlin.math.ceil((h - rasterRand) / z).toInt() + 2
                if (nS != spalten || nZ != zeilen) {
                    spalten = nS; zeilen = nZ
                    schwellen = FloatArray(nS * nZ) { zufall.nextFloat() }
                }
                val reichweite = e
                felder.clear()
                for (i in 0 until nS) {
                    val innen = if (i == 0) 0f else rasterRand + (i - 1) * z        // Abstand vom Rand (Feld-Anfang)
                    val aussen = if (i == 0) rasterRand else rasterRand + i * z
                    if (innen >= reichMax) break
                    for (j in 0 until nZ) {
                        val oben = if (j == 0) 0f else rasterRand + (j - 1) * z
                        val unten = if (j == 0) rasterRand else rasterRand + j * z
                        // Feld erscheint, wenn die Kante es zu einem zufaelligen Anteil ueberstrichen hat
                        val noetig = innen + (aussen - innen) * (0.15f + 0.85f * schwellen[i * nZ + j]) - z * 0.6f * schwellen[(i * nZ + j + 7) % schwellen.size]
                        if (reichweite < noetig.coerceAtLeast(2f * dp)) continue
                        val x1 = if (vonLinks) innen else w - aussen
                        val x2 = if (vonLinks) aussen else w - innen
                        pfad.addRect(x1, oben, x2, unten, Path.Direction.CW)
                        val frisch = (1f - (reichweite - noetig) / (2.5f * z)).coerceIn(0f, 1f)
                        felder.add(floatArrayOf(x1, oben, x2, unten, i.toFloat(), j.toFloat(), frisch))
                    }
                }
            } else {
                val amp = 16f * dp * bogen
                val ph = e / (55f * dp)
                val schritt = 5f * dp
                pfad.moveTo(rand, 0f)
                var y = 0f
                while (y <= h + schritt) {
                    val s = 0.7f * kotlin.math.sin(y / (130f * dp) * 6.283f + ph) + 0.3f * kotlin.math.sin(y / (57f * dp) * 6.283f - ph * 1.7f)
                    val xx = x0 + sgn * amp * (s + 1f)        // Wellenberge laufen voraus (in die App hinein)
                    pfad.lineTo(xx, y)
                    if (y == 0f) kante.moveTo(xx, y) else kante.lineTo(xx, y)
                    y += schritt
                }
                pfad.lineTo(rand, h + schritt); pfad.close()
            }
            // App-Seite leicht abdunkeln
            schleier.color = Color.BLACK; schleier.alpha = (60 * f).toInt()
            cv.drawRect(0f, 0f, w, h, schleier)
            if (art == "swiss") {
                // nur Linien wie im Swiss-Hintergrund: die Umrisse der zufaellig aufgetauchten Felder
                stift.color = tinte; stift.alpha = 255; stift.strokeWidth = 1.5f * dp
                stift.strokeCap = Paint.Cap.SQUARE
                for (fd in felder) {
                    val i = fd[4].toInt(); val j = fd[5].toInt()
                    // Kanten gehoeren zum Raster (nicht zum Feld) -> Nachbarn sind sich einig, ob eine Linie fehlt
                    if (!offen(i, j, 0)) cv.drawLine(fd[0], fd[1], fd[2], fd[1], stift)          // oben
                    if (!offen(i, j + 1, 0)) cv.drawLine(fd[0], fd[3], fd[2], fd[3], stift)      // unten
                    val innenX = if (vonLinks) fd[0] else fd[2]; val aussenX = if (vonLinks) fd[2] else fd[0]
                    if (!offen(i, j, 1)) cv.drawLine(innenX, fd[1], innenX, fd[3], stift)        // zum Rand hin
                    if (!offen(i + 1, j, 1)) cv.drawLine(aussenX, fd[1], aussenX, fd[3], stift)  // zur Mitte hin
                }
                stift.strokeCap = Paint.Cap.ROUND
            } else if (art == "skizze") {
                // der Startbildschirm (sonst Papier), jede Rasterkante wird in 150 ms mit Kuli gezogen, je weiter
                // gewischt, desto mehr werden die alten wegradiert (vom Linienanfang her); Vierecke mit allen
                // 4 Seiten haben Papier, das mit dem Radieren verschwindet. Zittern fest je Kante.
                if (skSaat != saat || skB != w || skH != h) { skSaat = saat; skB = w; skH = h; skPfade.clear(); skZeit.clear() }
                cv.save(); cv.clipPath(pfad)
                val b = bild
                if (b != null) fotoMalen(cv, b)
                else cv.drawColor(grund)
                cv.restore()
                val jetzt = SystemClock.uptimeMillis()
                var laeuft = false
                fun fertig(k: Long): Boolean { val t0 = skZeit[k] ?: return false; return jetzt - t0 >= SK_DAUER }
                for (fd in felder) {
                    if (fd[6] <= 0.05f) continue
                    val i = fd[4].toInt(); val j = fd[5].toInt()
                    if (!offen(i, j, 0) && !offen(i, j + 1, 0) && !offen(i, j, 1) && !offen(i + 1, j, 1) &&
                        fertig(skSchl(i, j, 0)) && fertig(skSchl(i, j + 1, 0)) && fertig(skSchl(i, j, 1)) && fertig(skSchl(i + 1, j, 1))) {
                        skPapier.alpha = (255 * (fd[6] * 1.6f).coerceAtMost(1f)).toInt()
                        cv.drawRect(fd[0], fd[1], fd[2], fd[3], skPapier)
                    }
                }
                skStift.alpha = 235
                fun ziehe(i: Int, j: Int, r: Int, ax: Float, ay: Float, bx: Float, by: Float, radiert: Float) {
                    val k = skSchl(i, j, r)
                    val pf = skKante(i, j, r, ax, ay, bx, by)
                    val t0 = skZeit.getOrPut(k) { jetzt }
                    val p = ((jetzt - t0) / SK_DAUER.toFloat()).coerceIn(0f, 1f)
                    if (p < 1f) laeuft = true
                    val von = 1f - (1f - radiert) * (1f - radiert)
                    val bis = 1f - (1f - p) * (1f - p)
                    if (bis <= von) return
                    if (von <= 0f && p >= 1f) { cv.drawPath(pf, skStift); return }
                    skMass.setPath(pf, false)
                    val len = skMass.length
                    skTeil.reset(); skMass.getSegment(len * von, len * bis, skTeil, true)
                    cv.drawPath(skTeil, skStift)
                }
                for (fd in felder) {
                    if (fd[6] <= 0.01f) continue
                    val i = fd[4].toInt(); val j = fd[5].toInt()
                    val r = 1f - fd[6]
                    val innenX = if (vonLinks) fd[0] else fd[2]; val aussenX = if (vonLinks) fd[2] else fd[0]
                    if (!offen(i, j, 0)) ziehe(i, j, 0, fd[0], fd[1], fd[2], fd[1], r)
                    if (!offen(i, j + 1, 0)) ziehe(i, j + 1, 0, fd[0], fd[3], fd[2], fd[3], r)
                    if (!offen(i, j, 1)) ziehe(i, j, 1, innenX, fd[1], innenX, fd[3], r)
                    if (!offen(i + 1, j, 1)) ziehe(i + 1, j, 1, aussenX, fd[1], aussenX, fd[3], r)
                }
                if (laeuft) postInvalidateOnAnimation()
            } else if (art == "felder") {
                // Felder gefuellt (Startbildschirm bzw. Swiss-Grund), darin feine Rasterlinien
                cv.save(); cv.clipPath(pfad)
                val b = bild
                if (b != null) fotoMalen(cv, b)
                else cv.drawColor(if (dunkel) 0xFF0B0B0C.toInt() else 0xFFFFFFFF.toInt())
                stift.color = tinte; stift.alpha = 34; stift.strokeWidth = 1f
                val z = zelle
                var gx = rasterRand - kotlin.math.ceil(rasterRand / z) * z
                while (gx <= w) { val xx = kotlin.math.floor(gx) + 0.5f; cv.drawLine(xx, 0f, xx, h, stift); gx += z }
                var gy = rasterRand - kotlin.math.ceil(rasterRand / z) * z
                while (gy <= h) { val yy = kotlin.math.floor(gy) + 0.5f; cv.drawLine(0f, yy, w, yy, stift); gy += z }
                cv.restore()
            } else {
                // hinter der Kante: Startbildschirm bzw. Themenfarbe
                cv.save(); cv.clipPath(pfad)
                val b = bild
                if (b != null) fotoMalen(cv, b)
                else cv.drawColor(grund)
                cv.restore()
                stift.color = wellenF
                stift.alpha = (230 * bogen.coerceAtLeast(0.35f)).toInt(); stift.strokeWidth = 3.5f * dp; cv.drawPath(kante, stift)
                cv.save(); cv.translate(sgn * 9f * dp, 0f)
                stift.alpha = (110 * bogen).toInt(); stift.strokeWidth = 1.6f * dp; cv.drawPath(kante, stift)
                cv.translate(sgn * 8f * dp, 0f)
                stift.alpha = (50 * bogen).toInt(); stift.strokeWidth = 1f * dp; cv.drawPath(kante, stift)
                cv.restore()
            }
            cv.restore()
        }
    }
}
