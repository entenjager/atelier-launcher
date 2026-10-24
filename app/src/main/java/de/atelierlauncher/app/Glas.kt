package de.atelierlauncher.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// ============================================================================
//  Liquid Glass: weiche, langsam treibende Farbflaechen als Hintergrund und Glas darueber,
//  das den Hintergrund an den Raendern wirklich bricht (wie eine dicke Glaslinse), leicht milchig ist,
//  einen Glanzsaum hat, der mit dem Neigen des Handys wandert, und sich der Farbe dahinter anpasst.
//  Technik: der Hintergrund ist eine Formel (Shader) - das Glas rechnet dieselbe Formel an der
//  "verbogenen" Stelle aus. So ist die Brechung echt und kostet kaum etwas (kein Bildschirmfoto).
//  Ab Android 13 (Shader). Darunter oder bei anderem Hintergrund: schlichtes Milchglas.
// ============================================================================
object Glas {
    val geht get() = Build.VERSION.SDK_INT >= 33
    /** Hintergrund ist das Glas-Farbfeld -> echte Brechung moeglich (sonst Milchglas) */
    @Volatile var echt = false
    /** Glas-Look an (Karten, Uhr, Alphabet, Suche ...) */
    @Volatile var an = false

    /** Zeit der treibenden Farbflaechen (s) - Hintergrund und Glas lesen dieselbe */
    val zeit = mutableFloatStateOf(0f)

    /** grund, f1..f4 als RGB 0..1 */
    fun farben(dunkel: Boolean): FloatArray = if (dunkel) floatArrayOf(
        0.043f, 0.059f, 0.118f,     // tiefes Nachtblau
        0.23f, 0.42f, 1.00f,        // Blau
        0.61f, 0.36f, 1.00f,        // Violett
        1.00f, 0.36f, 0.54f,        // Pink
        0.18f, 0.83f, 0.78f,        // Tuerkis
    ) else floatArrayOf(
        0.93f, 0.945f, 0.97f,
        0.50f, 0.66f, 1.00f,
        0.79f, 0.65f, 1.00f,
        1.00f, 0.62f, 0.75f,
        0.50f, 0.89f, 0.85f,
    )

    const val GEMEINSAM = """
        uniform float2 aufl;
        uniform float zeit;
        uniform float3 grund;
        uniform float3 f1;
        uniform float3 f2;
        uniform float3 f3;
        uniform float3 f4;
        float3 hg(float2 p) {
            float s = aufl.y;
            float2 u = p / s;
            float br = aufl.x / s;
            float t = zeit * 0.06;
            float2 c1 = float2(br * (0.20 + 0.12 * sin(t * 1.3)), 0.22 + 0.08 * cos(t * 1.1));
            float2 c2 = float2(br * (0.85 + 0.10 * cos(t * 0.9)), 0.40 + 0.10 * sin(t * 1.4 + 1.0));
            float2 c3 = float2(br * (0.28 + 0.15 * cos(t * 1.2 + 2.0)), 0.68 + 0.08 * sin(t * 0.8));
            float2 c4 = float2(br * (0.78 + 0.12 * sin(t * 1.0 + 3.0)), 0.90 + 0.06 * cos(t * 1.3));
            float3 c = grund;
            float2 d = u - c1; c = mix(c, f1, exp(-dot(d, d) / 0.045));
            d = u - c2; c = mix(c, f2, exp(-dot(d, d) / 0.040));
            d = u - c3; c = mix(c, f3, exp(-dot(d, d) / 0.050));
            d = u - c4; c = mix(c, f4, exp(-dot(d, d) / 0.035));
            return c;
        }
    """

    const val HG = GEMEINSAM + """
        half4 main(float2 p) { return half4(half3(hg(p)), 1.0); }
    """

    /** Hintergrund = dein Foto (Bild wird wie "Fuellen" auf den Bildschirm gelegt - gleiche Rechnung wie beim Malen) */
    const val FOTO = """
        uniform float2 aufl;
        uniform shader bild;
        uniform float2 bildGr;
        float3 hg(float2 p) {
            float s = max(aufl.x / bildGr.x, aufl.y / bildGr.y);
            float2 vers = (aufl - bildGr * s) * 0.5;
            float2 q = clamp((p - vers) / s, float2(0.5, 0.5), bildGr - float2(0.5, 0.5));
            return bild.eval(q).rgb;
        }
    """

