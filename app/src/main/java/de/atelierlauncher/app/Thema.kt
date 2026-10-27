package de.atelierlauncher.app

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
import androidx.core.view.WindowCompat
import java.io.File

// ============================================================================
//  Farben (hell/dunkel, Material You) und Schriften
// ============================================================================

/** Alle Farben, die die Oberflaeche benutzt */
data class Palette(
    val dunkel: Boolean,
    val text: Color,
    val leise: Color,       // Nebentext
    val karte: Color,       // Pop-ups, Einstellungen
    val linie: Color,       // zarte Flaechen/Trenner
    val akzent: Color,
    val aufAkzent: Color,   // Text auf Akzentfarbe
    val grund: Color,       // einfarbiger Hintergrund
    val schleier: Color,    // dunkelt/hellt den Hintergrund hinter der App-Liste ab
    val schatten: Shadow,   // Textschatten auf dem Startbildschirm
    val warnung: Color,
)

private fun aufFarbe(c: Color) = if (c.luminance() > 0.45f) Color(0xFF101312) else Color.White

fun dunklePalette(akzent: Color) = Palette(
    dunkel = true,
    text = Color(0xFFF1F4F2), leise = Color(0xFFF1F4F2).copy(alpha = 0.6f),
    karte = Color(0xFF1B1F1E), linie = Color.White.copy(alpha = 0.08f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color.Black, schleier = Color.Black.copy(alpha = 0.6f),
    schatten = Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 8f),
    warnung = Color(0xFFFF8A80),
)

