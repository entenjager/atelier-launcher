package de.atelierlauncher.app

import android.content.Context
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.Locale

// ============================================================================
//  Sprache (Atelier): Deutsch oder Englisch - nach der Handy-Sprache oder fest eingestellt.
//  Die Uebersetzungen liegen in res/raw/uebersetzung_en.txt (Zeile: deutsch TAB englisch,
//  {0} {1} ... = Platzhalter). Jeder Text im Launcher laeuft durch Text() unten -> wird uebersetzt.
// ============================================================================
object Sprache {
    @Volatile var englisch = false
        private set
    /** zum Neuzeichnen, wenn die Sprache umgestellt wird */
    val stand = mutableIntStateOf(0)
    val locale: Locale get() = if (englisch) Locale.ENGLISH else Locale.GERMANY

    private val genau = HashMap<String, String>()
    private val muster = ArrayList<Pair<Regex, String>>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** "auto" (wie das Handy), "de" oder "en" */
    fun wahl(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("sprache", "auto") ?: "auto"

    fun setzen(ctx: Context, w: String) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("sprache", w).apply()
        laden(ctx); stand.intValue++
    }

    fun laden(ctx: Context) {
        val w = wahl(ctx)
        val id = ctx.resources.getIdentifier("uebersetzung_en", "raw", ctx.packageName)
        englisch = id != 0 && (w == "en" || (w == "auto" && Locale.getDefault().language != "de"))
        cache.clear()
        if (!englisch || genau.isNotEmpty()) return
        try {
            ctx.resources.openRawResource(id).bufferedReader(Charsets.UTF_8).useLines { zeilen ->
                val m = ArrayList<Triple<Int, Regex, String>>()
                zeilen.forEach { z ->
                    val tab = z.indexOf('\t'); if (tab <= 0) return@forEach
                    val de = z.substring(0, tab).replace("\\n", "\n").replace("\\\\", "\\")
                    val en = z.substring(tab + 1).replace("\\n", "\n").replace("\\\\", "\\")
                    if (!de.contains('{')) genau[de] = en
                    else {
                        // "Größe: {0} × {1} Felder" -> ^Größe: (.+?) × (.+?) Felder$
                        val teile = de.split(Regex("\\{\\d\\}"))
                        val nummern = Regex("\\{(\\d)\\}").findAll(de).map { it.groupValues[1] }.toList()
                        val sb = StringBuilder("^")
                        teile.forEachIndexed { i, t -> sb.append(Regex.escape(t)); if (i < nummern.size) sb.append("(.+?)") }
                        sb.append("$")
                        // Reihenfolge der Platzhalter in der Vorlage merken (meist 0,1,2...)
                        var e = en
                        nummern.forEachIndexed { pos, nr -> e = e.replace("{$nr}", "{#$pos}") }
                        m += Triple(teile.sumOf { it.length }, Regex(sb.toString(), RegexOption.DOT_MATCHES_ALL), e)
                    }
                }
                // genauere Vorlagen (mehr fester Text) zuerst
                m.sortedByDescending { it.first }.forEach { muster += it.second to it.third }
            }
        } catch (e: Exception) { }
    }

    /** einen Text uebersetzen (Deutsch bleibt Deutsch, Unbekanntes bleibt wie es ist) */
    fun tr(s: String): String {
        if (!englisch || s.isBlank()) return s
        cache[s]?.let { return it }
        val r = genau[s] ?: run {
            for ((re, en) in muster) {
                val t = re.matchEntire(s) ?: continue
                var out = en
                t.groupValues.drop(1).forEachIndexed { i, g -> out = out.replace("{#$i}", tr(g)) }
                return@run out
            }
            s
        }
        if (cache.size < 4000) cache[s] = r
        return r
    }
}

/** Text mit Uebersetzung - alle Dateien benutzen diesen statt androidx.compose.material3.Text */
@Composable
fun TextUe(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    @Suppress("UNUSED_VARIABLE") val s = Sprache.stand.intValue
    // Tropf-Uhr (24.10.): fallende Tropfen landen auf Buchstaben - dafuer merkt sich jeder Text seine Lage
    val ziel = if (TropfZiele.aktiv) androidx.compose.runtime.remember { TropfZiele.Ziel() } else null
    if (ziel != null) androidx.compose.runtime.DisposableEffect(ziel) { TropfZiele.ziele.add(ziel); onDispose { TropfZiele.ziele.remove(ziel) } }
    val beimLayout: ((TextLayoutResult) -> Unit)? = if (ziel == null) onTextLayout else { r: TextLayoutResult ->
        ziel.text = r
        if (onTextLayout != null) onTextLayout(r)
    }
    androidx.compose.material3.Text(
        Sprache.tr(text), modifier = if (ziel != null) modifier.onGloballyPositioned { ziel.lage = it } else modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
        fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines,
        onTextLayout = beimLayout, style = style,
    )
}
