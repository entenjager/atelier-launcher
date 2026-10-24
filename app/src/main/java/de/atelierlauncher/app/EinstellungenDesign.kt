package de.atelierlauncher.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Slider
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

// ============================================================================
//  Design: alles, was man SIEHT, an einem Ort. Hauptseite = Liste der Bereiche, jeder Bereich
//  ist ein eigenes Untermenue (manche fassen zwei Dinge zusammen, z. B. Uhr + unter der Uhr).
// ============================================================================
@Composable
internal fun DesignSeite() {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    fun auf(s: String) = navi.auf(Ebene.Einstellungen(s))
    fun name(liste: List<Pair<String, String>>, wert: String) = liste.firstOrNull { it.first == wert }?.second ?: wert
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // kompletter Stand zum Wiederherstellen (Bausteine + Design + Einstellungen)
        MeineDesignsGruppe()
        Gruppe {
            MenueEintrag("Themes", ("Vorlagen · weitere Designs laden · teilen – aktiv: ") + t.name, rechts = "›") { auf("themen") }
            MenueEintrag("Farben", "Hell/dunkel, Farbschema, Akzent, Wellenfarbe", rechts = "›") { auf("d_farben") }
            MenueEintrag("Hintergrund & Tiefe", designName(t.hintergrund) + " · 3D · Papier", rechts = "›") { auf("d_hintergrund") }
            MenueEintrag("Sperrbildschirm", "Hintergrund", rechts = "›") { auf("d_sperr") }
        }
        Gruppe {
            MenueEintrag("Uhr & unter der Uhr", "Größe, Stil, Farbe · Datum, Wetter, Akku, Termin, Musik", rechts = "›") { auf("d_uhr") }
            MenueEintrag("Schrift & Text", schriftName(t.schrift) + " · Überschriften", rechts = "›") { auf("d_schrift") }
            MenueEintrag("Apps & Icons", iconName(t.icons) + " · Listen, Ecken · Öffnen-Animation", rechts = "›") { auf("d_apps") }
        }
        if (Variante.lautRegler) Gruppe {
            MenueEintrag("Randgesten", "Animation: " + name(Randgesten.STILE, Randgesten.stil(prefs)), rechts = "›") { auf("d_randgesten") }
            MenueEintrag("Lautstärke-Regler", "Design: " + name(LautDesign.ARTEN, LautDesign.art(ctx)), rechts = "›") { auf("d_laut") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Farben: hell/dunkel, Farbschema, Akzent, Wellenfarbe */
@Composable
internal fun DesignFarben() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    VorschauSeite({ MiniLauncher() }) {
        Gruppe("Farben") {
            Wahl("Hell oder dunkel", listOf("0" to "Hell", "1" to "Dunkel", "2" to "Wie Handy"), t.modus.toString()) { setze(t.copy(modus = it.toInt())) }
            Wahl("Farbschema", Designs.nurFrei("farben", listOf("sw" to "Schwarz & Weiß", "weich" to "Weich", "kontrast" to "Kontrast (Swiss)", "geld" to "Banknote", "glas" to "Liquid Glass", "skizze" to "Gezeichnet", "deko" to "Art Déco"), t.farben), t.farben,
                 when (t.farben) { "kontrast" -> "Tiefes Schwarz oder Papierweiß, keine Schatten"
                                   "sw" -> "Nur Schwarz und Weiß – keine Grautöne, keine Schatten"
                                   "geld" -> "Brauner Strick und Tintengrün – dunkel: Schokobraun mit hellen Linien"
                                   "deko" -> "Schwarzer Marmor, Gold und Elfenbein – Risse werden mit Gold gekittet (Kintsugi) – immer dunkel"
                                   "skizze" -> "Blauer Kuli auf Papier: alles von Hand gezeichnet, nichts ist ganz gerade – dunkel: weiße Kreide auf schwarzer Tafel"
                                   "glas" -> "Glasflächen statt grauer Karten – mit Hintergrund „Glas“ (dein Foto) brechen sie es echt"
                                   else -> "Sanfte Töne mit leichtem Schatten" }) { setze(t.copy(farben = it)) }
            Text("Akzentfarbe", fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                (AKZENTE + SWISS_FARBEN).distinct().forEach { a ->
                    FarbPunkt(
                        if (a == 0) Brush.sweepGradient(listOf(Color(0xFFFF8A80), Color(0xFFFFD54F), Color(0xFFA5D6A7),
                            Color(0xFF8AB4FF), Color(0xFFCE93D8), Color(0xFFFF8A80))) else SolidColor(Color(a)),
                        t.akzent == a,
                    ) { setze(t.copy(akzent = a)) }
                }
            }
            EigeneFarbe(t.akzent) { setze(t.copy(akzent = it)) }
            Text("Wellenfarbe", fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, top = 14.dp))
            Text("Für alle Wellen: Hintergrund, Wellen-Alphabet, Kreise, Randgesten, Lautstärke-Regler, Sperrbildschirm", color = p.leise, fontSize = 13.sp,
                 modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // erster Punkt: automatisch (weiss im Dunkeln, petrol im Hellen)
                FarbPunkt(Brush.linearGradient(listOf(Color.White, Color(0xFF1E5F74))), t.welle == 0) { setze(t.copy(welle = 0)) }
                (listOf(0xFF1E5F74.toInt(), 0xFFFFFFFF.toInt(), 0xFF111111.toInt()) + AKZENTE.filter { it != 0 } + SWISS_FARBEN).distinct().forEach { w ->
                    FarbPunkt(SolidColor(Color(w)), t.welle == w) { setze(t.copy(welle = w)) }
                }
            }
            EigeneFarbe(t.welle) { setze(t.copy(welle = it)) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Hintergrund, Tiefe (3D) und Papier */
@Composable
internal fun DesignHintergrund() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    VorschauSeite({ MiniLauncher() }) {
        Gruppe("Hintergrund") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp)) {
                listOf("bild", "video", "marmor", "papier", "glas", "swiss", "geld", "wellen", "eis", "pixel", "sterne", "polar", "schwarz", "system", "lottie")
                    .filter { Designs.frei("hintergrund", it) || (it == t.hintergrund && !Designs.raus("hintergrund", it)) }.forEach { art ->
                    DesignKachel(art, t.hintergrund == art) {
                        setze(t.copy(hintergrund = art))
                        if (art == "video" && !EigeneDateien.video(ctx).exists()) videoWaehlen(ctx)
                        if (art == "bild" && !EigeneDateien.bild(ctx).exists()) bildWaehlen(ctx)
                        if (art == "lottie" && !EigeneDateien.lottie(ctx).exists()) lottieWaehlen(ctx)
                        if (art == "glas" && !GlasFoto.datei(ctx).exists()) glasFotoWaehlen(ctx)
                    }
                }
            }
            when (t.hintergrund) {
                "video" -> MenueEintrag("Video wählen …", "Aus der Galerie oder den Dateien – läuft stumm in Schleife") { videoWaehlen(ctx) }
                "bild" -> MenueEintrag("Bild wählen …", "Aus der Galerie oder den Dateien") { bildWaehlen(ctx) }
                "lottie" -> MenueEintrag("Lottie-Datei wählen …", "JSON – der Daumen am Alphabet steuert sie") { lottieWaehlen(ctx) }
                "glas" -> {
                    @Suppress("UNUSED_VARIABLE") val neu = z.eigenStand + GlasFoto.stand.intValue
                    val da = GlasFoto.datei(ctx).exists()
                    MenueEintrag(if (da) "Anderes Hintergrundbild …" else "Hintergrundbild wählen …",
                                 "Am besten dasselbe Bild wie dein Handy-Hintergrund – das Glas bricht genau dieses Bild") { glasFotoWaehlen(ctx) }
                    if (da) MenueEintrag("Bild entfernen", "Dann wieder weiche Farbflächen") { glasFotoWeg(ctx) }
                }
            }
            if (t.hintergrund == "eis" || t.hintergrund == "pixel") {
                Text(if (t.hintergrund == "eis") "Farbe der Eis-Zacken" else "Farbe der Pixel", fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
                val liste = (if (t.hintergrund == "eis") EIS_FARBEN else PIXEL_FARBEN).map { it.second } + p.akzent.toArgb()
                if (t.hintergrund == "eis") FarbReihe(liste, z.eisFarbe) { z.eisFarbeSetzen(it) }
                else FarbReihe(liste, z.pixelFarbe) { z.pixelFarbeSetzen(it) }
            }
            if (t.hintergrund in setOf("wellen", "eis", "pixel") || (t.alphabet == "welle" && !z.eigeneDaumenEbene)) {
                Schalter("Zeigt den Akku", z.wellenAkku, "Leerer Akku = kürzere Wellen") { z.wellenSetzen("wellenAkku", it) }
            }
            if (t.hintergrund == "eis") Schalter("Schnee", z.eisSchnee, "Schnee zwischen den Zacken") { z.eisSchneeSetzen(it) }
            MenueEintrag("Auch auf den Startbildschirm-Hintergrund", "Live-Hintergrund mit diesem Hintergrund") { (ctx as MainActivity).wellenTapete() }
        }
        Gruppe("Tiefe") {
            val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
            var an by remember { mutableStateOf(prefs.getBoolean("neigung3d", false)) }
            Schalter("3D beim Neigen", an, "Uhr, Apps" + ("") + " schweben über dem Hintergrund und verschieben sich, wenn du das Handy kippst") {
                an = it; prefs.edit().putBoolean("neigung3d", it).apply()
                if (it) Neigung.launcher.start(ctx) else Neigung.launcher.stop()
            }
        }

        Gruppe("Papier (Test)") {
            Schalter("Papier-Struktur", Papier.an.value, "Feines Korn und Fasern wie echtes Papier – über allem, auch auf dem Sperrbildschirm") {
                Papier.setzen(ctx, it)
                SperrBild.aktualisieren(ctx, erzwingen = true)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun DesignSperr() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    val sPrefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var seite by remember { mutableStateOf(false) }
    var uebergang by remember { mutableStateOf(SperrSeite.art(ctx)) }
    VorschauSeite({ MiniSperr(seite, uebergang) }) {
        Gruppe("Hintergrund") {
            run {
                val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
                @Suppress("UNUSED_VARIABLE") val neuDatei = z.eigenStand
                var medium by remember { mutableStateOf(SperrMedium.art(ctx)) }
                Wahl("Sperrbildschirm zeigt", listOf("" to "Design", "bild" to "Eigenes Bild", "video" to "Eigenes Video"), medium,
                     when (medium) {
                         "bild" -> "Ein Bild aus der Galerie oder den Dateien – nur auf dem Sperrbildschirm"
                         "video" -> "Ein Video, stumm in Schleife – läuft über den Live-Hintergrund von ${Variante.appName}"
                         else -> null
                     }) {
                    medium = it; SperrMedium.setzen(ctx, it)
                    if (it == "bild" && !EigeneDateien.sperrBild(ctx).exists()) sperrBildWaehlen(ctx)
                    if (it == "video" && !EigeneDateien.sperrVideo(ctx).exists()) sperrVideoWaehlen(ctx)
                }
                if (medium == "bild") MenueEintrag(if (EigeneDateien.sperrBild(ctx).exists()) "Anderes Bild wählen …" else "Bild wählen …",
                                                   "Galerie oder Dateien", rechts = "›") { sperrBildWaehlen(ctx) }
                if (medium == "video") {
                    MenueEintrag(if (EigeneDateien.sperrVideo(ctx).exists()) "Anderes Video wählen …" else "Video wählen …", "Galerie oder Dateien", rechts = "›") { sperrVideoWaehlen(ctx) }
                    if (!SperrMedium.liveAktiv(ctx)) MenueEintrag("Live-Hintergrund einschalten", "Einmal nötig – dort „Start- und Sperrbildschirm“ wählen", rechts = "›") {
                        (ctx as MainActivity).wellenTapete()
                    }
                }
            }
            if (SperrMedium.art(ctx) == "") run {
                val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
                var bild by remember { mutableStateOf(SperrBild.an(ctx)) }
                Schalter("Sperrbildschirm mit diesem Design", bild,
                         "Wird beim Ausschalten des Bildschirms als Bild gesetzt – " + ("") + "hell oder dunkel wie hier") {
                    bild = it; prefs.edit().putBoolean("sperrBild", it).apply()
                    if (it) SperrBild.aktualisieren(ctx, erzwingen = true)
                }
                if (bild) {
                    var bewegt by remember { mutableStateOf(prefs.getBoolean("sperrBewegt", true)) }
                    Schalter("Hintergrund bewegt sich auf dem Sperrbildschirm", bewegt,
                             if (t.hintergrund in setOf("wellen", "eis", "pixel", "polar", "sterne", "swiss"))
                                 "Zeigt den Live-Hintergrund statt eines Bildes – dafür unter Design → Hintergrund „Auch auf den Startbildschirm-Hintergrund“ einmal antippen"
                             else "Geht nur bei Wellen, Eis, Pixel, Polar, Sterne und Swiss – bei diesem Hintergrund bleibt es ein Bild") {
                        bewegt = it; prefs.edit().putBoolean("sperrBewegt", it).apply()
                        SperrBild.aktualisieren(ctx, erzwingen = true)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Schrift & Text */
@Composable
internal fun DesignSchrift() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    VorschauSeite({ MiniLauncher() }) {
        Gruppe("Schrift & Text") {
            Wahl("Schrift", Designs.nurFrei("schrift", Schriften.EINGEBAUT, t.schrift), if (Schriften.EINGEBAUT.any { it.first == t.schrift }) t.schrift else "") { setze(t.copy(schrift = it)) }
            MenueEintrag("Weitere Schriften (Handy, eigene Datei)", rechts = "›") { navi.auf(Ebene.Einstellungen("schrift")) }
            Wahl("Überschriften & Datum", Designs.nurFrei("typo", listOf("schlicht" to "Schlicht", "swiss" to "Swiss", "geld" to "Banknote"), t.typo), t.typo,
                 when (t.typo) { "swiss" -> "VERSALIEN mit Sperrung, Balken davor, Uhr eng gesetzt"
                                 "geld" -> "Rauten-Zier, Uhr im Oval-Rahmen, Karten mit Doppellinie"
                                 else -> "Normale kleine Überschriften" }) { setze(t.copy(typo = it)) }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Apps & Icons: Icon-Stil, Listen, Ecken, Animation beim Oeffnen */
@Composable
internal fun DesignApps() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    VorschauSeite({ MiniLauncher() }) {
        Gruppe("Icons") {
            val icons = Designs.nurFrei("icons", ICON_STILE, t.icons)
            Wahl("Stil", icons, if (icons.any { it.first == t.icons }) t.icons else "", when (t.icons) {
                "umriss" -> "Die Original-Logos, nur als Linie"
                "skizze" -> "Die Logos wie mit Kuli nachgezogen, in einem handgemalten Kreis"
                "kintsugi" -> "Goldring, darin das Logo – zerbrochen und mit Gold gekittet"
                "swiss" -> "Eigene eckige Zeichen statt der Logos"
                "einfarbig" -> "Logos in deiner Akzentfarbe"
                "motiv" -> "Nur das Motiv in Schwarz bzw. Weiß, ohne Hintergrund"
                "punkte" -> "Ein farbiger Punkt statt Icon"
                "keine" -> "Nur die Namen"
                "original" -> "Die normalen App-Icons"
                else -> "Icon-Paket: " + iconName(t.icons)
            }) { setze(t.copy(icons = it)) }
            MenueEintrag("Icon-Pakete & einzelne Icons", rechts = "›") { navi.auf(Ebene.Einstellungen("icons")) }
        }

        Gruppe("Formen & Listen") {
            Wahl("Ecken", listOf("rund" to "Rund", "eckig" to "Eckig"), t.ecken) { setze(t.copy(ecken = it)) }
            Wahl("App-Listen", Designs.nurFrei("liste", listOf("schlicht" to "Schlicht", "einsatz" to "Einsatzliste"), t.liste), t.liste,
                 if (t.liste == "einsatz") "Nummern 01, 02 …, Trennlinien, Namen in Versalien" else "Nur Icon und Name") { setze(t.copy(liste = it)) }
        }

        Gruppe("Animation beim Öffnen von Apps") {
            val arten = listOf("wachsen", "sofort", "aufziehen", "zoom", "aus")
            var art by remember { mutableStateOf(StartRahmen.art(ctx)) }
            Segmente(listOf("Wachsen", "Ohne", "Aufklappen", "Zoom", "Samsung"), arten.indexOf(art).coerceAtLeast(0)) {
                art = arten[it]; StartRahmen.artSetzen(ctx, art)
            }
            Text(if (art == "wachsen") "Am schnellsten: die App wächst sofort aus der angetippten Stelle, während sie im Hintergrund fertig startet."
                 else if (art == "sofort") "Keine Animation – der Launcher bleibt stehen, bis die App ihr erstes Bild hat (wirkt bei großen Apps langsamer)."
                 else "Die App wächst aus der angetippten Zeile heraus. Wie schnell, bestimmen die Animations-Faktoren in den Entwickleroptionen.",
                 color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Alphabet: Bewegung, Anzeige, im Liegen */
@Composable
internal fun DesignAlphabet() = AlphabetSeite()      // steht jetzt unter Einstellungen → Alphabet

/** Aussehen und Verhalten des Alphabets (Teil der Alphabet-Seite in den Einstellungen) */
@Composable
internal fun androidx.compose.foundation.layout.ColumnScope.AlphabetAussehen() {
    val z = LocalZustand.current
    val abcNavi = LocalNavi.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
        Gruppe("Alphabet") {
            Wahl("Bewegung", Designs.nurFrei("alphabet", listOf("welle" to "Welle (Bogen)", "swiss" to "Feste Leiste"), t.alphabet), t.alphabet,
                 if (t.alphabet == "welle") "Buchstaben weichen dem Daumen im Bogen aus" else "Leiste bleibt stehen, nur der Buchstabe wird markiert") { setze(t.copy(alphabet = it)) }
            Wahl("Anzeige neben dem Daumen", Designs.nurFrei("buchstabe", listOf("gross" to "Großer Buchstabe", "karte" to "Karte (A B [C] D E)"), t.buchstabe), t.buchstabe,
                 if (t.buchstabe == "gross") "Ein großer Buchstabe" else "Der Buchstabe groß im Farbfeld, darüber und darunter die Nachbarn") { setze(t.copy(buchstabe = it)) }
        }
        // wie bei den Randgesten: Leiste selbst einstellen
        Gruppe("Größe") {
            RgRegler("Buchstaben", z.alphabetGroesse.toFloat(), 70f..160f, "${z.alphabetGroesse} %", {}) { z.alphabetGroesseSetzen((it / 5f).roundToInt() * 5) }
            // Atelier: Platz und Groesse bestimmt der Alphabet-Baustein auf dem Startbildschirm
            MenueEintrag("Platz und Größe", "Lange auf den Startbildschirm drücken → Alphabet-Baustein ziehen", rechts = "›") {
                abcNavi.auf(Ebene.Einstellungen("rasterHaupt"))
            }
            Schalter("Leiste zieht mit", z.alphabetMitziehen, "Daumen über das A hinaus = Alphabet wandert mit nach oben (und wieder runter), beim Loslassen zurück") {
                z.alphabetMitziehenSetzen(it)
            }
            MenueEintrag("Wie am Anfang", "Buchstaben 100 %, Höhe 100 %, Dicke 36 dp, Abstand 24 dp") {
                z.alphabetGroesseSetzen(100); z.alphabetLeisteSetzen(100, 36, 24)
            }
        }
        // alles, was beim Wischen uebers Alphabet passiert - gilt fuer jeden Hintergrund
        Gruppe("Beim Wischen") {
            // dieselbe Wahl wie im Alphabet-Baustein (✎) - vorher zwei verschiedene, die sich widersprachen
            HinterAlphabetWahl()
            if (!z.eigeneDaumenEbene && (t.alphabet == "welle" || t.hintergrund in setOf("wellen", "eis", "pixel")))
                Schalter("Wellen folgen dem Daumen", z.wellenDaumen, "Die Wellen am Rand kommen beim Wischen weiter herein") { z.wellenSetzen("wellenDaumen", it) }
            Schalter("Nach Namenslänge ordnen", z.abcLaenge, "Der kürzeste App-Name steht auf Höhe des Daumens, nach oben und unten werden die Namen immer länger. Aus = nach dem Alphabet") { z.abcLaengeSetzen(it) }
            Schalter("Klack-Geräusch", z.alphabetKlick, "Leises Klicken bei jedem Buchstaben (auch wenn die Berührungstöne von Android aus sind)") { z.alphabetKlickSetzen(it) }
            Schalter("Kreise beim Wischen", z.wellenTropfen, "Kleine Kreiswellen, wo der Daumen das Alphabet berührt – in der Wellenfarbe") { z.wellenSetzen("wellenTropfen", it) }
        }

        Gruppe("Im Liegen") {
            val akt = ctx as MainActivity
            Schalter("Alphabet nach links", z.spiegelnAn, "Auf der rechten Seite liegen (80–100°) → Alphabet und Design links") {
                z.spiegelnSetzen(it)
                akt.lageNeu()
            }
            if (z.spiegelnAn) Text(
                "Gerade: " + (if (z.gespiegelt) "Alphabet links" else "normal") + "  ·  ${z.lageWinkel}°",
                color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
        }
}

/** Randgesten: Animation beim Reinwischen */
@Composable
internal fun DesignRandgesten() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    val rPrefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var stil by remember { mutableStateOf(Randgesten.stil(rPrefs)) }
    VorschauSeite({ MiniRandgesten(stil) }) {
        Gruppe("Animation beim Reinwischen") {
            Wahl("Stil", Designs.nurFrei("randgeste", Randgesten.STILE, stil), stil, Randgesten.stilHinweis(stil)) {
                stil = it; Randgesten.stilSetzen(ctx, it)
            }
        }
        Gruppe {
            MenueEintrag("Gesten & Griffe einstellen", "Was welche Geste macht, Größe und Lage der Griffe", rechts = "›") {
                navi.auf(Ebene.Einstellungen("randgesten"))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Lautstaerke-Regler: Design (nur Aussehen) */
@Composable
internal fun DesignLaut() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Gruppe("Design") {
            val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
            var design by remember { mutableStateOf(LautDesign.art(ctx)) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Designs.nurFrei("laut", LautDesign.ARTEN, design).forEach { (wert, name) ->
                    val gewaehlt = wert == design
                    Column(
                        Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (gewaehlt) p.akzent.copy(alpha = 0.18f) else Color.Transparent)
                            .padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { c -> LautRegler(c, 0.32f, {}, {}, {}, {}).apply { stand = 0.62f; farbenLaden(); this.design = wert } },
                            update = { v -> v.design = wert; v.invalidate() },
                            modifier = Modifier.size(64.dp, 230.dp),
                        )
                        Chip(name, gewaehlt = gewaehlt) {
                            design = wert; prefs.edit().putString("lautDesign", wert).apply()
                        }
                    }
                }
            }
            Text("Die Vorschau kannst du mit dem Finger hoch- und runterziehen. Bedienung und Verhalten bleiben bei jedem Design gleich.",
                 color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        Gruppe {
            MenueEintrag("Regler einstellen", "Ein/aus, Feinbereich", rechts = "›") { navi.auf(Ebene.Einstellungen("laut")) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Uhr und was unter der Uhr steht */
@Composable
internal fun DesignUhr() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    fun setze(neu: Thema) = z.themaSetzen(neu)
    @Suppress("UNUSED_VARIABLE") val unbenutzt = Triple(p, navi, ctx)
    VorschauSeite({ MiniLauncher() }) {
        Gruppe("Uhr") {
            MenueEintrag("Uhr anpassen", "Größe, Stil, Farbe, Sekunden", rechts = "›") { navi.auf(Ebene.Uhr) }
        }
        UnterDerUhr()
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Themes
/** Akzent eines Themes ohne eigene Farbe - wie es selbst aussieht, nicht wie das gerade aktive */
internal fun vorschauAkzent(t: Thema, dunkel: Boolean, systemAkzent: Color): Color = when (t.farben) {
    "skizze" -> Color(if (dunkel) Skizze.KREIDE else Skizze.TINTE_BLAU)
    "deko" -> Color(Deko.GOLD)
    "kontrast" -> SWISS_ROT
    "geld" -> Color(Geld.gruen(dunkel))
    "glas" -> if (dunkel) Color(0xFF8AB4FF) else Color(0xFF3A6BFF)
    "sw" -> if (dunkel) Color.White else Color.Black
    else -> systemAkzent                 // Material You: Farbe vom Handy-Hintergrund
}

/** Icon-Stile (Design → Icons und Baukasten → App-Liste → ✎) */
internal val ICON_STILE = listOf("original" to "Original", "umriss" to "Umriss", "swiss" to "Piktogramme",
    "einfarbig" to "Einfarbig", "motiv" to "Nur Motiv (s/w)", "punkte" to "Punkte", "skizze" to "Gezeichnet", "kintsugi" to "Kintsugi", "keine" to "Keine")

internal fun vorschauFarben(t: Thema, systemDunkel: Boolean): List<Color> {
    val dunkel = when (t.modus) { 0 -> false; 1 -> true; else -> systemDunkel }
    return when (t.hintergrund) {
        "sterne" -> if (dunkel) listOf(Color.Black, Color(0xFF0B1430)) else listOf(Color(0xFFE9EEF6), Color(0xFFF8F1EA))
        "polar" -> if (dunkel) listOf(Color(0xFF02060A), Color(0xFF1B6B58)) else listOf(Color(0xFFEAF4F2), Color(0xFFC9E9DD))
        "wellen" -> if (dunkel) listOf(Color.Black, Color(0xFF3A3A3A)) else listOf(Color(0xFFF1EEE7), Color(0xFF9FC3CE))
        "system" -> listOf(Color(0xFF55606A), Color(0xFF8A7F74))
        "glas" -> if (dunkel) listOf(Color(0xFF243A9E), Color(0xFF7A3FB8)) else listOf(Color(0xFFA9C2FF), Color(0xFFFFC6DA))
        "marmor" -> listOf(Color(0xFF0B0B0D), Color(0xFF6B5A33))
        // Papier: nach dem Theme selbst (Kreidetafel = dunkel), nicht nach dem gerade aktiven
        "papier" -> if (dunkel && t.farben == "skizze") listOf(Color(Skizze.TAFEL), Color(0xFF2A2E2C)) else listOf(Color(Skizze.PAPIER_HELL), Color(0xFFF1E8C8))
        "geld" -> if (dunkel) listOf(Color(Geld.PAPIER_DUNKEL), Color(0xFF1F4A30)) else listOf(Color(Geld.PAPIER_HELL), Color(0xFFB9CFA6))
        else -> if (dunkel) listOf(Color.Black, Color.Black) else listOf(Color(0xFFF1EEE7), Color(0xFFF1EEE7))
    }
}

@Composable
internal fun ThemaKarte(t: Thema, aktiv: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val dunkel = androidx.compose.foundation.isSystemInDarkTheme()
    val farben = vorschauFarben(t, dunkel)
    val hell = when (t.modus) { 0 -> false; 1 -> true; else -> dunkel }.not()
    // Material You (Farbe vom Handy-Hintergrund) - unabhaengig vom gerade aktiven Theme
    val kontext = androidx.compose.ui.platform.LocalContext.current
    val materialAkzent = if (android.os.Build.VERSION.SDK_INT >= 31)
        Color(kontext.getColor(if (hell) android.R.color.system_accent1_600 else android.R.color.system_accent1_200)) else p.akzent
    Column(Modifier.width(104.dp).clip(rund(16.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(72.dp).clip(rund(14.dp))
                .background(Brush.horizontalGradient(farben))
                .border(if (aktiv) 2.dp else 0.dp, if (aktiv) p.akzent else Color.Transparent, rund(14.dp))
                .padding(8.dp),
        ) {
            Text("12:34", color = if (t.text != 0) Color(t.text) else if (hell) Color(0xFF191C1B) else Color.White, fontSize = 16.sp,
                 fontWeight = if (t.uhr.dicke == 2) FontWeight.Bold else FontWeight.Light,
                 fontFamily = Schriften.familie(t.schrift))
            Box(Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape)
                .background(if (t.akzent == 0) vorschauAkzent(t, !hell, materialAkzent) else Color(t.akzent)))
        }
        // Name in der Schrift der Vorlage (Gezeichnet in Handschrift, Art Deco in Poiret ...)
        Text(t.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = Schriften.familie(t.schrift),
             modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemenSeite() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val ctx = LocalContext.current
    val t = z.thema
    var neuerName by remember { mutableStateOf<String?>(null) }
    VorschauSeite({ MiniLauncher() }) {
        Abschnitt("Vorlagen")
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            FERTIGE_THEMEN.filter { Designs.themaFrei(it.name) }.forEach { v -> ThemaKarte(v, v.name == t.name) { z.themaSetzen(v) } }
        }
        // Atelier: weitere Designs erst laden
        WeitereDesigns()
        if (z.eigeneThemen.isNotEmpty()) {
            Abschnitt("Meine Themes")
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                z.eigeneThemen.forEach { v ->
                    Column {
                        ThemaKarte(v, v.name == t.name) { z.themaSetzen(v) }
                        Text("Löschen", color = p.leise, fontSize = 12.sp,
                             modifier = Modifier.clip(rund(8.dp)).clickable { z.themaLoeschen(v) }.padding(6.dp))
                    }
                }
            }
        }
        val name = neuerName
        if (name == null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Knopf("Aktuelles Aussehen speichern") { neuerName = if (t.name in FERTIGE_THEMEN.map { it.name }) "" else t.name }
            }
        } else {
            Eingabe(name, { neuerName = it }, "Name des Themes", fokus = true, onFertig = {
                z.themaSpeichern(name); neuerName = null
            })
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Knopf("Speichern") { z.themaSpeichern(name); neuerName = null }
                Chip("Abbrechen") { neuerName = null }
            }
        }

        Abschnitt("Teilen")
        MenueEintrag("Theme-Code kopieren", "Schick den Code z. B. per WhatsApp an Freunde") {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Theme", t.code()))
            Toast.makeText(ctx, Sprache.tr("Theme-Code kopiert"), Toast.LENGTH_SHORT).show()
        }
        MenueEintrag("Theme aus Zwischenablage einfügen", "Code kopieren, dann hier tippen") {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            val neu = themaAusCode(text)
            if (neu == null) Toast.makeText(ctx, Sprache.tr("Kein Theme-Code in der Zwischenablage"), Toast.LENGTH_SHORT).show()
            else {
                z.themaSetzen(neu)
                Toast.makeText(ctx, Sprache.tr("Theme „${neu.name}“ übernommen"), Toast.LENGTH_SHORT).show()
            }
        }
        Text("Community-Themes laufen ohne Server und ohne Konto: Ein Theme ist ein kurzer Code. " +
             "Wer ihn einfügt, bekommt genau dein Aussehen (Farben, Hintergrund, Schrift, Icons, Uhr).",
             color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Spacer(Modifier.height(20.dp))
    }
}

internal fun schriftName(s: String) = Schriften.EINGEBAUT.firstOrNull { it.first == s }?.second
    ?: s.removePrefix("datei:").substringAfterLast('/').substringBeforeLast('.')

internal fun iconName(s: String) = when {
    s == "original" -> "Original"
    s == "einfarbig" -> "Einfarbig"
    s == "punkte" -> "Punkte"
    s == "keine" -> "Keine"
    s == "swiss" -> "Swiss-Piktogramme"
    s == "umriss" -> "Umriss"
    s == "skizze" -> "Gezeichnet"
    s == "kintsugi" -> "Kintsugi"
    s == "motiv" -> "Nur Motiv (s/w)"
    else -> "Icon-Paket"
}

// ---------------------------------------------------------------- Schrift
@Composable
internal fun SchriftSeite() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val dateien by produceState<List<Pair<String, String>>?>(null, z.fortsetzen) {
        value = withContext(Dispatchers.IO) { Schriften.dateien(File(akt.filesDir, "fonts")) }
    }
    val alle = Designs.nurFrei("schrift", Schriften.EINGEBAUT, z.thema.schrift) + (dateien ?: emptyList())
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Knopf("Eigene Schrift laden (.ttf / .otf)") { akt.eigeneSchrift() }
    }
    if (dateien == null) Text("Suche Schriften …", color = p.leise, modifier = Modifier.padding(20.dp))
    LazyColumn(Modifier.fillMaxWidth()) {
        items(alle, key = { it.second }) { (name, wert) ->
            val gewaehlt = z.thema.schrift == wert
            val familie = remember(wert, Schriften.stand.intValue) { Schriften.familie(wert) }
            Row(
                Modifier.fillMaxWidth().clickable { z.themaSetzen(z.thema.copy(schrift = wert)) }
                    .background(if (gewaehlt) p.akzent.copy(alpha = 0.15f) else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontSize = 13.sp, color = p.leise)
                    Text("12:34  " + Variante.appName, fontSize = 20.sp, fontFamily = familie, maxLines = 1)
                }
                if (gewaehlt) Text("✓", color = p.akzent, fontSize = 20.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- Icons
@Composable
internal fun IconSeite() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val pakete by produceState<List<IconPaket>?>(null, z.fortsetzen) {
        value = withContext(Dispatchers.IO) { IconPakete.installiert(akt) }
    }
    fun setze(s: String) = z.themaSetzen(z.thema.copy(icons = s))
    val jetzt = z.thema.icons
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        MenueEintrag("Original", "Die normalen App-Icons", rechts = if (jetzt == "original") "✓" else null) { setze("original") }
        MenueEintrag("Einfarbig", "Alle Icons in deiner Akzentfarbe (wie Anycons / Themed Icons)",
                     rechts = if (jetzt == "einfarbig") "✓" else null) { setze("einfarbig") }
        MenueEintrag("Punkte", "Ein farbiger Punkt statt Icon (wie Niagara Dots)",
                     rechts = if (jetzt == "punkte") "✓" else null) { setze("punkte") }
        MenueEintrag("Keine", "Nur Namen, ganz schlicht", rechts = if (jetzt == "keine") "✓" else null) { setze("keine") }
        MenueEintrag("Nur Motiv (s/w)", "Nur das, was auf dem Icon abgebildet ist – schwarz bzw. weiß, ohne Hintergrund", rechts = if (jetzt == "motiv") "✓" else null) { setze("motiv") }
        // Atelier: Icon-Stile, die zu einem Design gehoeren, kommen erst mit dem Design
        fun da(w: String) = Designs.frei("icons", w) || jetzt == w
        if (da("umriss")) MenueEintrag("Umriss", "Die Original-Logos, nur als Linie gezeichnet", rechts = if (jetzt == "umriss") "✓" else null) { setze("umriss") }
        if (da("skizze")) MenueEintrag("Gezeichnet", "Die Logos wie mit Kuli nachgezogen, in einem handgemalten Kreis", rechts = if (jetzt == "skizze") "✓" else null) { setze("skizze") }
        if (da("kintsugi")) MenueEintrag("Kintsugi", "Goldring, darin das Logo – zerbrochen und mit Gold gekittet", rechts = if (jetzt == "kintsugi") "✓" else null) { setze("kintsugi") }
        if (da("swiss")) MenueEintrag("Swiss-Piktogramme", "Eigene eckige Zeichen statt der Logos", rechts = if (jetzt == "swiss") "✓" else null) { setze("swiss") }
        Text("Weitere Icon-Stile kommen mit ihrem Design: Design → Themes → Weitere Designs",
                                        color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Abschnitt("Icon-Pakete")
        val liste = pakete
        when {
            liste == null -> Text("Suche …", color = p.leise, modifier = Modifier.padding(horizontal = 20.dp))
            liste.isEmpty() -> Text(
                "Keine Icon-Pakete installiert. Kostenlos: „Arcticons“ (Linien-Icons, ähnlich Anycons), " +
                    "„Lawnicons“ oder „Delta“ aus dem Play Store bzw. F-Droid. Danach erscheinen sie hier.",
                color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
            else -> liste.forEach { ip ->
                MenueEintrag(ip.name, ip.paket, rechts = if (jetzt == "paket:${ip.paket}") "✓" else null) { setze("paket:${ip.paket}") }
            }
        }
        Abschnitt("Icon-Assistent")
        Text("Einzelnes Icon ändern: App nach rechts wischen → „Icon ändern“. Dort kannst du aus jedem " +
             "Icon-Paket ein beliebiges Bild wählen oder zum Original zurück.",
             color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp))
        if (z.eigeneIcons.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            MenueEintrag("Alle eigenen Icons zurücksetzen", "${z.eigeneIcons.size} geändert") {
                z.eigeneIcons.keys.toList().forEach { z.eigenesIcon(it, null) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
