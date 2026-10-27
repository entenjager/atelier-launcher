package de.atelierlauncher.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

// ============================================================================
//  Ring um die Play/Pause-Taste des Musik-Bausteins (z. B. genau um die Frontkamera):
//   - "beat": Kreis zittert, Striche springen nach aussen im Takt. Pause = duenner grauer Ring, keine Striche.
//             Takt: mit "Mikrofon"-Erlaubnis hoert Android mit (nur Lautstaerke je Tonhoehe, nichts wird gespeichert),
//             sonst tanzen die Striche in einem ruhigen erfundenen Takt.
//   - "song": Ring fuellt sich mit dem Song. Pause = er blinkt leicht.
// ============================================================================

internal const val BEAT_STRICHE = 48

/** Bass gerade (0..1) - der Beat-Ring schreibt ihn, die Seitentasten zappeln mit */
@Volatile internal var beatBass = 0f

/** Takt vom Handy (Android Visualizer auf der gesamten Ausgabe) */
internal object Beat {
    val baender = FloatArray(BEAT_STRICHE)
    @Volatile var zuletzt = 0L
        private set
    @Volatile private var vis: Visualizer? = null
    @Volatile private var nutzer = 0
    /** fuer die Anzeige in den Einstellungen: "", "laeuft", "fehler: ..." */
    @Volatile var stand = ""
        private set
    @Volatile var gestartet = 0L
        private set
    @Volatile var datenSeit = 0L          // letzte Daten ueberhaupt (auch Stille)
        private set
    private var abholPuffer = ByteArray(512)
    private val mittel = FloatArray(BEAT_STRICHE)
    private var spitze = 0.06f

