package de.atelierlauncher.app

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock

// ============================================================================
//  Animation beim Oeffnen einer App: die App waechst aus der angetippten Zeile heraus.
//  "aufziehen" = Fenster klappt aus der Zeile auf (wie beim Pixel), "zoom" = waechst aus der Zeile,
//  "aus" = Samsung-Standard, "sofort" = gar keine Animation (App ist sofort da - Standard). Die Laenge folgt den Animations-Faktoren aus den Entwickleroptionen.
// ============================================================================
object StartRahmen {
    private val r = IntArray(4)
    @Volatile private var wann = 0L

    /** angetippte Zeile merken (Fensterkoordinaten) */
    fun merken(rahmen: IntArray) {
        rahmen.copyInto(r)
        wann = SystemClock.uptimeMillis()
    }

    fun art(ctx: Context): String {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        // einmalig (22.10.): "sofort" fuehlte sich langsam an - ohne Animation bleibt der Launcher stehen, bis die
        // App ihr erstes Bild hat (bei WhatsApp/TikTok ~1 s). "wachsen": das App-Fenster waechst SOFORT aus der
        // angetippten Stelle, waehrend die App im Hintergrund fertig startet (so macht es auch der Void Launcher)
        if (!p.getBoolean("appAnimationWachsen", false)) {
            p.edit().putString("appAnimation", "wachsen").putBoolean("appAnimationWachsen", true).putBoolean("appAnimationSofort", true).apply()
            return "wachsen"
        }
        return p.getString("appAnimation", "wachsen") ?: "wachsen"
    }

    fun artSetzen(ctx: Context, a: String) =
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("appAnimation", a).apply()

    /**
     * Android zeigt beim Start sofort ein Start-Fenster der App (Hintergrundfarbe + Symbol), bis die App ihr
     * erstes Bild hat - schlicht in der App-Farbe, ohne Symbol (das Symbol wird am Ende noch ausgeblendet = langsamer). Ohne Angabe entscheidet das System - bei manchen Launchern zeigt es dann nichts und der
     * Launcher bleibt stehen, bis die App fertig ist. Darum ausdruecklich anfordern (ab Android 13).
     */
    private fun ActivityOptions.mitStartFenster(): Bundle {
        if (Build.VERSION.SDK_INT >= 33) try { splashScreenStyle = android.window.SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR } catch (e: Throwable) { }
        return toBundle()
    }

    /** passende Start-Animation */
    fun optionen(akt: MainActivity, intent: Intent): Bundle? = try { optionenRoh(akt, intent) } catch (e: Throwable) { null }

    private fun optionenRoh(akt: MainActivity, intent: Intent): Bundle? {
        val art = art(akt)
        // Sofort: keine Fenster-Animation - die App steht im naechsten Bild da (egal von wo gestartet)
        if (art == "sofort") return try { ActivityOptions.makeCustomAnimation(akt, 0, 0).mitStartFenster() } catch (e: Exception) { null }
        if (art == "wachsen") {
            val v = akt.window?.decorView ?: return null
            val frisch = SystemClock.uptimeMillis() - wann < 800 && r[2] > 0 && r[3] > 0
            // aus der angetippten Zeile / dem Baustein - sonst aus der Bildschirmmitte
            val x = if (frisch) r[0] else v.width / 2 - 1; val y = if (frisch) r[1] else v.height / 2 - 1
            val b = if (frisch) r[2] else 2; val h = if (frisch) r[3] else 2
            if (frisch) {
                val ort = IntArray(2); v.getLocationOnScreen(ort)
                intent.sourceBounds = Rect(ort[0] + x, ort[1] + y, ort[0] + x + b, ort[1] + y + h)
            }
            return try { ActivityOptions.makeScaleUpAnimation(v, x, y, b, h).mitStartFenster() } catch (e: Exception) { null }
        }
        val basis = ActivityOptions.makeBasic().mitStartFenster()
        if (SystemClock.uptimeMillis() - wann > 800 || r[2] <= 0 || r[3] <= 0) return basis    // nicht aus einer Zeile
        if (art == "aus") return basis
        val v = akt.window?.decorView ?: return basis
        val ort = IntArray(2); v.getLocationOnScreen(ort)
        // Apps wissen damit, von wo sie gestartet wurden (manche animieren selbst daraus)
        intent.sourceBounds = Rect(ort[0] + r[0], ort[1] + r[1], ort[0] + r[0] + r[2], ort[1] + r[1] + r[3])
        return try {
            when (art) {
                "zoom" -> ActivityOptions.makeScaleUpAnimation(v, r[0], r[1], r[2], r[3])
                else -> ActivityOptions.makeClipRevealAnimation(v, r[0], r[1], r[2], r[3])
            }.mitStartFenster()
        } catch (e: Exception) { basis }
    }
}
