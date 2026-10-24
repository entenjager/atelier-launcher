package de.atelierlauncher.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import java.util.concurrent.atomic.AtomicBoolean

// ============================================================================
//  Konsole fuer Bastler: log(...) aus den Skripten und Fehler landen hier.
//  Anzeige unter Einstellungen -> Bastler -> Konsole. Hoechstens 300 Zeilen.
// ============================================================================
object Konsole {
    private const val MAX = 300
    private val puffer = ArrayDeque<String>()
    private val haupt = Handler(Looper.getMainLooper())
    private val zeitFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.GERMANY)
    private val angestossen = AtomicBoolean(false)

    /** wird bei jeder neuen Zeile hochgezaehlt (nur auf dem Bildschirm-Faden) - zum Beobachten */
    val stand = mutableIntStateOf(0)

    // Drossel pro Quelle: Beginn des Sekunden-Fensters, Zeilen darin, verworfene Zeilen
    private class Drossel { var start = 0L; var anzahl = 0; var verworfen = 0 }
    private val drosseln = HashMap<String, Drossel>()

    fun schreib(quelle: String, text: String) {
        synchronized(puffer) {
            einfuegen(quelle, text)
        }
        anstossen()
    }

    /** wie [schreib], aber hoechstens [proSekunde] Zeilen pro Sekunde von dieser Quelle (Rest verworfen) */
    fun schreibGedrosselt(quelle: String, text: String, proSekunde: Int) {
        synchronized(puffer) {
            val d = drosseln.getOrPut(quelle) { Drossel() }
            val jetzt = System.currentTimeMillis()
            if (jetzt - d.start >= 1_000) {
                if (d.verworfen > 0) einfuegen(quelle, "… " + d.verworfen + " Zeilen ausgelassen (zu viele pro Sekunde)")
                d.start = jetzt; d.anzahl = 0; d.verworfen = 0
            }
            if (d.anzahl >= proSekunde) { d.verworfen++; return }
            d.anzahl++
            einfuegen(quelle, text)
        }
        anstossen()
    }

    /** nur mit gehaltener Sperre aufrufen */
    private fun einfuegen(quelle: String, text: String) {
        val zeile = zeitFormat.format(java.util.Date()) + " " + quelle + ": " + text.take(1_000)
        puffer.addLast(zeile)
        while (puffer.size > MAX) puffer.removeFirst()
    }

    /** Bildschirm Bescheid sagen - hoechstens ein wartender Auftrag gleichzeitig */
    private fun anstossen() {
        if (angestossen.compareAndSet(false, true)) haupt.post { angestossen.set(false); stand.intValue++ }
    }

    /** alle Zeilen, aelteste zuerst */
    fun zeilen(): List<String> = synchronized(puffer) { puffer.toList() }

    fun leeren() {
        synchronized(puffer) { puffer.clear(); drosseln.clear() }
        anstossen()
    }

    /** log(...) fuer ein Skript: Werte mit Leerzeichen verbunden, Objekte/Listen als JSON */
    fun jsLog(quelle: String, proSekunde: Int = 0): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any {
            val text = args.joinToString(" ") { a -> alsText(cx, scope, a) }
            if (proSekunde > 0) schreibGedrosselt(quelle, text, proSekunde) else schreib(quelle, text)
            return Undefined.instance
        }
    }

    private fun alsText(cx: Context, scope: Scriptable, a: Any?): String = when (a) {
        null -> "null"
        is Undefined -> "undefined"
        is NativeArray, is NativeObject -> try {
            val j = NativeJSON.stringify(cx, scope, a, null, null)
            if (j is CharSequence) j.toString() else Context.toString(a)
        } catch (e: Throwable) { Context.toString(a) }
        else -> try { Context.toString(a) } catch (e: Throwable) { "?" }
    }
}
