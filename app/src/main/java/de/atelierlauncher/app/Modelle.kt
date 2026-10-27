package de.atelierlauncher.app

import android.util.Base64
import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

// ============================================================================
//  Datenmodelle und Speicherformat (JSON)
// ============================================================================

const val MAX_FAVORITEN = 12

/** Buchstabe fuer das Alphabet (Ä -> A, Zahlen/Zeichen -> • am Ende) */
fun buchstabeVon(name: String): String {
    val c = Normalizer.normalize(name.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar()
    return if (c != null && c in 'A'..'Z') c.toString() else "•"
}

/** Sortierschluessel: Buchstaben A-Z, danach • */
fun sortierBuchstabe(b: String) = if (b == "•") "~" else b

/** Eine startbare App. name = angezeigter Name (evtl. umbenannt) */
@Immutable
data class AppEintrag(val name: String, val paket: String, val activity: String) {
    val schluessel get() = "$paket/$activity"
    val buchstabe: String by lazy { buchstabeVon(name) }
}

/** Aussehen der Uhr */
data class UhrStil(
    val groesse: Int = 58,
    val dicke: Int = 0,             // 0 duenn, 1 normal, 2 fett
    val farbe: Int = 0,             // Index in UHR_FARBEN (0 = Schriftfarbe, 1 = Akzent)
    val sekunden: Boolean = false,
    val datum: Boolean = true,
    val akku: Boolean = true,
    val mittig: Boolean = false,
    val gestapelt: Boolean = false, // Stunden und Minuten untereinander
    val schrift: String = "",       // eigene Schrift nur fuer die Uhr ("" = wie das Design), z. B. "kunst_monoton"
    val material: String = "",      // "" normal | "glas" (Liquid Glass)
    val glasBlur: Int = 0,          // Weichzeichnen im Glas (dp)
    val glasBrechung: Int = 100,    // wie stark der Rand den Hintergrund verbiegt (%)
    val wechsel: String = "",       // Animation beim Wechsel: "" passend zur Schrift | "keine" | "schreiben" | "fliegen"
    val wechselDauer: Int = 100,    // Laenge der Wechsel-Animation in Prozent (100 = normal)
)

/** Was die Kopfzeile unter der Uhr zeigt */
data class KopfOptionen(
    val termin: Boolean = false,
    val wetter: Boolean = false,
    val musik: Boolean = true,
)

/** Ort fuer das Wetter */
data class Ort(val name: String, val lat: Double, val lon: Double)

/** Ein komplettes Aussehen (wird als Theme gespeichert und geteilt) */
data class Thema(
    val name: String = "Wellen Nacht",
    val modus: Int = 1,                 // 0 hell, 1 dunkel, 2 wie das System
    val akzent: Int = 0,                // 0 = Material You (Farben vom Handy-Hintergrund), sonst ARGB
    val hintergrund: String = "wellen", // wellen | sterne | polar | schwarz | system
    val schrift: String = "system",     // system | serif | sans | mono | cursive | datei:/system/fonts/...
    val icons: String = "original",     // original | umriss | swiss | einfarbig | punkte | keine | paket:<paketname>
    val uhr: UhrStil = UhrStil(),
    // Baukasten: jedes Teil einzeln (frei mischbar, z. B. Swiss-Hintergrund mit Wellen-Alphabet)
    val alphabet: String = "welle",     // welle | swiss (feste Leiste)
    val buchstabe: String = "gross",    // gross (grosser Buchstabe) | karte (Karte mit Nachbarn A B [C] D E)
    val ecken: String = "rund",         // rund | eckig
    val typo: String = "schlicht",      // schlicht | swiss (Versalien mit Sperrung, roter Balken, enge Uhr)
    val liste: String = "schlicht",     // schlicht | einsatz (Nummern, Haarlinien, Versalien)
    val farben: String = "weich",       // weich | kontrast (Schwarz/Papierweiss, keine Schatten)
    val welle: Int = 0,                 // Farbe der Wellen: 0 = automatisch (weiss / petrol), sonst ARGB
    val text: Int = 0,                  // Schriftfarbe: 0 = automatisch (aus dem Farbschema), sonst ARGB
    // eigene Themes: was ausserhalb des Themes eingestellt ist (Randgesten, Sperr-Uebergang, Lautstaerke,
    // Alphabet am Daumen) - als JSON, leer = nichts davon aendern
    val paket: String = "",
)

/** Farbe der Wellen: eigene aus dem Theme, sonst weiss (dunkel) oder petrol (hell) */
fun wellenFarbe(eigen: Int, dunkel: Boolean): Int =
    if (eigen != 0) eigen else if (SW.an) (if (dunkel) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()) else if (dunkel) 0xFFFFFFFF.toInt() else 0xFF1E5F74.toInt()

// ---------------------------------------------------------------- JSON
fun UhrStil.json() = JSONObject()
    .put("groesse", groesse).put("dicke", dicke).put("farbe", farbe).put("sekunden", sekunden)
    .put("datum", datum).put("akku", akku).put("mittig", mittig).put("gestapelt", gestapelt).put("schrift", schrift)
    .put("material", material).put("glasBlur", glasBlur).put("glasBrechung", glasBrechung).put("wechsel", wechsel).put("wechselDauer", wechselDauer)

fun uhrStilAus(o: JSONObject?): UhrStil {
    if (o == null) return UhrStil()
    val d = UhrStil()
    return UhrStil(
        groesse = o.optInt("groesse", d.groesse), dicke = o.optInt("dicke", d.dicke),
        farbe = o.optInt("farbe", d.farbe), sekunden = o.optBoolean("sekunden", d.sekunden),
        datum = o.optBoolean("datum", d.datum), akku = o.optBoolean("akku", d.akku),
        mittig = o.optBoolean("mittig", d.mittig), gestapelt = o.optBoolean("gestapelt", d.gestapelt),
        // 24.10. gab es kurz "Tropfen" als Material bzw. Design - jetzt ist es eine Schrift der Uhr
        schrift = if (o.optString("material") == "tropfen" || o.optString("design") == "tropfen") Schriften.TROPFEN else o.optString("schrift", d.schrift).let { if (it in Schriften.ENTFERNT) "" else it },
        // "tropfen" war kurz ein Material - jetzt ein eigenes Design
        material = o.optString("material", d.material).let { if (it == "tropfen") "" else it }, glasBlur = o.optInt("glasBlur", d.glasBlur),
        glasBrechung = o.optInt("glasBrechung", d.glasBrechung),
        wechsel = o.optString("wechsel", d.wechsel),
        wechselDauer = o.optInt("wechselDauer", d.wechselDauer).coerceIn(25, 300),
    )
}

fun Thema.json(): JSONObject = JSONObject()
    .put("name", name).put("modus", modus).put("akzent", akzent).put("hintergrund", hintergrund)
    .put("schrift", schrift).put("icons", icons).put("uhr", uhr.json())
    .put("alphabet", alphabet).put("buchstabe", buchstabe).put("ecken", ecken).put("typo", typo).put("liste", liste).put("farben", farben).put("welle", welle).put("text", text).put("paket", paket)

fun themaAus(o: JSONObject?): Thema {
    if (o == null) return Thema()
    val d = Thema()
    return Thema(
        name = o.optString("name", d.name), modus = o.optInt("modus", d.modus),
        akzent = o.optInt("akzent", d.akzent), hintergrund = o.optString("hintergrund", d.hintergrund),
        schrift = o.optString("schrift", d.schrift), icons = o.optString("icons", d.icons),
        uhr = uhrStilAus(o.optJSONObject("uhr")),
        // aeltere Themes ohne diese Felder: Swiss-Hintergrund hiess frueher "alles Swiss"
        alphabet = o.optString("alphabet", if (o.optString("hintergrund") == "swiss") "swiss" else d.alphabet),
        buchstabe = o.optString("buchstabe", if (o.optString("alphabet", if (o.optString("hintergrund") == "swiss") "swiss" else "welle") == "swiss") "karte" else d.buchstabe),
        ecken = o.optString("ecken", if (o.optString("hintergrund") == "swiss") "eckig" else d.ecken),
        typo = o.optString("typo", if (o.optString("hintergrund") == "swiss") "swiss" else d.typo),
        liste = o.optString("liste", if (o.optString("hintergrund") == "swiss") "einsatz" else d.liste),
        farben = o.optString("farben", if (o.optString("hintergrund") == "swiss") "kontrast" else d.farben),
        welle = o.optInt("welle", d.welle),
        text = o.optInt("text", d.text),
        paket = o.optString("paket", ""),
    )
}

private const val THEMA_VORSATZ = "LAUNCHERTHEMA:"
private val THEMA_VORSAETZE = listOf(THEMA_VORSATZ)

/** Theme als kurzer Text zum Teilen (z. B. per Messenger) */
fun Thema.code(): String =
    THEMA_VORSATZ + Base64.encodeToString(json().toString().toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

fun themaAusCode(text: String): Thema? = try {
    val t = text.trim()
    val v = THEMA_VORSAETZE.firstOrNull { t.startsWith(it) }
    if (v == null) null
    else themaAus(JSONObject(String(Base64.decode(t.removePrefix(v), Base64.NO_WRAP or Base64.URL_SAFE))))
} catch (e: Exception) { null }


fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optString(it, null) }
}

