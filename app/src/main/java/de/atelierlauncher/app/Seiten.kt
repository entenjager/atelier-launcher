package de.atelierlauncher.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.clickable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import kotlin.math.exp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ============================================================================
// ============================================================================

// ---------------------------------------------------------------- Benachrichtigungen
data class Meldung(val key: String, val titel: String, val text: String, val zeit: Long, val paket: String, val ziel: PendingIntent?)




object ChatVerlauf {
    private const val SCHLUESSEL = "chatVerlauf"
    private val CHAT_APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.thoughtcrime.securesms")
    private val ZAHL = Regex("""\s*\(\d+\s+[^)]*\)\s*$""")      // "Familie (3 Nachrichten)" -> "Familie"

    fun name(titel: String) = titel.replace(ZAHL, "").trim()

    @Synchronized
    fun merken(ctx: Context, paket: String, titel: String, zeit: Long) {
        if (paket !in CHAT_APPS) return
        val n = name(titel)
        if (n.isEmpty() || n.equals("WhatsApp", true) || n.equals("WhatsApp Business", true) || n.equals("Signal", true)) return
        try {
            val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
            val alles = JSONObject(p.getString(SCHLUESSEL, "{}") ?: "{}")
            val app = alles.optJSONObject(paket) ?: JSONObject()
            if (app.optLong(n, 0L) >= zeit) return
            app.put(n, zeit)
            // nur die 40 juengsten behalten
            if (app.length() > 40) {
                val alt = app.keys().asSequence().toList().sortedBy { app.optLong(it) }.take(app.length() - 40)
                alt.forEach { app.remove(it) }
            }
            alles.put(paket, app)
            p.edit().putString(SCHLUESSEL, alles.toString()).apply()
        } catch (e: Exception) { }
    }

    /** Name (klein) -> (Name, Zeit) fuer diese Apps */
    fun lesen(ctx: Context, pakete: List<String>): Map<String, Pair<String, Long>> {
        val raus = HashMap<String, Pair<String, Long>>()
        try {
            val alles = JSONObject(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(SCHLUESSEL, "{}") ?: "{}")
            for (pk in pakete) {
                val app = alles.optJSONObject(pk) ?: continue
                for (n in app.keys()) {
                    val z = app.optLong(n)
                    val k = n.lowercase()
                    if ((raus[k]?.second ?: -1L) < z) raus[k] = n to z
                }
            }
        } catch (e: Exception) { }
        return raus
    }
}

/** Neue WhatsApp-Nachrichten (nur aus den Benachrichtigungen - nichts wird gespeichert oder verschickt) */
val WhatsAppPost = Postfach { _, paket -> paket == "com.whatsapp" || paket == "com.whatsapp.w4b" }

/** Neue Signal-Nachrichten (nur aus den Benachrichtigungen) */
val SignalPost = Postfach { _, paket -> paket == "org.thoughtcrime.securesms" }


/** Sammelt die Benachrichtigungen EINER App (wird nur im Arbeitsspeicher gehalten) */
/**
 * Sammelt die Benachrichtigungen EINER App.
 * merkenAls != null: die letzten 5 bleiben auch nach dem Lesen stehen (lokal auf dem Handy gespeichert,
 * ohne Link - Antippen oeffnet dann die App). Sonst nur, solange die Benachrichtigung da ist.
 */
/**
 * Lange auf einen Knopf gedrueckt = "weg damit": diese Meldungen / Mails zaehlen nicht mehr (auch wenn man die
 * Benachrichtigung selbst nicht wegwischen kann). Gemerkt als "Schluessel@Zeit" - kommt in derselben
 * Benachrichtigung etwas Neues (neue Zeit), zaehlt es wieder.
 */
object Abgehakt {
    private const val MAX = 150
    /** Aenderungszaehler, damit die Zahlen auf den Knoepfen sofort neu berechnet werden */
    var stand by mutableStateOf(0)
    private val cache = HashMap<String, Set<String>>()
    @Synchronized fun liste(ctx: Context, name: String): Set<String> = cache.getOrPut(name) {
        try {
            val a = JSONArray(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("abgehakt_$name", "[]"))
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (e: Exception) { emptySet() }
    }
    @Synchronized fun dazu(ctx: Context, name: String, neu: Collection<String>) {
        if (neu.isEmpty()) return
        val l = (liste(ctx, name).toList() - neu.toSet() + neu).takeLast(MAX)
        cache[name] = l.toSet()
        val a = JSONArray(); l.forEach { a.put(it) }
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("abgehakt_$name", a.toString()).apply()
        Handler(Looper.getMainLooper()).post { stand++ }
    }
}

class Postfach(private val merkenAls: String? = null, private val passt: (Context, String) -> Boolean) {
    var meldungen by mutableStateOf<List<Meldung>>(emptyList())
    /** Schluessel der Meldungen, deren Benachrichtigung noch da ist (= ungelesen) */
    var ungelesen by mutableStateOf<Set<String>>(emptySet())
    private val haupt = Handler(Looper.getMainLooper())
    private var app: Context? = null

    /** Zaehler fuer die Diagnose (Einstellungen -> Berechtigungen) */
    val zaehler = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun zaehl(was: String) { zaehler.merge(was, 1) { a, b -> a + b } }

