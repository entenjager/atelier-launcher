package de.atelierlauncher.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.runtime.LaunchedEffect

// ============================================================================
// ============================================================================
data class Baustein(val id: String, val typ: String, val x: Int, val y: Int, val b: Int, val h: Int,
                    val wert: String = "", val titel: String = "",
                    /** Eigenschaften (Schrift, Hintergrund, ...) als JSON - leer = alles wie im Design */
                    val opt: String = "")

object SeitenRaster {
    /** 20 Felder pro Zeile (22.10.: Raster halb so gross), frueher 10, davor 5 bzw. 7 */
    const val ZELLEN = 20
    /**
     * 23.10.: Lagen und Groessen zaehlen in LINIEN statt in Feldern - ein Baustein rastet an jeder Rasterlinie ein.
     * Linie k: gerade = Anfang eines Feldes (k/2 * sp), ungerade = Ende eines Feldes (vor der Luecke).
     * 20 Felder = Linien 0..39 -> SPALTEN = 39 Schritte. Ein Baustein reicht von Linie x bis Linie x + b.
     */
    val SPALTEN get() = ZELLEN * 2 - 1
    /** fruehere Feld-Einheiten -> Linien (gleiche Lage, gleiche Groesse) */
    fun zuLinien(b: Baustein) = b.copy(x = b.x * 2, y = b.y * 2, b = b.b * 2 - 1, h = b.h * 2 - 1)
    /** Groesse in alten (doppelt so grossen) Einheiten - damit rechnen die Inhalte (Schrift, Form ...) */
    fun alteEinheit(n: Int) = ((n + 1) / 2 + 1) / FEIN
    /** Standardgroesse aus dem "+"-Menue (alte Einheiten) -> Linien */
    fun neueGroesse(n: Int) = n * FEIN * 2 - 1
    /** wo Linie k liegt (sp = Feld + Luecke) */
    fun lage(k: Int, sp: Dp): Dp = sp * ((k + 1) / 2) - (if (k % 2 == 1) LUECKE else 0.dp)
    fun lagePx(k: Int, sp: Float, luecke: Float): Float = sp * ((k + 1) / 2) - (if (k % 2 == 1) luecke else 0f)
    /** 22.10.: Felder halb so gross - gespeicherte Anordnungen und Standardgroessen werden einmal verdoppelt */
    const val FEIN = 2
    /** Raster-Version: 2 = quadratische Felder, 5 Spalten */
    private const val VERSION = 4          // Seite oben: 2 = 5 Spalten, 3 = 7, 4 = 10
    private const val VERSION_START = 5    // Startbildschirm: 3 = 5 Spalten, 4 = 7, 5 = 10
    var stand by mutableIntStateOf(0)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Baustein>>()
    private fun schluessel(seite: String) = when (seite) { "start" -> "rasterHaupt"; "sperre" -> "rasterSperrSeite"; else -> "raster" }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Bausteine, die es in dieser Version gibt (Name, Standardgroesse b x h, nur einmal?) */
    data class Art(val typ: String, val name: String, val b: Int, val h: Int, val einmal: Boolean)
    val ARTEN: List<Art> get() = arten("oben")
    fun arten(seite: String): List<Art> = buildList {
        if (seite == "sperre") {
            add(Art("termine", "Termine", 10, 6, true))
            add(Art("whatsapp", "WhatsApp", 3, 2, true)); add(Art("signal", "Signal", 3, 2, true))
            add(Art("nachrichten", "Benachrichtigungen", 4, 2, false))
            add(Art("musik", "Musik", 10, 3, true)); add(Art("rahmen", "Rahmen", 4, 3, false))
            add(Art("uhrklein", "Uhr", 4, 2, false))          // hier gibt es die grosse Uhr nicht - gleiche Regeln (fuellt den Baustein)
            addAll(EXTRA)
            return@buildList
        }
        if (seite == "start") {
            // Startbildschirm (nur Atelier): die Favoriten sind selbst ein Baustein
            add(Art("uhr", "Uhr", 10, 6, true))                  // die normale Uhr oben - jetzt auch ein Baustein
            // weitere Uhren (die erste ist immer die normale): gleiche Regeln - fuellt den Baustein ganz aus
            add(Art("uhrklein", "Uhr", 4, 2, false))
            add(Art("favoriten", "App-Liste (Favoriten & Buchstaben)", 10, 12, true))
            add(Art("suche", "Suchen", 10, 2, true))
            add(Art("rahmen", "Rahmen", 4, 3, false))      // gemeinsame Linie um mehrere Bausteine           // oeffnet die Suche (Wischen gibt es auf dem Startbildschirm nicht mehr)
            add(Art("app", "App-Knopf", 2, 2, false))
            // unsichtbarer Knopf (gehoert zum Alphabet-Code)
            add(Art("geheim", "Unsichtbarer Knopf", 2, 2, true))
            add(Art("nachrichten", "Benachrichtigungen", 4, 2, false))
            add(Art("musik", "Musik", 10, 4, true)); add(Art("termine", "Termine", 10, 6, true))
            addAll(EXTRA)
            return@buildList
        }
                    // ein Baustein fuer alle Apps: nach dem Hinzufuegen gleich die App waehlen (WhatsApp, Signal, Telegram ...)
            add(Art("nachrichten", "Benachrichtigungen", 4, 2, false))
                    add(Art("termine", "Termine", 10, 6, true)); add(Art("musik", "Musik", 10, 4, true)); add(Art("app", "App-Knopf", 2, 2, false))
            add(Art("rahmen", "Rahmen", 4, 3, false))
            addAll(EXTRA)
    }
    /** Aktions-Knoepfe und Anzeigen (Atelier, auf beiden Seiten) */
    private val EXTRA = listOf(
        Art("lampe", "Taschenlampe", 1, 1, false), Art("ruhe", "Nicht stören", 1, 1, false),
        Art("ton", "Ton / Vibration / Lautlos", 1, 1, false), Art("webseite", "Webseite", 4, 2, false),
        Art("kamera", "Kamera", 1, 1, false), Art("wlan", "WLAN", 1, 1, false), Art("bluetooth", "Bluetooth", 1, 1, false),
        Art("akku", "Akku", 4, 4, false),
        Art("wlanstand", "WLAN-Anzeige", 1, 1, false), Art("mobil", "Mobile Daten", 1, 1, false),
        Art("netz", "WLAN + mobile Daten", 1, 1, false), Art("akkupille", "Akku (Samsung)", 1, 1, false),
        Art("termin", "Nächster Termin", 6, 2, false), Art("notiz", "Notiz", 6, 3, false),
    )
    /** fruehere Atelier-Bausteine, die es nicht mehr im "+"-Menue gibt, aber weiter funktionieren */
    private val FRUEHER = listOf(Art("whatsapp", "WhatsApp", 4, 2, true), Art("signal", "Signal", 4, 2, true),
                                 // das Alphabet ist immer da (nicht hinzufuegbar, nicht entfernbar) - nur Platz und Groesse
                                 Art("alphabet", "Alphabet", 1, 16, true),
                                 // eigene Bausteine (Code) - kommen ueber "Eigene Bausteine" im "+"-Menue
                                 Art("eigen", "Eigener Baustein", 4, 2, false),
                                 // kleine Uhr (22.10.) - nur noch auf der Sperr-Seite im "+"-Menue; vorhandene bleiben
                                 Art("uhrklein", "Uhr", 2, 1, false))
    /** steht diese Art im "+"-Menue? Nur-einmal-Bausteine, die schon liegen, nicht. Uhr: immer genau ein Eintrag
     *  (noch keine Uhr da = die normale Uhr, sonst eine weitere) */
    fun imMenue(a: Art, liste: List<Baustein>, seite: String): Boolean {
        if (a.einmal && liste.any { it.typ == a.typ }) return false
        if (a.typ == "uhrklein" && seite == "start" && liste.none { it.typ == "uhr" }) return false
        return true
    }
    fun art(typ: String) = (arten("oben") + arten("start") + arten("sperre") + FRUEHER).firstOrNull { it.typ == typ }

