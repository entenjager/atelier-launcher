package de.atelierlauncher.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ============================================================================
//   - "wlanstand"  WLAN: Faecher mit 3 Stufen, aus = durchgestrichen
//   - "mobil"      Mobile Daten wie bei Samsung: oben LTE/5G, darunter Pfeil runter + Pfeil hoch, daneben 4 Balken
//   - "netz"       WLAN + mobile Daten in einem Feld
//   - "akkupille"  Akku wie bei Samsung: Pille mit der Zahl drin, laedt = gruen mit Blitz, unter 15 % rot
//  Alles wird in einem Canvas gezeichnet und passt sich der Groesse an.
//  Antippen: das kleine WLAN- / Internet-Feld von Android bzw. die Akku-Seite.
// ============================================================================

internal val NETZ_TYPEN = setOf("wlanstand", "mobil", "netz", "akkupille")

/** was gerade los ist (jede Sekunde neu, solange der Startbildschirm zu sehen ist) */
private data class NetzStand(
    val wlanAn: Boolean = false, val wlanVerbunden: Boolean = false, val wlanStufe: Int = 0,      // Stufe 0..3
    val sim: Boolean = true, val daten: Boolean = false, val mobilStufe: Int = 0,                  // Stufe 0..4
    val typ: String = "", val runter: Boolean = false, val hoch: Boolean = false,
)

private fun netzTyp(t: Int): String = when (t) {
    TelephonyManager.NETWORK_TYPE_NR -> "5G"
    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
    TelephonyManager.NETWORK_TYPE_HSPAP -> "H+"
    TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA -> "H"
    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
    TelephonyManager.NETWORK_TYPE_EDGE -> "E"
    TelephonyManager.NETWORK_TYPE_GPRS -> "G"
    else -> ""
}

internal fun telefonErlaubt(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
@Composable
private fun netzStand(): NetzStand {
    val ctx = LocalContext.current
    val sichtbar = Sichtbar.an || SperrSeite.sichtbar.value
    val s by produceState(NetzStand(), sichtbar) {
        if (!sichtbar) return@produceState
        val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        var rx = TrafficStats.getMobileRxBytes(); var tx = TrafficStats.getMobileTxBytes()
        while (true) {
            val wlanAn = try { wm?.isWifiEnabled == true } catch (e: Exception) { false }
            var verbunden = false; var stufe = 0; var mobilAktiv = false
            try {
                cm?.allNetworks?.forEach { n ->
                    val c = cm.getNetworkCapabilities(n) ?: return@forEach
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        verbunden = true
                        val rssi = if (Build.VERSION.SDK_INT >= 29) c.signalStrength else wm?.connectionInfo?.rssi ?: -100
                        stufe = WifiManager.calculateSignalLevel(rssi, 4)
                    }
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) mobilAktiv = true
                }
            } catch (e: Exception) { }
            val sim = try { tm?.simState == TelephonyManager.SIM_STATE_READY } catch (e: Exception) { false }
            val daten = sim && (try { tm?.isDataEnabled == true } catch (e: Exception) { mobilAktiv })
            val mStufe = try { if (Build.VERSION.SDK_INT >= 28) tm?.signalStrength?.level ?: 0 else 0 } catch (e: Exception) { 0 }
            val typ = if (daten && telefonErlaubt(ctx)) (try { netzTyp(tm?.dataNetworkType ?: 0) } catch (e: Exception) { "" }) else ""
            // Datenfluss ueber das Handynetz (nur Zahlen, nichts wird gelesen)
            val r2 = TrafficStats.getMobileRxBytes(); val t2 = TrafficStats.getMobileTxBytes()
            val runter = daten && rx >= 0 && r2 - rx > 300; val hoch = daten && tx >= 0 && t2 - tx > 300
            rx = r2; tx = t2
            value = NetzStand(wlanAn, wlanAn && verbunden, stufe, sim, daten, mStufe.coerceIn(0, 4), typ, runter, hoch)
            delay(1000)
        }
    }
    return s
}

private fun oeffnen(ctx: Context, i: Intent) {
    try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (e: Exception) { Toast.makeText(ctx, Sprache.tr("Geht auf diesem Handy nicht"), Toast.LENGTH_SHORT).show() }
}

private fun internetFeld() = Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS)

/** kleine Karte mit Zeichenflaeche - passt auch in ein einzelnes Feld */
@Composable
private fun KleineKarte(modifier: Modifier, onClick: () -> Unit, malen: DrawScope.() -> Unit) {
    val p = LocalPalette.current
    Box(modifier.clip(bForm(14.dp)).bKarte(p, 14.dp, 0.09f).clickable(onClick = onClick).padding(2.dp)) {
        Canvas(Modifier.fillMaxSize(), onDraw = malen)
    }
}