    private fun lies(ctx: Context, sbn: StatusBarNotification, zusammenfassungOk: Boolean = false): Meldung? {
        if (!passt(ctx, sbn.packageName)) return null
        zaehl("gesehen")
        if ("${sbn.key}@${sbn.postTime}" in Abgehakt.liste(ctx, "post")) { zaehl("abgehakt"); return null }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 && !zusammenfassungOk) { zaehl("zusammenfassung"); return null }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) { zaehl("dauerhaft"); return null }
        val ex = n.extras
        // Chat-Nachrichten: letzte Nachricht aus dem Verlauf
        val verlauf = try {
            @Suppress("DEPRECATION")
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (e: Exception) { null }
        val letzte = (verlauf?.lastOrNull() as? android.os.Bundle)
        val titel = (ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: ex.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (letzte?.getCharSequence("text")
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (titel.isEmpty() && text.isEmpty()) { zaehl("leer"); return null }
        zaehl("ok")
        ChatVerlauf.merken(ctx, sbn.packageName, titel, sbn.postTime)
        val absender = letzte?.getCharSequence("sender")?.toString()
        return Meldung(sbn.key, titel.ifEmpty { "Nachricht" },
                       if (absender != null && absender != titel) "$absender: $text" else text,
                       sbn.postTime, sbn.packageName, n.contentIntent)
    }

    // eigener Stand, sofort aktuell (Benachrichtigungen kommen schnell hintereinander) - Compose bekommt eine Kopie
    private var stand = emptyList<Meldung>()
    private var aktiv = emptySet<String>()
    private var geladen = false

    /** gemerkte Meldungen einmal aus dem Speicher holen */
    private fun ladeGemerkt(ctx: Context) {
        if (geladen || merkenAls == null) return
        geladen = true
        app = ctx.applicationContext
        try {
            val a = JSONArray(ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(merkenAls, "[]"))
            stand = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Meldung(o.getString("k"), o.getString("t"), o.optString("x"), o.getLong("z"), o.getString("p"), null)
            }
        } catch (e: Exception) { }
    }

    @Synchronized private fun setzen(l: List<Meldung>, neuAktiv: Set<String>) {
        stand = l.sortedByDescending { it.zeit }.distinctBy { it.key }.take(if (merkenAls != null) 5 else 6)
        aktiv = neuAktiv
        val s = stand; val u = aktiv.filter { k -> s.any { it.key == k } }.toSet()
        haupt.post { meldungen = s; ungelesen = u }
        // merken: ohne Link speichern (Links verfallen ohnehin)
        val c = app
        if (merkenAls != null && c != null) try {
            val a = JSONArray()
            s.forEach { m -> a.put(JSONObject().put("k", m.key).put("t", m.titel).put("x", m.text).put("z", m.zeit).put("p", m.paket)) }
            c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(merkenAls, a.toString()).apply()
        } catch (e: Exception) { }
    }

    fun alleLesen(ctx: Context, alle: Array<StatusBarNotification>?) {
        synchronized(this) {
            ladeGemerkt(ctx)
            var jetzt = alle.orEmpty().mapNotNull { lies(ctx, it) }
            // nichts Einzelnes da, aber eine Zusammenfassung ("3 neue Nachrichten") -> die nehmen
            if (jetzt.isEmpty()) jetzt = alle.orEmpty().mapNotNull { lies(ctx, it, zusammenfassungOk = true) }
            val keys = jetzt.map { it.key }.toSet()
            setzen(if (merkenAls != null) jetzt + stand.filter { it.key !in keys } else jetzt, keys)
        }
    }

    fun neu(ctx: Context, sbn: StatusBarNotification) {
        val m = lies(ctx, sbn) ?: return
        synchronized(this) {
            ladeGemerkt(ctx)
            setzen(stand.filter { it.key != m.key } + m, aktiv + m.key)
        }
    }

    /**
     * Lange gedrueckt: alles, was hier gerade als neu zaehlt, weg. Die Benachrichtigungen werden geschlossen,
     * wo Android das erlaubt - die anderen (System-Meldungen, die man nicht wegwischen kann) zaehlen einfach nicht mehr.
     */
    fun allesWeg(ctx: Context) {
        val l = Lauscher.verbunden
        val alle = (try { l?.activeNotifications } catch (e: Exception) { null }).orEmpty().filter { passt(ctx, it.packageName) }
        Abgehakt.dazu(ctx, "post", alle.map { "${it.key}@${it.postTime}" })
        for (n in alle) try { l?.cancelNotification(n.key) } catch (e: Exception) { }
        synchronized(this) {
            val keys = aktiv + alle.map { it.key }
            if (merkenAls != null) setzen(stand, emptySet())                 // gemerkte bleiben stehen, nur nicht mehr "neu"
            else setzen(stand.filter { it.key !in keys }, emptySet())
        }
    }

    fun weg(key: String) {
        synchronized(this) {
            if (merkenAls != null) { if (key in aktiv) setzen(stand, aktiv - key) }   // bleibt stehen, nur nicht mehr "neu"
            else if (stand.any { it.key == key }) setzen(stand.filter { it.key != key }, aktiv - key)
        }
    }

    fun oeffnen(ctx: Context, m: Meldung) {
        // 1) Link aus der Benachrichtigung. Ab Android 14 muss der Launcher ausdruecklich erlauben,
        //    dass dabei ein Fenster aufgehen darf - sonst passiert beim Antippen einfach nichts.
        try {
            if (m.ziel != null) {
                val opt = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle() else null
                m.ziel.send(ctx, 0, null, null, null, null, opt)
                return
            }
        } catch (e: Exception) { }
        // 2) gemerkte Nachricht ohne Link: passenden Chat-Kurzbefehl suchen (oeffnet genau diesen Chat)
        (ctx as? MainActivity)?.let { akt ->
            val k = akt.kontaktFinden(m.paket, m.titel)
            if (k != null) { akt.kurzbefehlStarten(k); return }
        }
        // 3) sonst einfach die App
        ctx.packageManager.getLaunchIntentForPackage(m.paket)?.let {
            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
    }
}

          // bei Ausfall: was stattdessen ist (Vertretung, Text)


/**
 * Sieht aus wie die App-Liste: gleicher dunkler Schleier, gleiche grosse Zeilen (Symbol + Name),
 * kleine Ueberschriften wie die Buchstaben in der Suche. Kommt von oben herein und liegt dort,
 * wo sonst die Uhr ist. Nach oben wischen schliesst sie wieder (folgt dem Finger, federt).
 */
@Composable
fun NebenSeite(modifier: Modifier, onZurueck: () -> Unit, hochZurueck: Boolean = true,
 seite: String? = null,
               /** lange druecken auf freie Flaeche (Sperr-Seite: entsperren und gestalten) */ onLang: (() -> Unit)? = null) {
    val z = LocalZustand.current
    // Navigation gibt es nur im Launcher selbst (nicht auf dem Sperrbildschirm) -> nur oeffentlich lesen
    val navi = LocalNavi.current
    val haptikSeite = LocalHapticFeedback.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val ohneIcons = z.thema.icons == "keine"
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); if (Sichtbar.an) jetzt = System.currentTimeMillis() } }

    val scroll = rememberScrollState()
    val zurueck by rememberUpdatedState(onZurueck)
    val dichte = LocalDensity.current
    val maxZug = with(dichte) { 120.dp.toPx() }
    val schwelle = with(dichte) { 24.dp.toPx() }
    val genug = with(dichte) { 90.dp.toPx() }
    val herein = with(dichte) { 90.dp.toPx() }
    fun gummi(weg: Float) = maxZug * (1f - exp(-weg.coerceAtLeast(0f) / (maxZug * 1.6f)))
    val zieh = remember { mutableFloatStateOf(0f) }
    val feder = remember { Animatable(0f) }
    var zieht by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Hereinfallen von oben mit leichter Federung
    val auf = remember { Animatable(0f) }
    // beim Oeffnen einmal alle aktuellen Benachrichtigungen neu einlesen (falls etwas verpasst wurde)
    // NICHT auf dem Bildschirm-Faden und erst nach dem Hereinfallen: das Einlesen aller Benachrichtigungen
    // (inkl. Bilder) dauert spuerbar und hat sonst das Alphabet beim Oeffnen ruckeln lassen
    LaunchedEffect(Unit) {
        delay(450)
        withContext(Dispatchers.Default) {
            Lauscher.verbunden?.let { d ->
                try {
                    Lauscher.allesLesen(d, d.activeNotifications)
                } catch (e: Exception) { }
            }
        }
    }
    LaunchedEffect(Unit) { auf.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = 420f)) }

    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tempo = VelocityTracker()
                var weg = 0f          // positiv = nach oben
                var basis = Float.NaN // ab hier zaehlt das Ziehen (erst wenn ganz unten gescrollt)
                var aktiv = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    weg -= c.position.y - c.previousPosition.y
                    tempo.addPosition(c.uptimeMillis, Offset(0f, weg))
                    // nur wenn ganz nach unten gescrollt (oder nichts zu scrollen), sonst normal scrollen
                    if (basis.isNaN() && !scroll.canScrollForward) basis = weg
                    // Sperrbildschirm: nach oben wischen tut nichts (dort nur vom Rand weg)
                    if (hochZurueck && !aktiv && !basis.isNaN() && weg - basis > schwelle) aktiv = true
                    if (aktiv) { zieht = true; zieh.floatValue = gummi(weg - basis - schwelle); c.consume() }
                }
                if (aktiv) {
                    val v = tempo.calculateVelocity().y
                    val start = zieh.floatValue
                    zieht = false
                    if (weg - basis > genug || v > 1200f) zurueck()
                    else scope.launch {
                        feder.snapTo(start)
                        feder.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 380f), initialVelocity = v * 0.3f)
                    }
                }
            }
        }
    ) {
        Column(
            Modifier.fillMaxSize()
                .graphicsLayer {
                    val e = auf.value
                    translationY = -(1f - e) * herein - (if (zieht) zieh.floatValue else feder.value)
                    alpha = e.coerceIn(0f, 1f)
                }
                // oeffentlich: lange druecken (auf freie Flaeche) = Bausteine bearbeiten
                // (in der App-Liste uebernimmt das der Startbildschirm selbst - sonst ginge es doppelt auf)
                .then(if (onLang != null) Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptikSeite.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLang()
                    })
                } else Modifier)
                .then(if ((onLang == null) && !LocalInAppListe.current) Modifier.pointerInput(Unit) {
                    detectTapGestures(onLongPress = {
                        haptikSeite.performHapticFeedback(HapticFeedbackType.LongPress)
                        navi?.auf(Ebene.Einstellungen("raster"))
                    })
                } else Modifier)
                .verticalScroll(scroll)
                .padding(top = if (LocalKompakt.current) 2.dp else 24.dp, bottom = 24.dp)
        ) {
            var offen by remember { mutableStateOf<String?>(null) }      // "mails" / "messenger" / null
            BausteinRaster(SeitenRaster.laden(ctx, seite ?: "oben"), jetzt = jetzt, offen = offen,
                           leerText = if (seite == "sperre") "Leer – lange drücken, entsperren, Bausteine hinzufügen" else "Diese Seite ist noch leer.\nEinstellungen → Seite bei # → „+ Baustein“",
                           onOffen = { offen = if (offen == it) null else it })

            // ----- aufgeklappt: WhatsApp / Signal - die letzten 5 Chats (neue Nachrichten fett darin)
            if (offen == "whatsapp") ChatListe("WhatsApp", listOf("com.whatsapp", "com.whatsapp.w4b"), WhatsAppPost, ohneIcons, jetzt)
            if (offen == "signal") ChatListe("Signal", listOf("org.thoughtcrime.securesms"), SignalPost, ohneIcons, jetzt)
            // ----- aufgeklappt: Nachrichten einer selbst gewaehlten App
            offen?.takeIf { it.startsWith("n:") }?.removePrefix("n:")?.let { paket ->
                val name = z.apps.firstOrNull { it.paket == paket }?.name ?: paket
                ChatListe(name, listOf(paket), AppPost.fuer(paket), ohneIcons, jetzt)
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}


/** ein Knopf-Baustein (WhatsApp, Signal, beliebige App) mit Zahl - lang druecken = Zahl weg */
@Composable
private fun KnopfBaustein(typ: String, modifier: Modifier, offen: String?, wert: String = "", onOffen: (String) -> Unit) {
    val z = LocalZustand.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val abg = Abgehakt.stand
    when (typ) {
        "whatsapp" -> ZahlKnopf("WhatsApp", WhatsAppPost.meldungen.size, offen == "whatsapp", modifier, onLang = { WhatsAppPost.allesWeg(ctx) }) { onOffen("whatsapp") }
        "signal" -> ZahlKnopf("Signal", SignalPost.meldungen.size, offen == "signal", modifier, onLang = { SignalPost.allesWeg(ctx) }) { onOffen("signal") }
        "nachrichten" -> {
            val paket = wert
            val name = z.apps.firstOrNull { it.paket == paket }?.name ?: "App wählen"
            val post = AppPost.fuer(paket)
            ZahlKnopf(name, if (LocalBOpt.current?.b("zahl", true) == false) 0 else post.meldungen.size, offen == "n:$paket", modifier, onLang = { post.allesWeg(ctx) }) { if (paket.isNotBlank()) onOffen("n:$paket") }
        }
    }
}

/** die Seite bei # wird gerade in der App-Liste des Startbildschirms gezeigt */
internal val LocalInAppListe = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Inhalt eines Bausteins - fuellt die Rasterzelle ganz aus */
@Composable
internal fun BausteinInhalt(b0: Baustein, jetzt: Long, offen: String?, onOffen: (String) -> Unit) {
    val voll = Modifier.fillMaxSize()
    // Raster seit 22.10. halb so gross: die Inhalte rechnen weiter in den bisherigen (doppelt so grossen) Feldern
    val b = if (b0.typ == "eigen") b0 else b0.copy(b = SeitenRaster.alteEinheit(b0.b), h = SeitenRaster.alteEinheit(b0.h))
    when (b.typ) {
        "termine" -> TermineEinfach(jetzt, voll)
        "musik" -> BoxWithConstraints(voll) {
            // im Baustein: kein "Heruntergeladen"-Knopf, Cover nur wenn genug Platz
            MusikKarte(kompakt = maxWidth < 230.dp, modifier = Modifier.fillMaxSize(), imBaustein = true, mitCover = maxWidth >= 260.dp || (maxWidth < 230.dp && maxWidth >= 150.dp && maxHeight >= 110.dp))
        }
        "whatsapp", "signal", "nachrichten" -> KnopfBaustein(b.typ, voll, offen, b.wert, onOffen)
        "app" -> AppBaustein(b, voll)
        "favoriten" -> FavoritenVorschau(voll)
        "rahmen" -> { RahmenFlaeche(b, voll); RahmenLeer(voll) }
        "suche" -> SucheBaustein(b, voll)
        "alphabet" -> AlphabetVorschau(voll)
        "lampe", "ruhe", "ton", "webseite", "kamera", "wlan", "bluetooth" -> AktionBaustein(b, voll)
        // jede Uhr sieht aus wie die normale (alle Designs) und fuellt den Baustein. Auf der Sperr-Seite gibt es keine
        // Launcher-Activity (die normale Uhr braucht sie) - dort die einfache Uhr, die ebenfalls den Baustein fuellt
        "uhr", "uhrklein" -> if (LocalContext.current is MainActivity) UhrBlock(voll) else AnzeigeBaustein(b0.copy(typ = "uhrklein"), voll, jetzt)
        "akku", "termin", "notiz" -> AnzeigeBaustein(b, voll, jetzt)
        in NETZ_TYPEN -> NetzBaustein(b, voll)
        "eigen" -> EigenerBaustein(b, voll)
        "geheim" -> UnsichtbarerKnopf(voll)
    }
}


/**
 * Termine-Baustein, schlicht: je Zeile nur Titel und wann (heute / morgen / Fr 24.10.), so viele wie hineinpassen.
 * "+" oben rechts traegt einen Termin ein, lange druecken auf einen eigenen Termin loescht ihn.
 */
@Composable
private fun TermineEinfach(jetzt: Long, modifier: Modifier) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val geb = Geburtstage.stand
    val o = LocalBOpt.current
    val nurEigene = o?.s("quelle") == "eigen"
    val liste = Geburtstage.alsTermine(ctx, jetzt)
        .filter { it.ende > jetzt }.sortedBy { it.beginn }.take(o?.i("anzahl", 12) ?: 12)
    var neu by remember { mutableStateOf(false) }
    var weg by remember { mutableStateOf<Geburtstage.G?>(null) }
    if (neu) GeburtstagNeuDialog { neu = false }
    weg?.let { g -> GeburtstagLoeschenDialog(g) { weg = null } }
    val datum = remember { SimpleDateFormat("EE d.M.", Sprache.locale) }
    val c0 = remember(jetzt / 3_600_000L) {
        Calendar.getInstance().apply { timeInMillis = jetzt; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    }
    Column(modifier.clip(bForm(18.dp)).bKarte(p, 18.dp, 0.09f).padding(horizontal = 12.dp, vertical = 6.dp)) {
        // Atelier: kein "Termine" und kein "+" - neue Termine traegt man in den Einstellungen ein
        if (liste.isEmpty()) { Text("Nichts geplant", color = p.leise, fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)); return@Column }
        BisVoll(Modifier.fillMaxWidth().weight(1f)) {
            liste.forEach { t ->
                val tage = ((t.beginn - c0) / 86_400_000L).toInt()
                val wann = when { t.beginn <= jetzt || tage <= 0 -> "heute"; tage == 1 -> "morgen"; else -> datum.format(Date(t.beginn)).replace(".,", "") }
                val g = remember(t, Geburtstage.stand) { Geburtstage.ausTermin(ctx, t) }
                Row(Modifier.fillMaxWidth()
                        .then(if (g != null) Modifier.pointerInput(g) { detectTapGestures(onLongPress = { weg = g }) } else Modifier)
                        .padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t.titel, color = p.text, fontSize = 16.sp, fontWeight = bDicke(null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(10.dp))
                    Text(wann, color = if (tage <= 1) p.akzent else p.leise, fontSize = 14.sp, maxLines = 1)
                }
            }
        }
    }
}


