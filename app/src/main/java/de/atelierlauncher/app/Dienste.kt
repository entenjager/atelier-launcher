package de.atelierlauncher.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

// ============================================================================
//  Kalender, Wetter (Open-Meteo, ohne Konto) und Musik (laufende Wiedergabe)
// ============================================================================

// ---------------------------------------------------------------- Kalender
data class Termin(
    val id: Long, val titel: String, val beginn: Long, val ende: Long,
    val ganztag: Boolean, val farbe: Int,
)

object Kalender {
    var termine by mutableStateOf<List<Termin>>(emptyList())
    var erlaubt by mutableStateOf(false)
    @Volatile private var laedt = false

    fun hatRecht(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** Termine der naechsten 14 Tage (im Hintergrund) */
    fun laden(ctx: Context) {
        erlaubt = hatRecht(ctx)
        if (!erlaubt) { termine = emptyList(); return }
        if (laedt) return
        laedt = true
        val app = ctx.applicationContext
        Thread {
            try {
                val jetzt = System.currentTimeMillis()
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    ContentUris.appendId(it, jetzt - 86_400_000L)
                    ContentUris.appendId(it, jetzt + 14 * 86_400_000L)
                }.build()
                val spalten = arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.DISPLAY_COLOR,
                )
                val liste = ArrayList<Termin>()
                app.contentResolver.query(uri, spalten, "${CalendarContract.Instances.VISIBLE}=1", null,
                    "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val ganztag = c.getInt(4) == 1
                        val ende = c.getLong(3)
                        // ganztaegige Termine sind in UTC gespeichert
                        val vorbei = if (ganztag) ende - java.util.TimeZone.getDefault().getOffset(jetzt) <= jetzt else ende <= jetzt
                        if (vorbei) continue
                        liste += Termin(c.getLong(0), c.getString(1) ?: "(ohne Titel)", c.getLong(2), ende, ganztag, c.getInt(5))
                        if (liste.size >= 30) break
                    }
                }
                Handler(Looper.getMainLooper()).post { termine = liste }
            } catch (e: Exception) {
            } finally { laedt = false }
        }.start()
    }

    fun oeffnen(ctx: Context, t: Termin) {
        val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, t.id))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, t.beginn)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, t.ende)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(i) } catch (e: Exception) { }
    }
}

// ---------------------------------------------------------------- Wetter
data class WetterTag(val datum: String, val code: Int, val max: Double, val min: Double)
data class WetterDaten(
    val ort: String, val temp: Double, val code: Int, val tag: Boolean,
    val tage: List<WetterTag>, val zeit: Long,
)

enum class WetterArt { SONNE, MOND, HEITER, WOLKE, NEBEL, REGEN, SCHNEE, GEWITTER }

object Wetter {
    var daten by mutableStateOf<WetterDaten?>(null)
    val version = mutableIntStateOf(0)
    @Volatile private var laedt = false
    @Volatile private var ladeOrt: String? = null
    private const val ALTER = 30 * 60_000L

    /** Nach einem Ortswechsel: sofort neu holen */
    fun erzwingen() { daten = null; version.intValue++ }

    fun laden(ctx: Context, ort: Ort?) {
        if (ort == null) { daten = null; return }
        val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        if (daten == null) prefs.getString("wetter", null)?.let { s ->
            val alt = try { lies(JSONObject(s)) } catch (e: Exception) { null }
            if (alt != null && alt.ort == ort.name) daten = alt
        }
        val d = daten
        if (d != null && d.ort == ort.name && System.currentTimeMillis() - d.zeit < ALTER) return
        if (laedt && ladeOrt == ort.name) return
        laedt = true
        ladeOrt = ort.name
        Thread {
            try {
                val url = String.format(Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                    "&current=temperature_2m,weather_code,is_day" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5",
                    ort.lat, ort.lon)
                val o = JSONObject(holen(url))
                val cur = o.getJSONObject("current")
                val dl = o.getJSONObject("daily")
                val zeiten = dl.getJSONArray("time")
                val tage = (0 until zeiten.length()).map { i ->
                    WetterTag(zeiten.getString(i), dl.getJSONArray("weather_code").optInt(i),
                              dl.getJSONArray("temperature_2m_max").optDouble(i), dl.getJSONArray("temperature_2m_min").optDouble(i))
                }
                val neu = WetterDaten(ort.name, cur.optDouble("temperature_2m"), cur.optInt("weather_code"),
                                      cur.optInt("is_day", 1) == 1, tage, System.currentTimeMillis())
                // inzwischen anderer Ort gewaehlt? Dann diese Antwort wegwerfen
                if (ladeOrt == ort.name) {
                    prefs.edit().putString("wetter", schreib(neu).toString()).apply()
                    Handler(Looper.getMainLooper()).post { if (ladeOrt == ort.name || ladeOrt == null) daten = neu }
                }
            } catch (e: Exception) {
            } finally { if (ladeOrt == ort.name) { laedt = false; ladeOrt = null } }
        }.start()
    }

