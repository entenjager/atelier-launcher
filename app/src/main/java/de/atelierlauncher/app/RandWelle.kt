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

/**
 * Kleine Welle am Rand: Beule folgt dem Finger (mit leichtem Kraeuseln),
 * beim Ausloesen schwingt sie kurz nach und klingt aus. Nicht anfassbar.
 */
internal class RandWelle(ctx: Context, private val links: Boolean, val akzent: Int, val dunkel: Boolean) : View(ctx) {
    var fertig: (() -> Unit)? = null
    /** in Ruhe (Fenster 1 Pixel) - wird fuer die naechste Geste wiederverwendet statt neu angelegt */
    var ruht = false
    /** Geste vorbei (losgelassen) -> naechste Bewegung ist eine neue Geste */
    val vorbeiOderLos get() = los || vorbei

    /** fuer die naechste Geste alles auf Anfang */
    fun neu() {
        fy = 0f; fingerY = 0f; zug = 0f; richtung = null; erkannt = false
        tiefe = 0f; tempo = 0f; versatz = 0f; versatzTempo = 0f; phase = 0f
        los = false; stark = false; losZeit = 0L; vorbei = false; letzteZeit = 0L; erstes = true
        winkel = 0f; winkelTempo = 0f
        geboren = android.os.SystemClock.uptimeMillis()
        ruht = false
        invalidate()
    }
    private val d = resources.displayMetrics.density
    // Theme: Flaeche in der Akzentfarbe, Kante im dunklen Theme heller, im hellen Theme kraeftiger/dunkler
    private val kanteFarbe = if (dunkel) mischen(akzent, 0xFFFFFFFF.toInt(), 0.35f) else mischen(akzent, 0xFF000000.toInt(), 0.25f)
    private val fuell = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = akzent or 0xFF000000.toInt() }
    private val linie = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = kanteFarbe; style = Paint.Style.STROKE; strokeWidth = 2.2f * d; strokeCap = Paint.Cap.ROUND
    }
    private fun mischen(a: Int, b: Int, t: Float): Int {
        fun k(x: Int, sh: Int) = (x shr sh) and 0xFF
        fun m(sh: Int) = (k(a, sh) + (k(b, sh) - k(a, sh)) * t).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }
    private val form = Path()
    private val kante = Path()
    // Einrasten statt Mitschwimmen:
    //  - vor dem Erkennen: kleine Beule mit Gummiband-Widerstand
    //  - Geste erkannt: springt auf feste Groesse (Rastpunkt), Richtung = Beule rastet hoch/mittig/runter
    //  - Loslassen: kurzer "Klick" (ausgeloest) bzw. sauberes Zurueckziehen, kein Nachwackeln
    private var fy = 0f                // angezeigte Mitte
    private var fingerY = 0f
    private var zug = 0f
    private var richtung: String? = null
    private var erkannt = false
    private var tiefe = 0f
    private var tempo = 0f             // Feder-Geschwindigkeit der Tiefe
    private var versatz = 0f           // Rast-Versatz nach oben/unten
    private var versatzTempo = 0f
    private var phase = 0f
    private var los = false
    private var stark = false
    private var losZeit = 0L
    private var vorbei = false
    private var letzteZeit = 0L
    private var erstes = true

    fun ziehen(y: Float, z: Float, ri: String?) {
        if (los) return
        // Position wird beim Reinwischen festgelegt und bleibt dann stehen (auch bei Gesten schraeg runter/hoch)
        if (erstes) { fy = y; fingerY = y; erstes = false }
        zug = z; richtung = ri; erkannt = ri != null
        postInvalidateOnAnimation()
    }

    fun loslassen(ausgeloest: Boolean) {
        if (los) return
        los = true; stark = ausgeloest
        losZeit = android.os.SystemClock.uptimeMillis()
        if (ausgeloest) tempo += 900f * d          // kleiner Klick nach aussen
        postInvalidateOnAnimation()
    }

    /** gedaempfte Feder (leichtes Ueberschwingen = "rastet ein") */
    private fun feder(wert: Float, v: Float, ziel: Float, steif: Float, daempf: Float, dt: Float): Pair<Float, Float> {
        val a = steif * (ziel - wert) - daempf * v
        val nv = v + a * dt
        return (wert + nv * dt) to nv
    }

    override fun onDraw(c: Canvas) {
        if (vorbei) return
        val jetzt = android.os.SystemClock.uptimeMillis()
        val dt = if (letzteZeit == 0L) 0.016f else ((jetzt - letzteZeit) / 1000f).coerceIn(0.004f, 0.034f)
        letzteZeit = jetzt
        val rast = (if (Randgesten.strich) 30f else if (Randgesten.tropfen) 38f else if (Randgesten.kante) 26f else if (Randgesten.pfeil) 34f else if (Randgesten.skizze) 28f else 30f) * d
        var hell: Float
        if (!los) {
            val ziel = if (erkannt) rast else minOf(zug * 0.32f, rast * 0.45f)
            val (t, v) = feder(tiefe, tempo, ziel, 1100f, 2f * kotlin.math.sqrt(1100f) * 0.62f, dt)
            tiefe = t; tempo = v
            fy = fingerY                // fest: wandert nicht mit dem Finger
            hell = if (erkannt) 1f else 0.55f
        } else {
            // sauber zurueck: kritisch gedaempft, kein Wackeln. Ausgeloest: weicher und laenger (klingt aus),
            // abgebrochen: zuegig wie bisher
            // Probe: ausgeloest -> laeuft noch weiter nach innen und verschwindet dabei
            val steif = if (stark) 260f else 900f
            val (t, v) = feder(tiefe, tempo, if (stark) rast * 2.4f else 0f, steif, 2f * kotlin.math.sqrt(steif), dt)
            tiefe = t; tempo = v
            val seit = (jetzt - losZeit).toFloat()
            val weich = if (stark) (1f - (seit / 520f)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) } else 1f
            hell = if (stark) weich else 0.55f * (tiefe / rast).coerceIn(0f, 1f).let { if (seit < 60f) maxOf(it, 0.5f) else it }
            if ((seit > 60f && ((!stark && tiefe < 0.6f * d) || weich <= 0.01f)) || seit > 700f) { vorbei = true; fertig?.invoke(); return }
        }
        val b = width.toFloat()
        val rand = if (links) 0f else b
        val sgn = if (links) 1f else -1f
        if (Randgesten.tropfen) { tropfenMalen(c, rand, sgn, hell); postInvalidateOnAnimation(); return }
        if (Randgesten.kante) { kanteMalen(c, rand, sgn, hell); postInvalidateOnAnimation(); return }
        if (Randgesten.pfeil) { pfeilMalen(c, rand, sgn, hell, dt); postInvalidateOnAnimation(); return }
        if (Randgesten.skizze) { skizzeMalen(c, rand, sgn, hell); postInvalidateOnAnimation(); return }
        if (Randgesten.strich) { strichMalen(c, rand, sgn, hell); postInvalidateOnAnimation(); return }
        val hw = 44f * d + kotlin.math.abs(tiefe) * 0.9f       // halbe Breite der Beule
        val n = 48
        form.reset(); kante.reset()
        form.moveTo(rand, fy - hw * 1.8f)
        for (i in 0..n) {
            val yy = fy + (i / n.toFloat() * 2f - 1f) * hw * 1.8f
            val u = (yy - fy) / hw
            val glocke = kotlin.math.exp(-u * u * 1.6f)
            // saubere Beule, kein Kraeuseln
            val amp = tiefe * glocke
            val x = rand + sgn * amp
            form.lineTo(x, yy)
            if (i == 0) kante.moveTo(x, yy) else kante.lineTo(x, yy)
        }
        form.lineTo(rand, fy + hw * 1.8f)
        form.close()
        fuell.alpha = (hell * (if (dunkel) 85 else 70)).roundToInt().coerceIn(0, 255)
        linie.alpha = (hell * (if (dunkel) 190 else 220)).roundToInt().coerceIn(0, 255)
        c.drawPath(form, fuell)
        c.drawPath(kante, linie)
        postInvalidateOnAnimation()
    }

    /**
     * Gezeichnet: wie der Swiss-Block (rastet ein, harte Kante), aber von Hand: Papier als Grund,
     * schraege Kuli-Striche innen, wackliger Rand (fest pro Geste - flackert nicht)
     */
    private val strichFarbe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 4f * d
        color = if (dunkel) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    }
    private fun strichMalen(c: Canvas, rand: Float, sgn: Float, hell: Float) {
        val t = tiefe
        if (t < 0.5f * d) return
        val x = rand + sgn * (t * 0.6f + 6f * d)
        val halb = 28f * d
        strichFarbe.alpha = (255 * hell.coerceIn(0f, 1f)).roundToInt()
        c.drawLine(x, fy - halb, x, fy + halb, strichFarbe)
    }

    private val skStift = Skizze.stift(d, 1.8f)
    private val skLeicht = Skizze.stift(d, 0.9f, alpha = 80)
    private val skSchr = Skizze.stift(d, 1.1f, alpha = 170)
    private val skPapier = Paint().apply { color = Skizze.PAPIER }
    private val skKante = Path()
    private val skNach = Path()
    private val skSchraff = Path()
    private var skSchluessel = Long.MIN_VALUE
    private fun skizzeMalen(c: Canvas, rand: Float, sgn: Float, hell: Float) {
        val t = tiefe
        if (t < 0.5f * d) return
        val saat = (geboren % 100_000L).toInt()
        val halb = 22f * d
        val innen = rand + sgn * t
        // aussen reicht der Block etwas hinter den Bildschirmrand (dort sieht man keinen Rahmen)
        val aussen = rand - sgn * 8f * d
        val l = minOf(aussen, innen); val r = maxOf(aussen, innen)
        val o = fy - halb; val u = fy + halb
        val a = hell.coerceIn(0f, 1f)
        // Papier als Grund (das Viereck ist "voll")
        Skizze.laden(context); Skizze.nachfaerben(skStift, skLeicht, skSchr); Skizze.papierFaerben(skPapier)
        skPapier.alpha = (255 * a).roundToInt()
        c.drawRect(l, o, r, u, skPapier)
        // schraege Striche: einmal pro Geste gebaut (grosszuegig), dann nur auf den Block beschnitten
        val schl = geboren * 31 + fy.toLong()
        if (schl != skSchluessel) {
            skSchluessel = schl
            skSchraff.reset()
            val weit = rand + sgn * 120f * d
            Skizze.schraffur(skSchraff, minOf(rand, weit) - 8f * d, o - 6f * d, maxOf(rand, weit) + 8f * d, u + 6f * d, saat, d, 5f)
        }
        c.save(); c.clipRect(l + 2f * d, o + 2f * d, r - 2f * d, u - 2f * d)
        skSchr.alpha = (170 * a).roundToInt()
        c.drawPath(skSchraff, skSchr)
        c.restore()
        // wackliger Rand (wird mit der Tiefe breiter, Zittern bleibt gleich)
        skKante.reset(); skNach.reset()
        Skizze.kasten(skKante, l, o, r, u, 3f * d, saat, d)
        Skizze.kasten(skNach, l, o, r, u, 3f * d, saat + 77, d, 0.7f * d)
        skStift.alpha = (235 * a).roundToInt(); c.drawPath(skKante, skStift)
        skLeicht.alpha = (80 * a).roundToInt(); c.drawPath(skNach, skLeicht)
    }

    /** Swiss: roter Block mit harter Kante + weisse Marke innen; Richtung = Block rastet hoch/mittig/runter */
    private val block = Paint().apply { style = Paint.Style.FILL }
    /**
     * Pfeil: Schaft vom Rand herein, Spitze zeigt in die erkannte Richtung
     * (gerade / schraeg hoch / schraeg runter) - so siehst du vor dem Loslassen, welche Geste kommt.
     */
    private val pfeilStift = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val pfeilForm = Path()
    private var winkel = 0f
    private var winkelTempo = 0f
    private fun pfeilMalen(c: Canvas, rand: Float, sgn: Float, hell: Float, dt: Float) {
        val l = tiefe
        if (l < 1f * d) return
        // Richtung rastet mit kleiner Feder ein (Grad: hoch = negativ, Bildschirm-y zeigt nach unten)
        val ziel = when (richtung) { "o" -> -38f; "u" -> 38f; else -> 0f }
        val (w, wv) = feder(winkel, winkelTempo, ziel, 900f, 2f * kotlin.math.sqrt(900f) * 0.75f, dt)
        winkel = w; winkelTempo = wv
        val bog = Math.toRadians(winkel.toDouble())
        val dx = (sgn * kotlin.math.cos(bog)).toFloat(); val dy = kotlin.math.sin(bog).toFloat()
        val lang = l * 1.9f
        val sx = rand; val sy = fy
        val tx = sx + dx * lang; val ty = sy + dy * lang
        // Spitze: zwei Schenkel, je 40 Grad zurueck
        val arm = l * 0.62f
        fun schenkel(dreh: Double): Pair<Float, Float> {
            val a = bog + dreh
            val ax = (-sgn * kotlin.math.cos(a)).toFloat(); val ay = (-kotlin.math.sin(a)).toFloat()
            return (tx + ax * arm) to (ty + ay * arm * 1f)
        }
        val (ax1, ay1) = schenkel(Math.toRadians(40.0) * sgn)
        val (ax2, ay2) = schenkel(-Math.toRadians(40.0) * sgn)
        pfeilStift.color = akzent or 0xFF000000.toInt()
        pfeilStift.alpha = (255 * hell.coerceIn(0f, 1f)).roundToInt()
        pfeilStift.strokeWidth = 5f * d
        pfeilForm.reset()
        pfeilForm.moveTo(sx, sy); pfeilForm.lineTo(tx, ty)
        pfeilForm.moveTo(ax1, ay1); pfeilForm.lineTo(tx, ty); pfeilForm.lineTo(ax2, ay2)
        c.drawPath(pfeilForm, pfeilStift)
    }

    private fun kanteMalen(c: Canvas, rand: Float, sgn: Float, hell: Float) {
        val t = tiefe
        if (t < 0.5f * d) return
        val halb = 22f * d
        val innen = rand + sgn * t
        block.color = akzent or 0xFF000000.toInt()
        block.alpha = (255 * hell.coerceIn(0f, 1f)).roundToInt()
        c.drawRect(minOf(rand, innen), fy - halb, maxOf(rand, innen), fy + halb, block)
        // weisse Marke an der Innenkante (2 dp)
        block.color = 0xFFFFFFFF.toInt()
        block.alpha = (255 * hell.coerceIn(0f, 1f)).roundToInt()
        val m = innen + sgn * 4f * d
        c.drawRect(minOf(m, m + sgn * 2f * d), fy - halb, maxOf(m, m + sgn * 2f * d), fy + halb, block)
    }

    /** Wassertropfen haengt am Rand: rund, ohne Kraeuseln, sackt durch die Schwerkraft leicht nach unten */
    private val linse = Linse.randPinsel()
    private var geboren = android.os.SystemClock.uptimeMillis()

    private fun tropfenMalen(c: Canvas, rand: Float, sgn: Float, hell: Float) {
        val t = tiefe
        if (t < 1.5f * d) return                                    // nach aussen geschwungen -> nicht zu sehen
        val hw = 34f * d + t * 0.9f
        val sack = t * 0.18f                                        // Schwerpunkt etwas tiefer
        val n = 48
        val aussen = rand - sgn * 12f * d                           // flache Seite hinter dem Bildschirmrand
        form.reset()
        form.moveTo(aussen, fy - hw * 1.7f)
        for (i in 0..n) {
            val yy = fy + (i / n.toFloat() * 2f - 1f) * hw * 1.7f
            val mitte = fy + sack
            val breite = if (yy > mitte) hw * 1.12f else hw * 0.88f // unten voller, oben schlanker
            val u = (yy - mitte) / breite
            // runde Kuppe statt Glocke: wie ein Tropfen mit Oberflaechenspannung
            val k = (1f - u * u * 0.55f).coerceAtLeast(0f)
            val profil = if (k <= 0f) 0f else kotlin.math.sqrt(k) * kotlin.math.exp(-u * u * 0.9f)
            form.lineTo(rand + sgn * t * profil, yy)
        }
        form.lineTo(aussen, fy + hw * 1.7f)
        form.close()
        // durch den Tropfen sieht man den Bildschirm gekruemmt (wie durch eine Linse)
        val foto = Linse.frisch(geboren - 400)
        val lp = linse
        if (foto != null && lp != null) {
            // echte Optik im Shader (Brechung, Fresnel, Glanz, Kaustik) - fertig
            Linse.binden(lp, this, foto)
            Linse.wert(lp, "rand", rand); Linse.wert(lp, "sgn", sgn); Linse.wert(lp, "fy", fy)
            Linse.wert(lp, "sack", sack); Linse.wert(lp, "hw", hw); Linse.wert(lp, "tiefe", t)
            Linse.wert(lp, "hell", hell.coerceIn(0f, 1f)); Linse.wert(lp, "dunkel", if (dunkel) 1f else 0f)
            Linse.malen(c, form, lp)
            return
        }
        // ohne Bildschirmfoto (oder altes Android): gezeichneter Tropfen
        GlasTropfen.malen(c, form, akzent, dunkel, hell, d,
            glanzX = rand + sgn * t * 0.55f, glanzY = fy + sack - hw * 0.45f, glanzR = t * 0.17f)
    }
}
