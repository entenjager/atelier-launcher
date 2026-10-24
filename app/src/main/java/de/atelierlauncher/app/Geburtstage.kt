package de.atelierlauncher.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

// ============================================================================
//  Am Tag steht oben unter dem Datum "<Name> hat heute Geburtstag", und sie erscheinen bei den Terminen.
// ============================================================================
object Geburtstage {
    /** ein eigener Termin: Titel, Tag, Monat - jahr = 0 heisst jedes Jahr (z. B. Geburtstag), sonst nur einmal */
    data class G(val name: String, val monat: Int, val tag: Int, val jahr: Int = 0)

    /** Aenderungszaehler (Compose zeichnet neu, wenn etwas eingetragen/geloescht wurde) */
    var stand by mutableIntStateOf(0)
    @Volatile private var liste: List<G>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun alle(ctx: Context): List<G> {
        liste?.let { return it }
        val p = prefs(ctx)
        var l = try {
            val a = JSONArray(p.getString("geburtstage", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { G(it.getString("n"), it.getInt("m"), it.getInt("t"), it.optInt("j", 0)) } }
        } catch (e: Exception) { emptyList() }
        if (!p.getBoolean("geburtstageUebernommen", false)) {
            val id = ctx.resources.getIdentifier("geburtstage", "array", ctx.packageName)
            if (id != 0) try {
                val vorgabe = ctx.resources.getStringArray(id).mapNotNull { e ->
                    val name = e.substringBefore('|').trim()
                    val (m, t) = e.substringAfter('|').split('-').map { it.trim().toInt() }
                    if (name.isEmpty()) null else G(name, m, t)
                }
                l = (l + vorgabe).distinct()
            } catch (e: Exception) { }
            liste = l; speichern(ctx, l, melden = false)
            p.edit().putBoolean("geburtstageUebernommen", true).apply()
        }
        liste = l
        return l
    }

    private fun speichern(ctx: Context, l: List<G>, melden: Boolean = true) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("m", it.monat).put("t", it.tag).put("j", it.jahr)) }
        prefs(ctx).edit().putString("geburtstage", a.toString()).apply()
        liste = l; if (melden) stand++
    }

    fun eintragen(ctx: Context, name: String, tag: Int, monat: Int, jahr: Int = 0) {
        speichern(ctx, (alle(ctx) + G(name.trim(), monat, tag, jahr)).sortedWith(compareBy({ it.jahr }, { it.monat }, { it.tag }, { it.name })))
    }

    /** "20.11.2026" -> nur einmal (Tag, Monat, Jahr); ohne Jahr -> jedes Jahr (Jahr 0) */
    fun datumMitJahr(text: String): Triple<Int, Int, Int>? {
        val t = text.trim().trimEnd('.')
        Regex("^(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})\\s*[./-]\\s*(\\d{2,4})$").find(t)?.let { m ->
            val tag = m.groupValues[1].toInt(); val mon = m.groupValues[2].toInt()
            var j = m.groupValues[3].toInt(); if (j < 100) j += 2000
            if (mon in 1..12 && tag in 1..TAGE[mon - 1]) return Triple(tag, mon, j)
            return null
        }
        return datumLesen(t)?.let { Triple(it.first, it.second, 0) }
    }

    fun loeschen(ctx: Context, g: G) = speichern(ctx, alle(ctx) - g)

    /** "20.11" / "20. November" -> (Tag, Monat) oder null */
    fun datumLesen(text: String): Pair<Int, Int>? {
        val t = text.trim().trimEnd('.')
        Regex("^(\\d{1,2})\\s*[./-]\\s*(\\d{1,2})$").find(t)?.let { m ->
            val tag = m.groupValues[1].toInt(); val mon = m.groupValues[2].toInt()
            if (mon in 1..12 && tag in 1..TAGE[mon - 1]) return tag to mon
        }
        val monate = listOf("jan", "feb", "mär", "apr", "mai", "jun", "jul", "aug", "sep", "okt", "nov", "dez")
        Regex("^(\\d{1,2})\\.?\\s*([a-zäA-ZÄ]+)").find(t)?.let { m ->
            val tag = m.groupValues[1].toInt()
            val w = m.groupValues[2].lowercase().replace("mae", "mär").replace("maer", "mär")
            val mon = monate.indexOfFirst { w.startsWith(it) } + 1
            if (mon in 1..12 && tag in 1..TAGE[mon - 1]) return tag to mon
        }
        return null
    }
    private val TAGE = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    fun datumText(g: G): String {
        val monate = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")
        return "${g.tag}. ${monate[g.monat - 1]}" + if (g.jahr != 0) " ${g.jahr}" else " · jedes Jahr"
    }

    /** eigene Termine von heute (Titel) */
    fun heute(ctx: Context, jetzt: Long = System.currentTimeMillis()): List<String> {
        val c = Calendar.getInstance().apply { timeInMillis = jetzt }
        val m = c.get(Calendar.MONTH) + 1; val t = c.get(Calendar.DAY_OF_MONTH); val j = c.get(Calendar.YEAR)
        return alle(ctx).filter { it.monat == m && it.tag == t && (it.jahr == 0 || it.jahr == j) }.map { it.name }
    }

    /** zu einem angezeigten Termin den eigenen Eintrag finden (zum Loeschen) */
    fun ausTermin(ctx: Context, t: TerminEintrag): G? {
        if (!t.eigen) return null
        val c = Calendar.getInstance().apply { timeInMillis = t.beginn }
        val m = c.get(Calendar.MONTH) + 1; val tag = c.get(Calendar.DAY_OF_MONTH); val j = c.get(Calendar.YEAR)
        return alle(ctx).firstOrNull { it.monat == m && it.tag == tag && (it.jahr == 0 || it.jahr == j) && it.name == t.titel }
    }

    /** naechste Geburtstage als ganztaegige Termine (fuer die Termine-Karte) */
    fun alsTermine(ctx: Context, jetzt: Long): List<TerminEintrag> {
        val heute0 = Calendar.getInstance().apply { timeInMillis = jetzt; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        return alle(ctx).mapNotNull { g ->
            val c = Calendar.getInstance().apply { clear(); set(if (g.jahr != 0) g.jahr else heute0.get(Calendar.YEAR), g.monat - 1, 1) }
            if (g.tag > c.getActualMaximum(Calendar.DAY_OF_MONTH)) return@mapNotNull null      // 29.2. ausser im Schaltjahr
            c.set(Calendar.DAY_OF_MONTH, g.tag)
            if (c.before(heute0)) { if (g.jahr != 0) return@mapNotNull null else c.add(Calendar.YEAR, 1) }   // einmalig + vorbei: weg
            TerminEintrag(g.name, c.timeInMillis, c.timeInMillis + 86_400_000L, true, if (g.jahr == 0) "jedes Jahr" else "", eigen = true)
        }
    }
}
