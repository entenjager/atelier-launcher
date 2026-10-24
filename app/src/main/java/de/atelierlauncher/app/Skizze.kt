package de.atelierlauncher.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ============================================================================
//  Gezeichnet: alles sieht aus wie mit blauem Kuli auf leicht gelblichem Papier gemalt.
//  Regeln:
//   - keine Linie ist gerade: jede schwankt sanft wie eine Hand, die es versucht
//   - Ecken treffen sich nicht ganz bzw. schiessen etwas ueber, Kreise schliessen nicht
//   - jede Linie hat ihr festes Zittern (gleiche Saat = gleiche Linie, nichts flackert)
//   - Flaechen werden schraffiert statt gefuellt
//   - ist etwas ganz voll, wird es NICHT mit Tinte zugemalt: dann kommen Striche drumherum
//  Die Linien werden einmal gebaut und gemerkt - beim Zeichnen kostet das fast nichts.
// ============================================================================
object Skizze {
    /** warmes Weiss mit einem Hauch Gelb */
    const val PAPIER_HELL = 0xFFFBF7E9.toInt()
    /** blaue Kuli-Tinte */
    const val TINTE_BLAU = 0xFF1F3A93.toInt()
    /** Kreidetafel: fast schwarz, ein Hauch Schiefer */
    const val TAFEL = 0xFF161918.toInt()
    /** weisse Kreide */
    const val KREIDE = 0xFFF1F0EA.toInt()
    /** Kreidetafel-Variante ("Gezeichnet" + dunkel): Tafel statt Papier, Kreide statt Tinte */
    @Volatile private var tafelMerk = false
    @Volatile private var prefs: android.content.SharedPreferences? = null
    /** fuer die Dienste (Randgesten, Lautstaerke, Hintergrund): Einstellung lesen koennen */
    fun laden(ctx: android.content.Context) {
        if (prefs == null) prefs = ctx.applicationContext.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE)
    }
    var tafel: Boolean
        get() = prefs?.getBoolean("skizzeTafel", tafelMerk) ?: tafelMerk
        set(v) { tafelMerk = v; prefs?.let { if (it.getBoolean("skizzeTafel", !v) != v) it.edit().putBoolean("skizzeTafel", v).apply() } }
    /** Grund (Papier bzw. Tafel) */
    val PAPIER: Int get() = if (tafel) TAFEL else PAPIER_HELL
    /** Strich (Tinte bzw. Kreide) */
    val TINTE: Int get() = if (tafel) KREIDE else TINTE_BLAU
    /** einmal gebaute Stifte auf die aktuelle Farbe bringen (Deckkraft bleibt) */
    fun nachfaerben(vararg stifte: Paint) { val t = TINTE; for (p in stifte) { val a = p.alpha; p.color = t; p.alpha = a } }
    fun papierFaerben(vararg flaechen: Paint) { val g = PAPIER; for (p in flaechen) { val a = p.alpha; p.color = g; p.alpha = a } }
    /** Gezeichnet-Look an (Farbschema "skizze") */
    @Volatile var an = false

    private const val PI_F = 3.1415927f

    /** kleiner, fester Zufall */
    class Zufall(saat: Int) {
        private var x = saat * -1640531535 + 0x2545F491
        init { if (x == 0) x = 1 }
        fun f(): Float {
            x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
            return (x ushr 8) / 16777216f
        }
        fun z(a: Float, b: Float) = a + (b - a) * f()
        fun i() = (f() * 1.0e7f).toInt()
    }

    /** Punkte (x0,y0,x1,y1,...) weich verbinden */
    private fun glatt(p: Path, xs: FloatArray, n: Int) {
        p.moveTo(xs[0], xs[1])
        if (n <= 2) { p.lineTo(xs[2], xs[3]); return }
        var i = 1
        while (i < n - 1) {
            val mx = (xs[2 * i] + xs[2 * i + 2]) / 2f
            val my = (xs[2 * i + 1] + xs[2 * i + 3]) / 2f
            p.quadTo(xs[2 * i], xs[2 * i + 1], mx, my)
            i++
        }
        p.lineTo(xs[2 * (n - 1)], xs[2 * (n - 1) + 1])
    }

    /** Linie von Hand: wackelt sanft, ist leicht gebogen, am Ende meist etwas zu lang. ruhig < 1 = weniger Wackeln */
    fun linie(p: Path, ax: Float, ay: Float, bx: Float, by: Float, saat: Int, d: Float, ruhig: Float = 1f) {
        val r = Zufall(saat)
        val dx = bx - ax; val dy = by - ay
        val l = hypot(dx, dy).coerceAtLeast(0.01f)
        val ux = dx / l; val uy = dy / l; val nx = -uy; val ny = ux
        val a = r.z(-1f, 2.2f) * d * ruhig
        val b = r.z(0.5f, 3.5f) * d * ruhig
        val x0 = ax - ux * a; val y0 = ay - uy * a
        val x1 = bx + ux * b; val y1 = by + uy * b
        val l2 = l + a + b
        val n = max(3, (l2 / (5f * d)).toInt() + 1).coerceAtMost(400)
        val amp = min(0.8f * d + l2 * 0.004f, 2f * d) * ruhig
        val bog = l2 * r.z(-0.012f, 0.012f) * ruhig
        val p1 = r.z(0f, 6.28f); val f1 = r.z(3f, 6f)
        val xs = FloatArray(2 * (n + 1))
        for (i in 0..n) {
            val t = i / n.toFloat()
            val o = bog * sin(t * PI_F) + amp * (0.7f * sin(t * PI_F * f1 + p1) + 0.3f * (r.f() - 0.5f))
            xs[2 * i] = x0 + (x1 - x0) * t + nx * o
            xs[2 * i + 1] = y0 + (y1 - y0) * t + ny * o
        }
        glatt(p, xs, n + 1)
    }

    /** Punkt auf dem Umfang einer Form: s = Weg ab Start -> x, y, Normale x, Normale y */
    private fun interface Umfang { fun punkt(s: Float, q: FloatArray) }

    /** einmal rundherum (und ein Stueck weiter): Anfang und Ende treffen sich nie ganz */
    private fun umlauf(p: Path, u: Umfang, umfang: Float, saat: Int, d: Float, drift: Float, extra: Float, amp: Float) {
        if (umfang <= 1f) return
        val r = Zufall(saat)
        val s0 = r.z(0f, umfang)
        val gesamt = umfang * (1f + extra + r.z(0f, 0.04f))
        val n = (gesamt / (4f * d)).toInt().coerceIn(16, 600)
        val dr = r.z(-drift, drift) * d
        val p1 = r.z(0f, 6.28f); val p2 = r.z(0f, 6.28f)
        val f1 = r.z(1f, 3f); val f2 = r.z(5f, 9f)
        val xs = FloatArray(2 * (n + 1))
        val q = FloatArray(4)
        for (i in 0..n) {
            val s = s0 + gesamt * i / n
            val t = (s - s0) / umfang
            val o = amp * d * (0.6f * sin(t * 6.283f * f1 + p1) + 0.4f * sin(t * 6.283f * f2 + p2)) + dr * t
            u.punkt(((s % umfang) + umfang) % umfang, q)
            xs[2 * i] = q[0] + q[2] * o
            xs[2 * i + 1] = q[1] + q[3] * o
        }
        glatt(p, xs, n + 1)
    }

    private fun kastenPunkt(s0: Float, l: Float, t: Float, r: Float, b: Float, rad: Float, gw: Float, gh: Float, bo: Float, q: FloatArray) {
        var s = s0
        fun ecke(cx: Float, cy: Float, a0: Float) {
            val a = a0 + (if (rad > 0f) s / rad else 0f)
            q[2] = cos(a); q[3] = sin(a)
            q[0] = cx + q[2] * rad; q[1] = cy + q[3] * rad
        }
        if (s < gw) { q[0] = l + rad + s; q[1] = t; q[2] = 0f; q[3] = -1f; return }
        s -= gw
        if (s < bo) { ecke(r - rad, t + rad, -PI_F / 2f); return }
        s -= bo
        if (s < gh) { q[0] = r; q[1] = t + rad + s; q[2] = 1f; q[3] = 0f; return }
        s -= gh
        if (s < bo) { ecke(r - rad, b - rad, 0f); return }
        s -= bo
        if (s < gw) { q[0] = r - rad - s; q[1] = b; q[2] = 0f; q[3] = 1f; return }
        s -= gw
        if (s < bo) { ecke(l + rad, b - rad, PI_F / 2f); return }
        s -= bo
        if (s < gh) { q[0] = l; q[1] = b - rad - s; q[2] = -1f; q[3] = 0f; return }
        s -= gh
        ecke(l + rad, t + rad, PI_F)
    }

    /** gezeichneter (abgerundeter) Kasten; aus = so viel groesser (fuer "Striche drumherum") */
    fun kasten(p: Path, l0: Float, t0: Float, r0: Float, b0: Float, rad0: Float, saat: Int, d: Float, aus: Float = 0f, wackel: Float = 1f) {
        val l = l0 - aus; val t = t0 - aus; val r = r0 + aus; val b = b0 + aus
        val w = r - l; val h = b - t
        if (w <= 1f || h <= 1f) return
        val rad = (rad0 + aus).coerceIn(0f, min(w, h) / 2f)
        val gw = w - 2 * rad; val gh = h - 2 * rad; val bo = (PI_F / 2f) * rad
        val umfang = 2 * gw + 2 * gh + 4 * bo
        umlauf(p, Umfang { s, q -> kastenPunkt(s, l, t, r, b, rad, gw, gh, bo, q) }, umfang, saat, d, 2.2f * wackel, 0.05f, 1.1f * wackel)
    }

    /** gezeichneter Kreis / Oval (schliesst nicht ganz, schiesst etwas ueber) */
    fun kreis(p: Path, cx: Float, cy: Float, rx: Float, ry: Float, saat: Int, d: Float, aus: Float = 0f) {
        val a = rx + aus; val b = ry + aus
        if (a <= 0.5f || b <= 0.5f) return
        val umfang = PI_F * (a + b)
        umlauf(p, Umfang { s, q ->
            val w = s / umfang * 2f * PI_F
            val c = cos(w); val sn = sin(w)
            q[0] = cx + c * a; q[1] = cy + sn * b; q[2] = c; q[3] = sn
        }, umfang, saat, d, 3f, 0.12f, 1.3f)
    }

    private fun schnitt(px: Float, py: Float, ux: Float, uy: Float, l: Float, t: Float, r: Float, b: Float, aus: FloatArray): Boolean {
        var lo = -1e9f; var hi = 1e9f
        if (abs(ux) < 1e-6f) { if (px < l || px > r) return false }
        else { val a = (l - px) / ux; val c = (r - px) / ux; lo = max(lo, min(a, c)); hi = min(hi, max(a, c)) }
        if (abs(uy) < 1e-6f) { if (py < t || py > b) return false }
        else { val a = (t - py) / uy; val c = (b - py) / uy; lo = max(lo, min(a, c)); hi = min(hi, max(a, c)) }
        aus[0] = lo; aus[1] = hi
        return hi - lo > 1f
    }

    /**
     * Schraffur: schraege, leicht wacklige Striche quer durch das Rechteck. Der Aufrufer beschneidet sie auf
     * die Form (clipPath). Die Striche enden nicht alle genau am Rand - wie von Hand.
     */
    fun schraffur(p: Path, l: Float, t: Float, r: Float, b: Float, saat: Int, d: Float, abstand: Float = 5f, winkel: Float = -55f) {
        val z = Zufall(saat)
        val a = winkel * PI_F / 180f
        val ux = cos(a); val uy = sin(a)
        val cx = (l + r) / 2f; val cy = (t + b) / 2f
        val diag = hypot(r - l, b - t) / 2f + 4f * d
        val st = FloatArray(2)
        var k = -diag
        var i = 0
        while (k <= diag && i < 500) {
            val px = cx - uy * k; val py = cy + ux * k
            if (schnitt(px, py, ux, uy, l, t, r, b, st)) {
                val g1 = z.z(-1f, 2.5f) * d; val g2 = z.z(-1f, 2.5f) * d
                if (st[1] - st[0] > g1 + g2 + 2f * d)
                    linie(p, px + ux * (st[0] + g1), py + uy * (st[0] + g1), px + ux * (st[1] - g2), py + uy * (st[1] - g2), z.i(), d, 0.5f)
            }
            k += abstand * d * z.z(0.8f, 1.25f)
            i++
        }
    }

    /** Kuli-Stift: runde Enden; haupt = kraeftig, nach = zweiter, ganz leichter Strich */
    fun stift(d: Float, breite: Float = 1.5f, farbe: Int = TINTE, alpha: Int = 235) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = breite * d; color = farbe; this.alpha = alpha.coerceIn(0, 255)
    }

    // ---------------------------------------------------------------- Papier
    @Volatile private var korn: Bitmap? = null
    @Volatile private var kornTafel: Bitmap? = null
    private fun korn(): Bitmap {
        if (tafel) {
            // Kreidestaub: nur helle Puenktchen
            kornTafel?.let { return it }
            val g = 128
            val px = IntArray(g * g)
            val r = Zufall(9)
            for (i in px.indices) { val v = r.f(); px[i] = ((if (v < 0.5f) (v * 26f).toInt() else 0) shl 24) or 0xFFFFFF }
            return Bitmap.createBitmap(px, g, g, Bitmap.Config.ARGB_8888).also { kornTafel = it }
        }
        korn?.let { return it }
        val g = 128
        val px = IntArray(g * g)
        val r = Zufall(7)
        for (i in px.indices) {
            val v = r.f()
            val a = if (v < 0.55f) (v * 22f).toInt() else 0
            px[i] = (a shl 24) or (if (r.f() < 0.6f) 0x6B5A2E else 0xFFFFFF)
        }
        val bm = Bitmap.createBitmap(px, g, g, Bitmap.Config.ARGB_8888)
        korn = bm
        return bm
    }

    /** leicht gelbliches Papier mit feinem Korn und ein paar ganz weichen Flecken (Tafel: verwischte Kreidereste) */
    fun papier(c: Canvas, w: Float, h: Float) {
        c.drawColor(PAPIER)
        val fl = if (tafel) intArrayOf(0x0DFFFFFF, 0x00FFFFFF) else intArrayOf(0x1CE6D49C, 0x00E6D49C)
        val fleck = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Zufall(31)
        repeat(4) {
            val x = r.z(0f, w); val y = r.z(0f, h); val rad = r.z(0.25f, 0.5f) * maxOf(w, h)
            fleck.shader = RadialGradient(x, y, rad, fl, null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, fleck)
        }
        val k = Paint()
        k.shader = BitmapShader(korn(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, w, h, k)
    }

    // ---------------------------------------------------------------- Handschrift fuer die Dienste (Lautstaerke)
    @Volatile var schrift: android.graphics.Typeface? = null
    fun schriftLaden(ctx: android.content.Context) {
        if (schrift != null || Build.VERSION.SDK_INT < 26) return
        try {
            val id = ctx.resources.getIdentifier("kalam_regular", "font", ctx.packageName)
            if (id != 0) schrift = ctx.resources.getFont(id)
        } catch (e: Exception) { }
    }

    /** Wackel-Filter fuer Bilder (Icons): biegt gerade Linien des Logos leicht (ab Android 13) */
    const val WACKEL = """
        uniform shader bild;
        uniform float amp;
        uniform float ph;
        half4 main(float2 p) {
            float2 o = float2(sin(p.y * 0.11 + ph) + 0.5 * sin(p.y * 0.29 + ph * 2.3),
                              sin(p.x * 0.13 + ph * 1.7) + 0.5 * sin(p.x * 0.31 + ph * 0.6)) * amp;
            return bild.eval(p + o);
        }
    """
}

