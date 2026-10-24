package de.atelierlauncher.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

// ============================================================================
//  Live-Vorschau fuer die Design-Untermenues: ein kleines Handy oben, das sofort zeigt,
//  was man unten aendert. Es zeigt die ECHTEN Teile des Launchers (Hintergrund, Kopf mit Uhr,
//  Favoriten, Alphabet), nur verkleinert. Das Alphabet darin kann man sogar anfassen.
// ============================================================================

/** Seite mit fester Vorschau oben und scrollbaren Einstellungen darunter */
@Composable
internal fun VorschauSeite(vorschau: @Composable () -> Unit, inhalt: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().fillMaxHeight()) {
        Box(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 8.dp), contentAlignment = Alignment.Center) { vorschau() }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) { inhalt() }
    }
}

private val VIRT_B = 360.dp
private val VIRT_H = 780.dp

/** Kleines Handy: innen wird in voller Groesse (360 x 780 dp) gezeichnet und dann verkleinert */
@Composable
internal fun MiniHandy(hoehe: Dp = 250.dp, inhalt: @Composable BoxScope.() -> Unit) {
    val p = LocalPalette.current
    val f = hoehe / VIRT_H
    val form = RoundedCornerShape(18.dp)
    Box(
        Modifier.size(VIRT_B * f, hoehe).clip(form).border(1.5.dp, p.leise.copy(alpha = 0.45f), form),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.requiredSize(VIRT_B, VIRT_H).graphicsLayer { scaleX = f; scaleY = f }.background(p.grund), content = inhalt)
    }
}

/** faengt alle Beruehrungen ab (Vorschau soll nichts ausloesen) */
private fun Modifier.nichtAnfassbar() = pointerInput(Unit) {
    awaitPointerEventScope { while (true) { awaitPointerEvent().changes.forEach { it.consume() } } }
}

/** Startbildschirm im Kleinen: Hintergrund, Kopf mit Uhr, Favoriten, Wellen-Ebenen, Alphabet */
@Composable
internal fun MiniLauncher() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    MiniHandy {
        val hg = z.thema.hintergrund
        val wellenOben = hg in setOf("wellen", "eis", "pixel")
        if (wellenOben) Box(Modifier.fillMaxSize().background(p.grund))
        else Hintergrund(hg, Modifier.fillMaxSize(), laeuft = true)
        Startseite(Modifier.fillMaxSize().padding(start = 28.dp, end = 44.dp, top = 40.dp), {}, {}, {}, {})
        if (wellenOben || (z.thema.alphabet == "welle" && !z.eigeneDaumenEbene))
            Hintergrund(if (wellenOben) hg else "wellen", Modifier.fillMaxSize(), laeuft = true, nurWellen = true)
        if (z.alphabetRosette) GeldRosetteDaumen(Modifier.fillMaxSize(), p.dunkel)
        else if (z.alphabetKante) SwissKanteDaumen(Modifier.fillMaxSize(), p.dunkel, p.akzent)
        else if (z.thema.alphabet == "swiss") Hintergrund("swiss", Modifier.fillMaxSize(), laeuft = true, nurWellen = true)
        Box(Modifier.fillMaxSize().nichtAnfassbar())
        // Alphabet zum Ausprobieren (Daumen drauf = Welle, Buchstabe, ... wie echt)
        AlphabetStrich(
            buchstaben = ('A'..'Z').map { it.toString() } + "#",
            modifier = Modifier.align(Alignment.BottomEnd).padding(top = 130.dp, bottom = LocalZustand.current.alphabetUnten.dp, end = 4.dp)
                .fillMaxHeight(LocalZustand.current.alphabetHoehe / 100f).width(LocalZustand.current.alphabetDicke.dp),
        ) { }
    }
}

