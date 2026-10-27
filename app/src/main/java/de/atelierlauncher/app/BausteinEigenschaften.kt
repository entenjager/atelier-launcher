package de.atelierlauncher.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.json.JSONObject
import kotlin.math.roundToInt

// ============================================================================
//  Eigenschaften eines Bausteins: Schrift (Art, Staerke, kursiv, Groesse, Farbe, Ausrichtung),
//  Hintergrund / Ecken / Rahmen und was zu dem Baustein passt. Gespeichert als JSON im Baustein.
//  Leer = alles wie im Design (eine Vorlage aendert dann weiter alles).
// ============================================================================
class BOpt(private val m: Map<String, String>) {
    fun s(k: String, d: String = "") = m[k] ?: d
    fun b(k: String, d: Boolean) = m[k]?.let { it == "1" } ?: d
    fun i(k: String, d: Int) = m[k]?.toIntOrNull() ?: d
    fun hat(k: String) = m.containsKey(k)

    companion object {
        fun aus(json: String): BOpt = try {
            val o = JSONObject(json)
            BOpt(o.keys().asSequence().associateWith { o.optString(it) })
        } catch (e: Exception) { BOpt(emptyMap()) }
    }
}

/** Eigenschaften des Bausteins, in dem man gerade ist (null = keine eigenen) */
internal val LocalBOpt = compositionLocalOf<BOpt?> { null }

/** Baustein-Eigenschaft setzen ("" = zuruecksetzen auf Design) */
internal fun Baustein.mitOpt(k: String, v: String): Baustein {
    val o = try { if (opt.isBlank()) JSONObject() else JSONObject(opt) } catch (e: Exception) { JSONObject() }
    if (v.isEmpty()) o.remove(k) else o.put(k, v)
    return copy(opt = if (o.length() == 0) "" else o.toString())
}

// ---------------------------------------------------------------- Helfer fuer die Bausteine selbst
/** Form der Flaeche: rund (Standard) oder eckig */
@Composable
internal fun bForm(rad: Dp): Shape = if (LocalBOpt.current?.s("ecke") == "eckig") RoundedCornerShape(3.dp) else rund(rad)

/** Flaeche des Bausteins: wie bisher - oder ohne Hintergrund / mit Rahmen, wenn eingestellt */
@Composable
internal fun Modifier.bKarte(p: Palette, rad: Dp, alpha: Float): Modifier {
    val o = LocalBOpt.current
    var m = if (o?.b("hg", true) == false) this else this.karteFlaeche(p, rad, alpha)
    if (o?.b("rahmen", false) == true) m = m.border(1.dp, p.text.copy(alpha = 0.6f), bForm(rad))
    return m
}

/** Schriftstaerke: eigene Wahl oder die uebergebene */
@Composable
internal fun bDicke(std: FontWeight?): FontWeight? = when (LocalBOpt.current?.s("dicke")) {
    "0" -> FontWeight.Light; "1" -> FontWeight.Normal; "2" -> FontWeight.Bold; else -> std
}

/** Schriftfarbe: Akzent oder die uebergebene */
@Composable
internal fun bFarbe(std: Color): Color = if (LocalBOpt.current?.s("farbe") == "akzent") LocalPalette.current.akzent else std

/** Ausrichtung: links / Mitte / rechts oder die uebergebene */
@Composable
internal fun bAusr(std: TextAlign): TextAlign = when (LocalBOpt.current?.s("ausr")) {
    "l" -> TextAlign.Start; "m" -> TextAlign.Center; "r" -> TextAlign.End; else -> std
}

/**
 * Um jeden Baustein: gibt seine Eigenschaften weiter (Schriftart + kursiv ueber den Textstil,
 * Groesse ueber die Schriftskalierung) und malt bei Uhr / App-Liste / Alphabet auf Wunsch eine Flaeche.
 */