    fun erlaubt(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // Alles mit dem Visualizer laeuft auf einem eigenen Faden: Anlegen, Daten, Abholen sind Anfragen an Androids
    // Audio-Dienst und koennen dauern - auf dem Haupt-Faden hat das beim Musikstart alles ruckeln lassen
    private val faden by lazy { android.os.HandlerThread("Takt").also { it.start() } }
    private val h by lazy { android.os.Handler(faden.looper) }
    @Volatile private var abholLaeuft = false

    fun an(ctx: Context) {
        nutzer++
        val app = ctx.applicationContext
        // erst kurz nach dem Start der Musik: dann ist die Musik-App selbst fertig mit Hochfahren
        h.postDelayed({ startenJetzt(app) }, 1200)
    }

    /** Launcher nicht mehr vorne (onPause): Mithoeren sofort beenden - Compose raeumt evtl. erst spaeter auf */
    fun pause() { h.post { freigeben() } }

    private fun freigeben() { try { vis?.enabled = false; vis?.release() } catch (t: Throwable) { }; vis = null; if (stand == "laeuft") stand = "" }

    /** (nochmal) versuchen - z. B. nachdem die Erlaubnis gerade erteilt wurde */
    fun starten(ctx: Context) {
        if (vis != null || nutzer == 0 || !(Sichtbar.an || SperrSeite.sichtbar.value)) return
        val app = ctx.applicationContext
        h.post { startenJetzt(app) }
    }

    private fun startenJetzt(ctx: Context) {
        if (vis != null || nutzer == 0 || !(Sichtbar.an || SperrSeite.sichtbar.value)) return
        if (!erlaubt(ctx)) { stand = ""; return }
        try {
            val v = Visualizer(0)
            v.enabled = false
            val bereich = Visualizer.getCaptureSizeRange()
            v.captureSize = 512.coerceIn(bereich[0], bereich[1])
            try { v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED } catch (t: Throwable) { }
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) { if (fft != null) rechnen(fft) }
            }, Visualizer.getMaxCaptureRate(), false, true)
            val r = v.setEnabled(true)
            if (r != Visualizer.SUCCESS) { stand = "fehler: Android sagt $r"; try { v.release() } catch (t: Throwable) { }; return }
            vis = v; stand = "laeuft"; gestartet = System.currentTimeMillis()
        } catch (t: Throwable) {
            stand = "fehler: " + (t.message ?: t.javaClass.simpleName)
            Konsole.schreib("musik", "Takt: " + t.message); vis = null
        }
    }

    /** falls der Rueckruf nicht kommt: selbst nachfragen - auf dem Takt-Faden, hoechstens alle 150 ms */
    fun abholen() {
        if (vis == null || abholLaeuft) return
        val jetzt = System.currentTimeMillis()
        if (jetzt - datenSeit < 150) return
        abholLaeuft = true
        datenSeit = jetzt                                   // nicht gleich nochmal
        h.post {
            try {
                val v = vis
                if (v != null) {
                    val n = v.captureSize
                    if (abholPuffer.size != n) abholPuffer = ByteArray(n)
                    val f = abholPuffer
                    if (v.getFft(f) == Visualizer.SUCCESS) rechnen(f)
                }
            } catch (t: Throwable) { }
            abholLaeuft = false
        }
    }

    fun aus() {
        nutzer = (nutzer - 1).coerceAtLeast(0)
        if (nutzer == 0) h.post { if (nutzer == 0) freigeben() }
    }

    /** Text fuer die Einstellungen */
    fun zustandText(ctx: Context): String = when {
        !erlaubt(ctx) -> ""
        stand.startsWith("fehler") -> "Takt geht nicht (" + stand.removePrefix("fehler: ") + ") – es läuft der erfundene Takt"
        stand == "laeuft" && System.currentTimeMillis() - zuletzt < 1500 -> "Echter Takt läuft ✓"
        stand == "laeuft" && System.currentTimeMillis() - gestartet > 3000 ->
            "Android liefert nur Stille – das Handy lässt das Mithören nicht zu (oder die Lautstärke ist auf 0). Es läuft der erfundene Takt"
        stand == "laeuft" -> "Hört zu …"
        else -> "Echter Takt an – startet, sobald Musik läuft"
    }

    private val rohP = FloatArray(BEAT_STRICHE)
    private val wRohP = FloatArray(BEAT_STRICHE)

    @Synchronized
    private fun rechnen(fft: ByteArray) {
        datenSeit = System.currentTimeMillis()
        val n = fft.size / 2
        if (n < 8) return
        var summe = 0f
        val roh = rohP
        val wRoh = wRohP
        var hoechst = 0f
        for (i in 0 until BEAT_STRICHE) {
            // Baender logarithmisch verteilt (Bass hat wenige Bins, Hoehen viele)
            val a = (1 + (n - 1) * ((exp(i / BEAT_STRICHE.toFloat() * 4f) - 1f) / (exp(4f) - 1f))).toInt().coerceIn(1, n - 1)
            val b = (1 + (n - 1) * ((exp((i + 1) / BEAT_STRICHE.toFloat() * 4f) - 1f) / (exp(4f) - 1f))).toInt().coerceIn(a + 1, n)
            var m = 0f
            for (k in a until b) {
                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                m = maxOf(m, hypot(re, im))
            }
            val w = (ln(1f + m / 128f * 12f * (1f + i / 24f)) / ln(13f)).coerceIn(0f, 1f)
            // nur was ueber dem eigenen Durchschnitt liegt zaehlt: Baender, die immer laut sind (Bass-Brummen),
            // stehen sonst dauernd draussen - so springen die Striche nur bei Schlaegen / Aenderungen
            wRoh[i] = w
            mittel[i] = mittel[i] * 0.97f + w * 0.03f
            roh[i] = (w - mittel[i] * 0.9f).coerceAtLeast(0f)
            hoechst = maxOf(hoechst, roh[i])
            summe += w
        }
        // automatische Lautstaerke: leise Musik (Schlafmusik) und laute (Rock) fuellen den Ring aehnlich weit,
        // aber ruhige Musik bewegt sich weniger, weil sie weniger Schlaege hat
        spitze = maxOf(spitze * 0.996f, hoechst, 0.06f)
        for (i in 0 until BEAT_STRICHE) baender[i] = maxOf(baender[i] * 0.78f, (roh[i] / spitze).coerceIn(0f, 1f))
        if (summe > 0.01f) { zuletzt = System.currentTimeMillis(); MusikKenner.futter(wRoh) }
    }
}

/**
 * Ring um die Mitteltaste zeichnen (zeichnet ueber den Rand hinaus - Platz kommt nicht dazu).
 * abstand: Ring-Radius in Prozent der Taste, laenge: Strichlaenge in Prozent.
 */
