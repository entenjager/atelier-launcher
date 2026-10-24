package de.atelierlauncher.app

import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.mozilla.javascript.Function
import java.net.HttpURLConnection
import java.net.URL

// ============================================================================
//  Das Skript laeuft bei jedem Oeffnen und baut das Menue mit ui.* zusammen.
// ============================================================================

sealed class MenueTeil {
    class Titel(val text: String) : MenueTeil()
    class Text(val text: String) : MenueTeil()
    class Knopf(val text: String, val aktion: Function?) : MenueTeil()
    class Zeile(val titel: String, val unter: String, val aktion: Function?) : MenueTeil()
    object Abstand : MenueTeil()
    object Trenner : MenueTeil()
    class Eingabe(val hinweis: String, val aktion: Function?) : MenueTeil()
    class Schalter(val text: String, val an: Boolean, val aktion: Function?) : MenueTeil()
    class Fehler(val text: String) : MenueTeil()
}

/** ui.alle(sekunden, funktion): solange das Menue offen ist, regelmaessig aufrufen */
class MenueWiederholen(val sekunden: Int, val aktion: Function)

const val MENUE_BEISPIEL = """// Mein Menü – alles hier ist dein eigener Code (JavaScript).
// Das Skript läuft jedes Mal, wenn das Menü aufgeht.
//
// ui.titel(text)              Überschrift
// ui.text(text)               normaler Text
// ui.knopf(text, funktion)    Knopf
// ui.zeile(titel, unter, fn)  Zeile wie eine App
// ui.abstand()                etwas Platz
// ui.trenner()                dünne Linie
// ui.eingabe(hinweis, fn)     Textfeld + OK, fn(text)
// ui.schalter(text, an, fn)   Schalter, fn(neu) mit true/false
// ui.alle(sekunden, fn)       fn alle paar Sekunden (mind. 5), solange das Menü offen ist
// ui.neu()                    Menü neu aufbauen (z. B. nach einem Knopf)
//
// app.oeffnen(paket)   app.link(url)   app.meldung(text)   app.liste()
// netz.holen(url)      -> Text einer Webseite / API
// handy.akku()  handy.zeit()  handy.nachrichten(paket)
// handy.speichern(name, wert)   handy.laden(name)   handy.loeschen(name)
// log(...)             schreibt in die Konsole (Einstellungen → Bastler)

var stunde = new Date().getHours()
ui.titel(stunde < 12 ? "Guten Morgen" : stunde < 18 ? "Hallo" : "Guten Abend")
ui.text("Akku: " + handy.akku() + " %")

var zaehler = Number(handy.laden("zaehler") || 0)
ui.knopf("Gedrückt: " + zaehler + " mal", function () {
    handy.speichern("zaehler", zaehler + 1)
    ui.neu()
})

ui.abstand()
ui.titel("Nachrichten")
var neu = handy.nachrichten("com.whatsapp")
if (neu.length == 0) ui.text("Keine neuen WhatsApp-Nachrichten")
neu.forEach(function (m) {
    ui.zeile(m.titel, m.text, function () { app.oeffnen("com.whatsapp") })
})
"""

/** sammelt, was ein Skript-Lauf mit ui.* baut. Nach dem Aufbau "zu": spaetere ui-Aufrufe werden ignoriert */
class MenueSammler {
    val teile = ArrayList<MenueTeil>()
    @Volatile var zu = false
    @Volatile var neu = false          // ui.neu() wurde (in einem Knopf) aufgerufen
    @Volatile var alle: MenueWiederholen? = null   // ui.alle(...)
}

object MeinMenue {
    var teile by mutableStateOf<List<MenueTeil>>(emptyList())
    var laeuft by mutableStateOf(false)
    /** ui.alle(...) des zuletzt gebauten Menues (null = nichts wiederholen) */
    var alle by mutableStateOf<MenueWiederholen?>(null)
    private val lauf = SkriptLauf("menue").apply { beiHaenger = { t ->
        Konsole.schreib("menue", "Fehler: $t")
        Handler(Looper.getMainLooper()).post { teile = teile + MenueTeil.Fehler(t); laeuft = false }
    } }
    private val haupt = Handler(Looper.getMainLooper())
    @Volatile private var aktuell: MenueSammler? = null

