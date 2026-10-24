package de.atelierlauncher.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ============================================================================
//  Selbst platzierter unsichtbarer Knopf (Einstellungen -> Code)
// ============================================================================

/**
 * Tippen nur MITLESEN, nichts abfangen: Scrollen, langes Druecken usw. darunter funktionieren weiter.
 * rect = Bereich als Anteil der Flaeche (x, y, b, h) oder null = ganze Flaeche.
 */
fun Modifier.leiserTipp(aktiv: Boolean, rect: List<Float>? = null, onTipp: () -> Unit): Modifier =
    if (!aktiv) this else this.pointerInput(rect, onTipp) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (rect != null) {
                val x = down.position.x / size.width; val y = down.position.y / size.height
                if (x < rect[0] || x > rect[0] + rect[2] || y < rect[1] || y > rect[1] + rect[3]) return@awaitEachGesture
            }
            val start = down.position
            val t0 = down.uptimeMillis
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if ((c.position - start).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
                if (!c.pressed) { if (c.uptimeMillis - t0 < 450) onTipp(); return@awaitEachGesture }
            }
        }
    }

/** Punkt im selbst platzierten Knopf (nur Anzeige, faengt nichts ab) */
@Composable
fun EigenerGeheimKnopf() {
    val z = LocalZustand.current
    if (!z.musterPunkt) return
    val r = z.geheimRect
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current
        val b = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        Box(
            Modifier
                .offset { IntOffset(((r[0] + r[2] / 2f) * b).roundToInt() - 2.dp.roundToPx(), ((r[1] + r[3] / 2f) * h).roundToInt() - 2.dp.roundToPx()) }
                .size(4.dp).clip(CircleShape).background(Color.White)
        )
    }
}

/** Knopf verschieben (Mitte ziehen) und Groesse aendern (Ecke unten rechts ziehen) */
@Composable
fun KnopfPlatzierenBlatt() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    var r by remember { mutableStateOf(z.geheimRect) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
        val d = LocalDensity.current
        val b = with(d) { maxWidth.toPx() }
        val h = with(d) { maxHeight.toPx() }
        val minB = with(d) { 36.dp.toPx() } / b
        val minH = with(d) { 36.dp.toPx() } / h
        // das Rechteck
        Box(
            Modifier
                .offset { IntOffset((r[0] * b).roundToInt(), (r[1] * h).roundToInt()) }
                .size(with(d) { (r[2] * b).toDp() }, with(d) { (r[3] * h).toDp() })
                .clip(rund(10.dp))
                .background(p.akzent.copy(alpha = 0.35f))
                .border(2.dp, p.akzent, rund(10.dp))
                .pointerInput(b, h) {
                    detectDragGestures { c, weg ->
                        c.consume()
                        r = listOf(
                            (r[0] + weg.x / b).coerceIn(0f, 1f - r[2]),
                            (r[1] + weg.y / h).coerceIn(0f, 1f - r[3]),
                            r[2], r[3],
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Text("ziehen", color = Color.White, fontSize = 13.sp) }
        // Griff zum Groessenaendern
        Box(
            Modifier
                .offset { IntOffset(((r[0] + r[2]) * b).roundToInt() - 22.dp.roundToPx(), ((r[1] + r[3]) * h).roundToInt() - 22.dp.roundToPx()) }
                .size(44.dp)
                .pointerInput(b, h, minB, minH) {
                    detectDragGestures { c, weg ->
                        c.consume()
                        r = listOf(r[0], r[1],
                            (r[2] + weg.x / b).coerceIn(minB, 1f - r[0]),
                            (r[3] + weg.y / h).coerceIn(minH, 1f - r[1]))
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White).border(3.dp, p.akzent, CircleShape)) }

        // Hinweis + Knoepfe unten
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().systemBarsPadding().padding(20.dp)
                .clip(rund(22.dp)).background(p.karte).padding(18.dp),
        ) {
            Text("Unsichtbaren Knopf platzieren", color = p.text, fontSize = 18.sp)
            Text("Feld ziehen = verschieben, Punkt unten rechts = Größe. Der Knopf ist später unsichtbar.",
                 color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Abbrechen", color = p.text, fontSize = 16.sp,
                     modifier = Modifier.weight(1f).clip(rund(16.dp)).background(p.linie)
                         .clickable { navi.zu() }.padding(vertical = 13.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Fertig", color = p.aufAkzent, fontSize = 16.sp,
                     modifier = Modifier.weight(1f).clip(rund(16.dp)).background(p.akzent)
                         .clickable { z.geheimRectSetzen(r); z.geheimOrtSetzen("eigen"); navi.zu() }.padding(vertical = 13.dp),
                     textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