@Composable
internal fun Modifier.musikRing(art: String, spielt: Boolean, farbe: Color, still: Color, abstand: Float, laenge: Float, seite: Float = 1f, staerke: Float = 0.75f): Modifier {
    if (art != "beat" && art != "song") return this
    val ctx = LocalContext.current
    val sichtbar = Sichtbar.an || SperrSeite.sichtbar.value      // auch auf der Sperr-Seite
    val takt = remember { mutableLongStateOf(0L) }
    val start = remember { System.nanoTime() }
    val laeuft = sichtbar && (spielt || art == "song")
    // Song-Ring bei Pause blinkt nur - dafuer reichen ~12 Bilder pro Sekunde; spielend: Song-Ring ~20, Beat volle Rate
    LaunchedEffect(laeuft, spielt) {
        while (laeuft) {
            withFrameNanos { takt.longValue = it }
            // Takt kommt vom Handy hoechstens ~20x pro Sekunde - mehr als 30 Bilder bringen nichts, kosten aber Akku und Ruckler
            kotlinx.coroutines.delay(if (art == "song") (if (spielt) 45L else 80L) else 25L)
        }
    }
    if (art == "beat") DisposableEffect(spielt && sichtbar) {
        if (spielt && sichtbar) Beat.an(ctx)
        onDispose { if (spielt && sichtbar) Beat.aus() }
    }
    // Erlaubnis erst spaeter erteilt / Start hat nicht geklappt: alle 2 Sekunden nochmal
    if (art == "beat") LaunchedEffect(spielt && sichtbar) {
        while (spielt && sichtbar) { kotlinx.coroutines.delay(2000); Beat.starten(ctx) }
    }
    val werte = remember { FloatArray(BEAT_STRICHE) }
    // eigene Ebene: pro Bild wird nur der Ring neu gemalt, nicht alles drumherum (Bausteine, Milchglas ...)
    return this.graphicsLayer().drawBehind {
        val t = (takt.longValue - start).coerceAtLeast(0L) / 1e9f
        val c = Offset(size.width / 2f, size.height / 2f)
        val r0 = size.minDimension / 2f * abstand
        val strich = 2.dp.toPx()
        if (art == "song") {
            val f = Musik.fortschritt() ?: 0f
            val a = if (spielt) 1f else 0.45f + 0.35f * (0.5f + 0.5f * sin(t * 3.2f))
            val tl = Offset(c.x - r0, c.y - r0); val gr = Size(2 * r0, 2 * r0)
            drawArc(still, 0f, 360f, false, tl, gr, style = Stroke(strich * 1.4f))
            if (f > 0f) drawArc(farbe.copy(alpha = a), -90f, 360f * f, false, tl, gr, style = Stroke(strich * 1.6f, cap = StrokeCap.Round))
            val w = (-90f + 360f * f) * PI.toFloat() / 180f
            drawCircle(Color.White.copy(alpha = a), strich * 2.2f, Offset(c.x + cos(w) * r0, c.y + sin(w) * r0))
            return@drawBehind
        }
        // Beat
        Beat.abholen()
        if (!spielt) {
            beatBass = 0f
            drawCircle(still, r0, c, style = Stroke(strich * 0.8f))
            for (i in 0 until BEAT_STRICHE) {
                val w = 2f * PI.toFloat() * i / BEAT_STRICHE
                val seitlich = Math.pow(kotlin.math.abs(cos(w)).toDouble(), 6.0).toFloat()
                val a = r0 + strich * 2.5f
                if (seitlich > 0.3f) {
                    // Pause: links / rechts kurze graue Striche - da ist Zurueck / Weiter
                    val l = size.minDimension / 2f * 0.75f * laenge * 0.9f * seitlich * (0.4f + 0.6f * seite)
                    drawLine(still, Offset(c.x + cos(w) * a, c.y + sin(w) * a), Offset(c.x + cos(w) * (a + l), c.y + sin(w) * (a + l)), strich, StrokeCap.Round)
                } else drawCircle(still, strich * 0.6f, Offset(c.x + cos(w) * (r0 + strich * 4f), c.y + sin(w) * (r0 + strich * 4f)))
            }
            return@drawBehind
        }
        val echt = System.currentTimeMillis() - Beat.zuletzt < 600
        var bass = 0f
        for (i in 0 until BEAT_STRICHE) {
            val kn = MusikKenner
            val ziel = if (echt) (Beat.baender[i] * staerke * kn.staerke * (if (i < 8) kn.bassGewicht else 1f) * (1f + 0.5f * kn.schub)).coerceIn(0f, 1f) else {
                // erfundener Takt (~118 bpm): Schlag + etwas Bewegung je Strich
                val schlag = exp(-((t * 1.97f) % 1f) * 6f)
                (0.18f + 0.55f * schlag * (0.6f + 0.4f * sin(i * 1.7f + t * 2f)) + 0.2f * (0.5f + 0.5f * sin(t * 5.3f + i * 0.9f))).coerceIn(0f, 1f)
            }
            // erkannte Art: Rock schnappt sofort hoch und faellt schnell, Ruhiges gleitet
            werte[i] = if (!echt) (if (ziel > werte[i]) ziel else werte[i] * 0.86f + ziel * 0.14f)
                       else if (ziel > werte[i]) werte[i] + (ziel - werte[i]) * (0.3f + 0.7f * kn.ruck)
                       else werte[i] * kn.abklingen + ziel * (1f - kn.abklingen)
            if (i < 6) bass += werte[i] / 6f
        }
        beatBass = bass
        // der Kreis "vibriert" mit dem Bass - ruhige Musik: er atmet langsam im Tempo
        val kn = MusikKenner
        val atemTempo = if (kn.bpm > 0f) kn.bpm / 120f else 0.25f
        val atem = if (echt) kn.atmen * 0.07f * sin(t * 2f * PI.toFloat() * atemTempo) else 0f
        val r = r0 * (1f + 0.05f * bass * (if (echt) 0.5f + kn.ruck else 1f) + atem + (if (echt) 0.06f * (kn.schub - 0.5f).coerceAtLeast(0f) else 0f))
        drawCircle(farbe, r, c, style = Stroke(strich))
        val max = size.minDimension / 2f * 0.75f * laenge
        for (i in 0 until BEAT_STRICHE) {
            val w = 2f * PI.toFloat() * i / BEAT_STRICHE - PI.toFloat() / 2f
            // links / rechts (waagrecht) viel laenger - dort tippt man fuer Zurueck / Weiter
            val seitlich = Math.pow(kotlin.math.abs(cos(w)).toDouble(), 6.0).toFloat()
            val l = strich + max * (1f + 2.6f * seite * seitlich) * werte[(i * 7) % BEAT_STRICHE]
            val a = r + strich * 2.5f
            drawLine(farbe, Offset(c.x + cos(w) * a, c.y + sin(w) * a), Offset(c.x + cos(w) * (a + l), c.y + sin(w) * (a + l)), strich, StrokeCap.Round)
        }
    }
}

