package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
//  Hintergruende: alles Vektorgrafik, die jedes Bild neu gerechnet wird.
//  Keine Videodatei, kaum Arbeitsspeicher. Die Animation steht automatisch,
//  sobald der Startbildschirm nicht sichtbar ist.
// ============================================================================

@Composable
fun Hintergrund(art: String, modifier: Modifier = Modifier, laeuft: Boolean = true, nurWellen: Boolean = false, durchsichtig: Boolean = false) {
    val p = LocalPalette.current
    // Launcher nicht im Vordergrund (andere App offen, Bildschirm aus) -> keine Bilder rechnen
    val an = laeuft && Sichtbar.an && !Heim.still     // Heim.still: Finger auf einer App / gerade zurueckgekommen
    when (art) {
        "wellen" -> WellenHintergrund(modifier, if (nurWellen) Color.Transparent else p.grund,
                                      Color(wellenFarbe(LocalZustand.current.thema.welle, p.dunkel)), an)
        "video" -> VideoHintergrund(modifier, an)
        "bild" -> BildHintergrund(modifier)
        "lottie" -> LottieHintergrund(modifier, an)
        "code" -> CodeHintergrund(modifier, an)
        "pixel" -> PixelHintergrund(modifier, if (nurWellen) Color.Transparent else p.grund,
                                    Color(LocalZustand.current.pixelFarbe), an)
        "eis" -> EisHintergrund(modifier, if (nurWellen) Color.Transparent else p.grund,
                                Color(LocalZustand.current.eisFarbe), an)
        "sterne" -> SternenHintergrund(modifier, p.dunkel, an)
        "polar" -> PolarHintergrund(modifier, p.dunkel, an)
        "schwarz" -> Box(modifier.background(p.grund))
        "swiss" -> SwissHintergrund(modifier, p.dunkel, p.akzent, nurWellen, an, durchsichtig)
        "geld" -> if (!nurWellen) GeldHintergrund(modifier, p.dunkel, an, durchsichtig)
        "glas" -> if (!nurWellen) GlasHintergrund(modifier, p.dunkel, an, durchsichtig)
        "papier" -> if (!nurWellen) PapierHintergrund(modifier)
        "marmor" -> if (!nurWellen) androidx.compose.foundation.Canvas(modifier) {
            drawIntoCanvas { Deko.marmor(it.nativeCanvas, size.width, size.height) }
        }
        else -> {}   // "system": Hintergrundbild des Handys scheint durch
    }
}

/**
 * Zeit in ms fuer Animationen; nur alle [intervall] ms ein neues Bild (spart Akku).
 * laeuft = false haelt das Bild an (z. B. waehrend in der App-Liste gescrollt wird) - die
 * Animation macht danach genau dort weiter.
 */
@Composable
private fun animZeit(intervall: Long, laeuft: Boolean): State<Long> {
    val zeit = AnimUhr.zeit           // gemeinsam: Hintergrund macht nahtlos weiter, auch wenn er neu eingesetzt wird
    LaunchedEffect(intervall, laeuft) {
        if (!laeuft) return@LaunchedEffect
        val start = withFrameNanos { it } - zeit.longValue * 1_000_000
        while (true) {
            withFrameNanos { n -> zeit.longValue = (n - start) / 1_000_000 }
            delay(intervall - 8)
        }
    }
    return zeit
}

/**
 * Was die Wellen von aussen beeinflusst - einfache Zahlen, die nur beim Zeichnen gelesen werden
 * (kein Compose-State, also kein Neuaufbau, wenn der Daumen wandert).
 */
object WellenReiz {
    // Daumen am Alphabet (Bildschirm-Koordinaten in px)
    @Volatile var daumenY = -1f
    /** Finger auf dem Alphabet (Bildschirm-y, px) - immer, egal ob Wellen dem Daumen folgen */
    @Volatile var fingerY = -1f
    /** die Buchstaben des Alphabets (in der Reihenfolge, wie sie untereinander stehen) */
    @Volatile var abcListe: List<String> = emptyList()
    @Volatile var daumenDa = false
    @Volatile var kanteDa = false           // Daumen am Alphabet, aber Swiss-Kante statt Wellen
    // Lage der Alphabet-Leiste (px, Bildschirm) - fuer die Musik-Effekte ohne Daumen
    @Volatile var abcX = -1f
    @Volatile var abcOben = 0f
    @Volatile var abcUnten = 0f
    // wie weit der Daumen vom Alphabet nach links gezogen ist (px) - je weiter, desto hoeher die Wellen
    @Volatile var zug = 0f
    internal var zugWeich = 0f              // gefedert, nur im Zeichnen veraendert
    internal var zugTempo = 0f
    internal var beule = 0f                 // geglaettet 0..1, nur im Zeichnen veraendert
    internal var physikZeit = -1L
    // Akku 0..1: so weit laufen die Wellen herein
    @Volatile var akku = 1f
    internal var akkuWeich = -1f
    // Tropfen (Kreiswellen beim Antippen des Alphabets)
    private const val PLAETZE = 6
    val tropfenX = FloatArray(PLAETZE)
    val tropfenY = FloatArray(PLAETZE)
    val tropfenZeit = LongArray(PLAETZE)
    private var naechster = 0
    private var letzterTropfen = 0L
    val tropfenNr = androidx.compose.runtime.mutableIntStateOf(0)   // startet die Tropfen-Animation
    /** Daumen liegt gerade auf dem Alphabet (fuer die Daumen-Wellen bei anderen Hintergruenden) */
    val beruehrt = androidx.compose.runtime.mutableStateOf(false)
    const val TROPFEN_DAUER = 1100L

    fun tropfen(x: Float, y: Float, erzwingen: Boolean = false) {
        val jetzt = android.os.SystemClock.uptimeMillis()
        if (!erzwingen && jetzt - letzterTropfen < 140) return       // beim Wischen nicht zu viele
        letzterTropfen = jetzt
        tropfenX[naechster] = x; tropfenY[naechster] = y; tropfenZeit[naechster] = jetzt
        naechster = (naechster + 1) % PLAETZE
        tropfenNr.intValue++
    }
}

/** Laufende Zeit der Hintergrund-Animation (es laeuft immer nur ein Hintergrund) */
private object AnimUhr {
    val zeit = mutableLongStateOf(0L)
    val wellen by lazy { WellenSpeicher() }
}

internal fun zufall(n: Double): Double { val x = sin(n * 127.1 + 311.7) * 43758.5453; return x - floor(x) }
private fun ausrollen(x: Double) = 1 - (1 - x).pow(3)
private fun weich(x: Double) = (1 - cos(PI * x)) / 2
private fun glatt(a: Double, b: Double, x: Double): Double {
    val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t)
}

// ============================================================================
//  Wellen: Strand von oben, Wellen laufen von rechts 20-25 % herein und ziehen sich zurueck
// ============================================================================
private const val WELLEN = 3
private const val W_PUNKTE = 36
private const val W_ZYKLUS = 13000.0

private fun rauschen(u: Double, t: Double, s: Double) =
    0.55 * sin(2 * PI * (u * 1.3 + s) + t / 5200) +
    0.30 * sin(2 * PI * (u * 3.1 + s * 1.7) - t / 3700) +
    0.18 * sin(2 * PI * (u * 6.7 + s * 2.3) + t / 2300) +
    0.08 * sin(2 * PI * (u * 13.3 + s * 3.1) - t / 1500)

private class Blase(val y: Float, val hinter: Float, val r: Float)