    /** Code holen: zuerst aus dem Bastel-Ordner (menue.js), sonst aus der App. Nicht auf dem Bildschirm-Faden aufrufen! */
    fun code(ctx: android.content.Context): String =
        BastelOrdner.lesen(ctx, "menue.js")
            ?: ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("meinMenueCode", null)
            ?: MENUE_BEISPIEL

    /** speichern (App + Ordner); fertig(true) = ueberall gespeichert */
    fun codeSpeichern(ctx: android.content.Context, code: String, fertig: (Boolean) -> Unit = {}) {
        ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit().putString("meinMenueCode", code).apply()
        lauf.freigeben()
        if (BastelOrdner.gesetzt(ctx)) BastelOrdner.speichern(ctx, "menue.js", code, fertig) else fertig(true)
    }

    /** Skript ausfuehren und das Menue neu aufbauen - jedes Mal in frischer Umgebung (const, let ... gehen) */
    fun aufbauen(ctx: android.content.Context) {
        val app = ctx.applicationContext
        haupt.post { laeuft = true }
        lauf.spaeter(SKRIPT_HOECHST) {
            val quelle = code(app)
            val s = MenueSammler()
            try {
                lauf.zuruecksetzenJetzt()
                lauf.mitKontext(SKRIPT_ZEIT) { cx, scope ->
                    schnittstelle(app, cx, scope, s)
                    cx.evaluateString(scope, quelle, "Mein Menü", 1, null)
                }
            } catch (e: Throwable) {
                val t = JS.fehler(e)
                s.teile += MenueTeil.Fehler(t)
                Konsole.schreib("menue", "Fehler: $t")
            }
            s.zu = true
            s.neu = false                   // ui.neu() beim Aufbau selbst: nichts tun
            aktuell = s
            val fertig = s.teile.toList()   // Kopie: die Liste gehoert ab jetzt nur dem Bildschirm
            val wiederholen = s.alle
            haupt.post { teile = fertig; alle = wiederholen; laeuft = false }
        }
    }

    /** Knopf gedrueckt (oder Eingabe/Schalter/ui.alle): Funktion im Skript-Faden ausfuehren, args = Werte fuer die Funktion */
    fun ausfuehren(ctx: android.content.Context, f: Function, args: Array<Any?> = emptyArray()) {
        val app = ctx.applicationContext
        lauf.spaeter(SKRIPT_HOECHST) {
            var neuBauen = false
            try {
                lauf.mitKontext(SKRIPT_ZEIT) { cx, scope -> f.call(cx, scope, scope, args) }
            } catch (e: Throwable) {
                val t = JS.fehler(e)
                Konsole.schreib("menue", "Fehler: $t")
                haupt.post { teile = teile + MenueTeil.Fehler(t) }
            }
            aktuell?.let { if (it.neu) { it.neu = false; neuBauen = true } }
            if (neuBauen) aufbauen(app)
        }
    }

    /** Code einmal testweise ausfuehren (eigener Wegwerf-Faden). Blockiert - nicht auf dem Bildschirm-Faden! */
    fun testen(ctx: android.content.Context, code: String): TestErgebnis {
        val app = ctx.applicationContext
        val s = MenueSammler()
        return JS.testLauf(SKRIPT_ZEIT) { cx, scope ->
            schnittstelle(app, cx, scope, s, "test")
            cx.evaluateString(scope, code, "Mein Menü", 1, null)
            s.zu = true
            val a = s.alle
            TestErgebnis(true, "OK – " + s.teile.size + " Elemente" + (if (a != null) ", ui.alle alle " + a.sekunden + " s" else ""))
        }
    }

