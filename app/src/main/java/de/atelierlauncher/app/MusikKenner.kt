package de.atelierlauncher.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ============================================================================
//  Musik-Kenner: hoert laufend mit (nur Lautstaerke je Tonhoehe vom echten Takt), schaetzt die Musik ein
//  und entscheidet, wie Beat-Ring, Wellen & Co. reagieren. Alles auf dem Handy, nichts wird gespeichert.
//
//  Gemessen: Energie, Tempo (BPM aus den Schlag-Abstaenden), Bass gegen Hoehen, Dynamik, Aufbau / Drop.
//  Daraus eine Art (ruhig, chill, pop, rock, elektro, klassik, sprache) - wechselt erst, wenn die neue Art
//  ein paar Sekunden lang klar vorne liegt. Jede Art hat ihre eigene Reaktion (Staerke, Ruck, Abklingen, Atmen).
// ============================================================================

internal object MusikKenner {
    /** wie eine Art reagiert */
    data class Reaktion(val name: String, val staerke: Float, val ruck: Float, val abklingen: Float, val atmen: Float, val bass: Float)

    private val ARTEN = mapOf(
        "ruhig" to Reaktion("Ruhig", 0.35f, 0.0f, 0.965f, 1.0f, 0.8f),
        "chill" to Reaktion("Chill", 0.55f, 0.2f, 0.92f, 0.5f, 1.0f),
        "pop" to Reaktion("Pop", 0.75f, 0.55f, 0.86f, 0.15f, 1.0f),
        "rock" to Reaktion("Rock", 0.9f, 1.0f, 0.72f, 0.0f, 1.0f),
        "elektro" to Reaktion("Elektro / Bass", 1.0f, 0.75f, 0.80f, 0.1f, 1.5f),
        "klassik" to Reaktion("Klassik", 0.6f, 0.05f, 0.95f, 0.6f, 0.8f),
        "sprache" to Reaktion("Sprache", 0.15f, 0.0f, 0.95f, 0.0f, 0.6f),
    )

    /** Automatisch an? (Einstellung) */
    @Volatile var auto = true

    // ---- weiche Werte, die die Effekte lesen (alle 0..1, ausser wo vermerkt)
    @Volatile var staerke = 0.75f; private set
    @Volatile var ruck = 0.5f; private set
    @Volatile var abklingen = 0.86f; private set
    @Volatile var atmen = 0f; private set
    @Volatile var bassGewicht = 1f; private set
    @Volatile var bpm = 0f; private set
    /** 0..~1.5: kurz nach einem Drop gross, beim Aufbau langsam steigend */
    @Volatile var schub = 0f; private set
    @Volatile var energie = 0f; private set
    @Volatile var art = ""; private set
    @Volatile var zuletzt = 0L; private set

    // ---- Messung
    private val vorher = FloatArray(BEAT_STRICHE)
    private var eKurz = 0f; private var eLang = 0f; private var eSehrLang = 0f
    private var bassA = 1f; private var hell = 0f; private var dyn = 0f
    private var fluss = 0f; private var flussSchnitt = 0f
    private val schlaege = LongArray(24); private var sNr = 0; private var letzterSchlag = 0L
    private var cv = 0.5f
    private var pause = 0f             // wie lange es zuletzt deutlich leiser war (Aufbau / Break vor einem Drop)
    private var aufbau = 0f
    private var kandidat = ""; private var kandidatSeit = 0L; private var letzteWahl = 0L
    private var ziel = ARTEN.getValue("pop")

    @Synchronized
    fun futter(w: FloatArray) {
        val jetzt = System.currentTimeMillis()
        if (jetzt - zuletzt > 3000) zuruecksetzen()
        zuletzt = jetzt
        var e = 0f; var bass = 0f; var hoch = 0f; var fl = 0f
        for (i in 0 until BEAT_STRICHE) {
            e += w[i]
            if (i < 8) bass += w[i]
            if (i >= 30) hoch += w[i]
            fl += max(0f, w[i] - vorher[i]); vorher[i] = w[i]
        }
        e /= BEAT_STRICHE; bass /= 8f; hoch /= (BEAT_STRICHE - 30)
        val mitte = (e * BEAT_STRICHE - bass * 8f - hoch * (BEAT_STRICHE - 30)) / (30 - 8)
        eKurz += (e - eKurz) * 0.35f
        eLang += (e - eLang) * 0.02f            // ~2-3 s
        eSehrLang += (e - eSehrLang) * 0.005f   // ~10 s
        dyn += (abs(eKurz - eLang) - dyn) * 0.03f
        bassA += (bass / (mitte + 0.05f) - bassA) * 0.03f
        hell += (hoch - hell) * 0.03f
        energie = eLang

        // Schlaege (Spektral-Fluss deutlich ueber dem Schnitt)
        flussSchnitt += (fl - flussSchnitt) * 0.05f
        if (fl > flussSchnitt * 1.6f + 0.02f && jetzt - letzterSchlag > 200) {
            letzterSchlag = jetzt
            schlaege[sNr % schlaege.size] = jetzt; sNr++
            tempoRechnen()
        }

        // Aufbau / Break / Drop
        if (eKurz < eLang * 0.7f) pause = min(pause + 0.05f, 3f) else pause = max(0f, pause - 0.02f)
        val steigt = eKurz > eLang * 1.05f && eLang > eSehrLang
        aufbau = if (steigt) min(1f, aufbau + 0.01f) else max(0f, aufbau - 0.02f)
        if (pause > 0.6f && eKurz > eLang * 1.5f && eKurz > 0.2f) { schub = 1.5f; pause = 0f }   // DROP
        schub = max(schub * 0.97f, aufbau * 0.5f)

        // alle ~0,5 s: Art einschaetzen
        if (jetzt - letzteWahl > 500) { letzteWahl = jetzt; einschaetzen(jetzt) }

        // weich zur Reaktion der erkannten Art gleiten (~2 s)
        val z = if (auto) ziel else ARTEN.getValue("pop")
        val g = 0.03f
        // Staerke: Art + wie laut es gerade im Vergleich ist (leise Stellen weniger, laute mehr)
        val relativ = if (eSehrLang > 0.01f) (eKurz / eSehrLang).coerceIn(0.4f, 1.6f) else 1f
        val sZiel = if (auto) (z.staerke * (0.6f + 0.4f * relativ)).coerceIn(0.05f, 1.3f) else 1f
        staerke += (sZiel - staerke) * g
        ruck += ((if (auto) z.ruck else 0.6f) - ruck) * g
        abklingen += ((if (auto) z.abklingen else 0.86f) - abklingen) * g
        atmen += ((if (auto) z.atmen else 0f) - atmen) * g
        bassGewicht += ((if (auto) z.bass else 1f) - bassGewicht) * g
    }

