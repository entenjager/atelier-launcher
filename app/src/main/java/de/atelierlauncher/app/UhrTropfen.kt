package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import kotlinx.coroutines.delay
import kotlin.random.Random

// ============================================================================
//  Uhr-Schrift "Tropfen" (25.10.): klare Ziffern, die ab und zu als spitzer Faden nach unten auslaufen und abtropfen
//  (wie der After-Effects-Effekt "CC Smear"). Nur Vektor-Formen (25.10.).
//  Der fallende Tropfen landet auf Buchstaben darunter (TropfZiele / TropfSchicht).
// ============================================================================

/** ein Faden an einer Ziffer: Stelle x, Unterkante u, Dicke des Streifens, Laenge, Breite, Start, Dauer */
private class Faden(val x: Float, val u: Float, val dick: Float, val lang: Float, val breit: Float,
                    val start: Long, val dauer: Long) { var weg = false }

/** ab hier reisst der Faden ab (Anteil der Dauer) */
private const val LOS = 0.62f

private fun glatt(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

/** ein Zeichen als Vektor: fertiger Umriss (fuer Ruhe) und fein abgetastete Punkte (zum Verformen), dazu seine Breite */
private class Zeichen(val ch: Char, val ruhe: android.graphics.Path, val punkte: List<FloatArray>, val links: Float, val rechts: Float,
                      val kandidaten: IntArray)

/**
 * Uhr-Schrift "Tropfen" (25.10., Vektor): klare fette Ziffern (Inter ExtraBold) als Umrisse. Ab und zu zieht sich die
 * Unterkante einer Ziffer zu langen spitzen Faeden nach unten (der unterste Streifen wird gedehnt - wie "CC Smear"),
 * das untere Stueck reisst als schmaler Tropfen ab und faellt (TropfSchicht), der Rest schnellt zurueck.
 * Je Ziffer 10 Ablaeufe (Stellen, Laengen, Tempo, ein oder zwei Faeden), immer gleich fuer dieselbe Ziffer.
 * Nur Vektor-Formen, kein Shader, keine Bilder: bleibt in jeder Groesse scharf und kostet kaum etwas.
 */
@Composable
internal fun TropfZiffern(text: String, stil: TextStyle, farbe: Color, modifier: Modifier) {
    val d = LocalDensity.current
    val messer = rememberTextMeasurer()
    val layout = remember(text, stil) { messer.measure(text, stil) }
    val w = layout.size.width.coerceAtLeast(1); val h = layout.size.height.coerceAtLeast(1)
    val e = h.toFloat()                                    // alle Masse relativ zur Hoehe der Ziffern-Zeile
    val rand = (e * 0.05f).toInt().coerceAtLeast(2)
    val randUnten = (e * 1.2f).toInt()                     // unten Platz fuer die langen Faeden
    val bw = w + 2 * rand; val bh = h + rand + randUnten

    // Unterkante und Strichdicke je Spalte (wo ein Faden ansetzen kann) - einmal pro Uhrzeit
    val profil = remember(layout) {
        val bm = ImageBitmap(w, h)
        CanvasDrawScope().draw(d, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bm), Size(w.toFloat(), h.toFloat())) {
            drawText(layout, color = Color.White)
        }
        val px = IntArray(w * h)
        bm.asAndroidBitmap().getPixels(px, 0, w, 0, 0, w, h)
        fun voll(x: Int, y: Int) = (px[y * w + x] ushr 24) > 128
        val u = IntArray(w); val dicke = IntArray(w)
        for (x in 0 until w) {
            var y = h - 1
            while (y >= 0 && !voll(x, y)) y--
            u[x] = y
            var k = 0
            while (y - k >= 0 && voll(x, y - k)) k++
            dicke[x] = k
        }
        u to dicke
    }
    val unten = profil.first; val dicke = profil.second

    // jedes Zeichen als echter Umriss aus der Schrift (Inter ExtraBold), an derselben Stelle wie im Text.
    // Unten fein abgetastet (dort entstehen die duennen Faeden), sonst grob.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val schriftart = remember { try { androidx.core.content.res.ResourcesCompat.getFont(ctx, R.font.inter_extrabold) } catch (x: Exception) { null } }
    val groessePx = with(d) { if (stil.fontSize.isSpecified) stil.fontSize.toPx() else e * 0.8f }
    val fett = (stil.fontWeight?.weight ?: 400) >= 600                 // wie Compose: "fett" wird bei dieser Schrift nachgemacht
    val schraeg = stil.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic
    val zeichen = remember(layout, schriftart, groessePx, fett, schraeg) {
        val liste = ArrayList<Zeichen>()
        val pos = FloatArray(2)
        val stift = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = schriftart; textSize = groessePx; isFakeBoldText = fett; if (schraeg) textSkewX = -0.25f
        }
        val grund = layout.getLineBaseline(0)
        for (i in text.indices) {
            if (text[i].isWhitespace()) continue
            val umriss = android.graphics.Path()
            stift.getTextPath(text, i, i + 1, layout.getHorizontalPosition(i, true), grund, umriss)
            val box = android.graphics.RectF(); umriss.computeBounds(box, true)
            if (box.isEmpty) continue
            val zone = box.bottom - e * 0.16f                // darunter fein abtasten
            val konturen = ArrayList<FloatArray>()
            val pm = android.graphics.PathMeasure(umriss, true)
            do {
                val len = pm.length
                if (len <= 0f) continue
                var n = 0; val pts = FloatArray(((len / 0.3f).toInt() + 4) * 2)
                var s = 0f
                while (s < len && n < pts.size - 2) {
                    pm.getPosTan(s, pos, null)
                    pts[n++] = pos[0]; pts[n++] = pos[1]
                    s += if (pos[1] > zone) 0.35f else 1.5f
                }
                konturen += pts.copyOf(n)
            } while (pm.nextContour())
            // Stellen fuer Faeden: ganz unten an der Ziffer, wo sie dick genug ist
            val x0 = box.left.toInt().coerceIn(0, w - 1); val x1 = box.right.toInt().coerceIn(0, w - 1)
            var tief = -1
            for (x in x0..x1) tief = maxOf(tief, unten[x])
            val kand = (x0..x1).filter { x -> text[i].isDigit() && unten[x] >= tief - e * 0.04f && dicke[x] >= e * 0.05f }.toIntArray()
            liste += Zeichen(text[i], android.graphics.Path(umriss), konturen, box.left, box.right, kand)
        }
        liste
    }

    val faeden = remember { mutableStateListOf<Faden>() }
    var jetzt by remember { mutableLongStateOf(0L) }
    val sichtbar = Sichtbar.an
    val imEditor = LocalImEditor.current                   // im Baukasten tropft nichts
    var lage by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // solange diese Uhr da ist, merken sich die Texte ihre Lage (dort landen die Tropfen)
    androidx.compose.runtime.DisposableEffect(Unit) { TropfZiele.zaehler++; onDispose { TropfZiele.zaehler-- } }

    /** wie lang der Faden gerade ist: waechst, reisst ab, schnellt zurueck */
    fun laenge(f: Faden, zeit: Long): Float {
        val p = ((zeit - f.start).toFloat() / f.dauer)
        if (p <= 0f) return 0f
        if (p >= 1f) return 0f
        return if (p < LOS) f.lang * glatt(0f, 1f, p / LOS).let { it * (0.6f + 0.4f * it) }
        else {
            val k = (p - LOS) / (1f - LOS)
            f.lang * 0.5f * maxOf(0f, (1f - k) * (1f - k) * (1f + 2.5f * k) - 0.04f * kotlin.math.sin(k * 9f))
        }
    }

    /** ein Ablauf (0..9) fuer Zeichen z: ein oder zwei Faeden - fuer dieselbe Ziffer immer gleich */
    fun ablauf(z: Zeichen, ziffer: Char, nr: Int, t: Long): List<Faden> {
        val k = z.kandidaten
        if (k.isEmpty()) return emptyList()
        val r = Random(ziffer.code * 31 + nr)
        val anzahl = if (nr % 3 == 0) 2 else 1
        val raus = ArrayList<Faden>()
        repeat(anzahl) { j ->
            var x = k[r.nextInt(k.size)]
            // zweiter Faden nicht zu nah am ersten
            if (raus.isNotEmpty()) {
                val weiter = k.filter { kotlin.math.abs(it - raus[0].x) >= e * 0.16f }
                if (weiter.isEmpty()) return@repeat
                x = weiter[r.nextInt(weiter.size)]
            }
            val versatz = if (j == 0) 0L else 150L + r.nextInt(550)
            raus += Faden(x.toFloat(), (unten[x] + 1).toFloat(), minOf(dicke[x] * 0.7f, e * 0.09f),
                          e * (0.2f + 0.25f * r.nextFloat()), e * (0.024f + 0.01f * r.nextFloat()),
                          t + versatz, 2000L + r.nextInt(1400))
        }
        return raus
    }

    // neue Uhrzeit (z. B. 9 -> 10) bricht nichts ab: die Schleife liest immer die aktuellen Werte
    val zeichenJetzt = androidx.compose.runtime.rememberUpdatedState(zeichen)
    val ablaufVon = androidx.compose.runtime.rememberUpdatedState { z: Zeichen, c: Char, nr: Int, t: Long -> ablauf(z, c, nr, t) }
    val werte = androidx.compose.runtime.rememberUpdatedState(floatArrayOf(rand.toFloat(), e))
    val farbeJetzt = androidx.compose.runtime.rememberUpdatedState(farbe)
    LaunchedEffect(sichtbar, imEditor) {
        faeden.clear()
        if (!sichtbar || imEditor) return@LaunchedEffect
        var naechster = withFrameMillis { it } + Random.nextLong(1500, 4000)
        while (true) {
            val t = withFrameMillis { it }
            jetzt = t
            faeden.removeAll { t - it.start > it.dauer }
            // abgerissen: das untere Stueck faellt als schmaler Tropfen auf der Schicht ueber dem Startbildschirm weiter
            for (f in faeden) if (!f.weg && t - f.start >= f.dauer * LOS) {
                f.weg = true
                val c = lage
                if (c != null && c.isAttached) try {
                    val o = werte.value[0]
                    val mitte = c.localToWindow(Offset(f.x + o, f.u + o + f.lang * 0.78f))
                    val massstab = (c.localToWindow(Offset(f.x + o + 100f, f.u + o)).x - c.localToWindow(Offset(f.x + o, f.u + o)).x) / 100f
                    TropfZiele.fallen(mitte, f.breit * 0.55f * massstab, farbeJetzt.value, t, lang = f.lang * 0.42f * massstab)
                } catch (x: Exception) { }
            }
            if (t >= naechster) {
                // normal wenige: je mehr schon laufen, desto laenger bis zum naechsten (hoechstens 3 Faeden)
                val alle = zeichenJetzt.value
                val frei = alle.indices.filter { i ->
                    alle[i].kandidaten.isNotEmpty() && faeden.none { it.x >= alle[i].links && it.x <= alle[i].rechts }
                }
                if (frei.isNotEmpty() && faeden.size < 3) {
                    val i = frei[Random.nextInt(frei.size)]
                    val z = alle[i]
                    val neu = ablaufVon.value(z, z.ch, Random.nextInt(10), t)
                    neu.take(3 - faeden.size).forEach { faeden.add(it) }
                }
                naechster = t + Random.nextLong(2500, 6500) * (1 + faeden.size)
            }
            if (faeden.isEmpty()) delay((naechster - t).coerceIn(16L, 10_000L))      // nichts zu bewegen: schlafen
        }
    }

    // zum Malen: ein Pfad und ein Pinsel, die immer wieder benutzt werden (nichts Neues pro Bild)
    val pfad = remember { android.graphics.Path() }
    val aktiv = remember { FloatArray(3 * 5) }               // je Faden: x, Unterkante, Dicke, Laenge jetzt, Breite
    val pinsel = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.FILL } }

    Box(
        modifier
            // groesser zeichnen als die Ziffern, aber nur so viel Platz belegen wie normal (wie die Glas-Uhr)
            .layout { m, _ -> val pl = m.measure(Constraints.fixed(bw, bh)); layout(w, h) { pl.place(-rand, -rand) } },
    ) {
        Canvas(Modifier.fillMaxSize().onGloballyPositioned { lage = it }) {
            val o = rand.toFloat()
            val zeit = jetzt
            pinsel.color = farbe.toArgb()
            pfad.rewind()
            var n = 0
            for (i in 0 until minOf(faeden.size, 3)) {
                val f = faeden[i]
                val l = laenge(f, zeit)
                if (l <= 0.3f) continue
                aktiv[n * 5] = f.x; aktiv[n * 5 + 1] = f.u; aktiv[n * 5 + 2] = f.dick; aktiv[n * 5 + 3] = l; aktiv[n * 5 + 4] = f.breit
                n++
            }
            for (z in zeichen) {
                // Faeden an diesem Zeichen?
                var betroffen = false
                for (j in 0 until n) { val fx = aktiv[j * 5]; val fb = aktiv[j * 5 + 4]; if (fx >= z.links - fb * 4f && fx <= z.rechts + fb * 4f) { betroffen = true; break } }
                if (!betroffen) { pfad.addPath(z.ruhe); continue }
                // Umriss verformen: den untersten Streifen (Hoehe dick) an jedem Faden nach unten dehnen
                for (pts in z.punkte) {
                    var i = 0
                    while (i < pts.size) {
                        val x = pts[i]; var y = pts[i + 1]
                        for (j in 0 until n) {
                            val fb = aktiv[j * 5 + 4]
                            val dx = x - aktiv[j * 5]
                            if (dx > fb * 4f || dx < -fb * 4f) continue
                            val s = aktiv[j * 5 + 3] * kotlin.math.exp(-(dx * dx) / (fb * fb))
                            val dk = aktiv[j * 5 + 2]
                            val oben = aktiv[j * 5 + 1] - dk
                            if (s > 0.2f && y > oben) y = oben + (y - oben) * (dk + s) / dk
                        }
                        if (i == 0) pfad.moveTo(x, y) else pfad.lineTo(x, y)
                        i += 2
                    }
                    pfad.close()
                }
            }
            drawContext.canvas.nativeCanvas.let { nc ->
                nc.save(); nc.translate(o, o); nc.drawPath(pfad, pinsel); nc.restore()
            }
        }
    }
}