@Composable
internal fun NetzBaustein(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val messer = rememberTextMeasurer()
    val hell = p.text
    val dunkel = p.text.copy(alpha = if (SW.an) 0.3f else 0.22f)
    if (b.typ == "akkupille") { AkkuPille(b, modifier); return }
    val st = netzStand()
    KleineKarte(modifier, onClick = {
        oeffnen(ctx, if (b.typ == "wlanstand") Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS) else internetFeld())
    }) {
        val w = size.width; val h = size.height
        val breit = w > h * 1.5f; val hochk = h > w * 1.5f
        when (b.typ) {
            "wlanstand" -> {
                if (breit) {
                    wlanMalen(Rect(Offset(h * 0.12f, h * 0.12f), Size(h * 0.76f, h * 0.76f)), st, hell, dunkel)
                    textMalen(messer, if (!st.wlanAn) "aus" else "WLAN", Offset(h * 1.0f, h / 2f), h * 0.26f, if (st.wlanAn) hell else dunkel, links = true)
                } else if (hochk) {
                    wlanMalen(Rect(Offset(w * 0.12f, h * 0.18f), Size(w * 0.76f, w * 0.76f)), st, hell, dunkel)
                    textMalen(messer, if (!st.wlanAn) "aus" else "WLAN", Offset(w / 2f, h * 0.76f), w * 0.2f, if (st.wlanAn) hell else dunkel)
                } else wlanMalen(quadrat(0.74f), st, hell, dunkel)
            }
            "mobil" -> {
                if (hochk) mobilHochMalen(messer, Rect(Offset.Zero, size), st, hell, dunkel)
                else mobilMalen(messer, if (breit) mitte(h * 1.5f, h * 0.7f) else quadrat(0.72f), st, hell, dunkel)
            }
            "netz" -> {
                if (breit) {
                    wlanMalen(Rect(Offset(w * 0.25f - h * 0.36f, h * 0.14f), Size(h * 0.72f, h * 0.72f)), st, hell, dunkel)
                    drawLine(dunkel, Offset(w / 2f, h * 0.25f), Offset(w / 2f, h * 0.75f), 1.dp.toPx())
                    mobilMalen(messer, Rect(Offset(w * 0.75f - h * 0.4f, h * 0.2f), Size(h * 0.8f, h * 0.6f)), st, hell, dunkel)
                } else if (hochk) {
                    wlanMalen(Rect(Offset(w * 0.14f, h * 0.1f), Size(w * 0.72f, w * 0.72f)), st, hell, dunkel)
                    mobilMalen(messer, Rect(Offset(w * 0.12f, h * 0.56f), Size(w * 0.76f, w * 0.6f)), st, hell, dunkel)
                } else {
                    val q = quadrat(0.8f)
                    wlanMalen(Rect(Offset(q.left + q.width * 0.2f, q.top), Size(q.width * 0.6f, q.height * 0.5f)), st, hell, dunkel)
                    mobilMalen(messer, Rect(Offset(q.left + q.width * 0.1f, q.top + q.height * 0.56f), Size(q.width * 0.8f, q.height * 0.44f)), st, hell, dunkel)
                }
            }
        }
    }
}

private fun DrawScope.quadrat(anteil: Float): Rect {
    val d = minOf(size.width, size.height) * anteil
    return Rect(Offset((size.width - d) / 2f, (size.height - d) / 2f), Size(d, d))
}
private fun DrawScope.mitte(bw: Float, bh: Float) = Rect(Offset((size.width - bw) / 2f, (size.height - bh) / 2f), Size(bw, bh))

private fun DrawScope.textMalen(m: TextMeasurer, t: String, mitte: Offset, hoehePx: Float, farbe: Color, links: Boolean = false, dick: Boolean = true) {
    if (t.isEmpty()) return
    val l = m.measure(t, TextStyle(fontSize = hoehePx.toSp(), fontWeight = if (dick) FontWeight.Bold else FontWeight.Medium, color = farbe))
    val x = if (links) mitte.x else mitte.x - l.size.width / 2f
    drawText(l, topLeft = Offset(x, mitte.y - l.size.height / 2f))
}