    /**
     * Das Glas wie bei iOS 26: fast klar, leicht vergroessernd, am Rand eine dicke Linse, die das Bild dahinter
     * biegt (mit feinem Farbsaum), weich verwischt, ein haarfeiner heller Rand rundum (oben links und unten rechts
     * staerker - wandert mit dem Neigen) und ein weicher Schatten darunter.
     */
    const val KOERPER = """
        uniform float4 rahmen;
        uniform float2 ursprung;
        uniform float rad;
        uniform float2 licht;
        uniform float dunkel;
        uniform float staerke;
        uniform float deckung;
        uniform float weich;
        uniform float schattenR;
        float kasten(float2 p, float2 b, float r) {
            float2 q = abs(p) - b + float2(r, r);
            return length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
        }
        half4 main(float2 lp) {
            float2 halb = rahmen.zw * 0.5;
            float2 p = lp - ursprung - halb;
            float r = min(rad, min(halb.x, halb.y));
            float d = kasten(p, halb, r);
            if (d > 0.5) {
                if (schattenR < 0.5) { return half4(0.0); }
                float ds = kasten(p - float2(0.0, schattenR * 0.25), halb, r);
                float sa = (1.0 - smoothstep(-schattenR * 0.4, schattenR * 0.75, ds)) * 0.20 * deckung;
                return half4(0.0, 0.0, 0.0, half(sa));
            }
            float2 g = float2(kasten(p + float2(1.0, 0.0), halb, r) - kasten(p - float2(1.0, 0.0), halb, r),
                              kasten(p + float2(0.0, 1.0), halb, r) - kasten(p - float2(0.0, 1.0), halb, r));
            float2 n = g / max(length(g), 0.0001);
            float zone = min(16.0 * staerke, min(halb.x, halb.y) * 0.5);
            float kante = 1.0 - clamp(-d / zone, 0.0, 1.0);
            float bieg = kante * kante * kante * zone * 0.7;
            float2 sp = rahmen.xy + halb + p * 0.975 - n * bieg;
            float w = weich * (1.0 + kante * 0.3);
            float3 c = hg(sp) * 0.2;
            for (int i = 0; i < 6; i++) {
                float a = float(i) * 1.0472;
                float2 o = float2(cos(a), sin(a));
                c += hg(sp + o * w) * 0.0667;
                c += hg(sp + o * (w * 2.0) + float2(o.y, -o.x) * (w * 0.6)) * 0.0667;
            }
            float ca = kante * kante * kante * 1.5;
            c.r = mix(c.r, hg(sp + n * ca).r, 0.5 * kante);
            c.b = mix(c.b, hg(sp - n * ca).b, 0.5 * kante);
            float lum = dot(c, float3(0.299, 0.587, 0.114));
            c = mix(float3(lum, lum, lum), c, 1.2);
            if (dunkel > 0.5) { c = c * 0.90 + float3(0.05, 0.05, 0.05); } else { c = c * 0.84 + float3(0.17, 0.17, 0.17); }
            float2 ld = normalize(float2(-0.5 + licht.x * 0.7, -0.85 + licht.y * 0.7));
            float l1 = dot(n, ld);
            float saum = 1.0 - smoothstep(0.0, 1.6, -d);
            float band = 1.0 - smoothstep(0.0, 10.0, -d);
            float glanz = pow(abs(l1), 3.0);
            c = c + float3(saum * (0.20 + 0.75 * glanz)) + float3(band * 0.10 * max(l1, 0.0)) + float3(0.04 * kante);
            c = clamp(c, float3(0.0, 0.0, 0.0), float3(1.0, 1.0, 1.0));
            float al = clamp(0.5 - d, 0.0, 1.0) * deckung;
            return half4(half3(c * al), half(al));
        }
    """

    const val GLAS = GEMEINSAM + KOERPER
    const val GLAS_FOTO = FOTO + KOERPER

    /** Shader anlegen (als Any, damit aeltere Android-Versionen die Klasse nie laden) */
    fun shader(code: String): Any? {
        if (!geht) return null
        return try { android.graphics.RuntimeShader(code) } catch (e: Throwable) { null }
    }

