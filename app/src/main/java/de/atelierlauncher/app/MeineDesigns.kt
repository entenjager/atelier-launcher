package de.atelierlauncher.app

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONObject
import java.io.File
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Meine Designs: der komplette Stand (Startbildschirm-Bausteine, Design, alle Einstellungen) als Momentaufnahme
//  versteckte Apps, Logins), nur ohne Datei-Auswahl. Beim ersten Start nach dem Update wird der jetzige Stand
//  einmal automatisch gespeichert.
// ============================================================================
internal object MeineDesigns {
    private fun ordner(ctx: Context) = File(ctx.filesDir, "meine_designs").also { it.mkdirs() }
    val stand = mutableIntStateOf(0)

    data class Eintrag(val datei: File, val name: String, val datum: Long)

    fun liste(ctx: Context): List<Eintrag> = (ordner(ctx).listFiles() ?: emptyArray())
        .filter { it.isFile && it.name.endsWith(".json") }
        .mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                Eintrag(f, o.optString("name", f.nameWithoutExtension), o.optLong("datum"))
            } catch (e: Exception) { null }
        }.sortedByDescending { it.datum }

    fun speichern(ctx: Context, name: String): Boolean = try {
        val o = JSONObject(Sicherung.erstellen(ctx))
        o.remove("dateien")                          // Schriften liegen eh auf dem Handy
        o.put("name", name)
        File(ordner(ctx), "d" + System.currentTimeMillis() + ".json").writeText(o.toString())
        stand.intValue++
        true
    } catch (e: Exception) { false }

    fun laden(akt: MainActivity, e: Eintrag) {
        val s = Sicherung.lesen(e.datei.readText()) ?: run {
            Toast.makeText(akt, Sprache.tr("Kann nicht geladen werden"), Toast.LENGTH_SHORT).show(); return
        }
        Sicherung.einspielen(akt, s)
        Sicherung.neuStarten(akt)
    }

    fun loeschen(e: Eintrag) { e.datei.delete(); stand.intValue++ }

    /** einmal nach diesem Update: den jetzigen Stand automatisch merken */
    fun beimStart(ctx: Context) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        if (p.getBoolean("meinDesignErster", false)) return
        p.edit().putBoolean("meinDesignErster", true).commit()
        Thread {
            val datum = java.text.SimpleDateFormat("d.M.", java.util.Locale.GERMAN).format(java.util.Date())
            speichern(ctx, "Mein Design ($datum)")
        }.start()
    }
}

@Composable
internal fun MeineDesignsGruppe() {
    val akt = LocalContext.current as MainActivity
    val p = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val s = MeineDesigns.stand.intValue
    val liste = MeineDesigns.liste(akt)
    var neu by remember { mutableStateOf(false) }
    var offen by remember { mutableStateOf<MeineDesigns.Eintrag?>(null) }
    Gruppe("Meine Designs") {
        liste.forEach { e ->
            val datum = java.text.SimpleDateFormat("d. MMM, HH:mm", Sprache.locale).format(java.util.Date(e.datum))
            MenueEintrag(e.name, datum, rechts = "›") { offen = e }
        }
        if (liste.isEmpty()) Text("Noch keins gespeichert", color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        MenueEintrag("Jetziges Design speichern", "Startbildschirm, Bausteine, Design und Einstellungen – als Ganzes", rechts = "+") { neu = true }
    }
    if (neu) Dialog(onDismissRequest = { neu = false }) {
        var name by remember { mutableStateOf("Mein Design " + (liste.size + 1)) }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(20.dp)) {
            Text("Design speichern", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
            Eingabe(name, { name = it }, "Name")
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Knopf("Abbrechen", Modifier.weight(1f)) { neu = false }
                Knopf("Speichern", Modifier.weight(1f)) {
                    val ok = MeineDesigns.speichern(akt, name.ifBlank { "Mein Design" })
                    Toast.makeText(akt, Sprache.tr(if (ok) "Gespeichert" else "Speichern hat nicht geklappt"), Toast.LENGTH_SHORT).show()
                    neu = false
                }
            }
        }
    }
    offen?.let { e ->
        Dialog(onDismissRequest = { offen = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(20.dp)) {
                Text(e.name, color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                Text("Laden ersetzt Startbildschirm, Design und Einstellungen durch diesen Stand. Danach startet der Launcher kurz neu. " +
                     "Code und privater Bereich bleiben, wie sie sind.",
                     color = p.leise, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Knopf("Löschen", Modifier.weight(1f)) { MeineDesigns.loeschen(e); offen = null }
                    Knopf("Laden", Modifier.weight(1f)) { MeineDesigns.laden(akt, e) }
                }
            }
        }
    }
}
