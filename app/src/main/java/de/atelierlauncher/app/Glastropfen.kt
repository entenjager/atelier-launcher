package de.atelierlauncher.app

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.roundToInt

// ============================================================================
//  Wassertropfen auf Glas - reine Vektorgrafik (Pfad + Verlaeufe + weiche Kanten).
//  Wie echtes Wasser: fast durchsichtig, Schatten darunter, dunkler Rand durch
//  Lichtbrechung, heller Saum oben, gebuendeltes Licht (Kaustik) unten, Glanzpunkt.
// ============================================================================
internal object GlasTropfen {
    private val schatten = Paint(Paint.ANTI_ALIAS_FLAG)
    private val koerper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rand = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val glanz = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val unschaerfe = HashMap<Int, BlurMaskFilter>()

    private fun weich(px: Float): BlurMaskFilter {
        val k = px.roundToInt().coerceAtLeast(1)
        return unschaerfe.getOrPut(k) { BlurMaskFilter(k.toFloat(), BlurMaskFilter.Blur.NORMAL) }
    }

    private fun farbe(rgb: Int, alpha: Float) = (alpha.roundToInt().coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

    private fun mischen(a: Int, b: Int, t: Float): Int {
        fun k(x: Int, sh: Int) = (x shr sh) and 0xFF
        fun m(sh: Int) = (k(a, sh) + (k(b, sh) - k(a, sh)) * t).roundToInt().coerceIn(0, 255)
        return (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    /**
     * form = geschlossener Umriss des Tropfens. sicht = 0..1 (Ein-/Ausblenden).
     * glanzX/Y/R = Glanzpunkt (R = 0 -> keiner).
     */
    fun malen(c: Canvas, form: Path, akzent: Int, dunkel: Boolean, sicht: Float, d: Float,
              glanzX: Float, glanzY: Float, glanzR: Float) {
        val a = sicht.coerceIn(0f, 1f)
        if (a <= 0.01f) return
        form.computeBounds(r, true)
        if (r.width() < 1f || r.height() < 1f) return

        // Kein Schatten, keine Fuellung: der Tropfen selbst ist klar - man sieht nur,
        // wie er das Licht bricht (dunkler Rand, heller Saum, Lichtfleck, Glanz)
        // 3. Innen: alles nur innerhalb des Tropfens
        c.save()
        c.clipPath(form)
        // dunkler Rand (Lichtbrechung am Rand)
        rand.maskFilter = weich(3.5f * d); rand.strokeWidth = 7f * d
        rand.color = farbe(0, (if (dunkel) 140f else 90f) * a)
        c.drawPath(form, rand)
        // gebuendeltes Licht unten innen (Kaustik), leicht in Theme-Farbe
        c.save(); c.translate(0f, -2.6f * d)
        rand.maskFilter = weich(2.5f * d); rand.strokeWidth = 3.2f * d
        rand.color = farbe(mischen(akzent, 0xFFFFFF, 0.6f), 95f * a)
        c.drawPath(form, rand)
        c.restore()
        // heller Saum oben innen
        c.save(); c.translate(0f, 1.6f * d)
        rand.maskFilter = weich(1f * d); rand.strokeWidth = 1.6f * d
        rand.color = farbe(0xFFFFFF, 160f * a)
        c.drawPath(form, rand)
        c.restore()
        c.restore()

        // 4. hauchduenne Kontur
        rand.maskFilter = null; rand.strokeWidth = 0.8f * d
        rand.color = if (dunkel) farbe(0xFFFFFF, 50f * a) else farbe(0, 40f * a)
        c.drawPath(form, rand)

        // 5. Glanzpunkt (Spiegelung einer Lichtquelle) + kleiner Zweitreflex
        if (glanzR > 0.8f * d) {
            glanz.color = farbe(0xFFFFFF, 215f * a)
            glanz.maskFilter = weich(glanzR * 0.35f)
            r.set(glanzX - glanzR, glanzY - glanzR * 0.6f, glanzX + glanzR, glanzY + glanzR * 0.6f)
            c.drawOval(r, glanz)
            glanz.maskFilter = null
            glanz.color = farbe(0xFFFFFF, 235f * a)
            c.drawCircle(glanzX + glanzR * 0.9f, glanzY + glanzR * 1.15f, glanzR * 0.2f, glanz)
        }
    }
}