    /** Ortssuche (blockiert - im Hintergrund aufrufen) */
    fun suchen(name: String): List<Ort> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=de&format=json&name=" +
            URLEncoder.encode(name.trim(), "UTF-8")
        val a = JSONObject(holen(url)).optJSONArray("results") ?: return emptyList()
        return (0 until a.length()).map { i ->
            val r = a.getJSONObject(i)
            val zusatz = listOf(r.optString("admin1"), r.optString("country")).filter { it.isNotBlank() }.joinToString(", ")
            Ort(r.optString("name") + if (zusatz.isNotEmpty()) " ($zusatz)" else "", r.optDouble("latitude"), r.optDouble("longitude"))
        }
    }

    private fun holen(adresse: String): String {
        val v = URL(adresse).openConnection() as HttpURLConnection
        v.connectTimeout = 8000; v.readTimeout = 8000
        try { return v.inputStream.bufferedReader().readText() } finally { v.disconnect() }
    }

    private fun schreib(d: WetterDaten) = JSONObject()
        .put("ort", d.ort).put("temp", d.temp).put("code", d.code).put("tag", d.tag).put("zeit", d.zeit)
        .put("tage", JSONArray(d.tage.map { JSONObject().put("d", it.datum).put("c", it.code).put("max", it.max).put("min", it.min) }))

    private fun lies(o: JSONObject): WetterDaten {
        val a = o.getJSONArray("tage")
        return WetterDaten(o.getString("ort"), o.getDouble("temp"), o.getInt("code"), o.optBoolean("tag", true),
            (0 until a.length()).map { val t = a.getJSONObject(it); WetterTag(t.getString("d"), t.getInt("c"), t.getDouble("max"), t.getDouble("min")) },
            o.getLong("zeit"))
    }

    /** WMO-Wettercode -> Text */
    fun text(code: Int) = when (code) {
        0 -> "Klar"; 1 -> "Überwiegend klar"; 2 -> "Teilweise bewölkt"; 3 -> "Bedeckt"
        45, 48 -> "Nebel"
        51, 53, 55, 56, 57 -> "Nieselregen"
        61, 63, 65, 66, 67 -> "Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81, 82 -> "Regenschauer"
        85, 86 -> "Schneeschauer"
        95, 96, 99 -> "Gewitter"
        else -> ""
    }

    fun art(code: Int, tag: Boolean = true) = when (code) {
        0, 1 -> if (tag) WetterArt.SONNE else WetterArt.MOND
        2 -> WetterArt.HEITER
        3 -> WetterArt.WOLKE
        45, 48 -> WetterArt.NEBEL
        71, 73, 75, 77, 85, 86 -> WetterArt.SCHNEE
        95, 96, 99 -> WetterArt.GEWITTER
        in 51..67, 80, 81, 82 -> WetterArt.REGEN
        else -> WetterArt.WOLKE
    }
}

// ---------------------------------------------------------------- Musik
data class MusikInfo(val titel: String, val kuenstler: String, val bild: ImageBitmap?, val spielt: Boolean, val paket: String)

/**
 * Liest die gerade laufende Wiedergabe (Spotify, YouTube Music, ...).
 * Android erlaubt das nur mit "Benachrichtigungszugriff" fuer den Launcher.
 */
object Musik {
    var info by mutableStateOf<MusikInfo?>(null)
    var erlaubt by mutableStateOf(false)
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var letztesBild: Pair<String, ImageBitmap?>? = null
    private var coverArbeit: String? = null

