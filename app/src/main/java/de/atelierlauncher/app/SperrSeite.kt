package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// ============================================================================
//  (wie auf dem Startbildschirm bei "#" nach links), von links nach rechts wischen = wieder weg.
//  Technik wie bei den Randgesten: die Bedienungshilfe legt Ebenen ueber den Sperrbildschirm.
// ============================================================================
object SperrSeite {
    private var dienst: AccessibilityService? = null
    private val streifen = HashMap<Boolean, View>()          // Schluessel: links?
    private var seite: SeitenAnsicht? = null
    val offen get() = seite != null
    /** Sperr-Seite gerade zu sehen (Compose-State): Musik-Ring, Takt, Netz-Anzeigen laufen dann wie im Launcher */
    val sichtbar = androidx.compose.runtime.mutableStateOf(false)
    private fun sichtbarSetzen(an: Boolean) {
        if (sichtbar.value == an) return
        sichtbar.value = an
        val d = dienst ?: return
        if (an) { try { Musik.start(d) } catch (e: Exception) { } }
        else if (!Sichtbar.an) { Beat.pause(); try { Musik.stop() } catch (e: Exception) { } }
    }
    private var lauf: ValueAnimator? = null
    private val haupt = Handler(Looper.getMainLooper())

    fun an(c: Context) = false
    fun zuerst(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("sperrSeiteZuerst", true)
    /** in diesem Sperr-Durchgang schon weggewischt -> nicht wieder von selbst zeigen (bis Bildschirm aus / entsperrt) */
    private var weggewischt = false
    /** beim Bildschirm-Aus unsichtbar vorbereitet -> beim Einschalten sofort da, ohne Aufblitzen */
    private var bereit: SeitenAnsicht? = null
    // solange die Seite da ist: alle 40 ms schauen, ob entsperrt wurde -> sofort weg
    private val wache = object : Runnable {
        override fun run() {
            if (seite == null) return
            val d = dienst ?: return
            val km = d.getSystemService(android.app.KeyguardManager::class.java)
            val pm = d.getSystemService(android.os.PowerManager::class.java)
            if (km?.isKeyguardLocked != true || pm?.isInteractive != true || SperrBild.eingabe) pruefen()
            else haupt.postDelayed(this, 40)
        }
    }

    fun starten(d: AccessibilityService) { dienst = d; pruefen() }

    /**
     * "Inhalt geaendert"/"Fokus"-Meldungen braucht der Dienst nur auf dem Sperrbildschirm (PIN-Eingabe erkennen).
     * Entsperrt schickt jede App (TikTok, Videos, ...) davon hunderte pro Sekunde - das kostete das System
     * spuerbar Rechenzeit. Also nur bei gesperrtem Handy abonnieren.
     */
    private var feinAn: Boolean? = null
    private fun feineEreignisse(d: AccessibilityService, an: Boolean) {
        if (feinAn == an) return
        try {
            val info = d.serviceInfo ?: return
            val basis = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                android.view.accessibility.AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.eventTypes = if (an) basis or android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED else basis
            d.serviceInfo = info
            feinAn = an
        } catch (e: Exception) { }
    }

    /** viele Fenster-Ereignisse kurz hintereinander (z. B. beim Entsperren) -> nur einmal pruefen */
    private val bald = Runnable { pruefen() }
    fun pruefenBald() { haupt.removeCallbacks(bald); haupt.postDelayed(bald, 30) }
    fun stoppen() { streifenEntfernen(); seiteWeg(); bereitWeg(); dienst = null }

    /** Streifen nur, solange gesperrt + Bildschirm an (und keine PIN-Eingabe) */
    fun pruefen() {
        if (Looper.myLooper() != Looper.getMainLooper()) { haupt.post { pruefen() }; return }
        val d = dienst ?: return
        val km = d.getSystemService(android.app.KeyguardManager::class.java)
        val pm = d.getSystemService(android.os.PowerManager::class.java)
        val gesperrt = km?.isKeyguardLocked == true && pm?.isInteractive == true
        UntenWisch.sperre(km?.isKeyguardLocked == true)
        feineEreignisse(d, km?.isKeyguardLocked == true)
        val zuerst = an(d) && zuerst(d)
        if (gesperrt && an(d) && !SperrBild.eingabe) {
            if (zuerst && !weggewischt && seite == null && !hauptDa && !gegenDa) {
                val s = seiteZeigen(d, 0f); s.versatz = 0f
            }
            // Rand-Streifen nur, solange die Seite NICHT offen ist: sonst liegen sie ueber der offenen Seite und
            // fangen das Wegwischen an einem Rand ab (dann ging es nur von einer Seite)
            if (seite != null && !ziehtGerade) streifenVerstecken()
            else { streifenZeigen(d, links = false); streifenZeigen(d, links = true) }
            if (seite != null) { haupt.removeCallbacks(wache); haupt.postDelayed(wache, 40) }
        } else {
            streifenVerstecken(); seiteWeg(); hauptDa = false; gegenDa = false
            val aus = pm?.isInteractive != true
            if (aus || km?.isKeyguardLocked != true) weggewischt = false
            // Bildschirm aus: Seite unsichtbar bereitlegen; entsperrt: Speicher frei
            if (zuerst && aus) vorbereiten(d) else if (km?.isKeyguardLocked != true) bereitWeg()
        }
    }

    private fun vorbereiten(d: AccessibilityService) {
        if (seite != null || bereit != null) return
        val wm = d.getSystemService(WindowManager::class.java) ?: return
        val v = SeitenAnsicht(d)
        v.visibility = View.INVISIBLE
        try { wm.addView(v, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false).apply { title = "Sperrseite" }); bereit = v }
        catch (e: Exception) { }
    }

    private fun bereitWeg() {
        val v = bereit ?: return
        bereit = null
        v.ende()
        try { dienst?.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) { }
    }