    fun gemeinsam(s: Any, b: Float, h: Float, zeit: Float, dunkel: Boolean) {
        if (!geht) return
        val sh = s as android.graphics.RuntimeShader
        try {
            val f = farben(dunkel)
            sh.setFloatUniform("aufl", b, h)
            sh.setFloatUniform("zeit", zeit)
            sh.setFloatUniform("grund", f[0], f[1], f[2])
            sh.setFloatUniform("f1", f[3], f[4], f[5])
            sh.setFloatUniform("f2", f[6], f[7], f[8])
            sh.setFloatUniform("f3", f[9], f[10], f[11])
            sh.setFloatUniform("f4", f[12], f[13], f[14])
        } catch (e: Throwable) { }
    }

    /** Foto-Variante: Bildschirmgroesse + Bild */
    fun fotoSetzen(s: Any, b: Float, h: Float, bild: Any, bw: Float, bh: Float) {
        if (!geht) return
        val sh = s as android.graphics.RuntimeShader
        try {
            sh.setFloatUniform("aufl", b, h)
            sh.setFloatUniform("bildGr", bw, bh)
            sh.setInputShader("bild", bild as android.graphics.Shader)
        } catch (e: Throwable) { }
    }

    /** dasselbe Farbfeld ohne Shader (Sperrbild als Bild, aeltere Geraete) */
    fun hintergrundCpu(c: Canvas, b: Float, h: Float, dunkel: Boolean) {
        val f = farben(dunkel)
        fun farbe(i: Int, a: Float) = ((a * 255).toInt() shl 24) or ((f[i] * 255).toInt() shl 16) or ((f[i + 1] * 255).toInt() shl 8) or (f[i + 2] * 255).toInt()
        c.drawColor(farbe(0, 1f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val orte = listOf(Triple(0.20f * b, 0.22f * h, 3), Triple(0.85f * b, 0.40f * h, 6), Triple(0.28f * b, 0.68f * h, 9), Triple(0.78f * b, 0.90f * h, 12))
        for ((x, y, i) in orte) {
            p.shader = RadialGradient(x, y, h * 0.30f, intArrayOf(farbe(i, 0.95f), farbe(i, 0f)), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, b, h, p)
        }
    }
}

// ---------------------------------------------------------------- dein Foto als Hintergrund
/**
 * Das Bild hinter dem Glas: dein Handy-Hintergrundbild (in den Einstellungen gewaehlt, liegt als Kopie
 * in der App). Wird einmal passend zur Bildschirmgroesse geladen und von Launcher, Live-Hintergrund
 * und Sperrbild gemeinsam benutzt. Android laesst Apps das System-Hintergrundbild nicht zuverlaessig
 * lesen - deshalb waehlt man es einmal aus der Galerie.
 */
object GlasFoto {
    fun datei(ctx: android.content.Context) = java.io.File(EigeneDateien.ordner(ctx), "glas-foto.jpg")
    /** zaehlt hoch, sobald ein (neues) Bild fertig geladen ist -> Compose malt neu */
    val stand = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile private var bild: android.graphics.Bitmap? = null
    @Volatile private var bildVon = 0L
    @Volatile private var laedt = false
    private val sperre = Any()
    private val haupt = android.os.Handler(android.os.Looper.getMainLooper())
    private val malFarbe = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = android.graphics.Matrix()

    /** fuer den Launcher: fertiges Bild sofort, sonst im Hintergrund laden (dann zaehlt stand hoch) */
    fun bild(ctx: android.content.Context): android.graphics.Bitmap? {
        val f = datei(ctx)
        if (!f.exists()) { bild = null; bildVon = 0L; return null }
        val b = bild
        if (b != null && bildVon == f.lastModified()) return b
        if (!laedt) {
            laedt = true
            val app = ctx.applicationContext
            Thread {
                try { sofort(app) } finally { laedt = false; haupt.post { stand.intValue++ } }
            }.start()
        }
        return b
    }

    /** gleich laden (eigener Faden: Live-Hintergrund, Sperrbild) */
    fun sofort(ctx: android.content.Context): android.graphics.Bitmap? {
        val f = datei(ctx)
        if (!f.exists()) return null
        val m = f.lastModified()
        synchronized(sperre) {
            bild?.let { if (bildVon == m) return it }
            val neu = laden(ctx, f) ?: return null
            bild = neu; bildVon = m
            return neu
        }
    }

    private fun laden(ctx: android.content.Context, f: java.io.File): android.graphics.Bitmap? = try {
        val wm = ctx.getSystemService(android.view.WindowManager::class.java)
        val (sb, sh) = if (Build.VERSION.SDK_INT >= 30) wm.maximumWindowMetrics.bounds.let { it.width() to it.height() }
                       else ctx.resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) null else {
            // so klein wie moeglich laden, aber der Bildschirm muss noch ganz bedeckt sein
            val drehung = try {
                when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
                }
            } catch (e: Exception) { 0f }
            val (bw, bh) = if (drehung == 90f || drehung == 270f) o.outHeight to o.outWidth else o.outWidth to o.outHeight
            val reicht = minOf(bw / sb.coerceAtLeast(1).toFloat(), bh / sh.coerceAtLeast(1).toFloat())
            var n = 1
            while (reicht / (n * 2) >= 1f) n *= 2
            val roh = android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply {
                inSampleSize = n; inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 })
            if (roh == null || drehung == 0f) roh
            else android.graphics.Bitmap.createBitmap(roh, 0, 0, roh.width, roh.height,
                android.graphics.Matrix().apply { postRotate(drehung) }, true)
        }
    } catch (e: Throwable) { null }

    /** Bild bildschirmfuellend malen (gleiche Rechnung wie im Glas-Shader) */
    fun malen(c: Canvas, bm: android.graphics.Bitmap, b: Float, h: Float) {
        val s = maxOf(b / bm.width, h / bm.height)
        synchronized(matrix) {
            matrix.reset()
            matrix.setScale(s, s)
            matrix.postTranslate((b - bm.width * s) / 2f, (h - bm.height * s) / 2f)
            c.drawBitmap(bm, matrix, malFarbe)
        }
    }

    /** Glas-Shader braucht das Bild als Shader (mit weicher Abtastung) */
    fun shader(bm: android.graphics.Bitmap): Any? = try {
        android.graphics.BitmapShader(bm, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            if (Build.VERSION.SDK_INT >= 33) filterMode = android.graphics.BitmapShader.FILTER_MODE_LINEAR
        }
    } catch (e: Throwable) { null }
}

