package de.atelierlauncher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

// ============================================================================
//  umschalten. Unsichtbar = Samsungs eigene Wischsteuerung ohne den Strich unten:
//  nichts zu sehen, von unten nach oben wischen schickt die App in den Hintergrund.
//  Eine App darf diese Samsung-Einstellung nur mit einer Sondererlaubnis aendern,
//  die einmal per USB vergeben wird (das Installationsskript macht das).
// ============================================================================
object Navileiste {
    private const val GESTEN = "navigation_bar_gesture_while_hidden"   // 0 = Tasten, 1 = Wischen
    private const val STRICH = "navigation_bar_gesture_hint"           // 1 = Strich unten zeigen, 0 = aus

    fun darf(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun istUnsichtbar(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        return Settings.Global.getInt(cr, GESTEN, 0) == 1 && Settings.Global.getInt(cr, STRICH, 1) == 0
    }

    /** true = umgestellt */
    fun setzen(ctx: Context, unsichtbar: Boolean): Boolean = try {
        val cr = ctx.contentResolver
        Settings.Global.putInt(cr, GESTEN, if (unsichtbar) 1 else 0)
        Settings.Global.putInt(cr, STRICH, if (unsichtbar) 0 else 1)
        true
    } catch (e: Exception) { false }
}