    fun standard(seite: String = "oben"): List<Baustein> = (if (seite == "sperre") emptyList<Baustein>() else if (seite == "start") listOf(
        Baustein("uhr", "uhr", 0, 0, 9, 6),                      // Startbildschirm: Uhr, Favoriten, Alphabet rechts - wie bisher
        Baustein("favoriten", "favoriten", 0, 6, 9, 12),
        Baustein("alphabet", "alphabet", 9, 4, 1, 16),
    ) else emptyList()).map { zuLinien(it.copy(x = it.x * FEIN, y = it.y * FEIN, b = it.b * FEIN, h = it.h * FEIN)) }   // oeffentlich: leer

    fun laden(ctx: Context, seite: String = "oben"): List<Baustein> {
        @Suppress("UNUSED_VARIABLE") val s = stand
        cache[seite]?.let { return it }
        val l = try {
            val roh = prefs(ctx).getString(schluessel(seite), null)
            if (roh == null) { prefs(ctx).edit().putBoolean("rasterFein_$seite", true).putBoolean("rasterLinien_$seite", true).apply(); standard(seite) } else {
                val a = JSONArray(roh)
                (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                    Baustein(o.getString("i"), o.getString("t"), o.getInt("x"), o.getInt("y"), o.getInt("b"), o.getInt("h"),
                             o.optString("w"), o.optString("n"), o.optString("o"))
                } }.filter { art(it.typ) != null }.let { when (seite) {
                    "sperre" -> it                                    // neue Seite - nichts umzurechnen
                    "oben" -> feiner(ctx, seite, umrechnen(ctx, it))
                    else -> feiner(ctx, seite, alphabetDazu(ctx, uhrDazu(ctx, it)))
                } }.let { halbeFelder(ctx, seite, it) }.let { aufLinien(ctx, seite, it) }
            }
        } catch (e: Exception) { standard(seite) }
        cache[seite] = l
        return l
    }

    /**
     * 22.10.: Raster halb so gross (20 statt 10 Spalten). Gespeicherte Anordnung einmal verdoppeln -
     * alles bleibt genau gleich gross und an derselben Stelle, man kann nur feiner schieben und ziehen.
     */
    private fun halbeFelder(ctx: Context, seite: String, alt: List<Baustein>): List<Baustein> {
        val schl = "rasterFein_$seite"
        if (prefs(ctx).getBoolean(schl, false)) return alt
        val neu = alt.map { it.copy(x = it.x * FEIN, y = it.y * FEIN, b = it.b * FEIN, h = it.h * FEIN) }
        // Sicherheitskopie der alten Anordnung (falls etwas schiefgeht)
        val e = prefs(ctx).edit()
        prefs(ctx).getString(schluessel(seite), null)?.let { e.putString(schluessel(seite) + "_vorFein", it) }
        e.putString(schluessel(seite), json(neu)).putBoolean(schl, true).apply()
        return neu
    }

    /** 23.10.: einmal von Feldern auf Linien umrechnen - alles bleibt genau gleich gross und an derselben Stelle */
    private fun aufLinien(ctx: Context, seite: String, alt: List<Baustein>): List<Baustein> {
        val schl = "rasterLinien_$seite"
        if (prefs(ctx).getBoolean(schl, false)) return alt
        val neu = alt.map { zuLinien(it) }
        val e = prefs(ctx).edit()
        prefs(ctx).getString(schluessel(seite), null)?.let { e.putString(schluessel(seite) + "_vorLinien", it) }
        e.putString(schluessel(seite), json(neu)).putBoolean(schl, true).apply()
        return neu
    }

    /** gespeicherte Anordnung (5 oder 7 Spalten) einmal auf 10 Spalten umrechnen - alles bleibt ungefaehr gleich gross */
    private fun feiner(ctx: Context, seite: String, alt: List<Baustein>): List<Baustein> {
        val ALT_SPALTEN = 10                                    // damals 10 Spalten (danach: halbeFelder)
        val schl = if (seite == "start") "rasterHauptV" else "rasterV"
        val ziel = if (seite == "start") VERSION_START else VERSION
        val v = prefs(ctx).getInt(schl, 1)
        if (v >= ziel) return alt
        val von = if (v >= ziel - 1) 7f else 5f                 // vorher 7 oder 5 Spalten
        val f = 10f / von
        fun m(x: Int) = kotlin.math.round(x * f).toInt()
        var neu = emptyList<Baustein>()
        // Alphabet zuerst, damit es am rechten Rand bleibt
        alt.sortedWith(compareBy({ it.typ != "alphabet" }, { it.y }, { it.x })).forEach { b ->
            val bb = maxOf(1, minOf(ALT_SPALTEN, m(b.b)))
            val amRand = b.x + b.b >= von.toInt()
            var k = b.copy(x = if (amRand) ALT_SPALTEN - bb else minOf(m(b.x), ALT_SPALTEN - bb), y = m(b.y), b = bb, h = maxOf(1, m(b.h)))
            if (!passtFelder(neu, k)) k = freierPlatzFelder(neu, k)
            neu = neu + k
        }
        prefs(ctx).edit().putString(schluessel(seite), json(neu)).putInt(schl, ziel).apply()
        return neu
    }

    /** Startbildschirm von vorher: das Alphabet wird ein Baustein in der rechten Spalte, was dort lag, wird schmaler */
    private fun alphabetDazu(ctx: Context, alt: List<Baustein>): List<Baustein> {
        if (prefs(ctx).getInt("rasterHauptV", 1) >= 3 || alt.any { it.typ == "alphabet" }) return alt
        val abc = Baustein("alphabet", "alphabet", 4, 2, 1, 8)         // noch im alten 5er-Raster
        var neu = listOf(abc)
        alt.sortedWith(compareBy({ it.y }, { it.x })).forEach { b ->
            var k = b
            if (!passtFelder(neu, k) && k.x < 4) k = k.copy(b = minOf(k.b, 4 - k.x))
            if (!passtFelder(neu, k)) k = freierPlatzFelder(neu, k.copy(b = minOf(k.b, 4)))
            neu = neu + k
        }
        prefs(ctx).edit().putString(schluessel("start"), json(neu)).putInt("rasterHauptV", 3).apply()
        return neu
    }

    /** Startbildschirm von vorher: die feste Uhr wird einmal zum Baustein ganz oben (alles andere rutscht 3 Felder runter) */
    private fun uhrDazu(ctx: Context, alt: List<Baustein>): List<Baustein> {
        if (prefs(ctx).getInt("rasterHauptV", 1) >= 2) return alt
        val ersteUhr = alt.firstOrNull { it.typ == "uhr" }
        val neu = if (ersteUhr != null) alt.filter { it.typ != "uhr" || it.id == ersteUhr.id }
                  else listOf(Baustein("uhr", "uhr", 0, 0, 5, 3)) + alt.map { it.copy(y = it.y + 3) }
        prefs(ctx).edit().putString(schluessel("start"), json(neu)).putInt("rasterHauptV", 2).apply()
        return neu
    }

    /** alte Anordnung (4 Spalten, flache Zeilen) einmal auf quadratische Felder umrechnen - nichts geht verloren */
    private fun umrechnen(ctx: Context, alt: List<Baustein>): List<Baustein> {
        if (prefs(ctx).getInt("rasterV", 1) >= 2) return alt
        var neu = emptyList<Baustein>()
        alt.sortedWith(compareBy({ it.y }, { it.x })).forEach { b ->
            val bb = minOf(5, (b.b * 5 + 2) / 4)
            val k = b.copy(x = minOf((b.x * 5 + 2) / 4, 5 - bb), y = b.y * 2 / 3, b = bb, h = maxOf(1, (b.h * 2 + 1) / 3))
            neu = neu + if (passtFelder(neu, k)) k else freierPlatzFelder(neu, k)
        }
        // still speichern (kein stand++ - wir sind gerade beim Lesen)
        prefs(ctx).edit().putString("raster", json(neu)).putInt("rasterV", 2).apply()
        return neu
    }

    private fun json(l: List<Baustein>): String {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("i", it.id).put("t", it.typ).put("x", it.x).put("y", it.y).put("b", it.b).put("h", it.h)
                              .put("w", it.wert).put("n", it.titel).put("o", it.opt)) }
        return a.toString()
    }

    fun speichern(ctx: Context, l: List<Baustein>, seite: String = "oben") {
        val e = prefs(ctx).edit().putString(schluessel(seite), json(l)).putBoolean("rasterFein_$seite", true).putBoolean("rasterLinien_$seite", true)
        if (seite == "oben") e.putInt("rasterV", VERSION) else if (seite == "start") e.putInt("rasterHauptV", VERSION_START)
        e.apply()
        cache[seite] = l; stand++
    }

    fun zuruecksetzen(ctx: Context, seite: String = "oben") { prefs(ctx).edit().remove(schluessel(seite)).apply(); cache.remove(seite); stand++ }

    /** wo die Seite erscheint: beim Wischen auf dem Startbildschirm und/oder auf dem Sperrbildschirm */
    fun aufStart(ctx: Context) = prefs(ctx).getBoolean("rasterStart", true)
    fun aufSperre(ctx: Context) = prefs(ctx).getBoolean("rasterSperre", true)
    fun ortSetzen(ctx: Context, start: Boolean? = null, sperre: Boolean? = null) {
        val e = prefs(ctx).edit()
        start?.let { e.putBoolean("rasterStart", it) }; sperre?.let { e.putBoolean("rasterSperre", it) }
        e.apply(); stand++
    }

    // ---------------------------------------------------------------- Raster-Rechnerei
    /** passt der Baustein (in Linien)? Beruehren ist erlaubt, ueberlappen nicht. Nie nur so schmal wie eine Luecke. */
    fun passt(l: List<Baustein>, k: Baustein): Boolean {
        if (k.x < 0 || k.y < 0 || k.b < 1 || k.h < 1 || k.x + k.b > SPALTEN || k.y + k.h > 240) return false
        if ((k.b == 1 && k.x % 2 == 1) || (k.h == 1 && k.y % 2 == 1)) return false
        if (k.typ == "rahmen") return true
        return l.none { o -> o.id != k.id && o.typ != "rahmen" && k.x < o.x + o.b && o.x < k.x + k.b && k.y < o.y + o.h && o.y < k.y + k.h }
    }

    /** erster freier Platz fuer einen neuen Baustein (von oben links) - zuerst an Feld-Anfaengen (mit der gewohnten Luecke) */
    fun freierPlatz(l: List<Baustein>, neu: Baustein): Baustein {
        for (schritt in intArrayOf(2, 1)) for (y in 0 until 240 step schritt) for (x in 0..(SPALTEN - neu.b) step schritt) {
            val k = neu.copy(x = x, y = y)
            if (passt(l, k)) return k
        }
        return neu.copy(x = 0, y = ((l.maxOfOrNull { it.y + it.h } ?: 0) + 1) / 2 * 2)
    }

    /** nur fuer die alten Umrechnungen (noch in Feldern, 20 Spalten) - wie frueher */
    private fun passtFelder(l: List<Baustein>, k: Baustein): Boolean {
        if (k.x < 0 || k.y < 0 || k.b < 1 || k.h < 1 || k.x + k.b > ZELLEN || k.y + k.h > 120) return false
        if (k.typ == "rahmen") return true
        return l.none { o -> o.id != k.id && o.typ != "rahmen" && k.x < o.x + o.b && o.x < k.x + k.b && k.y < o.y + o.h && o.y < k.y + k.h }
    }
    private fun freierPlatzFelder(l: List<Baustein>, neu: Baustein): Baustein {
        for (y in 0 until 120) for (x in 0..(ZELLEN - neu.b)) {
            val k = neu.copy(x = x, y = y)
            if (passtFelder(l, k)) return k
        }
        return neu.copy(x = 0, y = (l.maxOfOrNull { it.y + it.h } ?: 0))
    }

    fun zeilen(l: List<Baustein>) = l.maxOfOrNull { it.y + it.h } ?: 0

    /** liegt ein Baustein dieser Art auf der Seite? (der Lauscher sammelt nur dann Nachrichten) */
    fun hat(ctx: Context, typ: String) = beide(ctx).any { it.typ == typ }
    private fun beide(ctx: Context) = laden(ctx) + laden(ctx, "start")
    /** Apps, deren Nachrichten auf der Seite liegen */
    fun pakete(ctx: Context): Set<String> = beide(ctx).filter { it.typ == "nachrichten" && it.wert.isNotBlank() }.map { it.wert }.toSet()
}