    private fun lp(b: Int, h: Int, beruehrbar: Boolean) = WindowManager.LayoutParams(
        b, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or (if (beruehrbar) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        windowAnimations = 0
    }

    // ------------------------------------------------------------ unsichtbare Streifen am linken und rechten Rand
    // Die Streifen-Fenster werden nur EINMAL angelegt und dann nur noch ein-/ausgeblendet.
    // Anlegen/Entfernen bei jedem Sperren/Entsperren hat jedes Mal ~150 ms den Launcher blockiert (gemessen).
    private val versteckt = HashSet<Boolean>()
    private fun streifenZeigen(d: AccessibilityService, links: Boolean) {
        streifen[links]?.let { v ->
            if (versteckt.remove(links)) {
                val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { d.getSystemService(WindowManager::class.java)?.updateViewLayout(v, lp) } catch (e: Exception) { }
            }
            return
        }
        val wm = d.getSystemService(WindowManager::class.java) ?: return
        val dichte = d.resources.displayMetrics.density
        val v = object : View(d) {
            // jeder Finger in diesem Streifen bekommt eine Rolle (1 = zieht die Seite, 2 = zieht vom anderen Rand)
            val rollen = android.util.SparseIntArray()
            fun rohX(e: MotionEvent, i: Int) = if (Build.VERSION.SDK_INT >= 29) e.getRawX(i) else e.getX(i) + (e.rawX - e.x)
            override fun onTouchEvent(e: MotionEvent): Boolean {
                val breite = d.resources.displayMetrics.widthPixels.toFloat()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) rollen.clear()
                        val i = e.actionIndex; val rx = rohX(e, i)
                        // Rand des Fingers: beim ersten der Streifen selbst, danach nach Bildschirmhaelfte
                        val amLinken = if (e.actionMasked == MotionEvent.ACTION_DOWN) links else rx < breite / 2f
                        val rolle = fingerRunter(amLinken, rx)
                        if (rolle != 0) rollen.put(e.getPointerId(i), rolle)
                    }
                    MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                        val rolle = rollen.get(e.getPointerId(i), 0)
                        if (rolle != 0) fingerZug(rolle, rohX(e, i))
                    }
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        val id = e.getPointerId(e.actionIndex)
                        val rolle = rollen.get(id, 0); rollen.delete(id)
                        if (rolle != 0) fingerHoch(rolle)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        for (k in 0 until rollen.size()) fingerHoch(rollen.valueAt(k))
                        rollen.clear()
                    }
                }
                return true
            }
        }
        // nur die mittleren 60 % der Hoehe: Samsungs Schnellstarter unten in den Ecken bleiben frei
        val p = lp((22 * dichte).toInt(), (d.resources.displayMetrics.heightPixels * 0.60f).toInt(), true).apply {
            gravity = (if (links) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
            title = "Sperrseite Streifen " + (if (links) "links" else "rechts")
        }
        try { wm.addView(v, p); streifen[links] = v } catch (e: Exception) { }
    }

    /** entsperrt: Streifen nur unsichtbar und durchlaessig machen (Fenster bleibt) */
    private fun streifenVerstecken() {
        val wm = dienst?.getSystemService(WindowManager::class.java) ?: return
        for ((links, v) in streifen) {
            if (!versteckt.add(links)) continue
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            // Streifen malen nichts - nur durchlaessig machen (GONE wirft jedes Mal die Grafik weg = Hänger)
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { wm.updateViewLayout(v, lp) } catch (e: Exception) { }
        }
    }

    private fun streifenEntfernen() {
        val wm = dienst?.getSystemService(WindowManager::class.java)
        for (v in streifen.values) try { wm?.removeView(v) } catch (e: Exception) { }
        streifen.clear(); versteckt.clear()
    }

    // ------------------------------------------------------------ die Seite
    private fun seiteZeigen(d: AccessibilityService, start: Float): SeitenAnsicht {
        seite?.let { return it }
        val wm = d.getSystemService(WindowManager::class.java)
        // schon vorbereitet (unsichtbar)? Dann nur sichtbar und anfassbar machen - geht sofort
        bereit?.let { b ->
            bereit = null
            b.versatz = start
            b.visibility = View.VISIBLE
            (b.layoutParams as? WindowManager.LayoutParams)?.let { l ->
                l.flags = l.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm?.updateViewLayout(b, l) } catch (e: Exception) { }
            }
            seite = b; sichtbarSetzen(true)
            haupt.removeCallbacks(wache); haupt.postDelayed(wache, 40)
            Randgesten.neuMessen()
            return b
        }
        val v = SeitenAnsicht(d)
        v.versatz = start
        try { wm?.addView(v, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true).apply { title = "Sperrseite" }) } catch (e: Exception) { }
        seite = v; sichtbarSetzen(true)
        haupt.removeCallbacks(wache); haupt.postDelayed(wache, 40)
        Randgesten.neuMessen()
        return v
    }

    private fun seiteWeg() {
        lauf?.cancel(); lauf = null
        val v = seite ?: return
        seite = null; sichtbarSetzen(false)
        v.ende()
        try { dienst?.getSystemService(WindowManager::class.java)?.removeView(v) } catch (e: Exception) { }
        pruefenBald()      // Rand-Streifen wieder an (zum Reinziehen), falls noch gesperrt
        Randgesten.neuMessen()
    }

    /** unten links lange gedrueckt: Launcher mit dem Sperr-Seiten-Editor oeffnen - erscheint nach dem Entsperren */
    fun gestalten() {
        val d = dienst ?: return
        schliessen()
        try {
            d.startActivity(android.content.Intent(d, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("oeffne", "rasterSperre"))
        } catch (e: Exception) { }
    }

    fun schliessen() { seite?.let { fahren(it, it.width.toFloat().coerceAtLeast(1f)) } }


    private fun anhalten() { lauf?.cancel() }

    // ------------------------------------------------------------ Ziehen von den Raendern
    // Bis zu zwei Finger gleichzeitig: einer pro Rand. Egal in welchem Fenster der zweite Finger landet
    // (Streifen oder schon die Seite) - er laeuft hier zusammen.
    private var hauptLinks = false
    private var hauptDa = false; private var hauptX0 = 0f
    private var gegenDa = false; private var gegenX0 = 0f
    val ziehtGerade get() = hauptDa || gegenDa

    /** neuer Finger an einem Rand: 1 = zieht die Seite, 2 = zieht vom anderen Rand, 0 = egal */
    private var zuletzt = 0L
    internal fun fingerRunter(links: Boolean, x: Float): Int {
        val jetzt = android.os.SystemClock.uptimeMillis()
        // Sicherheit: hing ein Finger fest (Ende nie gemeldet), neu anfangen
        if (jetzt - zuletzt > 4000) { hauptDa = false; gegenDa = false }
        zuletzt = jetzt
        if (!hauptDa && !gegenDa) {
            hauptLinks = links; hauptDa = true; hauptX0 = x; lauf?.cancel()
            return 1
        }
        if (links != hauptLinks && !gegenDa) { gegenDa = true; gegenX0 = x; lauf?.cancel(); return 2 }
        return 0
    }

    internal fun fingerZug(rolle: Int, x: Float) {
        zuletzt = android.os.SystemClock.uptimeMillis()
        val d = dienst ?: return
        val b = d.resources.displayMetrics.widthPixels.toFloat()
        val dichte = d.resources.displayMetrics.density
        val vz = if (hauptLinks) -1f else 1f
        if (rolle == 1) {
            val zug = (if (hauptLinks) x - hauptX0 else hauptX0 - x).coerceAtLeast(0f)
            if (zug > 8f * dichte || seite != null) seiteZeigen(d, vz * b).versatz = vz * (b - zug).coerceIn(0f, b)
        } else {
            val gz = (if (hauptLinks) gegenX0 - x else x - gegenX0).coerceAtLeast(0f)
            if (gz > 8f * dichte || seite != null) seiteZeigen(d, vz * b).gegen = gz.coerceAtMost(b)
        }
    }

    internal fun fingerHoch(rolle: Int) {
        if (rolle == 1) hauptDa = false else gegenDa = false
        if (hauptDa || gegenDa) return            // der andere Finger zieht noch
        val s = seite ?: return
        val b = (if (s.width > 0) s.width else s.resources.displayMetrics.widthPixels).toFloat()
        // beide Raender zusammen: ab einem Viertel einrasten, sonst zurueck
        val drin = (b - kotlin.math.abs(s.versatz)) + s.gegen
        fahren(s, if (drin > b * 0.25f) 0f else (if (hauptLinks) -b else b))
    }

    /** Uebergaenge zum Ausprobieren */
    val ARTEN = listOf("karte" to "Karte", "schieben" to "Schieben", "welle" to "Welle", "swiss" to "Swiss-Leiste", "raster" to "Swiss-Linien", "geld" to "Banknote", "glas" to "Glasscheibe", "skizze" to "Gezeichnet", "riss" to "Kintsugi",
                       "tiefe" to "Tür", "zoom" to "Zoom", "jalousie" to "Jalousie", "kreis" to "Kreis")
    fun art(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("sperrUebergang", "karte") ?: "karte"
    fun artSetzen(c: Context, a: String) {
        c.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("sperrUebergang", a).apply()
        seite?.art = a
    }

    private fun zeit(art: String, auf: Boolean): Pair<Long, android.animation.TimeInterpolator> = when (art) {
        "schieben" -> if (auf) 300L to DecelerateInterpolator(2f) else 240L to DecelerateInterpolator(1.6f)
        "welle"    -> if (auf) 560L to DecelerateInterpolator(1.4f) else 420L to DecelerateInterpolator(1.2f)
        "glas"     -> if (auf) 420L to DecelerateInterpolator(2.0f) else 300L to DecelerateInterpolator(1.5f)
        "skizze"   -> if (auf) 440L to DecelerateInterpolator(1.8f) else 320L to DecelerateInterpolator(1.4f)
        "riss"     -> if (auf) 460L to DecelerateInterpolator(1.8f) else 340L to DecelerateInterpolator(1.4f)
        "geld"     -> if (auf) 420L to DecelerateInterpolator(1.8f) else 320L to DecelerateInterpolator(1.4f)
        "raster"   -> if (auf) 440L to DecelerateInterpolator(1.6f) else 360L to DecelerateInterpolator(1.3f)
        "swiss"    -> if (auf) 180L to DecelerateInterpolator(3f) else 150L to android.view.animation.AccelerateInterpolator(1.5f)
        "tiefe"    -> if (auf) 460L to DecelerateInterpolator(2.2f) else 320L to DecelerateInterpolator(1.4f)
        "zoom"     -> if (auf) 420L to android.view.animation.OvershootInterpolator(1.1f) else 280L to DecelerateInterpolator(1.6f)
        "jalousie" -> if (auf) 520L to DecelerateInterpolator(1.3f) else 380L to DecelerateInterpolator(1.2f)
        "kreis"    -> if (auf) 480L to DecelerateInterpolator(1.8f) else 340L to DecelerateInterpolator(1.4f)
        else       -> if (auf) 380L to android.view.animation.OvershootInterpolator(0.7f) else 260L to DecelerateInterpolator(1.6f)
    }

    /** Wegwischen mit zwei Fingern (von beiden Raendern): beide Kanten fahren aufeinander zu (weg) oder zurueck */
    private fun fahrenBeide(s: SeitenAnsicht, weg: Boolean) {
        lauf?.cancel()
        val b = (if (s.width > 0) s.width else s.resources.displayMetrics.widthPixels).toFloat()
        val v0 = s.versatz; val z0 = s.zu
        val summe = (kotlin.math.abs(v0) + z0).coerceAtLeast(1f)
        val vorz = if (v0 < 0f) -1f else 1f
        val vT = if (weg) vorz * b * (kotlin.math.abs(v0) / summe) else 0f
        val zT = if (weg) b * (z0 / summe) else 0f
        val (dauer, kurve) = zeit(s.art, !weg)
        lauf = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dauer; interpolator = kurve
            addUpdateListener {
                val t = it.animatedValue as Float
                s.versatz = v0 + (vT - v0) * t
                s.zu = z0 + (zT - z0) * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (weg && seite === s) { weggewischt = true; seiteWeg() }
                    else if (!weg) { s.zu = 0f; s.versatz = 0f; streifenVerstecken() }
                }
            })
            start()
        }
    }

    /** Seite weich an eine Stelle fahren (0 = offen, Breite = weg) */
    private fun fahren(s: SeitenAnsicht, ziel: Float) {
        lauf?.cancel()
        val (dauer, kurve) = zeit(s.art, ziel == 0f)
        val g0 = s.gegen
        lauf = ValueAnimator.ofFloat(s.versatz, ziel).apply {
            duration = dauer; interpolator = kurve
            addUpdateListener {
                s.versatz = it.animatedValue as Float
                // rausfahren: die Seite vom anderen Rand faehrt mit zurueck; reinfahren: bleibt, die Kanten treffen sich
                if (ziel != 0f && g0 > 0f) s.gegen = g0 * (1f - it.animatedFraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (ziel == 0f) { s.gegen = 0f; streifenVerstecken() }     // Seite offen: Raender gehoeren ihr
                    if (ziel != 0f && seite === s) { weggewischt = true; seiteWeg() }
                }
            })
            start()
        }
    }

    private class NeueAufgabe(basis: Context) : android.content.ContextWrapper(basis) {
        override fun startActivity(i: android.content.Intent) {
            try { super.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
        override fun startActivity(i: android.content.Intent, o: android.os.Bundle?) {
            try { super.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK), o) } catch (e: Exception) { }
        }
    }

    /** Lebenszyklus fuer Compose in einem Fenster ohne Activity */
    private class Lebenszyklus : LifecycleOwner, SavedStateRegistryOwner {
        private val reg = LifecycleRegistry(this)
        private val gespeichert = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = gespeichert.savedStateRegistry
        fun los() { gespeichert.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        fun ende() { reg.currentState = Lifecycle.State.DESTROYED }
    }

    private class SeitenAnsicht(ctx: Context) : FrameLayout(ctx) {
        // Der Uebergang haengt am Weg "versatz" (folgt also dem Finger); WIE er aussieht, bestimmt "art"
        var versatz = 0f
            set(w) { field = w; anwenden() }
        var art = SperrSeite.art(ctx)
            set(w) { field = w; karte.modus = w; anwenden() }
        private val dp = ctx.resources.displayMetrics.density
        private val schleierAnsicht = View(ctx).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        private val karte = Rahmen(ctx).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, (v as Rahmen).ecke) }
            }
            cameraDistance = 9000f * dp
            modus = art
        }
        /** zweiter Finger vom anderen Rand: so weit (px) ist die Seite von dort schon drin */
        var gegen = 0f
            set(w) { field = w; anwenden() }
        /** Wegwischen mit zwei Fingern: so weit (px) ist die Seite vom ANDEREN Rand her schon weg */
        var zu = 0f
            set(w) { field = w; anwenden() }
        private fun anwenden() {
            val b = (if (width > 0) width else resources.displayMetrics.widthPixels).toFloat()
            val h = (if (height > 0) height else resources.displayMetrics.heightPixels).toFloat()
            // beide Raender zusammen: wie viel fehlt noch, bis die Seite ganz drin ist
            // zwei Finger beim Wegwischen: Uebergaenge mit Ausschneiden schneiden von beiden Seiten,
            // die anderen (Karte, Schieben, Tuer, Zoom) nehmen einfach den ganzen Weg zusammen
            val clip = art in setOf("welle", "swiss", "raster", "geld", "glas", "skizze", "riss", "jalousie", "kreis")
            val zuX = zu.coerceIn(0f, b)
            val vE = if (zuX > 0f && !clip) (if (this.versatz < 0f) -1f else 1f) * (kotlin.math.abs(this.versatz) + zuX).coerceAtMost(b) else this.versatz
            val zuA = b - kotlin.math.abs(vE).coerceIn(0f, b); val zuB = gegen.coerceIn(0f, b)
            val rest = (b - zuA - zuB).coerceAtLeast(0f)
            val vorz = if (vE < 0f) -1f else 1f
            // Uebergaenge mit Verschieben: kommen von der Seite, die gerade weiter drin ist
            val versatz = (if (zuB > zuA) -vorz else vorz) * rest
            val f = if (clip && zuX > 0f) ((kotlin.math.abs(vE) + zuX) / b).coerceIn(0f, 1f)
                    else (rest / b).coerceIn(0f, 1f)          // 0 = ganz offen, 1 = ganz weg
            val weich = f * f * (3 - 2 * f)
            val r = karte
            // erst alles zuruecksetzen, dann pro Art setzen
            r.translationX = 0f; r.scaleX = 1f; r.scaleY = 1f; r.alpha = 1f; r.rotationY = 0f
            r.pivotX = b / 2; r.pivotY = h / 2
            r.ecke = 0f; r.elevation = 0f; r.f = f; r.v = vE; r.g = zuB; r.b = b; r.zu = if (clip) zuX else 0f
            var schleier = 0f
            when (art) {
                "schieben" -> { r.translationX = versatz; r.elevation = 10f * dp; schleier = 0.30f * (1f - weich) }
                "welle", "swiss", "raster", "geld", "glas", "skizze", "riss", "jalousie", "kreis" -> {
                    schleier = when (art) { "swiss", "raster" -> 0f; else -> 0.35f * (1f - weich) }
                }
                "tiefe" -> {
                    // Tuer: Scharnier am Rand, zu dem sie verschwindet; die andere Seite klappt nach hinten weg
                    val rechts = versatz >= 0f
                    r.pivotX = if (rechts) b else 0f
                    r.rotationY = (if (rechts) -1f else 1f) * 80f * weich
                    r.translationX = versatz * 0.12f
                    r.alpha = 1f - 0.6f * weich
                    r.ecke = 22f * dp * kotlin.math.min(1f, f * 5f)
                    r.elevation = 16f * dp
                    schleier = 0.45f * (1f - weich)
                }
                "zoom" -> {
                    // Seite taucht aus der Tiefe auf / versinkt wieder
                    r.scaleX = 1.25f - 0.25f * (1f - weich) - 0.55f * weich; r.scaleY = r.scaleX
                    r.alpha = 1f - weich
                    r.ecke = 34f * dp * kotlin.math.min(1f, f * 3f)
                    r.translationX = versatz * 0.08f
                    schleier = 0.5f * (1f - weich)
                }
                else -> {   // "karte"
                    r.translationX = versatz
                    r.scaleX = 1f - 0.07f * weich; r.scaleY = r.scaleX
                    r.alpha = 1f - 0.25f * weich
                    r.ecke = 30f * dp * kotlin.math.min(1f, f * 4f)
                    r.elevation = 24f * dp
                    schleier = 0.45f * (1f - weich)
                }
            }
            r.invalidateOutline(); r.invalidate()
            schleierAnsicht.alpha = schleier
        }

        /** Rahmen um den Inhalt: schneidet bei Welle/Swiss/Jalousie/Kreis die sichtbare Flaeche zu und malt die Kante */
        private class Rahmen(c: Context) : FrameLayout(c) {
            var modus = "karte"
            var ecke = 0f
            var f = 0f; var v = 0f; var g = 0f; var b = 1f
            /** zwei Finger beim Wegwischen: vom anderen Rand her schon weg (px) */
            var zu = 0f
            private val dp = c.resources.displayMetrics.density
            private val pfad = android.graphics.Path()
            private val kante = android.graphics.Path()
            private val stift = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            private val fuell = Paint(Paint.ANTI_ALIAS_FLAG)
            private val thema = try {
                org.json.JSONObject(c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("thema", "{}") ?: "{}")
            } catch (e: Exception) { org.json.JSONObject() }
            private val wellenF = wellenFarbe(thema.optInt("welle", 0), Hell.dunkel(c))
            private val akzentF = thema.optInt("akzent", 0).let { a ->
                if (a != 0) a else if (Build.VERSION.SDK_INT >= 31) c.getColor(android.R.color.system_accent1_400) else 0xFFFF3B30.toInt()
            }

            private val zufall = java.util.Random()
            private var saat = zufall.nextInt()
            private val tinte = if (Hell.dunkel(c)) Color.WHITE else 0xFF111111.toInt()
            private val dunkelT = Hell.dunkel(c)
            private val geld by lazy { GeldZeichner(dp) }
            private val glasP = Paint(Paint.ANTI_ALIAS_FLAG)

            @Suppress("DEPRECATION")
            private fun ausschneiden(cv: Canvas, p: android.graphics.Path) {
                if (Build.VERSION.SDK_INT >= 26) cv.clipOutPath(p) else cv.clipPath(p, android.graphics.Region.Op.DIFFERENCE)
            }

            private val pfad2 = android.graphics.Path()
            private val skSchraff = android.graphics.Path()
            private var skSchl = Long.MIN_VALUE
            // "gerade gezeichnet": seit wann die Kante unterwegs ist (0 = Seite ruht)
            private var skStart = 0L
            private val skMass = android.graphics.PathMeasure()
            private val skTeil = android.graphics.Path()
            private val skPos = FloatArray(2)
            /** eine gezogene Kante (bleibt stehen, wo sie gezeichnet wurde) */
            private class SkStrich(val x: Float, val rechts: Boolean, val t0: Long, val kante: android.graphics.Path, val schraff: android.graphics.Path) {
                var weg = 0L      // seit wann wegradiert wird (0 = bleibt)
            }
            private val skStriche = ArrayList<SkStrich>()
            private val SK_ZEIT = 300f        // so lange dauert Zeichnen bzw. Radieren von oben nach unten
            private val SK_STRICH = 120f      // ein einzelner Schraffur-Strich
            private val SK_ABSTAND = 18f      // alle 18 dp Wischweg eine neue Kante
            private fun skNeu(x: Float, rechts: Boolean, h: Float, jetzt: Long): SkStrich {
                val sa = zufall.nextInt()
                val k = android.graphics.Path(); Skizze.linie(k, x, -6f * dp, x, h + 6f * dp, sa, dp)
                val sch = android.graphics.Path(); Skizze.schraffur(sch, 0f, 0f, 16f * dp, h, sa + 1, dp, 6f)
                return SkStrich(x, rechts, jetzt, k, sch)
            }
            private val kante2 = android.graphics.Path()
            // Kintsugi
            private var rsGold: Paint? = null
            private val rsFuge = Deko.fugeStift()
            private val rsGlanz = Deko.glanzStift()
            private val rsHaar = android.graphics.Path()

            override fun dispatchDraw(cv: Canvas) {
                val schneiden = modus == "welle" || modus == "swiss" || modus == "raster" || modus == "geld" || modus == "glas" || modus == "skizze" || modus == "riss" || modus == "jalousie" || modus == "kreis"
                if (!schneiden || f <= 0.0005f) { if (f <= 0.0005f) { saat = zufall.nextInt(); skStart = 0L; skStriche.clear() }; super.dispatchDraw(cv); return }
                if (f >= 0.9995f) { skStart = 0L; skStriche.clear(); return }
                val w = width.toFloat(); val h = height.toFloat()
                val rechts = v >= 0f                       // sichtbarer Teil liegt rechts von der Kante
                val zuG = g.coerceIn(0f, w)                // zweiter Finger: so weit ist die Seite vom anderen Rand drin
                // die Gegenseite als eigener "versatz" (kommt vom anderen Rand)
                val vG = if (rechts) -(w - zuG) else (w - zuG)
                if (modus == "raster" || modus == "skizze") { raster(cv, w, h, rechts, zuG); return }
                pfad.reset(); kante.reset()
                form(pfad, kante, v, w, h)
                if (zu > 0.5f) {
                    // zwei Finger beim Wegwischen: sichtbar bleibt nur, was zwischen beiden Kanten liegt
                    val vZ = if (rechts) -zu else zu
                    pfad2.reset(); kante2.reset()
                    form(pfad2, kante2, vZ, w, h)
                    pfad.op(pfad2, android.graphics.Path.Op.INTERSECT)
                    cv.save(); cv.clipPath(pfad); super.dispatchDraw(cv); cv.restore()
                    kanteMalen(cv, kante, v, w, h)
                    kanteMalen(cv, kante2, vZ, w, h)
                    return
                }
                if (zuG > 0.5f) {
                    pfad2.reset(); kante2.reset()
                    form(pfad2, kante2, vG, w, h)
                    pfad.op(pfad2, android.graphics.Path.Op.UNION)
                }
                cv.save(); cv.clipPath(pfad); super.dispatchDraw(cv); cv.restore()
                kanteMalen(cv, kante, v, w, h)
                if (zuG > 0.5f) kanteMalen(cv, kante2, vG, w, h)
            }

            private val pufferA = SwissRaster.Puffer()
            private val pufferB = SwissRaster.Puffer()
            private val bKarte = HashMap<Long, FloatArray>()
            private val beide = ArrayList<FloatArray>()

            /** Swiss-Linien: verschwundene Felder ausschneiden; bei zwei Fingern nur, was von beiden Seiten noch fehlt */
            private fun raster(cv: Canvas, w: Float, h: Float, rechts: Boolean, zuG: Float) {
                val wegA = SwissRaster.felder(w, h, (kotlin.math.abs(v) / w).coerceIn(0f, 1f), dp, rechts, saat, pufferA)
                val weg: List<FloatArray> = if (zuG > 0.5f) {
                    // nur Felder, die von BEIDEN Seiten aus noch fehlen; Linien frisch an beiden Kanten
                    val wegB = SwissRaster.felder(w, h, 1f - zuG / w, dp, !rechts, saat, pufferB)
                    fun schl(fd: FloatArray) = (kotlin.math.round(fd[0] / 4f).toLong() shl 24) + kotlin.math.round(fd[1] / 4f).toLong()
                    bKarte.clear()
                    for (fd in wegB) bKarte[schl(fd)] = fd
                    beide.clear()
                    for (fd in wegA) { val o = bKarte[schl(fd)] ?: continue; fd[6] = maxOf(fd[6], o[6]); beide.add(fd) }
                    beide
                } else if (zu > 0.5f) {
                    // zwei Finger beim Wegwischen: was von EINER der beiden Seiten weg ist, ist weg
                    val wegZ = SwissRaster.felder(w, h, (zu / w).coerceIn(0f, 1f), dp, !rechts, saat, pufferB)
                    fun schl(fd: FloatArray) = (kotlin.math.round(fd[0] / 4f).toLong() shl 24) + kotlin.math.round(fd[1] / 4f).toLong()
                    bKarte.clear()
                    beide.clear()
                    for (fd in wegA) { bKarte[schl(fd)] = fd; beide.add(fd) }
                    // Felder der anderen Seite markieren (Spalte + 1000): ihre Kanten liegen gespiegelt
                    for (fd in wegZ) { val o = bKarte[schl(fd)]; if (o == null) { fd[4] += 1000f; beide.add(fd) } else o[6] = maxOf(o[6], fd[6]) }
                    beide
                } else wegA
                pfad.reset()
                for (fd in weg) pfad.addRect(fd[0], fd[1], fd[2], fd[3], android.graphics.Path.Direction.CW)
                cv.save()
                ausschneiden(cv, pfad)
                super.dispatchDraw(cv); cv.restore()
                if (modus == "skizze") { skizzeRaster(cv, weg, rechts, h); return }
                // Felder rund um die Kante verschwinden zufaellig; ihre Umrisse bleiben kurz als Linien
                stift.color = tinte; stift.strokeWidth = 1.5f * dp; stift.strokeCap = Paint.Cap.SQUARE
                for (fd in weg) {
                    if (fd[6] <= 0.01f) continue
                    stift.alpha = (255 * fd[6] * (1f - f * 0.5f)).toInt()
                    val andere = fd[4] >= 1000f
                    if (andere) fd[4] -= 1000f
                    SwissRaster.linien(fd, if (andere) !rechts else rechts, saat) { a, b2, c2, d -> cv.drawLine(a, b2, c2, d, stift) }
                    if (andere) fd[4] += 1000f
                }
                stift.strokeCap = Paint.Cap.ROUND
            }

            // ---- Gezeichnet: genau wie die Randgeste "Gezeichnete Kanten" - jede Rasterkante wird in 150 ms
            // mit Kuli gezogen; je weiter gewischt, desto mehr werden die alten wieder wegradiert (vom
            // Linienanfang her). Vierecke mit allen 4 Seiten haben Papier. Zittern fest je Kante.
            private val rsZeit = HashMap<Long, Long>()
            private val rsPfade = HashMap<Long, android.graphics.Path>()
            private var rsSaat = 0
            private var rsH = 0f
            private val rsPapier = Paint().apply { color = Skizze.PAPIER }
            private val rsStift = Skizze.stift(dp, 1.6f)
            private val rsMass = android.graphics.PathMeasure()
            private val rsTeil = android.graphics.Path()
            private fun rsSchl(i: Int, j: Int, r: Int) = (i.toLong() shl 32) or ((j.toLong() and 0x7FFFFFFF) shl 1) or r.toLong()

            private fun skizzeRaster(cv: Canvas, weg: List<FloatArray>, rechts: Boolean, h: Float) {
                Skizze.nachfaerben(rsStift); Skizze.papierFaerben(rsPapier)
                if (rsSaat != saat || rsH != h) { rsSaat = saat; rsH = h; rsZeit.clear(); rsPfade.clear() }
                val jetzt = android.os.SystemClock.uptimeMillis()
                var laeuft = false
                fun fertig(k: Long): Boolean { val t0 = rsZeit[k] ?: return false; return jetzt - t0 >= 150L }
                fun of(a: Int, b: Int, r: Int) = SwissRaster.offen(a, b, r, saat)
                // Papier in vollen Vierecken (alle 4 Seiten gezogen), verschwindet mit dem Radieren
                for (fd in weg) {
                    if (fd[6] <= 0.05f) continue
                    val i = fd[4].toInt() % 1000; val j = fd[5].toInt()
                    val ki = if (fd[4] >= 1000f) i + 1000 else i
                    if (!of(i, j, 0) && !of(i, j + 1, 0) && !of(i, j, 1) && !of(i + 1, j, 1) &&
                        fertig(rsSchl(ki, j, 0)) && fertig(rsSchl(ki, j + 1, 0)) && fertig(rsSchl(ki, j, 1)) && fertig(rsSchl(ki + 1, j, 1))) {
                        rsPapier.alpha = (255 * (fd[6] * 1.6f).coerceAtMost(1f)).toInt()
                        cv.drawRect(fd[0], fd[1], fd[2], fd[3], rsPapier)
                    }
                }
                fun ziehe(k: Long, ax: Float, ay: Float, bx: Float, by: Float, radiert: Float) {
                    val pf = rsPfade.getOrPut(k) {
                        android.graphics.Path().also { Skizze.linie(it, ax, ay, bx, by, (k xor saat.toLong()).hashCode(), dp, 0.7f) }
                    }
                    val t0 = rsZeit.getOrPut(k) { jetzt }
                    val p = ((jetzt - t0) / 150f).coerceIn(0f, 1f)
                    if (p < 1f) laeuft = true
                    val von = 1f - (1f - radiert) * (1f - radiert)
                    val bis = 1f - (1f - p) * (1f - p)
                    if (bis <= von) return
                    if (von <= 0f && p >= 1f) { cv.drawPath(pf, rsStift); return }
                    rsMass.setPath(pf, false)
                    val len = rsMass.length
                    rsTeil.reset(); rsMass.getSegment(len * von, len * bis, rsTeil, true)
                    cv.drawPath(rsTeil, rsStift)
                }
                rsStift.alpha = 235
                for (fd in weg) {
                    if (fd[6] <= 0.01f) continue
                    val andere = fd[4] >= 1000f        // Feld von der anderen Seite (zwei Finger)
                    val i = fd[4].toInt() % 1000; val j = fd[5].toInt()
                    val r = 1f - fd[6]                 // je weiter die Kante weg ist, desto mehr radiert
                    val re = if (andere) !rechts else rechts
                    val innenX = if (re) fd[0] else fd[2]; val aussenX = if (re) fd[2] else fd[0]
                    val ki = if (andere) i + 1000 else i      // eigene Linien fuer die andere Seite
                    if (!of(i, j, 0)) ziehe(rsSchl(ki, j, 0), fd[0], fd[1], fd[2], fd[1], r)
                    if (!of(i, j + 1, 0)) ziehe(rsSchl(ki, j + 1, 0), fd[0], fd[3], fd[2], fd[3], r)
                    if (!of(i, j, 1)) ziehe(rsSchl(ki, j, 1), innenX, fd[1], innenX, fd[3], r)
                    if (!of(i + 1, j, 1)) ziehe(rsSchl(ki + 1, j, 1), aussenX, fd[1], aussenX, fd[3], r)
                }
                if (laeuft) postInvalidateOnAnimation()
            }

            /** sichtbare Flaeche einer Seite (vv > 0: kommt von rechts, < 0: von links) */
            private fun form(pfad: android.graphics.Path, kante: android.graphics.Path, vv: Float, w: Float, h: Float) {
                val fs = (kotlin.math.abs(vv) / w).coerceIn(0f, 1f)
                if (fs >= 0.9995f) return
                val rechts = vv >= 0f
                val e = if (rechts) vv else w + vv
                val bogen = kotlin.math.sin(Math.PI * fs).toFloat()   // 0 an den Enden, 1 in der Mitte
                when (modus) {
                    "riss" -> {
                        // Kintsugi: zackige Bruchkante, fest pro Wisch (Zacken in der Mitte der Bewegung am groessten)
                        val z = Skizze.Zufall(saat)
                        val schritt = 20f * dp
                        val amp = (4f + 12f * bogen) * dp
                        pfad.moveTo(if (rechts) w else 0f, 0f)
                        var y = 0f; var erst = true
                        while (y <= h + schritt) {
                            val xx = e + z.z(-1f, 1f) * amp
                            pfad.lineTo(xx, y)
                            if (erst) { kante.moveTo(xx, y); erst = false } else kante.lineTo(xx, y)
                            y += schritt * z.z(0.6f, 1.3f)
                        }
                        pfad.lineTo(if (rechts) w else 0f, h + schritt); pfad.close()
                    }
                    "welle" -> {
                        val amp = 26f * dp * bogen
                        val ph = vv / (55f * dp)
                        val schritt = 5f * dp
                        fun x(y: Float): Float {
                            val s = 0.7f * kotlin.math.sin(y / (130f * dp) * 6.283f + ph) + 0.3f * kotlin.math.sin(y / (57f * dp) * 6.283f - ph * 1.7f)
                            return e + (if (rechts) 1f else -1f) * amp * (s - 1f)   // Wellenberge laufen voraus
                        }
                        pfad.moveTo(if (rechts) w else 0f, 0f)
                        var y = 0f
                        while (y <= h + schritt) {
                            val xx = x(y)
                            pfad.lineTo(xx, y)
                            if (y == 0f) kante.moveTo(xx, y) else kante.lineTo(xx, y)
                            y += schritt
                        }
                        pfad.lineTo(if (rechts) w else 0f, h + schritt); pfad.close()
                    }
                    "skizze" -> {
                        // Kante wie von Hand gerissen/gezogen: wackelt fest pro Wisch, in der Mitte der Bewegung am meisten
                        val z = Skizze.Zufall(saat)
                        val p1 = z.z(0f, 6.28f); val p2 = z.z(0f, 6.28f); val f1 = z.z(1.5f, 3f)
                        val schritt = 6f * dp
                        fun x(y: Float): Float {
                            val u = y / h
                            return e + (2.4f * kotlin.math.sin(u * 6.283f * f1 + p1) + 1.1f * kotlin.math.sin(u * 6.283f * 9f + p2)) * dp * (0.5f + bogen)
                        }
                        pfad.moveTo(if (rechts) w else 0f, 0f)
                        var y = 0f
                        while (y <= h + schritt) {
                            val xx = x(y)
                            pfad.lineTo(xx, y)
                            if (y == 0f) kante.moveTo(xx, y) else kante.lineTo(xx, y)
                            y += schritt
                        }
                        pfad.lineTo(if (rechts) w else 0f, h + schritt); pfad.close()
                    }
                    "swiss", "geld", "glas" -> {
                        if (rechts) pfad.addRect(e, 0f, w, h, android.graphics.Path.Direction.CW)
                        else pfad.addRect(0f, 0f, e, h, android.graphics.Path.Direction.CW)
                    }
                    "jalousie" -> {
                        val n = 12
                        val streifen = h / n
                        for (i in 0 until n) {
                            // obere Lamellen sind voraus, untere hinken nach
                            val vor = 0.45f * w * bogen * (i.toFloat() / (n - 1) - 0.5f)
                            val xx = (if (rechts) e + vor else e - vor).coerceIn(0f, w)
                            val oben = i * streifen; val unten = oben + streifen + 0.5f
                            if (rechts) pfad.addRect(xx, oben, w, unten, android.graphics.Path.Direction.CW)
                            else pfad.addRect(0f, oben, xx, unten, android.graphics.Path.Direction.CW)
                        }
                    }
                    "kreis" -> {
                        val r = (1f - fs) * kotlin.math.hypot(w, h / 2f) * 1.02f
                        pfad.addCircle(if (rechts) w else 0f, h / 2f, r, android.graphics.Path.Direction.CW)
                        kante.addCircle(if (rechts) w else 0f, h / 2f, r, android.graphics.Path.Direction.CW)
                    }
                }
            }

            /** Kante einer Seite malen */
            private fun kanteMalen(cv: Canvas, kante: android.graphics.Path, vv: Float, w: Float, h: Float) {
                val fs = (kotlin.math.abs(vv) / w).coerceIn(0f, 1f)
                if (fs >= 0.9995f || fs <= 0.0005f) return
                val rechts = vv >= 0f
                val e = if (rechts) vv else w + vv
                val bogen = kotlin.math.sin(Math.PI * fs).toFloat()
                when (modus) {
                    "riss" -> {
                        // Goldsaum an der Bruchkante, dazu ein paar kurze Haarrisse in die Seite hinein
                        val gold = rsGold ?: Deko.goldStift(w, h).also { rsGold = it }
                        Deko.rissMalen(cv, kante, dp, rsFuge, gold, rsGlanz, breite = 1.3f)
                        val z = Skizze.Zufall(saat + 7)
                        rsHaar.reset()
                        repeat(5) {
                            val y0 = z.z(0.05f, 0.95f) * h
                            Deko.riss(rsHaar, e, y0, (if (rechts) 0f else 3.1416f) + z.z(-0.6f, 0.6f), z.z(20f, 55f) * dp * (0.4f + bogen), z, dp, 1)
                        }
                        cv.save(); cv.clipPath(pfad)
                        Deko.rissMalen(cv, rsHaar, dp, rsFuge, gold, rsGlanz, alpha = 220, breite = 0.8f)
                        cv.restore()
                    }
                    "welle" -> {
                        stift.color = wellenF
                        stift.alpha = (230 * bogen.coerceAtLeast(0.35f)).toInt(); stift.strokeWidth = 3.5f * dp; cv.drawPath(kante, stift)
                        cv.save(); cv.translate(if (rechts) -9f * dp else 9f * dp, 0f)
                        stift.alpha = (110 * bogen).toInt(); stift.strokeWidth = 1.6f * dp; cv.drawPath(kante, stift)
                        cv.translate(if (rechts) -8f * dp else 8f * dp, 0f)
                        stift.alpha = (50 * bogen).toInt(); stift.strokeWidth = 1f * dp; cv.drawPath(kante, stift)
                        cv.restore()
                    }
                    "swiss" -> {
                        fuell.color = akzentF
                        val bb = 12f * dp
                        if (rechts) cv.drawRect(e - bb, 0f, e, h, fuell) else cv.drawRect(e, 0f, e + bb, h, fuell)
                        fuell.color = Color.BLACK
                        if (rechts) cv.drawRect(e - bb - 3f * dp, 0f, e - bb, h, fuell) else cv.drawRect(e + bb, 0f, e + bb + 3f * dp, h, fuell)
                    }
                    "glas" -> {
                        // Glasscheibe: heller Lichtsaum an der Kante, nach innen auslaufender Glanz, Schatten davor
                        val innen = if (rechts) 1f else -1f
                        glasP.shader = android.graphics.LinearGradient(e, 0f, e + innen * 28f * dp, 0f,
                            intArrayOf(0x66FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
                        if (rechts) cv.drawRect(e, 0f, e + 28f * dp, h, glasP) else cv.drawRect(e - 28f * dp, 0f, e, h, glasP)
                        glasP.shader = android.graphics.LinearGradient(e, 0f, e - innen * 18f * dp, 0f,
                            intArrayOf(0x40000000, 0x00000000), null, android.graphics.Shader.TileMode.CLAMP)
                        if (rechts) cv.drawRect(e - 18f * dp, 0f, e, h, glasP) else cv.drawRect(e, 0f, e + 18f * dp, h, glasP)
                        glasP.shader = null
                        stift.color = Color.WHITE; stift.alpha = 220; stift.strokeWidth = 1.5f * dp
                        cv.drawLine(e + innen * 1f * dp, 0f, e + innen * 1f * dp, h, stift)
                    }
                    "geld" -> geld.band(cv, e, h, if (rechts) -1f else 1f, dunkelT, 1f, vv)
                    "skizze" -> {
                        // Beim Wischen entstehen an der Kante immer wieder neue schraege Striche (oben nach unten,
                        // Strich fuer Strich); die alten werden dabei langsam wegradiert - vom Anfang her.
                        // Ohne Stift, ohne Radiergummi, ohne Rand-Linie und ohne Papier: nur die Striche selbst.
                        if (vv != v) return   // zweiter Finger vom anderen Rand: keine Striche
                        val jetzt = android.os.SystemClock.uptimeMillis()
                        val letzter = skStriche.lastOrNull()
                        if (letzter == null || kotlin.math.abs(e - letzter.x) > SK_ABSTAND * dp || letzter.rechts != rechts) {
                            for (st in skStriche) if (st.weg == 0L) st.weg = jetzt
                            skStriche.add(skNeu(e, rechts, h, jetzt))
                        }
                        skStriche.removeAll { it.weg != 0L && jetzt - it.weg > SK_ZEIT + SK_STRICH }
                        while (skStriche.size > 12) skStriche.removeAt(0)
                        var laeuft = false
                        val br = 16f * dp
                        for (st in skStriche) {
                            val el = (jetzt - st.t0).toFloat()
                            val ew = if (st.weg == 0L) -1f else (jetzt - st.weg).toFloat()
                            if (el < SK_ZEIT + SK_STRICH || ew >= 0f) laeuft = true
                            val x0 = if (st.rechts) st.x - br else st.x
                            cv.save(); cv.clipRect(x0, 0f, x0 + br, h); cv.translate(x0, 0f)
                            stift.color = Skizze.TINTE; stift.alpha = 200; stift.strokeWidth = 1.2f * dp
                            if (ew < 0f && el >= SK_ZEIT + SK_STRICH) cv.drawPath(st.schraff, stift)
                            else {
                                skMass.setPath(st.schraff, false)
                                do {
                                    val len = skMass.length
                                    if (len > 0f) {
                                        skMass.getPosTan(0f, skPos, null)
                                        val yf = skPos[1] / h
                                        val bis = ((el - yf * SK_ZEIT) / SK_STRICH).coerceIn(0f, 1f)
                                        val von = if (ew < 0f) 0f else ((ew - yf * SK_ZEIT) / SK_STRICH).coerceIn(0f, 1f)
                                        if (bis > von) { skTeil.reset(); skMass.getSegment(len * von, len * bis, skTeil, true); cv.drawPath(skTeil, stift) }
                                    }
                                } while (skMass.nextContour())
                            }
                            cv.restore()
                        }
                        if (laeuft) postInvalidateOnAnimation()
                    }
                    "kreis" -> {
                        stift.color = wellenF; stift.alpha = (200 * bogen).toInt(); stift.strokeWidth = 3f * dp; cv.drawPath(kante, stift)
                        cv.save(); val m = if (rechts) w else 0f
                        cv.scale(1.06f, 1.06f, m, h / 2f)
                        stift.alpha = (80 * bogen).toInt(); stift.strokeWidth = 1.5f * dp; cv.drawPath(kante, stift)
                        cv.restore()
                    }
                }
            }
        }
        private val zyklus = Lebenszyklus()
        // Apps/Links aus dem Menue brauchen ausserhalb einer Activity "neue Aufgabe" - sonst stuerzt es ab
        private val k: Context = NeueAufgabe(ctx)
        private val schwelle = 12f * ctx.resources.displayMetrics.density
        private var x0 = 0f; private var y0 = 0f; private var start = 0f
        private var waagerecht = false
        private val rand = 28f * ctx.resources.displayMetrics.density   // nur vom Rand aus laesst sich die Seite wegwischen
        private var vomLinkenRand = false
        private var vomRechtenRand = false

        init {
            setViewTreeLifecycleOwner(zyklus)
            setViewTreeSavedStateRegistryOwner(zyklus)
            zyklus.los()
            val inhalt = ComposeView(k).apply {
                setContent {
                    val z = remember { Zustand(k) }
                    CompositionLocalProvider(LocalZustand provides z) {
                        LauncherTheme(z.thema) {
                            val p = LocalPalette.current
                            Box(Modifier.fillMaxSize().background(p.grund)) {
                                Hintergrund(z.thema.hintergrund, Modifier.fillMaxSize(), laeuft = true)
                                Box(Modifier.fillMaxSize().background(p.schleier))
                                // ganz oben anfangen (auch neben / ueber der Kamera)
                                androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 12.dp)) {
                                    CompositionLocalProvider(LocalKompakt provides true) {
                                        NebenSeite(Modifier.fillMaxWidth().weight(1f), onZurueck = { SperrSeite.schliessen() }, hochZurueck = false, seite = "sperre")
                                    }
                                }
                                // unten links lange druecken -> entsperren -> gleich die Sperr-Seite gestalten
                                val haptik = androidx.compose.ui.platform.LocalHapticFeedback.current
                                Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart).size(96.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures(onLongPress = {
                                            haptik.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                            SperrSeite.gestalten()
                                        })
                                    })
                            }
                        }
                    }
                }
            }
            addView(schleierAnsicht, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            karte.addView(inhalt, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(karte, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); anwenden() }

        fun ende() { zyklus.ende() }

        // Seite wird gerade von den Raendern hereingezogen: ein Finger, der hier landet, zieht mit (zweiter Rand)
        private var mitziehen = 0
        private var schlucken = false
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                mitziehen = 0; schlucken = false
                if (SperrSeite.ziehtGerade) {
                    val b = width.toFloat()
                    mitziehen = SperrSeite.fingerRunter(e.rawX < b / 2f, e.rawX)
                    // dritter Finger waehrend des Ziehens: ganze Beruehrung ignorieren (nicht halb ans Menue geben)
                    if (mitziehen == 0) schlucken = true
                }
            }
            if (schlucken) return true
            if (mitziehen != 0) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> SperrSeite.fingerZug(mitziehen, e.rawX)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val r = mitziehen; mitziehen = 0; SperrSeite.fingerHoch(r) }
                }
                return true
            }
            return super.dispatchTouchEvent(e)
        }

        // nur vom linken oder rechten Rand nach innen wischen schiebt die Seite weg - sonst bekommt alles das Menue.
        // Zwei Finger (je einer an jedem Rand) gehen auch - wie beim Reinziehen vom Sperrbildschirm.
        private var erster = -1
        private var zweiter = -1
        private var zx0 = 0f; private var zStart = 0f; private var zweiterLinks = false
        private fun zweiterAnnehmen(e: MotionEvent, i: Int): Boolean {
            if (zweiter != -1) return false
            val x = e.getX(i)
            val links = x < width / 2f
            val ersterLinks = vomLinkenRand || (!vomRechtenRand && versatz > 0f)
            if (links == ersterLinks) return false                     // gleiche Seite: nicht der Gegenfinger
            if (x > rand * 1.6f && x < width - rand * 1.6f) return false // nur vom Rand
            zweiter = e.getPointerId(i); zx0 = x; zStart = zu; zweiterLinks = links
            return true
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    erster = e.getPointerId(0); zweiter = -1
                    x0 = e.x; y0 = e.y; start = versatz; waagerecht = false
                    vomLinkenRand = e.x < rand; vomRechtenRand = e.x > width - rand
                    SperrSeite.anhalten()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // erster Finger liegt am Rand, zweiter kommt vom anderen Rand: sofort uebernehmen
                    if ((vomLinkenRand || vomRechtenRand) && zweiterAnnehmen(e, e.actionIndex)) { waagerecht = true; start = versatz; return true }
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = e.findPointerIndex(erster); if (i < 0) return false
                    val dx = e.getX(i) - x0; val dy = e.getY(i) - y0
                    val nachInnen = (vomLinkenRand && dx > schwelle) || (vomRechtenRand && -dx > schwelle)
                    if (nachInnen && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.3f) { waagerecht = true; x0 = e.getX(i); start = versatz; return true }
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val b = width.toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { erster = e.getPointerId(0); zweiter = -1; x0 = e.x; start = versatz; SperrSeite.anhalten() }
                MotionEvent.ACTION_POINTER_DOWN -> zweiterAnnehmen(e, e.actionIndex)
                MotionEvent.ACTION_MOVE -> {
                    val i1 = e.findPointerIndex(erster)
                    if (i1 >= 0) versatz = (start + e.getX(i1) - x0).coerceIn(-b, b)
                    val i2 = if (zweiter != -1) e.findPointerIndex(zweiter) else -1
                    if (i2 >= 0) {
                        val dx = e.getX(i2) - zx0
                        zu = (zStart + if (zweiterLinks) dx else -dx).coerceIn(0f, b)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val id = e.getPointerId(e.actionIndex)
                    if (id == zweiter) zweiter = -1
                    if (id == erster) erster = -1
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    erster = -1; zweiter = -1
                    if (zu > 0.5f) SperrSeite.fahrenBeide(this, kotlin.math.abs(versatz) + zu > b * 0.25f)
                    else SperrSeite.fahren(this, when { versatz > b * 0.25f -> b; versatz < -b * 0.25f -> -b; else -> 0f })
                }
            }
            return true
        }
    }
}


