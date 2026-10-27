package de.atelierlauncher.app

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import kotlin.random.Random

// ============================================================================
//  Glitch-Uhr: die duennen waagrechten Striche der Schrift flimmern wie bei einem alten Fernseher -
//  sie zucken seitlich, flackern in der Helligkeit, ab und zu verrutscht eine ganze Zeile.
//  Der Koerper der Ziffern bleibt ruhig. Getrennt wird einmal pro Uhrzeit:
//  je Spalte sind duenne Farbstuecke (kuerzer als ~7 % der Schriftgroesse) Striche, der Rest Koerper.
//  Gemalt werden nur zwei fertige Bilder in Streifen - kein Shader, nichts Neues pro Bild, ~20 Bilder/s.
// ============================================================================

private class GlitchTeile(val koerper: Bitmap, val striche: Bitmap,
                          /** je Band: x0, x1, y0, y1 */ val baender: IntArray)

@Composable
internal fun GlitchZiffern(text: String, stil: TextStyle, farbe: Color, modifier: Modifier) {
    val d = LocalDensity.current
    val messer = rememberTextMeasurer()
    val layout = remember(text, stil) { messer.measure(text, stil) }
    val w = layout.size.width.coerceAtLeast(1); val h = layout.size.height.coerceAtLeast(1)
    val groessePx = with(d) { if (stil.fontSize.isSpecified) stil.fontSize.toPx() else h * 0.8f }
    val rand = (groessePx * 0.2f).toInt().coerceAtLeast(4)          // seitlich Platz zum Zucken

    val teile = remember(layout, groessePx) {
        val ib = ImageBitmap(w, h)
        CanvasDrawScope().draw(d, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(ib), Size(w.toFloat(), h.toFloat())) {
            drawText(layout, color = Color.White)
        }
        val px = IntArray(w * h)
        ib.asAndroidBitmap().getPixels(px, 0, w, 0, 0, w, h)
        val k = (groessePx * 0.07f).toInt().coerceAtLeast(3)
        val kp = IntArray(w * h); val sp = IntArray(w * h)
        val strichZeile = Array(text.length.coerceAtLeast(1)) { BooleanArray(h) }
        // Spalte -> Zeichen (fuer Baender je Ziffer, damit nicht alle Ziffern gleich zucken)
        // jede Spalte gehoert zum naechsten Zeichen (auch Striche, die weit aus der Ziffer ragen)
        val mitte = FloatArray(text.length) { i -> if (text[i].isWhitespace()) Float.NaN else layout.getBoundingBox(i).center.x }
        val zeichenVon = IntArray(w) { x ->
            var best = -1; var bd = Float.MAX_VALUE
            for (i in mitte.indices) { val m = mitte[i]; if (!m.isNaN() && kotlin.math.abs(m - x) < bd) { bd = kotlin.math.abs(m - x); best = i } }
            best
        }
        val spX0 = IntArray(text.length) { w }; val spX1 = IntArray(text.length)
        for (x in 0 until w) { val z = zeichenVon[x]; if (z >= 0) { if (x < spX0[z]) spX0[z] = x; if (x + 1 > spX1[z]) spX1[z] = x + 1 } }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if ((px[y * w + x] ushr 24) <= 40) { y++; continue }
                var e = y
                while (e < h && (px[e * w + x] ushr 24) > 40) e++
                val duenn = e - y < k
                for (yy in y until e) {
                    if (duenn) { sp[yy * w + x] = px[yy * w + x]; val z = zeichenVon[x]; if (z >= 0) strichZeile[z][yy] = true }
                    else kp[yy * w + x] = px[yy * w + x]
                }
                y = e
            }
        }
        // Baender: zusammenhaengende Zeilen mit Strichen je Zeichen (hoechstens ~k hoch, dann neues Band)
        val b = ArrayList<Int>()
        for (i in text.indices) {
            val x0 = spX0[i]; val x1 = spX1[i]
            if (x1 <= x0) continue
            var y = 0
            while (y < h) {
                if (!strichZeile[i][y]) { y++; continue }
                var e = y
                while (e < h && strichZeile[i][e] && e - y < k) e++
                b.add(x0); b.add(x1); b.add(y); b.add(e)
                y = e
            }
        }
        GlitchTeile(Bitmap.createBitmap(kp, w, h, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(sp, w, h, Bitmap.Config.ARGB_8888), b.toIntArray())
    }

    val n = teile.baender.size / 4
    val versatz = remember(teile) { FloatArray(n) }
    val hell = remember(teile) { FloatArray(n) { 1f } }
    // ganze Zeile verrutscht: y0, y1, dx, Rest-Takte
    val rutsch = remember(teile) { FloatArray(4) }
    var takt by remember { mutableIntStateOf(0) }

    LaunchedEffect(teile) {
        val amp = groessePx * 0.035f
        var naechsterRutsch = 0L
        while (true) {
            delay(50)
            withFrameMillis { }                          // haelt an, solange der Startbildschirm nicht zu sehen ist
            val t = android.os.SystemClock.uptimeMillis()
            for (i in 0 until n) {
                if (Random.nextFloat() < 0.35f) {
                    val r = Random.nextFloat()
                    versatz[i] = when {
                        r < 0.05f -> (if (Random.nextBoolean()) 1 else -1) * groessePx * (0.08f + Random.nextFloat() * 0.1f)
                        r < 0.35f -> 0f
                        else -> (Random.nextFloat() * 2f - 1f) * amp
                    }
                    hell[i] = if (Random.nextFloat() < 0.06f) 0.15f else 0.55f + Random.nextFloat() * 0.45f
                }
            }
            if (rutsch[3] > 0f) rutsch[3] -= 1f
            else if (t >= naechsterRutsch) {
                if (naechsterRutsch != 0L) {
                    val hoehe = groessePx * (0.05f + Random.nextFloat() * 0.09f)
                    rutsch[0] = Random.nextFloat() * (h - hoehe); rutsch[1] = rutsch[0] + hoehe
                    rutsch[2] = (if (Random.nextBoolean()) 1 else -1) * groessePx * (0.03f + Random.nextFloat() * 0.07f)
                    rutsch[3] = (2 + Random.nextInt(3)).toFloat()
                }
                naechsterRutsch = t + Random.nextLong(1200, 3500)
            }
            takt++
        }
    }

    val farbFilter = remember(farbe) { PorterDuffColorFilter(farbe.copy(alpha = 1f).toArgb(), PorterDuff.Mode.SRC_IN) }
    val pinsel = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val von = remember { android.graphics.Rect() }
    val nach = remember { android.graphics.RectF() }

    Box(modifier.layout { m, _ ->
        val pl = m.measure(Constraints.fixed(w + 2 * rand, h)); layout(w, h) { pl.place(-rand, 0) }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tick = takt          // neu malen bei jedem Takt
            val c = drawContext.canvas.nativeCanvas
            val o = rand.toFloat()
            val grundAlpha = (farbe.alpha * 255).toInt()
            pinsel.colorFilter = farbFilter
            pinsel.alpha = grundAlpha
            val rutscht = rutsch[3] > 0f
            val ry0 = rutsch[0]; val ry1 = rutsch[1]; val rdx = rutsch[2]
            // Koerper - ruhig, nur beim Verrutschen ein Streifen versetzt
            if (!rutscht) c.drawBitmap(teile.koerper, o, 0f, pinsel)
            else {
                c.save(); c.clipRect(0f, 0f, size.width, ry0); c.drawBitmap(teile.koerper, o, 0f, pinsel); c.restore()
                c.save(); c.clipRect(0f, ry1, size.width, size.height); c.drawBitmap(teile.koerper, o, 0f, pinsel); c.restore()
                c.save(); c.clipRect(0f, ry0, size.width, ry1); c.drawBitmap(teile.koerper, o + rdx, 0f, pinsel); c.restore()
            }
            // Striche: jedes Band einzeln versetzt und in eigener Helligkeit
            val bd = teile.baender
            for (i in 0 until n) {
                val x0 = bd[i * 4]; val x1 = bd[i * 4 + 1]; val y0 = bd[i * 4 + 2]; val y1 = bd[i * 4 + 3]
                var dx = versatz[i]
                if (rutscht && y1 > ry0 && y0 < ry1) dx += rdx
                von.set(x0, y0, x1, y1)
                nach.set(o + x0 + dx, y0.toFloat(), o + x1 + dx, y1.toFloat())
                pinsel.alpha = (grundAlpha * hell[i]).toInt()
                c.drawBitmap(teile.striche, von, nach, pinsel)
            }
        }
    }
}
