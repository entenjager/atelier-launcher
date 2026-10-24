package de.atelierlauncher.app

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.Condition
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

// ============================================================================
//  Schnellzugriff (im Alphabet unter # das "<", steht in der App-Liste ganz oben): Taschenlampe, Nicht stoeren,
//  Flugmodus, WLAN, Helligkeit, ... Was Android Apps selbst schalten laesst,
//  wird direkt geschaltet - der Rest oeffnet das passende Android-Fenster.
// ============================================================================

object Taschenlampe {
    var an by mutableStateOf(false)
    var da by mutableStateOf(false)
    var maxStufe by mutableIntStateOf(1)
    private var id: String? = null
    private var cm: CameraManager? = null

    private val rueckruf = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == id) an = enabled
        }
    }

    fun start(ctx: Context) {
        try {
            val m = ctx.applicationContext.getSystemService(CameraManager::class.java) ?: return
            cm = m
            id = m.cameraIdList.firstOrNull { cid ->
                val c = m.getCameraCharacteristics(cid)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: m.cameraIdList.firstOrNull { m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            da = id != null
            if (Build.VERSION.SDK_INT >= 33) id?.let {
                maxStufe = m.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            }
            m.registerTorchCallback(rueckruf, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { da = false }
    }

    fun umschalten() {
        val i = id ?: return
        try { cm?.setTorchMode(i, !an) } catch (e: Exception) { }
    }

    fun stufe(s: Int) {
        val i = id ?: return
        if (Build.VERSION.SDK_INT >= 33) try { cm?.turnOnTorchWithStrengthLevel(i, s.coerceIn(1, maxStufe)) } catch (e: Exception) { }
    }
}

/**
 * "Nicht stoeren" ueber eine eigene Regel (Modus) des Launchers. Seit Android 15 duerfen Apps
 * den globalen Schalter nicht mehr direkt setzen - nur eigene Regeln an- und ausschalten.
 */
object Ruhe {
    private val BEDINGUNG: Uri = Uri.parse("launcher://nicht-stoeren")

    private fun regel(ctx: Context, nm: NotificationManager): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val prefs = ctx.getSharedPreferences("intern", Context.MODE_PRIVATE)
        var id = prefs.getString("ruheRegel", null)
        if (id != null && nm.getAutomaticZenRule(id) == null) id = null
        if (id == null) {
            val r = AutomaticZenRule(
                Variante.appName, null, ComponentName(ctx, MainActivity::class.java),
                BEDINGUNG, null, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true,
            )
            id = nm.addAutomaticZenRule(r)
            prefs.edit().putString("ruheRegel", id).apply()
        }
        return id
    }

    /** true = hat geklappt */
    fun setzen(ctx: Context, nm: NotificationManager, an: Boolean): Boolean = try {
        val id = regel(ctx, nm)
        if (id == null) {
            // aeltere Android-Versionen: direkt schalten
            nm.setInterruptionFilter(if (an) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            nm.setAutomaticZenRuleState(id, Condition(BEDINGUNG, "Nicht stören", if (an) Condition.STATE_TRUE else Condition.STATE_FALSE))
            // war "Nicht stoeren" vom Handy selbst an, zusaetzlich direkt ausschalten
            if (!an) try { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) } catch (e: Exception) { }
        }
        true
    } catch (e: Exception) { false }
}

private class Kachel(val name: String, val status: String, val aktiv: Boolean, val aktion: () -> Unit)

@Composable
fun SchnellListe() {
    val ctx = LocalContext.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    var neu by remember { mutableIntStateOf(0) }           // nach jeder Aktion Zustaende neu lesen
    val auffrischen = { neu++ }
    @Suppress("UNUSED_VARIABLE") val r = z.fortsetzen + neu   // auch nach Rueckkehr aus den Android-Einstellungen

    val cr = ctx.contentResolver
    fun oeffne(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
        try { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
    }
    fun panel(aktion: String, sonst: String) = oeffne(Intent(if (Build.VERSION.SDK_INT >= 29) aktion else sonst))
    val darfSchreiben = Settings.System.canWrite(ctx)
    fun schreibRecht() {
        Toast.makeText(ctx, Sprache.tr("Einmal „Systemeinstellungen ändern“ erlauben"), Toast.LENGTH_LONG).show()
        oeffne(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
    }

    val nm = ctx.getSystemService(NotificationManager::class.java)
    val audio = ctx.getSystemService(AudioManager::class.java)
    val flug = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    val dnd = (try { Lauscher.verbunden?.currentInterruptionFilter } catch (e: Exception) { null } ?: nm?.currentInterruptionFilter)
        ?.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN } ?: false
    val dndRecht = nm?.isNotificationPolicyAccessGranted == true
    val wlan = try { ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true } catch (e: Exception) { false }
    val bt = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true } catch (e: Exception) { false }
    val drehen = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    val standort = try {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true else true
    } catch (e: Exception) { false }
    val sparen = ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
    val nfc = try { NfcAdapter.getDefaultAdapter(ctx) } catch (e: Exception) { null }
    val klingel = audio?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
    val autoHell = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    // beim Oeffnen (und nach Rueckkehr) den aktuellen Wert neu lesen
    var hell by remember(z.fortsetzen) { mutableFloatStateOf(Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f) }
    // Schreiben drosseln: nur bei geaendertem Wert und hoechstens alle 50 ms ([0] Wert, [1] Zeitpunkt)
    val hellZuletzt = remember(z.fortsetzen) { longArrayOf(-1L, 0L) }
    fun hellSchreiben(v: Float, sofort: Boolean) {
        val wert = (v * 255).roundToInt().coerceIn(1, 255)
        val jetzt = SystemClock.uptimeMillis()
        if (wert.toLong() == hellZuletzt[0]) return
        if (!sofort && jetzt - hellZuletzt[1] < 50L) return
        hellZuletzt[0] = wert.toLong(); hellZuletzt[1] = jetzt
        try { Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, wert) } catch (e: Exception) { }
    }

    val kacheln = buildList {
        if (Taschenlampe.da) add(Kachel("Taschenlampe", if (Taschenlampe.an) "An" else "Aus", Taschenlampe.an) { Taschenlampe.umschalten() })
        add(Kachel("Nicht stören", if (dnd) "An" else "Aus", dnd) {
            if (!dndRecht || nm == null) {
                Toast.makeText(ctx, Sprache.tr("Einmal „Zugriff auf Nicht stören“ für den Launcher erlauben"), Toast.LENGTH_LONG).show()
                oeffne(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            } else if (!Ruhe.setzen(ctx, nm, !dnd)) {
                Toast.makeText(ctx, Sprache.tr("Android lässt „Nicht stören“ gerade nicht zu"), Toast.LENGTH_SHORT).show()
            }
            // Android braucht einen Moment - danach neu anzeigen
            Handler(Looper.getMainLooper()).postDelayed({ auffrischen() }, 400)
        })
        // Flugmodus darf keine App selbst schalten -> Androids Schnelleinstellungen aufklappen,
        // dort ist er mit einem Tipp an/aus
        add(Kachel("Flugmodus", if (flug) "An" else "Aus", flug) { (ctx as? MainActivity)?.schnelleinstellungen() })
        add(Kachel("WLAN", if (wlan) "An" else "Aus", wlan) { panel(Settings.Panel.ACTION_WIFI, Settings.ACTION_WIFI_SETTINGS) })
        add(Kachel("Internet", "Mobile Daten", false) { panel(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Settings.ACTION_WIRELESS_SETTINGS) })
        add(Kachel("Bluetooth", if (bt) "An" else "Aus", bt) { (ctx as? MainActivity)?.schnelleinstellungen() })
        add(Kachel("Ton", when (klingel) {
            AudioManager.RINGER_MODE_VIBRATE -> "Vibration"; AudioManager.RINGER_MODE_SILENT -> "Lautlos"; else -> "Klingeln"
        }, klingel != AudioManager.RINGER_MODE_NORMAL) {
            try {
                audio?.ringerMode = when (klingel) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE -> if (dndRecht) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
            } catch (e: Exception) { }
            auffrischen()
        })
        add(Kachel("Lautstärke", "Regler", false) { panel(Settings.Panel.ACTION_VOLUME, Settings.ACTION_SOUND_SETTINGS) })
        add(Kachel("Auto-Drehen", if (drehen) "An" else "Aus", drehen) {
            if (!darfSchreiben) schreibRecht()
            else { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (drehen) 0 else 1); auffrischen() }
        })
        add(Kachel("Standort", if (standort) "An" else "Aus", standort) { oeffne(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
        add(Kachel("Energiesparen", if (sparen) "An" else "Aus", sparen) { oeffne(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) })
        add(Kachel("Hotspot", "Einstellungen", false) {
            oeffne(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
        })
        if (nfc != null) add(Kachel("NFC", if (nfc.isEnabled) "An" else "Aus", nfc.isEnabled) { oeffne(Intent(Settings.ACTION_NFC_SETTINGS)) })
        add(Kachel("Timer", "Stellen", false) { oeffne(Intent(AlarmClock.ACTION_SET_TIMER)) })
    }

    val zeile = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    Column(Modifier.fillMaxWidth()) {
        // Helligkeit: wie eine App, nur mit Regler
        Row(zeile, verticalAlignment = Alignment.CenterVertically) {
            SchnellPunkt(!autoHell)
            Spacer(Modifier.width(18.dp))
            Text("Helligkeit", fontSize = 19.sp, maxLines = 1,
                 modifier = Modifier.clickable {
                     if (!darfSchreiben) schreibRecht()
                     else {
                         Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                             if (autoHell) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                         auffrischen()
                     }
                 })
            Spacer(Modifier.width(10.dp))
            Slider(
                value = hell,
                onValueChange = { v ->
                    hell = v
                    if (darfSchreiben) {
                        if (autoHell) Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                        hellSchreiben(v, sofort = false)
                    }
                },
                onValueChangeFinished = {
                    if (!darfSchreiben) schreibRecht()
                    else { hellSchreiben(hell, sofort = true); auffrischen() }      // Endwert immer schreiben
                },
                modifier = Modifier.weight(1f).height(32.dp),
            )
        }
        kacheln.forEach { k ->
            Row(Modifier.fillMaxWidth().clickable { k.aktion() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SchnellPunkt(k.aktiv)
                Spacer(Modifier.width(18.dp))
                Text(k.name, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(k.status, color = if (k.aktiv) p.akzent else p.leise, fontSize = 14.sp, maxLines = 1)
            }
            // Staerke der Taschenlampe direkt darunter (wenn das Handy das kann)
            if (k.name == "Taschenlampe" && Taschenlampe.an && Taschenlampe.maxStufe > 1) {
                var stufe by remember { mutableFloatStateOf(1f) }
                Row(Modifier.fillMaxWidth().padding(start = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                    Slider(value = stufe, onValueChange = { stufe = it; Taschenlampe.stufe(it.roundToInt()) },
                           valueRange = 1f..Taschenlampe.maxStufe.toFloat(), modifier = Modifier.weight(1f).height(32.dp))
                }
            }
        }
        // ganz unten: Einstellungen des Launchers (Zahnrad)
        val navi = LocalNavi.current
        Row(Modifier.fillMaxWidth().clickable { navi.auf(Ebene.Einstellungen()) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { Symbol("zahnrad", p.text.copy(alpha = 0.7f), 22.dp) }
            Spacer(Modifier.width(18.dp))
            Text("Einstellungen", fontSize = 19.sp, maxLines = 1, modifier = Modifier.weight(1f))
        }
    }
}

/** Statt App-Icon: Kreis, gefuellt wenn eingeschaltet */
@Composable
private fun SchnellPunkt(an: Boolean) {
    val p = LocalPalette.current
    Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(if (an) p.akzent else Color.Transparent)
                .border(1.5.dp, if (an) p.akzent else p.leise, CircleShape)
        )
    }
}