/** Alles, was pro Bild wiederverwendet wird (keine neuen Objekte je Bild) */
private class WellenSpeicher {
    val xs = Array(WELLEN) { FloatArray(W_PUNKTE + 1) }
    val spur = Array(WELLEN) { FloatArray(W_PUNKTE + 1) }
    val spurDa = BooleanArray(WELLEN)
    val pfad = Path()
    val kante = Path()
    val blasen = Array(WELLEN) { w ->
        Array(32) { i ->
            Blase(zufall(w * 100.0 + i).toFloat(), zufall(w * 100.0 + i + 50).pow(1.6).toFloat(),
                  (0.6 + 2.2 * zufall(w * 100.0 + i + 90)).toFloat())
        }
    }
    val spitzen1 = PathEffect.dashPathEffect(floatArrayOf(3f, 7f, 1f, 11f, 6f, 9f, 2f, 14f))
    val spitzen2 = PathEffect.dashPathEffect(floatArrayOf(1f, 9f, 2f, 17f, 1f, 6f))

    // Linien mit fester Breite: nur neu, wenn sich die Dichte aendert
    private var strichK = -1f
    var spurStrich = Stroke(width = 1f)
    var spitzen1Strich = Stroke(width = 1f)
    var spitzen2Strich = Stroke(width = 1f)
    fun striche(k: Float) {
        if (k == strichK) return
        strichK = k
        spurStrich = Stroke(width = 1.2f * k, cap = StrokeCap.Round)
        spitzen1Strich = Stroke(width = 1.6f * k, cap = StrokeCap.Round, pathEffect = spitzen1)
        spitzen2Strich = Stroke(width = 1f * k, cap = StrokeCap.Round, pathEffect = spitzen2)
    }

