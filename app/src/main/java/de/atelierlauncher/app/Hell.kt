package de.atelierlauncher.app

import android.content.Context

/** ist das Design gerade dunkel? (fuer Fenster ohne Compose, z. B. Sperrbildschirm und Lautstaerke) */
object Hell {
    fun dunkel(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val thema = try { org.json.JSONObject(p.getString("thema", "{}") ?: "{}") } catch (e: Exception) { org.json.JSONObject() }
        // Art Deco ist immer dunkel (schwarzer Marmor)
        if (thema.optString("farben") == "deko") return true
        return when (thema.optInt("modus", 1)) {
            0 -> false; 1 -> true
            else -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }
}

/** ein Termin (Kalender oder selbst eingetragen) */
data class TerminEintrag(val titel: String, val beginn: Long, val ende: Long, val ganztag: Boolean, val ort: String, val frei: Boolean = false,
                         /** selbst eingetragen (Eigene Termine) - kann man loeschen */
                         val eigen: Boolean = false)