// ---------------------------------------------------------------- Sperrbildschirm
@Composable
internal fun MiniSperr(seiteAn: Boolean, art: String) {
    val p = LocalPalette.current
    val z = LocalZustand.current
    val f = remember { mutableFloatStateOf(1f) }
    LaunchedEffect(seiteAn, art) {
        f.floatValue = 1f
        if (!seiteAn) return@LaunchedEffect
        suspend fun fahre(von: Float, nach: Float, ms: Long) {
            val start = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { ((it - start) / 1_000_000f / ms).coerceIn(0f, 1f) }
                val e = 1f - (1f - t) * (1f - t) * (1f - t)
                f.floatValue = von + (nach - von) * e
                if (t >= 1f) break
            }
        }
        while (true) {
            delay(700); fahre(1f, 0f, 520); delay(1300); fahre(0f, 1f, 420)
        }
    }
    MiniHandy {
        Hintergrund(z.thema.hintergrund, Modifier.fillMaxSize(), laeuft = true)
        Column(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 70.dp)) { SperrUhr() }
        if (seiteAn) SeiteKarteDemo(Modifier.fillMaxSize().uebergangDemo(art, { f.floatValue }, p.akzent, Color(wellenFarbe(z.thema.welle, p.dunkel)), p.text))
        Box(Modifier.fillMaxSize().nichtAnfassbar())
    }
}

@Composable
private fun SeiteKarteDemo(modifier: Modifier) {
    val p = LocalPalette.current
    Column(modifier.background(p.grund).padding(start = 28.dp, end = 44.dp, top = 60.dp)) {
        Text("12:34", color = p.text, fontSize = 96.sp)
        Spacer(Modifier.height(18.dp))
        listOf("1  Mathe", "2  Deutsch", "3  Englisch", "4  Physik", "5  Sport").forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(14.dp))
                .background(p.text.copy(alpha = if (i == 1) 0.16f else 0.07f)).padding(16.dp)) {
                Box(Modifier.size(width = 5.dp, height = 22.dp).background(if (i == 1) p.akzent else p.leise.copy(alpha = 0.4f)))
                Spacer(Modifier.width(12.dp))
                Text(s, color = p.text, fontSize = 20.sp)
            }
        }
    }
}

