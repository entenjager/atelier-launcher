package de.atelierlauncher.app

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
//  Art Déco: schwarzer Marmor, Gold, Elfenbein. Dazu Kintsugi - Risse, die mit Gold gekittet sind
//  (Randgeste, Lautstaerke, Sperr-Uebergang, Icons).
// ============================================================================
object Deko {
    const val SCHWARZ = 0xFF0B0B0D.toInt()
    const val GOLD = 0xFFC9A45C.toInt()
    const val GOLD_HELL = 0xFFF0DCA0.toInt()
    const val GOLD_DUNKEL = 0xFF7E5E28.toInt()
    const val ELFENBEIN = 0xFFF1E7D2.toInt()
    /** Art-Déco-Look an (Farbschema "deko") */
    @Volatile var an = false

    /** Gold als Verlauf (wie poliertes Metall: dunkel - hell - gold - hell - dunkel) */
    fun goldShader(x0: Float, y0: Float, x1: Float, y1: Float): Shader = LinearGradient(
        x0, y0, x1, y1, intArrayOf(GOLD_DUNKEL, GOLD_HELL, GOLD, GOLD_HELL, GOLD_DUNKEL),
        floatArrayOf(0f, 0.28f, 0.5f, 0.72f, 1f), Shader.TileMode.MIRROR)

    val GOLD_BRUSH get() = Brush.linearGradient(listOf(Color(GOLD_DUNKEL), Color(GOLD_HELL), Color(GOLD), Color(GOLD_HELL), Color(GOLD_DUNKEL)))

    // ---------------------------------------------------------------- Marmor
    /** gebaute Marmor-Bilder je Groesse (hoechstens 4: Hintergrund, Live-Hintergrund, Randgeste, Lautstaerke) */
    private val marmorListe = LinkedHashMap<Long, Bitmap>()

    /** schwarzer Marmor in Bildschirmgroesse (einmal gebaut, halbe Aufloesung, dann gemerkt) */
    @Synchronized fun marmorBild(b: Int, h: Int): Bitmap {
        // auf 8 px runden: kleine Groessenunterschiede bauen nicht neu
        val schl = ((b / 8).toLong() shl 32) or (h / 8).toLong()
        marmorListe[schl]?.let { return it }
        val w2 = (b / 2).coerceAtLeast(16); val h2 = (h / 2).coerceAtLeast(16)
        val bm = Bitmap.createBitmap(w2, h2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bm)
        c.drawColor(SCHWARZ)
        val z = Skizze.Zufall(1924)
        // wolkige Tiefe
        val wolke = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(6) {
            val x = z.z(0f, w2.toFloat()); val y = z.z(0f, h2.toFloat()); val r = z.z(0.3f, 0.7f) * maxOf(w2, h2)
            wolke.shader = RadialGradient(x, y, r, intArrayOf(0x14FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w2.toFloat(), h2.toFloat(), wolke)
        }
        val ader = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val weich = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        val p = Path()
        // graue Adern: weich und darin eine feine
        repeat(7) { i ->
            p.reset()
            aderPfad(p, z, w2.toFloat(), h2.toFloat())
            ader.maskFilter = weich; ader.color = 0x22D8D2C6; ader.strokeWidth = z.z(5f, 11f)
            c.drawPath(p, ader)
            ader.maskFilter = null; ader.color = if (i % 3 == 0) 0x55D8D2C6 else 0x30D8D2C6; ader.strokeWidth = z.z(0.6f, 1.3f)
            c.drawPath(p, ader)
        }
        // zwei feine Goldadern
        repeat(2) {
            p.reset()
            aderPfad(p, z, w2.toFloat(), h2.toFloat())
            ader.maskFilter = weich; ader.color = 0x30C9A45C; ader.strokeWidth = 4f
            c.drawPath(p, ader)
            ader.maskFilter = null; ader.color = 0x99C9A45C.toInt(); ader.strokeWidth = 0.7f
            c.drawPath(p, ader)
        }
        marmorListe[schl] = bm
        while (marmorListe.size > 4) marmorListe.remove(marmorListe.keys.first())
        return bm
    }

    /** eine Marmorader: schraeg ueber das Bild, mit Abzweigen */
    private fun aderPfad(p: Path, z: Skizze.Zufall, w: Float, h: Float) {
        var x = z.z(-0.1f, 1.1f) * w; var y = if (z.f() < 0.5f) -10f else z.z(0f, h)
        var a = z.z(0.4f, 1.4f) + (if (z.f() < 0.5f) 0f else 1.6f)
        val schritt = maxOf(w, h) / 34f
        p.moveTo(x, y)
        repeat(46) {
            a += z.z(-0.35f, 0.35f)
            x += cos(a) * schritt * z.z(0.6f, 1.3f); y += sin(a) * schritt * z.z(0.6f, 1.3f)
            p.lineTo(x, y)
            if (z.f() < 0.07f) {
                // kurzer Abzweig
                var bx = x; var by = y; var ba = a + z.z(-1.2f, 1.2f)
                val q = Path(); q.moveTo(bx, by)
                repeat(8) { ba += z.z(-0.4f, 0.4f); bx += cos(ba) * schritt * 0.7f; by += sin(ba) * schritt * 0.7f; q.lineTo(bx, by) }
                p.addPath(q)
                p.moveTo(x, y)
            }
        }
    }

    /** Marmor ueber eine Flaeche (Hintergrund) */
    fun marmor(c: Canvas, w: Float, h: Float) {
        val bm = marmorBild(w.toInt(), h.toInt())
        val r = android.graphics.RectF(0f, 0f, w, h)
        c.drawBitmap(bm, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
    }

    // ---------------------------------------------------------------- Risse (Kintsugi)
    /**
     * Ein Riss: zackig, aendert immer wieder leicht die Richtung, verzweigt sich manchmal.
     * winkel 0 = nach rechts (+x). laenge in px.
     */
    fun riss(p: Path, x0: Float, y0: Float, winkel: Float, laenge: Float, z: Skizze.Zufall, d: Float, tiefe: Int = 0) {
        var x = x0; var y = y0; var a = winkel; var rest = laenge
        p.moveTo(x, y)
        while (rest > 0f) {
            val seg = z.z(5f, 13f) * d
            a += z.z(-0.55f, 0.55f)
            // nicht zu weit von der Hauptrichtung weg
            a = winkel + (a - winkel).coerceIn(-0.9f, 0.9f)
            x += cos(a) * seg; y += sin(a) * seg; rest -= seg
            p.lineTo(x, y)
            if (tiefe < 2 && z.f() < 0.16f && rest > 12f * d) {
                val q = Path()
                riss(q, x, y, a + (if (z.f() < 0.5f) -1f else 1f) * z.z(0.45f, 1.0f), rest * z.z(0.3f, 0.6f), z, d, tiefe + 1)
                p.addPath(q)
                p.moveTo(x, y)
            }
        }
    }

    /** Riss malen: dunkle Fuge, darin Gold, oben ein feiner Glanz */
    fun rissMalen(c: Canvas, p: Path, d: Float, fuge: Paint, gold: Paint, glanz: Paint, alpha: Int = 255, breite: Float = 1f) {
        fuge.strokeWidth = 2.8f * d * breite; fuge.alpha = (200 * alpha / 255)
        c.drawPath(p, fuge)
        gold.strokeWidth = 1.3f * d * breite; gold.alpha = alpha
        c.drawPath(p, gold)
        glanz.strokeWidth = 0.45f * d * breite; glanz.alpha = (170 * alpha / 255)
        c.drawPath(p, glanz)
    }

    fun fugeStift() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt() }
    fun goldStift(w: Float, h: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        shader = goldShader(0f, 0f, maxOf(w, 1f) * 0.6f, maxOf(h, 1f) * 0.4f)
    }
    fun glanzStift() = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = GOLD_HELL }