// ---------------------------------------------------------------- Compose-Teile

/** Hintergrund "Glas": dein Foto - oder, solange keins gewaehlt ist, weiche Farbflaechen, die langsam treiben */
@Composable
fun GlasHintergrund(modifier: Modifier, dunkel: Boolean, laeuft: Boolean, durchsichtig: Boolean = false) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val z = LocalZustand.current
    // Immer selbst malen: das Fenster laesst bei "glas" die Tapete nicht durchscheinen (sonst: schwarz).
    // Das Foto steht still - das kostet nichts.
    @Suppress("UNUSED_VARIABLE") val unbenutzt = durchsichtig
    val stand = GlasFoto.stand.intValue
    val eigen = z.eigenStand
    val fotoDa = remember(stand, eigen) { GlasFoto.datei(ctx).exists() }
    val foto = remember(stand, eigen) { GlasFoto.bild(ctx) }
    val sh = remember { Glas.shader(Glas.HG) }
    LaunchedEffect(laeuft, fotoDa) {
        if (!laeuft || fotoDa) return@LaunchedEffect          // Foto steht still - kein Bild-Takt
        val start = androidx.compose.runtime.withFrameNanos { it } - (Glas.zeit.floatValue * 1e9f).toLong()
        while (true) {
            androidx.compose.runtime.withFrameNanos { n -> Glas.zeit.floatValue = (n - start) / 1e9f }
            delay(25)          // treibt langsam - 30 Bilder pro Sekunde reichen
        }
    }
    Canvas(modifier) {
        if (foto != null) { drawIntoCanvas { GlasFoto.malen(it.nativeCanvas, foto, size.width, size.height) }; return@Canvas }
        if (fotoDa) { drawRect(Color.Black); return@Canvas }      // Foto laedt gerade (Bruchteil einer Sekunde)
        val t = Glas.zeit.floatValue
        if (sh == null) { drawIntoCanvas { Glas.hintergrundCpu(it.nativeCanvas, size.width, size.height, dunkel) }; return@Canvas }
        Glas.gemeinsam(sh, size.width, size.height, t, dunkel)
        drawRect(ShaderBrush(sh as android.graphics.Shader))
    }
}

