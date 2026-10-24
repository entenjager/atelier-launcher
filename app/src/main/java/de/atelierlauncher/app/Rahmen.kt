package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
//  Rahmen: eine feste Flaeche, die man selbst gross zieht. Bausteine zieht man einfach hinein
//  (und wieder heraus) - der Rahmen liegt dahinter und malt eine gemeinsame Linie drumherum.
//  Eigenschaften: Linie duenn/dick, durchgezogen/gestrichelt, Ecken, Hintergrund, Abstand.
// ============================================================================

/** die Linie, etwas ausserhalb der Felder (damit Bausteine darin nicht an der Linie kleben) */
@Composable
internal fun RahmenFlaeche(r: Baustein, modifier: Modifier, musikMitte: Offset? = null) {
    val p = LocalPalette.current
    val o = BOpt.aus(r.opt)
    val luft = (o.i("abstand", 4)).dp
    val dick = (o.i("dicke", if (o.s("linie") == "dick") 25 else 13) / 10f).dp
    val deck = o.i("deck", 12) / 100f
    val strich = o.s("art") == "strich"
    val eckig = o.s("ecke") == "eckig"
    val hg = o.b("hg", false)
    val farbe = if (SW.an) p.text else p.text.copy(alpha = 0.55f)
    // Milchglas: was hinter dem Rahmen ist (Hintergrund), weichgezeichnet + eingefaerbt - die Bausteine darauf bleiben scharf
    if (o.b("milch", false)) RahmenMilch(r.id, o, modifier, luft, if (eckig) 3.dp else 20.dp)
    // Musik-Baustein im Rahmen: Cover des Songs hinter allem, um die Musik herum - nach aussen durchsichtig
    if (musikMitte != null && o.b("cover", true)) RahmenCover(o, modifier, luft, if (eckig) 3.dp else 20.dp, musikMitte)
    Canvas(modifier) {
        val l = luft.toPx(); val w = dick.toPx()
        val rad = if (eckig) 3.dp.toPx() else 20.dp.toPx()
        val tl = Offset(-l + w / 2, -l + w / 2)
        val gr = Size(size.width + 2 * l - w, size.height + 2 * l - w)
        if (hg) drawRoundRect(p.text.copy(alpha = deck), tl, gr, CornerRadius(rad))
        if (w > 0f) drawRoundRect(farbe, tl, gr, CornerRadius(rad),
            style = Stroke(w, pathEffect = if (strich) PathEffect.dashPathEffect(floatArrayOf(maxOf(8.dp.toPx(), w * 3), maxOf(6.dp.toPx(), w * 2))) else null))
    }
}