/**
 * Kleiner gezeichneter Kuli, der an der Stelle mitlaeuft, wo gerade gezeichnet wird - als ob die Linien
 * in diesem Moment entstehen. Spitze bei (x, y), der Stift liegt schraeg wie in einer rechten Hand.
 */
class Kuli(private val d: Float) {
    private val koerper = Path()
    private val spitze = Path()
    private val teile = Path()
    private val stift = Skizze.stift(d, 1.4f)
    private val papier = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skizze.PAPIER }
    private val tinte = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skizze.TINTE }
    private val kegel = Path()
    init {
        val l = 14f * d; val r = 74f * d; val h = 6f * d
        Skizze.kasten(koerper, l, -h, r, h, 4f * d, 901, d, wackel = 0.25f)
        Skizze.linie(spitze, 0.5f * d, 0f, l, -h, 902, d, 0.15f)
        Skizze.linie(spitze, 0.5f * d, 0f, l, h, 903, d, 0.15f)
        // Griffring und Clip
        Skizze.linie(teile, l + 8f * d, -h, l + 8f * d, h, 904, d, 0.1f)
        Skizze.linie(teile, r - 20f * d, -h - 2.5f * d, r - 4f * d, -h - 2.5f * d, 905, d, 0.1f)
        Skizze.linie(teile, r - 20f * d, -h - 2.5f * d, r - 20f * d, -h, 906, d, 0.05f)
        kegel.moveTo(0f, 0f); kegel.lineTo(l, -h); kegel.lineTo(l, h); kegel.close()
    }
    fun malen(c: Canvas, x: Float, y: Float, winkel: Float, deckung: Float) {
        if (deckung <= 0.01f) return
        val a = (255 * deckung.coerceIn(0f, 1f)).toInt()
        Skizze.papierFaerben(papier); Skizze.nachfaerben(stift, tinte)
        c.save(); c.translate(x, y); c.rotate(winkel)
        papier.alpha = a
        c.drawPath(kegel, papier); c.drawPath(koerper, papier)
        stift.alpha = a
        c.drawPath(koerper, stift); c.drawPath(spitze, stift); c.drawPath(teile, stift)
        tinte.alpha = a
        c.drawCircle(0f, 0f, 1.7f * d, tinte)
        c.restore()
    }
}