val LocalKompakt = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Musik-Karte: Cover, Titel, Kuenstler und drei Knoepfe. Keine Auswahl - nur steuern. */
@Composable
private fun MusikKarte(kompakt: Boolean = false, modifier: Modifier = Modifier, imBaustein: Boolean = false, mitCover: Boolean = true) {
    // immer da: ohne laufende Musik startet Play Spotifys "Heruntergeladen"
    val m = Musik.info
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val haptik = LocalHapticFeedback.current
    val recht = Musik.erlaubt
    // sofort umschalten (nicht auf die Musik-App warten), danach gilt wieder, was die App meldet
    var sofort by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(m?.spielt, m?.titel) { sofort = null }
    LaunchedEffect(sofort) { if (sofort != null) { delay(3000); sofort = null } }   // falls die App nicht reagiert
    val spielt = sofort ?: (m?.spielt == true)
    val zurueck: () -> Unit = {
        haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        Musik.taste(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }
    val leerArt = LocalBOpt.current?.s("leer") ?: ""      // Play, wenn nichts geladen ist: "" = Spotify, "eigene"
    val spiel: () -> Unit = {
        haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        sofort = !spielt
        // nichts gemeldet: je nach Einstellung die letzte Musik (Spotify) weiterspielen oder eigene Lieder (zufaellig)
        if (m == null) Musik.taste(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        else Musik.taste(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }
    val weiter: () -> Unit = {
        haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        Musik.taste(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
    }
    // Play halten + nach links / rechts ziehen = im Lied 5 s zurueck / vor (gehalten: weiter in 5-s-Schritten)
    val springen: (Int) -> Unit = { r -> Musik.spulen(r * 5000L) }
    val runter: () -> Unit = {
        haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        SpotifyHeruntergeladen.abspielen(ctx)
    }
    val ohneHinweise = imBaustein
    val unter = when {
        m != null -> m.kuenstler
        ohneHinweise -> ""
        !recht -> "Zugriff fehlt – antippen"
        imBaustein -> ""
        else -> "Play = letzte Musik · ↓ = Heruntergeladen"
    }
    // leere Flaeche der Karte antippen = Spotify oeffnen
    val appOeffnen: () -> Unit = {
        haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        ctx.packageManager.getLaunchIntentForPackage("com.spotify.music")?.let {
            try { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        } ?: Musik.oeffnen(ctx)
    }
    val oeffnen = Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { appOeffnen() }
    val unterMod = if (m == null && !recht) Modifier.clickable { (ctx as? MainActivity)?.benachrichtigungsZugriff() } else Modifier
    if (ohneHinweise) {
        val o = LocalBOpt.current
        val tf = (o?.i("tasten", 100) ?: 100) / 100f
        val mitTitel = o?.b("titel", false) == true && m != null
        // Ring um die Mitteltaste (Beat / Song) - darf ueber den Baustein hinausragen, darum dann nicht abschneiden
        val ring = o?.s("ring") ?: ""
        val mitRing = ring == "beat" || ring == "song"
        Column(
            modifier.then(if (mitRing) Modifier else Modifier.clip(bForm(18.dp))).bKarte(p, 18.dp, 0.09f).then(oeffnen).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (mitTitel) {
                Text(m!!.titel, color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(horizontal = 6.dp))
                if (m.kuenstler.isNotBlank()) Text(m.kuenstler, color = p.leise, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(horizontal = 6.dp))
            }
            val ph = (o?.i("pauseHoch", 100) ?: 100) / 100f
            val pg = (o?.i("playGr", 100) ?: 100) / 100f
            val reihe = (o?.i("reihe", 100) ?: 100) / 100f
            val mitte = (o?.i("mitte", 100) ?: 100) / 100f
            if (ring == "beat") {
                // Beat-Ring: keine eigenen Seitentasten - die langen Striche links / rechts sind Zurueck / Weiter
                val still = p.text.copy(alpha = 0.3f)
                Row(Modifier.fillMaxWidth(reihe).align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    val ohne = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(Modifier.weight(1f).height(44.dp * tf * mitte).clickable(ohne, null, onClick = zurueck))
                    Box(Modifier.musikRing(ring, spielt, p.akzent, still,
                                           (o?.i("ringAb", 125) ?: 125) / 100f, (o?.i("strichL", 100) ?: 100) / 100f, (o?.i("seitL", 100) ?: 100) / 100f, (o?.i("beatSt", 75) ?: 75) / 100f)) {
                        // Play / Pause in der Mitte (ausschaltbar, z. B. wenn die Kamera genau dort sitzt)
                        MusikTaste(44.dp * tf * mitte, null, { if (o?.b("ringSym", true) != false) SpielSymbol(spielt, p.text, ph, pg) }, spiel, springen, p.text, appOeffnen)
                    }
                    Box(Modifier.weight(1f).height(44.dp * tf * mitte).clickable(ohne, null, onClick = weiter))
                }
                return@Column
            }
            Row(Modifier.fillMaxWidth(reihe).align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                val still = p.text.copy(alpha = 0.3f)
                val seiteFarbe = if (ring == "beat") p.akzent else p.text
                Box(Modifier.musikSeite(ring, spielt, p.akzent, still)) {
                    MusikTaste(36.dp * tf, null, { SpringSymbol(false, seiteFarbe) }, zurueck)
                }
                if (mitRing) {
                    // entweder - oder: mit Ring keine alte Taste darunter (die Flaeche bleibt antippbar)
                    Box(Modifier.musikRing(ring, spielt, p.akzent, still,
                                           (o?.i("ringAb", 125) ?: 125) / 100f, (o?.i("strichL", 100) ?: 100) / 100f, (o?.i("seitL", 100) ?: 100) / 100f, (o?.i("beatSt", 75) ?: 75) / 100f)) {
                        // Play / Pause in der Mitte (ausschaltbar, z. B. wenn die Kamera genau dort sitzt)
                        MusikTaste(44.dp * tf * mitte, null, { if (o?.b("ringSym", true) != false) SpielSymbol(spielt, p.text, ph, pg) }, spiel, springen, p.text, appOeffnen)
                    }
                } else {
                    val mitteHg = o?.b("mitteHg", true) ?: true
                    MusikTaste(44.dp * tf * mitte, if (mitteHg) p.akzent else null, { SpielSymbol(spielt, if (mitteHg) p.grund else p.text, ph, pg) }, spiel, springen, p.text, appOeffnen)
                }
                Box(Modifier.musikSeite(ring, spielt, p.akzent, still)) {
                    MusikTaste(36.dp * tf, null, { SpringSymbol(true, seiteFarbe) }, weiter)
                }
            }
        }
        return
    }
    // schmal (rechts unter den Knoepfen, gleich breit wie sie): oben Cover + Titel, darunter die Tasten
    if (kompakt) {
        Column(
            modifier.clip(rund(18.dp))
                .karteFlaeche(p, 18.dp, 0.09f).then(if (Stil.typoGeld) Modifier.geldRahmen(p.akzent) else Modifier)
                .then(oeffnen)
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (mitCover) { MusikCover(m?.bild, 34.dp); Spacer(Modifier.width(8.dp)) }
                Column(Modifier.weight(1f)) {
                    if (m != null || !ohneHinweise) Text(m?.titel ?: "Musik", color = p.text, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (unter.isNotBlank()) Text(unter, color = p.leise, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = unterMod)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MusikTaste(28.dp, null, { SpringSymbol(false, p.text) }, zurueck)
                MusikTaste(36.dp, p.akzent, { SpielSymbol(spielt, p.grund) }, spiel, springen, p.text, appOeffnen)
                MusikTaste(28.dp, null, { SpringSymbol(true, p.text) }, weiter)
                if (!imBaustein) MusikTaste(24.dp, null, { Text("↓", color = p.akzent, fontSize = 16.sp, fontWeight = FontWeight.Bold) }, runter)
            }
        }
        return
    }
    // normal: alles in einer Zeile - Cover, Titel/Kuenstler, Zurueck, Play, Weiter, Heruntergeladen
    Row(
        modifier.clip(rund(18.dp))
            .karteFlaeche(p, 18.dp, 0.09f).then(if (Stil.typoGeld) Modifier.geldRahmen(p.akzent) else Modifier).then(oeffnen).padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mitCover) { MusikCover(m?.bild, 42.dp); Spacer(Modifier.width(10.dp)) }
        Column(Modifier.weight(1f)) {
            if (m != null || !ohneHinweise) Text(m?.titel ?: "Musik", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                 maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (unter.isNotBlank()) Text(unter, color = p.leise, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = unterMod)
        }
        MusikTaste(36.dp, null, { SpringSymbol(false, p.text) }, zurueck)
        MusikTaste(44.dp, p.akzent, { SpielSymbol(spielt, p.grund) }, spiel, springen, p.text, appOeffnen)
        MusikTaste(36.dp, null, { SpringSymbol(true, p.text) }, weiter)
        // jederzeit: Spotifys heruntergeladene Songs starten
        if (!imBaustein) MusikTaste(32.dp, null, { Text("↓", color = p.akzent, fontSize = 18.sp, fontWeight = FontWeight.Bold) }, runter)
    }
}

/** Cover der laufenden Musik (sonst Noten-Zeichen) */
@Composable
private fun MusikCover(bild: androidx.compose.ui.graphics.ImageBitmap?, gr: androidx.compose.ui.unit.Dp) {
    val p = LocalPalette.current
    Box(
        Modifier.size(gr).clip(rund(gr * 0.26f)).background(p.akzent.copy(alpha = 0.25f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bild != null) Image(bild, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Symbol("note", p.text, gr * 0.48f)
    }
}

/** Runder Knopf, der beim Druecken federnd kleiner wird (kein grauer Klick-Schatten) */
@Composable
private fun MusikTaste(groesse: androidx.compose.ui.unit.Dp, farbe: Color?, inhalt: @Composable () -> Unit, onClick: () -> Unit,
                       wisch: ((Int) -> Unit)? = null, pfeilFarbe: Color = Color.White, lang: (() -> Unit)? = null) {
    val quelle = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val gedrueckt by quelle.collectIsPressedAsState()
    // Play/Pause: gedrueckt halten, dann nach links / rechts = im Lied 5 s zurueck / vor (gehalten: weiter)
    var halten by remember { mutableStateOf(false) }
    var richtung by remember { mutableIntStateOf(0) }
    var drueck by remember { mutableStateOf(false) }
    var summe by remember { mutableIntStateOf(0) }           // gespult in Sekunden (Anzeige)
    val messer = androidx.compose.ui.text.rememberTextMeasurer()
    // aktuelle Aktionen (pointerInput laeuft weiter, die Lambdas aendern sich mit dem Spielstand)
    val langAktionJetzt by rememberUpdatedState(lang)
    val klickJetzt by rememberUpdatedState(onClick)
    val wischJetzt by rememberUpdatedState(wisch)
    val haptik = LocalHapticFeedback.current
    val ctxT = LocalContext.current
    val skala by androidx.compose.animation.core.animateFloatAsState(
        if (gedrueckt || drueck) 0.86f else if (halten) 1.08f else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "taste")
    val pfeile by androidx.compose.animation.core.animateFloatAsState(if (halten) 1f else 0f, tween(140), label = "pfeile")
    val bedienung = if (wisch == null) Modifier.clickable(interactionSource = quelle, indication = null, onClick = onClick)
    else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()
            drueck = true
            val langMs = 320L
            var lang = false
            var gezogen = false
            var senkrecht = false              // nach oben / unten gewischt: eigene Musik (zufaellig)
            var menueZug = false               // nach unten: das Lieder-Menue folgt dem Finger
            var menueStart = 0f
            var bewegt = false                 // Finger hat sich bewegt (dann kein Spotify)
            var offen = false                  // Spotify schon geoeffnet (ruhig gehalten)
            val spotifyMs = 600L               // so lange ruhig halten = Spotify oeffnen (ohne loszulassen)
            var naechster = 0L
            val schwelle = 22.dp.toPx()
            fun schritt(r: Int) {
                wischJetzt?.invoke(r); summe += r * 5
                haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            try {
                while (true) {
                    val jetzt = android.os.SystemClock.uptimeMillis()
                    val warte = when {
                        senkrecht || offen -> -1L
                        !lang -> langMs - (jetzt - down.uptimeMillis)
                        richtung != 0 -> naechster - jetzt
                        !bewegt && langAktionJetzt != null -> spotifyMs - (jetzt - down.uptimeMillis)
                        else -> -1L
                    }
                    val ev = if (warte < 0L) awaitPointerEvent() else withTimeoutOrNull(warte.coerceAtLeast(1L)) { awaitPointerEvent() }
                    if (ev == null) {
                        if (!lang) {
                            lang = true; halten = true; drueck = false; summe = 0
                            haptik.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (richtung != 0) {
                            // weiter gehalten: alle 0,35 s noch einmal 5 Sekunden
                            schritt(richtung); naechster = android.os.SystemClock.uptimeMillis() + 350
                        } else if (!bewegt && !offen) {
                            // ruhig gehalten: Spotify sofort oeffnen (ohne loszulassen)
                            offen = true; halten = false
                            haptik.performHapticFeedback(HapticFeedbackType.LongPress)
                            langAktionJetzt?.invoke()
                        }
                        continue
                    }
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        c.consume()
                        // Tippen = Play/Pause; alles andere (gewischt, gespult, Spotify) ist schon passiert
                        if (menueZug) EigeneMusik.menueLoslassen()
                        else if (!senkrecht && !offen && !lang) klickJetzt()
                        break
                    }
                    if (offen) { c.consume(); continue }
                    val d = c.position - down.position
                    // nach oben oder unten wischen (deutlich mehr hoch/runter als zur Seite)
                    if (menueZug) {
                        // Menue haengt am Finger: weiter runter = weiter auf, wieder hoch = wieder zu
                        EigeneMusik.menueZiehen((d.y - menueStart + 24.dp.toPx()) / 260.dp.toPx())
                        c.consume(); continue
                    }
                    if (senkrecht) { c.consume(); continue }
                    if (d.getDistance() > viewConfiguration.touchSlop) bewegt = true
                    if (!lang && d.getDistance() > viewConfiguration.touchSlop) { lang = true; halten = true; drueck = false; summe = 0 }
                    if (lang) {
                        val neu = if (d.x > schwelle) 1 else if (d.x < -schwelle) -1 else 0
                        if (neu != 0) gezogen = true
                        if (neu != richtung) {
                            richtung = neu
                            if (neu != 0) { schritt(neu); naechster = android.os.SystemClock.uptimeMillis() + 450 }
                        }
                    }
                    c.consume()
                }
            } finally { halten = false; richtung = 0; drueck = false; if (menueZug && EigeneMusik.menueZieht.value) EigeneMusik.menueLoslassen() }
        }
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.drawWithContent {
        drawContent()
        // gespult: "+15 s" ueber der Taste
        if (pfeile > 0.01f && summe != 0) {
            val t = messer.measure((if (summe > 0) "+" else "−") + kotlin.math.abs(summe) + " s",
                androidx.compose.ui.text.TextStyle(color = pfeilFarbe.copy(alpha = pfeile), fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            drawText(t, topLeft = Offset((size.width - t.size.width) / 2f, -t.size.height - 4.dp.toPx()))
        }
        // Pfeile links / rechts, solange gehalten - der in Zugrichtung leuchtet
        if (pfeile > 0.01f) for (s in listOf(-1, 1)) {
            val an = richtung == s
            val cx = size.width / 2f + s * size.width * 0.95f
            val cy = size.height / 2f
            val k = size.minDimension * 0.16f
            val a = pfeile * (if (an) 1f else 0.35f)
            for (j in 0..1) {
                val x = cx + s * j * k * 0.9f
                drawLine(pfeilFarbe.copy(alpha = a), Offset(x - s * k * 0.5f, cy - k), Offset(x + s * k * 0.5f, cy), 2.2.dp.toPx(), StrokeCap.Round)
                drawLine(pfeilFarbe.copy(alpha = a), Offset(x + s * k * 0.5f, cy), Offset(x - s * k * 0.5f, cy + k), 2.2.dp.toPx(), StrokeCap.Round)
            }
        }
    }) {
        Box(
            Modifier.size(groesse)
                .graphicsLayer { scaleX = skala; scaleY = skala }
                .clip(CircleShape)
                .then(if (farbe != null) Modifier.background(farbe) else Modifier)
                .then(bedienung),
            contentAlignment = Alignment.Center,
        ) { inhalt() }
    }
}

/** Play/Pause sauber gezeichnet; beim Wechsel weich ueberblendet */
@Composable
private fun SpielSymbol(spielt: Boolean, farbe: Color, pauseHoch: Float = 1f, playGr: Float = 1f) {
    val t by androidx.compose.animation.core.animateFloatAsState(if (spielt) 1f else 0f, tween(160), label = "spiel")
    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        val r = s * 0.07f
        // Pause: zwei runde Balken (Hoehe einstellbar - ragen auch ueber die Mitte hinaus, z. B. um eine Kamera herum)
        if (t > 0.01f) {
            val b = s * 0.22f; val hoch = s * 0.72f * pauseHoch; val y = (s - hoch) / 2
            val c = farbe.copy(alpha = farbe.alpha * t)
            drawRoundRect(c, Offset(s * 0.22f, y), androidx.compose.ui.geometry.Size(b, hoch), androidx.compose.ui.geometry.CornerRadius(r * 1.4f))
            drawRoundRect(c, Offset(s * 0.56f, y), androidx.compose.ui.geometry.Size(b, hoch), androidx.compose.ui.geometry.CornerRadius(r * 1.4f))
        }
        // Play: Dreieck, optisch zentriert (etwas nach rechts), mit runden Ecken
        if (t < 0.99f) {
            val g = playGr; val cx = s * 0.58f; val cy = s * 0.5f
            fun px(x: Float) = cx + (x - 0.58f) * s * g
            fun py(y: Float) = cy + (y - 0.5f) * s * g
            val pfad = androidx.compose.ui.graphics.Path().apply {
                moveTo(px(0.30f), py(0.16f)); lineTo(px(0.86f), py(0.5f)); lineTo(px(0.30f), py(0.84f)); close()
            }
            drawPath(pfad, farbe.copy(alpha = farbe.alpha * (1 - t)))
            drawPath(pfad, farbe.copy(alpha = farbe.alpha * (1 - t)),
                     style = Stroke(width = r * 1.6f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

/** Zurueck/Vor: Dreieck mit Strich, runde Ecken */
@Composable
private fun SpringSymbol(vor: Boolean, farbe: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(18.dp).graphicsLayer { if (!vor) scaleX = -1f }) {
        val s = size.minDimension
        val pfad = androidx.compose.ui.graphics.Path().apply {
            moveTo(s * 0.14f, s * 0.18f); lineTo(s * 0.70f, s * 0.5f); lineTo(s * 0.14f, s * 0.82f); close()
        }
        drawPath(pfad, farbe)
        drawPath(pfad, farbe, style = Stroke(width = s * 0.09f, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawRoundRect(farbe, Offset(s * 0.74f, s * 0.16f), androidx.compose.ui.geometry.Size(s * 0.13f, s * 0.68f),
                      androidx.compose.ui.geometry.CornerRadius(s * 0.07f))
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun ZahlKnopf(text: String, zahl: Int, an: Boolean, modifier: Modifier = Modifier, onLang: (() -> Unit)? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    val haptik = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier.clip(bForm(18.dp))
            .then(if (an) Modifier.background(p.akzent.copy(alpha = 0.28f)) else Modifier.bKarte(p, 18.dp, 0.09f)).then(if (Stil.typoGeld) Modifier.geldRahmen(p.akzent) else Modifier)
            .combinedClickable(onLongClick = if (onLang != null && zahl > 0) {
                { haptik.performHapticFeedback(HapticFeedbackType.LongPress); onLang() }
            } else null) { onClick() }
            .padding(horizontal = 14.dp, vertical = if (LocalKompakt.current) 9.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1,
             overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (zahl > 0) Box(
            Modifier.size(22.dp).clip(CircleShape).background(p.akzent),
            contentAlignment = Alignment.Center,
        ) { Text(if (zahl > 9) "9+" else "$zahl", color = p.grund, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
    }
}

/**
 * Die letzten Chats einer Messenger-App (aus den Unterhaltungs-Kurzbefehlen, die die App an Android meldet).
 * Neue, ungelesene Nachrichten (aus den Benachrichtigungen) stehen fett mit Text darunter.
 */
@Composable
private fun ChatListe(name: String, pakete: List<String>, post: Postfach, ohneIcons: Boolean, jetzt: Long) {
    val ctx = LocalContext.current
    val akt = ctx as? MainActivity
    val meldungen = post.meldungen
    // Kandidaten: Kurzbefehle der App (mit Bild, zum direkten Oeffnen) + selbst gemerkter Verlauf.
    // Sortiert nach "zuletzt etwas los" - Chats ohne Verlauf behalten die Reihenfolge der App dahinter.
    val kontakte by produceState<List<Kurzbefehl>?>(null, meldungen) {
        value = withContext(Dispatchers.IO) {
            val kurz = pakete.flatMap { p -> akt?.kontakte(p, 15) ?: emptyList() }
            val zeiten = ChatVerlauf.lesen(ctx, pakete)
            val namen = LinkedHashMap<String, Pair<Kurzbefehl?, String>>()
            kurz.forEach { k -> val n = ChatVerlauf.name(k.name); namen.putIfAbsent(n.lowercase(), k to n) }
            zeiten.forEach { (k, v) -> if (k !in namen) namen[k] = null to v.first }
            val rang = namen.keys.withIndex().associate { it.value to it.index }
            namen.entries
                .sortedWith(compareByDescending<Map.Entry<String, Pair<Kurzbefehl?, String>>> { zeiten[it.key]?.second ?: 0L }
                    .thenBy { rang[it.key] ?: 0 })
                .take(5)
                .map { (_, v) -> v.first ?: Kurzbefehl(Unit, v.second, null) }
        }
    }
    Ueberschrift(name)
    val liste = kontakte
    when {
        liste == null -> Hinweis("Lädt …")
        liste.isEmpty() && meldungen.isEmpty() -> Hinweis(
            if (akt?.istStandard() != true) "Geht nur, wenn der Launcher der Startbildschirm ist"
            else "Keine Chats gefunden – $name einmal öffnen und zurück"
        )
    }
    liste?.forEach { k ->
        val neu = meldungen.firstOrNull { ChatVerlauf.name(it.titel).equals(ChatVerlauf.name(k.name), ignoreCase = true) }
        val bild = k.bild
        MeldungsZeile(
            ohneIcons = ohneIcons,
            symbol = {
                if (bild != null) Image(bild, contentDescription = null, contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(38.dp).clip(CircleShape))
                else Kreis(k.name, neu != null)
            },
            titel = k.name,
            unter = neu?.let { (if (it.text.isNotEmpty()) it.text + "  ·  " else "") + seit(jetzt - it.zeit) } ?: "",
            fett = neu != null,
            // Kurzbefehl oeffnet zuverlaessig genau diesen Chat (auch bei neuen Nachrichten)
            onClick = {
                ChatVerlauf.merken(ctx, pakete.first(), k.name, System.currentTimeMillis())
                when {
                    k.info !is Unit && akt != null -> akt.kurzbefehlStarten(k)
                    neu != null -> post.oeffnen(ctx, neu)
                    // nur aus dem Verlauf bekannt: Chat per Namen suchen, sonst App oeffnen
                    else -> {
                        val gefunden = pakete.firstNotNullOfOrNull { p -> akt?.kontaktFinden(p, k.name) }
                        if (gefunden != null && akt != null) akt.kurzbefehlStarten(gefunden)
                        else pakete.firstNotNullOfOrNull { p -> ctx.packageManager.getLaunchIntentForPackage(p) }
                            ?.let { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
            },
        )
    }
    // neue Nachrichten von Chats, die nicht unter den letzten 5 sind
    if (liste != null) meldungen.filter { m -> liste.none { it.name.equals(m.titel, ignoreCase = true) } }.forEach { m ->
        MeldungsZeile(
            ohneIcons = ohneIcons,
            symbol = { Kreis(m.titel, true) },
            titel = m.titel,
            unter = (if (m.text.isNotEmpty()) m.text + "  ·  " else "") + seit(jetzt - m.zeit),
            fett = true,
            onClick = { post.oeffnen(ctx, m) },
        )
    }
}

/** kleine Ueberschrift wie die Buchstaben in der Suchliste */
@Composable
private fun Ueberschrift(text: String, erste: Boolean = false) {
    if (Stil.typoGeld) {
        val p = LocalPalette.current
        Row(Modifier.padding(top = if (erste) 0.dp else if (LocalKompakt.current) 10.dp else 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            GeldZier(p.akzent)
            Spacer(Modifier.width(8.dp))
            Text(text.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                 letterSpacing = 2.2.sp, style = schattenStil())
        }
        return
    }
    if (Stil.typoSwiss) {
        // Swiss: roter Balken + Versalien mit Sperrung
        val p = LocalPalette.current
        Row(Modifier.padding(top = if (erste) 0.dp else if (LocalKompakt.current) 10.dp else 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 13.dp).background(p.akzent))
            Spacer(Modifier.width(8.dp))
            Text(text.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                 letterSpacing = 1.6.sp, style = schattenStil())
        }
        return
    }
    Text(text, color = LocalPalette.current.leise, fontSize = 13.sp, fontWeight = FontWeight.Medium,
         style = schattenStil(), modifier = Modifier.padding(top = if (erste) 0.dp else if (LocalKompakt.current) 10.dp else 16.dp, bottom = 2.dp))
}

@Composable
private fun Hinweis(text: String, warnung: Boolean = false) {
    val p = LocalPalette.current
    Text(text, color = if (warnung) p.warnung else p.leise, fontSize = 15.sp, style = schattenStil(),
         modifier = Modifier.padding(vertical = 10.dp))
}

/** runder Anfangsbuchstabe als Symbol (fuer Absender ohne App-Icon) */
@Composable
private fun Kreis(name: String, hervor: Boolean) {
    val p = LocalPalette.current
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(if (hervor) p.akzent.copy(alpha = 0.85f) else p.text.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
             color = if (hervor) p.grund else p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/** Eine Zeile wie eine App in der Liste: Symbol + grosser Name, darunter eine leise Zeile */
@Composable
private fun MeldungsZeile(
    ohneIcons: Boolean,
    symbol: @Composable () -> Unit,
    titel: String,
    unter: String,
    titelFarbe: Color = LocalPalette.current.text,
    durchgestrichen: Boolean = false,
    fett: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!ohneIcons) { symbol(); Spacer(Modifier.width(18.dp)) }
        Column(Modifier.weight(1f)) {
            Text(titel, color = titelFarbe, fontSize = 23.sp,
                 fontWeight = if (fett) FontWeight.Medium else FontWeight.Normal,
                 textDecoration = if (durchgestrichen) TextDecoration.LineThrough else null,
                 maxLines = 1, overflow = TextOverflow.Ellipsis, style = schattenStil())
            if (unter.isNotBlank()) Text(unter, color = p.leise, fontSize = 14.sp, maxLines = 1,
                                         overflow = TextOverflow.Ellipsis, style = schattenStil())
        }
    }
}

private fun seit(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 1 -> "jetzt"
        min < 60 -> "$min min"
        min < 24 * 60 -> "${min / 60} h"
        else -> "${min / (24 * 60)} T"
    }
}