fun hellePalette(akzent: Color) = Palette(
    dunkel = false,
    text = Color(0xFF191C1B), leise = Color(0xFF191C1B).copy(alpha = 0.6f),
    karte = Color(0xFFF6F7F6), linie = Color.Black.copy(alpha = 0.07f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(0xFFF1EEE7), schleier = Color(0xFFF6F4EF).copy(alpha = 0.72f),
    schatten = Shadow(Color.White.copy(alpha = 0.6f), Offset(0f, 1f), 6f),
    warnung = Color(0xFFC62828),
)

/** Swiss / International Typographic Style: Schwarz oder Papierweiss, ein Rot, keine Schatten */
fun swissPalette(dunkel: Boolean, rot: Color) = if (dunkel) Palette(
    dunkel = true,
    text = Color(0xFFFFFFFF), leise = Color(0xFFFFFFFF).copy(alpha = 0.55f),
    karte = Color(0xFF151516), linie = Color.White.copy(alpha = 0.10f),
    akzent = rot, aufAkzent = aufFarbe(rot),
    grund = Color(0xFF0B0B0C), schleier = Color(0xFF0B0B0C).copy(alpha = 0.82f),
    schatten = Shadow(Color.Transparent, Offset.Zero, 0f),
    warnung = rot,
) else Palette(
    dunkel = false,
    text = Color(0xFF111111), leise = Color(0xFF111111).copy(alpha = 0.55f),
    karte = Color(0xFFFFFFFF), linie = Color.Black.copy(alpha = 0.10f),
    akzent = rot, aufAkzent = aufFarbe(rot),
    grund = Color(0xFFFFFFFF), schleier = Color(0xFFFFFFFF).copy(alpha = 0.86f),
    schatten = Shadow(Color.Transparent, Offset.Zero, 0f),
    warnung = rot,
)

/** Banknote: Creme-Papier + Tintengruen (hell) oder Tiefgruen + Creme-Linien (dunkel) */
fun geldPalette(dunkel: Boolean, akzent: Color) = if (dunkel) Palette(
    dunkel = true,
    text = Color(Geld.TINTE_DUNKEL), leise = Color(Geld.TINTE_DUNKEL).copy(alpha = 0.6f),
    karte = Color(0xFF13261A), linie = Color(Geld.GRUEN_DUNKEL).copy(alpha = 0.16f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(Geld.PAPIER_DUNKEL), schleier = Color(Geld.PAPIER_DUNKEL).copy(alpha = 0.78f),
    schatten = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 4f),
    warnung = Color(0xFFFF8A80),
) else Palette(
    dunkel = false,
    text = Color(Geld.TINTE_HELL), leise = Color(Geld.TINTE_HELL).copy(alpha = 0.62f),
    karte = Color(0xFFF6F3DF), linie = Color(Geld.GRUEN_HELL).copy(alpha = 0.14f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(Geld.PAPIER_HELL), schleier = Color(Geld.PAPIER_HELL).copy(alpha = 0.80f),
    schatten = Shadow(Color.White.copy(alpha = 0.5f), Offset(0f, 1f), 3f),
    warnung = Color(0xFFB3261E),
)

/** Liquid Glass: Nachtblau mit hellen Glasflaechen bzw. helles Blaugrau, Texte klar */
fun glasPalette(dunkel: Boolean, akzent: Color) = if (dunkel) Palette(
    dunkel = true,
    text = Color.White, leise = Color.White.copy(alpha = 0.66f),
    karte = Color(0xFF1A1F2E), linie = Color.White.copy(alpha = 0.12f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(0xFF0B0F1E), schleier = Color(0xFF0B0F1E).copy(alpha = 0.50f),
    schatten = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 1f), 8f),
    warnung = Color(0xFFFF8A80),
) else Palette(
    dunkel = false,
    text = Color(0xFF10131A), leise = Color(0xFF10131A).copy(alpha = 0.62f),
    karte = Color(0xFFF7F8FC), linie = Color.Black.copy(alpha = 0.08f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(0xFFEEF1F7), schleier = Color(0xFFEEF1F7).copy(alpha = 0.55f),
    schatten = Shadow(Color.White.copy(alpha = 0.6f), Offset(0f, 1f), 6f),
    warnung = Color(0xFFC62828),
)

/** Gezeichnet: leicht gelbliches Papier, blaue Kuli-Tinte, keine Schatten. Dunkel: Kreidetafel mit weisser Kreide */
fun skizzePalette(akzent: Color) = Palette(
    dunkel = Skizze.tafel,
    text = Color(Skizze.TINTE), leise = Color(Skizze.TINTE).copy(alpha = 0.62f),
    karte = Color(Skizze.PAPIER), linie = Color(Skizze.TINTE).copy(alpha = 0.16f),
    akzent = akzent, aufAkzent = aufFarbe(akzent),
    grund = Color(Skizze.PAPIER), schleier = Color(Skizze.PAPIER).copy(alpha = 0.86f),
    schatten = Shadow(Color.Transparent, Offset.Zero, 0f),
    warnung = Color(0xFFC62828),
)

/** Art Deco: schwarzer Marmor, Elfenbein-Schrift, Gold als Akzent (immer dunkel) */
fun dekoPalette(akzent: Color) = Palette(
    dunkel = true,
    text = Color(Deko.ELFENBEIN), leise = Color(Deko.ELFENBEIN).copy(alpha = 0.6f),
    karte = Color(0xFF141416), linie = Color(Deko.GOLD).copy(alpha = 0.28f),
    akzent = akzent, aufAkzent = Color(0xFF0B0B0D),
    grund = Color(Deko.SCHWARZ), schleier = Color(Deko.SCHWARZ).copy(alpha = 0.88f),
    schatten = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), 4f),
    warnung = Color(0xFFE57373),
)

/** Akzent hebt sich vom Swiss-Hintergrund (fast Schwarz / Papierweiss) kaum ab? */
fun kaumZuSehen(c: Color, dunkel: Boolean): Boolean {
    val l = c.luminance()
    return if (dunkel) l < 0.03f else l > 0.80f
}

/** Swiss-Rot (kraeftig, leicht warm) */
val SWISS_ROT = Color(0xFFE2001A)

/** Ecken: hart (rechteckig) oder rund - aus dem Baukasten */
object Form { @Volatile var eckig = false }

/** Baukasten-Teile, die nicht in der Palette stecken (vor dem Inhalt gesetzt) */
object Stil {
    @Volatile var alphabetSwiss = false     // feste Leiste statt Wellen-Bogen
    @Volatile var indexKarte = false        // Karte mit Nachbarbuchstaben statt grossem Buchstaben
    @Volatile var typoSwiss = false         // Ueberschriften in Versalien mit Sperrung, enge Uhr, Datum in Versalien
    @Volatile var typoGeld = false          // Banknote: Rauten-Zier, Uhr im Oval, Karten mit Doppellinie
    @Volatile var listeEinsatz = false      // Nummern, Haarlinien, Namen in Versalien
}
fun rund(r: androidx.compose.ui.unit.Dp): androidx.compose.ui.graphics.Shape =
    if (Form.eckig) androidx.compose.ui.graphics.RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(r)