/** gemerkte Linien einer Form (neu nur, wenn sich die Groesse aendert) */
class SkizzeSpeicher {
    val haupt = Path()
    val nach = Path()
    private var w = -1f
    private var h = -1f
    private var k = 0
    val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    /** Papier innen (jedes gezeichnete Viereck/jeder Kreis ist mit Papier gefuellt) */
    val pf = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Skizze.PAPIER }
    fun holen(w: Float, h: Float, schluessel: Int, bauen: (Path, Path) -> Unit) {
        if (w == this.w && h == this.h && schluessel == k) return
        this.w = w; this.h = h; k = schluessel
        haupt.reset(); nach.reset()
        bauen(haupt, nach)
    }
    fun stift(d: Float, farbe: Int, deckung: Float, breite: Float = 1.5f) {
        p1.color = farbe; p1.alpha = (235 * deckung).toInt().coerceIn(0, 255); p1.strokeWidth = breite * d
        p2.color = farbe; p2.alpha = (70 * deckung).toInt().coerceIn(0, 255); p2.strokeWidth = 0.9f * d
        pf.color = Skizze.PAPIER
        pf.alpha = (255 * deckung).toInt().coerceIn(0, 255)
    }
}

/**
 * Gezeichneter Rahmen hinter einem Element: wackliger Kasten (oder Kreis), mit Kuli nachgezogen.
 * aus = so viel ragt er ueber das Element hinaus (verschiebt nichts), deckung = 0..1 (zum Einblenden).
 */
