package de.atelierlauncher.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Sichern & Wiederherstellen (erst Atelier): Startbildschirm (Baukasten), Seite bei #, Design,
//  Themes, eigene Bausteine, Favoriten, Alphabet ... als eine Datei (JSON) - z. B. fuers neue Handy.
//  Zugangsdaten - die bleiben nur auf dem Handy.
//  Wiederherstellen ersetzt alles andere und startet den Launcher neu.
// ============================================================================
object Sicherung {
    private const val ART = "atelier-sicherung"

    /** gehoert nicht in die Datei (bleibt auf dem Handy, wird beim Wiederherstellen auch nicht ueberschrieben) */
    private fun bleibtHier(k: String): Boolean {
        val l = k.lowercase()
        return listOf("code", "geheim", "privat", "versteckt", "pass", "token", "muster", "dateizweck").any { it in l }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private fun schriften(ctx: Context) = File(ctx.filesDir, "fonts")

    fun dateiName(): String = ("atelier") + "-sicherung-" + java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()) + ".json"

    fun erstellen(ctx: Context): String {
        val werte = JSONObject()
        for ((k, v) in prefs(ctx).all) {
            if (bleibtHier(k) || v == null) continue
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> o.put("t", "m").put("v", JSONArray(v.filterIsInstance<String>()))
                else -> continue
            }
            werte.put(k, o)
        }
        val dateien = JSONObject()
        schriften(ctx).listFiles()?.forEach { f ->
            if (f.isFile && f.length() < 5_000_000) dateien.put("fonts/" + f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        return JSONObject().put("art", ART).put("version", 1).put("datum", System.currentTimeMillis())
            .put("ordner", ctx.filesDir.path).put("werte", werte).put("dateien", dateien).toString()
    }

    /** prueft die Datei - null = keine Sicherung */
    fun lesen(text: String): JSONObject? = try {
        JSONObject(text).takeIf { it.optString("art") == ART && it.has("werte") }
    } catch (e: Exception) { null }

    /** alles ersetzen (ausser dem, was nur auf dem Handy bleibt) */
    fun einspielen(ctx: Context, s: JSONObject) {
        // eigene Schriften zurueck - Pfade zeigen danach auf diesen Ordner (anderes Handy = anderer Pfad)
        val dateien = s.optJSONObject("dateien")
        if (dateien != null) {
            schriften(ctx).mkdirs()
            dateien.keys().forEach { name ->
                if (!name.startsWith("fonts/") || name.contains("..")) return@forEach
                try { File(ctx.filesDir, name).writeBytes(Base64.decode(dateien.getString(name), Base64.NO_WRAP)) } catch (e: Exception) { }
            }
        }
        val alterOrdner = s.optString("ordner")
        fun pfad(v: String) = if (alterOrdner.isNotEmpty() && alterOrdner != ctx.filesDir.path) v.replace(alterOrdner, ctx.filesDir.path) else v
        val p = prefs(ctx)
        val e = p.edit()
        p.all.keys.filter { !bleibtHier(it) }.forEach { e.remove(it) }
        val werte = s.getJSONObject("werte")
        werte.keys().forEach { k ->
            if (bleibtHier(k)) return@forEach
            val o = werte.optJSONObject(k) ?: return@forEach
            when (o.optString("t")) {
                "s" -> e.putString(k, pfad(o.optString("v")))
                "b" -> e.putBoolean(k, o.optBoolean("v"))
                "i" -> e.putInt(k, o.optInt("v"))
                "l" -> e.putLong(k, o.optLong("v"))
                "f" -> e.putFloat(k, o.optDouble("v").toFloat())
                "m" -> e.putStringSet(k, o.optJSONArray("v").strings().toSet())
            }
        }
        e.commit()          // sofort schreiben - gleich danach startet der Launcher neu
    }

    /** Launcher neu starten, damit alles frisch aus den Einstellungen gelesen wird */
    fun neuStarten(akt: MainActivity) {
        try {
            akt.startActivity(Intent(akt, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) { }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** System → Sichern & Wiederherstellen */
@Composable
internal fun SicherungKarte() {
    val akt = LocalContext.current as MainActivity
    val p = LocalPalette.current
    var gefunden by remember { mutableStateOf<JSONObject?>(null) }
    Gruppe("Sichern & Wiederherstellen") {
        Text("Startbildschirm, Seite bei #, Design, Themes, eigene Bausteine, Favoriten und Schriften als eine Datei – z. B. fürs neue Handy. " +
             "Code, privater Bereich und versteckte Apps kommen nicht mit.",
             color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        MenueEintrag("Sichern", "Als Datei speichern (z. B. in Downloads oder Drive)", rechts = "›") {
            akt.dateiSpeichern(Sicherung.dateiName()) { uri ->
                Thread {
                    val ok = try {
                        val text = Sicherung.erstellen(akt)
                        akt.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } != null
                    } catch (e: Exception) { false }
                    akt.runOnUiThread {
                        Toast.makeText(akt, Sprache.tr(if (ok) "Gesichert" else "Sichern hat nicht geklappt"), Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        }
        MenueEintrag("Wiederherstellen", "Sicherungs-Datei wählen – ersetzt das jetzige Aussehen", rechts = "›") {
            akt.dateiWaehlen(arrayOf("application/json", "text/*", "*/*")) { uri: Uri ->
                akt.textLesen(uri) { t ->
                    val s = Sicherung.lesen(t)
                    if (s == null) Toast.makeText(akt, Sprache.tr("Das ist keine Sicherung"), Toast.LENGTH_SHORT).show()
                    else gefunden = s
                }
            }
        }
    }
    gefunden?.let { s ->
        Dialog(onDismissRequest = { gefunden = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(20.dp)) {
                Text("Wiederherstellen?", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                val datum = java.text.SimpleDateFormat("d. MMMM yyyy, HH:mm", Sprache.locale).format(java.util.Date(s.optLong("datum")))
                Text(Sprache.tr("Sicherung vom ") + datum, color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Startbildschirm, Design und Einstellungen werden ersetzt. Danach startet der Launcher kurz neu.",
                     color = p.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp, bottom = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Knopf("Abbrechen", Modifier.weight(1f)) { gefunden = null }
                    Knopf("Wiederherstellen", Modifier.weight(1f)) {
                        Sicherung.einspielen(akt, s)
                        Sicherung.neuStarten(akt)
                    }
                }
            }
        }
    }
}