/** WLAN-Faecher in einem Quadrat: unten der Punkt, darueber 3 Boegen */
private fun DrawScope.wlanMalen(r: Rect, st: NetzStand, hell: Color, dunkel: Color) {
    val d = r.width
    val cx = r.center.x; val cy = r.top + d * 0.82f
    val dicke = d * 0.11f
    val leuchtet = if (!st.wlanVerbunden) 0 else maxOf(1, st.wlanStufe)
    for (i in 0 until 3) {
        val rr = d * (0.26f + 0.24f * i)
        drawArc(if (i < leuchtet) hell else dunkel, 225f, 90f, false, Offset(cx - rr, cy - rr), Size(2 * rr, 2 * rr),
                style = Stroke(dicke, cap = StrokeCap.Round))
    }
    drawCircle(if (st.wlanVerbunden) hell else dunkel, d * 0.075f, Offset(cx, cy))
    if (!st.wlanAn) drawLine(hell, Offset(r.left + d * 0.12f, r.top + d * 0.18f), Offset(r.right - d * 0.12f, r.top + d * 0.86f), d * 0.08f, StrokeCap.Round)
}

/** 4 Balken in einem Rechteck */
private fun DrawScope.balkenMalen(r: Rect, stufe: Int, hell: Color, dunkel: Color) {
    val bw = r.width / 6.1f; val luecke = (r.width - 4 * bw) / 3f
    for (i in 0 until 4) {
        val bh = r.height * (0.3f + 0.7f * i / 3f)
        drawRoundRect(if (i < stufe) hell else dunkel, Offset(r.left + i * (bw + luecke), r.bottom - bh), Size(bw, bh), CornerRadius(bw * 0.3f))
    }
}

private fun DrawScope.pfeileMalen(r: Rect, st: NetzStand, hell: Color, dunkel: Color) {
    val pw = r.width * 0.42f; val dicke = maxOf(1f, pw * 0.22f)
    fun pfeil(x: Float, runter: Boolean, an: Boolean) {
        val f = if (an) hell else dunkel
        val top = r.top; val bot = r.bottom; val spitze = pw * 0.55f
        val cx = x + pw / 2f
        drawLine(f, Offset(cx, if (runter) top else top + spitze * 0.6f), Offset(cx, if (runter) bot - spitze * 0.6f else bot), dicke)
        val pt = Path().apply {
            if (runter) { moveTo(x, bot - spitze); lineTo(x + pw, bot - spitze); lineTo(cx, bot) }
            else { moveTo(x, top + spitze); lineTo(x + pw, top + spitze); lineTo(cx, top) }
            close()
        }
        drawPath(pt, f)
    }
    pfeil(r.left, true, st.runter)
    pfeil(r.right - pw, false, st.hoch)
}

/** Mobile Daten: links oben LTE/5G, darunter die Pfeile, rechts die Balken */
private fun DrawScope.mobilMalen(m: TextMeasurer, r: Rect, st: NetzStand, hell: Color, dunkel: Color) {
    val links = r.width * 0.42f
    val balken = Rect(Offset(r.left + links + r.width * 0.06f, r.top), Size(r.width - links - r.width * 0.06f, r.height))
    balkenMalen(balken, if (st.sim) st.mobilStufe else 0, hell, dunkel)
    if (!st.sim) {
        drawLine(hell, Offset(balken.left, balken.top + balken.height * 0.1f), Offset(balken.right, balken.bottom), r.height * 0.08f, StrokeCap.Round)
        return
    }
    if (!st.daten) return
    val typ = st.typ
    if (typ.isNotEmpty()) {
        textMalen(m, typ, Offset(r.left + links / 2f, r.top + r.height * 0.24f), r.height * (if (typ.length > 2) 0.3f else 0.36f), hell)
        pfeileMalen(Rect(Offset(r.left + links * 0.1f, r.top + r.height * 0.5f), Size(links * 0.8f, r.height * 0.48f)), st, hell, dunkel)
    } else pfeileMalen(Rect(Offset(r.left + links * 0.1f, r.top + r.height * 0.2f), Size(links * 0.8f, r.height * 0.7f)), st, hell, dunkel)
}

/** hoch (1x2): oben Typ, darunter Pfeile, unten grosse Balken */
private fun DrawScope.mobilHochMalen(m: TextMeasurer, r: Rect, st: NetzStand, hell: Color, dunkel: Color) {
    val w = r.width
    if (st.sim && st.daten) {
        textMalen(m, st.typ, Offset(r.center.x, r.top + r.height * 0.17f), w * (if (st.typ.length > 2) 0.24f else 0.3f), hell)
        pfeileMalen(Rect(Offset(r.center.x - w * 0.2f, r.top + r.height * 0.28f), Size(w * 0.4f, r.height * 0.14f)), st, hell, dunkel)
    }
    val b = Rect(Offset(r.left + w * 0.2f, r.top + r.height * 0.5f), Size(w * 0.6f, r.height * 0.36f))
    balkenMalen(b, if (st.sim) st.mobilStufe else 0, hell, dunkel)
    if (!st.sim) drawLine(hell, Offset(b.left, b.top), Offset(b.right, b.bottom), w * 0.08f, StrokeCap.Round)
}

