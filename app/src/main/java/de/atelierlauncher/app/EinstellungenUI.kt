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
//  Einstellungen (lange auf eine freie Stelle druecken) und Uhr anpassen
// ============================================================================

@Composable
fun EinstellungenSeite(seite: String) {
    val navi = LocalNavi.current
    val titel = when (seite) {
        "design" -> "Design"
        "d_farben" -> "Farben"
        "d_hintergrund" -> "Hintergrund & Tiefe"
        "d_sperr" -> "Sperrbildschirm"
        "d_uhr", "startseite" -> "Uhr & unter der Uhr"
        "d_schrift" -> "Schrift & Text"
        "d_apps" -> "Apps & Icons"
        "d_alphabet" -> "Alphabet"
        "d_randgesten" -> "Randgesten"
        "d_laut" -> "Lautstärke-Regler"
        "alphabet" -> "Alphabet & Menü"
        "system" -> "System"
        "meinmenue" -> "Mein Menü"
        "codehintergrund" -> "Hintergrund-Code"
        "codealphabet" -> "Alphabet-Code (JavaScript)"
        "bastler" -> "Bastler"
        "themen" -> "Themes"
        "schrift" -> "Schrift"
        "icons" -> "Icons"
        "kopf" -> "Wetter & Termine"
        "wetter" -> "Wetter"
        "rechte" -> "Berechtigungen"
        "code" -> "Code"
        "laut" -> "Lautstärke-Regler"
        "randgesten" -> "Randgesten"
        "raster" -> "Seite bei #"
        "rasterHaupt" -> "Startbildschirm"
        "rasterSperre" -> "Sperr-Seite"
        "ueber" -> "Über ${Variante.appName}"
        "eigene" -> "Eigene Bausteine"
        "gefuehl" -> "Gefühl"
        else -> if (seite.startsWith("eigen_")) "Eigener Baustein" else "Einstellungen"
    }
    val inhalt: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit = { when (seite) {
            "design" -> DesignSeite()
            "d_farben" -> DesignFarben()
            "d_hintergrund" -> DesignHintergrund()
            "d_sperr" -> DesignSperr()
            "d_uhr", "startseite" -> DesignUhr()
            "d_schrift" -> DesignSchrift()
            "d_apps" -> DesignApps()
            "d_alphabet" -> DesignAlphabet()
            "d_randgesten" -> DesignRandgesten()
            "d_laut" -> DesignLaut()
            "alphabet" -> AlphabetSeite()
            "system" -> SystemSeite()
            "meinmenue" -> CodeEditor(
                art = "menue", laden = { MeinMenue.code(it) }, speichern = { c, t, fertig -> MeinMenue.codeSpeichern(c, t, fertig) }, beispiel = MENUE_BEISPIEL,
                hinweis = "Wird ausgeführt, wenn du auf # zur Seite wischst.")
            "bastler" -> BastlerSeite()
            "codealphabet" -> CodeEditor(
                art = "alphabet", laden = { AlphabetSkript.code(it) }, speichern = { c, t, fertig -> AlphabetSkript.codeSpeichern(c, t, fertig) },
                beispiel = ALPHABET_BEISPIEL, hinweis = "function wisch(w) wird nach jedem Wisch übers Alphabet aufgerufen.")
            "codehintergrund" -> CodeEditor(
                art = "hintergrund", laden = { CodeHintergrundDaten.code(it) },
                speichern = { c, t, fertig -> CodeHintergrundDaten.codeSpeichern(c, t) { ok -> LocalZustandHalter.z?.let { it.eigenStand++ }; fertig(ok) } },
                beispiel = HINTERGRUND_BEISPIEL, hinweis = "function male(g) wird ca. 30-mal pro Sekunde aufgerufen.")
            "themen" -> ThemenSeite()
            "schrift" -> SchriftSeite()
            "icons" -> IconSeite()
            "kopf" -> KopfSeite()
            "wetter" -> WetterSeite()
            "rechte" -> RechteSeite()
            "code" -> CodeSeite()
            "laut" -> LautSeite()
            "randgesten" -> RandgestenSeite()
            "raster" -> RasterEditor()
            "rasterHaupt" -> RasterEditor("start")
            "rasterSperre" -> RasterEditor("sperre")
            "ueber" -> UeberSeite()
            "eigene" -> EigeneBausteineSeite()
            "gefuehl" -> GefuehlSeite()
            else -> if (seite.startsWith("eigen_")) EigenerBausteinEditor(seite.removePrefix("eigen_"))
                    else if (NeuStil.an) NeuStartSeite() else StartSeite()
        } }
    if (NeuStil.an) NeuSeite(seite, titel, inhalt)
    else Blatt(onSchliessen = { navi.alleZu() }, vollbild = true, schleier = 0.5f) {
        BlattKopf(titel, if (seite == "start") "Fertig" else "Zurück") { navi.zu() }
        inhalt()
    }
}

