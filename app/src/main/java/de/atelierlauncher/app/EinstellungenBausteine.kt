package de.atelierlauncher.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// Einstellungen: gemeinsame Bausteine (Farbwahl, Segmente, Wahl-Chips ...)
// ---------------------------------------------------------------- Design
/** runder Farbpunkt zum Antippen */
@Composable
internal fun FarbPunkt(farbe: Brush, gewaehlt: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(farbe)
            .border(if (gewaehlt) 3.dp else 1.dp, if (gewaehlt) p.text else p.linie, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** Swiss-Plakatfarben: Signalrot, Orange, Gelb, Gruen, Tuerkis, Ultramarin, Violett, Weiss */
internal val SWISS_FARBEN = listOf(
    0xFFE2001A.toInt(), 0xFFFF5A00.toInt(), 0xFFFFC400.toInt(), 0xFF00A651.toInt(),
    0xFF00A3AD.toInt(), 0xFF1F3FD1.toInt(), 0xFF7A3CFF.toInt(), 0xFFFFFFFF.toInt(),
    0xFF8E8E93.toInt(),                 // neutrales Grau (Swiss nur Schwarz/Grau)
)

/** Ganz eigene Farbe: Farbton, Saettigung, Helligkeit. Vorschau sofort, gespeichert beim Loslassen. */
@Composable
internal fun EigeneFarbe(aktuell: Int, setzen: (Int) -> Unit) {
    val p = LocalPalette.current
    val start = remember(aktuell) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(if (aktuell == 0) p.akzent.toArgb() else aktuell, it) }
    }
    var ton by remember(aktuell) { mutableStateOf(start[0]) }
    var satt by remember(aktuell) { mutableStateOf(start[1]) }
    var hell by remember(aktuell) { mutableStateOf(start[2]) }
    val vorschau = android.graphics.Color.HSVToColor(floatArrayOf(ton, satt, hell))
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Eigene Farbe", fontSize = 15.sp, modifier = Modifier.weight(1f))
        Box(Modifier.size(width = 56.dp, height = 28.dp).clip(rund(6.dp)).background(Color(vorschau)).border(1.dp, p.linie, rund(6.dp)))
    }
    val fertig = { setzen(android.graphics.Color.HSVToColor(floatArrayOf(ton, satt, hell))) }
    FarbRegler("Farbton", ton, 0f..360f, fertig) { ton = it }
    FarbRegler("Sättigung", satt, 0f..1f, fertig) { satt = it }
    FarbRegler("Helligkeit", hell, 0f..1f, fertig) { hell = it }
}

@Composable
internal fun FarbRegler(name: String, wert: Float, bereich: ClosedFloatingPointRange<Float>, fertig: () -> Unit, aendern: (Float) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = p.leise, fontSize = 13.sp, modifier = Modifier.width(78.dp))
        Slider(value = wert, onValueChange = aendern, valueRange = bereich, onValueChangeFinished = fertig, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun FarbReihe(farben: List<Int>, aktuell: Int, waehle: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        farben.forEach { f -> FarbPunkt(SolidColor(Color(f)), aktuell == f) { waehle(f) } }
    }
}

/** Auswahl wie ein Schiebeschalter mit mehreren Feldern */
@Composable
internal fun Segmente(optionen: List<String>, gewaehlt: Int, waehle: (Int) -> Unit) {
    val p = LocalPalette.current
    if (NeuStil.an) { NeuSegmente(optionen.mapIndexed { i, o -> i.toString() to o }, gewaehlt.toString()) { waehle(it.toInt()) }; return }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(rund(16.dp))
            .background(p.text.copy(alpha = 0.06f)).padding(4.dp)) {
        optionen.forEachIndexed { i, o ->
            Box(
                Modifier.weight(1f).clip(rund(12.dp))
                    .background(if (i == gewaehlt) p.akzent else Color.Transparent)
                    .clickable { waehle(i) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, color = if (i == gewaehlt) p.aufAkzent else p.text, fontSize = 14.sp, maxLines = 1) }
        }
    }
}

/** kleine Live-Vorschau eines Designs (steht still - kostet nichts) */
@Composable
internal fun DesignKachel(art: String, gewaehlt: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.width(96.dp).clip(rund(18.dp)).clickable(onClick = onClick).padding(4.dp),
           horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(rund(16.dp)).background(p.grund)
                .border(if (gewaehlt) 3.dp else 1.dp, if (gewaehlt) p.akzent else p.linie, rund(16.dp)),
        ) {
            when (art) {
                "system" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF55606A), Color(0xFF8A7F74)))))
                "schwarz" -> {}
                // eigene: nur ein Zeichen (Vorschau wuerde Video/Code starten)
                "bild" -> if (EigeneDateien.bild(androidx.compose.ui.platform.LocalContext.current).exists()) Hintergrund(art, Modifier.fillMaxSize(), laeuft = false)
                          else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("▣", color = p.akzent, fontSize = 28.sp) }
                "video", "lottie", "code" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(when (art) { "video" -> "▶"; "lottie" -> "✦"; else -> "{ }" }, color = p.akzent, fontSize = 28.sp)
                }
                else -> Hintergrund(art, Modifier.fillMaxSize(), laeuft = false)
            }
            if (gewaehlt) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp).clip(CircleShape).background(p.akzent),
                              contentAlignment = Alignment.Center) { Text("✓", color = p.aufAkzent, fontSize = 12.sp) }
        }
        Text(designName(art), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * Aussehen als Baukasten: oben eine Vorlage als Startpunkt, darunter jedes Teil einzeln.
 * Alles ist frei mischbar - z. B. Swiss-Hintergrund + Swiss-Icons + Wellen-Alphabet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Wahl(titel: String, optionen: List<Pair<String, String>>, aktuell: String, hinweis: String? = null, waehle: (String) -> Unit) {
    val p = LocalPalette.current
    // neue Einstellungen: 2-3 kurze Moeglichkeiten als Umschalter statt Chips
    if (NeuStil.an && optionen.size in 2..3 && optionen.sumOf { Sprache.tr(it.second).length } <= 30) {
        Column(Modifier.fillMaxWidth().neuTrenner().padding(top = 10.dp, bottom = 4.dp)) {
            Text(titel, fontSize = 16.sp, color = p.text, modifier = Modifier.padding(horizontal = 16.dp))
            NeuSegmente(optionen, aktuell, waehle)
            if (hinweis != null) Text(hinweis, color = p.leise, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
        }
        return
    }
    Text(titel, fontSize = 16.sp, color = p.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        optionen.forEach { (wert, name) -> Chip(name, gewaehlt = wert == aktuell) { waehle(wert) } }
    }
    if (hinweis != null) Text(hinweis, color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
}