/** Nachrichten einer beliebigen App (nur fuer Apps, die als Baustein auf der Seite liegen; nur im Speicher) */
object AppPost {
    private val faecher = HashMap<String, Postfach>()
    fun fuer(paket: String): Postfach = synchronized(faecher) { faecher.getOrPut(paket) { Postfach { _, p -> p == paket } } }
    fun alleLesen(ctx: Context, alle: Array<android.service.notification.StatusBarNotification>?) {
        for (p in SeitenRaster.pakete(ctx)) fuer(p).alleLesen(ctx, alle)
    }
    fun neu(ctx: Context, sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName in SeitenRaster.pakete(ctx)) fuer(sbn.packageName).neu(ctx, sbn)
    }
    fun weg(key: String) { synchronized(faecher) { faecher.values.toList() }.forEach { it.weg(key) } }
}

/** Suche oeffnen - der Startbildschirm reicht das an den Such-Baustein weiter */
internal val LocalSucheOeffnen = androidx.compose.runtime.staticCompositionLocalOf<() -> Unit> { {} }

/** Zeilenhoehe des Rasters (Sperrbildschirm etwas enger) */
@Composable
private fun zeilenHoehe(): Dp = if (LocalKompakt.current) 42.dp else 48.dp
private val LUECKE = 8.dp
/** Atelier-Startbildschirm: Rand links/rechts und Abstand von oben bis zum Raster (36 dp Platz + 6 dp) */
internal val START_RAND = 16.dp
internal val START_OBEN get() = 6.dp