    /** ui, app, netz, handy, log - alles, was das Skript benutzen darf (s = wohin ui.* baut, quelle = Name in der Konsole) */
    internal fun schnittstelle(ctx: android.content.Context, cx: org.mozilla.javascript.Context,
                              scope: org.mozilla.javascript.ScriptableObject, s: MenueSammler, quelle: String = "menue") {
        val haupt = Handler(Looper.getMainLooper())
        fun bau(t: MenueTeil) { if (!s.zu && s.teile.size < 2000) s.teile += t }
        val ui = JS.objekt(cx, scope, mapOf(
            "titel" to JS.fn { a -> bau(MenueTeil.Titel(JS.text(a.getOrNull(0)))); null },
            "text" to JS.fn { a -> bau(MenueTeil.Text(JS.text(a.getOrNull(0)))); null },
            "knopf" to JS.fn { a -> bau(MenueTeil.Knopf(JS.text(a.getOrNull(0)), JS.funktion(a.getOrNull(1)))); null },
            "zeile" to JS.fn { a -> bau(MenueTeil.Zeile(JS.text(a.getOrNull(0)), JS.text(a.getOrNull(1)), JS.funktion(a.getOrNull(2)))); null },
            "abstand" to JS.fn { bau(MenueTeil.Abstand); null },
            "trenner" to JS.fn { bau(MenueTeil.Trenner); null },
            "eingabe" to JS.fn { a -> bau(MenueTeil.Eingabe(JS.text(a.getOrNull(0)), JS.funktion(a.getOrNull(1)))); null },
            "schalter" to JS.fn { a ->
                bau(MenueTeil.Schalter(JS.text(a.getOrNull(0)), org.mozilla.javascript.Context.toBoolean(a.getOrNull(1)), JS.funktion(a.getOrNull(2))))
                null
            },
            "alle" to JS.fn { a ->
                val f = JS.funktion(a.getOrNull(1))
                if (!s.zu && f != null) s.alle = MenueWiederholen(JS.zahl(a.getOrNull(0), 30.0).coerceIn(5.0, 86_400.0).toInt(), f)
                null
            },
            "neu" to JS.fn { s.neu = true; null },
        ))
        val app = JS.objekt(cx, scope, mapOf(
            "oeffnen" to JS.fn { a ->
                val paket = JS.text(a.getOrNull(0))
                haupt.post {
                    val i = ctx.packageManager.getLaunchIntentForPackage(paket)
                    if (i != null) try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                    else Toast.makeText(ctx, Sprache.tr("App nicht gefunden: $paket"), Toast.LENGTH_SHORT).show()
                }
                null
            },
            "link" to JS.fn { a ->
                val url = JS.text(a.getOrNull(0))
                // nur Web-Links, keine intent:-/file:-Tricks
                if (url.startsWith("https://") || url.startsWith("http://"))
                    haupt.post { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { } }
                null
            },
            "meldung" to JS.fn { a -> val t = JS.text(a.getOrNull(0)).take(300); haupt.post { Toast.makeText(ctx, Sprache.tr(t), Toast.LENGTH_SHORT).show() }; null },
            "liste" to JS.fn {
                val l = AppListeCache.holen(ctx).map { (name, paket) ->
                    JS.objekt(cx, scope, mapOf("name" to name, "paket" to paket))
                }
                JS.liste(cx, scope, l)
            },
        ))
        val netz = JS.objekt(cx, scope, mapOf(
            "holen" to JS.fn { a -> holen(JS.text(a.getOrNull(0))) },
        ))
        val handy = JS.objekt(cx, scope, mapOf(
            "akku" to JS.fn {
                val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
                bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            },
            "zeit" to JS.fn { System.currentTimeMillis().toDouble() },
            "nachrichten" to JS.fn { a ->
                val paket = JS.text(a.getOrNull(0))
                val alle = try { Lauscher.verbunden?.activeNotifications } catch (e: Exception) { null }
                val l = alle.orEmpty().filter { it.packageName == paket && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 }
                    .map { sbn ->
                        val ex = sbn.notification.extras
                        JS.objekt(cx, scope, mapOf(
                            "titel" to (ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() ?: ""),
                            "text" to (ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString() ?: ""),
                            "zeit" to sbn.postTime.toDouble(),
                        ))
                    }
                JS.liste(cx, scope, l)
            },
            "speichern" to JS.fn { a -> SkriptSpeicher.schreiben(ctx, JS.text(a.getOrNull(0)), JS.text(a.getOrNull(1))); null },
            "laden" to JS.fn { a -> SkriptSpeicher.lesen(ctx, JS.text(a.getOrNull(0))) },
            "loeschen" to JS.fn { a -> SkriptSpeicher.loeschen(ctx, JS.text(a.getOrNull(0))); null },
        ))
        scope.put("ui", scope, ui)
        scope.put("app", scope, app)
        scope.put("netz", scope, netz)
        scope.put("handy", scope, handy)
        JS.global(scope, "log", Konsole.jsLog(quelle))
    }