@Composable
internal fun BausteinRahmen(b: Baustein, inhalt: @Composable () -> Unit) {
    if (b.opt.isBlank()) { CompositionLocalProvider(LocalBOpt provides null) { inhalt() }; return }
    val o = remember(b.opt) { BOpt.aus(b.opt) }
    val p = LocalPalette.current
    val stil = LocalTextStyle.current
    val schrift = o.s("schrift")
    val neu = stil.copy(
        fontFamily = if (schrift.isNotEmpty()) Schriften.familie(schrift) else stil.fontFamily,
        fontStyle = if (o.b("kursiv", false)) FontStyle.Italic else stil.fontStyle,
    )
    val d = LocalDensity.current
    val gr = o.i("gr", 100) / 100f
    CompositionLocalProvider(LocalBOpt provides o, LocalTextStyle provides neu, LocalDensity provides Density(d.density, d.fontScale * gr)) {
        val ohneEigeneFlaeche = b.typ in setOf("uhr", "uhrklein", "favoriten", "alphabet")
        if (ohneEigeneFlaeche && (o.b("hg", false) || o.b("rahmen", false))) {
            Box(Modifier.fillMaxSize().clip(bForm(18.dp))
                .then(if (o.b("hg", false)) Modifier.karteFlaeche(p, 18.dp, 0.09f) else Modifier)
                .then(if (o.b("rahmen", false)) Modifier.border(1.dp, p.text.copy(alpha = 0.6f), bForm(18.dp)) else Modifier)
                .padding(6.dp)) { inhalt() }
        } else inhalt()
    }
}

// ---------------------------------------------------------------- das Blatt im Editor
private val MIT_SCHRIFT_ALLES = setOf("uhr", "uhrklein", "notiz", "favoriten")
private val MIT_SCHRIFT_TEIL = setOf("termine", "termin", "suche", "app", "webseite", "alphabet")
private val KNOEPFE = setOf("lampe", "ruhe", "ton", "kamera", "wlan", "bluetooth")