/**
 * Was manche fertigen Themes zusaetzlich umstellen (liegt nicht im Theme selbst, sondern in eigenen Einstellungen):
 * Randgesten-Stil, Sperrbildschirm-Uebergang, Lautstaerke-Design, Swiss-Kante am Alphabet, Wellen am Daumen, Kreise.
 */
data class ThemaPaket(val randgeste: String, val sperr: String, val laut: String, val kante: Boolean, val daumen: Boolean, val kreise: Boolean,
                      val rosette: Boolean = false,
                      // nur bei eigenen Themes gesetzt (null = nicht aendern): Akku-Wellen, Eis-/Pixel-Farbe, Schnee
                      val akku: Boolean? = null, val eis: Int? = null, val pixel: Int? = null, val schnee: Boolean? = null)
fun ThemaPaket.json(): String = JSONObject()
    .put("randgeste", randgeste).put("sperr", sperr).put("laut", laut).put("kante", kante)
    .put("daumen", daumen).put("kreise", kreise).put("rosette", rosette)
    .also { o -> akku?.let { o.put("akku", it) }; eis?.let { o.put("eis", it) }; pixel?.let { o.put("pixel", it) }; schnee?.let { o.put("schnee", it) } }
    .toString()

fun themaPaketAus(text: String): ThemaPaket? = try {
    if (text.isBlank()) null else JSONObject(text).let { o ->
        ThemaPaket(o.optString("randgeste", "gross"), o.optString("sperr", "karte"), o.optString("laut", "pille"),
                   o.optBoolean("kante", false), o.optBoolean("daumen", true), o.optBoolean("kreise", false), o.optBoolean("rosette", false),
                   akku = if (o.has("akku")) o.optBoolean("akku") else null,
                   eis = if (o.has("eis")) o.optInt("eis") else null,
                   pixel = if (o.has("pixel")) o.optInt("pixel") else null,
                   schnee = if (o.has("schnee")) o.optBoolean("schnee") else null)
    }
} catch (e: Exception) { null }

