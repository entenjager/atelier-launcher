package de.atelierlauncher.app

import android.content.Context as AContext
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.WrapFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

// ============================================================================
//  Eigener Code (JavaScript, Rhino-Engine) fuer Bastler.
//  - Laeuft auf einem eigenen Faden, nie auf dem Bildschirm-Faden.
//  - Kein Zugriff auf Java/Android-Interna: ClassShutter sperrt ALLE Java-Klassen,
//    die WrapFactory reicht nie ein Java-Objekt an das Skript durch.
//  - Zeitlimit (auch fuer Netz-Abrufe), Speicher-Grenze, Aufruftiefe begrenzt.
//  - Waechter-Hund: haengt ein Skript trotzdem (z. B. boese Regex), wird der Faden
//    aufgegeben und ein neuer genommen - der Launcher bleibt bedienbar.
// ============================================================================

/** keine einzige Java-Klasse ist fuer Skripte sichtbar */
private val KeinJava = ClassShutter { false }

/** Java-Objekte werden nie ins Skript gereicht (statt dessen ein leeres Objekt) */
private object KeineJavaObjekte : WrapFactory() {
    init { isJavaPrimitiveWrap = false }
    override fun wrapAsJavaObject(cx: Context, scope: Scriptable, javaObject: Any?, staticType: Class<*>?): Scriptable =
        leer(cx, scope)
    override fun wrapJavaClass(cx: Context, scope: Scriptable, javaClass: Class<*>?): Scriptable =
        leer(cx, scope)
    private fun leer(cx: Context, scope: Scriptable): Scriptable = cx.newObject(ScriptableObject.getTopLevelScope(scope))
}

/** Rhino-Kontexte im Interpreter-Modus (Android kann keinen Java-Bytecode erzeugen) + alle Grenzen */
private object Waechter : ContextFactory() {
    override fun makeContext(): Context {
        val c = super.makeContext()
        c.optimizationLevel = -1
        c.languageVersion = Context.VERSION_ES6
        c.instructionObserverThreshold = 5_000
        c.maximumInterpreterStackDepth = 1_000
        c.setClassShutter(KeinJava)
        c.wrapFactory = KeineJavaObjekte
        return c
    }

    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        val ende = cx.getThreadLocal("ende") as? Long ?: return
        if (System.currentTimeMillis() > ende) throw Error("Zeitlimit: Skript läuft zu lange (Endlosschleife?)")
        // Speicher: ab 70 % belegt abbrechen, bevor der ganze Launcher abstuerzt
        val r = Runtime.getRuntime()
        if (r.totalMemory() - r.freeMemory() > r.maxMemory() * 0.7) throw Error("Zu viel Speicher verbraucht")
    }
}

/** Eine laufende Skript-Umgebung mit eigenem Faden und dauerhaftem Speicher (globale Variablen bleiben) */
class SkriptLauf(private val name: String) {
    @Volatile private var faden: ExecutorService = neuerFaden()
    @Volatile private var scope: ScriptableObject? = null
    @Volatile private var aufgegeben = 0
    private val hund = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "skript-$name-hund").apply { isDaemon = true } }

    private fun neuerFaden(): ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "skript-$name").apply { isDaemon = true; priority = Thread.MIN_PRIORITY + 1 }
    }

    /** wird gemeldet, wenn ein Skript-Faden aufgegeben werden musste */
    @Volatile var beiHaenger: ((String) -> Unit)? = null

    /**
     * Im Skript-Faden ausfuehren. Laeuft die Arbeit laenger als [hoechstMs], wird der Faden
     * aufgegeben (er laeuft im Hintergrund leise zu Ende) und ein frischer Faden uebernimmt.
     */
    fun spaeter(hoechstMs: Long = 20_000, arbeit: () -> Unit) {
        if (aufgegeben >= 3) return          // 3x festgehangen: gesperrt bis der Code geaendert wird
        val f = faden
        val auftrag: Future<*> = try {
            f.submit { try { arbeit() } catch (e: Throwable) { } }
        } catch (e: Exception) { return }
        try { hund.schedule({
            if (!auftrag.isDone && faden === f) {
                auftrag.cancel(true)                 // unterbricht z. B. Netz-Wartezeiten
                faden = neuerFaden()
                scope = null
                aufgegeben++
                f.shutdownNow()
                beiHaenger?.invoke(if (aufgegeben >= 3) "Skript hing 3× fest und ist gesperrt – Code ändern und speichern"
                                   else "Skript hängt fest und wurde abgebrochen (z. B. sehr aufwendige Regex)")
            }
        }, hoechstMs, TimeUnit.MILLISECONDS) } catch (e: Exception) { }    // nach ende(): kein Absturz
    }

    /** Skript-Faden sofort benutzen (nur aus [spaeter] heraus aufrufen) */
    fun <T> mitKontext(zeitMs: Long, block: (Context, ScriptableObject) -> T): T {
        val cx = Waechter.enterContext()
        try {
            cx.putThreadLocal("ende", System.currentTimeMillis() + zeitMs)
            val s = scope ?: cx.initSafeStandardObjects().also { scope = it }
            return block(cx, s)
        } finally { Context.exit() }
    }

    /** neue, leere Umgebung (z. B. nach Aendern des Codes) */
    fun zuruecksetzen() { spaeter { scope = null } }

    /** wie zuruecksetzen, aber sofort (nur aus dem Skript-Faden heraus aufrufen) */
    fun zuruecksetzenJetzt() { scope = null }

    /** nach Code-Aenderung wieder erlauben */
    fun freigeben() { aufgegeben = 0 }

    fun ende() { faden.shutdownNow(); hund.shutdownNow() }
}