// ---------------------------------------------------------------- Akku (Samsung)
@Composable
internal fun AkkuPille(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val messer = rememberTextMeasurer()
    var prozent by remember { mutableIntStateOf(-1) }
    var laedt by remember { mutableStateOf(false) }
    Empfang(Intent.ACTION_BATTERY_CHANGED) { i ->
        val e = i ?: return@Empfang
        val l = e.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = e.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0 && s > 0) prozent = l * 100 / s
        laedt = e.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
    val hell = p.text
    val leer = p.text.copy(alpha = if (SW.an) 0.28f else 0.18f)
    val o = LocalBOpt.current
    val gruen = akkuGruen(o); val rot = o?.b("rot", true) != false
    val fuell = when { laedt && gruen -> Color(0xFF3DDC84); prozent in 0..15 && rot -> p.warnung; else -> p.text }
    val breiteF = (o?.i("pBreite", 100) ?: 100) / 100f
    val dickeF = (o?.i("pDicke", 100) ?: 100) / 100f
    val aufFuell = if ((laedt && gruen) || (prozent in 0..15 && rot)) Color(0xFF0E0E10) else p.grund
    KleineKarte(modifier, onClick = { oeffnen(ctx, Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) {
        val w = size.width; val h = size.height
        val breit = w > h * 1.5f; val hochk = h > w * 1.5f
        // automatisch moeglichst gross; ✎ → Breite / Dicke in Prozent davon
        var pillW: Float; var pillH: Float
        if (breit) { pillW = w * 0.86f; pillH = h * 0.6f }
        else if (hochk) { pillW = w * 0.84f; pillH = w * 0.44f }
        else { pillW = w * 0.84f; pillH = h * 0.46f }
        pillW *= breiteF; pillH *= dickeF
        pillW = minOf(pillW, w * 0.96f - pillH * 0.25f); pillH = minOf(pillH, h * 0.94f)
        val top = if (hochk) h * 0.3f - pillH / 2f else (h - pillH) / 2f
        val left = (w - pillW) / 2f - pillH * 0.08f
        val rad = CornerRadius(pillH / 2f)
        drawRoundRect(leer, Offset(left, top), Size(pillW, pillH), rad)
        // Knopf rechts
        drawRoundRect(leer, Offset(left + pillW + pillH * 0.1f, top + pillH * 0.3f), Size(pillH * 0.13f, pillH * 0.4f), CornerRadius(pillH * 0.06f))
        val anteil = (prozent.coerceIn(0, 100)) / 100f
        val fx = left + pillW * anteil
        clipRect(left, top, fx, top + pillH) { drawRoundRect(fuell, Offset(left, top), Size(pillW, pillH), rad) }
        val zahl = if (prozent >= 0) "$prozent" else "–"
        // Zahl immer ganz im Akku (auch "100" und mit Blitz)
        val zahlGr = minOf(pillH * 0.62f, (pillW - (if (laedt) pillH * 0.5f else 0f)) * 0.82f / 1.9f)
        val mitte = Offset(left + pillW / 2f + (if (laedt) pillH * 0.22f else 0f), top + pillH / 2f)
        // Zahl: auf der Fuellung dunkel, daneben hell (wie bei Samsung)
        clipRect(left, top, fx, top + pillH) { textMalen(messer, zahl, mitte, zahlGr, aufFuell) }
        clipRect(fx, top, left + pillW, top + pillH) { textMalen(messer, zahl, mitte, zahlGr, hell) }
        if (laedt) {
            val bx = left + pillH * 0.55f; val by = top + pillH / 2f; val k = pillH * 0.32f
            val blitz = Path().apply {
                moveTo(bx + k * 0.2f, by - k); lineTo(bx - k * 0.45f, by + k * 0.1f); lineTo(bx, by + k * 0.1f)
                lineTo(bx - k * 0.2f, by + k); lineTo(bx + k * 0.45f, by - k * 0.1f); lineTo(bx, by - k * 0.1f); close()
            }
            drawPath(blitz, if (bx < fx) aufFuell else hell)
        }
        if (hochk) {
            textMalen(messer, if (prozent >= 0) "$prozent %" else "–", Offset(w / 2f, h * 0.62f), w * 0.22f, hell)
            textMalen(messer, if (laedt) Sprache.tr("lädt") else "", Offset(w / 2f, h * 0.8f), w * 0.15f, p.leise, dick = false)
        }
    }
}

/** Akku: beim Laden gruen? ✎ → Farbe beim Laden ("" = automatisch: Schwarz & Weiss nie, sonst gruen) */
internal fun akkuGruen(o: BOpt?): Boolean = when (o?.s("ladeFarbe") ?: "") {
    "gruen" -> true
    "design" -> false
    else -> !SW.an
}