/** im Editor: Hinweis im Rahmen */
@Composable
internal fun RahmenLeer(modifier: Modifier) {
    val p = LocalPalette.current
    Box(modifier, contentAlignment = Alignment.Center) {
        TextUe("Bausteine hineinziehen", color = p.leise, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun RahmenMilch(id: String, o: BOpt, modifier: Modifier, luft: androidx.compose.ui.unit.Dp, rad: androidx.compose.ui.unit.Dp) {
    androidx.compose.runtime.DisposableEffect(id) { onDispose { MilchFlaechen.rects.remove(id) } }
    val z = LocalZustand.current
    val view = androidx.compose.ui.platform.LocalView.current
    val dichte = androidx.compose.ui.platform.LocalDensity.current
    var lage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val blur = o.i("milchBlur", 30)
    // Farbe: 0 = keine (nur unscharf), sonst leicht eingetoent
    val farbe = o.i("milchFarbe", 0)
    val deck = if (farbe == 0) 0f else 0.22f
    val art = z.thema.hintergrund
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Handy-Hintergrund: das Bild von Android selbst einlesen (verkleinert - wird ja eh unscharf)
    // z.fortsetzen: nach dem Erlauben in den Einstellungen gleich neu versuchen
    val tapete by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, art, z.fortsetzen) {
        if (art != "system") { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val d = TapetenZugriff.bild(ctx) ?: return@withContext null
                val bw = (d.intrinsicWidth.coerceAtLeast(1)); val bh = (d.intrinsicHeight.coerceAtLeast(1))
                val f = minOf(1f, 540f / maxOf(bw, bh))
                val bm = android.graphics.Bitmap.createBitmap((bw * f).toInt().coerceAtLeast(1), (bh * f).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bm); d.setBounds(0, 0, bm.width, bm.height); d.draw(c)
                bm.asImageBitmap()
            } catch (t: Throwable) { Konsole.schreib("rahmen", "Handy-Hintergrund: " + t.message); null }
        }
    }
    // Video / Lottie / Code laufen woanders - dort nur die Farbe
    val grundDurch = art !in setOf("video", "lottie", "code") && (art != "system" || tapete != null)
    // Wellen hinter dem Alphabet liegen ueber den Bausteinen - die malen wir hier unscharf nach (dort werden sie ausgespart)
    val wellen = MilchFlaechen.wellenArt
    val durch = grundDurch || wellen != null
    Box(
        modifier
            .layout { m, c ->
                val l = luft.roundToPx()
                val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(c.maxWidth + 2 * l, c.maxHeight + 2 * l))
                layout(c.maxWidth, c.maxHeight) { pl.place(-l, -l) }
            }
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(rad))
            .onGloballyPositioned {
                lage = it.positionInRoot()
                val b = it.boundsInRoot()
                val neu = b to with(dichte) { rad.toPx() }
                if (MilchFlaechen.rects[id] != neu) MilchFlaechen.rects[id] = neu
            }
    ) {
        // gerade zum Startbildschirm zurueckgekommen: Milchglas erst nach dem ersten Bild weich einblenden
        val ein by androidx.compose.animation.core.animateFloatAsState(if (Heim.leicht) 0f else 1f,
            if (Heim.leicht) androidx.compose.animation.core.snap() else androidx.compose.animation.core.tween(220), label = "milchEin")
        if (durch && blur > 0 && android.os.Build.VERSION.SDK_INT >= 31 && view.width > 0 && ein > 0.01f) {
            Box(
                Modifier
                    .layout { m, c ->
                        val pl = m.measure(androidx.compose.ui.unit.Constraints.fixed(view.width, view.height))
                        layout(c.maxWidth, c.maxHeight) { pl.place(-lage.x.roundToInt(), -lage.y.roundToInt()) }
                    }
                    .graphicsLayer { alpha = ein; renderEffect = BlurEffect(blur.dp.toPx(), blur.dp.toPx(), TileMode.Clamp) }
            ) { androidx.compose.runtime.CompositionLocalProvider(LocalKopie provides true) {
                val tb = tapete
                if (art == "system" && tb != null)
                    androidx.compose.foundation.Image(tb, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else if (grundDurch && wellen != art) Hintergrund(art, Modifier.fillMaxSize(), laeuft = MilchFlaechen.laeuft)
                if (wellen != null) Box(Modifier.fillMaxSize().graphicsLayer { scaleX = MilchFlaechen.spiegel }) {
                    Hintergrund(wellen, Modifier.fillMaxSize(), laeuft = MilchFlaechen.laeuft, nurWellen = true)
                }
            }
            }
        }
        if (deck > 0f) Box(Modifier.fillMaxSize().background(Color(farbe).copy(alpha = deck)))
    }
}