private fun Modifier.uebergangDemo(art: String, f: () -> Float, akzent: Color, welle: Color, tinte: Color = Color.White): Modifier =
    this.graphicsLayer {
        val w = size.width
        val v = f()
        val weich = v * v * (3 - 2 * v)
        when (art) {
            "schieben" -> translationX = v * w
            "karte" -> { translationX = v * w; scaleX = 1f - 0.07f * weich; scaleY = scaleX; alpha = 1f - 0.25f * weich
                         shape = RoundedCornerShape(30.dp * kotlin.math.min(1f, v * 4f)); clip = true }
            "zoom" -> { val s = 1f - 0.3f * weich; scaleX = s; scaleY = s; alpha = 1f - weich
                        shape = RoundedCornerShape(34.dp * kotlin.math.min(1f, v * 3f)); clip = true }
            "tiefe" -> { transformOrigin = TransformOrigin(1f, 0.5f); rotationY = -80f * weich; translationX = v * w * 0.12f
                         alpha = 1f - 0.6f * weich; cameraDistance = 12f * density }
        }
    }.drawWithContent {
        val v = f()
        if (v >= 0.999f && art !in setOf("karte", "schieben", "zoom", "tiefe")) return@drawWithContent
        when (art) {
            "raster" -> {
                if (v <= 0.001f) { drawContent(); return@drawWithContent }
                val w = size.width; val h = size.height
                val weg = SwissRaster.felder(w, h, v, density, true, 1234)
                val pfad = Path()
                for (fd in weg) pfad.addRect(Rect(fd[0], fd[1], fd[2], fd[3]))
                clipPath(pfad, ClipOp.Difference) { this@drawWithContent.drawContent() }
                for (fd in weg) {
                    if (fd[6] <= 0.01f) continue
                    val farbe = tinte.copy(alpha = fd[6] * (1f - v * 0.5f))
                    SwissRaster.linien(fd, true, 1234) { a, b, c, d ->
                        drawLine(farbe, Offset(a, b), Offset(c, d), 1.5.dp.toPx(), cap = StrokeCap.Square)
                    }
                }
            }
            "welle", "swiss", "geld", "glas", "skizze", "riss", "jalousie", "kreis" -> {
                if (v <= 0.001f) { drawContent(); return@drawWithContent }
                val w = size.width; val h = size.height
                val e = v * w
                val bogen = kotlin.math.sin(Math.PI * v).toFloat()
                val pfad = Path()
                val kante = Path()
                when (art) {
                    "welle" -> {
                        val amp = 26.dp.toPx() * bogen
                        var y = 0f; var erst = true
                        pfad.moveTo(w, 0f)
                        while (y <= h + 10f) {
                            val s = 0.7f * kotlin.math.sin(y / 130.dp.toPx() * 6.283f + v * 9f) + 0.3f * kotlin.math.sin(y / 57.dp.toPx() * 6.283f - v * 15f)
                            val xx = e + amp * (s - 1f)
                            pfad.lineTo(xx, y)
                            if (erst) { kante.moveTo(xx, y); erst = false } else kante.lineTo(xx, y)
                            y += 8f
                        }
                        pfad.lineTo(w, h + 10f); pfad.close()
                    }
                    "swiss", "geld", "glas" -> pfad.addRect(Rect(e, 0f, w, h))
                    "skizze" -> {
                        var y = 0f; var erst = true
                        pfad.moveTo(w, 0f)
                        while (y <= h + 6f) {
                            val xx = e + (2.4f * kotlin.math.sin(y / h * 6.283f * 2.2f + 1f) + 1.1f * kotlin.math.sin(y / h * 6.283f * 9f)) * density
                            pfad.lineTo(xx, y)
                            if (erst) { kante.moveTo(xx, y); erst = false } else kante.lineTo(xx, y)
                            y += 6f
                        }
                        pfad.lineTo(w, h + 6f); pfad.close()
                    }
                    "riss" -> {
                        // zackige Bruchkante
                        var y = 0f; var i = 0
                        pfad.moveTo(w, 0f)
                        while (y <= h + 20f) {
                            val xx = e + (if (i % 2 == 0) 1f else -1f) * (3f + (i * 37 % 7)) * density * (0.4f + bogen)
                            pfad.lineTo(xx, y)
                            if (i == 0) kante.moveTo(xx, y) else kante.lineTo(xx, y)
                            y += 18f * density; i++
                        }
                        pfad.lineTo(w, h + 20f); pfad.close()
                    }
                    "jalousie" -> {
                        val n = 12; val st = h / n
                        for (i in 0 until n) {
                            val vor = 0.45f * w * bogen * (i.toFloat() / (n - 1) - 0.5f)
                            pfad.addRect(Rect((e + vor).coerceIn(0f, w), i * st, w, (i + 1) * st + 0.5f))
                        }
                    }
                    "kreis" -> {
                        val r = (1f - v) * kotlin.math.hypot(w, h / 2f) * 1.02f
                        pfad.addOval(Rect(Offset(w, h / 2f), r)); kante.addOval(Rect(Offset(w, h / 2f), r))
                    }
                }
                clipPath(pfad) { this@drawWithContent.drawContent() }
                when (art) {
                    "welle" -> drawPath(kante, welle.copy(alpha = 0.9f * bogen.coerceAtLeast(0.35f)), style = Stroke(3.5.dp.toPx()))
                    "swiss" -> drawRect(akzent, Offset(e - 12.dp.toPx(), 0f), Size(12.dp.toPx(), h))
                    "glas" -> {
                        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.4f), Color.Transparent), e, e + 28.dp.toPx()),
                                 Offset(e, 0f), Size(28.dp.toPx(), h))
                        drawLine(Color.White.copy(alpha = 0.85f), Offset(e + 1f, 0f), Offset(e + 1f, h), 1.5.dp.toPx())
                    }
                    "geld" -> {
                        // Zierband: Doppellinie + zwei verschlungene Wellen
                        val d1 = density
                        drawLine(akzent, Offset(e, 0f), Offset(e, h), 1.4f * d1)
                        drawLine(akzent.copy(alpha = 0.8f), Offset(e + 3.5f * d1, 0f), Offset(e + 3.5f * d1, h), 0.7f * d1)
                        drawLine(akzent.copy(alpha = 0.8f), Offset(e + 18f * d1, 0f), Offset(e + 18f * d1, h), 0.7f * d1)
                        for (vz in listOf(1f, -1f)) {
                            val wp = Path(); var y = 0f
                            wp.moveTo(e + 10.75f * d1, 0f)
                            while (y <= h) { wp.lineTo(e + 10.75f * d1 + vz * 5.5f * d1 * kotlin.math.sin(y / (14f * d1) * 6.283f), y); y += 2f * d1 }
                            drawPath(wp, akzent.copy(alpha = 0.8f), style = Stroke(0.8f * d1))
                        }
                    }
                    "kreis" -> drawPath(kante, welle.copy(alpha = 0.8f * bogen), style = Stroke(3.dp.toPx()))
                    "skizze" -> drawPath(kante, Color(Skizze.TINTE), style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    "riss" -> {
                        drawPath(kante, Color.Black.copy(alpha = 0.8f), style = Stroke(3.5.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                        drawPath(kante, Deko.GOLD_BRUSH, style = Stroke(1.6.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                }
            }
            else -> drawContent()
        }
    }

// ---------------------------------------------------------------- Randgesten
/** Randgeste im Kleinen: ein Finger wischt vom rechten Rand rein, die gewaehlte Animation laeuft wie echt */
@Composable
internal fun MiniRandgesten(stil: String) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val dichte = LocalDensity.current.density
    val finger = remember { mutableFloatStateOf(-1f) }       // Finger-Abstand vom Rand in px (-1 = kein Finger)
    val gross = when (stil) { "gross" -> "welle"; "grossswiss" -> "swiss"; "grossfelder" -> "felder"; "grossgeld" -> "geld"; "skizze" -> "skizze"; "riss" -> "riss"; else -> null }
    val welleRef = remember { arrayOfNulls<RandWelle>(1) }
    val kanteRef = remember { arrayOfNulls<Uebergang.Wischer>(1) }
    MiniHandy {
        // eine "App" als Untergrund
        Column(Modifier.fillMaxSize().background(p.karte).padding(28.dp)) {
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(180.dp, 28.dp).clip(RoundedCornerShape(8.dp)).background(p.text.copy(alpha = 0.25f)))
            repeat(7) {
                Spacer(Modifier.height(22.dp))
                Box(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(14.dp)).background(p.text.copy(alpha = 0.08f)))
            }
        }
        if (stil != "aus") {
            if (gross != null) AndroidView(
                factory = { c -> Uebergang.Wischer(c, gross, false).also { kanteRef[0] = it } },
                modifier = Modifier.fillMaxSize(),
            ) else AndroidView(
                factory = { c ->
                    Randgesten.farbeLaden(c)
                    RandWelle(c, false, Randgesten.welleAkzent ?: 0xFF83D5C5.toInt(), Randgesten.welleDunkel).also { welleRef[0] = it }
                },
                modifier = Modifier.align(Alignment.CenterEnd).width(130.dp).fillMaxHeight(),
            )
        }
        // Finger
        val fx = finger.floatValue
        if (fx >= 0f) Box(
            Modifier.align(Alignment.CenterEnd).offset(x = (-(fx / dichte)).dp + 22.dp).size(44.dp).clip(CircleShape)
                .background(Color.White.copy(alpha = 0.35f)).border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
        )
        Box(Modifier.fillMaxSize().nichtAnfassbar())
    }
    LaunchedEffect(stil) {
        Randgesten.farbeLaden(ctx)
        val ziel = 360f * dichte / 5f / 1.1f            // bis zum Fuenftel (dort loest die Geste aus)
        val mitte = 390f * dichte
        while (true) {
            delay(600)
            welleRef[0]?.neu()
            kanteRef[0]?.neuStart(gross ?: "welle", (360 * dichte).toInt(), (780 * dichte).toInt())
            val start = withFrameNanos { it }
            while (true) {
                val t = withFrameNanos { ((it - start) / 1_000_000f / 480f).coerceIn(0f, 1f) }
                val zug = ziel * (1f - (1f - t) * (1f - t))
                finger.floatValue = zug
                welleRef[0]?.ziehen(mitte, zug, if (zug > 22f * dichte) "g" else null)
                kanteRef[0]?.ziel = zug * 1.1f
                if (t >= 1f) break
            }
            // Fuenftel erreicht: ausloesen
            welleRef[0]?.loslassen(true)
            kanteRef[0]?.durch { }
            delay(250)
            finger.floatValue = -1f
            delay(1100)
        }
    }
}
