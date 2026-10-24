package de.atelierlauncher.app

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Uhr aus Liquid Glass: die Ziffern sind klares Glas. Innen sieht man den Hintergrund (leicht vergroessert,
//  auf Wunsch weichgezeichnet), am Rand wirkt das Glas wie eine Linse und verbiegt ihn. Dazu Lichtkante
//  oben links, etwas Schatten innen unten rechts und ein zarter Schatten unter dem Glas.
//  Technik: der Hintergrund wird hinter der Uhr ein zweites Mal gemalt (genau deckungsgleich) und durch
//  einen eigenen Shader (AGSL, ab Android 13) geschickt. Video / Handy-Hintergrund: dort nur das Glas selbst.
// ============================================================================

/** die Ziffern der Uhr - normal oder aus Glas (Uhr anpassen → Material der Uhr) */
@Composable
internal fun UhrZiffern(text: String, farbe: Color, groesse: TextUnit, dicke: FontWeight, zeilenhoehe: TextUnit,
                        stil: TextStyle, modifier: Modifier = Modifier) {
    val u = LocalZustand.current.thema.uhr
    if (u.schrift == Schriften.TROPFEN)
        TropfZiffern(text, stil.merge(TextStyle(fontSize = groesse, fontWeight = dicke, lineHeight = zeilenhoehe, shadow = null)), farbe, modifier)
    else if (u.material == "glas" && Build.VERSION.SDK_INT >= 33)
        GlasZiffern(text, stil.merge(TextStyle(fontSize = groesse, fontWeight = dicke, lineHeight = zeilenhoehe, shadow = null)),
                    u.glasBlur.toFloat(), u.glasBrechung / 100f, modifier)
    else Text(text, color = farbe, fontSize = groesse, fontWeight = dicke, lineHeight = zeilenhoehe, style = stil, modifier = modifier,
              maxLines = 1, softWrap = false)      // die Uhr bricht nie um
}

/**
 * Linse: maske = Form der Ziffern (weiss), content = Hintergrund dahinter.
 * h() = weichgezeichnete Maske (wie eine Hoehe), ihr Gefaelle zeigt am Rand nach innen.
 */
private const val GLAS_AGSL = """
uniform shader content;
uniform shader maske;
uniform float staerke;
uniform float weite;

float hoehe(float2 p) {
    float s = 0.0;
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            s += float(maske.eval(p + float2(float(i), float(j)) * weite * 0.5).a);
        }
    }
    return s / 25.0;
}

half4 main(float2 p) {
    float m = float(maske.eval(p).a);
    if (m < 0.004) { return half4(0.0, 0.0, 0.0, 0.0); }
    float e = 1.5;
    float gx = hoehe(p + float2(e, 0.0)) - hoehe(p - float2(e, 0.0));
    float gy = hoehe(p + float2(0.0, e)) - hoehe(p - float2(0.0, e));
    float2 q = p + float2(gx, gy) * staerke * 120.0;
    float4 c = float4(content.eval(q));
    float licht = clamp((gx + gy) * 4.0, 0.0, 1.0);
    float dunkel = clamp(-(gx + gy) * 3.0, 0.0, 1.0);
    float3 f = c.rgb * 1.05 + float3(0.025 + licht * 0.75 - dunkel * 0.16);
    f = clamp(f, float3(0.0), float3(1.0));
    return half4(float4(f * m, m));
}
"""

@Composable
private fun GlasZiffern(text: String, stil: TextStyle, blurDp: Float, brechung: Float, modifier: Modifier) {
    if (Build.VERSION.SDK_INT < 33) return
    val z = LocalZustand.current
    val d = LocalDensity.current
    val view = LocalView.current
    val messer = rememberTextMeasurer()
    val layout = remember(text, stil) { messer.measure(text, stil) }
    val rand = with(d) { 24.dp.roundToPx() }                 // Platz um die Ziffern (die Linse greift etwas nach aussen)
    val w = layout.size.width.coerceAtLeast(1); val h = layout.size.height.coerceAtLeast(1)
    val bw = w + 2 * rand; val bh = h + 2 * rand
    // Form der Ziffern als Bild (fuer den Shader)
    val maske = remember(layout) {
        ImageBitmap(bw, bh).also { bm ->
            CanvasDrawScope().draw(d, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bm), Size(bw.toFloat(), bh.toFloat())) {
                drawText(layout, color = Color.White, topLeft = Offset(rand.toFloat(), rand.toFloat()))
            }
        }
    }
    // Shader sicher anlegen: klappt er auf einem Handy nicht, gibt es nur Glas ohne Linse (kein Absturz)
    val shader = remember { try { RuntimeShader(GLAS_AGSL) } catch (t: Throwable) { Konsole.schreib("uhr", "Glas-Shader: " + t.message); null } }
    val maskenShader = remember(maske) { android.graphics.BitmapShader(maske.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
    var lage by remember { mutableStateOf(Rect.Zero) }
    val art = z.thema.hintergrund
    // diese Hintergruende kann die Uhr "durchsehen" (Video / Handy-Hintergrund / Code laufen woanders)
    val durch = art !in setOf("video", "system", "lottie", "code")
    Box(
        modifier
            // groesser zeichnen als die Ziffern, aber nur so viel Platz belegen wie normal
            .layout { m, _ -> val pl = m.measure(Constraints.fixed(bw, bh)); layout(w, h) { pl.place(-rand, -rand) } }
            .onGloballyPositioned { lage = it.boundsInRoot() },
    ) {
        // zarter Schatten unter dem Glas
        Canvas(Modifier.fillMaxSize().graphicsLayer {
            translationY = 3.dp.toPx(); renderEffect = BlurEffect(7.dp.toPx(), 7.dp.toPx())
        }) { drawText(layout, color = Color.Black.copy(alpha = 0.22f), topLeft = Offset(rand.toFloat(), rand.toFloat())) }
        // das Glas: Hintergrund dahinter -> weichzeichnen -> Linse
        Box(Modifier.fillMaxSize().graphicsLayer {
            renderEffect = try {
                val sh = shader ?: throw IllegalStateException("kein Shader")
                sh.setInputShader("maske", maskenShader)
                sh.setFloatUniform("staerke", brechung)
                sh.setFloatUniform("weite", 7.dp.toPx())
                val linse = RenderEffect.createRuntimeShaderEffect(sh, "content")
                val blur = blurDp.dp.toPx()
                (if (blur >= 0.5f) RenderEffect.createChainEffect(linse, RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP))
                 else linse).asComposeRenderEffect()
            } catch (t: Throwable) { null }
            clip = true
        }) {
            val s = if (lage.width > 0f) lage.width / bw else 1f       // Uhr evtl. verkleinert (Baustein) - ausgleichen
            if (shader != null && durch && lage.width > 0f && view.width > 0) {
                Box(Modifier.layout { m, _ ->
                    val pl = m.measure(Constraints.fixed((view.width / s).roundToInt().coerceAtLeast(1), (view.height / s).roundToInt().coerceAtLeast(1)))
                    layout(bw, bh) { pl.place((-lage.left / s).roundToInt(), (-lage.top / s).roundToInt()) }   // feste Groesse (auch beim Vorab-Messen)
                }) { androidx.compose.runtime.CompositionLocalProvider(LocalKopie provides true) { Hintergrund(art, Modifier.fillMaxSize(), laeuft = MilchFlaechen.laeuft) } }
            } else Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f)))
        }
        // feine Kante, damit die Ziffern auch auf ganz ruhigem Grund (Schwarz) zu sehen sind
        Canvas(Modifier.fillMaxSize()) {
            drawText(layout, color = Color.White.copy(alpha = if (art == "schwarz") 0.42f else 0.26f),
                     topLeft = Offset(rand.toFloat(), rand.toFloat()), drawStyle = Stroke(1.dp.toPx()))
        }
    }
}

/** Material + Regler (Uhr anpassen und Uhr-Baustein → ✎) */
@Composable
internal fun UhrMaterialWahl(u: UhrStil, setze: (UhrStil) -> Unit) {
    if (u.schrift == Schriften.TROPFEN) return                        // Tropfen-Schrift hat kein Material (Glas passt nicht dazu)
    Wahl("Material der Uhr", listOf("" to "Normal", "glas" to "Liquid Glass"), u.material,
         if (u.material == "glas") (if (Build.VERSION.SDK_INT >= 33) "Klares Glas – am Rand verbiegt es den Hintergrund wie eine Linse"
                                    else "Braucht Android 13 oder neuer – hier bleibt die Uhr normal") else null) { setze(u.copy(material = it)) }
    if (u.material == "glas") {
        RgRegler("Weichzeichnen", u.glasBlur.toFloat(), 0f..24f, if (u.glasBlur == 0) "aus" else "${u.glasBlur}", {}) { setze(u.copy(glasBlur = it.roundToInt())) }
        RgRegler("Brechung", u.glasBrechung.toFloat(), 0f..200f, "${u.glasBrechung} %", {}) { setze(u.copy(glasBrechung = (it / 5f).roundToInt() * 5)) }
    }
}