/** Hilfen zum Bauen der Schnittstellen */
object JS {
    fun fn(f: (Array<Any?>) -> Any?): BaseFunction = object : BaseFunction() {
        override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>): Any =
            f(args) ?: Undefined.instance
    }

    fun objekt(cx: Context, scope: Scriptable, werte: Map<String, Any?>): NativeObject {
        val o = cx.newObject(scope) as NativeObject
        werte.forEach { (k, v) ->
            if (v is BaseFunction) ScriptRuntimeHilfe.einbinden(v, scope)
            ScriptableObject.putProperty(o, k, v)
        }
        return o
    }

    fun liste(cx: Context, scope: Scriptable, l: List<Any?>): Scriptable = cx.newArray(scope, l.toTypedArray())

    fun text(a: Any?) = if (a == null || a is Undefined) "" else Context.toString(a)
    fun zahl(a: Any?, sonst: Double = 0.0): Double = if (a == null || a is Undefined) sonst else Context.toNumber(a).let { if (it.isNaN()) sonst else it }
    fun funktion(a: Any?): Function? = a as? Function

    /** Restzeit des laufenden Skripts in ms (fuer Netz-Abrufe); wirft, wenn sie schon abgelaufen ist */
    fun restZeit(): Long {
        val cx = Context.getCurrentContext() ?: return 10_000
        val ende = cx.getThreadLocal("ende") as? Long ?: return 10_000
        val rest = ende - System.currentTimeMillis()
        if (rest <= 0) throw Error("Zeitlimit: Skript läuft zu lange")
        return rest
    }

    /** Funktion als globale Variable ins Skript legen (mit Prototyp, damit .call/.bind gehen) */
    fun global(scope: ScriptableObject, name: String, f: BaseFunction) {
        ScriptRuntimeHilfe.einbinden(f, scope)
        scope.put(name, scope, f)
    }

    /** Code nur uebersetzen (nicht ausfuehren): Fehlertext mit Zeile oder null, wenn alles passt */
    fun pruefen(code: String): String? {
        val cx = Waechter.enterContext()
        return try {
            cx.compileString(code, "Code", 1, null)
            null
        } catch (e: Throwable) {
            fehler(e)
        } finally { Context.exit() }
    }

    /**
     * Code einmal zum Testen ausfuehren: eigener Wegwerf-Faden, wartet hoechstens [zeitMs] + Puffer.
     * Blockiert - nicht auf dem Bildschirm-Faden aufrufen!
     */
    fun testLauf(zeitMs: Long = 5_000, block: (Context, ScriptableObject) -> TestErgebnis): TestErgebnis {
        val l = SkriptLauf("test")
        val ergebnis = java.util.concurrent.LinkedBlockingQueue<TestErgebnis>()
        l.beiHaenger = { t -> ergebnis.offer(TestErgebnis(false, t)) }
        l.spaeter(zeitMs + 1_000) {
            val e = try { l.mitKontext(zeitMs) { cx, s -> block(cx, s) } } catch (e: Throwable) { TestErgebnis(false, fehler(e)) }
            ergebnis.offer(e)
        }
        val r = try { ergebnis.poll(zeitMs + 3_000, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
        l.ende()
        return r ?: TestErgebnis(false, "Skript hängt fest")
    }

    /** Fehlermeldung mit Zeilennummer, verstaendlich */
    fun fehler(e: Throwable): String = when (e) {
        is RhinoException -> "Zeile ${e.lineNumber()}: ${e.details()}"
        is OutOfMemoryError -> "Zu viel Speicher verbraucht"
        is StackOverflowError -> "Zu tiefe Rekursion"
        else -> e.message ?: e.javaClass.simpleName
    }
}

/** eigene Funktionen bekommen Prototyp + Eltern-Scope -> .call/.apply/.bind gehen */
private object ScriptRuntimeHilfe {
    fun einbinden(f: BaseFunction, scope: Scriptable) {
        val top = ScriptableObject.getTopLevelScope(scope)
        if (f.parentScope == null) f.parentScope = top
        if (f.prototype == null) f.prototype = ScriptableObject.getFunctionPrototype(top)
    }
}

/** kleiner Schluessel-Wert-Speicher fuer Skripte (handy.speichern / handy.laden) */
object SkriptSpeicher {
    fun schreiben(ctx: AContext, k: String, v: String) =
        ctx.getSharedPreferences("skript_daten", AContext.MODE_PRIVATE).edit().putString(k, v.take(100_000)).apply()
    fun lesen(ctx: AContext, k: String): String? =
        ctx.getSharedPreferences("skript_daten", AContext.MODE_PRIVATE).getString(k, null)
    fun loeschen(ctx: AContext, k: String) =
        ctx.getSharedPreferences("skript_daten", AContext.MODE_PRIVATE).edit().remove(k).apply()
}

/** Ergebnis von "Testen" im Code-Editor */
class TestErgebnis(val ok: Boolean, val text: String)
