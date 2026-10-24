package de.atelierlauncher.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

// ============================================================================
//  Randgesten (eigene Einhand-Bedienung): schmale Griffe am linken/rechten Rand.
//  Reinwischen gerade / schraeg hoch / schraeg runter = Aktion,
//  Laeuft im Laut-Dienst (Bedienungshilfe),
//  dadurch auch ueber allen anderen Apps.
//  Einstellungen in den prefs "launcher", alle Schluessel beginnen mit "rg_".
// ============================================================================
object Randgesten {
    /** Richtungen: g = gerade, o = schraeg hoch, u = schraeg runter */
    val RICHTUNGEN = listOf("g" to "Gerade", "o" to "Schräg hoch", "u" to "Schräg runter")
    val PFEIL = mapOf("g" to "→", "o" to "↗", "u" to "↘")

    /** alle Aktionen (Schluessel -> Name). Dazu kommt "app:<paket/activity>". */
    val AKTIONEN = listOf(
        "nichts" to "Nichts",
        "zurueck" to "Zurück",
        "home" to "Startbildschirm",
        "letzte" to "Letzte Apps",
        "vorherige" to "Vorherige App",
        "benachrichtigungen" to "Benachrichtigungen",
        "schnell" to "Schnelleinstellungen",
        "screenshot" to "Screenshot",
        "sperren" to "Bildschirm sperren",
        "split" to "Geteilter Bildschirm",
        "power" to "Ausschalt-Menü",
        "lampe" to "Taschenlampe",
        *(emptyArray()),
    )

    fun standardAktion(richtung: String, lang: Boolean): String = when (richtung) {
        "g" -> if (lang) "vorherige" else "zurueck"
        "o" -> if (lang) "letzte" else "home"
        else -> if (lang) "schnell" else "benachrichtigungen"
    }

    fun schluessel(seite: String, richtung: String, lang: Boolean) = "rg_${seite}_${richtung}_${if (lang) "l" else "k"}"

    /** Links und rechts teilen sich EINE Einstellung (Standard) - links liest dann alles von rechts (gespiegelt) */
    fun gleich(prefs: SharedPreferences) = prefs.getBoolean("rg_lr_gleich", true)
    fun q(prefs: SharedPreferences, s: String) = if (s == "l" && gleich(prefs)) "r" else s

    fun aktion(prefs: SharedPreferences, seite: String, richtung: String, lang: Boolean): String =
        prefs.getString(schluessel(q(prefs, seite), richtung, lang), null) ?: standardAktion(richtung, lang)

    /** eigene Belegung fuer den Sperrbildschirm (nicht gesetzt = wie normal) */
    fun schluesselSperre(seite: String, richtung: String, lang: Boolean) = "rg_sp_${seite}_${richtung}_${if (lang) "l" else "k"}"
    fun aktionSperre(prefs: SharedPreferences, seite: String, richtung: String, lang: Boolean): String =
        prefs.getString(schluesselSperre(q(prefs, seite), richtung, lang), null) ?: aktion(prefs, seite, richtung, lang)

