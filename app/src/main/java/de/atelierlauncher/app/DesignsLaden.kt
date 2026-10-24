package de.atelierlauncher.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Design → Themes → "Weitere Designs" (nur Atelier): jedes Design einmal laden.
//  Danach stehen seine Themes oben bei den Vorlagen und seine Teile (Icons, Schrift,
//  Hintergrund, Randgeste ...) in den jeweiligen Menues.
// ============================================================================

/** was ein Design mitbringt - fuer die Beschreibung */
private val TEIL_NAMEN = listOf(
    "farben" to "Farbschema", "hintergrund" to "Hintergrund", "icons" to "Icons", "schrift" to "Schrift",
    "typo" to "Überschriften", "liste" to "App-Liste", "alphabet" to "Alphabet", "buchstabe" to "Alphabet-Anzeige",
    "daumen" to "Daumen-Effekt", "randgeste" to "Randgeste", "sperr" to "Sperr-Übergang", "laut" to "Lautstärke-Regler",
)

/** wird das Design gerade benutzt? (dann nicht entfernen - sonst waere die Einstellung verschwunden) */
private fun benutzt(ctx: Context, z: Zustand, pk: Designs.Paket): Boolean {
    val t = z.thema
    if (t.name in pk.themen) return true
    val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    val werte = mapOf("farben" to t.farben, "hintergrund" to t.hintergrund, "icons" to t.icons, "schrift" to t.schrift,
                      "alphabet" to t.alphabet, "typo" to t.typo, "liste" to t.liste, "buchstabe" to t.buchstabe,
                      "laut" to LautDesign.art(ctx), "randgeste" to Randgesten.stil(p), "sperr" to SperrSeite.art(ctx),
                      "daumen" to if (z.alphabetKante) "kante" else "passend")
    return werte.any { (art, w) ->
        // nur wenn kein anderes geladenes Design denselben Teil auch mitbringt
        w in pk.teile[art].orEmpty() && Designs.PAKETE.none { it.id != pk.id && it.id in Designs.geladen && w in it.teile[art].orEmpty() }
    }
}

@Composable
internal fun WeitereDesigns() {
    val p = LocalPalette.current
    Abschnitt("Weitere Designs")
    Text("Ab Werk ist nur „${Designs.GRUND}“ dabei. Jedes Design lädst du einmal – es bringt seine Themes mit, dazu passende Icons, Schrift, Hintergrund und mehr. Nichts wird aus dem Internet geladen.",
         color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
    Designs.PAKETE.forEach { pk -> DesignPaketKarte(pk) }
}

@Composable
private fun DesignPaketKarte(pk: Designs.Paket) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val geladen = pk.id in Designs.geladen
    var laedt by remember { mutableStateOf(false) }
    val fortschritt = remember { mutableFloatStateOf(0f) }
    var hinweis by remember { mutableStateOf<String?>(null) }
    val themen = FERTIGE_THEMEN.filter { it.name in pk.themen }
    Gruppe {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row {
                Text(pk.name, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                if (geladen) Text("Geladen ✓", color = p.akzent, fontSize = 13.sp)
            }
            Text(pk.text, color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
            val teile = TEIL_NAMEN.filter { (art, _) -> pk.teile[art].orEmpty().isNotEmpty() }.joinToString(" · ") { Sprache.tr(it.second) }
            if (teile.isNotEmpty()) Text(Sprache.tr("Bringt mit: ") + teile, color = p.leise, fontSize = 12.sp, lineHeight = 16.sp,
                                         modifier = Modifier.padding(top = 4.dp))
        }
        // Vorschau der Themes - geladen: antippen = benutzen
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            themen.forEach { v ->
                ThemaKarte(v, geladen && v.name == z.thema.name) {
                    if (geladen) z.themaSetzen(v) else hinweis = "Erst laden, dann antippen zum Benutzen"
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            when {
                laedt -> {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.linie)) {
                        Box(Modifier.fillMaxWidth(fortschritt.floatValue).height(4.dp).background(p.akzent))
                    }
                    Text("Wird geladen …", color = p.leise, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                }
                !geladen -> Knopf("Laden") {
                    laedt = true; hinweis = null
                    scope.launch {
                        for (i in 1..12) { kotlinx.coroutines.delay(35); fortschritt.floatValue = i / 12f }
                        Designs.geladenSetzen(ctx, pk.id, true)
                        laedt = false
                    }
                }
                else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    themen.firstOrNull()?.let { erstes ->
                        if (themen.none { it.name == z.thema.name }) Knopf("Benutzen", Modifier.weight(1f)) { z.themaSetzen(erstes) }
                    }
                    Knopf("Entfernen", Modifier.weight(1f)) {
                        if (benutzt(ctx, z, pk)) hinweis = "Wird gerade benutzt – erst ein anderes Theme wählen (z. B. „${Designs.GRUND}“)"
                        else { Designs.geladenSetzen(ctx, pk.id, false); hinweis = null }
                    }
                }
            }
            hinweis?.let { Text(it, color = p.warnung, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}