val LocalPalette = staticCompositionLocalOf { dunklePalette(Color(0xFF83D5C5)) }

/** Akzentfarben zur Auswahl im Theme-Studio (0 = Material You) */
val AKZENTE = listOf(
    0, 0xFF83D5C5.toInt(), 0xFF8AB4FF.toInt(), 0xFFB39DDB.toInt(), 0xFFF48FB1.toInt(),
    0xFFFF8A65.toInt(), 0xFFFFD54F.toInt(), 0xFFA5D6A7.toInt(), 0xFF90A4AE.toInt(), 0xFFFFFFFF.toInt(),
)

/** Uhrfarben: 0 = Schriftfarbe, 1 = Akzent, danach feste Farben */
val UHR_FARBEN = listOf(Color.Unspecified, Color.Unspecified, Color(0xFF8AB4FF), Color(0xFFFF8A80),
                        Color(0xFFFFD54F), Color(0xFFA5D6A7), Color(0xFFCE93D8))

fun uhrFarbe(index: Int, p: Palette): Color = when (index) {
    0 -> p.text
    1 -> p.akzent
    else -> UHR_FARBEN.getOrElse(index) { p.text }
}

// ---------------------------------------------------------------- Schriften
object Schriften {
    private val cache = HashMap<String, FontFamily>()
    /** hochzaehlen, wenn eine Schrift-Datei ersetzt wurde */
    val stand = androidx.compose.runtime.mutableIntStateOf(0)

