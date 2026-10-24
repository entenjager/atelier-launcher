package de.atelierlauncher.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

// ============================================================================
//  Welle aus Milchglas: die Wasserflaeche der Welle hinter dem Alphabet zeigt den Startbildschirm darunter
//  (Hintergrund + Bausteine) unscharf. Dafuer werden Hintergrund und Bausteine beim Zeichnen "mitgeschnitten"
//  (GraphicsLayer = Zeichenliste, kein Bild) und in der Welle weichgezeichnet nochmal gemalt.
//  Kopien (Milchglas-Rahmen, Glas-Uhr) malen die Welle normal - sonst wuerde sich das Bild selbst enthalten.
// ============================================================================

/** true in nachgemalten Kopien des Hintergrunds */
internal val LocalKopie = staticCompositionLocalOf { false }

internal object WelleGlas {
    var hg: GraphicsLayer? = null
    var inhalt: GraphicsLayer? = null
    var unscharf: GraphicsLayer? = null
    @Volatile var an = false
    @Volatile var radiusDp = 20f
    @Volatile var spiegel = 1f

    /** vor dem Zeichnen der Wellen: unscharfe Kopie von Hintergrund + Bausteinen bereitlegen */
    fun DrawScope.vorbereiten(): GraphicsLayer? {
        if (!an || Heim.leicht) return null              // gerade zurueckgekommen: erst ohne Glas
        val u = unscharf ?: return null
        val r = radiusDp * density
        u.renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
        u.record { hg?.let { drawLayer(it) }; inhalt?.let { drawLayer(it) } }
        return u
    }
}

/** Ebenen anlegen und an-/ausschalten (einmal auf der Startseite) */
@Composable
internal fun WelleGlasEbenen(an: Boolean, radiusDp: Float, spiegel: Float) {
    val a = rememberGraphicsLayer(); val b = rememberGraphicsLayer(); val c = rememberGraphicsLayer()
    SideEffect {
        WelleGlas.hg = a; WelleGlas.inhalt = b; WelleGlas.unscharf = c
        WelleGlas.an = an; WelleGlas.radiusDp = radiusDp; WelleGlas.spiegel = spiegel
    }
}

/** diese Flaeche fuer die Glas-Welle mitschneiden (sieht ganz normal aus) */
internal fun Modifier.mitschneiden(ebene: () -> GraphicsLayer?): Modifier = this.drawWithContent {
    val e = ebene()
    if (!WelleGlas.an || e == null) { drawContent(); return@drawWithContent }
    e.record { this@drawWithContent.drawContent() }
    drawLayer(e)
}
