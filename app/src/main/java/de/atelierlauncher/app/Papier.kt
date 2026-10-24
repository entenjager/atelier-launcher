package de.atelierlauncher.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.mutableStateOf
import kotlin.random.Random

// ============================================================================
//  Papier-Struktur (Test): ganz feines, neutrales Korn - wie mattes Papier, ohne Flecken.
//  Eine kleine Kachel wird einmal erzeugt und ueber den ganzen Bildschirm gekachelt (kostet fast nichts).
//  Liegt ueber allem, faengt keine Beruehrungen ab. In den Einstellungen sofort wieder aus.
// ============================================================================
object Papier {
    val an = mutableStateOf(false)
    private var geladen = false

    fun laden(ctx: Context) {
        if (geladen) return
        geladen = true
        an.value = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("papier", false)
    }

    fun setzen(ctx: Context, wert: Boolean) {
        an.value = wert
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putBoolean("papier", wert).apply()
    }

    fun istAn(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("papier", false)

    /** die Kachel: 384 x 384 px, nahtlos */
    val kachel: Bitmap by lazy { machen(384) }

    private fun machen(n: Int): Bitmap {
        val r = Random(7)
        val px = IntArray(n * n)
        // nur ein sehr feines, neutrales Korn (keine Flecken, keine Fasern, kein Gelbstich) - sauber, nicht schmutzig
        for (i in px.indices) {
            val v = (r.nextFloat() + r.nextFloat()) / 2f - 0.5f
            val a = (kotlin.math.abs(v) * 28f).toInt().coerceIn(0, 10)
            px[i] = if (v < 0f) Color.argb(a, 140, 140, 140) else Color.argb(a, 255, 255, 255)
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private val stift by lazy { Paint().apply { shader = BitmapShader(kachel, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) } }

    /** auf einen normalen Android-Canvas (Sperrbildschirm) */
    fun malen(c: Canvas) {
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), stift)
    }
}