/** Farben fuer das Milchglas (0 = keine) */
internal val MILCH_FARBEN = listOf(0, 0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF8E8E93.toInt(), 0xFF3A3A3C.toInt(), 0xFFE8DCC8.toInt(),
    0xFF1F3FD1.toInt(), 0xFF5AC8FA.toInt(), 0xFF00A651.toInt(), 0xFFE2001A.toInt(), 0xFFFF9500.toInt(), 0xFF7A3CFF.toInt(), 0xFFFF6FAE.toInt())

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MilchFarbWahl(aktuell: Int, setzen: (Int) -> Unit) {
    val p = LocalPalette.current
    TextUe("Farbe", color = p.text, fontSize = 16.sp, modifier = androidx.compose.ui.Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
    androidx.compose.foundation.layout.FlowRow(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
    ) {
        MILCH_FARBEN.forEach { f ->
            if (f == 0) Box(
                Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .border(if (aktuell == 0) 3.dp else 1.dp, if (aktuell == 0) p.text else p.linie, androidx.compose.foundation.shape.CircleShape)
                    .clickable { setzen(0) },
                contentAlignment = Alignment.Center,
            ) { TextUe("–", color = p.leise, fontSize = 18.sp) }
            else FarbPunkt(androidx.compose.ui.graphics.SolidColor(Color(f)), aktuell == f) { setzen(f) }
        }
    }
}

/** Milchglas-Rahmen auf dem Bildschirm (Lage in px) - die Wellen-Schicht spart sie aus */
internal object MilchFlaechen {
    val rects = androidx.compose.runtime.mutableStateMapOf<String, Pair<androidx.compose.ui.geometry.Rect, Float>>()
    var wellenArt by androidx.compose.runtime.mutableStateOf<String?>(null)
    var spiegel by androidx.compose.runtime.mutableFloatStateOf(1f)
    var laeuft by androidx.compose.runtime.mutableStateOf(true)
}

/** Flaechen der Milchglas-Rahmen nicht malen (dort malt der Rahmen das Ganze unscharf) */
internal fun Modifier.ohneMilch(): Modifier = this.drawWithContent {
    val r = MilchFlaechen.rects
    if (r.isEmpty()) { drawContent(); return@drawWithContent }
    val pfad = androidx.compose.ui.graphics.Path()
    r.values.forEach { (rc, rad) -> pfad.addRoundRect(androidx.compose.ui.geometry.RoundRect(rc, CornerRadius(rad))) }
    clipPath(pfad, androidx.compose.ui.graphics.ClipOp.Difference) { this@drawWithContent.drawContent() }
}

@Composable
private fun RahmenCover(o: BOpt, modifier: Modifier, luft: androidx.compose.ui.unit.Dp, rad: androidx.compose.ui.unit.Dp, mitte: Offset) {
    val info = Musik.info
    val bild = info?.bild
    val deut = o.i("coverDeut", 70) / 100f
    val reich = o.i("coverReich", 100) / 100f
    val weich = o.i("coverBlur", 0)
    // Pause: dunkler; nichts geladen: weg
    val staerke by androidx.compose.animation.core.animateFloatAsState(
        if (info == null || bild == null) 0f else if (info.spielt) 1f else 0.5f, androidx.compose.animation.core.tween(500), label = "cover")
    if (staerke < 0.01f && bild == null) return
    androidx.compose.animation.Crossfade(bild, modifier, androidx.compose.animation.core.tween(600), label = "coverWechsel") { b ->
        if (b == null) return@Crossfade
        // zwei Lagen (22.10.): aussen stark weichgezeichnet und immer durchsichtiger, in der Mitte (hinter
        // Play/Pause) scharf - so wird das Cover nach aussen hin weicher UND blasser statt nur blasser
        fun androidx.compose.ui.graphics.drawscope.DrawScope.cover(stopps: Array<Pair<Float, Float>>, weite: Float) {
            val l = luft.toPx()
            val tl = Offset(-l, -l); val gr = Size(size.width + 2 * l, size.height + 2 * l)
            val c = Offset(size.width * mitte.x, size.height * mitte.y)
            // so weit leuchtet es (eher zentral): breite flache Rahmen -> nach der Breite, hohe -> beides
            val r = reich * (0.20f * maxOf(size.width, size.height) + 0.22f * minOf(size.width, size.height)) * weite
            val seite = r * 2.2f
            val pfad = androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(androidx.compose.ui.geometry.Rect(tl, gr), CornerRadius(rad.toPx())))
            }
            clipPath(pfad) {
                val nc = drawContext.canvas
                nc.saveLayer(androidx.compose.ui.geometry.Rect(tl, gr), androidx.compose.ui.graphics.Paint())
                drawImage(b, srcOffset = androidx.compose.ui.unit.IntOffset.Zero,
                          srcSize = androidx.compose.ui.unit.IntSize(b.width, b.height),
                          dstOffset = androidx.compose.ui.unit.IntOffset((c.x - seite / 2f).roundToInt(), (c.y - seite / 2f).roundToInt()),
                          dstSize = androidx.compose.ui.unit.IntSize(seite.roundToInt(), seite.roundToInt()))
                drawRect(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        *stopps.map { (pos, a) -> pos to Color.White.copy(alpha = (a * deut * staerke).coerceIn(0f, 1f)) }.toTypedArray(),
                        center = c, radius = r),
                    tl, gr, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                nc.restore()
            }
        }
        Box(Modifier.fillMaxSize()) {
            // aussen: weich und nach aussen ganz durchsichtig (reicht weiter)
            val aussenBlur = maxOf(weich, 26)
            Canvas(Modifier.fillMaxSize()
                .then(if (android.os.Build.VERSION.SDK_INT >= 31) Modifier.graphicsLayer {
                    renderEffect = BlurEffect(aussenBlur.dp.toPx(), aussenBlur.dp.toPx(), TileMode.Decal) } else Modifier)) {
                cover(arrayOf(0f to 0.9f, 0.4f to 0.6f, 0.7f to 0.25f, 1f to 0f), 1.15f)
            }
            // innen: scharf (bzw. so weich wie eingestellt) nur hinter dem Musik-Baustein, faellt schnell ab
            Canvas(Modifier.fillMaxSize()
                .then(if (weich > 0 && android.os.Build.VERSION.SDK_INT >= 31) Modifier.graphicsLayer {
                    renderEffect = BlurEffect(weich.dp.toPx(), weich.dp.toPx(), TileMode.Decal) } else Modifier)) {
                cover(arrayOf(0f to 1f, 0.3f to 0.75f, 0.55f to 0.2f, 0.75f to 0f, 1f to 0f), 1f)
            }
        }
    }
}


