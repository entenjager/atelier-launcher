package de.atelierlauncher.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ============================================================================
//  Erweiterungen (nur oeffentliche Version): Randgesten & eigener Lautstaerke-Regler sind beim Installieren
//  noch nicht da. Erst in den Einstellungen "laden" - dann wird der Dienst dafuer freigeschaltet
//  (vorher taucht er nicht einmal in Androids Bedienungshilfen auf). "Entfernen" schaltet ihn wieder ab.
// ============================================================================
object Erweiterung {
    var randgesten by mutableStateOf(false)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun laden(ctx: Context) {
        randgesten = prefs(ctx).getBoolean("erw_randgesten", false)
    }

    /** Erweiterung installieren / entfernen: Dienst frei- bzw. abschalten */
    fun randgestenSetzen(ctx: Context, an: Boolean) {
        try {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, LautDienst::class.java),
                if (an) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        } catch (e: Exception) { }
        prefs(ctx).edit().putBoolean("erw_randgesten", an).apply()
        if (!an) prefs(ctx).edit().putBoolean("rg_an", false).apply()
        randgesten = an
    }
}
