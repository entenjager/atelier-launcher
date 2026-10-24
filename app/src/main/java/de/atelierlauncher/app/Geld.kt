package de.atelierlauncher.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
//  Banknoten-Look ("Greenback"): Papier, Guilloche-Rosetten, feine Linien, Ornament-Rahmen.
//  Nur das Gefuehl - keine echten Motive, Koepfe, Siegel, Schriftzuege oder Wertangaben.
// ============================================================================
object Geld {
    // Grund: alter brauner Strickpullover (hell: Kamel, dunkel: Schokobraun)
    const val PAPIER_HELL = 0xFFB59670.toInt()
    const val PAPIER_DUNKEL = 0xFF2F2219.toInt()
    const val GRUEN_HELL = 0xFF2C6646.toInt()     // Linien/Akzent auf hellem Papier
    const val GRUEN_DUNKEL = 0xFF8CC06C.toInt()   // Linien/Akzent auf dunklem Grund
    const val TINTE_HELL = 0xFF16301F.toInt()
    const val TINTE_DUNKEL = 0xFFE9E5CC.toInt()
    fun papier(dunkel: Boolean) = if (dunkel) PAPIER_DUNKEL else PAPIER_HELL
    fun gruen(dunkel: Boolean) = if (dunkel) GRUEN_DUNKEL else GRUEN_HELL
    fun tinte(dunkel: Boolean) = if (dunkel) TINTE_DUNKEL else TINTE_HELL
    /**
     * Strick-Kachel (glatt rechts): Maschen als V aus zwei schraegen Schlaufen, jede leicht gewoelbt,
     * etwas unregelmaessig wie von Hand, dazu Fusseln. Nahtlos kachelbar.
     */
    private val strickCache = HashMap<String, Bitmap>()
    @Synchronized fun strick(d: Float, dunkel: Boolean): Bitmap {
        val schl = "$d/$dunkel"
        strickCache[schl]?.let { return it }
        val sw = 8f * d; val sh = 6.5f * d
        val spalten = 8; val reihen = 10
        val tw = (sw * spalten).toInt().coerceAtLeast(8); val th = (sh * reihen).toInt().coerceAtLeast(8)
        val swE = tw / spalten.toFloat(); val shE = th / reihen.toFloat()       // exakt -> nahtlos
        val b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val grund = papier(dunkel)
        c.drawColor(mischen(grund, 0xFF000000.toInt(), 0.45f))               // dunkle Luecken zwischen den Maschen
        val zufall = java.util.Random(19)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val oval = android.graphics.RectF()
        for (r in -1..reihen) for (k in 0 until spalten) {
            val ton = mischen(grund, if (zufall.nextBoolean()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), zufall.nextFloat() * 0.07f)
            val cx = k * swE + swE / 2f; val cy = r * shE + shE / 2f
            for (seite in listOf(-1f, 1f)) for (dx in listOf(-tw.toFloat(), 0f, tw.toFloat())) {
                val mx = cx + dx + seite * swE * 0.23f
                p.shader = RadialGradient(mx, cy - shE * 0.15f, swE * 0.42f,
                    intArrayOf(mischen(ton, 0xFFFFFFFF.toInt(), 0.16f), ton, mischen(ton, 0xFF000000.toInt(), 0.35f)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.save(); c.rotate(seite * 28f, mx, cy)
                oval.set(mx - swE * 0.25f, cy - shE * 0.68f, mx + swE * 0.25f, cy + shE * 0.68f)
                c.drawOval(oval, p); c.restore()
            }
        }
        p.shader = null
        // Fusseln: kurze helle Haerchen
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f * d
        for (i in 0 until 90) {
            val x = zufall.nextFloat() * tw; val y = zufall.nextFloat() * th
            val w = (zufall.nextFloat() - 0.5f) * 3.14f; val l = (1.5f + zufall.nextFloat() * 3f) * d
            p.color = mitAlpha(mischen(grund, 0xFFFFFFFF.toInt(), 0.35f), 0.10f + zufall.nextFloat() * 0.10f)
            c.drawLine(x, y, x + kotlin.math.cos(w) * l, y + kotlin.math.sin(w) * l, p)
        }
        strickCache[schl] = b
        return b
    }

    fun mischen(a: Int, b: Int, t: Float): Int {
        fun k(x: Int, s: Int) = (x shr s) and 0xFF
        fun m(s: Int) = (k(a, s) + (k(b, s) - k(a, s)) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    fun mitAlpha(farbe: Int, a: Float) = (farbe and 0x00FFFFFF) or ((a * 255f).toInt().coerceIn(0, 255) shl 24)

    /** Rosette um (0,0): Ringe aus phasenverschobenen Sinus-Kreisen - das typische Guilloche-Geflecht */
    fun rosettePfad(r: Float, fein: Boolean = true): Path {
        val p = Path()
        fun ring(mitte: Float, amp: Float, k: Int, n: Int, schritte: Int) {
            for (i in 0 until n) {
                val ph = i * 2.0 * PI / n
                for (s in 0..schritte) {
                    val t = s * 2.0 * PI / schritte
                    val rr = mitte + amp * sin(k * t + ph)
                    val x = (rr * cos(t)).toFloat(); val y = (rr * sin(t)).toFloat()
                    if (s == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
            }
        }
        if (fein) {
            ring(r * 0.80f, r * 0.17f, 14, 14, 280)
            ring(r * 0.50f, r * 0.12f, 10, 10, 200)
            ring(r * 0.24f, r * 0.08f, 7, 8, 140)
        } else {
            ring(r * 0.78f, r * 0.18f, 9, 8, 160)
            ring(r * 0.40f, r * 0.12f, 6, 6, 110)
        }
        return p
    }
}

/**
 * Zeichnet alles Banknotige mit android.graphics (fuer Launcher, Live-Hintergrund, Randgesten, Sperrseite).
 * Eine Instanz pro Verwender (eigene Pinsel und Zwischenbilder - nicht zwischen Threads teilen).
 */
class GeldZeichner(private val d: Float) {
    private val stift = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fuell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bildP = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val pfad = Path()

    // ---- Zwischenbilder (einmal gerechnet, dann nur noch gedreht/kopiert -> kostet pro Bild fast nichts)
    private val rosetten = LinkedHashMap<Long, Bitmap>()
    /** Rosette als Bild (quadratisch, Seitenlaenge groesse px), Linien in [farbe] */
    fun rosette(groesse: Int, farbe: Int, fein: Boolean = true): Bitmap {
        val g = groesse.coerceIn(16, 2400)
        val schl = (g.toLong() shl 33) xor (farbe.toLong() and 0xFFFFFFFFL) xor (if (fein) 1L shl 32 else 0L)
        rosetten[schl]?.let { return it }
        val b = Bitmap.createBitmap(g, g, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.translate(g / 2f, g / 2f)
        stift.color = farbe; stift.strokeWidth = (g / 900f).coerceIn(0.7f, 1.6f) * d * 0.7f
        c.drawPath(Geld.rosettePfad(g / 2f - 2f, fein), stift)
        // nicht recyceln: das Bild kann noch in einer Zeichenliste stecken (sonst Absturz)
        if (rosetten.size >= 8) rosetten.remove(rosetten.keys.first())
        rosetten[schl] = b
        return b
    }

    private var statBild: Bitmap? = null
    private var statSchl = ""
    /** Papier + feine Linien + Ornament-Rahmen + Eck-Rosetten (steht still) */
    fun statisch(b: Int, h: Int, dunkel: Boolean): Bitmap {
        val schl = "$b/$h/$dunkel"
        statBild?.let { if (statSchl == schl) return it }
        val bild = Bitmap.createBitmap(b.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.RGB_565)
        val c = Canvas(bild)
        val papier = Geld.papier(dunkel); val gruen = Geld.gruen(dunkel)
        c.drawColor(papier)
        // alter brauner Pullover als Grund
        fuell.shader = android.graphics.BitmapShader(Geld.strick(d, dunkel), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        c.drawRect(0f, 0f, b.toFloat(), h.toFloat(), fuell); fuell.shader = null
        // alt und ausgeblichen: grosse weiche Flecken (heller verwaschen / dunkler abgetragen)
        val zufall = java.util.Random(4711)
        for (i in 0 until 16) {
            val x = zufall.nextFloat() * b; val y = zufall.nextFloat() * h
            val r = (0.15f + zufall.nextFloat() * 0.35f) * b
            val hell = zufall.nextBoolean()
            fuell.shader = RadialGradient(x, y, r,
                intArrayOf(if (hell) Geld.mitAlpha(0xFFE8D8B8.toInt(), if (dunkel) 0.07f else 0.14f) else Geld.mitAlpha(0xFF1A1008.toInt(), 0.10f), 0x00000000),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, b.toFloat(), h.toFloat(), fuell)
        }
        // abgegriffene Raender
        fuell.shader = RadialGradient(b * 0.5f, h * 0.45f, maxOf(b, h) * 0.75f,
            intArrayOf(0x00000000, 0x00000000, if (dunkel) 0x99000000.toInt() else 0x66301C0A),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, b.toFloat(), h.toFloat(), fuell); fuell.shader = null
        // Eck-Rosetten (klein, still) innerhalb des Rahmens
        val klein = rosette((b * 0.30f).toInt(), Geld.mitAlpha(gruen, 1f), fein = false)
        bildP.alpha = if (dunkel) 85 else 110
        val ein = 30f * d
        for ((x, yy) in listOf(ein to ein + 40f * d, b - ein - klein.width to ein + 40f * d,
                                ein to h - ein - klein.height - 30f * d, b - ein - klein.width to h - ein - klein.height - 30f * d)) {
            c.drawBitmap(klein, x, yy, bildP)
        }
        bildP.alpha = 255
        rahmen(c, b.toFloat(), h.toFloat(), dunkel)
        statBild = bild; statSchl = schl
        return bild
    }

    /** Ornament-Rahmen am Bildschirmrand: Doppellinie, Zierband aus zwei verschlungenen Wellen, Eck-Knoten */
    fun rahmen(c: Canvas, b: Float, h: Float, dunkel: Boolean) {
        val gruen = Geld.gruen(dunkel)
        val a = if (dunkel) 0.6f else 0.8f
        stift.color = Geld.mitAlpha(gruen, a)
        stift.strokeWidth = 1.3f * d; c.drawRect(9f * d, 9f * d, b - 9f * d, h - 9f * d, stift)
        stift.strokeWidth = 0.7f * d; c.drawRect(13f * d, 13f * d, b - 13f * d, h - 13f * d, stift)
        stift.strokeWidth = 0.7f * d; c.drawRect(27f * d, 27f * d, b - 27f * d, h - 27f * d, stift)
        // Zierband zwischen 13 und 27 dp: zwei gegenlaeufige Wellen
        stift.color = Geld.mitAlpha(gruen, a * 0.85f); stift.strokeWidth = 0.8f * d
        val mitte = 20f * d; val amp = 5f * d; val per = 16f * d
        for (vz in listOf(1f, -1f)) {
            // oben + unten
            for (yy in listOf(mitte, h - mitte)) {
                pfad.reset(); var x = mitte
                pfad.moveTo(x, yy)
                while (x <= b - mitte) { pfad.lineTo(x, yy + vz * amp * sin((x / per) * 2f * PI.toFloat())); x += 2f * d }
                c.drawPath(pfad, stift)
            }
            // links + rechts
            for (xx in listOf(mitte, b - mitte)) {
                pfad.reset(); var yy = mitte
                pfad.moveTo(xx, yy)
                while (yy <= h - mitte) { pfad.lineTo(xx + vz * amp * sin((yy / per) * 2f * PI.toFloat()), yy); yy += 2f * d }
                c.drawPath(pfad, stift)
            }
        }
        // Eck-Knoten: kleine Rosette + Kreis
        val k = rosette((26f * d).toInt(), Geld.mitAlpha(gruen, 1f), fein = false)
        fuell.color = Geld.papier(dunkel)
        for ((x, y) in listOf(mitte to mitte, b - mitte to mitte, mitte to h - mitte, b - mitte to h - mitte)) {
            c.drawCircle(x, y, 12f * d, fuell)
            bildP.alpha = (255 * a).toInt(); c.drawBitmap(k, x - k.width / 2f, y - k.height / 2f, bildP); bildP.alpha = 255
            stift.color = Geld.mitAlpha(gruen, a); stift.strokeWidth = 1f * d; c.drawCircle(x, y, 12f * d, stift)
        }
    }

    /** ganzer Hintergrund: Papier (still) + langsam drehende Rosetten (gegenlaeufig -> feines Moiré) */
    fun hintergrund(c: Canvas, b: Float, h: Float, dunkel: Boolean, tMs: Double) {
        val bi = b.toInt().coerceAtLeast(1); val hi = h.toInt().coerceAtLeast(1)
        c.drawBitmap(statisch(bi, hi, dunkel), 0f, 0f, null)
        val gruen = Geld.mitAlpha(Geld.gruen(dunkel), 1f)
        val w = ((tMs / 1000.0) * 1.2 % 360.0).toFloat()           // 1,2 Grad pro Sekunde
        val gross = rosette((b * 0.84f).toInt(), gruen)
        val cx = b * 0.5f; val cy = h * 0.40f
        bildP.alpha = if (dunkel) 80 else 110
        c.save(); c.rotate(w, cx, cy); c.drawBitmap(gross, cx - gross.width / 2f, cy - gross.height / 2f, bildP); c.restore()
        val mitte = rosette((b * 0.46f).toInt(), gruen)
        bildP.alpha = if (dunkel) 90 else 120
        c.save(); c.rotate(-w * 1.6f, cx, cy); c.drawBitmap(mitte, cx - mitte.width / 2f, cy - mitte.height / 2f, bildP); c.restore()
        val unten = rosette((b * 0.52f).toInt(), gruen)
        val uy = h * 0.80f
        bildP.alpha = if (dunkel) 65 else 90
        c.save(); c.rotate(w * 0.7f, cx, uy); c.drawBitmap(unten, cx - unten.width / 2f, uy - unten.height / 2f, bildP); c.restore()
        bildP.alpha = 255
    }

    /**
     * Senkrechtes Zierband als Kante (Randgesten, Sperrseite): Doppellinie, verschlungene Wellen, Doppellinie.
     * x = Kante, innen = Richtung ins Bild (+1 nach rechts, -1 nach links), a = Deckkraft.
     */
    fun band(c: Canvas, x: Float, h: Float, innen: Float, dunkel: Boolean, a: Float, phase: Float) {
        if (a <= 0.01f) return
        val gruen = Geld.gruen(dunkel)
        val breite = 18f * d
        // Papierstreifen unter dem Band, damit es auf allem lesbar ist
        fuell.color = Geld.mitAlpha(Geld.papier(dunkel), 0.92f * a)
        if (innen > 0) c.drawRect(x - breite, 0f, x, h, fuell) else c.drawRect(x, 0f, x + breite, h, fuell)
        val aussen = -innen
        stift.color = Geld.mitAlpha(gruen, a)
        stift.strokeWidth = 1.4f * d; c.drawLine(x, 0f, x, h, stift)
        stift.strokeWidth = 0.7f * d; c.drawLine(x + aussen * 3.5f * d, 0f, x + aussen * 3.5f * d, h, stift)
        c.drawLine(x + aussen * breite, 0f, x + aussen * breite, h, stift)
        val m = x + aussen * (3.5f * d + (breite - 3.5f * d) / 2f)
        val amp = (breite - 3.5f * d) / 2f - 1.5f * d
        val per = 14f * d
        stift.color = Geld.mitAlpha(gruen, 0.85f * a); stift.strokeWidth = 0.8f * d
        for (vz in listOf(1f, -1f)) {
            pfad.reset(); var y = -per
            pfad.moveTo(m, y)
            while (y <= h + per) { pfad.lineTo(m + vz * amp * sin(((y + phase) / per) * 2f * PI.toFloat()), y); y += 2f * d }
            c.drawPath(pfad, stift)
        }
        // kleine Knoten im Band
        fuell.color = Geld.mitAlpha(gruen, a)
        var y = ((phase % (per * 4)) + per * 4) % (per * 4)
        while (y < h) { c.drawCircle(m, y, 1.6f * d, fuell); y += per * 4 }
    }
}

// ---------------------------------------------------------------- Compose-Teile

/** eigene Zeit fuer die Rosetten: macht nach Pausen nahtlos weiter */
private object GeldUhr { val zeit = androidx.compose.runtime.mutableLongStateOf(0L) }

/** Hintergrund "Banknote" im Launcher */
@Composable
fun GeldHintergrund(modifier: Modifier, dunkel: Boolean, laeuft: Boolean, durchsichtig: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (durchsichtig) {
        // laeuft unser Live-Hintergrund, malt der schon genau das - nicht doppelt
        val neu = LocalZustand.current.fortsetzen
        val eigeneTapete = remember(neu) { EigeneTapete.aktiv(ctx) }
        if (eigeneTapete) return
    }
    val z = remember { GeldZeichner(ctx.resources.displayMetrics.density) }
    val zeit = GeldUhr.zeit
    LaunchedEffect(laeuft) {
        if (!laeuft) return@LaunchedEffect
        val start = androidx.compose.runtime.withFrameNanos { it } - zeit.longValue * 1_000_000
        while (true) {
            androidx.compose.runtime.withFrameNanos { n -> zeit.longValue = (n - start) / 1_000_000 }
            delay(50)      // dreht sich ganz langsam - 20 Bilder pro Sekunde reichen
        }
    }
    Canvas(modifier) {
        val t = zeit.longValue
        drawIntoCanvas { z.hintergrund(it.nativeCanvas, size.width, size.height, dunkel, t.toDouble()) }
    }
}

/** Alphabet: am Daumen blueht eine Rosette auf (dreht sich, waechst mit dem Zug nach links) */
@Composable
fun GeldRosetteDaumen(modifier: Modifier, dunkel: Boolean) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val z = remember { GeldZeichner(ctx.resources.displayMetrics.density) }
    val an = WellenReiz.beruehrt.value
    var aktiv by remember { mutableStateOf(false) }
    LaunchedEffect(an) { if (an) aktiv = true else { delay(700); aktiv = false } }
    val zeit = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    LaunchedEffect(aktiv) {
        if (!aktiv) return@LaunchedEffect
        while (true) { androidx.compose.runtime.withFrameNanos { zeit.longValue = it / 1_000_000 } }
    }
    val stand = remember { FloatArray(3) }   // [0] Groesse 0..1, [1] y, [2] Sichtbarkeit
    val bp = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
    Canvas(modifier) {
        val t = zeit.longValue
        if (!aktiv && stand[2] < 0.01f) return@Canvas
        val d = density
        val da = WellenReiz.kanteDa && WellenReiz.daumenY >= 0f
        if (da) stand[1] = WellenReiz.daumenY
        val zug = WellenReiz.zug.coerceAtLeast(0f)
        val ziel = if (da) (0.55f + zug / (180f * d)).coerceAtMost(1.6f) else 0f
        stand[0] += (ziel - stand[0]) * 0.22f
        stand[2] += ((if (da) 1f else 0f) - stand[2]) * 0.18f
        if (stand[2] < 0.01f || stand[1] < 0f) return@Canvas
        val r = 70f * d * stand[0]
        if (r < 2f) return@Canvas
        val gruen = Geld.mitAlpha(Geld.gruen(dunkel), 1f)
        val bild = z.rosette((140f * d).toInt(), gruen)
        val cx = size.width; val cy = stand[1]
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.rotate((t % 36000L) / 100f * 2f, cx, cy)        // ruhig drehen
            val s = r * 2f / bild.width
            n.scale(s, s, cx, cy)
            bp.alpha = (255 * 0.55f * stand[2]).toInt()
            n.drawBitmap(bild, cx - bild.width / 2f, cy - bild.height / 2f, bp)
            n.restore()
        }
        // feiner Ring + Knoten in Daumenhoehe
        drawCircle(Color(Geld.gruen(dunkel)).copy(alpha = 0.5f * stand[2]), radius = r * 1.04f, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
    }
}

/** Ueberschrift-Zier: kleine Raute (statt Swiss-Balken) */
@Composable
fun GeldZier(farbe: Color) {
    Canvas(Modifier.size(9.dp)) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width / 2f, 0f); lineTo(size.width, size.height / 2f)
            lineTo(size.width / 2f, size.height); lineTo(0f, size.height / 2f); close()
        }
        drawPath(p, farbe)
    }
}

/** Uhr im Oval-Rahmen (nur gezeichnet - verschiebt nichts) */
fun Modifier.geldOval(farbe: Color): Modifier = this.drawBehind {
    val d = density
    val l = -14f * d; val o = -2f * d
    val r = size.width + 14f * d; val u = size.height + 2f * d
    drawOval(farbe.copy(alpha = 0.55f), Offset(l, o), androidx.compose.ui.geometry.Size(r - l, u - o), style = Stroke(1.4f * d))
    drawOval(farbe.copy(alpha = 0.35f), Offset(l + 4f * d, o + 4f * d), androidx.compose.ui.geometry.Size(r - l - 8f * d, u - o - 8f * d), style = Stroke(0.7f * d))
    // Knoten links und rechts
    for (x in listOf(l, r)) {
        drawCircle(farbe.copy(alpha = 0.7f), radius = 3f * d, center = Offset(x, (o + u) / 2f))
        drawCircle(farbe.copy(alpha = 0.45f), radius = 6f * d, center = Offset(x, (o + u) / 2f), style = Stroke(0.8f * d))
    }
}

fun Modifier.geldRahmen(farbe: Color): Modifier = this.drawBehind {
    val d = density
    drawRect(farbe.copy(alpha = 0.55f), Offset(1f, 1f), androidx.compose.ui.geometry.Size(size.width - 2f, size.height - 2f), style = Stroke(1f * d))
    drawRect(farbe.copy(alpha = 0.30f), Offset(4f * d, 4f * d), androidx.compose.ui.geometry.Size(size.width - 8f * d, size.height - 8f * d), style = Stroke(0.6f * d))
    val k = 4f * d
    for ((x, y) in listOf(0f to 0f, size.width to 0f, 0f to size.height, size.width to size.height)) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(x, y - k); lineTo(x + k, y); lineTo(x, y + k); lineTo(x - k, y); close()
        }
        drawPath(p, farbe.copy(alpha = 0.8f))
    }
}
