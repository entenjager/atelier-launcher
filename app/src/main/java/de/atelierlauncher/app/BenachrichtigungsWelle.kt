package de.atelierlauncher.app

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as ACanvas
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin

// ============================================================================
//  Benachrichtigungs-Welle: kommt eine Nachricht (WhatsApp, Signal, Mail ...), laeuft oben eine kleine Welle
//  in der Farbe der App herein und ist verblasst, bevor sie ganz unten ist. Form passend zu "Hinter dem Alphabet"
//  (Welle, Swiss-Kante, Swiss-Linie, Kreise). Nur auf dem Startbildschirm, nichts wird gespeichert.
// ============================================================================

internal object BenachrichtigungsWelle {
    class Ereignis(val farbe: Int, val start: Long, val saat: Int)
    val laufend = mutableStateListOf<Ereignis>()
    private val farben = HashMap<String, Int>()
    private val zuletzt = HashMap<String, Long>()

    /** vom Benachrichtigungs-Dienst: ist das eine echte neue Nachricht? */
    fun neu(ctx: Context, sbn: StatusBarNotification) {
        if (!Sichtbar.an) return                                    // nur, wenn man den Startbildschirm sieht
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        if (!p.getBoolean("benWelle", true)) return
        val n = sbn.notification ?: return
        if (sbn.packageName == ctx.packageName || sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        val nachricht = n.category in setOf(Notification.CATEGORY_MESSAGE, Notification.CATEGORY_EMAIL, Notification.CATEGORY_SOCIAL,
                                             Notification.CATEGORY_CALL, Notification.CATEGORY_REMINDER, Notification.CATEGORY_EVENT) ||
                        n.extras?.containsKey(Notification.EXTRA_MESSAGES) == true
        if (!nachricht) return
        // dieselbe App kurz hintereinander (Aktualisierungen, Gruppen): nur einmal
        val jetzt = System.currentTimeMillis()
        if (jetzt - (zuletzt[sbn.packageName] ?: 0L) < 1500) return
        zuletzt[sbn.packageName] = jetzt
        val farbe = if (p.getBoolean("benWelleFarbe", true)) farbe(ctx, sbn.packageName) else 0
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            while (laufend.size >= 3) laufend.removeAt(0)
            laufend.add(Ereignis(farbe, android.os.SystemClock.uptimeMillis(), (jetzt % 10000).toInt()))
        }
    }

    /** Hauptfarbe des App-Icons: die kraeftigste Farbe (graue / weisse Pixel zaehlen kaum) */
    private fun farbe(ctx: Context, paket: String): Int = farben.getOrPut(paket) {
        try {
            val d = ctx.packageManager.getApplicationIcon(paket)
            val bm = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 24, 24); d.draw(ACanvas(bm))
            var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
            val hsv = FloatArray(3)
            for (y in 0 until 24) for (x in 0 until 24) {
                val c = bm.getPixel(x, y)
                if ((c ushr 24) < 128) continue
                android.graphics.Color.colorToHSV(c, hsv)
                val gew = (hsv[1] * hsv[2]).toDouble().let { it * it } + 0.001
                r += ((c shr 16) and 255) * gew; g += ((c shr 8) and 255) * gew; b += (c and 255) * gew; w += gew
            }
            if (w <= 0.0) 0 else {
                val c = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
                // etwas kraeftiger und hell genug, damit man es auf Schwarz sieht
                android.graphics.Color.colorToHSV(c, hsv)
                if (hsv[1] < 0.15f) 0 else { hsv[1] = maxOf(hsv[1], 0.55f); hsv[2] = maxOf(hsv[2], 0.85f); android.graphics.Color.HSVToColor(hsv) }
            }
        } catch (e: Exception) { 0 }
    }
}