// ============================================================================
//  Wo die Tropfen landen: auf Buchstaben anderer Texte darunter (nicht auf dem leeren Rand eines Bausteins).
//  Jeder Text (TextUe) meldet seine Lage nur, solange eine Tropf-Uhr zu sehen ist.
// ============================================================================
internal object TropfZiele {
    class Ziel {
        var lage: LayoutCoordinates? = null; var text: TextLayoutResult? = null
        private var bild: android.graphics.Bitmap? = null; private var bildFuer: TextLayoutResult? = null
        /** der Text als Bild (nur wenn ein Tropfen darauf fallen koennte), um die echte Form der Buchstaben zu sehen */
        fun bild(t: TextLayoutResult): android.graphics.Bitmap? {
            if (bildFuer === t) return bild
            val bw = t.size.width; val bh = t.size.height
            bild = if (bw <= 0 || bh <= 0 || bw.toLong() * bh > 4_000_000L) null else try {
                val ib = ImageBitmap(bw, bh)
                CanvasDrawScope().draw(t.layoutInput.density, t.layoutInput.layoutDirection, androidx.compose.ui.graphics.Canvas(ib), Size(bw.toFloat(), bh.toFloat())) {
                    drawText(t, color = Color.White)
                }
                ib.asAndroidBitmap()
            } catch (e: Exception) { null }
            bildFuer = t
            return bild
        }
    }
    val ziele = LinkedHashSet<Ziel>()
    var zaehler by mutableIntStateOf(0)
    val aktiv get() = zaehler > 0