/** Zurueck/Weiter passend zum Ring: Beat = kleine Striche rundherum (zappeln mit dem Bass), Song = feiner Kreis */
@Composable
internal fun Modifier.musikSeite(art: String, spielt: Boolean, farbe: Color, still: Color): Modifier {
    if (art != "beat" && art != "song") return this
    val sichtbar = Sichtbar.an || SperrSeite.sichtbar.value      // auch auf der Sperr-Seite
    val takt = remember { mutableLongStateOf(0L) }
    val laeuft = sichtbar && spielt && art == "beat"
    LaunchedEffect(laeuft) { while (laeuft) { withFrameNanos { takt.longValue = it }; kotlinx.coroutines.delay(25) } }
    return this.graphicsLayer().drawBehind {
        @Suppress("UNUSED_VARIABLE") val tick = takt.longValue
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f
        val strich = 1.5.dp.toPx()
        if (art == "song") {
            drawCircle(if (spielt) farbe.copy(alpha = 0.55f) else still, r * 0.92f, c, style = Stroke(strich))
            return@drawBehind
        }
        val n = 16
        val b = if (spielt) beatBass else 0f
        for (i in 0 until n) {
            val w = 2f * PI.toFloat() * i / n
            val a = r * 0.86f
            if (!spielt) drawCircle(still, strich * 0.55f, Offset(c.x + cos(w) * a, c.y + sin(w) * a))
            else {
                val l = strich * (1.2f + 4f * b * (0.6f + 0.4f * sin(i * 2.3f)))
                drawLine(farbe, Offset(c.x + cos(w) * a, c.y + sin(w) * a), Offset(c.x + cos(w) * (a + l), c.y + sin(w) * (a + l)), strich, StrokeCap.Round)
            }
        }
    }
}

