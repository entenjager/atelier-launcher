package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs

// ============================================================================
//  Von unten nach oben wischen = Startbildschirm, so schnell wie moeglich:
//  geht der Launcher auf - ohne Animation, ohne auf das Loslassen zu warten. Alles andere (Tippen auf
//  die Samsung-Tasten, Scrollen) wird an das Darunter weitergereicht.
//  Aus: auf dem Startbildschirm selbst (dort ist unten-nach-oben die Suche) und bei offener Tastatur.
// ============================================================================
object UntenWisch {
    private var griff: UntenGriff? = null
    private var lp: WindowManager.LayoutParams? = null
    private var ruht = false
    private var reicht = false
    private val haupt = Handler(Looper.getMainLooper())
    private var fenster: WindowManager? = null
    /** wann zuletzt nach Hause geschickt (fuer die Messung: wie schnell ist der Launcher da) */
    @Volatile var heimZeit = 0L
    /** Sperrbildschirm: Streifen aus (sonst faengt er das Entsperr-Wischen ab) */
    private var gesperrt = false
    private var zuletztSoll = false

    /** von SperrSeite.pruefen (Bildschirm an/aus, entsperrt) */
    fun sperre(an: Boolean) {
        if (an == gesperrt) return
        gesperrt = an
        val wm = fenster ?: return
        if (an) ruhen(wm) else if (zuletztSoll && griff != null && ruht) { ruht = false; if (!reicht) anfassbar(wm, true) }
    }

    fun an(p: SharedPreferences) = p.getBoolean("rg_unten", true)

    /**
     * Samsungs Wischgesten an? Dann wischt Samsung selbst nach Hause - unser Streifen wuerde den Launcher
     * erst sofort oeffnen und Samsung danach nochmal mit Animation (doppelt). Also: dann ist der Streifen aus.
     */
    fun samsungWischt(ctx: Context): Boolean = try {
        android.provider.Settings.Secure.getInt(ctx.contentResolver, "navigation_mode", 0) == 2 ||
            android.provider.Settings.Global.getInt(ctx.contentResolver, "navigation_bar_gesture_while_hidden", 0) == 1
    } catch (e: Exception) { false }

    /** von Randgesten.anwenden() - gleiche Lage (Launcher vorne, Tastatur) */
    internal fun anwenden(dn: AccessibilityService, wm: WindowManager, p: SharedPreferences, launcherVorne: Boolean, tastatur: Boolean,
                          vorschau: Boolean = false, ausnahme: Boolean = false) {
        fenster = wm
        // Einstellungsseite offen: blau zeigen, damit man sieht, wo der Streifen liegt.
        // Apps unter "In diesen Apps aus" (z. B. Spiele, Videos): Streifen aus - dort gilt Samsungs Wischen
        zuletztSoll = an(p) && (vorschau || (!launcherVorne && !tastatur && !ausnahme && !samsungWischt(dn) && Standard.bin(dn)))
        griff?.let { if (it.vorschau != vorschau) { it.vorschau = vorschau; it.invalidate() } }
        val soll = zuletztSoll && !gesperrt
        val g = griff
        if (!soll) { if (g != null) ruhen(wm); return }
        if (g == null) {
            val d = dn.resources.displayMetrics.density
            val neu = UntenGriff(dn, { heim(dn) }, { punkte, dauer -> durchreichen(dn, wm, punkte, dauer) })
            neu.vorschau = vorschau
            val l = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, (12 * d).toInt(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.LEFT
                if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                windowAnimations = 0
                title = "Unten wischen"
            }
            try { wm.addView(neu, l); griff = neu; lp = l; ruht = false; android.util.Log.d("LauncherUnten", "Streifen angelegt") }
            catch (e: Exception) { android.util.Log.d("LauncherUnten", "Streifen FEHLER: $e") }
            return
        }
        if (ruht) {
            ruht = false
            if (!reicht) anfassbar(wm, true)
        }
    }

    private fun ruhen(wm: WindowManager) {
        if (ruht) return
        ruht = true
        anfassbar(wm, false)
    }

    fun entfernen(wm: WindowManager?) {
        griff?.let { try { wm?.removeView(it) } catch (e: Exception) { } }
        griff = null; lp = null; ruht = false; reicht = false
    }

