package de.atelierlauncher.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// ============================================================================
//  Designs zum Nachladen (nur Atelier): ab Werk gibt es nur "Schwarz & Weiß" (mit App-Icons in
//  Originalfarbe). Alle anderen Stile laedt man einmal unter Design → Themes → "Weitere Designs".
//  Ein Design bringt alles mit, was zu ihm gehoert: Themes, Farbschema, Hintergrund, Schrift,
//  Icon-Stil, Randgeste, Sperr-Uebergang und Lautstaerke-Regler. Vorher tauchen diese Teile nirgends auf.
//  Greenback (Banknote) und Liquid Glass gibt es in Atelier gar nicht mehr.
// ============================================================================
object Designs {
    /** ein ladbares Design: Name, Beschreibung, seine Themes und welche Teile es freischaltet (Art -> Werte) */
    data class Paket(val id: String, val name: String, val text: String, val themen: List<String>, val teile: Map<String, Set<String>>)

    /** immer da (ab Werk) */
    const val GRUND = "Schwarz & Weiß"

    val PAKETE = listOf(
        Paket("wellen", "Wellen",
              "Weiche Wellen im Hintergrund und am Alphabet – dazu Wellen als Randgeste, beim Sperrbildschirm und am Lautstärke-Regler. Mit Umriss-Icons.",
              listOf("Nur Wellen", "Wellen Nacht", "Wellen Tag"),
              mapOf("hintergrund" to setOf("wellen"), "icons" to setOf("umriss"),      // Wellen-Alphabet ist immer frei
                    "laut" to setOf("welle"), "randgeste" to setOf("gross"), "sperr" to setOf("welle"))),
        Paket("swiss", "Swiss",
              "Strenges Raster, Grotesk-Schrift und ein kräftiges Rot – mit eigenen eckigen Piktogrammen als Icons.",
              listOf("Nur Swiss-Kanten", "Swiss Papier"),
              mapOf("farben" to setOf("kontrast"), "hintergrund" to setOf("swiss"), "icons" to setOf("swiss"), "typo" to setOf("swiss"),
                    "liste" to setOf("einsatz"), "buchstabe" to setOf("karte"), "laut" to setOf("swiss"),
                    "randgeste" to setOf("grossswiss", "grossfelder", "kante"), "sperr" to setOf("raster", "swiss"), "daumen" to setOf("kante"))),
        Paket("gezeichnet", "Gezeichnet",
              "Blauer Kuli auf Papier – oder weiße Kreide auf der Tafel. Nichts ist ganz gerade. Mit handgezeichneten Icons und Handschrift.",
              listOf("Gezeichnet", "Kreidetafel"),
              mapOf("farben" to setOf("skizze"), "hintergrund" to setOf("papier"), "icons" to setOf("skizze"), "schrift" to setOf("hand"),
                    "laut" to setOf("skizze"), "randgeste" to setOf("skizze"), "sperr" to setOf("skizze"))),
        Paket("deko", "Art Déco",
              "Schwarzer Marmor, Gold und Elfenbein – Risse werden mit Gold gekittet. Mit Kintsugi-Icons und Art-Déco-Schrift.",
              listOf("Art Déco"),
              mapOf("farben" to setOf("deko"), "hintergrund" to setOf("marmor"), "icons" to setOf("kintsugi"), "schrift" to setOf("deko"),
                    "laut" to setOf("riss"), "randgeste" to setOf("riss"), "sperr" to setOf("riss"))),
        Paket("minimal", "Minimal",
              "Schwarzer Grund, kleine Uhr, keine Icons – nur die Namen der Apps.",
              listOf("Minimal"), emptyMap()),
    )

    /** in Atelier ganz entfernt */
    private val RAUS = mapOf(
        "farben" to setOf("geld", "glas"), "hintergrund" to setOf("geld", "glas"), "typo" to setOf("geld"),
        "randgeste" to setOf("grossgeld"), "sperr" to setOf("geld", "glas"), "laut" to setOf("geld", "liquid"), "daumen" to setOf("rosette"),
    )
    private val THEMEN_RAUS = setOf("Greenback", "Liquid Glass")

    /** geladene Designs (ids) - Compose-State, damit die Listen sofort neu gezeichnet werden */
    var geladen by mutableStateOf<Set<String>>(emptySet())
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun geladenSetzen(ctx: Context, id: String, an: Boolean) {
        geladen = if (an) geladen + id else geladen - id
        prefs(ctx).edit().putStringSet("designsGeladen", HashSet(geladen)).apply()
    }

    fun raus(art: String, wert: String) = RAUS[art]?.contains(wert) == true