@androidx.compose.runtime.Composable
internal fun SperrUhr() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val stil = z.thema.uhr
    var jetzt by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) { jetzt = System.currentTimeMillis(); kotlinx.coroutines.delay(1000 - jetzt % 1000) }
    }
    val de = java.util.Locale.GERMANY
    val d = java.util.Date(jetzt)
    val dicke = when (stil.dicke) { 0 -> androidx.compose.ui.text.font.FontWeight.Light; 1 -> androidx.compose.ui.text.font.FontWeight.Normal
                                    else -> androidx.compose.ui.text.font.FontWeight.Bold }
    val zeilen = if (stil.gestapelt) listOf(java.text.SimpleDateFormat("HH", de).format(d), java.text.SimpleDateFormat("mm", de).format(d))
                 else listOf(java.text.SimpleDateFormat("HH:mm", de).format(d))
    val messer = androidx.compose.ui.text.rememberTextMeasurer()
    val dichte = androidx.compose.ui.platform.LocalDensity.current
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp)) {
        val breite = with(dichte) { maxWidth.toPx() }
        // Probe mit 100 sp messen (breitester Fall "00"/"00:00"), dann so skalieren, dass es genau die Breite fuellt
        val probe = androidx.compose.material3.LocalTextStyle.current.merge(androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(100f, androidx.compose.ui.unit.TextUnitType.Sp),
            fontWeight = dicke, letterSpacing = androidx.compose.ui.unit.TextUnit(-0.04f, androidx.compose.ui.unit.TextUnitType.Em)))
        val muster = if (stil.gestapelt) "00" else "00:00"
        val gemessen = remember(breite, dicke, muster, probe) { messer.measure(muster, probe).size.width.toFloat().coerceAtLeast(1f) }
        val sp = 100f * breite / gemessen * 0.98f
        val groesse = androidx.compose.ui.unit.TextUnit(sp, androidx.compose.ui.unit.TextUnitType.Sp)
        // Ziffern eng zuschneiden: der unsichtbare Schriftrand ueber und unter den Ziffern (bei dieser Groesse
        // fast ein halbes Zeichen hoch) zaehlt nicht mehr mit - die Uhr bleibt gleich gross, der leere Platz ist weg
        val schriftPx = with(dichte) { groesse.toPx() }
        val eng = Modifier.layout { m, c ->
            val pl = m.measure(c)
            val fb = pl[androidx.compose.ui.layout.FirstBaseline]
            val lb = pl[androidx.compose.ui.layout.LastBaseline]
            if (fb == androidx.compose.ui.layout.AlignmentLine.Unspecified || lb == androidx.compose.ui.layout.AlignmentLine.Unspecified) {
                layout(pl.width, pl.height) { pl.place(0, 0) }
            } else {
                val oben = (fb - schriftPx * 0.76f).toInt().coerceAtLeast(0)
                val h = (lb - oben).coerceAtLeast(1)
                layout(pl.width, h) { pl.place(0, -oben) }
            }
        }
        androidx.compose.foundation.layout.Column {
            zeilen.forEachIndexed { nr, zeile ->
                if (nr > 0) androidx.compose.foundation.layout.Spacer(Modifier.height(with(dichte) { (schriftPx * 0.07f).toDp() }))
                TextUe(zeile, color = p.text, fontSize = groesse, fontWeight = dicke, maxLines = 1, softWrap = false,
                    letterSpacing = androidx.compose.ui.unit.TextUnit(-0.04f, androidx.compose.ui.unit.TextUnitType.Em),
                    modifier = eng)
            }
        }
    }
}