/**
 * Wo das Alphabet auf dem Atelier-Startbildschirm liegt - aus seinem Baustein berechnet
 * (fest, scrollt nicht mit). Im Liegen (gespiegelt) sorgt die Rechts-nach-links-Richtung fuer die Spiegelung.
 */
@Composable
internal fun Modifier.alphabetPlatz(): Modifier {
    val ctx = LocalContext.current
    val breite = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val b = SeitenRaster.laden(ctx, "start").firstOrNull { it.typ == "alphabet" } ?: Baustein("alphabet", "alphabet", SeitenRaster.SPALTEN - 3, 16, 3, 63)
    val sp = (breite - START_RAND * 2 + LUECKE) / SeitenRaster.ZELLEN
    fun lg(k: Int) = SeitenRaster.lage(k, sp)
    val (rs, re) = alphabetAussen()
    return this.leistenAbstand()
        .padding(start = START_RAND + lg(b.x) - rs, top = START_OBEN + lg(b.y))
        .size(lg(b.x + b.b) - lg(b.x) + rs + re, lg(b.y + b.h) - lg(b.y))
}

/**
 * Liegt der Alphabet-Baustein ganz aussen im Raster, reicht seine Beruehr-Flaeche bis an den Bildschirmrand
 * (sonst war der Streifen zwischen Raster und Rand tot - genau da, wo der Daumen gern hinlangt).
 * Ergebnis: so viel kommt am Anfang / Ende dazu. Die Buchstaben bleiben, wo der Baustein ist.
 */
@Composable
internal fun alphabetAussen(): Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp> {
    val ctx = LocalContext.current
    val breite = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val b = SeitenRaster.laden(ctx, "start").firstOrNull { it.typ == "alphabet" } ?: Baustein("alphabet", "alphabet", SeitenRaster.SPALTEN - 3, 16, 3, 63)
    val sp = (breite - START_RAND * 2 + LUECKE) / SeitenRaster.ZELLEN
    val innen = breite - START_RAND * 2
    val anfang = if (b.x <= 0) START_RAND else 0.dp
    val ende = if (SeitenRaster.lage(b.x + b.b, sp) >= innen - 1.dp) START_RAND else 0.dp
    return anfang to ende
}

@Composable
fun BausteinRaster(liste: List<Baustein>, jetzt: Long, offen: String?,
                   leerText: String = "Diese Seite ist noch leer.\nEinstellungen → Seite bei # → „+ Baustein“",
                   favoriten: (@Composable () -> Unit)? = null, spiegeln: Boolean = false, onOffen: (String) -> Unit) {
    val dichte = LocalDensity.current
    if (liste.isEmpty()) {
        // noch nichts angeordnet: kurzer Hinweis, wo man die Seite gestaltet
        val p = LocalPalette.current
        Text(leerText,
             color = p.leise, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
             modifier = Modifier.fillMaxWidth().padding(top = 40.dp))
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        val sp = (maxWidth + LUECKE) / SeitenRaster.ZELLEN
        fun lg(k: Int) = SeitenRaster.lage(k, sp)          // Lage einer Rasterlinie (Lagen/Groessen zaehlen in Linien)
        // das Alphabet liegt fest obendrueber - es macht die Seite nicht laenger (sonst koennte man ins Leere scrollen)
        Box(Modifier.fillMaxWidth().height(lg(SeitenRaster.zeilen(liste.filter { it.typ != "alphabet" })))) {
            // Rahmen zuerst (hinter den Bausteinen): feste Flaeche, die man selbst gross zieht
            liste.filter { it.typ == "rahmen" }.forEach { r0 ->
                val r = if (spiegeln) r0.copy(x = SeitenRaster.SPALTEN - r0.x - r0.b) else r0
                Box(Modifier.offset { with(dichte) { IntOffset(lg(r.x).roundToPx(), lg(r.y).roundToPx()) } }
                        .size(lg(r.x + r.b) - lg(r.x), lg(r.y + r.h) - lg(r.y))) {
                    // liegt ein Musik-Baustein im Rahmen? Dann leuchtet dort das Cover (Mitte als Anteil des Rahmens)
                    val m0 = liste.firstOrNull { it.typ == "musik" && it.x >= r0.x && it.x + it.b <= r0.x + r0.b && it.y >= r0.y && it.y + it.h <= r0.y + r0.h }
                    val mitte = m0?.let { m ->
                        val mx = if (spiegeln) SeitenRaster.SPALTEN - m.x - m.b else m.x
                        androidx.compose.ui.geometry.Offset(((mx - r.x) + m.b / 2f) / r.b, ((m.y - r.y) + m.h / 2f) / r.h)
                    }
                    RahmenFlaeche(r, Modifier.fillMaxSize(), mitte)
                }
            }
            liste.forEach { b0 ->
                if (b0.typ == "alphabet" || b0.typ == "rahmen") return@forEach   // Alphabet liegt obendrueber, Rahmen sind schon gemalt
                val b = if (spiegeln) b0.copy(x = SeitenRaster.SPALTEN - b0.x - b0.b) else b0
                Box(
                    Modifier
                        .offset { with(dichte) { IntOffset(lg(b.x).roundToPx(), lg(b.y).roundToPx()) } }
                        .size(lg(b.x + b.b) - lg(b.x), lg(b.y + b.h) - lg(b.y))
                        .druckGefuehl(b.typ in Gefuehl.DRUCK_TYPEN)
                ) { BausteinRahmen(b) { if (b.typ == "favoriten" && favoriten != null) favoriten() else BausteinInhalt(b, jetzt, offen, onOffen) } }
            }
        }
    }
}