    // ---------------------------------------------------------------- Schrift fuer die Dienste
    @Volatile var schrift: android.graphics.Typeface? = null
    fun schriftLaden(ctx: android.content.Context) {
        if (schrift != null || android.os.Build.VERSION.SDK_INT < 26) return
        try {
            val id = ctx.resources.getIdentifier("poiret_one", "font", ctx.packageName)
            if (id != 0) schrift = ctx.resources.getFont(id)
        } catch (e: Exception) { }
    }
}

/**
 * Art-Déco-Rahmen: dunkles, leicht durchscheinendes Feld mit doppelter Goldlinie und kleinen Stufen in den Ecken.
 * deckung = 0..1 (zum Einblenden, z. B. beim Antippen).
 */
fun Modifier.dekoRahmen(rad: Dp = 14.dp, deckung: () -> Float = { 1f }): Modifier = this.drawBehind {
    val de = deckung().coerceIn(0f, 1f)
    if (de <= 0.01f) return@drawBehind
    val r = rad.toPx().coerceAtMost(size.minDimension / 2f)
    val gold = Deko.GOLD_BRUSH
    drawRoundRect(Color(0xCC0E0E10).copy(alpha = 0.8f * de), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
    drawRoundRect(gold, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), style = Stroke(1.1.dp.toPx()), alpha = de)
    val i = 3.5.dp.toPx()
    val ri = (r - i).coerceAtLeast(0f)
    drawRoundRect(gold, topLeft = androidx.compose.ui.geometry.Offset(i, i),
                  size = androidx.compose.ui.geometry.Size(size.width - 2 * i, size.height - 2 * i),
                  cornerRadius = androidx.compose.ui.geometry.CornerRadius(ri, ri), style = Stroke(0.6.dp.toPx()), alpha = 0.6f * de)
    // Stufen in den Ecken (typisch Art Deco): drei kleine Treppenstufen nach innen
    val s = 3.dp.toPx(); val o = i + 2.dp.toPx() + ri * 0.3f
    val st = Stroke(0.8.dp.toPx())
    for ((ex, ey) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
        val sx = if (ex == 0f) 1f else -1f; val sy = if (ey == 0f) 1f else -1f
        val x0 = if (ex == 0f) o else size.width - o; val y0 = if (ey == 0f) o else size.height - o
        val pf = androidx.compose.ui.graphics.Path().apply {
            moveTo(x0, y0 + sy * 3 * s)
            lineTo(x0 + sx * s, y0 + sy * 3 * s); lineTo(x0 + sx * s, y0 + sy * 2 * s)
            lineTo(x0 + sx * 2 * s, y0 + sy * 2 * s); lineTo(x0 + sx * 2 * s, y0 + sy * s)
            lineTo(x0 + sx * 3 * s, y0 + sy * s); lineTo(x0 + sx * 3 * s, y0)
        }
        drawPath(pf, gold, style = st, alpha = 0.85f * de)
    }
}

