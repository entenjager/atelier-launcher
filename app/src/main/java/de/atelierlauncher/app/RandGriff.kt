package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** ein Griff am Rand: zeichnet einen schmalen Streifen und erkennt die Wischrichtung */
internal class RandGriff(
    ctx: Context,
    /** "l" links, "r" rechts, "ul" / "ur" Ecke unten links / rechts */
    private val rand: String,
    private val ausloesen: (String, Boolean) -> Unit,
    private val durchreichen: (FloatArray, Long) -> Unit,
    private val welle: (Float, Float, String?) -> Unit,
    private val welleLos: (Boolean) -> Unit,
    private val beruehrt: () -> Unit,
    /** grosse Kante ganz drin, aber noch keine Richtung erkannt -> Kante ausblenden */
    private val abbrechen: () -> Unit,
) : View(ctx) {
    private var abgebrochen = false
    // Wisch-Tempo (fuer "schnell gewischt = frueher ausloesen")
    private var tempo = 0f
    private var tempoDx = 0f
    private var tempoZeit = 0L
    private var amRand = false
    private var maxDx = 0f
    private var welleLaeuft = false
    // Fingerweg (x, y, x, y, ...) fuer das Durchreichen
    private var weg = FloatArray(64)
    private var wegN = 0
    private var startZeit = 0L
    private fun merke(x: Float, y: Float) {
        if (wegN + 2 > weg.size) weg = weg.copyOf(weg.size * 2)
        weg[wegN++] = x; weg[wegN++] = y
    }
    var deckkraft = 0.25f
    /** zuletzt schnell ausgeloest? Dann spielt die Animation von selbst (schnell gezeichnet) statt dem Finger zu folgen */
    var schnellAusgeloest = false
    var vorschau = false
    var vibrieren = true
    private val d = resources.displayMetrics.density
    private val links get() = rand == "l"
    private val farbe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flaeche = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    private var x0 = 0f; private var y0 = 0f
    private var ruheX = 0f; private var ruheY = 0f
    private var richtung: String? = null
    private var gedrueckt = false
    private val schwelle get() = 22 * d          // so weit reinwischen = erkannt

    override fun onDraw(c: Canvas) {
        val b = width.toFloat(); val h = height.toFloat()
        if (vorschau) {
            // ganze Beruehrflaeche zeigen
            flaeche.color = 0x5533AAFF
            r.set(0f, 0f, b, h)
            c.drawRoundRect(r, 6 * d, 6 * d, flaeche)
        }
        // 100 % durchsichtig: nie etwas zeichnen (auch nicht beim Druecken) - nur die blaue Vorschau oben
        if (deckkraft <= 0f) return
        val a = if (gedrueckt) maxOf(deckkraft, 0.55f) else deckkraft
        farbe.color = 0xFFFFFFFF.toInt()
        farbe.alpha = (minOf(a, 1f) * 255).roundToInt().coerceIn(0, 255)
        // uebersteuert (ueber 100 %): Streifen breiter
        val streifen = minOf(b, (if (deckkraft > 1f) 5f + (deckkraft - 1f) * 50f else 5f) * d)
        when (rand) {
            "l" -> r.set(0f, 0f, streifen, h)
            "ul", "ur" -> r.set(0f, h - minOf(h, streifen), b, h)
            else -> r.set(b - streifen, 0f, b, h)
        }
        c.drawRoundRect(r, streifen, streifen, farbe)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.rawX; y0 = e.rawY; ruheX = x0; ruheY = y0
                richtung = null; gedrueckt = true; welleLaeuft = false; abgebrochen = false; amRand = false; maxDx = 0f
                beruehrt()                      // Bildschirmfoto fuer die Linse schon mal holen
                wegN = 0; merke(x0, y0); startZeit = e.eventTime
                tempo = 0f; tempoDx = 0f; tempoZeit = e.eventTime
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (wegN < 2000) merke(e.rawX, e.rawY)
                if (abgebrochen) return true
                // dx = wie weit nach innen, dy = quer dazu (unten: nach links = "o", nach rechts = "u")
                val ecke = rand == "ul" || rand == "ur"
                // Ecke: "hoch" und "zur Mitte" (links und rechts gespiegelt = gleich behandelt)
                val hoch = y0 - e.rawY
                val mitte = if (rand == "ur") x0 - e.rawX else e.rawX - x0
                val dx = when (rand) { "l" -> e.rawX - x0; "r" -> x0 - e.rawX; else -> hypot(maxOf(hoch, 0f), maxOf(mitte, 0f)) }
                val dy = if (ecke) 0f else e.rawY - y0
                if (dx >= schwelle * 0.55f && hypot(dx, dy) >= schwelle) {
                    val neu = if (ecke) {
                        // 0 Grad = gerade hoch, 90 Grad = flach zur Mitte
                        val w = Math.toDegrees(atan2(maxOf(mitte, 0f).toDouble(), maxOf(hoch, 0f).toDouble()))
                        when { w < 28 -> "g"; w < 62 -> "o"; else -> "u" }
                    } else {
                        val w = Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))
                        when { w > 28 -> "o"; w < -28 -> "u"; else -> "g" }
                    }
                    if (richtung == null && vibrieren) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    richtung = neu
                }
                // Tempo nach innen (geglaettet, px pro ms)
                val dt = (e.eventTime - tempoZeit).coerceAtLeast(1L)
                tempo = tempo * 0.5f + ((dx - tempoDx) / dt) * 0.5f
                tempoDx = dx; tempoZeit = e.eventTime
                // Ausloesen ohne Loslassen: bei 1/5 der Breite - oder schon bei 1/10, wenn schnell gewischt wird
                val breite = resources.displayMetrics.widthPixels.toFloat()
                val schnell = tempo >= 1.2f * d && richtung != null
                if (dx * 1.1f >= breite / 5f || (schnell && dx >= breite / 10f)) {
                    abgebrochen = true; welleLaeuft = false
                    val rr = richtung
                    schnellAusgeloest = tempo >= 1.2f * d
                    if (rr != null) { welleLos(true); ausloesen(rr, false) } else abbrechen()
                    return true
                }
                // Welle erst, wenn der Finger wirklich nach innen zieht (nicht beim Tippen)
                if (dx > 3 * d || welleLaeuft) { welleLaeuft = true; welle(e.rawY, maxOf(dx, 0f), richtung) }
                // Finger wieder zurueck an den Rand = Geste zuruecknehmen
                // weitester Punkt merken; mehr als halb zurueckgezogen = zuruecknehmen
                if (dx > maxDx) maxDx = dx
                amRand = richtung != null && (dx < schwelle * 0.5f || dx < maxDx * 0.5f)
            }
            MotionEvent.ACTION_UP -> {
                gedrueckt = false; invalidate()
                if (abgebrochen) { richtung = null; return true }     // schon ausgeloest (Kante war ganz drin)
                if (amRand) {                         // zurueck zum Rand gezogen: nichts tun
                    if (welleLaeuft) { welleLaeuft = false; welleLos(false) }
                    richtung = null; amRand = false; return true
                }
                val rr = richtung
                // vor dem Fuenftel losgelassen: keine Aktion, Welle faehrt zurueck
                if (welleLaeuft) { welleLaeuft = false; welleLos(false) }
                // keine Randgeste -> war ein normales Tippen/Scrollen: an die App darunter weitergeben
                if (rr == null) { merke(e.rawX, e.rawY); durchreichen(weg.copyOf(wegN), e.eventTime - startZeit) }
                richtung = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (welleLaeuft) { welleLaeuft = false; welleLos(false) }
                gedrueckt = false; richtung = null; invalidate()
            }
        }
        return true
    }
}