fun Modifier.skizzeRahmen(rad: Dp = 18.dp, aus: Dp = 0.dp, kreis: Boolean = false, saat: Int = 0,
                          deckung: () -> Float = { 1f }): Modifier = composed {
    val s = if (saat != 0) saat else androidx.compose.runtime.currentCompositeKeyHash
    val p = LocalPalette.current
    val sp = remember { SkizzeSpeicher() }
    this.drawBehind {
        val de = deckung().coerceIn(0f, 1f)
        if (de <= 0.01f) return@drawBehind
        val d = density
        val a = aus.toPx(); val r = rad.toPx()
        val w = size.width; val h = size.height
        val s2 = s
        sp.holen(w, h, s2 * 31 + (if (kreis) 1 else 0) + (a * 7f).toInt()) { haupt, nach ->
            if (kreis) {
                Skizze.kreis(haupt, w / 2f, h / 2f, w / 2f, h / 2f, s2, d, a)
                Skizze.kreis(nach, w / 2f, h / 2f, w / 2f, h / 2f, s2 + 77, d, a + 0.8f * d)
            } else {
                Skizze.kasten(haupt, 0f, 0f, w, h, r, s2, d, a)
                Skizze.kasten(nach, 0f, 0f, w, h, r, s2 + 77, d, a + 0.8f * d)
            }
        }
        sp.stift(d, p.text.toArgb(), de)
        drawIntoCanvas { c ->
            c.nativeCanvas.drawPath(sp.haupt, sp.pf)
            c.nativeCanvas.drawPath(sp.haupt, sp.p1)
            c.nativeCanvas.drawPath(sp.nach, sp.p2)
        }
    }
}