    /** ein fallender Tropfen (Fenster-Koordinaten) */
    class Fall(val x: Float, val y0: Float, val r: Float, val farbe: Color, val start: Long, val oben: Float?, val unten: Float?,
               val dauer: Float,       // Sekunden bis er ganz weg ist
               val lang: Float)        // Laenge (lang und schmal wie ein abgerissener Faden)
    val fallend = mutableStateListOf<Fall>()
    /** Schwerkraft in px/s^2 (setzt die Schicht passend zur Bildschirmdichte) */
    var g = 4700f

    fun fallen(mitte: Offset, r: Float, farbe: Color, t: Long, lang: Float = r * 2.4f) {
        if (fallend.size >= 6) return
        val ziel = treffer(mitte.x, mitte.y + lang / 2f, r)
        val dauer = if (ziel != null) kotlin.math.sqrt(2f * (ziel.first - lang / 2f - mitte.y).coerceAtLeast(0f) / g) + 1.3f
                    else kotlin.math.sqrt(2f * 3500f / g) + 0.2f
        fallend.add(Fall(mitte.x, mitte.y, r, farbe, t, ziel?.first, ziel?.second, dauer, lang))
    }

    /** erster Buchstabe unter (x, ab): wo er landet und wo er abtropft (Fenster-Koordinaten) */
    private fun treffer(x: Float, ab: Float, r: Float): Pair<Float, Float>? {
        var best: Pair<Float, Float>? = null
        for (z in ziele) {
            val c = z.lage ?: continue
            val t = z.text ?: continue
            if (!c.isAttached) continue
            try {
                val lokal = c.windowToLocal(Offset(x, ab))
                if (lokal.x < 0f || lokal.x > c.size.width) continue
                for (i in 0 until t.lineCount) {
                    if (lokal.x < t.getLineLeft(i) || lokal.x > t.getLineRight(i)) continue
                    // die echte Form an dieser Stelle: oberster und unterster Punkt des Buchstabens in dieser Spalte
                    // (oben landet der Tropfen, unten tropft er ab; Luecke = faellt durch)
                    val bm = z.bild(t) ?: continue
                    val xi = lokal.x.toInt().coerceIn(0, bm.width - 1)
                    val y0 = t.getLineTop(i).toInt().coerceIn(0, bm.height - 1); val y1 = t.getLineBottom(i).toInt().coerceIn(0, bm.height - 1)
                    var hoch = -1; var tief = -1
                    for (y in y0..y1) if ((bm.getPixel(xi, y) ushr 24) > 128) { if (hoch < 0) hoch = y; tief = y }
                    if (hoch < 0) continue
                    val oben = c.localToWindow(Offset(lokal.x, hoch.toFloat())).y
                    val unten = c.localToWindow(Offset(lokal.x, (tief + 1).toFloat())).y
                    if (oben <= ab) continue
                    if (best == null || oben < best.first) best = oben to unten
                }
            } catch (e: Exception) { }
        }
        return best
    }
}