    fun name(ctx: Context, aktion: String): String {
        if (aktion.startsWith("app:")) {
            val k = aktion.removePrefix("app:")
            val cn = ComponentName.unflattenFromString(k) ?: return "App"
            return try {
                val pm = ctx.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(cn.packageName, 0)).toString()
            } catch (e: Exception) { "App (nicht mehr da)" }
        }
        return AKTIONEN.firstOrNull { it.first == aktion }?.second ?: "Nichts"
    }

    // ---------------------------------------------------------------- mehrere Randgesten
    // "l" und "r" sind die beiden ersten (wie immer). Weitere heissen g3, g4, ... und haben einen eigenen Rand.
    fun extra(prefs: SharedPreferences): List<String> =
        (prefs.getString("rg_extra", "") ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun ids(prefs: SharedPreferences): List<String> = listOf("l", "r") + extra(prefs)
    /** fuer das Einstellungs-Menue: bei "gleich" nur EIN Eintrag fuer links und rechts */
    fun menueIds(prefs: SharedPreferences): List<String> = (if (gleich(prefs)) listOf("r") else listOf("l", "r")) + extra(prefs)
    /** Rand einer Randgeste: "l", "r" oder "e" (unten in beiden Ecken - links und rechts gleich) */
    fun rand(prefs: SharedPreferences, s: String): String {
        if (s == "l" || s == "r") return s
        val r = prefs.getString("rg_${s}_rand", "e") ?: "e"
        return if (r == "u") "e" else r            // frueher: Streifen ueber den ganzen unteren Rand -> jetzt Ecken
    }
    fun randName(r: String) = when (r) { "l" -> "links"; "e" -> "untere Ecken"; else -> "rechts" }
    /** Fenster-Namen einer Randgeste: Ecken = zwei Fenster (links "g3", rechts "g3~r") */
    private fun fenster(p: SharedPreferences, s: String): List<Pair<String, String>> =
        if (rand(p, s) == "e") listOf(s to "ul", "$s~r" to "ur") else listOf(s to rand(p, s))
    fun anzeigeName(prefs: SharedPreferences, s: String): String = when (s) {
        "l" -> "Links"; "r" -> if (gleich(prefs)) "Links & rechts" else "Rechts"
        else -> "Nr. " + s.removePrefix("g") + " · " + randName(rand(prefs, s))
    }
    /** neue Randgeste anlegen (Standard: unten, halbe Breite, mittig) - gibt ihren Namen zurueck */
    fun neu(ctx: Context): String {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val alt = extra(p)
        val nr = (alt.mapNotNull { it.removePrefix("g").toIntOrNull() }.maxOrNull() ?: 2) + 1
        val id = "g$nr"
        p.edit().putString("rg_${id}_rand", "e").putBoolean("rg_${id}_an", true)
            .putFloat("rg_${id}_pos", 0f).putFloat("rg_${id}_hoehe", 0.14f).putInt("rg_${id}_breite", 36)
            .putString("rg_extra", (alt + id).joinToString(",")).apply()
        return id
    }
    /** Randgeste loeschen (nur die zusaetzlichen) - mit allen ihren Einstellungen */
    fun loeschen(ctx: Context, id: String) {
        if (id == "l" || id == "r") return
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val e = p.edit()
        for (k in p.all.keys) if (k.startsWith("rg_${id}_") || k.startsWith("rg_sp_${id}_")) e.remove(k)
        e.putString("rg_extra", extra(p).filter { it != id }.joinToString(",")).apply()
    }
    /** Richtungen mit Namen und Pfeil passend zum Rand */
    fun richtungName(rand: String, r: String): String = when (rand) {
        "e" -> when (r) { "o" -> "⤢  Schräg zur Mitte"; "u" -> "↔  Flach zur Mitte"; else -> "↑  Gerade hoch" }
        "l" -> (PFEIL[r] ?: "") + "  " + (RICHTUNGEN.firstOrNull { it.first == r }?.second ?: "")
        else -> (PFEIL[r] ?: "").replace("→", "←").replace("↗", "↖").replace("↘", "↙") + "  " + (RICHTUNGEN.firstOrNull { it.first == r }?.second ?: "")
    }

    // Standardwerte der Griffe
    fun an(prefs: SharedPreferences) = prefs.getBoolean("rg_an", false)
    fun seiteAn(prefs: SharedPreferences, s: String) = q(prefs, s).let { prefs.getBoolean("rg_${it}_an", it != "l") }
    fun position(prefs: SharedPreferences, s: String) = prefs.getFloat("rg_${q(prefs, s)}_pos", 0.55f)        // 0 oben .. 1 unten
    fun hoehe(prefs: SharedPreferences, s: String) = prefs.getFloat("rg_${q(prefs, s)}_hoehe", 0.30f)         // Anteil der Bildschirmhoehe
    fun breite(prefs: SharedPreferences, s: String) = prefs.getInt("rg_${q(prefs, s)}_breite", 14)            // dp
    fun deckkraft(prefs: SharedPreferences, s: String) = prefs.getFloat("rg_${q(prefs, s)}_alpha", 0.25f)     // 0 = unsichtbar
    fun vibrieren(prefs: SharedPreferences) = prefs.getBoolean("rg_vib", true)
    /** woher die Animation kommt: vom unteren Rand (Standard bei den Ecken) oder von der Seite */
    fun animUnten(prefs: SharedPreferences, s: String) =
        (prefs.getString("rg_${q(prefs, s)}_anim", null) ?: if (rand(prefs, s) == "e") "unten" else "seite") == "unten"
    /** Animation beim Reinwischen an? ("aus" ist ein eigener Stil) */
    fun welleAn(prefs: SharedPreferences) = stil(prefs) != "aus"
    /** EINE Einstellung fuer die Animation beim Reinwischen */
    val STILE = listOf("aus" to "Aus", "tropfen" to "Wassertropfen", "linie" to "Linie", "strich" to "Strich (schlicht)", "kante" to "Kante", "pfeil" to "Pfeil",
                       "gross" to "Große Welle", "grossswiss" to "Swiss-Linien", "grossfelder" to "Swiss-Felder", "grossgeld" to "Banknote", "skizze" to "Gezeichnete Kanten", "riss" to "Kintsugi-Risse")
    fun stilHinweis(st: String) = when (st) {
        "aus" -> "Keine Animation"
        "strich" -> "Nur eine Linie wie die Taskleiste – kommt mit dem Finger rein"
        "tropfen" -> "Wie ein Tropfen auf Glas – der Bildschirm wird dahinter gekrümmt"
        "riss" -> "Marmor reißt vom Rand her auf, die Risse füllen sich mit Gold – beim Auslösen bricht das Stück heraus und fällt"
        "skizze" -> "Wie die Swiss-Linien, aber mit Kuli gezeichnet: schiefe Kanten, geschlossene Vierecke mit Papier gefüllt"
        "pfeil" -> "Pfeil vom Rand herein – zeigt gerade, schräg hoch oder schräg runter, je nach Geste"
        "kante" -> "Block in der Akzentfarbe mit harter Kante"
        "gross" -> "Wellenkante läuft mit dem Finger bis zu einem Fünftel rein und wird dabei durchsichtiger. Bei Home sieht man dahinter den Startbildschirm"
        "grossswiss" -> "Rasterlinien wie im Swiss-Hintergrund tauchen zufällig auf"
        "grossfelder" -> "Felder im Swiss-Raster ploppen zufällig auf – bei Home mit dem Startbildschirm darin"
        "grossgeld" -> "Ein verziertes Banknoten-Band fährt vom Rand rein – dahinter Guilloche-Papier bzw. der Startbildschirm"
        else -> "Schlichte Welle mit Linie"
    }
    /** gespeicherter Stil; alter Schalter "Wellen-Animation aus" wird zu "aus" */
    fun stil(prefs: SharedPreferences): String {
        if (!prefs.getBoolean("rg_welle", true)) {
            prefs.edit().putString("welleStil", "aus").remove("rg_welle").remove("rg_uebergang").apply()
        }
        return prefs.getString("welleStil", "tropfen") ?: "tropfen"
    }
    fun aufStartseiteAus(prefs: SharedPreferences) = prefs.getBoolean("rg_start_aus", true)
    fun ausnahmen(prefs: SharedPreferences): Set<String> = prefs.getStringSet("rg_aus", emptySet()) ?: emptySet()

    // ---------------------------------------------------------------- Laufzeit (im Dienst)
    private var dienst: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var prefs: SharedPreferences? = null
    private val haupt = Handler(Looper.getMainLooper())
    private val griffe = HashMap<String, RandGriff>()
    private val params = HashMap<String, WindowManager.LayoutParams>()
    private var vorschau = false
    private var launcherVorne = false
    private var vordergrund: String? = null

    private val hoerer = SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
        if (k != null && k.startsWith("rg_")) haupt.post { anwenden() }
    }

    fun starten(s: AccessibilityService) {
        dienst = s
        wm = s.getSystemService(WindowManager::class.java)
        val p = s.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        prefs = p
        p.registerOnSharedPreferenceChangeListener(hoerer)
        anwenden()
    }

    fun stoppen() {
        prefs?.unregisterOnSharedPreferenceChangeListener(hoerer)
        for (s in griffe.keys.toList()) entfernen(s)
        UntenWisch.entfernen(wm)
        dienst = null; wm = null; prefs = null
    }

    /** Einstellungsseite offen: Griffe immer zeigen (auch ueber dem Launcher) und deutlicher */
    fun vorschauSetzen(an: Boolean) { vorschau = an; haupt.post { anwenden() } }

    /** vom Launcher selbst (onResume/onPause) - schneller als das Fenster-Ereignis */
    // Zeitpunkt, an dem eine App geoeffnet wurde: waehrend Android das App-Fenster einblendet, keine
    // Rand-Fenster anlegen/verschieben - das hielt den Uebergang auf, bis die App ihr erstes Bild hatte
    private var wegZeit = 0L
    private const val RUHE_MS = 700L
    private fun ruhig() = android.os.SystemClock.uptimeMillis() - wegZeit < RUHE_MS

    fun launcherVorne(an: Boolean) {
        launcherVorne = an
        if (an) haupt.post { anwenden() }
        else { wegZeit = android.os.SystemClock.uptimeMillis(); haupt.postDelayed({ if (!launcherVorne) anwenden() }, RUHE_MS) }
        if (an) Uebergang.homeFotoSpaeter(dienst) { launcherVorne }
    }

    /** neue App im Vordergrund (fuer "In diesen Apps aus") */
    fun fensterEreignis(e: AccessibilityEvent?, ctx: Context) {
        if (e == null) return
        // nur bei Fenster-Aenderungen (nicht bei jedem Inhalts-Ereignis) nach der Tastatur schauen
        if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) tastaturPruefen()
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pk = e.packageName?.toString() ?: return
        if (pk == "com.android.systemui" || pk == ctx.packageName) return
        val tastatur = tastaturApp(ctx)
        if (tastatur != null && tastatur.startsWith("$pk/")) return
        if (pk == vordergrund) return
        vordergrund = pk
        if (ruhig()) return     // wird nach dem Uebergang ohnehin angewendet (launcherVorne(false))
        anwenden()
    }

    // eingestellte Tastatur-App: nur ab und zu nachschauen (war bei jedem Fensterwechsel eine Systemabfrage)
    private var tastaturName: String? = null
    private var tastaturZeit = 0L
    private fun tastaturApp(ctx: Context): String? {
        val jetzt = android.os.SystemClock.uptimeMillis()
        if (tastaturZeit == 0L || jetzt - tastaturZeit > 60_000L) {
            tastaturName = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) } catch (e: Exception) { null }
            tastaturZeit = jetzt
        }
        return tastaturName
    }

    /** Oberkante der Tastatur auf dem Bildschirm (null = keine Tastatur offen) */
    private var tastaturOben: Int? = null
    private val rahmen = android.graphics.Rect()

    /** Tastatur offen? Dann duerfen die Griffe nicht drueberliegen (sonst ist z. B. das "a" weg) */
    private fun tastaturPruefen() {
        val dn = dienst ?: return
        val oben = try {
            dn.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .minOfOrNull { it.getBoundsInScreen(rahmen); if (rahmen.height() > 0) rahmen.top else Int.MAX_VALUE }
                ?.takeIf { it != Int.MAX_VALUE }
        } catch (e: Exception) { null }
        if (oben != tastaturOben) { tastaturOben = oben; anwenden() }
    }

    /** Drehung o. ae.: Groesse neu berechnen */
    fun neuMessen() = haupt.post { anwenden() }

    private fun soll(s: String): Boolean {
        val p = prefs ?: return false
        if (!an(p) || !seiteAn(p, s)) return false
        if (vorschau) return true
        // anderer Launcher ist Startbildschirm: dessen Randgesten gelten, diese ruhen
        dienst?.let { if (!Standard.bin(it)) return false }
        if (SperrSeite.offen) return false
        if (launcherVorne && aufStartseiteAus(p)) return false
        val v = vordergrund
        if (!launcherVorne && v != null && v in ausnahmen(p)) return false
        return true
    }

    private fun anwenden() {
        val ctx = dienst ?: return
        val w = wm ?: return
        val p = prefs ?: return
        val dm = ctx.resources.displayMetrics
        val d = dm.density
        val hoeheBild = if (Build.VERSION.SDK_INT >= 30) w.currentWindowMetrics.bounds.height() else dm.heightPixels
        // unten nach oben wischen = Startbildschirm (eigener flacher Streifen, gleiche Lage wie die Griffe)
        UntenWisch.anwenden(ctx, w, p, launcherVorne, tastaturOben != null, vorschau,
                            ausnahme = !launcherVorne && vordergrund != null && vordergrund in ausnahmen(p))
        val alle = ids(p)
        // geloeschte Randgesten (oder Ecke, die keine mehr ist): Fenster weg
        val lebend = alle.flatMap { s -> fenster(p, s).map { it.first } }.toSet()
        for (alt in griffe.keys.toList()) if (alt !in lebend) entfernen(alt)
        val breiteBild = if (Build.VERSION.SDK_INT >= 30) w.currentWindowMetrics.bounds.width() else dm.widthPixels
        for (s0 in alle) for ((s, rd) in fenster(p, s0)) {
            // s = Fenster-Name, s0 = Randgeste (Einstellungen + Aktionen)
            if (!soll(s0)) { ruhen(s); continue }
            // Rand geaendert: Griff neu anlegen (er weiss, an welchem Rand er sitzt)
            if (griffe[s] != null && griffRand[s] != rd) entfernen(s)
            val unten = rd == "ul" || rd == "ur"
            var h: Int; val b: Int; val x: Int; var y: Int
            if (unten) {
                // Ecke unten: "Hoehe" = Breite der Ecke (Anteil Bildschirmbreite), "Breite" = Hoehe in dp; beide Ecken gleich
                b = (hoehe(p, s0) * breiteBild).roundToInt().coerceAtLeast((12 * d).roundToInt())
                h = (breite(p, s0) * d).roundToInt().coerceAtLeast((2 * d).roundToInt())
                x = 0; y = 0
                if (tastaturOben != null && !vorschau) { ruhen(s); continue }     // Tastatur offen: unten nichts verdecken
            } else {
                h = (hoehe(p, s0) * hoeheBild).roundToInt().coerceAtLeast((12 * d).roundToInt())
                b = (breite(p, s0) * d).roundToInt().coerceAtLeast((2 * d).roundToInt())
                // uebersteuert (unter 0 / ueber 1): Griff ragt ueber den Bildschirmrand hinaus
                y = (position(p, s0) * (hoeheBild - h)).roundToInt(); x = 0
                // Tastatur offen: Griff endet ueber der Tastatur - oder verschwindet ganz, wenn zu wenig Platz bleibt
                val tk = tastaturOben
                if (tk != null && !vorschau && y + h > tk) {
                    val rest = tk - maxOf(y, 0)
                    if (rest < (40 * d).roundToInt()) { ruhen(s); continue }
                    h = rest; if (y < 0) { h = tk - y }
                }
            }
            val links = rd == "l" || rd == "ul"
            val au = { prefs?.let { animUnten(it, s0) } == true }      // jedes Mal frisch (Einstellung kann sich aendern)
            val g = griffe[s] ?: RandGriff(ctx, rd, { r, lang -> ausloesen(s0, links, r, lang, griffe[s]?.schnellAusgeloest == true) }, { punkte, dauer -> durchreichen(s, punkte, dauer) },
                { yy, zug, ri -> welleBewegt(s0, s, links, au(), yy, zug, ri) },
                { stark -> wellen[s]?.loslassen(stark); if (!stark) Uebergang.zurueck(links, au()) },
                { gesteBeginnt(); if (!au() && tropfen && stilJetzt != "aus") Linse.holen(dienst) },
                { wellen[s]?.loslassen(false); Uebergang.ausblenden(links, au()) })
            g.deckkraft = deckkraft(p, s0)
            g.vorschau = vorschau
            g.vibrieren = vibrieren(p)
            val lp = params[s] ?: WindowManager.LayoutParams(
                b, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = when (rd) { "l" -> Gravity.LEFT or Gravity.TOP; "ul" -> Gravity.LEFT or Gravity.BOTTOM
                                      "ur" -> Gravity.RIGHT or Gravity.BOTTOM; else -> Gravity.RIGHT or Gravity.TOP }
                if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "Randgeste $s"
            }
            var neu = lp.width != b || lp.height != h || lp.y != y || lp.x != x
            // war versteckt (z. B. Launcher vorne): nur wieder sichtbar + anfassbar machen, kein neues Fenster
            if (ruhend.remove(s)) {
                g.alpha = 1f
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                neu = true
            }
            lp.width = b; lp.height = h; lp.x = x; lp.y = y
            if (griffe[s] == null) {
                try { w.addView(g, lp); griffe[s] = g; params[s] = lp; griffRand[s] = rd } catch (e: Exception) { }
            } else {
                if (neu) try { w.updateViewLayout(g, lp) } catch (e: Exception) { }
                g.invalidate()
            }
        }
    }

    /**
     * Griff gerade nicht gebraucht (Launcher vorne, Tastatur, ...): nur unsichtbar und durchlaessig machen.
     * Fenster jedes Mal entfernen und neu anlegen kostete beim Entsperren / Zurueckkehren spuerbar Zeit.
     */
    private val ruhend = HashSet<String>()
    /** an welchem Rand ein angelegter Griff sitzt */
    private val griffRand = HashMap<String, String>()
    private fun ruhen(s: String) {
        val g = griffe[s] ?: return
        val lp = params[s] ?: return
        if (!ruhend.add(s)) return
        g.alpha = 0f            // nur unsichtbar zeichnen (GONE hat jedes Mal die Grafik weggeworfen = Hänger)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm?.updateViewLayout(g, lp) } catch (e: Exception) { }
    }

    private fun entfernen(s: String) {
        ruhend.remove(s)
        wellenLp.remove(s)
        wellen.remove(s)?.let { v -> try { wm?.removeView(v) } catch (e: Exception) { } }
        val g = griffe.remove(s) ?: return
        params.remove(s); reicht.remove(s); griffRand.remove(s)
        try { wm?.removeView(g) } catch (e: Exception) { }
    }

    /**
     * Keine Randgeste erkannt (Tippen, Halten, Scrollen am Rand): die Beruehrung an die App darunter
     * weitergeben. Griff kurz "durchlaessig" machen und die Bewegung per Bedienungshilfe nachspielen.
     */
    private fun durchreichen(s: String, punkte: FloatArray, dauer: Long) {
        val dn = dienst ?: return
        if (griffe[s] == null || params[s] == null || punkte.size < 2) return
        if (s in reicht) return                              // laeuft schon (nie sich selbst nochmal fangen)
        reicht.add(s)
        anfassbar(s, false)
        val pfad = Path().apply {
            moveTo(punkte[0], punkte[1])
            var i = 2
            while (i + 1 < punkte.size) { lineTo(punkte[i], punkte[i + 1]); i += 2 }
        }
        val zeit = dauer.coerceIn(1L, 10_000L)
        val zurueck = Runnable { reicht.remove(s); anfassbar(s, true) }
        // kurz warten, bis das System den Griff wirklich durchlaessig gemacht hat
        haupt.postDelayed({ nachspielen(dn, pfad, zeit, zurueck) }, 35)
    }

    private val reicht = HashSet<String>()

    private fun nachspielen(dn: AccessibilityService, pfad: Path, zeit: Long, zurueck: Runnable) {
        val ok = try {
            dn.dispatchGesture(
                GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(pfad, 0L, zeit)).build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(d: GestureDescription?) { haupt.removeCallbacks(zurueck); haupt.post(zurueck) }
                    override fun onCancelled(d: GestureDescription?) { haupt.removeCallbacks(zurueck); haupt.post(zurueck) }
                }, haupt)
        } catch (e: Exception) { false }
        if (!ok) haupt.post(zurueck)
        else haupt.postDelayed(zurueck, zeit + 600)      // falls kein Rueckruf kommt
    }

    private fun anfassbar(s: String, an: Boolean) {
        if (an && s in ruhend) return
        val g = griffe[s] ?: return
        val lp = params[s] ?: return
        val f = if (an) lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                else lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (f == lp.flags) return
        lp.flags = f
        try { wm?.updateViewLayout(g, lp) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- kleine Welle am Rand
    /** Farbe der Welle = Akzent des aktuellen Themes (gemerkt, falls der Launcher gerade nicht laeuft) */
    @Volatile var welleAkzent: Int? = null
    @Volatile var welleDunkel: Boolean = true
    /** Aussehen der Wellen: "tropfen" (Wasser auf Glas), "linie", "kante" (Swiss: roter Block) - Design-Menue */
    @Volatile var tropfen: Boolean = true
    @Volatile var kante: Boolean = false
    @Volatile var pfeil: Boolean = false
    @Volatile var skizze: Boolean = false
    @Volatile var strich: Boolean = false
    /** grosse Kante statt kleiner Welle: "welle" / "swiss", sonst null */
    @Volatile var gross: String? = null

    private fun stilMerken(st: String) {
        tropfen = st == "tropfen"; kante = st == "kante"; pfeil = st == "pfeil"; skizze = false; strich = st == "strich"
        gross = when (st) { "gross" -> "welle"; "grossswiss" -> "swiss"; "grossfelder" -> "felder"; "grossgeld" -> "geld"; "skizze" -> "skizze"; "riss" -> "riss"; else -> null }
    }

    fun stilSetzen(ctx: Context, stil: String) {
        stilMerken(stil)
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString("welleStil", stil).apply()
    }


    fun farbeSetzen(ctx: Context, akzent: Int, dunkel: Boolean) {
        if (welleAkzent == akzent && welleDunkel == dunkel) return
        welleAkzent = akzent; welleDunkel = dunkel
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit()
            .putInt("welleAkzent", akzent).putBoolean("welleDunkel", dunkel).apply()
    }

    internal fun farbeLaden(ctx: Context) {
        val p = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        stilMerken(stil(p))
        if (welleAkzent != null) return
        welleAkzent = p.getInt("welleAkzent", 0xFF83D5C5.toInt())
        welleDunkel = p.getBoolean("welleDunkel", true)
    }

    private val wellen = HashMap<String, RandWelle>()
    private val wellenLp = HashMap<String, WindowManager.LayoutParams>()

    /** Finger zieht vom Rand rein: Welle (eigenes, nicht anfassbares Fenster) folgt dem Finger */
    // einmal pro Geste (Finger auf den Rand) statt bei jeder Bewegung: Stil, Farbe, gesperrt?
    // (isKeyguardLocked fragt jedes Mal beim System nach - das war bei jedem Bewegungs-Schritt)
    @Volatile private var stilJetzt = "tropfen"
    @Volatile private var gesperrtJetzt = false
    private fun gesteBeginnt() {
        val ctx = dienst ?: return
        val p = prefs ?: return
        farbeLaden(ctx)
        stilJetzt = stil(p)
        gesperrtJetzt = try { ctx.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true } catch (e: Exception) { false }
    }

    private fun welleBewegt(s: String, wk: String, links: Boolean, vonUnten: Boolean, y: Float, zug: Float, richtung: String?) {
        val ctx = dienst ?: return
        val w = wm ?: return
        val p = prefs ?: return
        if (stilJetzt == "aus") return
        // grosse Kante (statt der kleinen Welle) - bei Home mit dem Startbildschirm dahinter
        gross?.let { art ->
            val home = richtung != null && !gesperrtJetzt && aktion(p, s, richtung, false) == "home"
            Uebergang.ziehen(ctx, links, zug, art, home, vonUnten)
            return
        }
        // kleine Wellen gibt es nur seitlich
        if (vonUnten) return
        val farbe = welleAkzent ?: 0xFF83D5C5.toInt()
        // andere Theme-Farbe: das Fenster einmal neu machen (sonst wird es immer wiederverwendet)
        wellen[wk]?.let { alt -> if (alt.akzent != farbe || alt.dunkel != welleDunkel) {
            wellen.remove(wk); wellenLp.remove(wk); try { w.removeView(alt) } catch (e: Exception) { }
        } }
        wellen[wk]?.let { alt ->
            if (alt.ruht || alt.vorbeiOderLos) {
                // neue Geste: vorhandenes Fenster wieder auf volle Groesse statt neu anlegen (~150 ms gespart)
                alt.neu()
                wellenLp[wk]?.let { lp ->
                    if (lp.width <= 1) {
                        lp.width = (130 * ctx.resources.displayMetrics.density).roundToInt(); lp.height = WindowManager.LayoutParams.MATCH_PARENT
                        try { w.updateViewLayout(alt, lp) } catch (e: Exception) { }
                    }
                }
            }
        }
        if (wellen[wk] == null) {
            val neu = RandWelle(ctx, links, farbe, welleDunkel)
            val lp = WindowManager.LayoutParams(
                (130 * ctx.resources.displayMetrics.density).roundToInt(), WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = (if (links) Gravity.LEFT else Gravity.RIGHT) or Gravity.TOP
                if (Build.VERSION.SDK_INT >= 30) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "Randwelle $s"
            }
            // fertig: nicht entfernen, nur auf 1 Pixel verkleinern (bleibt fuer die naechste Geste)
            neu.fertig = { haupt.post {
                if (wellen[wk] === neu && !neu.ruht) {
                    neu.ruht = true
                    wellenLp[wk]?.let { l -> l.width = 1; l.height = 1; try { w.updateViewLayout(neu, l) } catch (e: Exception) { } }
                }
            } }
            try { w.addView(neu, lp); wellen[wk] = neu; wellenLp[wk] = lp } catch (e: Exception) { return }
        }
        (wellen[wk] ?: return).ziehen(y, zug, richtung)
    }

    private fun ausloesen(seite: String, links: Boolean, richtung: String, lang: Boolean, schnell: Boolean = false) {
        val p = prefs ?: return
        // gesperrt? Dann die Sperrbildschirm-Belegung
        val gesperrt = try { dienst?.getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true } catch (e: Exception) { false }
        val a = if (gesperrt) aktionSperre(p, seite, richtung, lang) else aktion(p, seite, richtung, lang)
        // grosse Kante da? Sie loest sich beim Ausfuehren auf
        val u = animUnten(p, seite)
        val rd = if (links) "l" else "r"
        // ganz schnell geschnippt: die grosse Animation spielt von selbst, schnell gezeichnet (folgt nicht dem Finger)
        val art = gross
        val dn = dienst
        if (schnell && art != null && dn != null && stilJetzt != "aus") {
            val home = !gesperrt && a == "home"
            Uebergang.blitz(dn, links, art, home, u) { ausfuehren(a, rd) }
            return
        }
        if (Uebergang.aktiv(links, u)) Uebergang.durch(links, u) { ausfuehren(a, rd) } else ausfuehren(a, rd)
    }

    /** eine Aktion ausfuehren (Bedienungshilfe darf das ueberall) */
    fun ausfuehren(a: String, seite: String = "r") {
        val s = dienst ?: return
        when (a) {
            "zurueck" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "letzte" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            // zweimal "Letzte Apps" kurz hintereinander = zur vorherigen App
            "vorherige" -> {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                haupt.postDelayed({ dienst?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }, 180)
            }
            "benachrichtigungen" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "schnell" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "power" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            "split" -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            "sperren" -> if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            "lampe" -> { if (!Taschenlampe.da) Taschenlampe.start(s); Taschenlampe.umschalten() }
            else -> if (a.startsWith("app:")) {
                val cn = ComponentName.unflattenFromString(a.removePrefix("app:")) ?: return
                try {
                    s.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(cn)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
                } catch (e: Exception) {
                    s.packageManager.getLaunchIntentForPackage(cn.packageName)?.let {
                        try { s.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
                    }
                }
            }
        }
    }
}
