package de.atelierlauncher.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

// ============================================================================
//  Sperrbildschirm-Seite, Randgesten und eigener Lautstaerke-Regler laufen nur bei der App,
//  die gerade als Startbildschirm eingestellt ist. Die andere ruht komplett.
// ============================================================================
object Standard {
    @Volatile private var stand = true
    @Volatile private var zeit = 0L

    /** ist diese App gerade der Standard-Startbildschirm? (kurz gemerkt, wird oft gefragt) */
    fun bin(ctx: Context): Boolean {
        val jetzt = android.os.SystemClock.uptimeMillis()
        if (jetzt - zeit < 3000L && zeit != 0L) return stand
        zeit = jetzt
        stand = try {
            val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            val p = r?.activityInfo?.packageName
            // noch kein Standard gewaehlt (Android fragt dann) -> nicht ruhen
            p == null || p == ctx.packageName || p == "android"
        } catch (e: Exception) { true }
        return stand
    }
}
