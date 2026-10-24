package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.View

// ============================================================================
//  Linse: durch den Wassertropfen sieht man den Bildschirm dahinter verzerrt
//  (vergroessert in der Mitte, am Rand zusammengedrueckt) - wie durch echtes Wasser.
//  Dafuer macht der Dienst kurz ein Bildschirmfoto (nur im Speicher, nie gespeichert)
//  und ein kleiner GPU-Shader (AGSL, ab Android 13) bricht es im Tropfen.
// ============================================================================
internal object Linse {
    @Volatile var bild: Bitmap? = null
    @Volatile var bildZeit = 0L
    private var holt = false

    val geht get() = Build.VERSION.SDK_INT >= 33

    /** Bildschirmfoto holen (Android erlaubt das hoechstens ~3x pro Sekunde) */
    fun holen(dn: AccessibilityService?) {
        if (Build.VERSION.SDK_INT < 33 || dn == null || holt) return
        holt = true
        try {
            dn.takeScreenshot(Display.DEFAULT_DISPLAY, dn.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    holt = false
                    try {
                        val hb = r.hardwareBuffer
                        val b = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        hb.close()
                        if (b != null) { bild = b; bildZeit = SystemClock.uptimeMillis() }
                    } catch (e: Exception) { }
                }
                override fun onFailure(code: Int) { holt = false }
            })
        } catch (e: Exception) { holt = false }
    }

    /** Foto passt zu dieser Animation? (nicht von vor Ewigkeiten) */
    fun frisch(ab: Long): Bitmap? = bild?.takeIf { bildZeit >= ab }

    // ------------------------------------------------------------------ Optik (fuer beide Shader gleich)
    // Physik dahinter (siehe Research):
    //  - Wasser n = 1,331 (rot) / 1,333 (gruen) / 1,337 (blau) -> drei leicht verschiedene Brechungen = feiner Farbsaum (im Code etwas verstaerkt, sonst sieht man ihn nicht)
    //  - Blick von oben: Strahl tritt durch die gewoelbte Oberflaeche ein (Snellius), laeuft bis zur Scheibe
    //    -> Mitte vergroessert, zum Rand hin immer staerker verzogen, bei steilen Tropfen sogar umgedreht
    //  - Fresnel (Schlick, 2 % senkrecht): am steilen Rand spiegelt der Tropfen die Umgebung,
    //    oben den hellen Himmel/das Fenster, unten das Dunkle -> heller Bogen oben, dunkler Ring
    //  - Glanzpunkt der Lichtquelle (oben links) scharf, schwacher Zweitreflex von der Rueckseite gegenueber
    //  - gebuendeltes Licht (Kaustik) innen auf der lichtabgewandten Seite
    private const val OPTIK = """
        const float3 BLICK = float3(0.0, 0.0, -1.0);
        float3 brechen(float3 n, float eta) {
            float ci = n.z;
            float k = 1.0 - eta * eta * (1.0 - ci * ci);
            return eta * BLICK + (eta * ci - sqrt(max(k, 0.0))) * n;
        }
        float2 weg(float3 n, float eta, float tief) {
            float3 t = brechen(n, eta);
            return t.xy * (tief / max(-t.z, 0.08));
        }
        float fresnel(float3 n) { return 0.02 + 0.98 * pow(1.0 - clamp(n.z, 0.0, 1.0), 5.0); }
        float umgebung(float3 n, float dk) {
            float3 r = BLICK - 2.0 * dot(BLICK, n) * n;           // gespiegelte Blickrichtung
            float oben = 1.0 - smoothstep(-0.65, 0.25, r.y);              // nach oben = Himmel / Fenster
            return mix(dk > 0.5 ? 0.03 : 0.18, 0.97, oben);
        }
        float glanz(float3 n) {
            float3 l = normalize(float3(-0.45, -0.62, 0.64));
            float3 h = normalize(l + float3(0.0, 0.0, 1.0));
            float d1 = max(dot(n, h), 0.0);
            float3 n2 = float3(-n.x, -n.y, n.z);                  // Reflex von der Rueckseite: gegenueber, weicher
            float d2 = max(dot(n2, h), 0.0);
            return pow(d1, 260.0) * 1.7 + pow(d1, 28.0) * 0.10 + pow(d2, 70.0) * 0.32;
        }
    """

    // Tropfen am Rand: gleiche Umrissform wie in RandWelle.tropfenMalen.
    // Hoehe = Kugelkappe; Kontaktwinkel unten groesser (Wasser sackt nach unten, vorrueckender Winkel),
    // oben kleiner (zurueckweichender Winkel) - wie ein Tropfen an einer senkrechten Scheibe.
    private const val RAND = """
        uniform shader bild;
        uniform float2 versatz;
        uniform float rand;
        uniform float sgn;
        uniform float fy;
        uniform float sack;
        uniform float hw;
        uniform float tiefe;
        uniform float hell;
        uniform float dunkel;
    """ + OPTIK + """
        float breiteBei(float y) {
            float mitte = fy + sack;
            float br = y > mitte ? hw * 1.12 : hw * 0.88;
            float u = (y - mitte) / br;
            float k = max(1.0 - u * u * 0.55, 0.0);
            return tiefe * sqrt(k) * exp(-u * u * 0.9);
        }
        float hoehe(float2 p) {
            float w = breiteBei(p.y);
            if (w < 0.5) return 0.0;
            float s = abs((p.x - rand) * sgn) / w;
            if (s >= 1.0) return 0.0;
            float u = clamp((p.y - fy - sack) / (hw * 1.4), -1.0, 1.0);
            float th = mix(0.62, 1.12, u * 0.5 + 0.5);             // ~36 Grad oben .. ~64 Grad unten
            float rs = 1.0 / sin(th);
            return max(sqrt(rs * rs - s * s) - rs * cos(th), 0.0) * w;
        }
        half4 main(float2 p) {
            float h = hoehe(p);
            if (h <= 0.0) return half4(0.0);
            float hx = (hoehe(p + float2(1.0, 0.0)) - hoehe(p - float2(1.0, 0.0))) * 0.5;
            float hy = (hoehe(p + float2(0.0, 1.0)) - hoehe(p - float2(0.0, 1.0))) * 0.5;
            float3 n = normalize(float3(-hx, -hy, 1.0));
            float tief = (h + tiefe * 0.18) * 1.5;                  // Tropfen + Deckglas bis zum Bild
            float3 farbe = float3(0.0);
            farbe.r = bild.eval(p + versatz + weg(n, 1.0 / 1.325, tief)).r;
            farbe.g = bild.eval(p + versatz + weg(n, 1.0 / 1.333, tief)).g;
            farbe.b = bild.eval(p + versatz + weg(n, 1.0 / 1.349, tief)).b;
            // steile Stellen: Licht geht verloren -> dunkler Ring, Kontaktlinie ganz aussen
            float steil = 1.0 - n.z;
            farbe *= 1.0 - 0.6 * smoothstep(0.22, 0.75, steil);
            float f = fresnel(n);
            farbe = mix(farbe, float3(umgebung(n, dunkel)), f);
            // Kaustik: Licht von oben links sammelt sich innen unten auf der anderen Seite
            float2 mitte = float2(rand + sgn * tiefe * 0.38, fy + sack);
            float2 kp = mitte + float2(0.45, 0.62) * tiefe * 0.42;
            float2 dk = (p - kp) / max(tiefe * 0.32, 1.0);
            farbe += float3(1.0, 0.98, 0.92) * exp(-dot(dk, dk) * 2.2) * 0.22;
            farbe += float3(glanz(n));
            float a = clamp(h, 0.0, 1.0) * hell;                   // 1 px weiche Kante
            return half4(half3(farbe) * half(a), half(a));
        }
    """

    @androidx.annotation.RequiresApi(33)
    class Pinsel(val paint: Paint, val shader: RuntimeShader)

    fun randPinsel(): Any? = pinsel(RAND)

    private fun pinsel(code: String): Any? {
        if (Build.VERSION.SDK_INT < 33) return null
        return try {
            val sh = RuntimeShader(code)
            Pinsel(Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = sh }, sh)
        } catch (e: Exception) { null }
    }

    /** Uniform setzen (Pinsel als Any, damit aeltere Android-Versionen nichts davon laden) */
    fun wert(p: Any, name: String, w: Float) {
        if (Build.VERSION.SDK_INT >= 33) try { (p as Pinsel).shader.setFloatUniform(name, w) } catch (e: Exception) { }
    }

    /** Pfad mit dem Linsen-Pinsel fuellen */
    fun malen(c: android.graphics.Canvas, form: android.graphics.Path, p: Any) {
        if (Build.VERSION.SDK_INT >= 33) c.drawPath(form, (p as Pinsel).paint)
    }

    private val ort = IntArray(2)
    private var letztesBild: Bitmap? = null
    private var letzterShader: BitmapShader? = null

    /** Bild + Lage der Ansicht auf dem Bildschirm in den Shader geben */
    fun binden(pa: Any, v: View, b: Bitmap) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = pa as Pinsel
        if (b !== letztesBild) { letztesBild = b; letzterShader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        try {
            p.shader.setInputShader("bild", letzterShader!!)
            v.getLocationOnScreen(ort)
            p.shader.setFloatUniform("versatz", ort[0].toFloat(), ort[1].toFloat())
        } catch (e: Exception) { }
    }
}