internal object SwissRaster {
    /** Feld: x1, y1, x2, y2, Spalte, Zeile, frisch (1 = gerade weg, 0 = Linien verblasst) */
    /** wiederverwendbare Felder: waehrend der Animation keine neuen Arrays pro Bild */
    class Puffer {
        val liste = ArrayList<FloatArray>()
        private val vorrat = ArrayList<FloatArray>()
        fun leeren() { vorrat.addAll(liste); liste.clear() }
        fun neu(): FloatArray = if (vorrat.isEmpty()) FloatArray(7) else vorrat.removeAt(vorrat.size - 1)
    }

    fun felder(w: Float, h: Float, f: Float, dp: Float, vonLinks: Boolean, saat: Int, puffer: Puffer? = null): List<FloatArray> {
        val rr = 24f * dp
        val z = ((w - 2 * rr) / 8f).coerceAtLeast(8f * dp)
        val nS = 1 + kotlin.math.ceil((w - rr) / z).toInt()
        val nZ = 1 + kotlin.math.ceil((h - rr) / z).toInt()
        val gesamt = rr + (nS - 1) * z
        val weit = f * (gesamt + z * 0.9f)                      // bei f = 1 sind sicher alle weg
        puffer?.leeren()
        val aus = puffer?.liste ?: ArrayList()
        for (i in 0 until nS) {
            val innen = if (i == 0) 0f else rr + (i - 1) * z
            val aussen = if (i == 0) rr else rr + i * z
            if (innen > weit + z) break
            for (j in 0 until nZ) {
                val t1 = zahl(i, j, 1, saat); val t2 = zahl(i, j, 2, saat)
                val noetig = innen + (aussen - innen) * (0.15f + 0.85f * t1) - z * 0.6f * t2
                if (weit < noetig.coerceAtLeast(2f * dp)) continue
                val oben = if (j == 0) 0f else rr + (j - 1) * z
                val unten = if (j == 0) rr else rr + j * z
                val x1 = if (vonLinks) innen else w - aussen
                val x2 = if (vonLinks) aussen else w - innen
                val frisch = (1f - (weit - noetig) / (2.5f * z)).coerceIn(0f, 1f)
                val fd = puffer?.neu() ?: FloatArray(7)
                fd[0] = x1; fd[1] = oben; fd[2] = x2; fd[3] = minOf(unten, h + 1f); fd[4] = i.toFloat(); fd[5] = j.toFloat(); fd[6] = frisch
                aus.add(fd)
            }
        }
        return aus
    }