/**
 * Schicht ueber dem Startbildschirm: fallende Tropfen. Trifft einer einen Buchstaben, bleibt er kurz darauf liegen,
 * laeuft an ihm herunter und tropft unten ab. Sonst faellt er bis unten aus dem Bild.
 */
@Composable
internal fun TropfSchicht(modifier: Modifier) {
    val liste = TropfZiele.fallend
    val d = LocalDensity.current
    var jetzt by remember { mutableLongStateOf(0L) }
    var ursprung by remember { mutableStateOf(Offset.Zero) }
    val form = remember { androidx.compose.ui.graphics.Path() }          // wird fuer jeden Tropfen neu belegt
    val g = with(d) { 1800.dp.toPx() }                         // Schwerkraft in px/s^2
    TropfZiele.g = g
    val etwas = liste.isNotEmpty()
    LaunchedEffect(etwas) {
        while (liste.isNotEmpty()) {
            val t = withFrameMillis { it }
            jetzt = t
            liste.removeAll { (t - it.start) / 1000f > it.dauer }
        }
    }
    Canvas(modifier.onGloballyPositioned { ursprung = it.positionInWindow() }) {
        if (liste.isEmpty()) return@Canvas
        val t = jetzt
        for (f in liste) {
            val s = (t - f.start).coerceAtLeast(0L) / 1000f
            val x = f.x - ursprung.x
            fun tropfen(y: Float, r: Float, alpha: Float = 1f) {
                if (r < 0.4f) return
                val c = f.farbe.copy(alpha = f.farbe.alpha * alpha)
                // Tropfen: oben spitz, unten rund (laenglich wie im Video)
                val l = maxOf(f.lang * r / f.r, r * 2.2f)
                val oben = y - l / 2f; val mitteK = y + l / 2f - r
                form.reset()
                form.moveTo(x, oben)
                form.quadraticTo(x + r * 1.1f, mitteK - (mitteK - oben) * 0.3f, x + r, mitteK)
                form.lineTo(x - r, mitteK)
                form.quadraticTo(x - r * 1.1f, mitteK - (mitteK - oben) * 0.3f, x, oben)
                form.close()
                drawPath(form, c)
                drawCircle(c, r, Offset(x, mitteK))
            }
            // Groesse fuers Aufkommen: ein langer schmaler Tropfen hat mehr "Masse" als sein Radius
            val rv = kotlin.math.sqrt(f.r * f.lang / 2f).coerceAtLeast(f.r)
            val oben = f.oben
            val unten = f.unten
            if (oben == null || unten == null) {
                // nichts darunter: faellt aus dem Bild
                val y = f.y0 + 0.5f * g * s * s - ursprung.y
                if (y - f.r * 3f <= size.height) tropfen(y, f.r)
                continue
            }
            // Zeit bis zum Aufprall auf dem Buchstaben
            val weg = (oben - f.lang / 2f - f.y0).coerceAtLeast(0f)
            val ta = kotlin.math.sqrt(2f * weg / g)
            if (s < ta) { tropfen(f.y0 + 0.5f * g * s * s - ursprung.y, f.r); continue }
            val n = s - ta
            val obenL = oben - ursprung.y; val untenL = unten - ursprung.y
            when {
                // aufgeprallt: wird kurz flach auf dem Buchstaben
                n < 0.14f -> {
                    val k = n / 0.14f
                    val b = rv * (1.2f + 0.6f * k); val h = rv * (1.1f - 0.5f * k)
                    drawOval(f.farbe, Offset(x - b, obenL - h), Size(2f * b, h * 1.2f))
                }
                // laeuft vorne am Buchstaben herunter (wird dabei duenner)
                n < 0.74f -> {
                    val k = (n - 0.14f) / 0.6f
                    val y = obenL + (untenL - obenL) * k * k
                    val r = rv * (0.85f - 0.25f * k)
                    drawCircle(f.farbe, r, Offset(x, y))
                    drawRect(f.farbe.copy(alpha = f.farbe.alpha * 0.55f * (1f - k)), Offset(x - r * 0.35f, obenL), Size(r * 0.7f, (y - obenL).coerceAtLeast(0f)))
                }
                // tropft unten ab und verschwindet
                n < 1.3f -> {
                    val k = n - 0.74f
                    tropfen(untenL + f.r * 0.6f + 0.5f * g * k * k, f.r * 0.6f * (1f - k / 0.56f), 1f - k / 0.56f)
                }
                else -> { }
            }
        }
    }
}
