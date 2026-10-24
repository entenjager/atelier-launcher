package de.atelierlauncher.app

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================================================================
//  Atelier-Bausteine: Aktions-Knoepfe (Taschenlampe, Nicht stoeren, Ton, Webseite, Kamera, WLAN, Bluetooth)
//  und Anzeigen (Uhr, Akku, naechster Termin, Notiz). Alles im Stil des Designs (Karte, Akzentfarbe).
// ============================================================================

private fun starten(ctx: Context, i: Intent) {
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { Toast.makeText(ctx, Sprache.tr("Geht auf diesem Handy nicht"), Toast.LENGTH_SHORT).show() }
}

/** System-Meldung (z. B. Akku, WLAN) beobachten, solange der Baustein zu sehen ist */
@Composable
internal fun Empfang(vararg aktionen: String, beiStart: Boolean = true, onEmpfang: (Intent?) -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(*aktionen) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { onEmpfang(i) } }
        val f = IntentFilter().apply { aktionen.forEach { addAction(it) } }
        val klebt = try { ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED) } catch (e: Exception) { null }
        if (beiStart) onEmpfang(klebt)
        onDispose { try { ctx.unregisterReceiver(r) } catch (e: Exception) { } }
    }
}

/** kleine Karte: Symbol, darunter (wenn Platz) der Name. an = Akzentfarbe */
@Composable
private fun KnopfKarte(b: Baustein, modifier: Modifier, symbol: String, name: String, an: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val o = LocalBOpt.current
    val farbe = if (an) p.aufAkzent else p.text
    // Eigenschaften: Beschriftung an/aus, Symbolgroesse, Symbol an/aus (Webseite)
    val gross = (b.h >= 2 || b.b >= 2) && (o?.b("beschr", true) ?: true)
    val mitSymbol = o?.b("symbol", true) ?: true
    val sf = (o?.i("symGr", 100) ?: 100) / 100f
    if (!gross) {
        // klein (1x1 ...): nur das Symbol, so gross wie das Feld es zulaesst
        BoxWithConstraints(modifier.clip(bForm(14.dp))
            .then(if (an) Modifier.background(p.akzent) else Modifier.bKarte(p, 14.dp, 0.09f))
            .clickable(onClick = onClick).padding(2.dp), contentAlignment = Alignment.Center) {
            KleinSymbol(symbol, farbe, minOf(minOf(maxWidth, maxHeight) * 0.66f * sf, 34.dp * sf))
        }
        return
    }
    Column(
        modifier.clip(bForm(18.dp))
            .then(if (an) Modifier.background(p.akzent) else Modifier.bKarte(p, 18.dp, 0.09f))
            .clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (gross && b.b >= 2 && b.h == 1) Row(verticalAlignment = Alignment.CenterVertically) {
            if (mitSymbol) { KleinSymbol(symbol, farbe, 24.dp * sf); Spacer(Modifier.width(8.dp)) }
            Text(name, color = farbe, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            if (mitSymbol || !gross) KleinSymbol(symbol, farbe, (if (gross) 34.dp else 26.dp) * sf)
            if (gross) {
                if (mitSymbol) Spacer(Modifier.height(4.dp))
                Text(name, color = farbe, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

// ---------------------------------------------------------------- Aktions-Knoepfe
@Composable
internal fun AktionBaustein(b: Baustein, modifier: Modifier) {
    when (b.typ) {
        "lampe" -> LampeKnopf(b, modifier)
        "ruhe" -> RuheKnopf(b, modifier)
        "ton" -> TonKnopf(b, modifier)
        "webseite" -> WebseiteKnopf(b, modifier)
        "kamera" -> {
            val ctx = LocalContext.current
            KnopfKarte(b, modifier, "kamera", b.titel.ifBlank { "Kamera" }, false) { starten(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)) }
        }
        "wlan" -> WlanKnopf(b, modifier)
        "bluetooth" -> BluetoothKnopf(b, modifier)
    }
}

@Composable
private fun LampeKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val cm = remember { ctx.getSystemService(CameraManager::class.java) }
    val id = remember {
        try { cm?.cameraIdList?.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
        catch (e: Exception) { null }
    }
    var an by remember { mutableStateOf(false) }
    DisposableEffect(id) {
        val cb = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) an = enabled }
        }
        try { cm?.registerTorchCallback(cb, Handler(Looper.getMainLooper())) } catch (e: Exception) { }
        onDispose { try { cm?.unregisterTorchCallback(cb) } catch (e: Exception) { } }
    }
    KnopfKarte(b, modifier, "lampe", b.titel.ifBlank { "Taschenlampe" }, an) {
        if (id == null) { Toast.makeText(ctx, Sprache.tr("Keine Taschenlampe gefunden"), Toast.LENGTH_SHORT).show(); return@KnopfKarte }
        try { cm?.setTorchMode(id, !an) } catch (e: Exception) { Toast.makeText(ctx, Sprache.tr("Kamera ist gerade belegt"), Toast.LENGTH_SHORT).show() }
    }
}

/** darf der Launcher "Nicht stoeren" / Lautlos schalten? Sonst die Android-Seite dafuer oeffnen */
internal fun ruheErlaubt(ctx: Context) =
    ctx.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true

internal fun ruheErlaubnisHolen(ctx: Context) {
    Toast.makeText(ctx, Sprache.tr("„${ctx.applicationInfo.loadLabel(ctx.packageManager)}“ erlauben"), Toast.LENGTH_LONG).show()
    starten(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
}

@Composable
private fun RuheKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val nm = remember { ctx.getSystemService(NotificationManager::class.java) }
    var an by remember { mutableStateOf(false) }
    fun lesen() { an = (nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL) > NotificationManager.INTERRUPTION_FILTER_ALL }
    Empfang(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) { lesen() }
    // beim Zurueckkommen in den Launcher neu lesen (die Meldung kommt nicht immer an)
    val z = LocalZustand.current
    LaunchedEffect(z.fortsetzen) { lesen() }
    KnopfKarte(b, modifier, "mond", b.titel.ifBlank { "Nicht stören" }, an) {
        if (nm == null) return@KnopfKarte
        if (!ruheErlaubt(ctx)) { ruheErlaubnisHolen(ctx); return@KnopfKarte }
        val neu = !an
        an = neu                    // sofort zeigen, Android meldet sich danach
        if (!RuheSchalter.setzen(ctx, nm, neu)) {
            an = !neu
            Toast.makeText(ctx, Sprache.tr("Android hat „Nicht stören“ nicht umgeschaltet"), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Nicht stoeren schalten. Seit Android 15 wirkt setInterruptionFilter fuer neue Apps nur noch eingeschraenkt -
 * deshalb ueber eine eigene Regel ("Atelier – Nicht stören"), sonst direkt.
 */
internal object RuheSchalter {
    private val BEDINGUNG: Uri = Uri.parse("atelier://nicht-stoeren")
    private fun regel(ctx: Context, nm: NotificationManager): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val ip = ctx.getSharedPreferences("intern", Context.MODE_PRIVATE)
        var id = ip.getString("ruheKnopfRegel", null)
        if (id != null && nm.getAutomaticZenRule(id) == null) id = null
        if (id == null) {
            id = nm.addAutomaticZenRule(android.app.AutomaticZenRule("Nicht stören (Knopf)", null,
                android.content.ComponentName(ctx, MainActivity::class.java), BEDINGUNG, null,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true))
            ip.edit().putString("ruheKnopfRegel", id).apply()
        }
        return id
    }
    private fun aktiv(nm: NotificationManager) = nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

    fun setzen(ctx: Context, nm: NotificationManager, an: Boolean): Boolean {
        try {
            val id = regel(ctx, nm)
            if (id != null) nm.setAutomaticZenRuleState(id, android.service.notification.Condition(BEDINGUNG, "Knopf",
                if (an) android.service.notification.Condition.STATE_TRUE else android.service.notification.Condition.STATE_FALSE))
        } catch (e: Exception) { }
        // hat es nicht geklappt (oder war Nicht stoeren von woanders an): direkt
        if (aktiv(nm) != an) try {
            nm.setInterruptionFilter(if (an) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (e: Exception) { }
        return true
    }
}

@Composable
private fun TonKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var modus by remember { mutableIntStateOf(AudioManager.RINGER_MODE_NORMAL) }
    Empfang(AudioManager.RINGER_MODE_CHANGED_ACTION) { modus = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL }
    val (symbol, name) = when (modus) {
        AudioManager.RINGER_MODE_VIBRATE -> "vibration" to "Vibration"
        AudioManager.RINGER_MODE_SILENT -> "lautlos" to "Lautlos"
        else -> "glocke" to "Ton"
    }
    // Ton -> Vibration -> Lautlos -> Ton
    KnopfKarte(b, modifier, symbol, name, modus != AudioManager.RINGER_MODE_NORMAL) {
        val neu = when (modus) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> AudioManager.RINGER_MODE_SILENT
            else -> AudioManager.RINGER_MODE_NORMAL
        }
        try { am?.ringerMode = neu; modus = am?.ringerMode ?: neu }
        catch (e: SecurityException) { ruheErlaubnisHolen(ctx) }
        catch (e: Exception) { }
    }
}

/** Adresse wie "wikipedia.org" -> https://wikipedia.org */
internal fun webAdresse(roh: String): String {
    val t = roh.trim()
    if (t.isEmpty()) return ""
    return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
}

@Composable
private fun WebseiteKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val adresse = webAdresse(b.wert)
    val name = b.titel.ifBlank { Uri.parse(adresse).host?.removePrefix("www.") ?: "Webseite" }
    KnopfKarte(b, modifier, "globus", if (adresse.isEmpty()) "Adresse fehlt" else name, false) {
        if (adresse.isEmpty()) Toast.makeText(ctx, Sprache.tr("Lange drücken → Bausteine → Adresse eintragen"), Toast.LENGTH_SHORT).show()
        else starten(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(adresse)))
    }
}

@Composable
private fun WlanKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val wm = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    var an by remember { mutableStateOf(false) }
    Empfang(WifiManager.WIFI_STATE_CHANGED_ACTION) { an = try { wm?.isWifiEnabled == true } catch (e: Exception) { false } }
    // direkt schalten erlaubt Android seit Version 10 nicht mehr -> das kleine WLAN-Feld
    KnopfKarte(b, modifier, "wlan", b.titel.ifBlank { "WLAN" }, an) {
        starten(ctx, Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
    }
}

@Composable
private fun BluetoothKnopf(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    var an by remember { mutableStateOf(false) }
    Empfang(BluetoothAdapter.ACTION_STATE_CHANGED) {
        an = try {
            ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true
        } catch (e: Exception) { false }
    }
    KnopfKarte(b, modifier, "bluetooth", b.titel.ifBlank { "Bluetooth" }, an) { starten(ctx, Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
}

// ---------------------------------------------------------------- Anzeigen
@Composable
internal fun AnzeigeBaustein(b: Baustein, modifier: Modifier, jetzt: Long) {
    when (b.typ) {
        "uhr" -> UhrAnzeige(b, modifier)
        "akku" -> AkkuAnzeige(b, modifier)
        "termin" -> TerminAnzeige(b, modifier, jetzt)
        "notiz" -> NotizAnzeige(b, modifier)
        "uhrklein" -> UhrKlein(modifier)
    }
}

/**
 * Kleine Uhrzeit: beliebig oft, jede Groesse (ab 1 x 1). Die Ziffern fuellen den Baustein aus;
 * hoch und schmal = Stunden ueber Minuten. Schrift, Farbe und Dicke wie die grosse Uhr im Design.
 * Antippen = Wecker / Uhr-App.
 */
@Composable
private fun UhrKlein(modifier: Modifier) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val o = LocalBOpt.current
    val sek = o?.b("sek", false) ?: false
    val mitDatum = o?.b("datum", false) ?: false
    var jetzt by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    val sichtbar = Sichtbar.an || SperrSeite.sichtbar.value
    LaunchedEffect(sek, sichtbar, z.fortsetzen) {
        if (!sichtbar) return@LaunchedEffect          // nicht zu sehen: nicht weiterzaehlen
        while (true) {
            jetzt = System.currentTimeMillis()
            delay(if (sek) 1000 - jetzt % 1000 else 60_000 - jetzt % 60_000)
        }
    }
    val u = z.thema.uhr
    val farbe = bFarbe(uhrFarbe(u.farbe, p))
    val dicke = bDicke(when (u.dicke) { 0 -> FontWeight.Light; 1 -> FontWeight.Normal; else -> FontWeight.Bold }) ?: FontWeight.Normal
    // Schrift als Android-Typeface: so kennen wir die genaue Groesse der Ziffern (ohne Zeilenabstand oben/unten)
    val eigeneSchrift = o?.s("schrift") ?: ""          // ✎ → Schrift → Schriftart (sonst die Schrift der grossen Uhr)
    val familie = if (eigeneSchrift.isNotEmpty()) Schriften.familie(eigeneSchrift) else if (u.schrift.isNotEmpty()) Schriften.familie(u.schrift) else androidx.compose.ui.text.font.FontFamily.Default
    val aufloeser = androidx.compose.ui.platform.LocalFontFamilyResolver.current
    val schrift = remember(familie, dicke) {
        try { aufloeser.resolve(familie, dicke).value as? android.graphics.Typeface } catch (e: Exception) { null } ?: android.graphics.Typeface.DEFAULT
    }
    val schatten = schattenStil().shadow
    val dichte = androidx.compose.ui.platform.LocalDensity.current
    val d = Date(jetzt)
    val text = SimpleDateFormat(if (sek) "HH:mm:ss" else "HH:mm", Sprache.locale).format(d)
    val datumText = SimpleDateFormat(if (Sprache.englisch) "EEE, MMM d" else "EEE, d. MMM", Sprache.locale).format(d)
    val datumFarbe = p.text.copy(alpha = 0.8f)
    // Die Ziffern fuellen den Baustein immer ganz aus (kein Rand, kein Einruecken): hoeher gezogen = hoehere Ziffern,
    // breiter gezogen = breitere Ziffern - auch wenn sie dabei gestaucht/gestreckt werden
    androidx.compose.foundation.Canvas(
        modifier.clip(bForm(18.dp)).bKarte(p, 18.dp, 0.09f)
            .clickable { starten(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
    ) {
        val stift = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = schrift; textSize = 200f; color = farbe.toArgb()
            if (schatten != null && schatten.blurRadius > 0f) setShadowLayer(schatten.blurRadius, schatten.offset.x, schatten.offset.y, schatten.color.toArgb())
        }
        // Datum unten (nur wenn der Baustein hoch genug ist)
        val datumPx = with(dichte) { 12.sp.toPx() }
        val datumDa = mitDatum && size.height > datumPx * 3.2f
        val uhrH = size.height - if (datumDa) datumPx * 1.4f else 0f
        // Rahmen der Ziffern: an "88:88" gemessen (gleich breit, egal welche Uhrzeit)
        val probe = if (sek) "88:88:88" else "88:88"
        val r = android.graphics.Rect(); stift.getTextBounds(probe, 0, probe.length, r)
        if (r.width() <= 0 || r.height() <= 0) return@Canvas
        val sx = size.width / r.width(); val sy = uhrH / r.height()
        val nc = drawContext.canvas.nativeCanvas
        nc.save()
        nc.scale(sx, sy)
        // echte Uhrzeit in der Mitte des Probe-Rahmens (eine "1" ist schmaler als eine "8")
        val versatz = (stift.measureText(probe) - stift.measureText(text)) / 2f
        nc.drawText(text, -r.left + versatz, -r.top.toFloat(), stift)
        nc.restore()
        if (datumDa) {
            val dp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = schrift; textSize = datumPx; color = datumFarbe.toArgb(); textAlign = android.graphics.Paint.Align.CENTER
            }
            nc.drawText(datumText, size.width / 2f, size.height - datumPx * 0.25f, dp)
        }
    }
}

/** Karte fuer Anzeigen */
@Composable
private fun AnzeigeKarte(modifier: Modifier, onClick: (() -> Unit)? = null, inhalt: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(bForm(18.dp)).bKarte(p, 18.dp, 0.09f)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = inhalt,
    )
}

/** aktuelle Zeit, jede Minute neu */
@Composable
private fun minutenTakt(): Long {
    val t by produceState(System.currentTimeMillis()) {
        while (true) {
            val jetzt = System.currentTimeMillis()
            value = jetzt
            delay(60_000L - jetzt % 60_000L + 50L)
        }
    }
    return t
}

@Composable
private fun UhrAnzeige(b: Baustein, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val t = minutenTakt()
    AnzeigeKarte(modifier, onClick = { starten(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS)) }) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // Schrift passt sich der Groesse des Bausteins an
            val nachHoehe = maxHeight.value * (if (b.h >= 2) 0.62f else 0.75f)
            val nachBreite = maxWidth.value / 3.0f
            Text(SimpleDateFormat("HH:mm", Sprache.locale).format(Date(t)), color = p.text, fontWeight = FontWeight.Light,
                 fontSize = minOf(nachHoehe, nachBreite).coerceAtLeast(12f).sp, maxLines = 1, style = schattenStil())
        }
        if (b.h >= 2) Text(SimpleDateFormat(if (Sprache.englisch) "EEEE, MMMM d" else "EEEE, d. MMMM", Sprache.locale).format(Date(t)), color = p.leise, fontSize = 13.sp,
                           maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AkkuAnzeige(b: Baustein, modifier: Modifier) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var prozent by remember { mutableIntStateOf(-1) }
    var laedt by remember { mutableStateOf(false) }
    Empfang(Intent.ACTION_BATTERY_CHANGED) { i ->
        val e = i ?: return@Empfang
        val l = e.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = e.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0 && s > 0) prozent = l * 100 / s
        laedt = e.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
    val form = LocalBOpt.current?.s("form") ?: ""
    // klein (oder "Akku mit Zahl"): Akku wie bei Samsung, die Zahl steht im Akku
    if (form == "zahl" || (form != "ring" && !(b.h >= 2 && b.b >= 2))) { AkkuPille(b, modifier); return }
    AnzeigeKarte(modifier, onClick = { starten(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) {
        val ring = true
        // wie das Akku-Widget von Samsung: duenner Ring, in der Mitte das Handy, darunter die Prozent
        if (ring) {
            val farbe = when {
                laedt && akkuGruen(LocalBOpt.current) -> Color(0xFF3DDC84)    // laedt: gruen (einstellbar)
                prozent in 0..15 && LocalBOpt.current?.b("rot", true) != false -> p.warnung   // fast leer: rot
                else -> p.akzent
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(2.dp)) {
                    val d = minOf(size.width, size.height); val dicke = d * 0.075f
                    val tl = Offset((size.width - d) / 2f + dicke / 2f, (size.height - d) / 2f + dicke / 2f)
                    val gr = Size(d - dicke, d - dicke)
                    drawArc(p.text.copy(alpha = if (SW.an) 0.25f else 0.14f), 0f, 360f, false, tl, gr, style = Stroke(dicke))
                    if (prozent >= 0) drawArc(farbe, -90f, 360f * prozent / 100f, false, tl, gr, style = Stroke(dicke, cap = StrokeCap.Round))
                    // Handy in der Mitte
                    val mx = size.width / 2f; val my = size.height / 2f
                    val hw = d * 0.17f; val hh = d * 0.30f
                    drawRoundRect(p.text, Offset(mx - hw, my - hh), Size(hw * 2, hh * 2), CornerRadius(d * 0.05f), style = Stroke(d * 0.035f))
                    drawLine(p.text, Offset(mx - hw * 0.35f, my - hh * 0.72f), Offset(mx + hw * 0.35f, my - hh * 0.72f), d * 0.03f, StrokeCap.Round)
                    // laedt: kleiner Blitz unten rechts am Ring
                    if (laedt) {
                        val bx = mx + d * 0.30f; val by = my + d * 0.30f; val k = d * 0.09f
                        drawCircle(p.grund, k * 1.25f, Offset(bx, by))
                        val blitz = androidx.compose.ui.graphics.Path().apply {
                            moveTo(bx + k * 0.2f, by - k); lineTo(bx - k * 0.45f, by + k * 0.1f); lineTo(bx, by + k * 0.1f)
                            lineTo(bx - k * 0.2f, by + k); lineTo(bx + k * 0.45f, by - k * 0.1f); lineTo(bx, by - k * 0.1f); close()
                        }
                        drawPath(blitz, farbe)
                    }
                }
            }
            Text(if (prozent >= 0) "$prozent %" else "–", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                 modifier = Modifier.padding(top = 2.dp))
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            KleinSymbol("akku", p.text, 22.dp, anteil = prozent / 100f)
            Spacer(Modifier.width(6.dp))
            Text(if (prozent >= 0) "$prozent %" + (if (laedt) " ⚡" else "") else "–", color = p.text, fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
private fun TerminAnzeige(b: Baustein, modifier: Modifier, jetzt: Long) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val t = minutenTakt()
    val naechster = remember(t / 3_600_000L, Geburtstage.stand) { Geburtstage.alsTermine(ctx, maxOf(t, jetzt)).minByOrNull { it.beginn } }
    var neu by remember { mutableStateOf(false) }
    if (neu) GeburtstagNeuDialog { neu = false }
    AnzeigeKarte(modifier, onClick = null) {
        if (naechster == null) {
            Text("Keine Termine", color = p.leise, fontSize = 14.sp)
            // Atelier: neue Termine nur ueber die Einstellungen
            // kein "+ Termin" mehr - neue Termine traegt man in den Einstellungen ein
            return@AnzeigeKarte
        }
        val heute0 = Calendar.getInstance().apply { timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val tage = ((naechster.beginn - heute0.timeInMillis) / 86_400_000L).toInt()
        val wann = when {
            tage <= 0 -> "Heute"
            tage == 1 -> "Morgen"
            tage < 7 -> "In $tage Tagen"
            else -> SimpleDateFormat("d. MMMM", Sprache.locale).format(Date(naechster.beginn))
        }
        Text(naechster.titel, color = p.text, fontSize = 15.sp, fontWeight = bDicke(FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(wann, color = if (tage <= 0) p.akzent else p.leise, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun NotizAnzeige(b: Baustein, modifier: Modifier) {
    val p = LocalPalette.current
    AnzeigeKarte(modifier) {
        if (b.wert.isBlank()) Text("Notiz – lange drücken → Bausteine → Text eintragen", color = p.leise, fontSize = 12.sp, textAlign = TextAlign.Center)
        else Text(b.wert, color = bFarbe(p.text), fontSize = 15.sp, lineHeight = 20.sp, fontWeight = bDicke(null),
                  textAlign = bAusr(TextAlign.Center), overflow = TextOverflow.Ellipsis,
                  modifier = if (LocalBOpt.current?.s("ausr").isNullOrEmpty()) Modifier else Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- Favoriten
/** Vorschau im Editor: Favoriten als kleine Liste */
@Composable
internal fun FavoritenVorschau(modifier: Modifier) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val favs = z.favoriten.filter { it !in z.versteckt }.mapNotNull { z.app(it) }.take(z.favMax)
    Column(modifier.clip(rund(18.dp)).background(p.text.copy(alpha = 0.04f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text("App-Liste · Favoriten (höchstens ${z.favMax}), beim Wischen der Buchstabe", color = p.leise, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        BisVoll(Modifier.fillMaxSize()) {
            favs.forEach { a ->
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.schluessel, 22.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(a.name, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** stapelt die Kinder untereinander, solange sie ganz hineinpassen - der Rest bleibt weg (nichts wird abgeschnitten) */
@Composable
internal fun BisVoll(modifier: Modifier, inhalt: @Composable () -> Unit) {
    Layout(inhalt, modifier) { teile, c ->
        val frei = c.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val gemessen = ArrayList<androidx.compose.ui.layout.Placeable>()
        var y = 0
        for (t in teile) {
            val pl = t.measure(frei)
            if (y + pl.height > c.maxHeight) break
            gemessen += pl; y += pl.height
        }
        layout(c.maxWidth, if (c.hasBoundedHeight) c.maxHeight else y) {
            var yy = 0
            gemessen.forEach { it.place(0, yy); yy += it.height }
        }
    }
}

// ---------------------------------------------------------------- Einstellungen im Editor
/** was man beim ausgewaehlten Baustein einstellen kann (Adresse, Text, Hoechstzahl ...) */
@Composable
internal fun BausteinEinstellungen(b: Baustein, aendern: (Baustein) -> Unit) {
    val ctx = LocalContext.current
    val z = LocalZustand.current
    when (b.typ) {
        "webseite" -> {
            var adresse by remember(b.id) { mutableStateOf(b.wert) }
            var name by remember(b.id) { mutableStateOf(b.titel) }
            Eingabe(adresse, { adresse = it; aendern(b.copy(wert = it)) }, "Adresse, z. B. wikipedia.org")
            Eingabe(name, { name = it; aendern(b.copy(titel = it)) }, "Name (leer = Adresse)")
        }
        "notiz" -> {
            var text by remember(b.id) { mutableStateOf(b.wert) }
            Eingabe(text, { text = it; aendern(b.copy(wert = it)) }, "Text der Notiz")
        }
        "ruhe", "ton" -> if (!ruheErlaubt(ctx)) MenueEintrag("Zugriff auf „Nicht stören“ erlauben", "Nötig, damit der Knopf schalten darf") { ruheErlaubnisHolen(ctx) }
    }
}

private fun Float.roundToInt() = kotlin.math.round(this).toInt()

// ---------------------------------------------------------------- kleine Symbole (gezeichnet, passen zu jedem Design)
@Composable
internal fun KleinSymbol(name: String, farbe: Color, groesse: Dp, anteil: Float = 1f) {
    Canvas(Modifier.size(groesse)) { symbolMalen(name, farbe, anteil) }
}

private fun DrawScope.symbolMalen(name: String, farbe: Color, anteil: Float) {
    val w = size.width; val h = size.height
    val strich = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun pt(x: Float, y: Float) = Offset(x * w, y * h)
    when (name) {
        "lampe" -> {
            val kopf = Path().apply { moveTo(0.28f * w, 0.12f * h); lineTo(0.72f * w, 0.12f * h); lineTo(0.62f * w, 0.4f * h); lineTo(0.38f * w, 0.4f * h); close() }
            drawPath(kopf, farbe, style = strich)
            drawRoundRect(farbe, pt(0.38f, 0.4f), Size(0.24f * w, 0.5f * h), CornerRadius(0.05f * w), style = strich)
            drawCircle(farbe, 0.04f * w, pt(0.5f, 0.55f))
        }
        "mond" -> {
            val voll = Path().apply { addOval(Rect(pt(0.16f, 0.16f), Size(0.68f * w, 0.68f * h))) }
            val weg = Path().apply { addOval(Rect(pt(0.36f, 0.06f), Size(0.6f * w, 0.6f * h))) }
            drawPath(Path().apply { op(voll, weg, PathOperation.Difference) }, farbe)
        }
        "glocke", "lautlos" -> {
            val g = Path().apply {
                moveTo(0.2f * w, 0.72f * h); lineTo(0.8f * w, 0.72f * h)
                cubicTo(0.7f * w, 0.62f * h, 0.72f * w, 0.5f * h, 0.72f * w, 0.42f * h)
                cubicTo(0.72f * w, 0.24f * h, 0.62f * w, 0.16f * h, 0.5f * w, 0.16f * h)
                cubicTo(0.38f * w, 0.16f * h, 0.28f * w, 0.24f * h, 0.28f * w, 0.42f * h)
                cubicTo(0.28f * w, 0.5f * h, 0.3f * w, 0.62f * h, 0.2f * w, 0.72f * h); close()
            }
            drawPath(g, farbe, style = strich)
            drawLine(farbe, pt(0.43f, 0.84f), pt(0.57f, 0.84f), strich.width, StrokeCap.Round)
            if (name == "lautlos") drawLine(farbe, pt(0.15f, 0.15f), pt(0.85f, 0.85f), strich.width, StrokeCap.Round)
        }
        "vibration" -> {
            drawRoundRect(farbe, pt(0.34f, 0.16f), Size(0.32f * w, 0.68f * h), CornerRadius(0.07f * w), style = strich)
            for (s in listOf(-1f, 1f)) {
                val x0 = 0.5f + s * 0.28f; val x1 = 0.5f + s * 0.4f
                drawLine(farbe, pt(x0, 0.32f), pt(x1, 0.42f), strich.width, StrokeCap.Round)
                drawLine(farbe, pt(x1, 0.42f), pt(x0, 0.52f), strich.width, StrokeCap.Round)
                drawLine(farbe, pt(x0, 0.52f), pt(x1, 0.62f), strich.width, StrokeCap.Round)
            }
        }
        "kamera" -> {
            drawRoundRect(farbe, pt(0.12f, 0.3f), Size(0.76f * w, 0.52f * h), CornerRadius(0.1f * w), style = strich)
            drawLine(farbe, pt(0.36f, 0.3f), pt(0.42f, 0.2f), strich.width, StrokeCap.Round)
            drawLine(farbe, pt(0.42f, 0.2f), pt(0.58f, 0.2f), strich.width, StrokeCap.Round)
            drawLine(farbe, pt(0.58f, 0.2f), pt(0.64f, 0.3f), strich.width, StrokeCap.Round)
            drawCircle(farbe, 0.14f * w, pt(0.5f, 0.56f), style = strich)
        }
        "wlan" -> {
            for ((i, r) in listOf(0.22f, 0.4f, 0.58f).withIndex()) {
                val d = r * w
                drawArc(farbe, 225f, 90f, false, Offset(0.5f * w - d, 0.8f * h - d), Size(2 * d, 2 * d), style = strich,
                        alpha = if (i == 2) 0.95f else 1f)
            }
            drawCircle(farbe, 0.06f * w, pt(0.5f, 0.8f))
        }
        "bluetooth" -> {
            val r = Path().apply {
                moveTo(0.3f * w, 0.32f * h); lineTo(0.7f * w, 0.66f * h); lineTo(0.5f * w, 0.84f * h)
                lineTo(0.5f * w, 0.16f * h); lineTo(0.7f * w, 0.34f * h); lineTo(0.3f * w, 0.68f * h)
            }
            drawPath(r, farbe, style = strich)
        }
        "lupe" -> {
            drawCircle(farbe, 0.24f * w, pt(0.43f, 0.43f), style = strich)
            drawLine(farbe, pt(0.61f, 0.61f), pt(0.84f, 0.84f), strich.width * 1.2f, StrokeCap.Round)
        }
        "globus" -> {
            drawCircle(farbe, 0.36f * w, pt(0.5f, 0.5f), style = strich)
            drawOval(farbe, pt(0.34f, 0.14f), Size(0.32f * w, 0.72f * h), style = strich)
            drawLine(farbe, pt(0.14f, 0.5f), pt(0.86f, 0.5f), strich.width, StrokeCap.Round)
        }
        "akku" -> {
            drawRoundRect(farbe, pt(0.12f, 0.3f), Size(0.68f * w, 0.4f * h), CornerRadius(0.06f * w), style = strich)
            drawLine(farbe, pt(0.88f, 0.42f), pt(0.88f, 0.58f), strich.width, StrokeCap.Round)
            val a = anteil.coerceIn(0f, 1f)
            if (a > 0f) drawRect(farbe, pt(0.2f, 0.38f), Size(0.52f * w * a, 0.24f * h))
        }
    }
}

/** Vorschau im Editor: das Alphabet als schmale Leiste A … # */
@Composable
internal fun AlphabetVorschau(modifier: Modifier) {
    val p = LocalPalette.current
    val zeichen = ('A'..'Z').map { it.toString() } + "#"
    Column(modifier.clip(rund(12.dp)).background(p.akzent.copy(alpha = 0.12f)).padding(vertical = 4.dp),
           horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val groesse = (maxHeight.value / zeichen.size * 0.8f).coerceIn(5f, 13f)
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
                zeichen.forEach { Text(it, color = p.text, fontSize = groesse.sp, lineHeight = groesse.sp, maxLines = 1) }
            }
        }
    }
}

/** Suchen: breit wie eine Suchleiste, klein als Lupe - antippen oeffnet die Suche */
@Composable
internal fun SucheBaustein(b: Baustein, modifier: Modifier) {
    val p = LocalPalette.current
    val oeffnen = LocalSucheOeffnen.current
    val breit = b.b >= 4 && LocalBOpt.current?.b("nurLupe", false) != true
    Row(
        modifier.clip(bForm(18.dp)).bKarte(p, 18.dp, 0.09f).clickable { oeffnen() }.padding(horizontal = if (breit) 14.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (breit) Arrangement.Start else Arrangement.Center,
    ) {
        if (breit) KleinSymbol("lupe", p.leise, 22.dp)
        else BoxWithConstraints(contentAlignment = Alignment.Center) { KleinSymbol("lupe", p.leise, minOf(minOf(maxWidth, maxHeight) * 0.66f, 26.dp)) }
        if (breit) {
            Spacer(Modifier.width(10.dp))
            Text(b.titel.ifBlank { "Apps suchen" }, color = p.leise, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