    /** etwa jede vierte Rasterlinie fehlt -> manche Felder haben nur 3 (oder 2) Seiten */
    fun offen(a: Int, b: Int, richtung: Int, saat: Int): Boolean = (hash(a, b, richtung + 10, saat) and 0xFF) < 70

    fun zahl(a: Int, b: Int, r: Int, saat: Int) = (hash(a, b, r, saat) and 0xFFFF) / 65535f
    private fun hash(a: Int, b: Int, r: Int, saat: Int): Int {
        var x = a * 73856093 xor (b * 19349663) xor (r * 83492791) xor saat
        x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15)
        return x
    }

    /** die vier Kanten eines Feldes, die gemalt werden: Liste von (x1, y1, x2, y2) */
    fun linien(fd: FloatArray, vonLinks: Boolean, saat: Int, ziel: (Float, Float, Float, Float) -> Unit) {
        val i = fd[4].toInt(); val j = fd[5].toInt()
        if (!offen(i, j, 0, saat)) ziel(fd[0], fd[1], fd[2], fd[1])
        if (!offen(i, j + 1, 0, saat)) ziel(fd[0], fd[3], fd[2], fd[3])
        val innenX = if (vonLinks) fd[0] else fd[2]; val aussenX = if (vonLinks) fd[2] else fd[0]
        if (!offen(i, j, 1, saat)) ziel(innenX, fd[1], innenX, fd[3])
        if (!offen(i + 1, j, 1, saat)) ziel(aussenX, fd[1], aussenX, fd[3])
    }
}
