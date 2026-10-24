package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
//  Gezeichnete Symbole (keine Emojis): Zahnrad, Musiksteuerung, Wetter, Termin
// ============================================================================

private fun tagNummer(zeit: Long, utc: Boolean): Int {
    val c = Calendar.getInstance(if (utc) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
    c.timeInMillis = zeit
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

private fun heuteNummer(plus: Int = 0): Int {
    val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, plus)
    return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR)
}

fun terminTag(t: Termin): Int {
    val tag = tagNummer(t.beginn, t.ganztag)
    val heute = heuteNummer()
    return maxOf(tag, if (t.beginn < System.currentTimeMillis()) heute else tag)   // laufende Termine unter "Heute"
}


@Composable
fun MusikSteuerung(m: MusikInfo, groesse: Dp) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        SymbolKnopf("zurueck", p.text, groesse) { Musik.zurueck() }
        Spacer(Modifier.width(groesse * 0.5f))
        SymbolKnopf(if (m.spielt) "pause" else "play", p.text, groesse * 1.15f) { Musik.spielenPause() }
        Spacer(Modifier.width(groesse * 0.5f))
        SymbolKnopf("weiter", p.text, groesse) { Musik.weiter() }
    }
}

// ---------------------------------------------------------------- Symbole (gezeichnet, keine Emojis)
@Composable
fun SymbolKnopf(art: String, farbe: Color, groesse: Dp, onClick: () -> Unit) {
    Box(Modifier.size(groesse + 12.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Symbol(art, farbe, groesse)
    }
}

@Composable
fun Symbol(art: String, farbe: Color, groesse: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(groesse)) {
        val s = size.minDimension
        val strich = s * 0.1f
        fun dreieck(x0: Float, breite: Float, links: Boolean) {
            val pfad = Path()
            if (links) { pfad.moveTo(x0 + breite, s * 0.2f); pfad.lineTo(x0, s * 0.5f); pfad.lineTo(x0 + breite, s * 0.8f) }
            else { pfad.moveTo(x0, s * 0.2f); pfad.lineTo(x0 + breite, s * 0.5f); pfad.lineTo(x0, s * 0.8f) }
            pfad.close(); drawPath(pfad, farbe)
        }
        when (art) {
            "play" -> dreieck(s * 0.28f, s * 0.5f, false)
            "pause" -> {
                drawRoundRect(farbe, Offset(s * 0.26f, s * 0.2f), Size(s * 0.16f, s * 0.6f), CornerRadius(s * 0.04f))
                drawRoundRect(farbe, Offset(s * 0.58f, s * 0.2f), Size(s * 0.16f, s * 0.6f), CornerRadius(s * 0.04f))
            }
            "weiter" -> { dreieck(s * 0.2f, s * 0.42f, false); drawRect(farbe, Offset(s * 0.66f, s * 0.2f), Size(s * 0.12f, s * 0.6f)) }
            "zurueck" -> { dreieck(s * 0.38f, s * 0.42f, true); drawRect(farbe, Offset(s * 0.22f, s * 0.2f), Size(s * 0.12f, s * 0.6f)) }
            "plus" -> {
                drawLine(farbe, Offset(s * 0.5f, s * 0.2f), Offset(s * 0.5f, s * 0.8f), strich, StrokeCap.Round)
                drawLine(farbe, Offset(s * 0.2f, s * 0.5f), Offset(s * 0.8f, s * 0.5f), strich, StrokeCap.Round)
            }
            "lupe" -> {
                drawCircle(farbe, s * 0.27f, Offset(s * 0.43f, s * 0.43f), style = androidx.compose.ui.graphics.drawscope.Stroke(strich * 1.1f))
                drawLine(farbe, Offset(s * 0.63f, s * 0.63f), Offset(s * 0.84f, s * 0.84f), strich * 1.3f, StrokeCap.Round)
            }
            "kreuz" -> {
                drawLine(farbe, Offset(s * 0.25f, s * 0.25f), Offset(s * 0.75f, s * 0.75f), strich, StrokeCap.Round)
                drawLine(farbe, Offset(s * 0.75f, s * 0.25f), Offset(s * 0.25f, s * 0.75f), strich, StrokeCap.Round)
            }
            "hoch", "runter" -> {
                val y1 = if (art == "hoch") s * 0.62f else s * 0.38f
                val y2 = if (art == "hoch") s * 0.36f else s * 0.64f
                drawLine(farbe, Offset(s * 0.25f, y1), Offset(s * 0.5f, y2), strich, StrokeCap.Round)
                drawLine(farbe, Offset(s * 0.5f, y2), Offset(s * 0.75f, y1), strich, StrokeCap.Round)
            }
            "pfeil" -> {
                drawLine(farbe, Offset(s * 0.38f, s * 0.25f), Offset(s * 0.62f, s * 0.5f), strich, StrokeCap.Round)
                drawLine(farbe, Offset(s * 0.62f, s * 0.5f), Offset(s * 0.38f, s * 0.75f), strich, StrokeCap.Round)
            }
            "termin" -> {
                drawRoundRect(farbe, Offset(s * 0.15f, s * 0.22f), Size(s * 0.7f, s * 0.62f), CornerRadius(s * 0.1f),
                              style = androidx.compose.ui.graphics.drawscope.Stroke(strich * 0.8f))
                drawLine(farbe, Offset(s * 0.15f, s * 0.4f), Offset(s * 0.85f, s * 0.4f), strich * 0.8f)
                drawLine(farbe, Offset(s * 0.34f, s * 0.14f), Offset(s * 0.34f, s * 0.28f), strich * 0.8f, StrokeCap.Round)
                drawLine(farbe, Offset(s * 0.66f, s * 0.14f), Offset(s * 0.66f, s * 0.28f), strich * 0.8f, StrokeCap.Round)
            }
            "zahnrad" -> {
                // 8 Zaehne, Loch in der Mitte - reine Vektorform
                val c = s / 2
                val aussen = s * 0.47f; val innen = s * 0.36f
                val rad = Path()
                for (i in 0 until 8) {
                    val m = i * PI / 4
                    val punkte = listOf(m - 0.30 to innen, m - 0.17 to aussen, m + 0.17 to aussen, m + 0.30 to innen,
                                        m + PI / 8 to innen)
                    punkte.forEachIndexed { j, (w, r) ->
                        val x = c + (cos(w) * r).toFloat(); val y = c + (sin(w) * r).toFloat()
                        if (i == 0 && j == 0) rad.moveTo(x, y) else rad.lineTo(x, y)
                    }
                }
                rad.close()
                val loch = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c, c), s * 0.15f)) }
                drawPath(Path.combine(PathOperation.Difference, rad, loch), farbe)
            }
            "note" -> {
                drawCircle(farbe, s * 0.13f, Offset(s * 0.36f, s * 0.72f))
                drawLine(farbe, Offset(s * 0.47f, s * 0.72f), Offset(s * 0.47f, s * 0.2f), strich * 0.8f)
                drawLine(farbe, Offset(s * 0.47f, s * 0.2f), Offset(s * 0.72f, s * 0.3f), strich * 0.9f, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun WetterSymbol(art: WetterArt, farbe: Color, groesse: Dp) {
    Canvas(Modifier.size(groesse)) {
        val s = size.minDimension
        fun sonne(c: Offset, r: Float) {
            drawCircle(farbe, r, c)
            for (i in 0 until 8) {
                val a = i * PI / 4
                val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                drawLine(farbe, c + Offset(dx * r * 1.45f, dy * r * 1.45f), c + Offset(dx * r * 1.95f, dy * r * 1.95f),
                         s * 0.06f, StrokeCap.Round)
            }
        }
        when (art) {
            WetterArt.SONNE -> sonne(Offset(s / 2, s / 2), s * 0.2f)
            WetterArt.MOND -> {
                val voll = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s / 2, s / 2), s * 0.32f)) }
                val weg = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(s * 0.66f, s * 0.38f), s * 0.28f)) }
                drawPath(Path.combine(PathOperation.Difference, voll, weg), farbe)
            }
            WetterArt.HEITER -> { sonne(Offset(s * 0.64f, s * 0.36f), s * 0.13f); wolke(farbe, s, 0f, s * 0.06f, 0.82f) }
            WetterArt.WOLKE -> wolke(farbe, s, 0f, 0f, 1f)
            WetterArt.NEBEL -> {
                wolke(farbe.copy(alpha = 0.6f), s, 0f, -s * 0.1f, 0.9f)
                for (i in 0 until 3) drawLine(farbe, Offset(s * (0.18f + 0.05f * i), s * (0.72f + 0.1f * i)),
                    Offset(s * (0.82f - 0.05f * i), s * (0.72f + 0.1f * i)), s * 0.06f, StrokeCap.Round)
            }
            WetterArt.REGEN, WetterArt.SCHNEE -> {
                wolke(farbe, s, 0f, -s * 0.12f, 1f)
                for (i in 0 until 3) {
                    val x = s * (0.32f + 0.18f * i)
                    if (art == WetterArt.REGEN)
                        drawLine(farbe, Offset(x, s * 0.72f), Offset(x - s * 0.06f, s * 0.9f), s * 0.06f, StrokeCap.Round)
                    else drawCircle(farbe, s * 0.045f, Offset(x - s * 0.03f, s * 0.8f + if (i == 1) s * 0.07f else 0f))
                }
            }
            WetterArt.GEWITTER -> {
                wolke(farbe, s, 0f, -s * 0.12f, 1f)
                val blitz = Path().apply {
                    moveTo(s * 0.52f, s * 0.6f); lineTo(s * 0.4f, s * 0.8f); lineTo(s * 0.5f, s * 0.8f)
                    lineTo(s * 0.44f, s * 0.97f); lineTo(s * 0.62f, s * 0.74f); lineTo(s * 0.52f, s * 0.74f); lineTo(s * 0.58f, s * 0.6f); close()
                }
                drawPath(blitz, farbe)
            }
        }
    }
}

private fun DrawScope.wolke(farbe: Color, s: Float, dx: Float, dy: Float, sk: Float) {
    fun p(x: Float, y: Float) = Offset(s * 0.5f + (x - 0.5f) * s * sk + dx, s * 0.55f + (y - 0.55f) * s * sk + dy)
    drawCircle(farbe, s * 0.15f * sk, p(0.33f, 0.58f))
    drawCircle(farbe, s * 0.2f * sk, p(0.52f, 0.48f))
    drawCircle(farbe, s * 0.13f * sk, p(0.7f, 0.6f))
    val lo = p(0.2f, 0.58f)
    drawRoundRect(farbe, lo, Size(s * 0.62f * sk, s * 0.15f * sk), CornerRadius(s * 0.075f * sk))
}

/** Kleine Zeile mit Wetter fuer die Kopfzeile */
@Composable
fun WetterKurz(d: WetterDaten, farbe: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        WetterSymbol(Wetter.art(d.code, d.tag), farbe, 16.dp)
        Spacer(Modifier.width(5.dp))
        Text("${d.temp.roundToInt()}°", color = farbe, fontSize = 16.sp)
    }
}
