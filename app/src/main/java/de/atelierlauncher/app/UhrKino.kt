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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified

// ============================================================================
//  Kino / Plakat: aendert sich die Uhrzeit, loescht ein Strich wie am PC (Schreibmarke) von hinten
//  bis zur ersten geaenderten Stelle und tippt die neuen Zeichen hin.
//  Dauer je nach dem, was sich aendert: Minute 0,3 s, Zehnerminute 0,4 s, Stunde 0,5 s, Zehnerstunde 0,7 s.
// ============================================================================

/** Schriften mit Schreibmarke */
internal val SCHREIB_SCHRIFTEN = setOf("kunst_limelight", "kunst_bebasneue")

@Composable
internal fun SchreibZiffern(text: String, stil: TextStyle, farbe: Color, modifier: Modifier) {
    val d = LocalDensity.current
    val messer = rememberTextMeasurer()
    val ziel = remember(text, stil) { messer.measure(text, stil) }
    val groessePx = with(d) { if (stil.fontSize.isSpecified) stil.fontSize.toPx() else ziel.size.height * 0.8f }

    var alt by remember { mutableStateOf(text) }         // was vorher da stand
    var gleich by remember { mutableStateOf(text.length) } // so viele Zeichen vorne bleiben stehen
    var fort by remember { mutableFloatStateOf(1f) }      // 0..1 im Ablauf, 1 = fertig

    val faktor by androidx.compose.runtime.rememberUpdatedState(LocalZustand.current.thema.uhr.wechselDauer / 100f)
    LaunchedEffect(text) {
        val vorher = alt
        if (vorher == text) { fort = 1f; return@LaunchedEffect }
        var p = 0
        while (p < vorher.length && p < text.length && vorher[p] == text[p]) p++
        val ziffern = text.substring(p).count { it.isDigit() }
        val dauer = faktor * when { ziffern <= 1 -> 300f; ziffern == 2 -> 400f; ziffern == 3 -> 500f; else -> 700f }
        gleich = p; fort = 0f
        val start = withFrameMillis { it }
        while (fort < 1f) {
            withFrameMillis { t -> fort = ((t - start) / dauer).coerceIn(0f, 1f) }
        }
        alt = text
    }

    // was gerade zu sehen ist: erst die alten Zeichen von hinten weg, dann die neuen nacheinander hin
    val laeuft = fort < 1f && alt != text
    val gezeigt: String = if (!laeuft) text else {
        val weg = alt.length - gleich; val hin = text.length - gleich
        val schritte = (weg + hin).coerceAtLeast(1)
        val s = (fort * schritte).toInt().coerceIn(0, schritte)
        if (s <= weg) alt.substring(0, alt.length - s) else text.substring(0, gleich + (s - weg))
    }
    val bild = remember(gezeigt, stil) { messer.measure(gezeigt, stil) }

    Box(modifier.layout { m, _ ->
        val pl = m.measure(Constraints.fixed(ziel.size.width, ziel.size.height)); layout(ziel.size.width, ziel.size.height) { pl.place(0, 0) }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            drawText(bild, color = farbe)
            if (laeuft) {
                val x = if (gezeigt.isEmpty()) 0f else bild.getBoundingBox(gezeigt.length - 1).right
                val grund = ziel.firstBaseline
                val breite = (groessePx * 0.06f).coerceAtLeast(2f)
                val hoehe = groessePx * 0.74f
                drawRect(farbe, Offset(x + breite * 0.6f, grund - hoehe), Size(breite, hoehe + groessePx * 0.04f))
            }
        }
    }
}