    // ---------------------------------------------------------------- Netz (abgesichert)
    private val abrufZeiten = java.util.ArrayDeque<Long>()
    private val waechterNetz = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "netz-waechter").apply { isDaemon = true } }

    /**
     * Webseite/API holen. Grenzen: nur https, nicht ins eigene Netz (Router, PC ...),
     * hoechstens 1 MB, insgesamt hoechstens 10 s (und nie laenger als das Skript noch darf),
     * hoechstens 30 Abrufe pro Minute. Bei Fehlern: "".
     */
    fun holen(url: String): String {
        val rest = JS.restZeit()
        synchronized(abrufZeiten) {
            val jetzt = System.currentTimeMillis()
            while (abrufZeiten.isNotEmpty() && jetzt - abrufZeiten.first() > 60_000) abrufZeiten.removeFirst()
            if (abrufZeiten.size >= 30) throw Error("netz.holen: höchstens 30 Abrufe pro Minute")
            abrufZeiten.addLast(jetzt)
        }
        val gesamt = minOf(10_000L, rest)
        val ende = System.currentTimeMillis() + gesamt
        var adresse = url
        repeat(4) {                                   // bis zu 3 Weiterleitungen, jede wird geprueft
            if (!adresse.startsWith("https://")) return ""
            val u = try { URL(adresse) } catch (e: Exception) { return "" }
            if (!oeffentlich(u.host)) return ""
            val uebrig = (ende - System.currentTimeMillis()).toInt()
            if (uebrig <= 0) return ""
            val v = try { u.openConnection() as HttpURLConnection } catch (e: Exception) { return "" }
            // harte Gesamtgrenze: nach Ablauf Verbindung kappen (unterbricht auch langsames Tröpfeln)
            val kappen = waechterNetz.schedule({ try { v.disconnect() } catch (e: Exception) { } }, uebrig.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            try {
                v.instanceFollowRedirects = false
                v.connectTimeout = uebrig; v.readTimeout = uebrig
                v.setRequestProperty("User-Agent", "Launcher")
                val code = v.responseCode
                if (code in 300..399) {
                    val ziel = v.getHeaderField("Location") ?: return ""
                    adresse = try { URL(u, ziel).toString() } catch (e: Exception) { return "" }
                    return@repeat
                }
                val strom = if (code >= 400) v.errorStream ?: return "" else v.inputStream
                return strom.use { String(it.readNBytesSicher(1_000_000), Charsets.UTF_8) }
            } catch (e: Exception) {
                return ""
            } finally {
                kappen.cancel(false)
                try { v.disconnect() } catch (e: Exception) { }
            }
        }
        return ""
    }

    /** nur Adressen im Internet - nicht localhost, Router, Heimnetz */
    private fun oeffentlich(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val adressen = try { java.net.InetAddress.getAllByName(host) } catch (e: Exception) { return false }
        return adressen.isNotEmpty() && adressen.none { a ->
            a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress || a.isMulticastAddress ||
                (a is java.net.Inet6Address && (a.address[0].toInt() and 0xFE) == 0xFC)
        }
    }

    private fun java.io.InputStream.readNBytesSicher(max: Int): ByteArray {
        val aus = java.io.ByteArrayOutputStream()
        val puffer = ByteArray(8192)
        while (aus.size() < max) {
            val n = read(puffer, 0, minOf(puffer.size, max - aus.size()))
            if (n < 0) break
            aus.write(puffer, 0, n)
        }
        return aus.toByteArray()
    }
}

/** Zeitgrenzen fuer Skripte: reine Rechenzeit + Faden-Notbremse */
const val SKRIPT_ZEIT = 15_000L
const val SKRIPT_HOECHST = 20_000L

