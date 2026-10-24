package de.atelierlauncher.app

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Geburtstag eintragen (Name + Tag) - aus den Einstellungen und direkt aus dem Termine-Baustein */
@Composable
fun GeburtstagNeuDialog(onZu: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var name by remember { mutableStateOf("") }
    var datum by remember { mutableStateOf("") }
    val gelesen = Geburtstage.datumMitJahr(datum)
    fun fertig() {
        if (name.isBlank() || gelesen == null) { Toast.makeText(ctx, Sprache.tr("Was und wann (z. B. 20.11) eintragen"), Toast.LENGTH_SHORT).show(); return }
        Geburtstage.eintragen(ctx, name, gelesen.first, gelesen.second, gelesen.third); onZu()
    }
    Dialog(onDismissRequest = onZu) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(vertical = 16.dp)) {
            Text("Termin eintragen", color = p.text, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Eingabe(name, { name = it }, "Was? z. B. Geburtstag von Max, Zahnarzt", fokus = true)
            Eingabe(datum, { datum = it }, "Wann? 20.11 = jedes Jahr, 20.11.2026 = einmal", onFertig = { fertig() })
            Text(if (gelesen != null) "→ " + Geburtstage.datumText(Geburtstage.G("", gelesen.second, gelesen.first, gelesen.third)) else " ",
                 color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 22.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Knopf("Abbrechen", Modifier.weight(1f)) { onZu() }
                Knopf("Speichern", Modifier.weight(1f)) { fertig() }
            }
        }
    }
}

/** Geburtstag loeschen - mit Rueckfrage */
@Composable
fun GeburtstagLoeschenDialog(g: Geburtstage.G, onZu: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    Dialog(onDismissRequest = onZu) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(vertical = 16.dp)) {
            Text("${g.name} (${Geburtstage.datumText(g)}) löschen?", color = p.text, fontSize = 18.sp,
                 modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Knopf("Behalten", Modifier.weight(1f)) { onZu() }
                Knopf("Löschen", Modifier.weight(1f)) { Geburtstage.loeschen(ctx, g); onZu() }
            }
        }
    }
}