// ============================================================================
//  Aufbau: Startseite = 5 grosse Kacheln. Jede Kachel = eine Seite mit wenigen, klar
//  getrennten Gruppen (Karten). Nie mehr als eine Ebene tief, bevor man etwas einstellt.
// ============================================================================

/** Karte mit optionaler Ueberschrift - darin Schalter, Eintraege, Regler */
@Composable
internal fun Gruppe(titel: String? = null, inhalt: @Composable () -> Unit) {
    if (NeuStil.an && !Stil.typoSwiss) { NeuGruppe(titel, inhalt); return }
    val p = LocalPalette.current
    if (titel != null) {
        if (Stil.typoGeld) Row(Modifier.padding(start = 18.dp, end = 28.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GeldZier(p.akzent)
            Spacer(Modifier.width(8.dp))
            Text(titel.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp)
        } else if (Stil.typoSwiss) Row(Modifier.padding(start = 16.dp, end = 28.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 12.dp).background(p.akzent))
            Spacer(Modifier.width(8.dp))
            Text(titel.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        } else Text(titel, color = p.leise, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 28.dp, end = 28.dp, top = 16.dp, bottom = 6.dp))
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
            .clip(rund(22.dp)).karteFlaeche(p, 22.dp, 0.06f)
            .then(if (Stil.typoGeld) Modifier.geldRahmen(p.akzent) else Modifier).padding(vertical = 6.dp),
    ) { inhalt() }
}