/** App-Knopf: Symbol und Name - antippen startet die App */
@Composable
internal fun AppBaustein(b: Baustein, modifier: Modifier) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val app = z.app(b.wert)
    val name = b.titel.ifBlank { app?.name ?: "App wählen" }
    val rahmen = remember { IntArray(4) }            // Lage im Fenster: die App waechst beim Oeffnen hier heraus
    Column(
        modifier.onGloballyPositioned { val r = it.boundsInWindow(); rahmen[0] = r.left.toInt(); rahmen[1] = r.top.toInt(); rahmen[2] = r.width.toInt(); rahmen[3] = r.height.toInt() }
            .clip(bForm(18.dp)).bKarte(p, 18.dp, 0.09f)
            .clickable(enabled = app != null) {
                StartRahmen.merken(rahmen)
                val a = app ?: return@clickable
                val akt = ctx as? MainActivity
                if (akt != null) akt.starte(a) else try {
                    ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(ComponentName(a.paket, a.activity)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) { }
            }
            .padding(if (b.b == 1 || b.h == 1) 2.dp else 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val mitIcon = LocalBOpt.current?.b("icon", true) ?: true
        if (mitIcon && app != null && (b.h >= 2 || b.b >= 2)) AppIcon(app.schluessel, if (b.h >= 2) 40.dp else 26.dp)
        if (mitIcon && (b.h >= 2 || app == null)) Spacer(Modifier.height(4.dp))
        if (b.h >= 2 || b.b >= 2 || app == null || !mitIcon)
            Text(name, color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        else androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
            AppIcon(app.schluessel, minOf(minOf(maxWidth, maxHeight) * 0.9f, 30.dp))       // 1x1: Icon so gross wie das Feld
        }
    }
}

// ============================================================================
//  Bearbeiten: sieht aus wie die Seite selbst, dazu das Raster leicht sichtbar.
//  Ziehen = verschieben, an den Ecken ziehen = Groesse, antippen = Funktion waehlen, "+" = neuer Baustein.
//  imStart (Startbildschirm, 23.10.): kein eigenes Menue - der Startbildschirm zoomt leicht heraus und wird
//  dort selbst bearbeitet (gleiche Funktionen). Sperr-Seite und Seite bei # wie bisher.
// ============================================================================
@Composable
internal fun RasterEditor(seite: String = "oben", imStart: Boolean = false) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val dichte = LocalDensity.current
    var liste by remember { mutableStateOf(SeitenRaster.laden(ctx, seite)) }
    val zEditor = LocalZustand.current
    val startSeite = seite == "start"
    val verlauf = remember { mutableStateListOf<List<Baustein>>() }
    var auswahl by remember { mutableStateOf<String?>(null) }
    var neuOffen by remember { mutableStateOf(false) }
    var eigenschaftenOffen by remember { mutableStateOf(false) }
    var funktionOffen by remember { mutableStateOf(false) }          // Startbildschirm: Funktion des Bausteins (statt Feld unten)
    var appWahlFuer by remember { mutableStateOf<String?>(null) }
    // gerade gezogen: welcher Baustein, wo angefasst ("m" Mitte, "lo" "ro" "lu" "ru" Ecken), Weg in px
    var zieht by remember { mutableStateOf<String?>(null) }
    var griff by remember { mutableStateOf("m") }
    val zx = remember { mutableFloatStateOf(0f) }
    val zy = remember { mutableFloatStateOf(0f) }
    // letzte Lage beim Ziehen, die gepasst hat (passt die neue nicht, bleibt der Baustein dort statt zurueckzuspringen)
    val letzteGute = remember { arrayOfNulls<Baustein>(1) }
    var start by remember { mutableStateOf(SeitenRaster.aufStart(ctx)) }
    var sperre by remember { mutableStateOf(SeitenRaster.aufSperre(ctx)) }
    val jetzt = remember { System.currentTimeMillis() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Zoom auf dem Startbildschirm: 0 = normal, 1 = ganz herausgezoomt
    val zoom = remember { androidx.compose.animation.core.Animatable(if (imStart) 0f else 1f) }
    var schliesst by remember { mutableStateOf(false) }
    // Abstand zu Status-/Navigationsleiste wie auf dem echten Startbildschirm (leistenAbstand) - als feste Werte,
    // weil der Zoom-Rahmen und der verkleinerte Startbildschirm ihn beide brauchen
    val leistenRand = if (zEditor.statusAus) WindowInsets.navigationBars.asPaddingValues() else WindowInsets.systemBars.asPaddingValues()

    fun aendern(neu: List<Baustein>) {
        verlauf.add(liste); if (verlauf.size > 30) verlauf.removeAt(0)
        liste = neu; SeitenRaster.speichern(ctx, neu, seite)
    }
    // Fertig / Zurueck: auf dem Startbildschirm erst wieder hineinzoomen
    fun fertig() {
        if (!imStart) { navi.zu(); return }
        if (schliesst) return
        schliesst = true
        scope.launch {
            zoom.animateTo(0f, androidx.compose.animation.core.tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing))
            navi.zu()
        }
    }
    if (imStart) {
        LaunchedEffect(Unit) { zoom.animateTo(1f, androidx.compose.animation.core.tween(260, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
        androidx.activity.compose.BackHandler { fertig() }
    }

    // ---- Leiste oben
    val leiste: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LeistenKnopf("↶", enabled = verlauf.isNotEmpty(), deckend = imStart) {
                val alt = verlauf.removeAt(verlauf.size - 1); liste = alt; SeitenRaster.speichern(ctx, alt, seite)
            }
            LeistenKnopf("+ Baustein", deckend = imStart) { neuOffen = true }
            // ausgewaehlten Baustein entfernen (das Alphabet bleibt immer)
            val weg = liste.firstOrNull { it.id == auswahl }?.takeIf { it.typ != "alphabet" }
            LeistenKnopf("🗑", enabled = weg != null, deckend = imStart) {
                if (weg != null) {
                    aendern(liste.filter { it.id != weg.id }); auswahl = null
                    // unsichtbarer Knopf weg -> Code wieder mit dem Feld neben der Uhr
                    if (weg.typ == "geheim" && zEditor.geheimOrt == "baustein") zEditor.geheimOrtSetzen("uhr")
                }
            }
            // Eigenschaften des ausgewaehlten Bausteins (Schrift, Hintergrund, ...)
            LeistenKnopf("✎", enabled = auswahl != null, deckend = imStart) {
                val t = liste.firstOrNull { it.id == auswahl }?.typ
                if (imStart && t in MIT_FUNKTION) funktionOffen = true else eigenschaftenOffen = true
            }
            Spacer(Modifier.weight(1f))
            LeistenKnopf("Fertig", hervor = true) { fertig() }
        }
    }

    // ---- ausgewaehlter Baustein: Funktion, Beschriftung, Groesse
    val details: @Composable (Baustein) -> Unit = { b ->
        val art = SeitenRaster.art(b.typ)
        val eigenDef = if (b.typ == "eigen") EigeneBausteine.von(ctx, b.wert) else null
        Text(eigenDef?.name ?: art?.name ?: b.typ, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (eigenDef != null) MenueEintrag("Code bearbeiten", "Eigener Baustein – im Baukasten nur als Platzhalter", rechts = "›") {
            navi.auf(Ebene.Einstellungen("eigen_" + eigenDef.id))
        }
        if (b.typ == "rahmen") Text("Rahmen an den Ecken so groß ziehen, wie du willst – dann Bausteine hineinziehen. Wieder rausziehen geht genauso.",
                                    color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (b.typ == "geheim") Text("Auf dem Startbildschirm unsichtbar. Gehört zum Code (Einstellungen → Alphabet → Code).",
                                    color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (b.typ == "alphabet") Text("Das Alphabet bleibt immer da – nur Platz und Größe sind frei.", color = p.leise, fontSize = 13.sp,
                                      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        if (b.typ == "nachrichten") {
            val z = LocalZustand.current
            MenueEintrag("Benachrichtigungen von", z.apps.firstOrNull { it.paket == b.wert }?.name ?: "Noch keine App – antippen") { appWahlFuer = b.id }
            if (!Musik.hatRecht(ctx)) MenueEintrag("Benachrichtigungs-Zugriff erlauben", "Nötig, damit Nachrichten hier erscheinen") {
                (ctx as? MainActivity)?.benachrichtigungsZugriff()
            }
        }
        if (b.typ == "app") {
            val z = LocalZustand.current
            MenueEintrag("Funktion: App öffnen", z.app(b.wert)?.name ?: "Noch keine App – antippen") { appWahlFuer = b.id }
            var titel by remember(b.id) { mutableStateOf(b.titel) }
            Eingabe(titel, { titel = it; aendern(liste.map { x -> if (x.id == b.id) x.copy(titel = it) else x }) }, "Beschriftung (leer = Name der App)")
        }
        BausteinEinstellungen(b) { neu -> aendern(liste.map { x -> if (x.id == b.id) neu else x }) }
        Text("Größe: ${b.b} × ${b.h} Linien", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }

    // ---- das Raster selbst. fest = Startbildschirm (so viele Reihen, wie auf den Bildschirm passen, kein Scrollen)
    val raster: @Composable (Boolean) -> Unit = { fest ->
        BoxWithConstraints(if (fest) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            val sp = (maxWidth + LUECKE) / SeitenRaster.ZELLEN
            val spPx = with(dichte) { sp.toPx() }; val lPx = with(dichte) { LUECKE.toPx() }
            // Lage einer Rasterlinie in px (gleich in X und Y - quadratische Felder)
            fun lp(k: Int) = SeitenRaster.lagePx(k, spPx, lPx)
            val zeilen = if (fest) {
                val hPx = with(dichte) { maxHeight.toPx() }
                var z = 1; while (lp(z + 1) <= hPx) z++; z
            } else SeitenRaster.zeilen(liste) + 8
            // naechste Rasterlinie zu einer Stelle (in px)
            fun naechste(px: Float): Int {
                val g = (px / (spPx / 2f)).roundToInt()
                return (g - 2..g + 2).map { maxOf(0, it) }.minByOrNull { kotlin.math.abs(lp(it) - px) } ?: 0
            }
            // beim Verschieben auf eine andere Linie: Groesse so waehlen, dass der Baustein (fast) gleich gross bleibt
            fun gleichGross(alt: Int, n: Int, neu: Int): Int {
                val soll = lp(alt + n) - lp(alt)
                return listOf(n, n - 1, n + 1).filter { it >= 1 }.minByOrNull { kotlin.math.abs(lp(neu + it) - lp(neu) - soll) } ?: n
            }
            // gezogener Baustein: eingerastete Vorschau-Lage (nur wenn sie passt) - jede Kante rastet an der naechsten Linie ein
            fun kandidat(b: Baustein): Baustein {
                if (zieht != b.id) return b
                val dx = zx.floatValue; val dy = zy.floatValue
                val k = when (griff) {
                    "m" -> {
                        val nx = naechste(lp(b.x) + dx); val ny = naechste(lp(b.y) + dy)
                        b.copy(x = nx, y = ny, b = gleichGross(b.x, b.b, nx), h = gleichGross(b.y, b.h, ny))
                    }
                    "lo" -> { val nx = naechste(lp(b.x) + dx); val ny = naechste(lp(b.y) + dy)
                              b.copy(x = nx, y = ny, b = b.x + b.b - nx, h = b.y + b.h - ny) }
                    "ro" -> { val r = naechste(lp(b.x + b.b) + dx); val ny = naechste(lp(b.y) + dy)
                              b.copy(y = ny, b = r - b.x, h = b.y + b.h - ny) }
                    "lu" -> { val nx = naechste(lp(b.x) + dx); val u = naechste(lp(b.y + b.h) + dy)
                              b.copy(x = nx, b = b.x + b.b - nx, h = u - b.y) }
                    else -> { val r = naechste(lp(b.x + b.b) + dx); val u = naechste(lp(b.y + b.h) + dy)
                              b.copy(b = r - b.x, h = u - b.y) }
                }
                if (SeitenRaster.passt(liste, k)) { letzteGute[0] = k; return k }
                return letzteGute[0]?.takeIf { it.id == b.id } ?: b
            }
            Box(
                Modifier.fillMaxWidth().height(with(dichte) { lp(zeilen).toDp() })
                    // Raster leicht sichtbar: jede Linie ist eine Stelle, an der eine Baustein-Kante einrastet
                    .drawBehind {
                        val farbe = p.text.copy(alpha = if (fest) 0.12f else 0.07f)
                        val dick = 1.dp.toPx()
                        for (i in 0..SeitenRaster.SPALTEN) {
                            val x = lp(i).coerceIn(0f, size.width)
                            drawLine(farbe, Offset(x, 0f), Offset(x, size.height), dick)
                        }
                        for (j in 0..zeilen) {
                            val y = lp(j)
                            if (y <= size.height) drawLine(farbe, Offset(0f, y), Offset(size.width, y), dick)
                        }
                    }
            ) {
                // Rahmen zuerst (liegen hinter den Bausteinen, die man hineinzieht)
                liste.sortedBy { it.typ != "rahmen" }.forEach { b0 -> androidx.compose.runtime.key(b0.id) {
                    val b = kandidat(b0)
                    val gewaehlt = auswahl == b.id
                    // gleitet weich in die neue Lage (statt zu springen) - abschaltbar unter System → Gefühl
                    val feder: androidx.compose.animation.core.AnimationSpec<Float> =
                        if (Gefuehl.uebergaenge) androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 700f)
                        else androidx.compose.animation.core.snap()
                    val ax by androidx.compose.animation.core.animateFloatAsState(lp(b.x), feder, label = "x")
                    val ay by androidx.compose.animation.core.animateFloatAsState(lp(b.y), feder, label = "y")
                    val ab by androidx.compose.animation.core.animateFloatAsState(lp(b.x + b.b) - lp(b.x), feder, label = "b")
                    val ah by androidx.compose.animation.core.animateFloatAsState(lp(b.y + b.h) - lp(b.y), feder, label = "h")
                    Box(
                        Modifier
                            .offset { IntOffset(ax.roundToInt(), ay.roundToInt()) }
                            .size(with(dichte) { ab.toDp() }, with(dichte) { ah.toDp() })
                    ) {
                        Box(if (fest) Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)) else Modifier.fillMaxSize()) {
                            androidx.compose.runtime.CompositionLocalProvider(LocalImEditor provides true) {
                                BausteinRahmen(b) { BausteinInhalt(b, jetzt, null) { } }
                            }
                        }
                        // Deckel: faengt alles ab (im Bearbeiten nichts ausloesen) - antippen / verschieben
                        Box(
                            Modifier.fillMaxSize()
                                .border(if (gewaehlt) 2.dp else 1.dp, if (gewaehlt) p.akzent else p.text.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                                .background(if (zieht == b.id) p.akzent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(18.dp))
                                .pointerInput(b0.id) { detectTapGestures(onTap = { auswahl = if (auswahl == b0.id) null else b0.id }) }
                                .pointerInput(b0.id, liste) {
                                    detectDragGestures(
                                        onDragStart = { zieht = b0.id; griff = "m"; zx.floatValue = 0f; zy.floatValue = 0f; letzteGute[0] = null; auswahl = b0.id },
                                        onDragEnd = {
                                            val k = kandidat(b0)
                                            zieht = null
                                            if (k != b0) aendern(liste.map { if (it.id == b0.id) k else it })
                                        },
                                        onDragCancel = { zieht = null },
                                    ) { c, weg -> c.consume(); zx.floatValue += weg.x; zy.floatValue += weg.y }
                                }
                        )
                        // Ecken zum Ziehen (beim ausgewaehlten Baustein)
                        if (gewaehlt) for (ecke in listOf("lo", "ro", "lu", "ru")) {
                            val klein = b.b <= 2 || b.h <= 2
                            Box(
                                Modifier
                                    .align(when (ecke) { "lo" -> Alignment.TopStart; "ro" -> Alignment.TopEnd; "lu" -> Alignment.BottomStart; else -> Alignment.BottomEnd })
                                    .offset(x = (if (klein) 10.dp else 9.dp).let { if (ecke.startsWith("l")) -it else it }, y = (if (klein) 10.dp else 9.dp).let { if (ecke.endsWith("o")) -it else it })
                                    .size(if (klein) 18.dp else 24.dp).clip(CircleShape).background(p.akzent)
                                    .pointerInput(b0.id, ecke, liste) {
                                        detectDragGestures(
                                            onDragStart = { zieht = b0.id; griff = ecke; zx.floatValue = 0f; zy.floatValue = 0f; letzteGute[0] = null },
                                            onDragEnd = {
                                                val k = kandidat(b0)
                                                zieht = null
                                                if (k != b0) aendern(liste.map { if (it.id == b0.id) k else it })
                                            },
                                            onDragCancel = { zieht = null },
                                        ) { c, weg -> c.consume(); zx.floatValue += weg.x; zy.floatValue += weg.y }
                                    }
                            )
                        }
                    }
                } }
            }
        }
    }

    if (imStart) {
        // ---- Startbildschirm: ganz leicht herausgezoomt, drumherum abgedunkelt
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val vollB = maxWidth; val vollH = maxHeight
            var loch by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
            var wurzel by remember { mutableStateOf(Offset.Zero) }
            Column(
                Modifier.fillMaxSize()
                    .onGloballyPositioned { wurzel = it.boundsInWindow().topLeft }
                    // Schleier um den verkleinerten Startbildschirm (der selbst bleibt klar)
                    .drawBehind {
                        val schleier = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f * zoom.value)
                        val l = loch
                        if (l == null) drawRect(schleier) else {
                            val r = l.translate(-wurzel)
                            val rund = 28.dp.toPx() * zoom.value
                            val pfad = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(androidx.compose.ui.geometry.RoundRect(r, androidx.compose.ui.geometry.CornerRadius(rund, rund)))
                            }
                            clipPath(pfad, androidx.compose.ui.graphics.ClipOp.Difference) { drawRect(schleier) }
                        }
                    }
                    .padding(top = leistenRand.calculateTopPadding())
            ) {
                Box(Modifier.graphicsLayer { alpha = zoom.value }) { leiste() }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp, bottom = 10.dp), contentAlignment = Alignment.TopCenter) {
                    val ziel = minOf(maxHeight / vollH, maxWidth / vollB, 0.94f)
                    Box(Modifier.size(vollB * ziel, vollH * ziel)) {
                        // der Startbildschirm in voller Groesse aufgebaut (gleiche Abstaende wie in echt), dann verkleinert
                        Box(
                            Modifier.align(Alignment.Center).requiredSize(vollB, vollH)
                                .graphicsLayer { val s = 1f + (ziel - 1f) * zoom.value; scaleX = s; scaleY = s }
                                .onGloballyPositioned { loch = it.boundsInWindow() }
                                .border((1.5f / ziel).dp, p.text.copy(alpha = 0.45f * zoom.value), RoundedCornerShape(28.dp / ziel))
                                .clip(RoundedCornerShape(28.dp / ziel))
                                .padding(leistenRand)
                                .padding(horizontal = START_RAND)
                                .padding(top = 6.dp)
                        ) { raster(true) }
                    }
                }
            }
        }
    } else Column(Modifier.fillMaxSize()) {
        leiste()
        if (!startSeite && seite != "sperre") Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Auf # nach links (App-Liste)", start) {
                start = !start; SeitenRaster.ortSetzen(ctx, start = start)
            }
        }
        Text("Ziehen = verschieben · an den Ecken ziehen = Größe · antippen = Funktion",
             color = p.leise, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))

        // ---- das Raster selbst
        // rechts eine Scroll-Leiste: dort ziehen scrollt, ohne einen Baustein zu verschieben
        val blatt = rememberScrollState()
        Row(Modifier.fillMaxWidth().weight(1f)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(blatt, enabled = zieht == null).padding(start = 12.dp, end = 4.dp)) {
            raster(false)
            // ---- ausgewaehlter Baustein: Funktion, Beschriftung, entfernen
            val b = liste.firstOrNull { it.id == auswahl }
            if (b != null) {
                Column(Modifier.fillMaxWidth().padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(p.text.copy(alpha = 0.06f)).padding(vertical = 6.dp)) {
                    details(b)
                }
            }
            Spacer(Modifier.height(10.dp))
            MenueEintrag("Wie am Anfang", "Alles auf die ursprüngliche Anordnung zurücksetzen") {
                verlauf.add(liste); SeitenRaster.zuruecksetzen(ctx, seite); liste = SeitenRaster.laden(ctx, seite); auswahl = null
            }
            if (seite == "sperre") {
                MenueEintrag("Sperrbildschirm", "Große Uhr, Hintergrund, Abdunkeln, Übergang", rechts = "›") { navi.auf(Ebene.Einstellungen("d_sperr")) }
            } else if (startSeite) {
                MenueEintrag("Eigene Bausteine", "Selbst bauen – mit Anleitung", rechts = "›") { navi.auf(Ebene.Einstellungen("eigene")) }
                MenueEintrag("System", "Berechtigungen, Standard-Launcher, Statusleiste …", rechts = "›") { navi.auf(Ebene.Einstellungen("system")) }
                MenueEintrag("Alle Einstellungen", "Design, Alphabet, System …", rechts = "›") { navi.auf(Ebene.Einstellungen()) }
            } else MenueEintrag("Weitere Einstellungen", "Wetter, Geburtstage …", rechts = "›") { navi.auf(Ebene.Einstellungen("kopf")) }
            Spacer(Modifier.height(40.dp))
        }
        ScrollLeiste(blatt)
        }
    }

    // ---- Startbildschirm: Funktion des ausgewaehlten Bausteins (App, Adresse, Text ...), darunter weiter zu den Eigenschaften
    if (funktionOffen) liste.firstOrNull { it.id == auswahl }?.let { b ->
        Dialog(onDismissRequest = { funktionOffen = false }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(p.grund)
                       .verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                details(b)
                MenueEintrag("Aussehen", "Schrift, Hintergrund …", rechts = "›") { funktionOffen = false; eigenschaftenOffen = true }
            }
        }
    } ?: run { if (funktionOffen) funktionOffen = false }

    // ---- Eigenschaften des ausgewaehlten Bausteins
    if (eigenschaftenOffen) liste.firstOrNull { it.id == auswahl }?.let { b ->
        EigenschaftenBlatt(b, onAendern = { neu -> aendern(liste.map { x -> if (x.id == b.id) neu else x }) }) { eigenschaftenOffen = false }
    } ?: run { if (eigenschaftenOffen) eigenschaftenOffen = false }

    // ---- neuer Baustein
    if (neuOffen) Dialog(onDismissRequest = { neuOffen = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 600.dp).clip(RoundedCornerShape(24.dp)).background(p.grund)
                   .verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text("Baustein hinzufügen", color = p.text, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            SeitenRaster.arten(seite).filter { a -> SeitenRaster.imMenue(a, liste, seite) }.forEach { a ->
                MenueEintrag(a.name, "${a.b} × ${a.h} Felder") {
                    val id = if (a.einmal) a.typ else a.typ + System.currentTimeMillis()
                    // Rahmen: freien Platz suchen wie fuer einen normalen Baustein (sonst laege er auf anderen)
                    val k = SeitenRaster.freierPlatz(liste, Baustein(id, if (a.typ == "rahmen") "platz" else a.typ, 0, 0, SeitenRaster.neueGroesse(a.b), SeitenRaster.neueGroesse(a.h))).copy(typ = a.typ)
                    aendern(liste + k); auswahl = k.id; neuOffen = false
                    if (a.typ == "app" || a.typ == "nachrichten") appWahlFuer = k.id
                    if (a.typ == "geheim") zEditor.geheimOrtSetzen("baustein")
                    // gleich die schon vorhandenen Nachrichten einlesen
                    Lauscher.verbunden?.let { d -> try { Lauscher.allesLesen(d, d.activeNotifications) } catch (e: Exception) { } }
                }
            }
            if (SeitenRaster.arten(seite).none { a -> SeitenRaster.imMenue(a, liste, seite) })
                Text("Alles schon da", color = p.leise, modifier = Modifier.padding(20.dp))
            // eigene Bausteine (Code) - erst Atelier
                            @Suppress("UNUSED_VARIABLE") val st = EigeneBausteine.stand.intValue
                Text("Eigene Bausteine", color = p.leise, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                     modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 2.dp))
                EigeneBausteine.alle(ctx).forEach { d ->
                    MenueEintrag(d.name, "</> · ${d.b} × ${d.h} Felder") {
                        val k = SeitenRaster.freierPlatz(liste, Baustein("eigen" + System.currentTimeMillis(), "eigen", 0, 0, SeitenRaster.neueGroesse(d.b), SeitenRaster.neueGroesse(d.h), wert = d.id))
                        aendern(liste + k); auswahl = k.id; neuOffen = false
                    }
                }
                MenueEintrag("Eigenen Baustein bauen …", "Mit Anleitung – aus HTML, CSS und JavaScript", rechts = "›") {
                    neuOffen = false; navi.auf(Ebene.Einstellungen("eigene"))
                }
        }
    }

    // ---- App fuer einen App-Knopf waehlen
    appWahlFuer?.let { id ->
        val z = LocalZustand.current
        var suche by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { appWahlFuer = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).clip(RoundedCornerShape(24.dp)).background(p.grund).padding(vertical = 12.dp)) {
                val fuerNachr = liste.firstOrNull { it.id == id }?.typ == "nachrichten"
                Text(if (fuerNachr) "Benachrichtigungen von welcher App?" else "App wählen", color = p.text, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                Eingabe(suche, { suche = it }, "Apps suchen")
                val apps = z.apps.filter { suche.isBlank() || it.name.contains(suche.trim(), true) }
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(apps, key = { it.schluessel }) { a ->
                        Row(Modifier.fillMaxWidth().clickable {
                                // App-Knopf merkt sich die App (Paket/Activity), Nachrichten nur das Paket
                                aendern(liste.map { if (it.id == id) it.copy(wert = if (it.typ == "nachrichten") a.paket else a.schluessel) else it }); appWahlFuer = null
                                Lauscher.verbunden?.let { d -> try { Lauscher.allesLesen(d, d.activeNotifications) } catch (e: Exception) { } }
                            }.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.schluessel, 32.dp)
                            Spacer(Modifier.size(14.dp))
                            Text(a.name, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Bausteine mit eigener Funktion (App, Adresse, Text, Code ...) - auf dem Startbildschirm ueber ✎ */
private val MIT_FUNKTION = setOf("app", "nachrichten", "webseite", "notiz", "eigen", "ruhe", "ton")

/** Scroll-Leiste ganz rechts: Griff zeigt, wo man ist; ziehen (oder antippen) scrollt das Blatt */
@Composable
private fun ScrollLeiste(blatt: androidx.compose.foundation.ScrollState) {
    val p = LocalPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var hoehe by remember { mutableIntStateOf(1) }
    Box(
        Modifier.width(26.dp).fillMaxHeight()
            .onSizeChanged { hoehe = maxOf(1, it.height) }
            .pointerInput(Unit) {
                // Position auf der Leiste = Position im Blatt (wie ein Rollbalken)
                fun hin(y: Float) {
                    val ziel = (y / hoehe).coerceIn(0f, 1f) * blatt.maxValue
                    scope.launch { blatt.scrollTo(ziel.roundToInt()) }
                }
                detectDragGestures(onDragStart = { hin(it.y) }) { c, _ -> c.consume(); hin(c.position.y) }
            }
            .pointerInput(Unit) { detectTapGestures { hin2 -> scope.launch { blatt.animateScrollTo(((hin2.y / hoehe).coerceIn(0f, 1f) * blatt.maxValue).roundToInt()) } } },
    ) {
        // Schiene und Griff
        Box(Modifier.align(Alignment.Center).width(3.dp).fillMaxHeight().padding(vertical = 8.dp).clip(RoundedCornerShape(2.dp)).background(p.text.copy(alpha = 0.12f)))
        val gesamt = (blatt.maxValue + hoehe).toFloat()
        val griffH = (hoehe * (hoehe / gesamt)).coerceAtLeast(with(LocalDensity.current) { 36.dp.toPx() })
        val y = if (blatt.maxValue <= 0) 0f else (hoehe - griffH) * blatt.value / blatt.maxValue
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, y.roundToInt()) }
                .width(8.dp).height(with(LocalDensity.current) { griffH.toDp() })
                .clip(RoundedCornerShape(4.dp)).background(p.text.copy(alpha = if (blatt.maxValue > 0) 0.55f else 0.2f))
        )
    }
}

@Composable
private fun LeistenKnopf(text: String, enabled: Boolean = true, hervor: Boolean = false, deckend: Boolean = false, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (hervor) p.akzent else if (deckend) p.karte else p.text.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(text, color = if (hervor) p.aufAkzent else p.text.copy(alpha = if (enabled) 1f else 0.35f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