/** Sonnenstrahlen-Faecher hinter dem grossen Buchstaben (Art Deco) */
fun Modifier.dekoSonne(deckung: () -> Float = { 1f }): Modifier = this.drawBehind {
    val de = deckung().coerceIn(0f, 1f)
    if (de <= 0.01f) return@drawBehind
    val c = center; val r = size.minDimension / 2f
    val gold = Deko.GOLD_BRUSH
    drawCircle(Color(0xE60E0E10).copy(alpha = 0.9f * de), r)
    drawCircle(gold, r, style = Stroke(1.2.dp.toPx()), alpha = de)
    drawCircle(gold, r - 4.dp.toPx(), style = Stroke(0.6.dp.toPx()), alpha = 0.6f * de)
    val n = 24
    for (k in 0 until n) {
        val a = (k.toFloat() / n) * 6.2832f
        val lang = if (k % 2 == 0) 0.32f else 0.18f
        val r0 = r - 7.dp.toPx(); val r1 = r0 - r * lang
        drawLine(gold, androidx.compose.ui.geometry.Offset(c.x + cos(a) * r0, c.y + sin(a) * r0),
                 androidx.compose.ui.geometry.Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), strokeWidth = 0.7.dp.toPx(), alpha = 0.55f * de)
    }
}

/** gemerkte Risse eines Icons */
private class IconRisse { val p = Path(); var b = -1f; var s = 0 }

/**
 * Kintsugi-Icon: feiner Goldring, in der Mitte das Logo (Linien, Elfenbein) - zerbrochen und mit Gold gekittet.
 * Die Risse laufen quer durchs Logo; wo sie es kreuzen, ist eine Fuge, darin Gold.
 */
@Composable
fun KintsugiIcon(bild: ImageBitmap?, groesse: Dp, saat: Int, modifier: Modifier = Modifier) {
    val risse = remember { IconRisse() }
    val fuge = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) } }
    val gold = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }
    Box(modifier.size(groesse).drawBehind {
        val r = size.minDimension / 2f
        drawCircle(Deko.GOLD_BRUSH, r - 0.8.dp.toPx(), style = Stroke(1.1.dp.toPx()))
        drawCircle(Deko.GOLD_BRUSH, r - 3.6.dp.toPx(), style = Stroke(0.5.dp.toPx()), alpha = 0.45f)
    }, contentAlignment = Alignment.Center) {
        if (bild != null) Image(
            bild, contentDescription = null,
            colorFilter = ColorFilter.tint(Color(Deko.ELFENBEIN)),
            modifier = Modifier.size(groesse * 0.6f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val d = density; val w = size.width; val h = size.height
                    if (risse.b != w || risse.s != saat) {
                        risse.b = w; risse.s = saat; risse.p.reset()
                        val z = Skizze.Zufall(saat)
                        // ein Hauptriss quer durch, dazu ein zweiter kuerzerer
                        val a1 = z.z(-0.5f, 0.5f) + (if (z.f() < 0.5f) 0f else 3.1416f)
                        val sx = w / 2f - cos(a1) * w * 0.62f; val sy = h / 2f - sin(a1) * h * 0.62f + z.z(-0.15f, 0.15f) * h
                        Deko.riss(risse.p, sx, sy, a1, w * 1.3f, z, d * 0.55f, 1)
                        val a2 = a1 + 1.5708f + z.z(-0.4f, 0.4f)
                        Deko.riss(risse.p, w / 2f + z.z(-0.1f, 0.1f) * w, h / 2f, a2, w * 0.5f, z, d * 0.55f, 1)
                    }
                    drawIntoCanvas { cv ->
                        val c = cv.nativeCanvas
                        fuge.strokeWidth = 2.2f * d; c.drawPath(risse.p, fuge)          // Fuge: Logo dort weg
                        gold.shader = Deko.goldShader(0f, 0f, w, h); gold.strokeWidth = 1.0f * d
                        c.drawPath(risse.p, gold)
                    }
                },
        )
    }
}