    // Flaechen-Verlauf: einmal je Farbe gebaut (0..1), pro Welle nur per Matrix auf links..b gelegt.
    // Die Durchsichtigkeit kommt als alpha beim Zeichnen dazu.
    private var flaecheFarbe = Color.Unspecified
    private var flaecheVerlauf: android.graphics.LinearGradient? = null
    private lateinit var flaechePinsel: Brush
    private val lage = android.graphics.Matrix()
    fun flaeche(farbe: Color, links: Float, rechts: Float): Brush {
        if (farbe != flaecheFarbe || flaecheVerlauf == null) {
            flaecheFarbe = farbe
            val v = android.graphics.LinearGradient(
                0f, 0f, 1f, 0f,
                intArrayOf(farbe.copy(alpha = 0.62f).toArgb(), farbe.copy(alpha = 0.30f).toArgb(),
                           farbe.copy(alpha = 0.12f).toArgb(), farbe.copy(alpha = 0.04f).toArgb()),
                floatArrayOf(0f, 0.12f, 0.45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            flaecheVerlauf = v
            flaechePinsel = ShaderBrush(v)
        }
        lage.setScale(maxOf(rechts - links, 1f), 1f)
        lage.postTranslate(links, 0f)
        flaecheVerlauf?.setLocalMatrix(lage)
        return flaechePinsel
    }
}

@Composable
fun WellenHintergrund(modifier: Modifier, grund: Color, farbe: Color, laeuft: Boolean = true, nurDaumen: Boolean = false) {
    val kopie = LocalKopie.current
    // Daumen-Wellen mit 60 Bildern/s (flinker), der ruhige Hintergrund reicht mit 30
    val zeit = animZeit(if (nurDaumen) 16 else 33, laeuft)
    val speicher = AnimUhr.wellen
    Canvas(modifier) {
        if (grund.alpha > 0f) drawRect(grund)
        val t = zeit.value.toDouble()
        // Federn nur einmal pro Bild rechnen (die Wellen werden evtl. zweimal gemalt - Milchglas im Rahmen)
        if (zeit.value != WellenReiz.physikZeit) {
        WellenReiz.physikZeit = zeit.value
        // Daumen-Beule weich ein- und ausblenden, Akku-Stand weich nachziehen
        WellenReiz.beule += ((if (WellenReiz.daumenDa) 1f else 0f) - WellenReiz.beule) * 0.34f
        if (WellenReiz.akkuWeich < 0f) WellenReiz.akkuWeich = WellenReiz.akku
        WellenReiz.akkuWeich += (WellenReiz.akku - WellenReiz.akkuWeich) * 0.02f
        // Ziehen: 0..1 (halbe Bildschirmbreite = voll), gefedert -> schwappt beim Loslassen leicht nach
        val zielZug = ((if (WellenReiz.daumenDa) WellenReiz.zug else 0f) / (size.width * 0.5f)).coerceIn(0f, 1f)
        WellenReiz.zugTempo = (WellenReiz.zugTempo + (zielZug - WellenReiz.zugWeich) * 0.36f) * 0.66f
        WellenReiz.zugWeich = (WellenReiz.zugWeich + WellenReiz.zugTempo).coerceIn(-0.15f, 1.2f)
        }
        if (nurDaumen) {
            // nur beim Beruehren des Alphabets sichtbar: weich ein- und ausblenden
            if (WellenReiz.beule < 0.01f) return@Canvas
            val c = drawContext.canvas.nativeCanvas
            val ebene = c.saveLayerAlpha(0f, 0f, size.width, size.height, (WellenReiz.beule * 255).toInt().coerceIn(0, 255))
            for (w in 0 until WELLEN) welle(t, w, speicher, farbe, null)
            c.restoreToCount(ebene)
        } else {
            // Welle aus Milchglas (nicht in Kopien - sonst enthielte das Bild sich selbst)
            val glas = if (kopie) null else with(WelleGlas) { vorbereiten() }
            for (w in 0 until WELLEN) welle(t, w, speicher, farbe, glas)
        }
    }
}

/**
 * Kreiswellen, wo der Daumen das Alphabet beruehrt. Eigene Schicht ueber allem
 * (wird nicht mit abgedunkelt) und rechnet nur, solange ein Tropfen lebt.
 */
@Composable
fun TropfenSchicht(modifier: Modifier, farbe: Color) {
    val nr = WellenReiz.tropfenNr.intValue
    val uhr = remember { mutableLongStateOf(0L) }
    LaunchedEffect(nr) {
        if (nr == 0) return@LaunchedEffect
        val ende = android.os.SystemClock.uptimeMillis() + WellenReiz.TROPFEN_DAUER + 250
        while (android.os.SystemClock.uptimeMillis() < ende) {
            withFrameNanos { uhr.longValue = android.os.SystemClock.uptimeMillis() }
        }
        uhr.longValue = 0L
    }
    Canvas(modifier) {
        val jetzt = uhr.longValue
        if (jetzt == 0L) return@Canvas
        val gross = 78f * density
        for (i in WellenReiz.tropfenZeit.indices) {
            for (ring in 0..1) {
                val alter = jetzt - WellenReiz.tropfenZeit[i] - ring * 200L
                if (WellenReiz.tropfenZeit[i] == 0L || alter < 0 || alter > WellenReiz.TROPFEN_DAUER) continue
                val p = alter.toFloat() / WellenReiz.TROPFEN_DAUER
                val raus = 1f - (1f - p) * (1f - p)            // schnell los, sanft auslaufen
                val a = (1f - p) * (1f - p) * (if (ring == 0) 0.55f else 0.3f)
                drawCircle(
                    farbe.copy(alpha = a), radius = 6f * density + gross * raus,
                    center = Offset(WellenReiz.tropfenX[i], WellenReiz.tropfenY[i]),
                    style = Stroke(width = (2.2f - 1.4f * p) * density),
                )
            }
        }
    }
}

/** Kurve durch die Punkte (Catmull-Rom als kubische Bezier), optional nach rechts versetzt */
private fun kurve(p: Path, xs: FloatArray, h: Float, versatz: Float) {
    val n = xs.size - 1
    fun y(i: Int) = h * i / n
    p.moveTo(xs[0] + versatz, -4f)
    for (i in 0 until n) {
        val i0 = maxOf(0, i - 1); val i3 = minOf(n, i + 2)
        p.cubicTo(
            xs[i] + (xs[i + 1] - xs[i0]) / 6 + versatz, y(i) + (y(i + 1) - y(i0)) / 6,
            xs[i + 1] - (xs[i3] - xs[i]) / 6 + versatz, y(i + 1) - (y(i3) - y(i)) / 6,
            xs[i + 1] + versatz, y(i + 1),
        )
    }
}

private fun DrawScope.welle(t: Double, w: Int, sp: WellenSpeicher, farbe: Color, glas: androidx.compose.ui.graphics.layer.GraphicsLayer?) {
    val b = size.width; val h = size.height
    val k = density * 0.55f
    val lauf = t + w * W_ZYKLUS / WELLEN                     // Wellen kommen nacheinander
    val runde = floor(lauf / W_ZYKLUS)
    val u = (lauf % W_ZYKLUS) / W_ZYKLUS
    // jede Welle laeuft 20-25 % der Breite herein - bei leerem Akku nur gut halb so weit
    val akku = WellenReiz.akkuWeich.coerceIn(0f, 1f)
    // gezogen: alle Wellen kommen etwas weiter herein (bis knapp 1,5x)
    val gezogen = WellenReiz.zugWeich.coerceAtLeast(0f).toDouble()
    // Musik: Bass schiebt die Wellen weiter herein, jeder Schlag gibt einen kurzen Ruck
    val mk = MusikPuls.k.toDouble()
    val mBass = MusikPuls.bass * mk; val mStoss = MusikPuls.stoss * mk
    val weit = b * (0.20 + 0.05 * zufall(runde * 7 + w)) * (0.5 + 0.5 * akku) * (1 + 0.45 * gezogen) * (1 + 0.35 * mBass + 0.3 * mStoss)
    val kurz = b * 0.04
    val gipfel = 0.32
    val r = if (u < gipfel) kurz + (weit - kurz) * ausrollen(u / gipfel)
            else weit - (weit - kurz) * weich((u - gipfel) / (1 - gipfel))
    val rueckzug = if (u < gipfel) 0.0 else (u - gipfel) / (1 - gipfel)
    val amp = b * (0.016 + 0.022 * rueckzug) * (1 + 0.6 * gezogen) * (1 + 1.6 * mBass)      // gezogen / Bass: unruhigeres Wasser
    val s = zufall(runde * 13 + w * 3)
    val neigung = 0.15 * sin(runde * 2.3 + w)
    val xs = sp.xs[w]
    var links = b
    // Daumen am Alphabet: auf seiner Hoehe schiebt sich die Welle weiter herein
    val beule = WellenReiz.beule
    val dy0 = WellenReiz.daumenY
    val zz = WellenReiz.zugWeich
    val breiteBeule = h * (0.075f + 0.03f * zz.coerceAtLeast(0f))       // gezogen: breitere Beule
    for (i in 0..W_PUNKTE) {
        val v = i.toDouble() / W_PUNKTE
        val form = 0.68 + 0.32 * sin(PI * v) + neigung * (v - 0.5)
        var x = (b - r * form - amp * rauschen(v, t, s)).toFloat()
        // nasse Spur OHNE Daumen-Beule merken - sonst blieb nach weitem Ziehen eine duenne Linie
        // bis zur Mitte stehen (die Spur verblasst erst ueber ~8 s)
        if (u < gipfel) sp.spur[w][i] = x
        if (beule > 0.01f && dy0 >= 0f) {
            val d = (h * i / W_PUNKTE - dy0) / breiteBeule
            // Beule unter dem Daumen: waechst mit dem Ziehen (bis ca. 2,5x so weit wie vorher), federt nach
            x -= beule * b * (0.09f + 0.14f * zz) * kotlin.math.exp(-d * d)
        }
        xs[i] = x
        links = minOf(links, xs[i])
    }
    val sichtbar = (1 - glatt(0.72, 1.0, u)).toFloat()
    val rz = rueckzug.toFloat()

    sp.striche(k)

    // nasse Spur: weitesten Stand merken, danach verblassen
    if (u < gipfel) sp.spurDa[w] = true
    else if (sp.spurDa[w]) {
        val a = (0.16 * (1 - glatt(0.3, 0.95, u))).toFloat()
        if (a > 0.005f) {
            sp.kante.reset(); kurve(sp.kante, sp.spur[w], h, 0f)
            drawPath(sp.kante, farbe.copy(alpha = a), style = sp.spurStrich)
        }
    }
    if (sichtbar < 0.01f) return

    sp.pfad.reset(); kurve(sp.pfad, xs, h, 0f)
    sp.pfad.lineTo(b + 4, h + 4); sp.pfad.lineTo(b + 4, -4f); sp.pfad.close()
    if (glas != null) {
        // Milchglas: im Wasser der Startbildschirm darunter, unscharf (Ebene ist ggf. gespiegelt -> zurueckspiegeln)
        val nc = drawContext.canvas.nativeCanvas
        val ebene = nc.saveLayerAlpha(0f, 0f, size.width, size.height, (sichtbar * 255).toInt().coerceIn(0, 255))
        clipPath(sp.pfad) {
            scale(WelleGlas.spiegel, 1f) { drawLayer(glas) }
            drawRect(farbe.copy(alpha = 0.10f))
        }
        nc.restoreToCount(ebene)
    } else drawPath(sp.pfad, sp.flaeche(farbe, links, b), alpha = sichtbar)
    sp.kante.reset(); kurve(sp.kante, xs, h, 0f)
    // Breite aendert sich laufend -> diese eine Linie wird je Bild neu angelegt
    drawPath(sp.kante, farbe.copy(alpha = 0.9f * sichtbar * (1 - 0.6f * rz)),
             style = Stroke(width = (1.5f + 3f * (1 - rz)) * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    sp.kante.reset(); kurve(sp.kante, xs, h, (6f + 6f * rz) * k)
    drawPath(sp.kante, farbe.copy(alpha = 0.55f * sichtbar), style = sp.spitzen1Strich)
    sp.kante.reset(); kurve(sp.kante, xs, h, (14f + 14f * rz) * k)
    drawPath(sp.kante, farbe.copy(alpha = 0.35f * sichtbar), style = sp.spitzen2Strich)
    sp.blasen[w].forEachIndexed { i, bl ->
        val j = minOf(W_PUNKTE, (bl.y * W_PUNKTE).roundToInt())
        val x = xs[j] + (5f + bl.hinter * (24f + 50f * rz)) * k
        val y = bl.y * h + 4f * k * sin(t / 1900 + i).toFloat()
        drawCircle(farbe.copy(alpha = sichtbar * (0.75f - 0.5f * bl.hinter)),
                   radius = bl.r * (1 - 0.4f * rz) * k, center = Offset(x, y))
    }
}

// ============================================================================
//  Sternenhimmel: funkelnde Sterne, ab und zu eine Sternschnuppe
// ============================================================================
private class Stern(val x: Float, val y: Float, val r: Float, val phase: Float, val tempo: Float)

@Composable
fun SternenHintergrund(modifier: Modifier, dunkel: Boolean, laeuft: Boolean = true) {
    val zeit = animZeit(50, laeuft)
    val sterne = remember {
        Array(150) { i ->
            Stern(zufall(i * 1.0).toFloat(), zufall(i + 500.0).toFloat().pow(1.25f),
                  (0.5 + 1.6 * zufall(i + 900.0).pow(3)).toFloat(),
                  (zufall(i + 1300.0) * 2 * PI).toFloat(), (0.4 + 1.2 * zufall(i + 1700.0)).toFloat())
        }
    }
    val himmel = remember(dunkel) {
        Brush.verticalGradient(if (dunkel) listOf(Color(0xFF000000), Color(0xFF050A1C), Color(0xFF0B1430))
                               else listOf(Color(0xFFE9EEF6), Color(0xFFF2EEF2), Color(0xFFF8F1EA)))
    }
    val sternFarbe = if (dunkel) Color.White else Color(0xFF5A6B85)
    Canvas(modifier) {
        drawRect(himmel)
        val t = zeit.value / 1000f
        val k = density
        for (s in sterne) {
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * s.tempo + s.phase))
            drawCircle(sternFarbe.copy(alpha = a * (if (dunkel) 1f else 0.55f)), s.r * k * 0.8f,
                       Offset(s.x * size.width, s.y * size.height))
        }
        // Sternschnuppe: alle 11 Sekunden, ca. 0,9 Sekunden lang
        val runde = floor(t / 11f)
        val u = (t - runde * 11f) / 0.9f
        if (dunkel && u in 0f..1f) {
            val sx = (0.25f + 0.6f * zufall(runde * 3.0).toFloat()) * size.width
            val sy = (0.05f + 0.3f * zufall(runde * 5.0).toFloat()) * size.height
            val laenge = 160f * k
            val kopf = Offset(sx - u * 220f * k, sy + u * 110f * k)
            val schweif = Offset(kopf.x + laenge * 0.89f, kopf.y - laenge * 0.45f)
            val a = sin(PI.toFloat() * u)
            drawLine(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.9f * a), Color.Transparent), kopf, schweif),
                kopf, schweif, strokeWidth = 1.6f * k, cap = StrokeCap.Round,
            )
        }
    }
}