@Composable
internal fun EigenschaftenBlatt(b: Baustein, onAendern: (Baustein) -> Unit, onZu: () -> Unit) {
    val p = LocalPalette.current
    val z = LocalZustand.current
    val o = remember(b.opt) { BOpt.aus(b.opt) }
    fun setz(k: String, v: String) = onAendern(b.mitOpt(k, v))
    Dialog(onDismissRequest = onZu) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(24.dp)).background(p.grund)
                   .verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
            Text(Sprache.tr("Eigenschaften · ") + Sprache.tr(SeitenRaster.art(b.typ)?.name ?: b.typ), color = p.text, fontSize = 19.sp,
                 modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))

            // ---- Schrift
            val alles = b.typ in MIT_SCHRIFT_ALLES
            if (alles || b.typ in MIT_SCHRIFT_TEIL) {
                Gruppe("Schrift") {
                    // eingebaute und selbst geladene Schriften (Design → Schrift → „Eigene Schrift laden“)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val eigene = remember(Schriften.stand.intValue) {
                        try { Schriften.dateien(java.io.File(ctx.filesDir, "fonts")).filter { it.second.startsWith("datei:" + ctx.filesDir.path) }.map { it.second to it.first } } catch (e: Exception) { emptyList() }
                    }
                    // Uhr: eigene Schriftauswahl (Kunst-Schriften) statt der allgemeinen - sonst gab es zwei
                    if (b.typ == "uhr" || b.typ == "uhrklein") UhrSchriftWahl(z.thema.uhr.schrift) { z.uhrSetzen(z.thema.uhr.copy(schrift = it)) }
                    if (b.typ == "uhr") UhrWechselWahl(z.thema.uhr) { z.uhrSetzen(it) }
                    else Wahl("Schriftart", listOf("" to "Wie im Design") + Designs.nurFrei("schrift", Schriften.EINGEBAUT, o.s("schrift")) + eigene, o.s("schrift")) { setz("schrift", it) }
                    if (alles || b.typ in setOf("termine", "termin", "alphabet"))
                        Wahl("Stärke", listOf("" to "Wie im Design", "0" to "Dünn", "1" to "Normal", "2" to "Fett"), o.s("dicke")) { setz("dicke", it) }
                    if (alles) Schalter("Kursiv", o.b("kursiv", false)) { setz("kursiv", if (it) "1" else "") }
                    if (b.typ != "alphabet" && b.typ != "uhrklein" && b.typ != "uhr") {          // kleine Uhr: fuellt immer den ganzen Baustein
                        val gr = o.i("gr", 100)
                        RgRegler("Größe", gr.toFloat(), 50f..200f, "$gr %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("gr", if (r == 100) "" else r.toString()) }
                    }
                    if (alles) Wahl("Farbe", listOf("" to "Schriftfarbe", "akzent" to "Akzentfarbe"), o.s("farbe")) { setz("farbe", it) }
                    if (b.typ == "uhr" || b.typ == "uhrklein") Wahl("Ausrichtung", listOf("" to "Wie im Design", "l" to "Links", "m" to "Mitte"), o.s("ausr")) { setz("ausr", it) }
                    if (b.typ == "notiz") Wahl("Ausrichtung", listOf("" to "Mitte", "l" to "Links", "r" to "Rechts"), o.s("ausr")) { setz("ausr", it) }
                }
            }

            // ---- was zu diesem Baustein gehoert
            when (b.typ) {
                "uhr", "uhrklein" -> Gruppe("Uhr") {
                    val u = z.thema.uhr
                    Schalter("Datum", o.b("datum", u.datum)) { setz("datum", if (it) "1" else "0") }
                    Schalter("Akku", o.b("akku", u.akku)) { setz("akku", if (it) "1" else "0") }
                    Schalter("Sekunden", o.b("sek", u.sekunden)) { setz("sek", if (it) "1" else "0") }
                    UhrMaterialWahl(u) { z.uhrSetzen(it) }
                }
                "favoriten" -> Gruppe("App-Liste") {
                    Wahl("Beim Wischen übers Alphabet", listOf("" to "Jeder Buchstabe eine Seite", "liste" to "Alle Apps, nur scrollen"), o.s("modus"),
                         if (o.s("modus") == "liste") "Eine lange Liste mit allen Apps – das Alphabet springt an die Stelle" else "Nur die Apps des Buchstabens") { setz("modus", it) }
                    // Icon-Stil: derselbe wie unter Design → Icons (gilt fuer alle Icons)
                    Wahl("Icon-Stil", Designs.nurFrei("icons", ICON_STILE, z.thema.icons), if (ICON_STILE.any { it.first == z.thema.icons }) z.thema.icons else "") { z.themaSetzen(z.thema.copy(icons = it)) }
                    Schalter("Icons", o.b("icons", true)) { setz("icons", if (it) "" else "0") }
                    val ab = o.i("abstand", 0)
                    RgRegler("Zeilenabstand", ab.toFloat(), 0f..24f, "+$ab dp", {}) { v -> setz("abstand", v.roundToInt().takeIf { it > 0 }?.toString() ?: "") }
                    RgRegler("Höchstens", z.favMax.toFloat(), 1f..20f, "${z.favMax} Favoriten", {}) { z.favMaxSetzen(it.roundToInt()) }
                }
                "termine" -> Gruppe("Termine") {
                    val n = o.i("anzahl", 12)
                    RgRegler("Wie viele", n.toFloat(), 1f..12f, "$n", {}) { v -> setz("anzahl", v.roundToInt().let { if (it == 12) "" else it.toString() }) }
                }
                "suche" -> Gruppe("Suchen") {
                    Schalter("Nur die Lupe", o.b("nurLupe", false)) { setz("nurLupe", if (it) "1" else "") }
                    var t by remember(b.id) { mutableStateOf(b.titel) }
                    Eingabe(t, { t = it; onAendern(b.copy(titel = it)) }, "Text (leer = „Apps suchen“)")
                }
                "app" -> Gruppe("App-Knopf") {
                    Wahl("Icon-Stil", Designs.nurFrei("icons", ICON_STILE, z.thema.icons), if (ICON_STILE.any { it.first == z.thema.icons }) z.thema.icons else "") { z.themaSetzen(z.thema.copy(icons = it)) }
                    Schalter("Icon", o.b("icon", true)) { setz("icon", if (it) "" else "0") }
                }
                "webseite" -> Gruppe("Webseite") {
                    Schalter("Symbol", o.b("symbol", true)) { setz("symbol", if (it) "" else "0") }
                }
                "alphabet" -> Gruppe("Alphabet") {
                    Wahl("Bewegung", listOf("welle" to "Welle (Bogen)", "swiss" to "Feste Leiste"), z.thema.alphabet,
                         if (z.thema.alphabet == "welle") "Buchstaben weichen dem Daumen im Bogen aus" else "Leiste bleibt stehen, nur der Buchstabe wird markiert") {
                        z.themaSetzen(z.thema.copy(alphabet = it))
                    }
                    HinterAlphabetWahl()
                    Schalter("Bewegt sich mit der Musik", z.abcMusik, "Was hinter dem Alphabet ist (Welle, Swiss-Kante, Swiss-Linie, Kreise) zuckt im Takt und mit dem Bass") { z.abcMusikSetzen(an = it) }
                    if (z.abcMusik) {
                        val ctxM = androidx.compose.ui.platform.LocalContext.current
                        MusikKennerEinstellung()
                        RgRegler("Stärke (Musik)", z.abcMusikStaerke.toFloat(), 20f..200f, "${z.abcMusikStaerke} %", {}) { v -> z.abcMusikSetzen(staerke = (v / 5f).roundToInt() * 5) }
                        if (!Beat.erlaubt(ctxM)) MenueEintrag("Echten Takt hören", "Braucht die Erlaubnis „Mikrofon“ – ohne bewegt sich nichts mit der Musik") {
                            (ctxM as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4722)
                        }
                    }
                    Schalter("Klack-Geräusch", z.alphabetKlick) { z.alphabetKlickSetzen(it) }
                    Schalter("Leiste zieht mit", z.alphabetMitziehen) { z.alphabetMitziehenSetzen(it) }
                    AndererTon()
                }
                "mobil", "netz" -> Gruppe("Mobile Daten") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    if (!telefonErlaubt(ctx)) MenueEintrag("Netztyp anzeigen (LTE / 5G)", "Dafür braucht die App die Berechtigung „Telefon“ – ohne siehst du nur die Balken und Pfeile") {
                        (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.READ_PHONE_STATE), 4720)
                    } else Text("Netztyp (LTE / 5G) wird angezeigt", color = LocalPalette.current.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                }
                "akku", "akkupille" -> Gruppe("Akku") {
                    Wahl("Farbe beim Laden", listOf("" to "Automatisch", "gruen" to "Grün", "design" to "Wie das Design"), o.s("ladeFarbe"),
                         if (o.s("ladeFarbe").isEmpty()) "Schwarz & Weiß: wie das Design, sonst grün" else null) { setz("ladeFarbe", it) }
                    Schalter("Rot unter 15 %", o.b("rot", true)) { setz("rot", if (it) "" else "0") }
                    if (b.typ == "akku") Wahl("Form", listOf("" to "Automatisch", "ring" to "Ring", "zahl" to "Akku mit Zahl"), o.s("form")) { setz("form", it) }
                    val pille = b.typ == "akkupille" || o.s("form") == "zahl" || (o.s("form") != "ring" && !(SeitenRaster.alteEinheit(b.h) >= 2 && SeitenRaster.alteEinheit(b.b) >= 2))
                    if (pille) {
                        val pb = o.i("pBreite", 100); val pd = o.i("pDicke", 100)
                        RgRegler("Breite", pb.toFloat(), 40f..100f, "$pb %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("pBreite", if (r == 100) "" else r.toString()) }
                        RgRegler("Dicke", pd.toFloat(), 40f..160f, "$pd %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("pDicke", if (r == 100) "" else r.toString()) }
                    }
                }
                "musik" -> Gruppe("Musik") {
                    Unit
                    Schalter("Titel anzeigen", o.b("titel", false), "Titel und Künstler, wenn etwas läuft") { setz("titel", if (it) "1" else "") }
                    val t = o.i("tasten", 100)
                    RgRegler("Tastengröße", t.toFloat(), 60f..160f, "$t %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("tasten", if (r == 100) "" else r.toString()) }
                    val ringArt = o.s("ring")
                    Wahl("Mitte", listOf("" to "Taste", "beat" to "Beat-Ring", "song" to "Song-Ring"), ringArt, when (ringArt) {
                        "beat" -> "Spielt: Kreis zittert, Striche springen im Takt. Pause: dünner grauer Ring"
                        "song" -> "Füllt sich mit dem Song – bei Pause blinkt er"
                        else -> null
                    }) { setz("ring", it) }
                    if (ringArt == "beat" || ringArt == "song") {
                        val ra = o.i("ringAb", 125)
                        RgRegler("Ring – Abstand", ra.toFloat(), 80f..250f, "$ra %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("ringAb", if (r == 125) "" else r.toString()) }
                        val mi = o.i("mitte", 100)
                        RgRegler("Ring – Größe", mi.toFloat(), 50f..200f, "$mi %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("mitte", if (r == 100) "" else r.toString()) }
                        Schalter("Play / Pause in der Mitte", o.b("ringSym", true), "Aus, wenn dort z. B. die Kamera sitzt") { setz("ringSym", if (it) "" else "0") }
                        if (o.b("ringSym", true)) for ((titel, k, ber) in listOf(Triple("Pause-Striche – Höhe", "pauseHoch", 40f..260f), Triple("Play-Dreieck – Größe", "playGr", 40f..200f))) {
                            val w = o.i(k, 100)
                            RgRegler(titel, w.toFloat(), ber, "$w %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz(k, if (r == 100) "" else r.toString()) }
                        }
                    }
                    if (ringArt == "beat") {
                        val sl = o.i("strichL", 100)
                        RgRegler("Striche – Länge", sl.toFloat(), 20f..250f, "$sl %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("strichL", if (r == 100) "" else r.toString()) }
                        MusikKennerEinstellung()
                        val st = o.i("beatSt", 75)
                        RgRegler("Stärke (echter Takt)", st.toFloat(), 20f..200f, "$st %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("beatSt", if (r == 75) "" else r.toString()) }
                        val se = o.i("seitL", 100)
                        RgRegler("Striche links / rechts – Länge", se.toFloat(), 0f..400f, "$se %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("seitL", if (r == 100) "" else r.toString()) }
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        if (!Beat.erlaubt(ctx)) MenueEintrag("Echten Takt hören", "Braucht die Erlaubnis „Mikrofon“ – Android hört dann nur, wie laut welche Töne gerade sind. Ohne: erfundener Takt") {
                            (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 4721)
                        } else {
                            val txt by androidx.compose.runtime.produceState(Beat.zustandText(ctx)) {
                                while (true) { value = Beat.zustandText(ctx); kotlinx.coroutines.delay(700) }
                            }
                            Text(txt, color = LocalPalette.current.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                        }
                    }
                    if (ringArt == "") {
                        Schalter("Mitteltaste mit Hintergrund", o.b("mitteHg", true)) { setz("mitteHg", if (it) "" else "0") }
                        for ((titel, k, ber) in listOf(Triple("Mitteltaste (Play/Pause)", "mitte", 50f..200f), Triple("Pause-Striche – Höhe", "pauseHoch", 40f..260f),
                                                       Triple("Play-Dreieck – Größe", "playGr", 40f..200f))) {
                            val w = o.i(k, 100)
                            RgRegler(titel, w.toFloat(), ber, "$w %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz(k, if (r == 100) "" else r.toString()) }
                        }
                    }
                    run {
                        val w = o.i("reihe", 100)
                        RgRegler("Breite der Tasten-Reihe", w.toFloat(), 30f..100f, "$w %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("reihe", if (r == 100) "" else r.toString()) }
                    }
                }
                "nachrichten" -> Gruppe("Benachrichtigungen") {
                    Schalter("Zahl anzeigen", o.b("zahl", true)) { setz("zahl", if (it) "" else "0") }
                }
                "rahmen" -> Gruppe("Linie") {
                    // Dicke in Zehntel-dp (13 = 1,3 dp); alte Wahl "Dick" zaehlt als 25
                    val dk = o.i("dicke", if (o.s("linie") == "dick") 25 else 13)
                    RgRegler("Dicke", dk.toFloat(), 0f..100f, if (dk == 0) "keine Linie" else "${dk / 10},${dk % 10} dp", {}) { v ->
                        val r = if (v < 20f) v.roundToInt() else (v / 5f).roundToInt() * 5
                        setz("dicke", r.toString())
                    }
                    Schalter("Gefüllt", o.b("hg", false)) { setz("hg", if (it) "1" else "") }
                    Schalter("Cover der Musik", o.b("cover", true), "Liegt ein Musik-Baustein im Rahmen, leuchtet dahinter das Cover des Songs – nach außen durchsichtiger") { setz("cover", if (it) "" else "0") }
                    if (o.b("cover", true)) {
                        val cd = o.i("coverDeut", 70); val cr = o.i("coverReich", 100); val cb = o.i("coverBlur", 0)
                        RgRegler("Cover – Deutlichkeit", cd.toFloat(), 10f..100f, "$cd %", {}) { v -> setz("coverDeut", v.roundToInt().toString()) }
                        RgRegler("Cover – Reichweite", cr.toFloat(), 40f..220f, "$cr %", {}) { v -> setz("coverReich", v.roundToInt().toString()) }
                        RgRegler("Cover – Unschärfe", cb.toFloat(), 0f..30f, if (cb == 0) "aus" else "$cb", {}) { v -> setz("coverBlur", v.roundToInt().toString()) }
                    }
                    Schalter("Milchglas", o.b("milch", false), "Was hinter dem Rahmen ist, wird weichgezeichnet und eingefärbt – die Bausteine darauf bleiben scharf") { setz("milch", if (it) "1" else "") }
                    if (o.b("milch", false)) {
                        val mb = o.i("milchBlur", 30)
                        RgRegler("Unschärfe", mb.toFloat(), 0f..80f, if (mb == 0) "aus" else "$mb", {}) { v -> setz("milchBlur", v.roundToInt().toString()) }
                        MilchFarbWahl(o.i("milchFarbe", 0)) { setz("milchFarbe", if (it == 0) "" else it.toString()) }
                        // Hintergrund "Handy-Hintergrund": Android muss das Bild herausgeben
                        if (z.thema.hintergrund == "system") {
                            val ctxM = androidx.compose.ui.platform.LocalContext.current
                            @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen       // nach Rueckkehr aus den Einstellungen neu pruefen
                            if (TapetenZugriff.live(ctxM))
                                Text("Dein Handy-Hintergrund ist ein Live-Hintergrund (bewegt) – den kann Android nicht als Bild herausgeben, er bleibt hinter dem Rahmen scharf. Mit einem normalen Bild als Hintergrund geht es.",
                                     color = LocalPalette.current.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                            else if (TapetenZugriff.brauchtRecht(ctxM))
                                Schalter("Handy-Hintergrund unscharf machen", false,
                                         "Android gibt das Hintergrundbild nur mit „Zugriff auf alle Dateien“ heraus. Antippen und dort einschalten – gelesen wird nur das Hintergrundbild.") { TapetenZugriff.erlauben(ctxM) }
                        }
                    }
                    if (o.b("hg", false)) {
                        val dc = o.i("deck", 12)
                        RgRegler("Füllung – Deckkraft", dc.toFloat(), 3f..100f, "$dc %", {}) { v -> setz("deck", v.roundToInt().toString()) }
                    }
                    Wahl("Art", listOf("" to "Durchgezogen", "strich" to "Gestrichelt"), o.s("art")) { setz("art", it) }
                    val ab = o.i("abstand", 4)
                    RgRegler("Abstand zu den Bausteinen", ab.toFloat(), 0f..16f, "$ab dp", {}) { v -> v.roundToInt().let { setz("abstand", if (it == 4) "" else it.toString()) } }
                }
                in KNOEPFE -> Gruppe("Knopf") {
                    Schalter("Beschriftung", o.b("beschr", true)) { setz("beschr", if (it) "" else "0") }
                    if (o.b("beschr", true) && b.typ in setOf("lampe", "kamera", "wlan", "bluetooth")) {
                        var name by remember(b.id) { mutableStateOf(b.titel) }
                        Eingabe(name, { name = it; onAendern(b.copy(titel = it)) }, "Text (leer = Standard)")
                    }
                    val g = o.i("symGr", 100)
                    RgRegler("Symbolgröße", g.toFloat(), 50f..200f, "$g %", {}) { v -> val r = (v / 5f).roundToInt() * 5; setz("symGr", if (r == 100) "" else r.toString()) }
                }
            }

            // ---- fuer alle
            Gruppe("Fläche") {
                val eigen = b.typ in setOf("uhr", "uhrklein", "favoriten", "alphabet", "rahmen")
                if (b.typ != "rahmen") Schalter("Hintergrund", o.b("hg", !eigen)) { setz("hg", if (it == !eigen) "" else if (it) "1" else "0") }
                Wahl("Ecken", listOf("" to "Rund", "eckig" to "Eckig"), o.s("ecke")) { setz("ecke", it) }
                if (b.typ != "rahmen") Schalter("Rahmen", o.b("rahmen", false)) { setz("rahmen", if (it) "1" else "") }
            }
            if (b.opt.isNotBlank()) MenueEintrag("Alles zurücksetzen", "Wieder wie im Design") { onAendern(b.copy(opt = "")) }
            Spacer(Modifier.height(4.dp))
            Knopf("Fertig", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { onZu() }
        }
    }
}


@Composable
internal fun MusikKennerEinstellung() {
    val z = LocalZustand.current
    Schalter("Automatisch (erkennt die Musik)", z.musikAuto, "Schätzt ein, was läuft (ruhig, chill, Pop, Rock, Elektro, Klassik, Sprache), und reagiert jeweils anders – auch auf Aufbau und Drop. Deine Stärke ist die Obergrenze") { z.musikAutoSetzen(it) }
    if (z.musikAuto) {
        val txt by androidx.compose.runtime.produceState(MusikKenner.text()) { while (true) { value = MusikKenner.text(); kotlinx.coroutines.delay(500) } }
        Text(txt, color = LocalPalette.current.akzent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
}

/** "Hinter dem Alphabet" - dieselbe Wahl im Alphabet-Baustein (✎) und unter Design → Alphabet */
@Composable
internal fun HinterAlphabetWahl() {
    val z = LocalZustand.current
    Wahl("Hinter dem Alphabet", listOf("" to "Wie im Design", "welle" to "Welle", "kante" to "Swiss-Kante", "linie" to "Swiss-Linie",
                                       "kreise" to "Kreise") + (emptyList()) +
                                listOf("aus" to "Nichts"),
         z.abcHinter, when (z.abcHinter) {
             "welle" -> "Am Rand hinter den Buchstaben eine Welle – sie kommt dem Daumen beim Wischen entgegen"
             "kante" -> "Felder im Raster ploppen auf Höhe des Daumens auf"
             "linie" -> "Feine Haarlinie, ein roter Block rastet beim Daumen ein"
             "kreise" -> "Kreise breiten sich aus, wo der Daumen das Alphabet berührt"
             "rosette" -> "Banknoten-Rosette folgt dem Daumen"
             "aus" -> "Nur die Buchstaben"
             else -> null
         }) { z.abcHinterSetzen(it) }
    Schalter("Bei Benachrichtigungen", z.benWelle, "Kommt eine Nachricht (WhatsApp, Signal, Mail …), läuft oben kurz eine kleine Welle herein – in der Farbe der App, Form wie hier gewählt") { z.benWelleSetzen(an = it) }
    if (z.benWelle) {
        Schalter("In der Farbe der App", z.benWelleFarbe, "Aus: in der Farbe der Welle") { z.benWelleSetzen(farbe = it) }
        RgRegler("Wie weit runter", z.benWelleTiefe.toFloat(), 15f..120f, "${z.benWelleTiefe} dp", {}) { v -> z.benWelleSetzen(tiefe = v.roundToInt()) }
        RgRegler("Dauer", z.benWelleDauer / 100f, 10f..60f, "${"%.1f".format(z.benWelleDauer / 1000f)} s", {}) { v -> z.benWelleSetzen(dauer = v.roundToInt() * 100) }
        if (!Musik.hatRecht(androidx.compose.ui.platform.LocalContext.current))
            Text("Braucht den Benachrichtigungs-Zugriff (System → Berechtigungen)", color = LocalPalette.current.warnung, fontSize = 13.sp,
                   modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
    }
    if (z.abcHinter == "welle" || (z.abcHinter == "" && (z.thema.alphabet == "welle" || z.thema.hintergrund == "wellen"))) {
        Schalter("Welle aus Milchglas", z.welleGlas, "Im Wasser der Welle ist der Startbildschirm darunter unscharf – die Kante bleibt") { z.welleGlasSetzen(an = it) }
        if (z.welleGlas) RgRegler("Unschärfe", z.welleGlasBlur.toFloat(), 4f..60f, "${z.welleGlasBlur}", {}) { v -> z.welleGlasSetzen(blur = v.roundToInt()) }
    }
}

/** Alphabet: bei welchen Buchstaben (oder #) ein anderer Ton kommt - und welcher */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AndererTon() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bei = z.alphabetTonBei
    Text("Anderer Ton bei", color = p.text, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
    Text(if (bei.isEmpty()) "Noch bei keinem – Buchstaben antippen (mehrere gehen)" else bei.sortedBy { if (it == "#") " " else it }.joinToString(" "),
         color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        (listOf("#") + ('A'..'Z').map { it.toString() }).forEach { b ->
            val an = b in bei
            Box(Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (an) p.akzent else p.text.copy(alpha = 0.07f))
                    .clickable {
                        z.alphabetTonSetzen(bei = if (an) bei - b else bei + b)
                        if (!an) Klack.spielen(ctx.applicationContext, z.alphabetTonArt)
                    }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                androidx.compose.material3.Text(b, color = if (an) p.aufAkzent else p.text, fontSize = 15.sp)
            }
        }
    }
    Wahl("Welcher Ton", Klack.ARTEN, z.alphabetTonArt, "Antippen spielt ihn einmal vor – läuft über die Medien-Lautstärke") {
        z.alphabetTonSetzen(art = it); Klack.spielen(ctx.applicationContext, it)
    }
    if (bei.isNotEmpty()) MenueEintrag("Bei keinem mehr") { z.alphabetTonSetzen(bei = emptySet()) }
}
