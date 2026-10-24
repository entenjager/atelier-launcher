package de.atelierlauncher.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Gehoert zu Einstellungen → Alphabet → Code (Knopf: "Unsichtbarer Baustein").
//  Im Baukasten sieht man ihn als gestrichelten Rahmen, damit man ihn verschieben kann.
// ============================================================================
@Composable
internal fun UnsichtbarerKnopf(modifier: Modifier) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    if (LocalImEditor.current) {
        Box(modifier.border(1.dp, p.text.copy(alpha = 0.35f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Text("unsichtbar", color = p.leise, fontSize = 11.sp, maxLines = 1)
        }
        return
    }
    val scharf: () -> Unit = remember(z) { { z.musterBereitBis = System.currentTimeMillis() + 8_000; z.musterPunkt = true; z.musterTipp++ } }
    // Lage merken: die ganze Seite liest das Antippen dort mit (falls etwas darueber liegt und die Beruehrung abfaengt)
    Box(modifier
            .onGloballyPositioned {
                // nur bei echter Verschiebung merken (3D-Neigen bewegt ihn ein paar Pixel - das soll nichts neu zeichnen)
                val r = it.boundsInRoot(); val alt = KnopfLage.rect.value
                if (alt == null || kotlin.math.abs(alt.left - r.left) > 24f || kotlin.math.abs(alt.top - r.top) > 24f ||
                    kotlin.math.abs(alt.width - r.width) > 4f || kotlin.math.abs(alt.height - r.height) > 4f) KnopfLage.rect.value = r
            }
            .leiserTipp(true, onTipp = scharf), contentAlignment = Alignment.Center) {
        // der Punkt (solange angetippt) sitzt ganz oben links auf dem Startbildschirm (Startseite.kt)
    }
    LaunchedEffect(z.musterTipp) { if (z.musterPunkt) { delay(8_000); z.musterPunkt = false } }
}

/** wo der unsichtbare Baustein auf dem Bildschirm liegt (px, Fenster) */
internal object KnopfLage {
    val rect = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    val wurzel = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** als Anteil der Startseite (x, y, b, h) - wie beim selbst platzierten Knopf */
    fun anteil(): List<Float>? {
        val r = rect.value ?: return null
        val w = wurzel.value ?: return null
        if (w.width <= 0f || w.height <= 0f) return null
        fun g(x: Float) = kotlin.math.round(x * 200f) / 200f        // grob runden: gleiche Liste = kein Neustart
        return listOf(g((r.left - w.left) / w.width), g((r.top - w.top) / w.height), g(r.width / w.width), g(r.height / w.height))
    }
}

/**
 * Punkt ganz oben links, solange der Knopf gedrueckt ist - knapp innerhalb der runden Bildschirmecke
 * (sonst schneidet die Ecke ihn weg).
 */
@Composable
internal fun androidx.compose.foundation.layout.BoxScope.KnopfPunkt() {
    val view = androidx.compose.ui.platform.LocalView.current
    val d = androidx.compose.ui.platform.LocalDensity.current
    val radius = remember(view) {
        var r = 0
        if (android.os.Build.VERSION.SDK_INT >= 31) try {
            r = view.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
        } catch (e: Exception) { }
        if (r <= 0) r = with(d) { 24.dp.roundToPx() }      // unbekannt: typische Ecke
        r
    }
    // auf der Diagonale der Ecke: ca. 0,3 x Radius nach innen, plus etwas Luft
    val rein = with(d) { (radius * 0.3f).toDp() } + 3.dp
    Box(Modifier.align(Alignment.TopStart).offset(rein, rein).size(4.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White))
}

/** liegt der Baustein auf dem Startbildschirm? Sonst einen an einen freien Platz legen */
internal fun unsichtbarHinlegen(ctx: android.content.Context): Boolean {
    val l = SeitenRaster.laden(ctx, "start")
    if (l.any { it.typ == "geheim" }) return false
    val k = SeitenRaster.freierPlatz(l, Baustein("geheim", "geheim", 0, 0, 2, 2))
    SeitenRaster.speichern(ctx, l + k, "start")
    return true
}