// ============================================================================
//  Polarlicht: drei weiche Lichtvorhaenge, die langsam wehen
// ============================================================================
@Composable
fun PolarHintergrund(modifier: Modifier, dunkel: Boolean, laeuft: Boolean = true) {
    val zeit = animZeit(50, laeuft)
    val pfad = remember { Path() }
    val farben = remember { listOf(Color(0xFF6CF5B0), Color(0xFF45D3E0), Color(0xFF9B7BFF)) }
    val himmel = remember(dunkel) {
        Brush.verticalGradient(if (dunkel) listOf(Color(0xFF02060A), Color(0xFF041218), Color(0xFF07161C))
                               else listOf(Color(0xFFEAF4F2), Color(0xFFF3F1F7), Color(0xFFF6F2EC)))
    }
    // Verlauf je Band haengt nur von der Hoehe ab -> nur neu, wenn sie sich aendert
    val baender = remember { arrayOfNulls<Brush>(3) }
    val baenderHoehe = remember { FloatArray(1) }
    Canvas(modifier) {
        drawRect(himmel)
        if (dunkel) for (i in 0 until 60) {
            drawCircle(Color.White.copy(alpha = 0.35f * zufall(i + 40.0).toFloat()), 0.9f * density,
                       Offset(zufall(i + 7.0).toFloat() * size.width, zufall(i + 77.0).toFloat() * size.height * 0.7f))
        }
        val t = zeit.value / 1000.0
        val b = size.width; val h = size.height
        val schritte = 28
        for (band in 0 until 3) {
            val basis = h * (0.16f + 0.11f * band)
            val dicke = h * (0.20f + 0.05f * band)
            val amp = h * 0.045f
            fun oben(v: Double) = (basis + amp * (sin(2 * PI * v * 1.2 + t * 0.21 + band * 1.7) +
                    0.5 * sin(2 * PI * v * 2.7 - t * 0.33 + band))).toFloat()
            pfad.reset()
            for (i in 0..schritte) {
                val v = i.toDouble() / schritte
                val x = (v * b).toFloat()
                if (i == 0) pfad.moveTo(x, oben(v)) else pfad.lineTo(x, oben(v))
            }
            for (i in schritte downTo 0) {
                val v = i.toDouble() / schritte
                val unten = oben(v) + dicke * (0.75f + 0.25f * sin(2 * PI * v * 1.6 + t * 0.17 + band).toFloat())
                pfad.lineTo((v * b).toFloat(), unten)
            }
            pfad.close()
            val staerke = (if (dunkel) 0.42f else 0.30f) * (0.75f + 0.25f * sin(t * 0.4 + band * 2).toFloat())
            if (baenderHoehe[0] != h) {
                if (band == 2) baenderHoehe[0] = h
                val c = farben[band]
                baender[band] = Brush.verticalGradient(
                    0f to c.copy(alpha = 0f),
                    0.55f to c.copy(alpha = 0.55f),
                    0.85f to c.copy(alpha = 1f),
                    1f to c.copy(alpha = 0f),
                    startY = basis - amp * 1.5f, endY = basis + amp * 1.5f + dicke,
                )
            }
            // Staerke als Durchsichtigkeit beim Zeichnen (gleiches Ergebnis wie vorher, ohne neuen Verlauf)
            baender[band]?.let { drawPath(pfad, it, alpha = staerke) }
        }
    }
}

// ============================================================================
//  Eis-Zacken: von rechts nach links stechende Eiskristalle (Vektor, Farbe einstellbar).
//  Drei Ebenen (hinten blass, vorne kraeftig, kleine am Rand), Facetten mit Licht von oben,
//  helle Kanten, ab und zu ein Glanz-Funkeln. Reagiert wie die Wellen auf den Daumen + Akku.
// ============================================================================
val EIS_FARBEN = listOf(
    "Eisblau" to 0xFFA8E6FF.toInt(), "Gletscher" to 0xFF6FD3E8.toInt(), "Weiß" to 0xFFF2FAFF.toInt(),
    "Lila" to 0xFFC3A8FF.toInt(), "Rosa" to 0xFFFFB3D9.toInt(), "Grün" to 0xFF9CF2C8.toInt(),
)

private class Zacke(val y: Float, val breite: Float, val laenge: Float, val neigung: Float, val ebene: Int)

private class EisSpeicher {
    val zacken: List<Zacke>
    val form = Path()
    init {
        val l = ArrayList<Zacke>()
        // hinten: wenige grosse, blass
        for (i in 0 until 9) {
            val y = ((i + 0.5f) / 9f + 0.05f * (zufall(i * 3.1) - 0.5).toFloat()).coerceIn(0.02f, 0.98f)
            l += Zacke(y, (0.02 + 0.016 * zufall(i * 7.7)).toFloat(), (0.22 + 0.18 * zufall(i * 5.3)).toFloat(),
                       (0.30 * (zufall(i * 9.1) - 0.5)).toFloat(), 0)
        }
        // vorne: mittlere, kraeftig
        for (i in 0 until 14) {
            val y = ((i + 0.5f) / 14f + 0.04f * (zufall(i * 4.7 + 50) - 0.5).toFloat()).coerceIn(0.01f, 0.99f)
            val schraeg = if (i % 3 == 1) 0.75 else 0.30           // ein paar deutlich schraeger
            l += Zacke(y, (0.011 + 0.013 * zufall(i * 6.1 + 50)).toFloat(), (0.12 + 0.18 * zufall(i * 2.9 + 50)).toFloat(),
                       (schraeg * (zufall(i * 8.3 + 50) - 0.5)).toFloat(), 1)
        }
        // klein am Rand: Frost-Splitter
        for (i in 0 until 22) {
            l += Zacke(zufall(i * 3.3 + 100).toFloat(), (0.006 + 0.008 * zufall(i * 1.7 + 100)).toFloat(),
                       (0.025 + 0.05 * zufall(i * 4.1 + 100)).toFloat(), (0.5 * (zufall(i * 2.2 + 100) - 0.5)).toFloat(), 2)
        }
        zacken = l
    }