val THEMA_PAKETE = mapOf(
    "Nur Wellen" to ThemaPaket(randgeste = "gross", sperr = "welle", laut = "welle", kante = false, daumen = true, kreise = false),
    "Nur Swiss-Kanten" to ThemaPaket(randgeste = "grossswiss", sperr = "raster", laut = "swiss", kante = true, daumen = false, kreise = false),
    "Liquid Glass" to ThemaPaket(randgeste = "tropfen", sperr = "glas", laut = "liquid", kante = false, daumen = false, kreise = true),
    "Gezeichnet" to ThemaPaket(randgeste = "skizze", sperr = "skizze", laut = "skizze", kante = false, daumen = false, kreise = false),
    "Schwarz & Weiß" to ThemaPaket(randgeste = "strich", sperr = "karte", laut = "sw", kante = false, daumen = false, kreise = false),
    "Art Déco" to ThemaPaket(randgeste = "riss", sperr = "riss", laut = "riss", kante = false, daumen = false, kreise = false),
    "Kreidetafel" to ThemaPaket(randgeste = "skizze", sperr = "skizze", laut = "skizze", kante = false, daumen = false, kreise = false),
    "Greenback" to ThemaPaket(randgeste = "grossgeld", sperr = "geld", laut = "geld", kante = false, daumen = false, kreise = false, rosette = true),
)