/** Schicht ueber dem Startbildschirm (unter dem Alphabet) */
@Composable
internal fun BenachrichtigungsSchicht(modifier: Modifier, form: String, standardFarbe: Color, tiefeDp: Int, dauerMs: Int) {
    val liste = BenachrichtigungsWelle.laufend
    if (liste.isEmpty() || form == "aus") return
    val uhr = remember { mutableLongStateOf(0L) }
    LaunchedEffect(liste.size) {
        while (BenachrichtigungsWelle.laufend.isNotEmpty()) {
            withFrameNanos { uhr.longValue = android.os.SystemClock.uptimeMillis() }
            BenachrichtigungsWelle.laufend.removeAll { uhr.longValue - it.start > dauerMs }
        }
    }
    Canvas(modifier) {
        val jetzt = uhr.longValue
        if (jetzt == 0L) return@Canvas
        val tiefe = tiefeDp * density
        for (e in liste) {
            val p = ((jetzt - e.start).toFloat() / dauerMs).coerceIn(0f, 1f)
            val farbe = if (e.farbe != 0) Color(e.farbe) else standardFarbe
            // herein: schnell los, sanft weiter; Deckkraft ist weg, bevor die Welle ganz unten ist
            val weg = 1f - (1f - p) * (1f - p)
            val a = ((1f - p / 0.85f).coerceIn(0f, 1f)).let { it * it }
            if (a < 0.01f) continue
            when (form) {
                "kante" -> kanteOben(farbe, a, weg, tiefe, e.saat)
                "linie" -> linieOben(farbe, a, weg, tiefe)
                "kreise" -> kreiseOben(farbe, a, weg, tiefe, e.saat)
                else -> welleOben(farbe, a, weg, tiefe, jetzt, e.saat)
            }
        }
    }
}

private fun DrawScope.welleOben(farbe: Color, a: Float, weg: Float, tiefe: Float, jetzt: Long, saat: Int) {
    val b = size.width
    val y0 = tiefe * weg
    val t = jetzt / 1000f
    val pfad = Path()
    fun y(x: Float, extra: Float) = y0 * (0.55f + 0.45f * sin(PI.toFloat() * x / b)) + extra +
        5f * density * sin(x / (22f * density) + t * 2.2f + saat)
    pfad.moveTo(0f, 0f)
    var x = 0f
    while (x <= b) { pfad.lineTo(x, y(x, 0f)); x += 6f * density }
    pfad.lineTo(b, 0f); pfad.close()
    drawPath(pfad, farbe.copy(alpha = 0.30f * a))
    for ((k, versatz) in listOf(0f, 10f, 20f).withIndex()) {
        val linie = Path()
        x = 0f
        linie.moveTo(0f, y(0f, versatz * density))
        while (x <= b) { linie.lineTo(x, y(x, versatz * density)); x += 6f * density }
        drawPath(linie, farbe.copy(alpha = a * (if (k == 0) 0.95f else if (k == 1) 0.45f else 0.2f)),
                 style = Stroke((if (k == 0) 2.2f else 1.2f) * density, cap = StrokeCap.Round))
    }
}

private fun DrawScope.kanteOben(farbe: Color, a: Float, weg: Float, tiefe: Float, saat: Int) {
    val z = 24f * density
    val tinte = Color(0xFF8C8C8C)
    val reihen = (tiefe / z).coerceAtLeast(1f)
    val n = (size.width / z).toInt() + 1
    val r = java.util.Random(saat.toLong())
    for (i in 0 until n) {
        val h = ((0.3f + 0.7f * r.nextFloat()) * reihen * weg).toInt().coerceAtLeast(if (weg > 0.2f) 1 else 0)
        for (j in 0 until h) if (r.nextFloat() < 0.85f)
            drawRect(tinte.copy(alpha = 0.5f * a), Offset(i * z, j * z), Size(z, z), style = Stroke(1f * density))
    }
    drawRect(farbe.copy(alpha = a), Offset(size.width / 2f - 30f * density, z * reihen * weg - 3f * density), Size(60f * density, 6f * density))
}

private fun DrawScope.linieOben(farbe: Color, a: Float, weg: Float, tiefe: Float) {
    val y = tiefe * weg
    drawLine(Color(0xFF8C8C8C).copy(alpha = 0.5f * a), Offset(0f, y), Offset(size.width, y), 1f * density)
    drawRect(farbe.copy(alpha = a), Offset(size.width / 2f - 4f * density, y - 4f * density), Size(8f * density, 8f * density))
}

private fun DrawScope.kreiseOben(farbe: Color, a: Float, weg: Float, tiefe: Float, saat: Int) {
    val cx = size.width * (0.3f + 0.4f * ((saat % 100) / 100f))
    for (k in 0..1) {
        val p = (weg - k * 0.2f).coerceAtLeast(0f)
        if (p <= 0f) continue
        drawCircle(farbe.copy(alpha = a * (if (k == 0) 0.8f else 0.4f)), tiefe * 1.6f * p, Offset(cx, 0f), style = Stroke(2f * density))
    }
}