    // Facetten-Verlaeufe: einmal je Farbe gebaut (Richtung 0..1), pro Zacke nur per Matrix ausgerichtet.
    // Die Deckkraft der Ebene kommt als alpha beim Zeichnen dazu (keine neuen Objekte je Bild).
    private var pinselFarbe = Color.Unspecified
    private var lichtVerlauf: android.graphics.LinearGradient? = null
    private var schattenVerlauf: android.graphics.LinearGradient? = null
    lateinit var lichtPinsel: Brush
    lateinit var schattenPinsel: Brush
    private val richtung = android.graphics.Matrix()
    fun pinsel(farbe: Color, hell: Color, dunkel: Color) {
        if (farbe == pinselFarbe && lichtVerlauf != null) return
        pinselFarbe = farbe
        val l = android.graphics.LinearGradient(0f, 0f, 1f, 0f, farbe.copy(alpha = 0.5f).toArgb(),
                                                hell.copy(alpha = 0.9f).toArgb(), android.graphics.Shader.TileMode.CLAMP)
        val s = android.graphics.LinearGradient(0f, 0f, 1f, 0f, dunkel.copy(alpha = 0.55f).toArgb(),
                                                farbe.copy(alpha = 0.75f).toArgb(), android.graphics.Shader.TileMode.CLAMP)
        lichtVerlauf = l; schattenVerlauf = s
        lichtPinsel = ShaderBrush(l); schattenPinsel = ShaderBrush(s)
    }
    /** Verlauf von (bx,by) nach (tx,ty) legen */
    fun ausrichten(bx: Float, by: Float, tx: Float, ty: Float) {
        val dx = tx - bx; val dy = ty - by
        val l = kotlin.math.sqrt(dx * dx + dy * dy)
        if (l < 0.01f) richtung.setScale(0.01f, 0.01f)
        else { richtung.setSinCos(dy / l, dx / l); richtung.postScale(l, l) }
        richtung.postTranslate(bx, by)
        lichtVerlauf?.setLocalMatrix(richtung)
        schattenVerlauf?.setLocalMatrix(richtung)
    }

    // Frost und Schnee am rechten Rand: nur neu bei anderer Farbe/Breite
    private var randFarbe = Color.Unspecified
    private var randB = -1f
    private var randK = -1f
    lateinit var frost: Brush
    lateinit var schneePinsel: Brush
    fun rand(farbe: Color, b: Float, k: Float) {
        if (farbe == randFarbe && b == randB && k == randK) return
        randFarbe = farbe; randB = b; randK = k
        frost = Brush.horizontalGradient(listOf(Color.Transparent, farbe.copy(alpha = 0.18f)), startX = b - 48f * k, endX = b)
        schneePinsel = Brush.horizontalGradient(
            listOf(lerp(Color.White, farbe, 0.25f).copy(alpha = 0.92f), Color.White.copy(alpha = 0.97f)),
            startX = b - 34f * k, endX = b)
    }
}

private val eisSpeicher by lazy { EisSpeicher() }

/** Kleine Eiszapfen an den anderen Raendern (oben, unten, links) - vereinzelt, damit es nicht einseitig wirkt.
 *  Werte: Rand (0 oben, 1 unten, 2 links), Position entlang des Rands, Laenge dp, halbe Breite dp, Neigung */
internal val RAND_ZACKEN: Array<FloatArray> by lazy {
    val l = ArrayList<FloatArray>()
    for (rand in 0..2) {
        val anzahl = if (rand == 2) 3 else 4                     // halb so viele wie vorher
        for (i in 0 until anzahl) {
            val n = rand * 37.0 + i
            // deutlicher Groessen-Unterschied: viele kleine, ab und zu ein groesserer
            val groesse = zufall(n * 2.3 + 700).pow(1.7)
            val laenge = (10 + 44 * groesse).toFloat()
            l += floatArrayOf(
                rand.toFloat(),
                ((i + 0.25f + 0.5f * zufall(n * 1.9 + 700).toFloat()) / anzahl),
                laenge,
                (1.8f + laenge * 0.11f + 1.2f * zufall(n * 3.1 + 700).toFloat()),   // Breite passt zur Laenge
                // jeder zweite leicht schraeg
                (if (i % 2 == 0) 0.12 * (zufall(n * 4.3 + 700) - 0.5) else 0.55 * (zufall(n * 4.3 + 700) - 0.5)).toFloat(),
            )
        }
    }
    l.toTypedArray()
}

/** Schneewand am rechten Rand zwischen den Zacken: wie weit sie bei Hoehe y hereinreicht (px) */
internal fun schneeRand(y: Float, k: Float): Float =
    k * (18f + 9f * kotlin.math.sin(y / k * 0.021f) + 6f * kotlin.math.sin(y / k * 0.057f + 1.3f) + 3.5f * kotlin.math.sin(y / k * 0.13f + 0.7f))

/**
 * Eine Eiszacke: Basis-Mitte (bx,by) am Rand, Richtung (dx,dy) nach innen (Einheitsvektor).
 * Die "Licht"-Seite ist links von der Richtung. Zwei Facetten + Kanten.
 */
private fun DrawScope.eisZacke(
    sp: EisSpeicher, bx: Float, by: Float, dx: Float, dy: Float, len: Float, w: Float, neig: Float,
    deck: Float, dunkel: Color, kanten: Boolean,
) {
    val form = sp.form
    val nx = -dy; val ny = dx                                       // Seitenrichtung (Licht-Seite)
    val tx = bx + dx * len - nx * len * neig; val ty = by + dy * len - ny * len * neig
    val ox = bx + nx * w; val oy = by + ny * w                      // Ecke Licht-Seite
    val ux = bx - nx * w; val uy = by - ny * w                      // Ecke Schatten-Seite
    val mx = bx - nx * w * 0.15f; val my = by - ny * w * 0.15f      // Grat-Ansatz
    sp.ausrichten(bx, by, tx, ty)                                   // Verlaeufe vorher mit sp.pinsel(...) gesetzt
    form.reset(); form.moveTo(ox, oy); form.lineTo(tx, ty); form.lineTo(mx, my); form.close()
    drawPath(form, sp.lichtPinsel, alpha = deck)
    form.reset(); form.moveTo(mx, my); form.lineTo(tx, ty); form.lineTo(ux, uy); form.close()
    drawPath(form, sp.schattenPinsel, alpha = deck)
    if (kanten) {
        val k = density
        drawLine(Color.White.copy(alpha = 0.75f * deck), Offset(ox, oy), Offset(tx, ty), strokeWidth = 1.1f * k)
        drawLine(Color.White.copy(alpha = 0.32f * deck), Offset(mx, my), Offset(tx, ty), strokeWidth = 0.8f * k)
        drawLine(dunkel.copy(alpha = 0.45f * deck), Offset(ux, uy), Offset(tx, ty), strokeWidth = 0.8f * k)
    }
}