/** Fertige Themes */
val FERTIGE_THEMEN = listOf(
    Thema(name = "Swiss Papier", modus = 0, hintergrund = "wellen", akzent = 0xFFADCAFF.toInt(), schrift = "inter", icons = "umriss",
          uhr = UhrStil(groesse = 88, dicke = 2, datum = true, akku = true, gestapelt = true),
          alphabet = "welle", buchstabe = "karte", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "kontrast"),
    // Alles Welle: Hintergrund, Alphabet, Randgesten, Sperr-Uebergang, Lautstaerke (Rest siehe THEMA_PAKETE)
    Thema(name = "Nur Wellen", modus = 1, hintergrund = "wellen", schrift = "inter", icons = "umriss", text = 0xFF9CC4FF.toInt(),
          uhr = UhrStil(groesse = 88, dicke = 0, datum = true),
          alphabet = "welle", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "weich"),
    // Alles Swiss-Kante: Raster-Hintergrund, Kante am Alphabet, Swiss-Linien bei Randgesten und Sperrbildschirm
    Thema(name = "Nur Swiss-Kanten", modus = 1, hintergrund = "swiss", akzent = 0, schrift = "inter", icons = "swiss",
          uhr = UhrStil(groesse = 88, dicke = 2, datum = true, akku = true),
          alphabet = "swiss", buchstabe = "karte", ecken = "eckig", typo = "swiss", liste = "einsatz", farben = "kontrast"),
    // Banknoten-Gefuehl: Creme-Papier/Tiefgruen, Guilloche, Ornament-Rahmen, Serifen (Rest siehe THEMA_PAKETE)
    Thema(name = "Greenback", modus = 2, hintergrund = "geld", akzent = 0, schrift = "serif", icons = "einfarbig",
          uhr = UhrStil(groesse = 72, dicke = 1, datum = true, akku = true),
          alphabet = "welle", buchstabe = "gross", ecken = "eckig", typo = "geld", liste = "schlicht", farben = "geld"),
    // Gezeichnet: blauer Kuli auf leicht gelbem Papier, nichts ist ganz gerade (Rest siehe THEMA_PAKETE)
    Thema(name = "Gezeichnet", modus = 0, hintergrund = "papier", akzent = 0, schrift = "hand", icons = "skizze",
          uhr = UhrStil(groesse = 84, dicke = 0, datum = true, akku = true),
          alphabet = "welle", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "skizze"),
    // Art Deco: schwarzer Marmor, Gold, Elfenbein - Risse werden mit Gold gekittet (Kintsugi)
    Thema(name = "Art Déco", modus = 1, hintergrund = "marmor", akzent = 0, schrift = "deko", icons = "kintsugi",
          uhr = UhrStil(groesse = 96, dicke = 0, farbe = 1, datum = true, akku = true),
          alphabet = "welle", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "deko"),
    // Kreidetafel: wie Gezeichnet, aber weisse Kreide auf schwarzer Tafel (Gezeichnet + dunkel)
    Thema(name = "Kreidetafel", modus = 1, hintergrund = "papier", akzent = 0, schrift = "hand", icons = "skizze",
          uhr = UhrStil(groesse = 84, dicke = 0, datum = true, akku = true),
          alphabet = "welle", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "skizze"),
    // Liquid Glass: treibende Farbflaechen, darueber Glas, das echt bricht (Rest siehe THEMA_PAKETE)
    Thema(name = "Liquid Glass", modus = 2, hintergrund = "glas", akzent = 0, schrift = "inter", icons = "original",
          uhr = UhrStil(groesse = 80, dicke = 1, datum = true, akku = true),
          alphabet = "welle", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "glas"),
    // Schwarz & Weiss: ganz schlicht - nackte Uhr, festes Alphabet ohne Wellen, graue Strich-Randgeste, runder s/w-Regler
    Thema(name = "Schwarz & Weiß", modus = 2, hintergrund = "schwarz", akzent = 0, schrift = "inter", icons = "original",
          uhr = UhrStil(groesse = 88, dicke = 0, datum = false, akku = false),
          alphabet = "swiss", buchstabe = "gross", ecken = "rund", typo = "schlicht", liste = "schlicht", farben = "sw"),
    Thema(name = "Wellen Nacht"),
    Thema(name = "Wellen Tag", modus = 0, hintergrund = "wellen", uhr = UhrStil(dicke = 0)),
    Thema(name = "Minimal", hintergrund = "schwarz", icons = "keine", uhr = UhrStil(groesse = 48, dicke = 1)),
)