    private val rueckruf = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = aktualisieren()
        override fun onMetadataChanged(metadata: MediaMetadata?) = aktualisieren()
        override fun onSessionDestroyed() {
            try { waehle(msm?.let { m -> komponente?.let { m.getActiveSessions(it) } }) }
            catch (e: Exception) { controller = null; info = null }   // z. B. Zugriff inzwischen entzogen
        }
    }
    private val sitzungen = MediaSessionManager.OnActiveSessionsChangedListener { waehle(it) }
    private var komponente: ComponentName? = null

    fun hatRecht(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    fun start(ctx: Context) {
        erlaubt = hatRecht(ctx)
        if (!erlaubt) { stop(); info = null; return }
        try {
            val m = msm ?: ctx.applicationContext.getSystemService(MediaSessionManager::class.java) ?: return
            val k = ComponentName(ctx, Lauscher::class.java)
            msm = m; komponente = k
            m.removeOnActiveSessionsChangedListener(sitzungen)
            m.addOnActiveSessionsChangedListener(sitzungen, k)
            waehle(m.getActiveSessions(k))
        } catch (e: SecurityException) {
            erlaubt = false; info = null
        } catch (e: Exception) { }
    }

    fun stop() {
        try { msm?.removeOnActiveSessionsChangedListener(sitzungen) } catch (e: Exception) { }
        controller?.unregisterCallback(rueckruf)
        controller = null
    }

    private fun waehle(liste: List<MediaController>?) {
        // lieber eine Sitzung mit Titel (manche Apps melden leere Sitzungen) - spielende zuerst
        fun hatTitel(c: MediaController) = !(c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: c.metadata?.description?.title?.toString()).isNullOrBlank()
        val neu = liste?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && hatTitel(it) }
            ?: liste?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: liste?.firstOrNull { hatTitel(it) } ?: liste?.firstOrNull()
        if (neu?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(rueckruf)
            controller = neu
            neu?.registerCallback(rueckruf, Handler(Looper.getMainLooper()))
        }
        aktualisieren()
    }

    private fun aktualisieren() {
        val c = controller
        val md = c?.metadata
        val titel = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md?.description?.title?.toString()
        if (c == null || titel.isNullOrBlank()) { info = null; return }
        val kuenstler = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: md?.description?.subtitle?.toString() ?: ""
        val schluessel = c.packageName + titel
        val bild = letztesBild?.takeIf { it.first == schluessel }?.second ?: run {
            // Cover verkleinern im Hintergrund (lag beim Songwechsel auf dem Haupt-Faden = Hänger, gemessen).
            // Solange: altes Cover weiter zeigen, danach neu anzeigen.
            val b = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (b == null) { letztesBild = schluessel to null; null }
            else {
                if (coverArbeit != schluessel) {
                    coverArbeit = schluessel
                    Thread {
                        val klein = try {
                            val f = 256f / maxOf(b.width, b.height)
                            (if (f < 1f) Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true) else b)
                                .also { it.prepareToDraw() }.asImageBitmap()
                        } catch (e: Exception) { null }
                        Handler(Looper.getMainLooper()).post {
                            if (coverArbeit == schluessel) { letztesBild = schluessel to klein; coverArbeit = null; aktualisieren() }
                        }
                    }.start()
                }
                info?.bild           // bis dahin das bisherige Cover
            }
        }
        val st = c.playbackState?.state
        val spielt = st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING
        info = MusikInfo(titel, kuenstler, bild, spielt, c.packageName)
    }

    /** im Lied spulen (ms, + vor / - zurueck). Mehrere Schritte kurz hintereinander bauen aufeinander auf. */
    private var spulZiel = -1L; private var spulZeit = 0L
    fun spulen(ms: Long) {
        val c = controller ?: return
        val ps = c.playbackState ?: return
        val jetzt = android.os.SystemClock.elapsedRealtime()
        val spielt = ps.state == PlaybackState.STATE_PLAYING
        var pos = if (spulZiel >= 0 && jetzt - spulZeit < 2500) spulZiel + (if (spielt) jetzt - spulZeit else 0L)
                  else ps.position + (if (spielt && ps.lastPositionUpdateTime > 0) ((jetzt - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong() else 0L)
        val dauer = try { c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L } catch (e: Exception) { 0L }
        pos = (pos + ms).coerceAtLeast(0L)
        if (dauer > 0) pos = pos.coerceAtMost(dauer - 1000L)
        spulZiel = pos; spulZeit = jetzt
        try { c.transportControls.seekTo(pos) } catch (e: Exception) { }
    }

    /** wie weit der Song ist (0..1) - null, wenn die App keine Laenge meldet */
    fun fortschritt(): Float? {
        val c = controller ?: return null
        val dauer = try { c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L } catch (e: Exception) { 0L }
        if (dauer <= 0L) return null
        val ps = c.playbackState ?: return null
        var pos = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0)
            pos += ((android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        return (pos.toFloat() / dauer).coerceIn(0f, 1f)
    }

    fun spielenPause() {
        val c = controller ?: return
        if (info?.spielt == true) c.transportControls.pause() else c.transportControls.play()
    }
    fun weiter() { controller?.transportControls?.skipToNext() }
    fun zurueck() { controller?.transportControls?.skipToPrevious() }

    /**
     * Ohne aktive Wiedergabe: Medientaste an Android schicken. Android gibt sie an die
     * zuletzt benutzte Musik-App weiter (z. B. Spotify spielt dort weiter, wo es war).
     */
    fun taste(ctx: Context, code: Int) {
        // kurz danach neu nachsehen, welche App spielt (Spotify meldet sich nach dem Start oft erst spaeter)
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).postDelayed({ if (Sichtbar.an || SperrSeite.sichtbar.value) start(app) }, 700)
        Handler(Looper.getMainLooper()).postDelayed({ if (Sichtbar.an || SperrSeite.sichtbar.value) start(app) }, 2000)
        val c = controller
        if (c != null) {
            when (code) {
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> return weiter()
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> return zurueck()
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> { try { c.transportControls.play() } catch (e: Exception) { }; return }
                android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> { try { c.transportControls.pause() } catch (e: Exception) { }; return }
                else -> return spielenPause()
            }
        }
        try {
            val a = ctx.getSystemService(android.media.AudioManager::class.java) ?: return
            a.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            a.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        } catch (e: Exception) { }
        // kurz danach neu nachsehen, welche App jetzt spielt
        Handler(Looper.getMainLooper()).postDelayed({ try { start(ctx) } catch (e: Exception) { } }, 700)
    }

    fun oeffnen(ctx: Context) {
        val c = controller ?: return
        // direkt die Musik-App starten (zuverlaessig, auch ab Android 14)
        ctx.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            try { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        try { c.sessionActivity?.send() } catch (e: Exception) { }
    }
}

/** Dienst fuer Benachrichtigungs-Zugriff: noetig, damit Android dem Launcher die laufende Musik verraet */
class Lauscher : NotificationListenerService() {
    override fun onListenerConnected() {
        verbunden = this
        try { allesLesen(this, activeNotifications) } catch (e: Exception) { }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        empfangen++
        if (sammeln(this, "whatsapp")) try { WhatsAppPost.neu(this, sbn) } catch (e: Exception) { }
        if (sammeln(this, "signal")) try { SignalPost.neu(this, sbn) } catch (e: Exception) { }
        try { AppPost.neu(this, sbn) } catch (e: Exception) { }
        try { BenachrichtigungsWelle.neu(this, sbn) } catch (e: Exception) { }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        WhatsAppPost.weg(sbn.key); SignalPost.weg(sbn.key); AppPost.weg(sbn.key)
    }
    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
    }
    override fun onListenerDisconnected() { if (verbunden === this) verbunden = null }
    override fun onDestroy() { if (verbunden === this) verbunden = null; super.onDestroy() }

    companion object {
        /** Laufender Dienst - ueber ihn darf der Launcher "Nicht stoeren" direkt schalten */
        @Volatile var verbunden: Lauscher? = null
        fun sammeln(ctx: android.content.Context, typ: String) = SeitenRaster.hat(ctx, typ)
        fun allesLesen(ctx: android.content.Context, alle: Array<StatusBarNotification>?) {
            if (sammeln(ctx, "whatsapp")) WhatsAppPost.alleLesen(ctx, alle)
            if (sammeln(ctx, "signal")) SignalPost.alleLesen(ctx, alle)
            AppPost.alleLesen(ctx, alle)
        }
        /** Diagnose: wie viele Benachrichtigungen seit dem Start angekommen sind */
        @Volatile var empfangen = 0
    }
}