/**
 * Musik-Puls fuer die Effekte hinter dem Alphabet (Wellen, Swiss-Kante, Swiss-Linie, Kreise):
 * Bass (0..1) und "Stoss" bei jedem Schlag. Nur mit echtem Takt - ohne bleibt alles wie immer.
 */
internal object MusikPuls {
    val an = androidx.compose.runtime.mutableStateOf(false)
    val schlagNr = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile var k = 0f              // Staerke (Einstellung), 0 = aus
    @Volatile var bass = 0f
    @Volatile var stoss = 0f
    @Volatile var schlagY = 0.5f      // 0..1 entlang des Alphabets - wo der letzte Schlag "landet"
    private var schnitt = 0f
    private var letzter = 0L
    private val zufall = java.util.Random()

    fun takt() {
        Beat.abholen()
        val echt = System.currentTimeMillis() - Beat.zuletzt < 600
        if (!echt) { bass *= 0.9f; stoss *= 0.85f; return }
        val kn = MusikKenner
        var roh = 0f
        for (i in 0 until 6) roh += Beat.baender[i]
        roh /= 6f
        val b = (roh * kn.bassGewicht * kn.staerke).coerceIn(0f, 1.5f)
        // Art entscheidet: Rock = sofort hoch, schnell weg; Ruhiges / Klassik = weich
        bass = if (b > bass) bass + (b - bass) * (0.3f + 0.7f * kn.ruck) else bass * kn.abklingen + b * (1f - kn.abklingen)
        if (kn.atmen > 0.05f) {
            val t = System.nanoTime() / 1e9f
            val ph = sin(t * 2f * PI.toFloat() * (if (kn.bpm > 0f) kn.bpm / 120f else 0.25f))
            bass = maxOf(bass, kn.atmen * 0.35f * (0.5f + 0.5f * ph) * kn.staerke)
        }
        schnitt = schnitt * 0.96f + roh * 0.04f
        val jetzt = System.currentTimeMillis()
        if (kn.schub > 1.2f && jetzt - letzter > 600) {
            // Drop: grosser Ruck
            stoss = 1.5f; letzter = jetzt
            schlagY = 0.1f + 0.8f * zufall.nextFloat(); schlagNr.intValue++
        } else if (kn.ruck > 0.15f && roh > 0.3f && roh > schnitt * 1.45f && jetzt - letzter > 180) {
            stoss = (0.5f + 0.5f * kn.ruck) * kn.staerke.coerceAtMost(1f); letzter = jetzt
            schlagY = 0.1f + 0.8f * zufall.nextFloat()
            schlagNr.intValue++
        } else stoss *= if (kn.ruck > 0.8f) 0.78f else 0.88f
    }
}

/** startet den Takt, solange Musik laeuft und "Alphabet bewegt sich mit der Musik" an ist */
@Composable
internal fun MusikPulsQuelle(aktiv: Boolean, staerke: Float) {
    val ctx = LocalContext.current
    MusikKenner.auto = LocalZustand.current.musikAuto
    val spielt = Musik.info?.spielt == true
    val laeuft = aktiv && spielt && Sichtbar.an
    MusikPuls.k = if (laeuft) staerke else 0f
    DisposableEffect(laeuft) {
        if (laeuft) Beat.an(ctx)
        MusikPuls.an.value = laeuft
        onDispose { if (laeuft) Beat.aus(); MusikPuls.an.value = false; MusikPuls.bass = 0f; MusikPuls.stoss = 0f }
    }
    LaunchedEffect(laeuft) { while (laeuft) withFrameNanos { MusikPuls.takt() } }
    LaunchedEffect(laeuft) { while (laeuft) { kotlinx.coroutines.delay(2000); Beat.starten(ctx) } }
}

/** Kreise (falls eingeschaltet): bei jedem Schlag ein Tropfen am Alphabet */
@Composable
internal fun MusikKreise() {
    val nr = MusikPuls.schlagNr.intValue
    val tropfenAn = LocalZustand.current.wellenTropfen
    LaunchedEffect(nr) {
        if (nr > 0 && tropfenAn && MusikPuls.an.value && WellenReiz.abcX >= 0f && WellenReiz.abcUnten > 0f)
            WellenReiz.tropfen(WellenReiz.abcX, WellenReiz.abcOben + MusikPuls.schlagY * (WellenReiz.abcUnten - WellenReiz.abcOben), erzwingen = true)
    }
}
