package de.atelierlauncher.app

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

// ============================================================================
//  Tempo beim App-Oeffnen und Zurueckkommen:
//   leicht = die ersten Bilder nach dem Zurueckkommen ohne Unschaerfe / Glas / Wellenbewegung
//            (so steht der Startbildschirm sofort da), danach blendet alles weich ein
//   ruhe   = Finger liegt auf einer App: Wellen anhalten, damit die App gleich die volle Leistung hat
// ============================================================================
internal object Heim {
    var leicht by mutableStateOf(false)
    var ruhe by mutableStateOf(false)
    /** Hintergrund-Bewegung gerade anhalten */
    val still: Boolean get() = leicht || ruhe

    private val h = Handler(Looper.getMainLooper())
    private val weiter = Runnable { if (Sichtbar.an) ruhe = false }

    fun druecken() { h.removeCallbacks(weiter); if (!ruhe) ruhe = true }
    /** losgelassen: wurde keine App gestartet (z. B. nur gescrollt), geht es kurz danach weiter */
    fun loslassen() { h.removeCallbacks(weiter); h.postDelayed(weiter, 350) }
    // seit wann der Startbildschirm unveraendert "in Ruhe" ist (0 = gerade nicht)
    @Volatile private var ruhigSeit = 0L
    fun ruhigSetzen(r: Boolean) {
        if (!r) ruhigSeit = 0L else if (ruhigSeit == 0L) ruhigSeit = android.os.SystemClock.uptimeMillis()
    }
    /** darf jetzt ein Foto fuer das Zurueckkommen gemacht werden? (Popup-Animationen sind dann sicher vorbei) */
    fun fotoErlaubt() = ruhigSeit != 0L && android.os.SystemClock.uptimeMillis() - ruhigSeit > 600

    /** Launcher wieder vorne */
    fun zurueck() { h.removeCallbacks(weiter); ruhe = false }
}

/** merkt, wann ein Finger auf einer App liegt - nimmt dem Tippen / Scrollen nichts weg */
internal fun Modifier.appDruck(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        Heim.druecken()
        try {
            do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed })
        } finally { Heim.loslassen() }
    }
}

/**
 * Abstand zu den Systemleisten auf dem Startbildschirm. Ist die Statusleiste ausgeblendet (Standard), zaehlt sie
 * gar nicht mit: Beim Zurueckkommen aus einer App war sie kurz da und verschwand dann wieder - dabei rutschte der
 * ganze Startbildschirm sichtbar nach unten und wieder hoch (sah aus, als wuerde er hereingeschoben).
 */
@Composable
internal fun Modifier.leistenAbstand(): Modifier =
    if (LocalZustand.current.statusAus) this.navigationBarsPadding() else this.systemBarsPadding()