    private fun anfassbar(wm: WindowManager, an: Boolean) {
        val g = griff ?: return; val l = lp ?: return
        val f = if (an) l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else l.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (f == l.flags) return
        l.flags = f
        try { wm.updateViewLayout(g, l) } catch (e: Exception) { }
    }

    /**
     * Launcher direkt nach vorne holen, ohne Fenster-Animation (schneller als die Home-Taste).
     * Gleichzeitig drueckt der Dienst "Home" wie die Taste - es geht also immer, ohne Wartezeit.
     */
    private fun heim(dn: AccessibilityService) {
        val ok = try {
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(dn.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            dn.startActivity(i, ActivityOptions.makeCustomAnimation(dn, 0, 0).toBundle())
            true
        } catch (e: Exception) { false }
        // KEIN "Home" hinterher: das loest Samsungs normale Animation aus (genau die soll weg).
        // Nur als Notfall, falls Android den direkten Start blockiert hat (dann ist der Launcher nach 250 ms nicht da).
        if (!ok) { dn.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); return }
        heimZeit = android.os.SystemClock.uptimeMillis()
        haupt.postDelayed({
            if (!Sichtbar.vorne) {
                android.util.Log.d("LauncherUnten", "direkter Start blockiert -> Home-Taste")
                dn.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }, 250)
    }

    /** kein Wisch nach oben: Beruehrung an das Darunter (Samsung-Tasten, App) weitergeben */
    private fun durchreichen(dn: AccessibilityService, wm: WindowManager, punkte: FloatArray, dauer: Long) {
        if (reicht || punkte.size < 2) return
        reicht = true
        anfassbar(wm, false)
        val pfad = Path().apply {
            moveTo(punkte[0], punkte[1])
            var i = 2
            while (i + 1 < punkte.size) { lineTo(punkte[i], punkte[i + 1]); i += 2 }
        }
        val zurueck = Runnable { reicht = false; if (!ruht) anfassbar(wm, true) }
        haupt.postDelayed({
            val ok = try {
                dn.dispatchGesture(
                    GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(pfad, 0L, dauer.coerceIn(1L, 5_000L))).build(),
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) { haupt.removeCallbacks(zurueck); haupt.post(zurueck) }
                        override fun onCancelled(d: GestureDescription?) { haupt.removeCallbacks(zurueck); haupt.post(zurueck) }
                    }, haupt)
            } catch (e: Exception) { false }
            if (!ok) haupt.post(zurueck) else haupt.postDelayed(zurueck, dauer + 600)
        }, 35)
    }
}

/** der flache Streifen unten: erkennt nur "nach oben" */
internal class UntenGriff(
    ctx: Context,
    private val heim: () -> Unit,
    private val durchreichen: (FloatArray, Long) -> Unit,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    var vorschau = false
    private val blau = android.graphics.Paint().apply { color = 0x5533AAFF }
    override fun onDraw(c: android.graphics.Canvas) { if (vorschau) c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), blau) }
    private var x0 = 0f; private var y0 = 0f
    private var fertig = false
    private var weg = FloatArray(64)
    private var n = 0
    private var start = 0L
    private fun merke(x: Float, y: Float) {
        if (n + 2 > weg.size) weg = weg.copyOf(weg.size * 2)
        weg[n++] = x; weg[n++] = y
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; fertig = false; n = 0; start = e.eventTime; merke(x0, y0)
                android.util.Log.d("LauncherUnten", "runter y=${e.rawY.toInt()}")
            }
            MotionEvent.ACTION_MOVE -> {
                if (fertig) return true
                if (n < 2000) merke(e.rawX, e.rawY)
                val hoch = y0 - e.rawY
                // schon 5 dp nach oben (und mehr hoch als seitlich) = sofort Startbildschirm -
                // frueher als Samsungs eigene Wischsteuerung (die greift erst nach etwa 8-10 dp zu)
                if (hoch >= 5f * d && abs(e.rawX - x0) < hoch * 1.5f) {
                    fertig = true
                    android.util.Log.d("LauncherUnten", "HOME nach ${(e.eventTime - start)} ms")
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    heim()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!fertig) { merke(e.rawX, e.rawY); durchreichen(weg.copyOf(n), (e.eventTime - start).coerceAtLeast(1L)) }
                fertig = false
            }
            MotionEvent.ACTION_CANCEL -> { android.util.Log.d("LauncherUnten", "abgebrochen (System hat uebernommen)"); fertig = false }
        }
        return true
    }
}