    private fun tempoRechnen() {
        val n = min(sNr, schlaege.size)
        if (n < 6) return
        val t = (0 until n).map { schlaege[(sNr - 1 - it + schlaege.size * 4) % schlaege.size] }.sortedDescending()
        val abst = (0 until n - 1).map { (t[it] - t[it + 1]).toFloat() }.filter { it in 200f..2000f }
        if (abst.size < 4) return
        val sortiert = abst.sorted()
        val m = sortiert[sortiert.size / 2]
        // in den ueblichen Bereich falten (70..180 BPM)
        var b = 60000f / m
        while (b < 70f) b *= 2f
        while (b > 180f) b /= 2f
        bpm = if (bpm == 0f) b else bpm + (b - bpm) * 0.2f
        val schnitt = abst.average().toFloat()
        val sd = sqrt(abst.map { (it - schnitt) * (it - schnitt) }.average().toFloat())
        cv += ((sd / schnitt).coerceIn(0f, 2f) - cv) * 0.2f
    }

    private fun einschaetzen(jetzt: Long) {
        val e = eLang
        // Punkte je Art (weich, keine harten Grenzen)
        fun um(x: Float, mitte: Float, breite: Float) = max(0f, 1f - abs(x - mitte) / breite)
        val p = mapOf(
            "ruhig" to (if (e < 0.14f) 1.2f else um(e, 0.1f, 0.12f)) * (1f - dyn * 2f).coerceAtLeast(0.2f),
            "sprache" to (if (cv > 0.55f) 1f else 0.2f) * um(bassA, 0.5f, 0.5f) * um(e, 0.25f, 0.2f) * (if (bpm == 0f || cv > 0.6f) 1.1f else 0.6f),
            "chill" to um(e, 0.25f, 0.15f) * (if (bpm in 1f..105f) 1.2f else 0.6f) * (1f - cv).coerceAtLeast(0.3f),
            "pop" to um(e, 0.4f, 0.18f) * (if (bpm in 95f..135f) 1.1f else 0.7f) * (1f - cv).coerceAtLeast(0.3f),
            "rock" to (if (e > 0.42f) 1f else um(e, 0.5f, 0.15f)) * um(hell, 0.45f, 0.3f) * (if (bassA in 0.7f..1.5f) 1.1f else 0.6f),
            "elektro" to (if (bassA > 1.3f) 1.2f else um(bassA, 1.4f, 0.5f)) * (if (bpm in 115f..155f) 1.2f else 0.7f) * (1f - cv).coerceAtLeast(0.3f) * (0.5f + e),
            "klassik" to um(e, 0.28f, 0.2f) * (dyn * 6f).coerceIn(0f, 1.2f) * (if (bassA < 0.95f) 1f else 0.5f) * (if (cv > 0.35f) 1f else 0.5f),
        )
        val bester = p.maxByOrNull { it.value }?.key ?: return
        if (art.isEmpty()) { art = bester; ziel = ARTEN.getValue(bester); return }
        if (bester == art) { kandidat = ""; return }
        if (bester != kandidat) { kandidat = bester; kandidatSeit = jetzt; return }
        // erst wechseln, wenn die neue Art ~3 s lang vorne liegt
        if (jetzt - kandidatSeit > 3000) { art = bester; ziel = ARTEN.getValue(bester); kandidat = "" }
    }

    private fun zuruecksetzen() {
        eKurz = 0f; eLang = 0f; eSehrLang = 0f; dyn = 0f; bassA = 1f; hell = 0f; fluss = 0f; flussSchnitt = 0f
        sNr = 0; bpm = 0f; cv = 0.5f; pause = 0f; aufbau = 0f; schub = 0f; art = ""; kandidat = ""
        vorher.fill(0f)
    }

    /** laeuft gerade eine Einschaetzung? */
    fun aktiv() = System.currentTimeMillis() - zuletzt < 1500

    /** fuer die Einstellungen: "Erkannt: Rock · ~128 BPM · viel Bass" */
    fun text(): String {
        if (!aktiv()) return "Erkennt die Musik, sobald etwas läuft"
        if (art.isEmpty()) return "Hört zu …"
        val r = ARTEN[art]?.name ?: art
        val teile = mutableListOf(Sprache.tr("Erkannt") + ": " + Sprache.tr(r))
        if (bpm > 0f && art != "sprache") teile += "~${bpm.toInt()} BPM"
        teile += Sprache.tr(when { bassA > 1.3f -> "viel Bass"; bassA < 0.7f -> "wenig Bass"; else -> "ausgewogen" })
        if (schub > 1f) teile += Sprache.tr("Drop!") else if (aufbau > 0.5f) teile += Sprache.tr("baut sich auf")
        return teile.joinToString(" · ")
    }
}