/** app.liste() ist teuer - 1 Minute zwischenspeichern */
object AppListeCache {
    @Volatile private var stand = 0L
    @Volatile private var liste: List<Pair<String, String>> = emptyList()
    fun holen(ctx: android.content.Context): List<Pair<String, String>> {
        if (System.currentTimeMillis() - stand < 60_000 && liste.isNotEmpty()) return liste
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val l = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { ri -> ri.loadLabel(pm).toString() to ri.activityInfo.packageName }
            .sortedBy { it.first.lowercase() }
        liste = l; stand = System.currentTimeMillis()
        return l
    }
}

@Composable
fun MeinMenueSeite(modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    LaunchedEffect(Unit) { MeinMenue.aufbauen(ctx) }
    // ui.alle(sekunden, fn): solange das Menue zu sehen ist (neu gebaut -> neuer Takt)
    val alle = MeinMenue.alle
    LaunchedEffect(alle) {
        val w = alle ?: return@LaunchedEffect
        while (true) {
            delay(w.sekunden * 1000L)
            // nur wenn der Launcher vorne ist (nicht hinter einer anderen App)
            val akt = ctx as? android.app.Activity
            if (akt == null || akt.hasWindowFocus()) MeinMenue.ausfuehren(ctx, w.aktion)
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp, bottom = 80.dp)) {
            val teile = MeinMenue.teile
            if (teile.isEmpty() && !MeinMenue.laeuft) {
                Text("Leer – das Menü baust du selbst: Einstellungen → Mein Menü", color = p.leise, fontSize = 15.sp)
            }
            teile.forEach { t ->
                when (t) {
                    is MenueTeil.Titel -> Text(t.text, color = p.leise, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                               style = schattenStil(), modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
                    is MenueTeil.Text -> Text(t.text, color = p.text, fontSize = 17.sp, style = schattenStil(),
                                              modifier = Modifier.padding(vertical = 6.dp))
                    is MenueTeil.Knopf -> Text(
                        t.text, color = p.aufAkzent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 6.dp).clip(rund(16.dp)).background(p.akzent)
                            .clickable { t.aktion?.let { MeinMenue.ausfuehren(ctx, it) } }
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                    is MenueTeil.Zeile -> Column(
                        Modifier.fillMaxWidth().clickable(enabled = t.aktion != null) { t.aktion?.let { MeinMenue.ausfuehren(ctx, it) } }
                            .padding(vertical = 10.dp),
                    ) {
                        Text(t.titel, color = p.text, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = schattenStil())
                        if (t.unter.isNotBlank()) Text(t.unter, color = p.leise, fontSize = 14.sp, maxLines = 2,
                                                       overflow = TextOverflow.Ellipsis, style = schattenStil())
                    }
                    MenueTeil.Abstand -> Spacer(Modifier.height(16.dp))
                    MenueTeil.Trenner -> Box(Modifier.padding(vertical = 10.dp).fillMaxWidth().height(1.dp).background(p.text.copy(alpha = 0.25f)))
                    is MenueTeil.Eingabe -> MenueEingabe(t)
                    is MenueTeil.Schalter -> MenueSchalter(t)
                    is MenueTeil.Fehler -> Text("Fehler im Code – " + t.text, color = p.warnung, fontSize = 14.sp,
                                                modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}

/** ui.eingabe: Textfeld + OK; OK (oder Fertig auf der Tastatur) ruft die Funktion mit dem Text auf */
@Composable
private fun MenueEingabe(t: MenueTeil.Eingabe) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var wert by remember(t) { mutableStateOf("") }
    fun absenden() {
        val f = t.aktion ?: return
        MeinMenue.ausfuehren(ctx, f, arrayOf<Any?>(wert))
        wert = ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).clip(rund(16.dp)).background(p.karte).padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            if (wert.isEmpty()) Text(t.hinweis, color = p.leise, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = wert,
                onValueChange = { wert = it.take(2_000) },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 16.sp),
                cursorBrush = SolidColor(p.akzent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { absenden() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "OK", color = p.aufAkzent, fontSize = 16.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.clip(rund(16.dp)).background(p.akzent)
                .clickable { absenden() }.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

/** ui.schalter: Material-Schalter; Aenderung ruft die Funktion mit true/false auf */
@Composable
private fun MenueSchalter(t: MenueTeil.Schalter) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    var an by remember(t) { mutableStateOf(t.an) }
    fun wechseln(neu: Boolean) {
        an = neu
        t.aktion?.let { MeinMenue.ausfuehren(ctx, it, arrayOf<Any?>(neu)) }
    }
    Row(
        Modifier.fillMaxWidth().clickable { wechseln(!an) }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.text, color = p.text, fontSize = 17.sp, style = schattenStil(), modifier = Modifier.weight(1f))
        Switch(
            checked = an, onCheckedChange = { wechseln(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = p.akzent, checkedThumbColor = p.aufAkzent),
        )
    }
}

// ============================================================================
//  alphabet.js: eigene Aktionen nach einem Wisch uebers Alphabet (function wisch(w))
// ============================================================================
object AlphabetSkript {
    @Volatile private var kontext: android.content.Context? = null
    private val lauf = SkriptLauf("alphabet").apply {
        beiHaenger = { t ->
            geladenVon = null
            Konsole.schreib("alphabet", "Fehler: $t")
            kontext?.let { c -> Handler(Looper.getMainLooper()).post { Toast.makeText(c, Sprache.tr("alphabet.js – $t"), Toast.LENGTH_LONG).show() } }
        }
    }
    @Volatile private var geladenVon: String? = null      // zuletzt ausgefuehrter Code
    @Volatile var neuLesen = true                         // beim naechsten Wisch Datei neu lesen

    fun code(ctx: android.content.Context): String =
        BastelOrdner.lesen(ctx, "alphabet.js")
            ?: ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("alphabetCode", null)
            ?: ""

    fun codeSpeichern(ctx: android.content.Context, code: String, fertig: (Boolean) -> Unit = {}) {
        ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit().putString("alphabetCode", code).apply()
        lauf.freigeben()
        neuLesen = true
        if (BastelOrdner.gesetzt(ctx)) BastelOrdner.speichern(ctx, "alphabet.js", code) { ok -> neuLesen = true; fertig(ok) }
        else fertig(true)
    }

    fun wisch(ctx: android.content.Context, start: String, ende: String, links: Boolean) {
        val app = ctx.applicationContext
        kontext = app
        lauf.spaeter(SKRIPT_HOECHST) {
            try {
                if (neuLesen || geladenVon == null) {
                    val quelle = code(app)
                    neuLesen = false
                    if (quelle != geladenVon) {
                        geladenVon = quelle
                        lauf.zuruecksetzenJetzt()
                        lauf.mitKontext(SKRIPT_ZEIT) { cx, scope ->
                            MeinMenue.schnittstelle(app, cx, scope, MenueSammler().apply { zu = true }, "alphabet")
                            cx.evaluateString(scope, quelle, "alphabet.js", 1, null)
                        }
                    }
                }
                if (geladenVon.isNullOrBlank()) return@spaeter
                lauf.mitKontext(SKRIPT_ZEIT) { cx, scope ->
                    val f = scope.get("wisch", scope) as? Function
                    if (f != null) {
                        val w = JS.objekt(cx, scope, mapOf("start" to start, "ende" to ende, "links" to links))
                        f.call(cx, scope, scope, arrayOf<Any?>(w))
                    }
                    Unit
                }
            } catch (e: Throwable) {
                val t = JS.fehler(e)
                Konsole.schreib("alphabet", "Fehler: $t")
                Handler(Looper.getMainLooper()).post { Toast.makeText(app, Sprache.tr("alphabet.js – $t"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Code testweise ausfuehren und pruefen, ob function wisch(w) da ist. Blockiert - nicht auf dem Bildschirm-Faden! */
    fun testen(ctx: android.content.Context, code: String): TestErgebnis {
        if (code.isBlank()) return TestErgebnis(true, "Leer – beim Wischen passiert nichts Eigenes")
        val app = ctx.applicationContext
        return JS.testLauf(SKRIPT_ZEIT) { cx, scope ->
            MeinMenue.schnittstelle(app, cx, scope, MenueSammler().apply { zu = true }, "test")
            cx.evaluateString(scope, code, "alphabet.js", 1, null)
            if (scope.get("wisch", scope) is Function) TestErgebnis(true, "OK – function wisch(w) gefunden")
            else TestErgebnis(false, "Es fehlt: function wisch(w) { ... }")
        }
    }
}
