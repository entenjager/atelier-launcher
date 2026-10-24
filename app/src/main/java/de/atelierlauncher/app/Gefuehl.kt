package de.atelierlauncher.app

import android.content.Context
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView

// ============================================================================
//   - Vibration: kurzes Antippen-Gefuehl bei Bausteinen, beim langen Druecken (Baukasten)
//   - Federn: Bausteine werden beim Druecken minimal kleiner und federn zurueck
//   - Weiche Uebergaenge: Baukasten gleitet beim Einrasten, Favoriten / Buchstabe / Seite bei # blenden ueber
//   - Suche: startet die App sofort, sobald nur noch eine passt
// ============================================================================
object Gefuehl {
    private val vib = mutableStateOf(true)
    private val feder = mutableStateOf(true)
    private val weich = mutableStateOf(true)
    private val sofort = mutableStateOf(false)

    val vibration get() = vib.value
    val federn get() = feder.value
    val uebergaenge get() = weich.value
    val sucheSofort get() = sofort.value

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun laden(ctx: Context) {
        val p = prefs(ctx)
        vib.value = p.getBoolean("gefuehlVibration", true)
        feder.value = p.getBoolean("gefuehlFedern", true)
        weich.value = p.getBoolean("gefuehlWeich", true)
        sofort.value = p.getBoolean("sucheSofort", false)
    }

    fun setzen(ctx: Context, was: String, an: Boolean) {
        when (was) { "vibration" -> vib.value = an; "federn" -> feder.value = an; "weich" -> weich.value = an; "sofort" -> sofort.value = an }
        val k = when (was) { "vibration" -> "gefuehlVibration"; "federn" -> "gefuehlFedern"; "weich" -> "gefuehlWeich"; else -> "sucheSofort" }
        prefs(ctx).edit().putBoolean(k, an).apply()
    }

    /** Bausteine, die beim Druecken federn / vibrieren (nicht: Listen, Musik mit eigenen Tasten, Rahmen, Alphabet, unsichtbare) */
    val DRUCK_TYPEN = setOf("app", "lampe", "ruhe", "ton", "webseite", "kamera", "wlan", "bluetooth", "nachrichten",
                            "akku", "termin", "suche", "uhr", "eigen", "wlanstand", "mobil", "netz", "akkupille")
}

/** Antippen fuehlbar machen: federt beim Druecken, kurzes Vibrieren beim Antippen. Faengt nichts ab. */
@Composable
internal fun Modifier.druckGefuehl(aktiv: Boolean): Modifier {
    if (!aktiv || (!Gefuehl.federn && !Gefuehl.vibration)) return this
    val view = LocalView.current
    var gedrueckt by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (gedrueckt && Gefuehl.federn) 0.95f else 1f,
                                 spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "druck")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val start = down.position
                val t0 = down.uptimeMillis
                var tipp = true
                gedrueckt = true
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if ((c.position - start).getDistance() > viewConfiguration.touchSlop) { tipp = false; gedrueckt = false }
                        if (!c.pressed) { if (c.uptimeMillis - t0 > 450) tipp = false; break }
                    }
                } finally { gedrueckt = false }
                if (tipp && Gefuehl.vibration) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            }
        }
}

/** langes Druecken (Baukasten oeffnet sich): deutliches Vibrieren */
internal fun langDruckGefuehl(view: android.view.View) {
    if (Gefuehl.vibration) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}