/** grosse Kachel auf der Startseite der Einstellungen */
@Composable
private fun Kachel(titel: String, unter: String, modifier: Modifier, zeichen: @Composable () -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    Column(
        modifier.clip(rund(24.dp)).karteFlaeche(p, 24.dp, 0.06f)
            .clickable(onClick = onClick).padding(16.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(p.akzent.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { zeichen() }
        Spacer(Modifier.height(12.dp))
        Text(titel, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(unter, color = p.leise, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun Zeichen(text: String) {
    Text(text, color = LocalPalette.current.akzent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

internal fun designName(art: String) = when (art) {
    "wellen" -> "Wellen"; "eis" -> "Eis-Zacken"; "pixel" -> "Pixel"; "sterne" -> "Sternenhimmel"
    "polar" -> "Polarlicht"; "schwarz" -> "Einfarbig"; "swiss" -> "Swiss"; "geld" -> "Banknote"; "glas" -> "Glas · dein Foto"; "papier" -> "Papier"; "marmor" -> "Marmor"; "video" -> "Video"; "bild" -> "Bild"; "lottie" -> "Lottie"; "code" -> "Eigener Code"
    else -> "Handy-Hintergrund"
}

@Composable
internal fun StartSeite() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        if (!akt.istStandard()) {
            // wichtigster Hinweis ganz oben
            Row(
                Modifier.padding(vertical = 6.dp).fillMaxWidth().clip(rund(20.dp)).background(p.akzent)
                    .clickable { akt.standardLauncher() }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Als Startbildschirm festlegen", color = p.aufAkzent, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("→", color = p.aufAkzent, fontSize = 18.sp)
            }
        }
        val abstand = Arrangement.spacedBy(12.dp)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = abstand) {
            Row(horizontalArrangement = abstand) {
                Kachel("Design", "Alles, was man sieht: Farben, Hintergrund, Uhr, Icons, Randgesten, Lautstärke, Sperrbildschirm",
                       Modifier.weight(1f), {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Brush.sweepGradient(
                        listOf(Color(0xFFFF8A80), Color(0xFFFFD54F), Color(0xFFA5D6A7), Color(0xFF8AB4FF), Color(0xFFCE93D8), Color(0xFFFF8A80)))))
                }) { navi.auf(Ebene.Einstellungen("design")) }
                // Atelier: Startbildschirm aus Bausteinen (auch: lange druecken auf dem Startbildschirm)
                Kachel("Startbildschirm", "Bausteine, Favoriten, Knöpfe, Anzeigen", Modifier.weight(1f), { Zeichen("▦") }) {
                    navi.auf(Ebene.Einstellungen("rasterHaupt"))
                }
            }
            Row(horizontalArrangement = abstand) {
                Kachel("Alphabet", "Aussehen, Wischen", Modifier.weight(1f), { Zeichen("A") }) {
                    navi.auf(Ebene.Einstellungen("alphabet"))
                }
                // die Seite selbst gestalten (Bausteine im Raster); weitere Einstellungen von dort aus
                Kachel("Seite bei #", "Auf # nach links: erscheint in der App-Liste", Modifier.weight(1f), { Symbol("termin", p.akzent, 20.dp) }) {
                    navi.auf(Ebene.Einstellungen("raster"))
                }
            }
            Row(horizontalArrangement = abstand) {
                // eigener Code (Bastler, JavaScript) gibt es nirgends mehr in den Einstellungen
                // oeffentlich: Randgesten direkt hier - als Erweiterung (erst laden)
                Kachel("Randgesten", if (Erweiterung.randgesten) "Vom Rand reinwischen" else "Erweiterung – noch nicht geladen",
                       Modifier.weight(1f), { Zeichen("⇥") }) { navi.auf(Ebene.Einstellungen("randgesten")) }
                Kachel("System", "Berechtigungen", Modifier.weight(1f), { Symbol("zahnrad", p.akzent, 20.dp) }) {
                    navi.auf(Ebene.Einstellungen("system"))
                }
            }
        }
        // Atelier: eigene Bausteine selbst bauen (HTML/CSS/JavaScript, mit Anleitung)
        MenueEintrag("Eigene Bausteine", "Selbst bauen – kleine Anleitung, Code einfügen, Vorschau", rechts = "›") {
            navi.auf(Ebene.Einstellungen("eigene"))
        }
        LauncherWahl()
        MenueEintrag("Über ${Variante.appName}", "Version, Datenschutz, Schriften, Einführung", rechts = "›") { navi.auf(Ebene.Einstellungen("ueber")) }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- Unter der Uhr (Teil von Design → Uhr)
@Composable
internal fun UnterDerUhr() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val akt = LocalContext.current as MainActivity
    val k = z.kopf
    val stil = z.thema.uhr
        Gruppe("Unter der Uhr") {
            Schalter("Datum", stil.datum) { z.uhrSetzen(stil.copy(datum = it)) }
            Schalter("Wetter", k.wetter, if (k.wetter && z.ort == null)
                         ("Unten „Ort für das Wetter“ festlegen") else null) {
                z.kopfSetzen(k.copy(wetter = it))
            }
            MenueEintrag("Ort für das Wetter", z.ort?.name ?: "Noch kein Ort festgelegt") {
                navi.auf(Ebene.Einstellungen("wetter"))
            }
            Schalter("Akku", stil.akku) { z.uhrSetzen(stil.copy(akku = it)) }
            Schalter("Nächster Termin", k.termin) {
                z.kopfSetzen(k.copy(termin = it))
                if (it && !Kalender.hatRecht(akt)) akt.kalenderErlauben()
            }
            Schalter("Musik", z.musikZeile, if (Musik.hatRecht(akt)) "Titel mit Pause/Vor/Zurück"
                     else "Titel mit Pause/Vor/Zurück – braucht den Benachrichtigungszugriff (nur für die Musik-Anzeige, nichts verlässt das Handy)") {
                z.musikZeileSetzen(it)
                if (it && !Musik.hatRecht(akt)) akt.benachrichtigungsZugriff()
            }
        }
}

// ---------------------------------------------------------------- Alphabet & Gesten
@Composable
internal fun AlphabetSeite() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    // mit Vorschau: Aussehen (frueher unter Design → Alphabet) und Verhalten an einer Stelle
    VorschauSeite({ MiniLauncher() }) {
        AlphabetAussehen()
        Gruppe("Code") {
            MenueEintrag("Code", if (z.codeGesetzt) "Von … bis … ändern" else "Von … bis … festlegen", rechts = "›") {
                navi.auf(Ebene.Einstellungen("code"))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------- System
@Composable
internal fun SystemSeite() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val akt = LocalContext.current as MainActivity
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // oeffentlich: Erweiterungen (Randgesten & Lautstaerke) erst laden
        Gruppe("Sprache / Language") {
            var w by remember { mutableStateOf(Sprache.wahl(akt)) }
            Wahl("Sprache / Language", listOf("auto" to "Wie das Handy / Like phone", "de" to "Deutsch", "en" to "English"), w) { w = it; Sprache.setzen(akt, it) }
        }
        // wie sich Antippen und Wechsel anfuehlen (Atelier: eigene Seite "Gefuehl" auf der Hauptseite)
        if (!NeuStil.an) Gruppe("Gefühl") {
            Schalter("Vibration beim Antippen", Gefuehl.vibration, "Kurzes Klicken bei Bausteinen, deutlicher beim langen Drücken") { Gefuehl.setzen(akt, "vibration", it) }
            Schalter("Bausteine federn", Gefuehl.federn, "Beim Drücken minimal kleiner, dann federn sie zurück") { Gefuehl.setzen(akt, "federn", it) }
            Schalter("Weiche Übergänge", Gefuehl.uebergaenge, "Bausteine gleiten im Baukasten an ihren Platz, Favoriten / Buchstabe / Seite bei # blenden über") { Gefuehl.setzen(akt, "weich", it) }
            Schalter("Suche startet sofort", Gefuehl.sucheSofort, "Passt nur noch eine App, startet sie ohne Antippen") { Gefuehl.setzen(akt, "sofort", it) }
        }
        Gruppe("Anzeige") {
            Schalter("Statusleiste oben ausblenden", z.statusAus, "Uhrzeit, Akku und Symbole ganz oben weg – von oben wischen zeigt sie kurz") { z.statusAusSetzen(it) }
            Schalter("Runterwischen öffnet Benachrichtigungen", z.runterBenachr, "Aus: auf dem Startbildschirm nach unten wischen klappt Samsungs Benachrichtigungen nicht mehr auf") { z.runterBenachrSetzen(it) }
        }
        if (Variante.lautRegler) Gruppe("Bedienung") {
            if (Variante.lautRegler) MenueEintrag("Lautstärke-Regler", if (LautDienst.istAn(akt)) "Eigener Regler ✓" else "Aus") {
                navi.auf(Ebene.Einstellungen("laut"))
            }
        }
        Gruppe("Apps öffnen") {
            Schalter("Sofort bei Berührung öffnen", z.favSofort,
                     if (z.favSofort) "An: Favoriten starten schon beim Antippen (nach 25 ms ruhigem Finger) – lang drücken auf Favoriten geht dann nicht"
                     else "Aus: Apps starten erst beim Loslassen – lang drücken auf Favoriten geht wieder") {
                z.wellenSetzen("favSofort", it)
            }
        }
        // Atelier: Startbildschirm + Design als Datei sichern / zurueckholen
        SicherungKarte()
        Gruppe("Zugriffe") {
            MenueEintrag("Berechtigungen", "Kalender, Benachrichtigungen, Diagnose") { navi.auf(Ebene.Einstellungen("rechte")) }
            MenueEintrag("Startbildschirm", if (akt.istStandard()) Variante.appName + " ✓" else "Noch nicht festgelegt") { akt.standardLauncher() }
        }
        Spacer(Modifier.height(24.dp))
    }
}


// ---------------------------------------------------------------- Startbildschirm-App wechseln (Test)
/** wie Samsungs "Standard-Startbildschirm": alle Launcher auf dem Handy, der aktive mit Haken */
@Composable
internal fun LauncherWahl() {
    val akt = LocalContext.current as MainActivity
    val p = LocalPalette.current
    val z = LocalZustand.current
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen          // nach Rueckkehr aus Androids Auswahl neu lesen
    val apps = remember(z.fortsetzen) { akt.startbildschirmApps() }
    val aktiv = remember(z.fortsetzen) { akt.standardPaket() }
    Text("Startbildschirm-App", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
    Column(Modifier.fillMaxWidth().clip(rund(20.dp)).background(p.text.copy(alpha = 0.06f))) {
        apps.forEach { (paket, name) ->
            val istAktiv = paket == aktiv
            Row(
                Modifier.fillMaxWidth().clickable {
                    when {
                        istAktiv -> {}
                        paket == akt.packageName -> akt.alsStandardFragen()
                        else -> {
                            android.widget.Toast.makeText(akt, Sprache.tr("„$name“ auswählen"), android.widget.Toast.LENGTH_SHORT).show()
                            akt.standardLauncher()
                        }
                    }
                }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(name + if (paket == akt.packageName) "  (dieser)" else "", color = p.text, fontSize = 16.sp, modifier = Modifier.weight(1f))
                if (istAktiv) Text("✓", color = p.akzent, fontSize = 18.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- Erweiterungen (oeffentliche Version)
/** Randgesten & Lautstaerke: erst laden (wie ein Plugin), dann in den Bedienungshilfen einschalten */
@Composable
internal fun ErweiterungKarte() {
    val akt = LocalContext.current as MainActivity
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var laedt by remember { mutableStateOf(false) }
    var fortschritt by remember { mutableStateOf(0f) }
    val geladen = Erweiterung.randgesten
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text("Randgesten & Lautstärke", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("Vom Bildschirmrand reinwischen für Zurück, Home, Apps und mehr – mit Animationen passend zum Theme. " +
             "Dazu ein eigener Lautstärke-Regler im gleichen Stil.",
             color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        when {
            laedt -> {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.linie)) {
                    Box(Modifier.fillMaxWidth(fortschritt).height(4.dp).background(p.akzent))
                }
                Text("Wird geladen …", color = p.leise, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
            !geladen -> Knopf("Erweiterung laden") {
                laedt = true
                scope.launch {
                    for (i in 1..20) { kotlinx.coroutines.delay(45); fortschritt = i / 20f }
                    Erweiterung.randgestenSetzen(akt, true)
                    laedt = false
                }
            }
            else -> {
                val an = LautDienst.istAn(akt)
                Text(if (an) "Geladen und eingeschaltet ✓" else "Geladen – jetzt noch in den Bedienungshilfen einschalten",
                     color = if (an) p.akzent else p.text, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!an) Knopf("Einschalten", Modifier.weight(1f)) {
                        try { akt.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
                    }
                    Knopf("Entfernen", Modifier.weight(1f)) { Erweiterung.randgestenSetzen(akt, false) }
                }
            }
        }
    }
}
