package de.atelierlauncher.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ============================================================================
//  Fuer den Entwickler: Aussehen und Anordnung als Text ausgeben, um daraus den Standard fuer neue
//  Installationen zu machen. Nur per USB abrufbar (adb shell dumpsys activity ... standard), nur Atelier.
//  Ohne Persoenliches: keine Favoriten, Namen, Notizen, Orte, Codes; Texte mit Punkten, Leerzeichen oder
//  Pfaden (App-Namen, eigene Dateien, Notiz-Texte) werden durch "…" ersetzt.
// ============================================================================
internal object StandardExport {
    private val WEG = Regex("code|geheim|privat|versteckt|pass|token|muster|favorit|buendel|name|umbenannt|eigeneicon|icon_|geburt|termin|notiz|kontakt|ort|wetter|link|mail|foto|bild|datei|verlauf|zuletzt|suche|einfuehrung|kalender|sperrzaehler|rect", RegexOption.IGNORE_CASE)
    private val HARMLOS = Regex("^[A-Za-z0-9_#:+-]{0,24}$")

    fun text(ctx: Context): String {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val o = JSONObject()
        val m = ctx.resources.displayMetrics
        o.put("_bildschirm", "${(m.widthPixels / m.density).toInt()}x${(m.heightPixels / m.density).toInt()} dp, dichte ${m.density}")
        for ((k, v) in p.all.toSortedMap()) {
            if (WEG.containsMatchIn(k) && !k.startsWith("raster")) continue
            o.put(k, saeubern(v))
        }
        return "===STANDARD-ANFANG===\n" + o.toString(2) + "\n===STANDARD-ENDE==="
    }

    private fun saeubern(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Boolean, is Int, is Long, is Float, is Double -> v
        is Set<*> -> "…"                                        // Mengen sind Listen von Apps
        is JSONObject -> JSONObject().also { n -> v.keys().forEach { k -> if (!WEG.containsMatchIn(k)) n.put(k, saeubern(v.opt(k))) } }
        is JSONArray -> JSONArray().also { n -> for (i in 0 until v.length()) n.put(saeubern(v.opt(i))) }
        is String -> {
            val t = v.trim()
            when {
                t.startsWith("{") -> try { saeubern(JSONObject(t)) } catch (e: Exception) { "…" }
                t.startsWith("[") -> try { saeubern(JSONArray(t)) } catch (e: Exception) { "…" }
                HARMLOS.matches(t) -> t
                else -> "…"
            }
        }
        else -> "…"
    }
}