/**
 * Glasflaeche hinter einem Element. rad = Ecken, rand = so weit ragt das Glas ueber das Element hinaus
 * (verschiebt nichts am Layout), staerke = wie stark der Rand bricht, deckung = 0..1 (zum Einblenden),
 * weich = wie stark es verwischt, schatten = weicher Schatten darunter.
 */
fun Modifier.glas(rad: Dp = 22.dp, rand: Dp = 0.dp, staerke: Float = 1f, deckung: () -> Float = { 1f },
                  weich: Dp = 3.dp, schatten: Dp = 12.dp, randY: Dp = rand): Modifier = composed {
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ort = remember { FloatArray(2) }
    val echt = Glas.echt
    val stand = GlasFoto.stand.intValue
    val eigen = LocalZustand.current.eigenStand
    val foto = remember(echt, stand, eigen) { if (echt) GlasFoto.bild(ctx) else null }
    val fotoDa = remember(echt, stand, eigen) { echt && GlasFoto.datei(ctx).exists() }
    val bildSh = remember(foto) { foto?.let { GlasFoto.shader(it) } }
    val sh = remember(echt, foto != null) {
        if (!echt) null else if (foto != null) Glas.shader(Glas.GLAS_FOTO) else Glas.shader(Glas.GLAS)
    }
    val ansicht = androidx.compose.ui.platform.LocalView.current
    this
        .onGloballyPositioned { k -> val o = k.positionInWindow(); ort[0] = o.x; ort[1] = o.y }
        .drawBehind {
            val de = deckung().coerceIn(0f, 1f)
            if (de <= 0.01f) return@drawBehind
            val rp = rand.toPx(); val ry = randY.toPx(); val r = rad.toPx()
            val tl = Offset(-rp, -ry); val gr = Size(size.width + 2 * rp, size.height + 2 * ry)
            // Foto gewaehlt, aber noch nicht geladen: kurz Milchglas statt falscher Farbflaechen
            val ohne = sh == null || !Glas.echt || (fotoDa && (foto == null || bildSh == null))
            if (ohne) {
                // Milchglas: helle Tonung + Glanzsaum
                drawRoundRect(Color.White.copy(alpha = (if (p.dunkel) 0.12f else 0.45f) * de), tl, gr, CornerRadius(r, r))
                drawRoundRect(Color.White.copy(alpha = 0.35f * de), tl, gr, CornerRadius(r, r), style = Stroke(1.dp.toPx()))
                return@drawBehind
            }
            val n = Neigung.launcher
            val rb = ansicht.rootView.width.toFloat(); val rh = ansicht.rootView.height.toFloat()
            if (foto != null && bildSh != null) Glas.fotoSetzen(sh!!, rb, rh, bildSh, foto.width.toFloat(), foto.height.toFloat())
            else Glas.gemeinsam(sh!!, rb, rh, Glas.zeit.floatValue, p.dunkel)
            val rs = sh as android.graphics.RuntimeShader
            val sr = schatten.toPx()
            try {
                rs.setFloatUniform("rahmen", ort[0] - rp, ort[1] - ry, gr.width, gr.height)
                rs.setFloatUniform("ursprung", -rp, -ry)
                rs.setFloatUniform("rad", r)
                rs.setFloatUniform("licht", n.x.floatValue, n.y.floatValue)
                rs.setFloatUniform("dunkel", if (p.dunkel) 1f else 0f)
                rs.setFloatUniform("staerke", staerke)
                rs.setFloatUniform("deckung", de)
                rs.setFloatUniform("weich", weich.toPx())
                rs.setFloatUniform("schattenR", sr)
            } catch (e: Throwable) { }
            // etwas groesser malen: Platz fuer den Schatten
            drawRect(ShaderBrush(rs), Offset(tl.x - sr, tl.y - sr), Size(gr.width + 2 * sr, gr.height + 2 * sr))
        }
}

/** Kartenflaeche: im Glas-Look Glas, sonst die bisherige zarte Flaeche */
fun Modifier.karteFlaeche(p: Palette, rad: Dp, alpha: Float): Modifier =
    if (SW.an) this else if (Glas.an) this.glas(rad) else if (Skizze.an) this.skizzeRahmen(rad) else if (Deko.an) this.dekoRahmen(rad) else this.drawBehind { drawRect(p.text.copy(alpha = alpha)) }