    /** "system", "serif", ... oder "datei:/pfad/zur/schrift.ttf" */
    fun familie(name: String): FontFamily = when (name) {
        "serif" -> FontFamily.Serif
        "sans" -> FontFamily.SansSerif
        "mono" -> FontFamily.Monospace
        "cursive" -> FontFamily.Cursive
        "system" -> FontFamily.Default
        "inter" -> inter ?: FontFamily.SansSerif
        "hand" -> hand ?: FontFamily.Cursive
        "deko" -> deko ?: FontFamily.Serif
        "futur" -> futur ?: FontFamily.SansSerif
        // kuenstlerische Uhr-Schriften (liegen in res/font als kunst_*.ttf)
        // direkt ueber R.font (nicht per Name suchen - sonst kann der Build sie als "unbenutzt" wegwerfen)
        in KUNST_ID.keys -> kunstCache.getOrPut(name) { FontFamily(androidx.compose.ui.text.font.Font(KUNST_ID.getValue(name))) }
        // Schluessel mit Aenderungszeit: gleiche Datei neu importiert -> neue Schrift
        else -> if (!name.startsWith("datei:")) FontFamily.Default else cache.getOrPut(name + "@" + File(name.removePrefix("datei:")).lastModified()) {
            try {
                val f = File(name.removePrefix("datei:"))
                if (!f.canRead()) FontFamily.Default
                else FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromFile(f)))
            } catch (e: Exception) { FontFamily.Default }
        }
    }

    private val kunstCache = HashMap<String, FontFamily>()
    @Volatile private var resKontext: android.content.Context? = null

    /** Uhr-Schrift mit Laeufern, die ab und zu abtropfen (TropfZiffern) */
    const val TROPFEN = "kunst_tropfen"
    /** Glitch: die Striche flimmern (GlitchZiffern) */
    const val GLITCH = "kunst_rubikglitch"
    /** 25.10. entfernt - wer sie hatte, bekommt wieder die Schrift vom Design */
    val ENTFERNT = setOf("kunst_bungeeshade", "kunst_bungeeinline", "kunst_trainone", "kunst_silkscreen", "kunst_monofett", "kunst_zillaslabhighlight")
    /** kuenstlerische Schriften fuer die Uhr (alle SIL Open Font License, aus Google Fonts) */
    val KUNST = listOf(
        "kunst_monoton" to "Neon", "kunst_bungeeoutline" to "Umriss", 
        "kunst_bungeehairline" to "Haarlinie", "kunst_rampartone" to "3D-Block", "kunst_codystar" to "Sterne",
        "kunst_majormonodisplay" to "Mono Display", "kunst_megrim" to "Geometrie", "kunst_orbitron" to "Raumschiff", "kunst_wallpoet" to "Graffiti",
        "kunst_rubikglitch" to "Glitch", "kunst_vt323" to "Terminal", 
        "kunst_plaster" to "Schablone", "kunst_bebasneue" to "Plakat", "kunst_sixcaps" to "Schmal", "kunst_abrilfatface" to "Mode",
        "kunst_limelight" to "Kino", "kunst_fascinate" to "Varieté", "kunst_shrikhand" to "Retro", "kunst_pacifico" to "Schwung",
        "kunst_sancreek" to "Western", "kunst_ewert" to "Holzschnitt", "kunst_tropfen" to "Tropfen", "kunst_nosifer" to "Grusel", "kunst_frederickathegreat" to "Kreide",
        "kunst_londrinaoutline" to "Skizze",
    )
    /** Schrift-Datei je Uhr-Schrift */
    private val KUNST_ID: Map<String, Int> = mapOf(
        "kunst_monoton" to R.font.kunst_monoton,
        "kunst_bungeeoutline" to R.font.kunst_bungeeoutline,
        "kunst_bungeehairline" to R.font.kunst_bungeehairline,
        "kunst_rampartone" to R.font.kunst_rampartone,
        "kunst_codystar" to R.font.kunst_codystar,
        "kunst_majormonodisplay" to R.font.kunst_majormonodisplay,
        "kunst_megrim" to R.font.kunst_megrim,
        "kunst_orbitron" to R.font.kunst_orbitron,
        "kunst_wallpoet" to R.font.kunst_wallpoet,
        "kunst_rubikglitch" to R.font.kunst_rubikglitch,
        "kunst_vt323" to R.font.kunst_vt323,
        "kunst_plaster" to R.font.kunst_plaster,
        "kunst_bebasneue" to R.font.kunst_bebasneue,
        "kunst_sixcaps" to R.font.kunst_sixcaps,
        "kunst_abrilfatface" to R.font.kunst_abrilfatface,
        "kunst_limelight" to R.font.kunst_limelight,
        "kunst_fascinate" to R.font.kunst_fascinate,
        "kunst_shrikhand" to R.font.kunst_shrikhand,
        "kunst_pacifico" to R.font.kunst_pacifico,
        "kunst_sancreek" to R.font.kunst_sancreek,
        "kunst_ewert" to R.font.kunst_ewert,
        "kunst_nosifer" to R.font.kunst_nosifer,
        "kunst_tropfen" to R.font.inter_extrabold,          // 25.10.: Tropfen = klare fette Ziffern, die Laeufer malt TropfZiffern
        "kunst_frederickathegreat" to R.font.kunst_frederickathegreat,
        "kunst_londrinaoutline" to R.font.kunst_londrinaoutline,
    )

    val EINGEBAUT = listOf(
        "system" to "Standard", "inter" to "Grotesk (Swiss)", "sans" to "Sans", "serif" to "Serif", "mono" to "Mono", "cursive" to "Schreibschrift",
        "hand" to "Handschrift (Kuli)", "deko" to "Art Déco (Poiret One)", "futur" to "Futur, breit (Michroma)",
    )

    /** Michroma (breit, futuristisch, SIL Open Font License) - liegt in res/font */
    var futur: FontFamily? = null
        private set

    /** Poiret One (Art Deco, SIL Open Font License) - liegt in res/font */
    var deko: FontFamily? = null
        private set

    /** Inter (Grotesk wie Helvetica, SIL Open Font License) - liegt in res/font, beim Start geladen */
    var inter: FontFamily? = null
        private set

    /** Kalam (Handschrift wie mit Kuli, SIL Open Font License) - liegt in res/font */
    var hand: FontFamily? = null
        private set

    fun laden(ctx: android.content.Context) {
        resKontext = ctx.applicationContext
        fun id(n: String) = ctx.resources.getIdentifier(n, "font", ctx.packageName)
        if (hand == null) {
            val l = id("kalam_light"); val r = id("kalam_regular")
            val W = androidx.compose.ui.text.font.FontWeight
            val teileH = ArrayList<androidx.compose.ui.text.font.Font>()
            if (l != 0) { teileH += androidx.compose.ui.text.font.Font(l, W.Light); teileH += androidx.compose.ui.text.font.Font(l, W.Normal) }
            if (r != 0) { teileH += androidx.compose.ui.text.font.Font(r, W.Medium); teileH += androidx.compose.ui.text.font.Font(r, W.SemiBold); teileH += androidx.compose.ui.text.font.Font(r, W.Bold) }
            if (teileH.isNotEmpty()) hand = FontFamily(teileH)
        }
        Skizze.schriftLaden(ctx)
        Deko.schriftLaden(ctx)
        if (deko == null) id("poiret_one").takeIf { it != 0 }?.let { deko = FontFamily(androidx.compose.ui.text.font.Font(it)) }
        if (futur == null) id("michroma").takeIf { it != 0 }?.let { futur = FontFamily(androidx.compose.ui.text.font.Font(it)) }
        if (inter != null) return
        val teile = listOf(
            "inter_light" to androidx.compose.ui.text.font.FontWeight.Light,
            "inter_regular" to androidx.compose.ui.text.font.FontWeight.Normal,
            "inter_semibold" to androidx.compose.ui.text.font.FontWeight.SemiBold,
            "inter_extrabold" to androidx.compose.ui.text.font.FontWeight.ExtraBold,
        ).mapNotNull { (n, w) -> id(n).takeIf { it != 0 }?.let { androidx.compose.ui.text.font.Font(it, w) } }
        if (teile.isNotEmpty()) inter = FontFamily(teile)
    }

    /** Schriften des Handys (eine Datei je Familie, bevorzugt "Regular") und eigene geladene Schriften */
    fun dateien(eigenerOrdner: File): List<Pair<String, String>> {
        val ergebnis = ArrayList<Pair<String, String>>()
        try {
            eigenerOrdner.listFiles()?.sortedBy { it.name.lowercase() }?.forEach {
                ergebnis += "Eigene: " + it.nameWithoutExtension to "datei:" + it.path
            }
        } catch (e: Exception) { }
        try {
            val alle = File("/system/fonts").listFiles { f ->
                val n = f.name.lowercase(); n.endsWith(".ttf") || n.endsWith(".otf")
            } ?: emptyArray()
            alle.groupBy { it.nameWithoutExtension.substringBefore('-') }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                .forEach { (familie, dateien) ->
                    // Emoji- und Schriftzeichen-Dateien fuer andere Sprachen ueberspringen
                    if (familie.startsWith("Noto", ignoreCase = true) &&
                        familie !in setOf("NotoSerif", "NotoSans", "NotoSansMono")) return@forEach
                    if (familie.contains("Emoji", ignoreCase = true) || familie.contains("Fallback", true)) return@forEach
                    val wahl = dateien.firstOrNull { it.nameWithoutExtension.endsWith("Regular", true) }
                        ?: dateien.firstOrNull { !it.nameWithoutExtension.contains('-') }
                        ?: dateien.minByOrNull { it.name.length }!!
                    ergebnis += familie to "datei:" + wahl.path
                }
        } catch (e: Exception) { }
        return ergebnis
    }
}

