package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import kotlin.random.Random

// ============================================================================
//  Sterne-Uhr: jede Ziffer besteht aus einzelnen Sternpunkten. Aendert sich eine Ziffer,
//  fliegen ihre Punkte auseinander und verschwinden, die Punkte der neuen Ziffer fliegen
//  von ueberall her an ihren Platz. Alles als Vektor aus der Schrift (Codystar), keine Bilder.
//  Als Einstellung "Fliegen" geht das mit jeder Uhr-Schrift: jedes Teilstueck einer Ziffer fliegt fuer sich.
// ============================================================================

const val STERNE_SCHRIFT = "kunst_codystar"

/** ein Sternpunkt: sein Umriss und seine Mitte */
private class SternPunkt(val pfad: android.graphics.Path, val x: Float, val y: Float)

/** Flug eines Punkts: Richtung/Weite, Verzoegerung (0..1) */
private class Flug(val p: SternPunkt, val dx: Float, val dy: Float, val verz: Float, val dreh: Float)

@Composable
internal fun SternZiffern(text: String, stil: TextStyle, farbe: Color, modifier: Modifier) {
    val faktor by androidx.compose.runtime.rememberUpdatedState(LocalZustand.current.thema.uhr.wechselDauer / 100f)
    val d = LocalDensity.current
    val ctx = LocalContext.current
    val messer = rememberTextMeasurer()
    val layout = remember(text, stil) { messer.measure(text, stil) }
    // die Schrift, die die Uhr gerade hat (Kunst-Schrift, Design-Schrift oder eigene Datei)
    val aufloeser = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val schriftart = remember(stil.fontFamily, stil.fontWeight, stil.fontStyle) {
        try {
            aufloeser.resolve(stil.fontFamily, stil.fontWeight ?: androidx.compose.ui.text.font.FontWeight.Normal,
                              stil.fontStyle ?: androidx.compose.ui.text.font.FontStyle.Normal).value as? android.graphics.Typeface
        } catch (x: Exception) { null }
    }
    val groessePx = with(d) { if (stil.fontSize.isSpecified) stil.fontSize.toPx() else layout.size.height * 0.8f }
    val fett = (stil.fontWeight?.weight ?: 400) >= 600
    val schraeg = stil.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic

    // je Zeichen die einzelnen Punkte (ein Punkt = eine Kontur; Loecher werden dem Punkt zugeschlagen, in dem sie liegen)
    val punkte: List<List<SternPunkt>> = remember(layout, schriftart, groessePx, fett, schraeg) {
        val stift = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = schriftart; textSize = groessePx
            isFakeBoldText = fett && schriftart?.isBold != true; if (schraeg && schriftart?.isItalic != true) textSkewX = -0.25f
        }
        val grund = layout.getLineBaseline(0)
        text.indices.map { i ->
            if (text[i].isWhitespace()) return@map emptyList<SternPunkt>()
            val umriss = android.graphics.Path()
            stift.getTextPath(text, i, i + 1, layout.getHorizontalPosition(i, true), grund, umriss)
            // alle Konturen mit Umlaufsinn (Flaeche mit Vorzeichen); Loecher laufen andersherum als die Aussenkonturen
            class K(val pfad: android.graphics.Path, val box: android.graphics.RectF, val flaeche: Float)
            val alle = ArrayList<K>()
            val pm = android.graphics.PathMeasure(umriss, false)
            val pos = FloatArray(2); val tan = FloatArray(2)
            do {
                val len = pm.length
                if (len <= 0f) continue
                val teil = android.graphics.Path()
                pm.getSegment(0f, len, teil, true); teil.close()
                val box = android.graphics.RectF(); teil.computeBounds(box, true)
                var fl = 0f; var px = 0f; var py = 0f
                for (k in 0..48) {
                    pm.getPosTan(len * k / 48f, pos, tan)
                    if (k > 0) fl += px * pos[1] - pos[0] * py
                    px = pos[0]; py = pos[1]
                }
                alle.add(K(teil, box, fl))
            } while (pm.nextContour())
            val aussen = alle.maxByOrNull { kotlin.math.abs(it.flaeche) }?.flaeche ?: 1f
            val teile = alle.filter { it.flaeche * aussen >= 0f }.toMutableList()
            for (loch in alle.filter { it.flaeche * aussen < 0f }) {
                // Loch zur kleinsten Aussenkontur, in der es liegt
                val ziel = teile.filter { it.box.contains(loch.box) }.minByOrNull { it.box.width() * it.box.height() }
                if (ziel != null) ziel.pfad.addPath(loch.pfad) else teile.add(loch)
            }
            teile.map { SternPunkt(it.pfad, it.box.centerX(), it.box.centerY()) }
        }
    }

    var altText by remember { mutableStateOf(text) }
    var altPunkte by remember { mutableStateOf(punkte) }
    var weg by remember { mutableStateOf(emptyList<Flug>()) }
    var hin by remember { mutableStateOf(emptyList<Flug>()) }
    var ruhig by remember { mutableStateOf(BooleanArray(0)) }   // Zeichen, die stehen bleiben
    var fort by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(punkte) {
        val vorher = altText; val vorherPunkte = altPunkte
        altText = text; altPunkte = punkte
        if (vorher == text) { fort = 1f; return@LaunchedEffect }
        val alleNeu = vorher.length != text.length
        val bleibt = BooleanArray(text.length) { i -> !alleNeu && vorher[i] == text[i] }
        fun flug(p: SternPunkt, rein: Boolean): Flug {
            // weg vom Zeichen-Mittelpunkt, etwas Zufall dazu
            val w = Random.nextFloat() * 6.2832f
            val weite = groessePx * (0.35f + Random.nextFloat() * 0.6f)
            return Flug(p, kotlin.math.cos(w) * weite, kotlin.math.sin(w) * weite - (if (rein) 0f else groessePx * 0.1f),
                        Random.nextFloat() * 0.25f, (Random.nextFloat() - 0.5f) * 160f)
        }
        weg = vorherPunkte.indices.filter { it >= bleibt.size || !bleibt[it] }.flatMap { i -> vorherPunkte[i].map { flug(it, false) } }
        hin = punkte.indices.filter { !bleibt[it] }.flatMap { i -> punkte[i].map { flug(it, true) } }
        ruhig = bleibt
        fort = 0f
        val start = withFrameMillis { it }
        val dauer = 900f * faktor
        while (fort < 1f) withFrameMillis { t -> fort = ((t - start) / dauer).coerceIn(0f, 1f) }
        weg = emptyList(); hin = emptyList()
    }

    val pinsel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL } }
    val rand = (groessePx * 0.9f).toInt()                       // Platz zum Wegfliegen (belegt aber keinen)

    Box(modifier.layout { m, _ ->
        val w = layout.size.width; val h = layout.size.height
        val pl = m.measure(Constraints.fixed(w + 2 * rand, h + 2 * rand)); layout(w, h) { pl.place(-rand, -rand) }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val c = drawContext.canvas.nativeCanvas
            val o = rand.toFloat()
            val a = (farbe.alpha * 255).toInt()
            pinsel.color = farbe.copy(alpha = 1f).toArgb()
            c.save(); c.translate(o, o)
            if (altText != text) {                                  // Wechsel noch nicht angelaufen: bis dahin das Alte zeigen
                pinsel.alpha = a
                for (z in altPunkte) for (p in z) c.drawPath(p.pfad, pinsel)
                c.restore(); return@Canvas
            }
            val anim = fort < 1f && (weg.isNotEmpty() || hin.isNotEmpty())
            // stehende Zeichen
            pinsel.alpha = a
            for (i in punkte.indices) {
                if (anim && (i >= ruhig.size || !ruhig[i])) continue
                for (p in punkte[i]) c.drawPath(p.pfad, pinsel)
            }
            if (anim) {
                // weg: beschleunigt, wird kleiner und blasser
                val wegEnde = 0.6f; val hinStart = 0.3f
                for (f in weg) {
                    val q = ((fort / wegEnde - f.verz) / (1f - f.verz)).coerceIn(0f, 1f)
                    if (q >= 1f) continue
                    val e = q * q
                    zeichne(c, f.p, f.dx * e, f.dy * e, 1f - 0.6f * q, 1f - 0.6f * q, f.dreh * q, (a * (1f - q)).toInt(), pinsel)
                }
                // hin: bremst ab, wird deutlicher
                for (f in hin) {
                    val q = (((fort - hinStart) / (1f - hinStart) - f.verz) / (1f - f.verz)).coerceIn(0f, 1f)
                    if (q <= 0f) continue
                    val r = 1f - q; val e = r * r
                    zeichne(c, f.p, f.dx * e, f.dy * e, 0.4f + 0.6f * q, 0.4f + 0.6f * q, f.dreh * r, (a * q).toInt(), pinsel)
                }
            }
            c.restore()
        }
    }
}

private fun zeichne(c: android.graphics.Canvas, p: SternPunkt, dx: Float, dy: Float, sx: Float, sy: Float, dreh: Float, alpha: Int,
                    pinsel: android.graphics.Paint) {
    if (alpha <= 0) return
    pinsel.alpha = alpha.coerceAtMost(255)
    c.save()
    c.translate(p.x + dx, p.y + dy); if (dreh != 0f) c.rotate(dreh); c.scale(sx, sy); c.translate(-p.x, -p.y)
    c.drawPath(p.pfad, pinsel)
    c.restore()
}