/** Bild (Icon) leicht verbogen, als waere es von Hand nachgezogen - ab Android 13, sonst unveraendert */
fun Modifier.wackeln(saat: Int): Modifier = composed {
    if (Build.VERSION.SDK_INT < 33) return@composed this
    val d = androidx.compose.ui.platform.LocalDensity.current.density
    val eff = remember(saat) {
        try {
            val sh = android.graphics.RuntimeShader(Skizze.WACKEL)
            sh.setFloatUniform("amp", 0.9f * d)
            sh.setFloatUniform("ph", (abs(saat) % 1000) / 159f)
            android.graphics.RenderEffect.createRuntimeShaderEffect(sh, "bild").asComposeRenderEffect()
        } catch (e: Throwable) { null }
    }
    if (eff == null) this else this.graphicsLayer { renderEffect = eff }
}

/** gezeichnetes Kaestchen statt Schalter: an = Haken, der ueber das Kaestchen hinausschiesst */
@Composable
fun SkizzeHaken(an: Boolean, saat: Int) {
    val p = LocalPalette.current
    val sp = remember { SkizzeSpeicher() }
    androidx.compose.foundation.Canvas(Modifier.size(28.dp)) {
        val d = density; val w = size.width; val h = size.height
        sp.holen(w, h, saat * 2 + (if (an) 1 else 0)) { haupt, nach ->
            Skizze.kasten(haupt, 3f * d, 5f * d, w - 3f * d, h - 1f * d, 4f * d, saat, d)
            Skizze.kasten(nach, 3f * d, 5f * d, w - 3f * d, h - 1f * d, 4f * d, saat + 77, d, 0.6f * d)
            if (an) {
                Skizze.linie(haupt, w * 0.24f, h * 0.55f, w * 0.44f, h * 0.80f, saat + 3, d, 0.4f)
                Skizze.linie(haupt, w * 0.44f, h * 0.80f, w * 0.95f, h * 0.02f, saat + 4, d, 0.6f)
            }
        }
        sp.stift(d, p.text.toArgb(), 1f, 1.6f)
        drawIntoCanvas { c ->
            c.nativeCanvas.drawPath(sp.haupt, sp.pf)
            c.nativeCanvas.drawPath(sp.haupt, sp.p1)
            c.nativeCanvas.drawPath(sp.nach, sp.p2)
        }
    }
}

/** Papier als Hintergrund */
@Composable
fun PapierHintergrund(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        drawIntoCanvas { Skizze.papier(it.nativeCanvas, size.width, size.height) }
    }
}