    /** darf dieser Wert (z. B. Farbschema "skizze") gerade ausgewaehlt werden? */
    fun frei(art: String, wert: String): Boolean {
        if (raus(art, wert)) return false
        val besitzer = PAKETE.filter { wert in it.teile[art].orEmpty() }
        return besitzer.isEmpty() || besitzer.any { it.id in geladen }
    }

    /** Auswahl-Liste kuerzen: nur freie Werte - der gerade eingestellte bleibt sichtbar (sonst waere die Wahl leer) */
    fun <T> nurFrei(art: String, liste: List<Pair<String, T>>, aktuell: String? = null): List<Pair<String, T>> =
        liste.filter { (w, _) -> frei(art, w) || (w == aktuell && !raus(art, w)) }

    fun themaFrei(name: String): Boolean {
        if (name in THEMEN_RAUS) return false
        if (name == GRUND) return true
        val besitzer = PAKETE.filter { name in it.themen }
        return besitzer.isEmpty() || besitzer.any { it.id in geladen }
    }

    /** Paket eines Themes (null = Grund-Design oder eigenes Theme) */
    fun paketVon(themaName: String) = PAKETE.firstOrNull { themaName in it.themen }

    fun grundThema(): Thema = FERTIGE_THEMEN.first { it.name == GRUND }

    /** entfernte Teile aus einem Theme nehmen (Theme-Codes, eigene Themes, alte Einstellungen) */
    fun bereinigen(t: Thema): Thema {
        if (t.name in THEMEN_RAUS || raus("farben", t.farben)) return grundThema()
        return t.copy(
            hintergrund = if (raus("hintergrund", t.hintergrund)) "schwarz" else t.hintergrund,
            typo = if (raus("typo", t.typo)) "schlicht" else t.typo,
        )
    }

    fun bereinigen(pk: ThemaPaket): ThemaPaket {
        return pk.copy(
            randgeste = if (raus("randgeste", pk.randgeste)) "strich" else pk.randgeste,
            sperr = if (raus("sperr", pk.sperr)) "karte" else pk.sperr,
            laut = if (raus("laut", pk.laut)) "sw" else pk.laut,
            rosette = false,
        )
    }

    /**
     * Beim Start (nur Atelier):
     *  - neu installiert: "Schwarz & Weiß" einstellen
     *  - schon benutzt (Update): Designs, die gerade benutzt werden, gelten als geladen - nichts verschwindet
     *  - Reste von Greenback / Liquid Glass entfernen
     */
    fun start(ctx: Context, z: Zustand) {
        val p = prefs(ctx)
        if (!p.contains("designsGeladen")) {
            val schonBenutzt = p.contains("thema")
            val ids = HashSet<String>()
            if (schonBenutzt) {
                val t = z.thema
                val werte = mapOf("farben" to t.farben, "hintergrund" to t.hintergrund, "icons" to t.icons, "schrift" to t.schrift,
                                  "alphabet" to t.alphabet, "typo" to t.typo, "liste" to t.liste, "buchstabe" to t.buchstabe,
                                  "laut" to LautDesign.art(ctx), "randgeste" to Randgesten.stil(p), "sperr" to SperrSeite.art(ctx))
                PAKETE.forEach { pk ->
                    if (t.name in pk.themen || werte.any { (art, w) -> w in pk.teile[art].orEmpty() }) ids += pk.id
                }
                // eigene Themes, die Teile eines Designs benutzen, bleiben auswaehlbar
                z.eigeneThemen.forEach { e ->
                    PAKETE.forEach { pk -> if (e.farben in pk.teile["farben"].orEmpty() || e.hintergrund in pk.teile["hintergrund"].orEmpty()) ids += pk.id }
                }
            }
            geladen = ids
            p.edit().putStringSet("designsGeladen", ids).apply()
            if (!schonBenutzt) { z.themaStill(grundThema()); return }
        } else geladen = p.getStringSet("designsGeladen", emptySet())?.toSet() ?: emptySet()

        // Reste der entfernten Designs
        val t = z.thema
        val sauber = bereinigen(t)
        if (sauber != t) z.themaStill(sauber)
        if (raus("laut", LautDesign.art(ctx))) p.edit().putString("lautDesign", "sw").apply()
        if (raus("sperr", SperrSeite.art(ctx))) SperrSeite.artSetzen(ctx, "karte")
        if (raus("randgeste", Randgesten.stil(p))) Randgesten.stilSetzen(ctx, "strich")
        if (z.alphabetRosette) z.wellenSetzen("alphabetRosette", false)
    }
}