@Composable
fun EisHintergrund(modifier: Modifier, grund: Color, farbe: Color, laeuft: Boolean = true) {
    val schnee = LocalZustand.current.eisSchnee
    val zeit = animZeit(50, laeuft)
    val sp = eisSpeicher
    val schneeForm = remember { Path() }
    val schneeGroesse = remember { FloatArray(3) }          // b, h, k, fuer die die Schnee-Form gebaut ist
    Canvas(modifier) {
        if (grund.alpha > 0f) drawRect(grund)
        val t = zeit.value.toDouble()
        // Daumen + Akku wie bei den Wellen
        WellenReiz.beule += ((if (WellenReiz.daumenDa) 1f else 0f) - WellenReiz.beule) * 0.2f
        if (WellenReiz.akkuWeich < 0f) WellenReiz.akkuWeich = WellenReiz.akku
        WellenReiz.akkuWeich += (WellenReiz.akku - WellenReiz.akkuWeich) * 0.03f
        val b = size.width; val h = size.height; val k = density
        // Frost am Rand
        sp.rand(farbe, b, k)
        drawRect(sp.frost, topLeft = Offset(b - 48f * k, 0f))
        val akku = 0.6f + 0.4f * WellenReiz.akkuWeich.coerceIn(0f, 1f)
        val dy0 = WellenReiz.daumenY
        val beule = WellenReiz.beule
        val hell = lerp(farbe, Color.White, 0.65f)
        val dunkel = lerp(farbe, Color.Black, 0.35f)
        sp.pinsel(farbe, hell, dunkel)

        // vereinzelte kleine Eiszapfen oben, unten, links
        for (z in RAND_ZACKEN) {
            val pos = z[1]
            when (z[0].toInt()) {
                0 -> eisZacke(sp, pos * b, -2f, 0f, 1f, z[2] * k, z[3] * k, z[4], 0.8f, dunkel, true)
                1 -> eisZacke(sp, pos * b, h + 2f, 0f, -1f, z[2] * k, z[3] * k, z[4], 0.8f, dunkel, true)
                else -> eisZacke(sp, -2f, pos * h, 1f, 0f, z[2] * k, z[3] * k, z[4], 0.8f, dunkel, true)
            }
        }

        var vorne = 0
        var schneeGemalt = false
        for (z in sp.zacken) {
            // Schnee zwischen den Zacken: nach der hinteren Ebene, die vorderen ragen heraus
            if (schnee && !schneeGemalt && z.ebene >= 1) {
                schneeGemalt = true
                if (schneeGroesse[0] != b || schneeGroesse[1] != h || schneeGroesse[2] != k) {
                    schneeGroesse[0] = b; schneeGroesse[1] = h; schneeGroesse[2] = k
                    schneeForm.reset()
                    schneeForm.moveTo(b + 2f, -2f)
                    var yy = -2f
                    while (yy <= h + 2f) { schneeForm.lineTo(b - schneeRand(yy, k), yy); yy += 5f * k }
                    schneeForm.lineTo(b + 2f, h + 2f); schneeForm.close()
                }
                drawPath(schneeForm, sp.schneePinsel)
                // feines Glitzern im Schnee (fest, kein Rechenaufwand)
                for (i in 0 until 40) {
                    val gy = zufall(i * 6.1 + 900).toFloat() * h
                    val gx = b - schneeRand(gy, k) * (0.15f + 0.8f * zufall(i * 2.9 + 900).toFloat())
                    drawCircle(lerp(farbe, Color.White, 0.3f).copy(alpha = 0.55f), radius = 0.9f * k, center = Offset(gx, gy))
                }
            }
            val y = z.y * h
            var len = z.laenge * b * akku * 0.8f                // etwas kuerzer
            if (beule > 0.01f && dy0 >= 0f) {
                val d = (y - dy0) / (h * 0.07f)
                len *= 1f + 0.9f * beule * kotlin.math.exp(-d * d)   // unter dem Daumen deutlich laenger
            }
            val w = z.breite * h
            val deck = when (z.ebene) { 0 -> 0.5f; 1 -> 0.95f; else -> 0.75f }
            val basis = b + 4f
            eisZacke(sp, basis, y, -1f, 0f, len, w, z.neigung, deck, dunkel, z.ebene < 2)
            if (z.ebene < 2) {
                // innerer Lichtstreifen (Lichtbrechung)
                val tipX = basis - len; val tipY = y + len * z.neigung
                val a = 0.25f; val e = 0.7f
                drawLine(Color.White.copy(alpha = 0.22f * deck),
                         Offset(basis + (tipX - basis) * a, (y - w * 0.45f) + (tipY - (y - w * 0.45f)) * a),
                         Offset(basis + (tipX - basis) * e, (y - w * 0.45f) + (tipY - (y - w * 0.45f)) * e),
                         strokeWidth = 1.4f * k)
            }
            // Funkeln: wandert langsam von Zacke zu Zacke (nur vordere Ebene)
            if (z.ebene == 1) {
                val periode = 2600.0
                if (((t / periode).toInt()) % 14 == vorne) {
                    val tipX = basis - len; val tipY = y + len * z.neigung
                    val mitte = y + w * 0.15f
                    val ph = ((t % periode) / periode).toFloat()
                    val sx = basis + (tipX - basis) * (0.35f + 0.55f * ph)
                    val sy = mitte + (tipY - mitte) * (0.35f + 0.55f * ph)
                    val s2 = kotlin.math.sin(Math.PI * ph).toFloat()
                    val r = 7f * k * s2
                    if (s2 > 0.02f) {
                        drawLine(Color.White.copy(alpha = 0.9f * s2), Offset(sx - r, sy), Offset(sx + r, sy), strokeWidth = 1.2f * k)
                        drawLine(Color.White.copy(alpha = 0.9f * s2), Offset(sx, sy - r), Offset(sx, sy + r), strokeWidth = 1.2f * k)
                        drawCircle(Color.White.copy(alpha = 0.8f * s2), radius = 1.8f * k, center = Offset(sx, sy))
                    }
                }
                vorne++
            }
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

// ============================================================================
//  Pixel: rechts ganz dicht, nach links immer weniger. Jeder Pixel funkelt leicht vor sich hin.
//  Farbe einstellbar, reagiert auf Daumen (reicht dort weiter rein) und Akku.
// ============================================================================
val PIXEL_FARBEN = listOf(
    "Cyan" to 0xFF4FE3FF.toInt(), "Neon-Grün" to 0xFF5CFF8F.toInt(), "Lila" to 0xFFB57BFF.toInt(),
    "Pink" to 0xFFFF5FB4.toInt(), "Orange" to 0xFFFFA24C.toInt(), "Weiß" to 0xFFF2F2F2.toInt(),
)

@Composable
fun PixelHintergrund(modifier: Modifier, grund: Color, farbe: Color, laeuft: Boolean = true) {
    val zeit = animZeit(66, laeuft)                     // 15 Bilder/s reichen fuer leichtes Funkeln
    val hsv = remember { FloatArray(3) }
    Canvas(modifier) {
        if (grund.alpha > 0f) drawRect(grund)
        val t = zeit.value.toDouble()
        WellenReiz.beule += ((if (WellenReiz.daumenDa) 1f else 0f) - WellenReiz.beule) * 0.25f
        if (WellenReiz.akkuWeich < 0f) WellenReiz.akkuWeich = WellenReiz.akku
        WellenReiz.akkuWeich += (WellenReiz.akku - WellenReiz.akkuWeich) * 0.03f
        val b = size.width; val h = size.height
        val zelle = 9f * density                            // Raster
        val pixel = zelle - 1.6f * density                  // Pixel mit kleiner Luecke
        val zeilen = (h / zelle).toInt() + 1
        val akku = 0.55f + 0.45f * WellenReiz.akkuWeich.coerceIn(0f, 1f)
        val reichweite = 5.5f * akku                        // in Spalten: so schnell wird es nach links duenner
        val dy0 = WellenReiz.daumenY
        val beule = WellenReiz.beule
        val maxSpalten = (reichweite * 2.4f * 4.2f).toInt() + 2
        // Grundfarbe als Farbton - jeder Pixel wandert langsam um den Farbton herum
        android.graphics.Color.colorToHSV(farbe.toArgb(), hsv)
        fun pixelFarbe(u: Float, hell: Float): Color {
            val ton = (hsv[0] + 22f * kotlin.math.sin(t / 4200.0 + u * 13.0).toFloat() + 360f) % 360f
            return Color.hsv(ton, hsv[1], hsv[2], hell.coerceIn(0f, 1f))
        }
        // vereinzelt Pixel in den linken Ecken (oben und unten), damit es nicht einseitig wirkt
        val spalten = (b / zelle).toInt()
        for (ecke in 0..1) {
            for (r in 0 until 7) for (c in 0 until 7) {
                val abstand = kotlin.math.sqrt((r * r + c * c).toFloat())
                val dichte = kotlin.math.exp(-abstand / 1.6f) * 0.9f
                val u = zufall(c * 53.1 + r * 17.9 + ecke * 400.0).toFloat()
                if (u > dichte || c >= spalten) continue
                val funkel = 0.55f + 0.45f * kotlin.math.sin(t / 1300.0 + u * 40.0).toFloat()
                val y = if (ecke == 0) r * zelle else h - (r + 1) * zelle
                drawRect(pixelFarbe(u, (0.35f + 0.65f * dichte) * funkel),
                         topLeft = Offset(c * zelle, y), size = androidx.compose.ui.geometry.Size(pixel, pixel))
            }
        }
        for (r in 0 until zeilen) {
            val y = h - (r + 1) * zelle                     // von unten aufbauen -> unten buendig
            var k = reichweite
            if (beule > 0.01f && dy0 >= 0f) {
                val d = (y + zelle / 2f - dy0) / (h * 0.07f)
                k *= 1f + 1.4f * beule * kotlin.math.exp(-d * d)
            }
            for (c in 0 until maxSpalten) {
                val dichte = kotlin.math.exp(-c / k)
                if (dichte < 0.015f) break
                val u = zufall(c * 91.7 + r * 7.3).toFloat()
                if (u > dichte) continue
                // jeder Pixel funkelt in seinem eigenen Takt
                val funkel = 0.55f + 0.45f * kotlin.math.sin(t / 1300.0 + u * 40.0).toFloat()
                val hell = (0.35f + 0.65f * dichte) * funkel
                drawRect(pixelFarbe(u, hell),
                         topLeft = Offset(b - (c + 1) * zelle, y), size = androidx.compose.ui.geometry.Size(pixel, pixel))
            }
        }
    }
}


// ---------------------------------------------------------------- Swiss / Agent
/**
 * International Typographic Style: tiefes Schwarz (oder Papierweiss), feines 4-Spalten-Raster,
 * Schnittmarken in den Ecken, ein einziger roter Akzent. Keine Verlaeufe, keine Schatten.
 * nurWellen = nur die Ebene, die auf den Daumen am Alphabet reagiert: Haarlinie quer ueber den
 * Bildschirm + roter Block am Rand, der mit dem Ziehen waechst und einrastet.
 */
@Composable
fun SwissHintergrund(modifier: Modifier, dunkel: Boolean, rot: Color, nurWellen: Boolean, laeuft: Boolean, durchsichtig: Boolean = false) {
    val grund = if (dunkel) Color(0xFF0B0B0C) else Color(0xFFFFFFFF)
    val tinte = if (dunkel) Color.White else Color(0xFF111111)
    if (!nurWellen) {
        // Ist unser eigener Live-Hintergrund aktiv, malt der das Raster schon (genau wie auf dem Sperrbildschirm).
        // Dann hier NICHTS zeichnen - sonst liegen zwei Raster uebereinander.
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val neu = LocalZustand.current.fortsetzen
        val eigeneTapete = remember(neu) {
            EigeneTapete.aktiv(ctx)
        }
        if (durchsichtig && eigeneTapete) return
        Canvas(modifier.background(grund)) {
            val d = density
            val rand = 24f * d
            val linie = tinte.copy(alpha = if (dunkel) 0.07f else 0.09f)
            // Raster aus exakt quadratischen Feldern: 4 Spalten, Zeilen gleich hoch wie die Spalten breit
            val zelle = (size.width - 2 * rand) / 4f
            // auf ganze Pixel gesetzt (+0,5 = Mitte des Pixels) -> scharfe, gleich dicke Linien
            fun px(v: Float) = kotlin.math.floor(v) + 0.5f
            for (k in 0..4) {
                val x = px(rand + zelle * k)
                drawLine(linie, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            var k = 0
            while (rand + zelle * k <= size.height) {
                val yy = px(rand + zelle * k)
                drawLine(linie, Offset(0f, yy), Offset(size.width, yy), strokeWidth = 1f)
                k++
            }
            // Schnittmarken in den Ecken
            val m = tinte.copy(alpha = 0.35f); val l = 14f * d; val o = 14f * d; val s = 1.5f * d
            for ((cx, cy, sx, sy) in listOf(
                floatArrayOf(o, o + 30 * d, 1f, 1f), floatArrayOf(size.width - o, o + 30 * d, -1f, 1f),
                floatArrayOf(o, size.height - o - 40 * d, 1f, -1f), floatArrayOf(size.width - o, size.height - o - 40 * d, -1f, -1f))) {
                drawLine(m, Offset(cx, cy), Offset(cx + sx * l, cy), strokeWidth = s)
                drawLine(m, Offset(cx, cy), Offset(cx, cy + sy * l), strokeWidth = s)
            }
            // der eine rote Akzent: Balken links an der Rasterlinie, auf Hoehe der Uhr
            // sitzt genau im Raster: auf der linken Linie, eine Feldhoehe lang (zweite Zeile)
            drawRect(rot, Offset(rand - 2.5f * d, rand + zelle), androidx.compose.ui.geometry.Size(5f * d, zelle))
        }
        return
    }
    // Daumen-Ebene: laeuft nur, solange der Daumen am Alphabet ist (+ kurz zum Ausblenden) -
    // vorher tickte sie dauernd mit 60 Bildern/s und hat den ganzen Launcher mitgezogen (gemessen)
    val an = WellenReiz.beruehrt.value || MusikPuls.an.value
    var aktiv by remember { mutableStateOf(false) }
    LaunchedEffect(an) {
        if (an) aktiv = true
        else { delay(600); aktiv = false }
    }
    val zeit = animZeit(16, laeuft && aktiv)
    val stand = remember { FloatArray(4) }        // [0] Breite, [1] Tempo, [2] Sichtbarkeit, [3] Hoehe (Musik)
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = zeit.value
        val d = density
        val da = WellenReiz.daumenDa && WellenReiz.daumenY >= 0f
        // Feder: rastet auf Stufen ein (kurz, mittel, lang) statt stufenlos zu wachsen
        val zug = WellenReiz.zug.coerceAtLeast(0f)
        val stufe = when { !da -> 0f; zug < 24 * d -> 18f * d; zug < 90 * d -> 56f * d; else -> 120f * d }
        val a = 900f * (stufe - stand[0]) - 2f * kotlin.math.sqrt(900f) * 0.7f * stand[1]
        stand[1] += a * 0.016f; stand[0] += stand[1] * 0.016f
        // Musik (ohne Daumen): Linie springt bei jedem Schlag auf eine neue Hoehe (rastet ein), Bass = wie deutlich
        val mk = if (MusikPuls.an.value) MusikPuls.k else 0f
        val musik = !da && mk > 0f && WellenReiz.abcUnten > 0f
        if (musik) stand[3] = WellenReiz.abcOben + MusikPuls.schlagY * (WellenReiz.abcUnten - WellenReiz.abcOben)
        val sichtbarZiel = if (da) 1f else if (musik) (0.25f + 0.75f * maxOf(MusikPuls.bass, MusikPuls.stoss)) * mk.coerceAtMost(1f) else 0f
        stand[2] += (sichtbarZiel - stand[2]) * 0.35f
        if (stand[2] < 0.01f && stand[0] < 0.5f) return@Canvas
        val y = if (da) WellenReiz.daumenY else stand[3]
        // nur eine Haarlinie auf Hoehe des Daumens - endet vor der Buchstaben-Leiste, verdeckt also nichts
        drawLine(tinte.copy(alpha = 0.30f * stand[2]), Offset(0f, y), Offset(size.width - 44f * d, y), strokeWidth = 1f)
        // kleine rote Marke am Ende der Linie
        drawRect(rot.copy(alpha = stand[2]), Offset(size.width - 50f * d, y - 3f * d), androidx.compose.ui.geometry.Size(6f * d, 6f * d))
    }
}


/**
 * Swiss-Kante am Daumen: Felder im Swiss-Raster ploppen vom Alphabet-Rand aus auf Hoehe des Daumens auf
 * (zufaellige Reihenfolge, manche Seiten offen). Je weiter der Daumen nach links zieht, desto weiter und
 * hoeher reicht die Kante. Am vordersten Feld in Daumenhoehe sitzt ein Balken in der Akzentfarbe.
 * Laeuft nur, solange der Daumen dran ist (+ kurz zum Verblassen).
 */
private class KantenStand {
    var a = FloatArray(0); var s = 0; var z = 0
    var r = 0f; var tempo = 0f; var y = -1f; var saat = 0
    var balkenX = -1f; var balkenA = 0f
    val fd = FloatArray(6)                 // wiederverwendet: kein neues Array pro Feld und Bild
}

@Composable
fun SwissKanteDaumen(modifier: Modifier, dunkel: Boolean, rot: Color) {
    // grau statt weiss/schwarz: die Buchstaben davor bleiben lesbar
    val tinte = if (dunkel) Color(0xFF8C8C8C) else Color(0xFF7A7A7A)
    val an = WellenReiz.beruehrt.value || MusikPuls.an.value
    val st = remember { KantenStand() }
    var aktiv by remember { mutableStateOf(false) }
    LaunchedEffect(an) {
        if (an) { st.saat = java.util.Random().nextInt(); aktiv = true }
        else { delay(1000); aktiv = false }
    }
    val zeit = animZeit(16, aktiv)
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val t = zeit.value
        val d = density; val w = size.width; val h = size.height
        val rr = 24f * d
        val z = ((w - 2 * rr) / 8f).coerceAtLeast(8f * d)
        val nS = 1 + kotlin.math.ceil((w * 0.62f - rr) / z).toInt()
        val nZ = 1 + kotlin.math.ceil((h - rr) / z).toInt()
        if (nS != st.s || nZ != st.z) { st.s = nS; st.z = nZ; st.a = FloatArray(nS * nZ) }
        val da = WellenReiz.kanteDa && WellenReiz.daumenY >= 0f
        // Musik (ohne Daumen): bei jedem Schlag ploppen die Felder auf einer neuen Hoehe auf - ruckartig
        val mk = if (MusikPuls.an.value) MusikPuls.k else 0f
        val musik = !da && mk > 0f && WellenReiz.abcUnten > 0f
        if (da) st.y = WellenReiz.daumenY
        else if (musik) st.y = WellenReiz.abcOben + MusikPuls.schlagY * (WellenReiz.abcUnten - WellenReiz.abcOben)
        if (st.y < 0f) return@Canvas
        // Reichweite federt zum Ziel (wie die Wellen), waechst mit dem Zug nach links
        val zug = WellenReiz.zug.coerceAtLeast(0f)
        val ziel = if (da) rr + z * 0.75f + zug * 0.85f
                   else if (musik) (rr * 0.6f + z * (0.4f * MusikPuls.bass + 2.6f * MusikPuls.stoss)) * mk
                   else 0f      // bleibt weiter aussen (rechts)
        val acc = 700f * (ziel - st.r) - 2f * kotlin.math.sqrt(700f) * 0.8f * st.tempo
        st.tempo += acc * 0.016f; st.r += st.tempo * 0.016f
        val sigma = z * 1.2f + st.r * 0.35f
        val strich = 1.5f * d
        var vorneI = -1; var vorneA = 0f
        val jDaumen = if (st.y < rr) 0 else 1 + ((st.y - rr) / z).toInt()
        for (i in 0 until nS) {
            val innen = if (i == 0) 0f else rr + (i - 1) * z
            val aussen = if (i == 0) rr else rr + i * z
            for (j in 0 until nZ) {
                val k = i * nZ + j
                val oben = if (j == 0) 0f else rr + (j - 1) * z
                val unten = if (j == 0) rr else rr + j * z
                val dy = (oben + unten) / 2f - st.y
                val reicht = st.r * kotlin.math.exp(-(dy * dy) / (2f * sigma * sigma))
                val noetig = innen + (aussen - innen) * (0.15f + 0.85f * SwissRaster.zahl(i, j, 1, st.saat)) -
                             z * 0.5f * SwissRaster.zahl(i, j, 2, st.saat)
                val soll = if (reicht >= noetig.coerceAtLeast(2f * d)) 1f else 0f
                var a = st.a[k]
                a += (soll - a) * (if (soll > a) 0.42f else 0.10f)
                st.a[k] = a
                if (a < 0.01f) continue
                val x1 = w - aussen; val x2 = w - innen
                // ganz leichte Flaeche, damit die Felder als Felder lesbar sind
                drawRect(tinte.copy(alpha = 0.015f * a), Offset(x1, oben), androidx.compose.ui.geometry.Size(x2 - x1, unten - oben))
                val farbe = tinte.copy(alpha = 0.4f * a)
                val fd = st.fd
                fd[0] = x1; fd[1] = oben; fd[2] = x2; fd[3] = unten; fd[4] = i.toFloat(); fd[5] = j.toFloat()
                SwissRaster.linien(fd, false, st.saat) { ax, ay, bx, by ->
                    drawLine(farbe, Offset(ax, ay), Offset(bx, by), strokeWidth = strich, cap = StrokeCap.Square)
                }
                if (j == jDaumen && soll > 0f && i > vorneI) { vorneI = i; vorneA = a }
            }
        }
        // Akzent-Balken an der vordersten Kante in Daumenhoehe (gleitet weich mit)
        if (vorneI >= 0) {
            val xZiel = w - (if (vorneI == 0) rr else rr + vorneI * z)
            st.balkenX = if (st.balkenX < 0f) xZiel else st.balkenX + (xZiel - st.balkenX) * 0.35f
        }
        st.balkenA += ((if (vorneI >= 0 && (da || musik)) vorneA else 0f) - st.balkenA) * 0.25f
        if (st.balkenA > 0.01f && st.balkenX >= 0f) {
            val oben = if (jDaumen == 0) 0f else rr + (jDaumen - 1) * z
            val unten = if (jDaumen == 0) rr else rr + jDaumen * z
            drawRect(rot.copy(alpha = 0.8f * st.balkenA), Offset(st.balkenX - 2.5f * d, oben), androidx.compose.ui.geometry.Size(5f * d, unten - oben))
        }
        if (!da && !musik && st.r < 0.5f && st.a.all { it < 0.01f }) { st.y = -1f; st.balkenX = -1f }
    }
}
