package de.atelierlauncher.app

import android.content.Context
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.json.JSONArray
import org.json.JSONObject

val LocalZustand = staticCompositionLocalOf<Zustand> { error("Kein Zustand") }

/**
 * Alles, was der Launcher sich merkt. Jede Aenderung wird sofort gespeichert.
 * Die Oberflaeche liest die Felder direkt (Compose-State) und ruft die Funktionen auf.
 */
class Zustand(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val appCtx: Context = ctx.applicationContext

    /** Verbindung zur Activity (Apps starten, Icons, Erlaubnisse) - wird von MainActivity gesetzt */
    var aktivitaet: MainActivity? = null

    // ---------------------------------------------------------------- Daten
    var rohApps by mutableStateOf<List<AppEintrag>>(emptyList())        // wie von Android geliefert
    var favoriten by mutableStateOf(liesListe("favoriten"))              // App-Schluessel
    var versteckt by mutableStateOf(liesListe("versteckt").toSet())
    var namen by mutableStateOf(liesMap("namen"))                        // eigene App-Namen
    var eigeneIcons by mutableStateOf(liesMap("eigeneIcons"))            // App -> "paket:<pkg>:<drawable>"
    var buendel by mutableStateOf(liesListenMap("buendel"))              // App -> verwandte Apps im Pop-up
    var thema by mutableStateOf(liesThema())
    var eigeneThemen by mutableStateOf(liesJsonListe("eigeneThemen").map { themaAus(it) })
    var kopf by mutableStateOf(liesKopf())
    var ort by mutableStateOf(liesOrt())
    var taskleiste by mutableStateOf(prefs.getString("taskleiste", "normal") ?: "normal")      // "normal" (Samsung-Tasten) oder "unsichtbar"
    var heim by mutableIntStateOf(0)                                     // Home-Taste gedrueckt
    var musterBereitBis = 0L
    // Wellen-Effekte (in den Einstellungen an/aus)
    var wellenDaumen by mutableStateOf(prefs.getBoolean("wellenDaumen", true))   // Wellen folgen dem Daumen
    var wellenAkku by mutableStateOf(prefs.getBoolean("wellenAkku", true))       // Wellen-Weite = Akkustand
    var wellenTropfen by mutableStateOf(prefs.getBoolean("wellenTropfen", false)) // Kreise beim Wischen
    var favSofort by mutableStateOf(prefs.getBoolean("favSofort", true))         // Favoriten oeffnen beim Antippen
    var alphabetKante by mutableStateOf(prefs.getBoolean("alphabetKante", false)) // Swiss-Kante am Daumen
    var alphabetRosette by mutableStateOf(prefs.getBoolean("alphabetRosette", false)) // Banknoten-Rosette am Daumen
    /** eigene Ebene am Daumen (Kante oder Rosette) statt Wellen - nie beides */
    val eigeneDaumenEbene get() = alphabetKante || alphabetRosette
    fun wellenSetzen(schluessel: String, an: Boolean) {
        when (schluessel) {
            "wellenDaumen" -> wellenDaumen = an
            "wellenAkku" -> wellenAkku = an
            "wellenTropfen" -> wellenTropfen = an
            "favSofort" -> favSofort = an
            "alphabetKante" -> alphabetKante = an
            "alphabetRosette" -> alphabetRosette = an
        }
        prefs.edit().putBoolean(schluessel, an).apply()
    }
    var eisFarbe by mutableIntStateOf(prefs.getInt("eisFarbe", 0xFFA8E6FF.toInt()))   // Farbe der Eis-Zacken
    var pixelFarbe by mutableIntStateOf(prefs.getInt("pixelFarbe", 0xFF4FE3FF.toInt()))   // Farbe der Pixel
    fun pixelFarbeSetzen(f: Int) { pixelFarbe = f; prefs.edit().putInt("pixelFarbe", f).apply() }
    var eigenStand by mutableIntStateOf(0)                                         // hochzaehlen = eigene Datei/Code neu laden
    var eisSchnee by mutableStateOf(prefs.getBoolean("eisSchnee", true))               // Schnee beim Eis-Design
    fun eisSchneeSetzen(an: Boolean) { eisSchnee = an; prefs.edit().putBoolean("eisSchnee", an).apply() }
    fun eisFarbeSetzen(f: Int) { eisFarbe = f; prefs.edit().putInt("eisFarbe", f).apply() }
    var musikZeile by mutableStateOf(prefs.getBoolean("musikZeile", false))      // Musik-Zeile unter der Uhr
    var linksWeg by mutableIntStateOf(prefs.getInt("linksWeg", 40))
    fun musikZeileSetzen(an: Boolean) { musikZeile = an; prefs.edit().putBoolean("musikZeile", an).apply() }
    fun linksWegSetzen(dp: Int) { linksWeg = dp; prefs.edit().putInt("linksWeg", dp).apply() }
    // Groesse des Alphabets in Prozent (Schrift und Breite der Leiste)
    var alphabetGroesse by mutableIntStateOf(prefs.getInt("alphabetGroesse", 100))
    fun alphabetGroesseSetzen(prozent: Int) { alphabetGroesse = prozent; prefs.edit().putInt("alphabetGroesse", prozent).apply() }
    // Leiste: Hoehe (% der freien Hoehe, unten verankert), Dicke (dp), Abstand nach unten (dp)
    // Leiste zieht mit, wenn der Daumen ueber das obere Ende hinausgeht
    // Klack-Geraeusch beim Wechsel des Buchstabens
    var alphabetKlick by mutableStateOf(prefs.getBoolean("alphabetKlick", true))
    fun alphabetKlickSetzen(an: Boolean) { alphabetKlick = an; prefs.edit().putBoolean("alphabetKlick", an).apply() }
    /** Musik-Kenner: erkennt die Musik und waehlt die Reaktion (Beat-Ring, Alphabet) */
    var musikAuto by mutableStateOf(prefs.getBoolean("musikAuto", true))
    fun musikAutoSetzen(an: Boolean) { musikAuto = an; prefs.edit().putBoolean("musikAuto", an).apply() }
    /** Benachrichtigung = kleine Welle von oben in der Farbe der App */
    var benWelle by mutableStateOf(prefs.getBoolean("benWelle", true))
    var benWelleFarbe by mutableStateOf(prefs.getBoolean("benWelleFarbe", true))
    var benWelleTiefe by mutableIntStateOf(prefs.getInt("benWelleTiefe", 36))
    var benWelleDauer by mutableIntStateOf(prefs.getInt("benWelleDauer", 2400))
    fun benWelleSetzen(an: Boolean? = null, farbe: Boolean? = null, tiefe: Int? = null, dauer: Int? = null) {
        val e = prefs.edit()
        an?.let { benWelle = it; e.putBoolean("benWelle", it) }
        farbe?.let { benWelleFarbe = it; e.putBoolean("benWelleFarbe", it) }
        tiefe?.let { benWelleTiefe = it; e.putInt("benWelleTiefe", it) }
        dauer?.let { benWelleDauer = it; e.putInt("benWelleDauer", it) }
        e.apply()
    }
    /** Welle hinter dem Alphabet aus Milchglas (Startbildschirm darunter unscharf) */
    var welleGlas by mutableStateOf(prefs.getBoolean("welleGlas", false))
    var welleGlasBlur by mutableIntStateOf(prefs.getInt("welleGlasBlur", 20))
    fun welleGlasSetzen(an: Boolean? = null, blur: Int? = null) {
        an?.let { welleGlas = it; prefs.edit().putBoolean("welleGlas", it).apply() }
        blur?.let { welleGlasBlur = it; prefs.edit().putInt("welleGlasBlur", it).apply() }
    }
    /** hinter dem Alphabet bewegt sich mit der Musik (echter Takt) */
    var abcMusik by mutableStateOf(prefs.getBoolean("abcMusik", false))
    var abcMusikStaerke by mutableIntStateOf(prefs.getInt("abcMusikStaerke", 100))
    fun abcMusikSetzen(an: Boolean? = null, staerke: Int? = null) {
        an?.let { abcMusik = it; prefs.edit().putBoolean("abcMusik", it).apply() }
        staerke?.let { abcMusikStaerke = it; prefs.edit().putInt("abcMusikStaerke", it).apply() }
    }
    /** was hinter dem Alphabet zu sehen ist: "" wie im Design, "welle" (folgt dem Daumen), "kante", "aus" */
    var abcHinter by mutableStateOf(prefs.getString("abcHinter", "") ?: "")
    fun abcHinterSetzen(w: String) {
        abcHinter = w; prefs.edit().putString("abcHinter", w).apply()
        wellenSetzen("alphabetKante", w == "kante")
        if (w != "") wellenSetzen("alphabetRosette", w == "rosette")
        if (w == "welle") wellenSetzen("wellenDaumen", true)
        if (w == "kreise") wellenSetzen("wellenTropfen", true)
        if (w == "aus") wellenSetzen("wellenTropfen", false)
    }
    /** anderer Ton bei bestimmten Buchstaben (oder #) - Baukasten → Alphabet → ✎ */
    var alphabetTonBei by mutableStateOf(prefs.getStringSet("alphabetTonBei", emptySet())?.toSet() ?: emptySet())
    var alphabetTonArt by mutableStateOf(prefs.getString("alphabetTonArt", "glocke") ?: "glocke")
    fun alphabetTonSetzen(bei: Set<String>? = null, art: String? = null) {
        bei?.let { alphabetTonBei = it }; art?.let { alphabetTonArt = it }
        prefs.edit().putStringSet("alphabetTonBei", HashSet(alphabetTonBei)).putString("alphabetTonArt", alphabetTonArt).apply()
    }
    // Statusleiste ganz oben (Uhrzeit, Akku, Symbole von Android) ausblenden - von oben wischen holt sie kurz zurueck
    var statusAus by mutableStateOf(prefs.getBoolean("statusAus", true))
    /** Buchstaben-Seite: kuerzester App-Name dort, wo der Daumen ist, nach aussen immer laenger (ab Werk an) */
    var abcLaenge by mutableStateOf(prefs.getBoolean("abcLaenge", true))
    fun abcLaengeSetzen(an: Boolean) { abcLaenge = an; prefs.edit().putBoolean("abcLaenge", an).apply() }
    /** nach unten wischen klappt Samsungs Benachrichtigungen auf (ab Werk aus) */
    var runterBenachr by mutableStateOf(prefs.getBoolean("runterBenachr", false))
    fun runterBenachrSetzen(an: Boolean) { runterBenachr = an; prefs.edit().putBoolean("runterBenachr", an).apply() }
    fun statusAusSetzen(an: Boolean) { statusAus = an; prefs.edit().putBoolean("statusAus", an).apply() }
    // erster Start (Atelier): Einfuehrung gesehen?
    var einfuehrungFertig by mutableStateOf(prefs.getBoolean("einfuehrungFertig", false))
    fun einfuehrungSetzen(fertig: Boolean) { einfuehrungFertig = fertig; prefs.edit().putBoolean("einfuehrungFertig", fertig).apply() }
    var alphabetMitziehen by mutableStateOf(prefs.getBoolean("alphabetMitziehen", true))
    fun alphabetMitziehenSetzen(an: Boolean) { alphabetMitziehen = an; prefs.edit().putBoolean("alphabetMitziehen", an).apply() }
    var alphabetHoehe by mutableIntStateOf(prefs.getInt("alphabetHoehe", 100))
    var alphabetDicke by mutableIntStateOf(prefs.getInt("alphabetDicke", 36))
    var alphabetUnten by mutableIntStateOf(prefs.getInt("alphabetUnten", 24))
    fun alphabetLeisteSetzen(hoehe: Int = alphabetHoehe, dicke: Int = alphabetDicke, unten: Int = alphabetUnten) {
        alphabetHoehe = hoehe; alphabetDicke = dicke; alphabetUnten = unten
        prefs.edit().putInt("alphabetHoehe", hoehe).putInt("alphabetDicke", dicke).putInt("alphabetUnten", unten).apply()
    }
    // Spiegeln im Liegen (auf der rechten Seite): Alphabet links, alles gespiegelt -> mit links bedienbar
    var spiegelnAn by mutableStateOf(prefs.getBoolean("spiegelnAn", true))
    var gespiegelt by mutableStateOf(false)                                       // auf der rechten Seite liegend (Lagesensor)
    var lageWinkel by mutableIntStateOf(0)                                        // fuer die Anzeige in den Einstellungen
    var linksLiegend by mutableStateOf(false)                                     // auf der linken Seite liegend -> Inhalt rueckt nach rechts
    fun spiegelnSetzen(an: Boolean) { spiegelnAn = an; if (!an) { gespiegelt = false; linksLiegend = false }; prefs.edit().putBoolean("spiegelnAn", an).apply() }
    init {
    }
    // oeffentliche Version: kein fertiger Code - den legt jeder selbst fest
    var codeStart by mutableStateOf(prefs.getString("codeStart", null) ?: (""))
    var codeGesetzt by mutableStateOf(prefs.getBoolean("codeGesetzt", false))   // Alphabet-Code: Startbuchstaben
    var codeEnde by mutableStateOf(prefs.getString("codeEnde", "#") ?: "#")               // Alphabet-Code: Ziel
    var privatAn by mutableStateOf(prefs.getBoolean("privatAn", false))
    fun privatAnSetzen(an: Boolean) { privatAn = an; prefs.edit().putBoolean("privatAn", an).apply() }
    // unsichtbarer Knopf vor dem Code: "uhr" = neben der Uhr, "eigen" = selbst platziert, "aus" = kein Knopf
    var geheimOrt by mutableStateOf(prefs.getString("geheimOrt", "uhr") ?: "uhr")
    fun geheimOrtSetzen(o: String) { geheimOrt = o; prefs.edit().putString("geheimOrt", o).apply() }
    // selbst platzierter Knopf: x, y, Breite, Hoehe als Anteil vom Bildschirm (0..1)
    var geheimRect by mutableStateOf(rechteckLesen(prefs.getString("geheimRect", null)))
    fun geheimRectSetzen(r: List<Float>) { geheimRect = r; prefs.edit().putString("geheimRect", r.joinToString(",")).apply() }
    private fun rechteckLesen(t: String?): List<Float> =
        t?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 } ?: listOf(0.62f, 0.06f, 0.25f, 0.08f)
    var musterTipp by mutableIntStateOf(0)                              // zaehlt Antippen (startet den 8-s-Punkt neu)
    /** von aussen gewuenschte Einstellungs-Seite (z. B. vom Sperrbildschirm: "rasterSperre") */
    var oeffnenWunsch by mutableStateOf<String?>(null)
    var musterPunkt by mutableStateOf(false)                             // Punkt im Zahnrad, solange das Muster "scharf" ist
    var privatSperrZaehler by mutableIntStateOf(0)
    var fortsetzen by mutableIntStateOf(0)                               // Launcher wieder sichtbar (Rechte neu pruefen)

    /** Angezeigte Apps: umbenannt und sortiert (wird nur neu berechnet, wenn sich etwas aendert) */
    val apps: List<AppEintrag> by derivedStateOf {
        rohApps.map { a -> namen[a.schluessel]?.let { a.copy(name = it) } ?: a }
            .sortedWith(compareBy({ sortierBuchstabe(it.buchstabe) }, { it.name.lowercase() }))
    }
    private val appIndex: Map<String, AppEintrag> by derivedStateOf { apps.associateBy { it.schluessel } }

    fun app(key: String): AppEintrag? = appIndex[key]

    // ---------------------------------------------------------------- Favoriten
    fun istFavorit(key: String) = key in favoriten

    /** wie viele Favoriten es hoechstens geben kann (Atelier: einstellbar im Favoriten-Baustein, 1-20) */
    var favMax by mutableIntStateOf(prefs.getInt("favMax", MAX_FAVORITEN))
    fun favMaxSetzen(n: Int) { favMax = n.coerceIn(1, 20); prefs.edit().putInt("favMax", favMax).apply() }

    fun favoritUmschalten(key: String) {
        val neu = favoriten.toMutableList()
        if (!neu.remove(key)) {
            if (neu.size >= favMax) return
            neu.add(key)
        }
        favoriten = neu; schreibe("favoriten", neu)
    }

    fun favoritenSetzen(neu: List<String>) {
        val l = neu.distinct().take(maxOf(favMax, MAX_FAVORITEN))
        favoriten = l; schreibe("favoriten", l)
    }

    fun favoritVerschieben(key: String, richtung: Int) {
        val neu = favoriten.toMutableList()
        val i = neu.indexOf(key); val j = i + richtung
        if (i < 0 || j !in neu.indices) return
        neu[i] = neu[j].also { neu[j] = neu[i] }
        favoriten = neu; schreibe("favoriten", neu)
    }

    /** Nach dem Laden: Eintraege von deinstallierten Apps entfernen */
    fun aufraeumen(gueltig: Set<String>) {
        val f = favoriten.filter { it in gueltig }
        if (f.size != favoriten.size) { favoriten = f; schreibe("favoriten", f) }
        val b = buendel.mapValues { (_, v) -> v.filter { it in gueltig } }.filter { (k, v) -> k in gueltig && v.isNotEmpty() }
        if (b != buendel) { buendel = b; schreibeListenMap("buendel", b) }
    }

    // ---------------------------------------------------------------- Apps anpassen
    fun verstecken(key: String) {
        val neu = versteckt.toMutableSet()
        if (!neu.remove(key)) neu.add(key)
        versteckteSetzen(neu)
    }

    fun versteckteSetzen(neu: Set<String>) {
        versteckt = neu; schreibe("versteckt", neu)
        if (favoriten.any { it in neu }) favoritenSetzen(favoriten.filter { it !in neu })
    }


    fun umbenennen(key: String, name: String?) {
        val neu = namen.toMutableMap()
        if (name.isNullOrBlank()) neu.remove(key) else neu[key] = name.trim()
        namen = neu; schreibeMap("namen", neu)
    }

    fun eigenesIcon(key: String, quelle: String?) {
        val neu = eigeneIcons.toMutableMap()
        if (quelle == null) neu.remove(key) else neu[key] = quelle
        eigeneIcons = neu; schreibeMap("eigeneIcons", neu)
        aktivitaet?.iconsNeuLaden(setOf(key))
    }

    fun buendelSetzen(key: String, apps: List<String>) {
        val neu = buendel.toMutableMap()
        if (apps.isEmpty()) neu.remove(key) else neu[key] = apps
        buendel = neu; schreibeListenMap("buendel", neu)
    }

    // ---------------------------------------------------------------- Aussehen
    fun themaSetzen(t0: Thema) {
        // Atelier: Teile entfernter Designs (Banknote, Liquid Glass) gar nicht erst setzen
        val t = Designs.bereinigen(t0)
        val iconsAnders = t.icons != thema.icons
        // zum Swiss-Look wechseln: Randgesten bekommen die passende harte "Kante"
        // ganze Swiss-Vorlage gewaehlt (nicht nur ein Teil im Baukasten): Randgesten passend dazu
        if (t.name != thema.name && t.farben == "kontrast" && t.alphabet == "swiss") {
            // nur die kleine Welle umstellen - wer "Aus" oder eine grosse Kante gewaehlt hat, behaelt sie
            val alt = Randgesten.stil(prefs)
            if (alt != "aus" && !alt.startsWith("gross")) {
                prefs.edit().putString("welleStil", "kante").apply()
                Randgesten.tropfen = false; Randgesten.kante = true; Randgesten.pfeil = false; Randgesten.gross = null
            }
        }
        // fertiges Paket-Theme gewaehlt: auch Randgesten, Sperr-Uebergang, Lautstaerke und Alphabet-Hintergrund passend
        // eigenes gespeichertes Theme: dasselbe mit dem, was beim Speichern eingestellt war
        if (t.name != thema.name) (THEMA_PAKETE[t.name] ?: themaPaketAus(t.paket))?.let { paketAnwenden(it) }
        thema = t
        prefs.edit().putString("thema", t.json().toString()).apply()
        if (iconsAnders) aktivitaet?.iconsNeuLaden(null)
        aktivitaet?.let { SperrBild.aktualisieren(it) }
    }

    fun uhrSetzen(u: UhrStil) = themaSetzen(thema.copy(uhr = u))

    /** Theme beim Start setzen (erster Start, Aufraeumen) - ohne Icons/Sperrbild neu zu machen */
    fun themaStill(t: Thema) {
        if (t.name != thema.name) (THEMA_PAKETE[t.name] ?: themaPaketAus(t.paket))?.let { paketAnwenden(it) }
        thema = t
        prefs.edit().putString("thema", t.json().toString()).apply()
    }

    private fun paketAnwenden(pk0: ThemaPaket) {
        val pk = Designs.bereinigen(pk0)
        Randgesten.stilSetzen(appCtx, pk.randgeste)
        SperrSeite.artSetzen(appCtx, pk.sperr)
        prefs.edit().putString("lautDesign", pk.laut).apply()
        wellenSetzen("alphabetKante", pk.kante)
        wellenSetzen("alphabetRosette", pk.rosette)
        wellenSetzen("wellenDaumen", pk.daumen)
        wellenSetzen("wellenTropfen", pk.kreise)
        pk.akku?.let { wellenSetzen("wellenAkku", it) }
        pk.eis?.let { eisFarbeSetzen(it) }
        pk.pixel?.let { pixelFarbeSetzen(it) }
        pk.schnee?.let { eisSchneeSetzen(it) }
    }

    /** was gerade ausserhalb des Themes eingestellt ist (kommt beim Speichern mit ins Theme) */
    private fun paketJetzt() = ThemaPaket(
        randgeste = Randgesten.stil(prefs), sperr = SperrSeite.art(appCtx), laut = LautDesign.art(appCtx),
        kante = alphabetKante, daumen = wellenDaumen, kreise = wellenTropfen, rosette = alphabetRosette,
        akku = wellenAkku, eis = eisFarbe, pixel = pixelFarbe, schnee = eisSchnee,
    )

    fun themaSpeichern(name: String) {
        val t = thema.copy(name = name.ifBlank { "Mein Theme" }, paket = paketJetzt().json())
        eigeneThemen = eigeneThemen.filter { it.name != t.name } + t
        thema = t
        prefs.edit()
            .putString("thema", t.json().toString())
            .putString("eigeneThemen", JSONArray(eigeneThemen.map { it.json() }).toString())
            .apply()
    }

    fun themaLoeschen(t: Thema) {
        eigeneThemen = eigeneThemen.filter { it.name != t.name }
        prefs.edit().putString("eigeneThemen", JSONArray(eigeneThemen.map { it.json() }).toString()).apply()
    }

    fun kopfSetzen(k: KopfOptionen) {
        kopf = k
        prefs.edit().putString("kopf", JSONObject().put("termin", k.termin).put("wetter", k.wetter)
            .put("musik", k.musik).toString()).apply()
    }

    fun taskleisteSetzen(art: String) {
        taskleiste = art
        prefs.edit().putString("taskleiste", art).apply()
    }

    /** Buchstaben vereinheitlichen: gross, nur Alphabet-Zeichen, jeder einmal, sortiert */
    private fun codeBuchstaben(t: String) =
        t.uppercase().filter { it in 'A'..'Z' || it == '#' || it == '<' }.toSet().sorted().joinToString("")

    /** Alphabet-Code aendern - nur wenn der alte Code stimmt */
    fun codePruefen(altStart: String, altEnde: String): Boolean =
        !codeGesetzt || (codeBuchstaben(altStart) == codeBuchstaben(codeStart) && codeBuchstaben(altEnde) == codeBuchstaben(codeEnde))

    /** Code als Bereich: erster und letzter Startbuchstabe (die Buchstaben dazwischen gelten alle) */
    private fun codeVonBis(): Pair<String, String>? =
        codeStart.filter { it in 'A'..'Z' }.takeIf { it.isNotEmpty() }?.let { it.minOrNull()!!.toString() to it.maxOrNull()!!.toString() }

    fun codePruefenBereich(von: String, bis: String): Boolean = !codeGesetzt || codeVonBis() == (von to bis)

    /** neuer Code: auf einem Buchstaben von ... bis ... anfangen, hoch zu # ziehen */
    fun codeBereichSetzen(von: String, bis: String, verfuegbar: Set<String>): String? {
        val a = von.firstOrNull() ?: return "„Von“ wählen"
        val b = bis.firstOrNull() ?: return "„Bis“ wählen"
        if (a > b) return "„Von“ muss im Alphabet vor „Bis“ kommen"
        val s = (a..b).joinToString("")
        // nur Buchstaben, die im Alphabet wirklich vorkommen - sonst sperrt man sich aus
        if (s.none { it.toString() in verfuegbar }) return "Zwischen „Von“ und „Bis“ steht kein Buchstabe im Alphabet"
        codeStart = s; codeEnde = "#"; codeGesetzt = true
        prefs.edit().putString("codeStart", s).putString("codeEnde", "#").putBoolean("codeGesetzt", true).apply()
        privatAnSetzen(true)
        return null
    }

    fun codeAendern(altStart: String, altEnde: String, neuStart: String, neuEnde: String, verfuegbar: Set<String>): String? {
        if (!codePruefen(altStart, altEnde)) return "Alter Code stimmt nicht"
        val s = codeBuchstaben(neuStart)
        val e = codeBuchstaben(neuEnde)
        if (s.isEmpty()) return "Mindestens ein Startbuchstabe"
        if (e.length != 1) return "Genau ein Ziel (z. B. #)"
        if (e in s) return "Ziel darf kein Startbuchstabe sein"
        // nur Buchstaben, die im Alphabet wirklich vorkommen - sonst sperrt man sich aus
        if (s.none { it.toString() in verfuegbar }) return "Keiner der Startbuchstaben steht im Alphabet"
        if (e !in verfuegbar) return "Das Ziel steht nicht im Alphabet"
        codeStart = s; codeEnde = e; codeGesetzt = true
        prefs.edit().putString("codeStart", s).putString("codeEnde", e).putBoolean("codeGesetzt", true).apply()
        return null
    }


    fun ortSetzen(o: Ort?) {
        ort = o
        if (o == null) prefs.edit().remove("ort").apply()
        else prefs.edit().putString("ort", JSONObject().put("name", o.name).put("lat", o.lat).put("lon", o.lon).toString()).apply()
        Wetter.erzwingen()
    }

    // ---------------------------------------------------------------- Lesen/Schreiben
    private fun liesListe(n: String) = prefs.getString(n, "").orEmpty().split("|").filter { it.isNotBlank() }
    private fun schreibe(n: String, w: Collection<String>) = prefs.edit().putString(n, w.joinToString("|")).apply()

    private fun liesJson(n: String): JSONObject? = try { prefs.getString(n, null)?.let { JSONObject(it) } } catch (e: Exception) { null }
    private fun liesJsonListe(n: String): List<JSONObject> = try {
        val a = JSONArray(prefs.getString(n, "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    private fun liesMap(n: String): Map<String, String> {
        val o = liesJson(n) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optString(it) }
    }
    private fun schreibeMap(n: String, m: Map<String, String>) =
        prefs.edit().putString(n, JSONObject(m as Map<*, *>).toString()).apply()

    private fun liesListenMap(n: String): Map<String, List<String>> {
        val o = liesJson(n) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optJSONArray(it).strings() }
    }
    private fun schreibeListenMap(n: String, m: Map<String, List<String>>) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, JSONArray(v)) }
        prefs.edit().putString(n, o.toString()).apply()
    }

    private fun liesKopf(): KopfOptionen {
        val o = liesJson("kopf") ?: return KopfOptionen()
        val d = KopfOptionen()
        return KopfOptionen(o.optBoolean("termin", d.termin), o.optBoolean("wetter", d.wetter), o.optBoolean("musik", d.musik))
    }

    /** Thema lesen. Beim ersten Start nach dem Update: Uhr und Wellen-Schalter der alten Version uebernehmen */
    private fun liesThema(): Thema {
        liesJson("thema")?.let { return themaAus(it) }
        if (!prefs.contains("uhr_groesse") && !prefs.contains("wellen")) return Thema()
        val d = UhrStil()
        return Thema(
            hintergrund = if (prefs.getBoolean("wellen", true)) "wellen" else "system",
            uhr = UhrStil(
                groesse = prefs.getInt("uhr_groesse", d.groesse), dicke = prefs.getInt("uhr_dicke", d.dicke),
                farbe = prefs.getInt("uhr_farbe", d.farbe), sekunden = prefs.getBoolean("uhr_sekunden", d.sekunden),
                datum = prefs.getBoolean("uhr_datum", d.datum), akku = prefs.getBoolean("uhr_akku", d.akku),
                mittig = prefs.getBoolean("uhr_mittig", d.mittig),
            ),
        )
    }

    private fun liesOrt(): Ort? {
        val o = liesJson("ort") ?: return null
        return Ort(o.optString("name"), o.optDouble("lat"), o.optDouble("lon"))
    }
}