// ---------------------------------------------------------------- Theme
@Composable
fun LauncherTheme(thema: Thema, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val systemDunkel = isSystemInDarkTheme()
    val skizze = thema.farben == "skizze"
    // Art Deco ist immer dunkel (schwarzer Marmor), egal was bei Hell/Dunkel steht
    val dunkel = if (thema.farben == "deko") true else when (thema.modus) { 0 -> false; 1 -> true; else -> systemDunkel }
    // Gezeichnet + dunkel = Kreidetafel (schwarze Tafel, weisse Kreide)
    Skizze.laden(ctx)
    Skizze.tafel = skizze && dunkel
    // Baukasten: jedes Teil fuer sich (vor dem Inhalt setzen, damit alles darunter es schon kennt)
    val swiss = thema.farben == "kontrast"
    val geld = thema.farben == "geld"
    val glas = thema.farben == "glas"
    Glas.an = glas
    Skizze.an = skizze
    val deko = thema.farben == "deko"
    Deko.an = deko
    val sw = thema.farben == "sw"
    SW.an = sw
    Glas.echt = thema.hintergrund == "glas"
    Form.eckig = thema.ecken == "eckig"
    Stil.alphabetSwiss = thema.alphabet == "swiss"
    Stil.indexKarte = thema.buchstabe == "karte"
    Stil.typoSwiss = thema.typo == "swiss"
    Stil.typoGeld = thema.typo == "geld"
    Stil.listeEinsatz = thema.liste == "einsatz"
    val akzent = remember(thema.akzent, dunkel, swiss, geld, glas, skizze, thema.farben) {
        when {
            skizze && thema.akzent == 0 -> Color(Skizze.TINTE)
            // Schwarz & Weiss: kein Farbton
            sw -> if (dunkel) Color.White else Color.Black
            thema.farben == "deko" && thema.akzent == 0 -> Color(Deko.GOLD)
            geld && thema.akzent == 0 -> Color(Geld.gruen(dunkel))
            glas && thema.akzent == 0 -> if (dunkel) Color(0xFF8AB4FF) else Color(0xFF3A6BFF)
            // Swiss: ohne eigene Wahl Rot. Eigene Farbe gilt - nur wenn sie im Hintergrund verschwinden
            // wuerde (Weiss auf Papier, Schwarz auf Schwarz), nimmt er stattdessen Rot
            swiss && (thema.akzent == 0 || kaumZuSehen(Color(thema.akzent), dunkel)) -> SWISS_ROT
            thema.akzent != 0 -> Color(thema.akzent)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dunkel) dynamicDarkColorScheme(ctx).primary else dynamicLightColorScheme(ctx).primary
            else -> if (dunkel) Color(0xFF83D5C5) else Color(0xFF00696B)
        }
    }
    val p = remember(dunkel, akzent, swiss, geld, glas, skizze, deko, sw, thema.text) {
        // eigene Schriftfarbe aus dem Theme (z. B. "Nur Wellen": blau)
        val eigen: (Palette) -> Palette = { pa -> if (thema.text == 0) pa else pa.copy(text = Color(thema.text), leise = Color(thema.text).copy(alpha = 0.62f)) }
        eigen(if (sw) swPalette(dunkel) else if (deko) dekoPalette(akzent) else if (skizze) skizzePalette(akzent) else if (glas) glasPalette(dunkel, akzent) else if (geld) geldPalette(dunkel, akzent) else if (swiss) swissPalette(dunkel, akzent) else if (dunkel) dunklePalette(akzent) else hellePalette(akzent))
    }
    val schema = remember(p) {
        if (p.dunkel) darkColorScheme(
            primary = p.akzent, onPrimary = p.aufAkzent, surface = p.karte, onSurface = p.text,
            background = p.grund, onBackground = p.text, surfaceVariant = Color(0xFF2A302E),
        ) else lightColorScheme(
            primary = p.akzent, onPrimary = p.aufAkzent, surface = p.karte, onSurface = p.text,
            background = p.grund, onBackground = p.text, surfaceVariant = Color(0xFFE2E6E4),
        )
    }
    remember { Schriften.laden(ctx); Papier.laden(ctx) }
    val familie = remember(thema.schrift, Schriften.stand.intValue) { Schriften.familie(thema.schrift) }

    // Statusleiste: dunkle Symbole im hellen Modus
    val view = LocalView.current
    SideEffect {
        val fenster = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(fenster, view).apply {
            isAppearanceLightStatusBars = !p.dunkel
            isAppearanceLightNavigationBars = !p.dunkel
        }
    }
    // Randgesten-Welle (laeuft im Dienst, ausserhalb von Compose): Farben vom Theme mitgeben
    SideEffect { Randgesten.farbeSetzen(ctx, p.akzent.toArgb(), p.dunkel) }

    MaterialTheme(colorScheme = schema) {
        CompositionLocalProvider(
            LocalPalette provides p,
            // Swiss: etwas engere Laufweite wie bei Helvetica-Plakaten
            LocalTextStyle provides TextStyle(fontFamily = familie, color = p.text,
                letterSpacing = if (thema.schrift == "inter") (-0.012f).em else TextUnit.Unspecified),
            content = content,
        )
    }
}
