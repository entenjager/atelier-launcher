package de.atelierlauncher.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================
//  Klack beim Alphabet: ein kurzes, trockenes Klicken (wie eine Holztaste), selbst erzeugt.
//  Laeuft ueber die Medien-Lautstaerke (die Systemtoene sind bei vielen Handys leise oder aus).
// ============================================================================
object Klack {
    /** die Toene: normales Klack und die "anderen" (fuer einzelne Buchstaben, im Baukasten einstellbar) */
    val ARTEN = listOf("hoch" to "Hoch", "tief" to "Tief", "glocke" to "Glöckchen", "doppel" to "Doppel-Klick")
    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private val bereit = java.util.Collections.synchronizedSet(HashSet<Int>())

    fun vorbereiten(ctx: Context) {
        if (pool != null) return
        try {
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            ).build()
            p.setOnLoadCompleteListener { _, id, status -> if (status == 0) bereit.add(id) }
            for (art in listOf("klack") + ARTEN.map { it.first }) {
                val datei = File(ctx.cacheDir, "ton_$art.wav")
                if (!datei.exists()) FileOutputStream(datei).use { it.write(wav(art)) }
                ids[art] = p.load(datei.path, 1)
            }
            pool = p
        } catch (e: Exception) { }
    }

    fun spielen(ctx: Context, art: String = "klack") {
        if (pool == null) vorbereiten(ctx)
        val id = ids[art] ?: ids["klack"] ?: return
        if (id in bereit) try { pool?.play(id, 0.55f, 0.55f, 1, 0, 1f) } catch (e: Exception) { }
    }

    /** 16 Bit mono 44,1 kHz, alles selbst erzeugt */
    private fun wav(art: String): ByteArray {
        val rate = 44100
        val ms = when (art) { "glocke" -> 260; "doppel" -> 90; "tief" -> 45; "hoch" -> 30; else -> 30 }
        val n = rate * ms / 1000
        val r = Random(3)
        fun klick(t: Double, f: Double) = (r.nextDouble() * 2 - 1) * exp(-t / 0.0012) * 0.6 +
            sin(2 * PI * f * t) * exp(-t / 0.006) * 0.7 + sin(2 * PI * f * 2 * t) * exp(-t / 0.003) * 0.25
        val pcm = ShortArray(n) { i ->
            val t = i.toDouble() / rate
            val w = when (art) {
                // heller, kuerzerer Klick
                "hoch" -> klick(t, 3200.0) * 0.85
                // dumpfer Holz-Ton
                "tief" -> (r.nextDouble() * 2 - 1) * exp(-t / 0.001) * 0.4 + sin(2 * PI * 520 * t) * exp(-t / 0.012) * 0.9
                // kleines Glöckchen: Grundton + unharmonische Obertoene, klingt aus
                "glocke" -> (sin(2 * PI * 2093 * t) * 0.6 + sin(2 * PI * 2093 * 2.76 * t) * 0.25 * exp(-t / 0.03) +
                             sin(2 * PI * 2093 * 5.4 * t) * 0.1 * exp(-t / 0.015)) * exp(-t / 0.09)
                // zwei Klicks kurz hintereinander
                "doppel" -> klick(t, 1700.0) + (if (t > 0.045) klick(t - 0.045, 2000.0) else 0.0)
                else -> klick(t, 1700.0)
            }
            (w * 0.8 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
        val daten = n * 2
        val b = ByteBuffer.allocate(44 + daten).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + daten); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(daten)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }
}