/**
 * Handy-Hintergrundbild fuer den Milchglas-Rahmen. Ab Android 13 gibt Android es fremden Apps nur mit
 * "Zugriff auf alle Dateien" heraus (sonst Fehler) - und ein Live-Hintergrund ist gar kein Bild.
 */
internal object TapetenZugriff {
    /** null = kein Bild (kein Recht / Live-Hintergrund) */
    fun bild(ctx: android.content.Context): android.graphics.drawable.Drawable? = try {
        val wm = android.app.WallpaperManager.getInstance(ctx)
        if (wm.wallpaperInfo != null) null else wm.drawable
    } catch (t: Throwable) { Konsole.schreib("rahmen", "Handy-Hintergrund: " + t.message); null }

    /** Live-Hintergrund (bewegt) - laesst sich nicht als Bild lesen */
    fun live(ctx: android.content.Context) = try { android.app.WallpaperManager.getInstance(ctx).wallpaperInfo != null } catch (t: Throwable) { false }

    /** fehlt das Recht, das Bild zu lesen? (nur wenn diese Version es ueberhaupt anfragen darf) */
    fun brauchtRecht(ctx: android.content.Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        val erklaert = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.contains("android.permission.MANAGE_EXTERNAL_STORAGE") == true
        } catch (t: Throwable) { false }
        return erklaert && !android.os.Environment.isExternalStorageManager()
    }

    fun erlauben(ctx: android.content.Context) {
        try {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            try { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (t2: Throwable) { }
        }
    }
}
