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

// Einstellungen: Code & Knopf, Lautstaerke, eigene Dateien, Bastler
// ---------------- Code & Knopf (erst nach dem alten Code; der aktuelle Code wird nirgends angezeigt) ----------------
@Composable
internal fun CodeSeite() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val navi = LocalNavi.current
    var offen by remember { mutableStateOf(true) }               // gleich offen - der jetzige Code wird nicht abgefragt
    var altVon by remember { mutableStateOf("") }
    var altBis by remember { mutableStateOf("") }
    var neuVon by remember { mutableStateOf("") }
    var neuBis by remember { mutableStateOf("") }
    var fehler by remember { mutableStateOf<String?>(null) }
    fun pruefen() {
        if (z.codePruefenBereich(altVon, altBis)) { offen = true; fehler = null }
        else { fehler = "Code stimmt nicht"; altVon = ""; altBis = "" }
    }
    fun speichern() {
        val verfuegbar = z.apps.filter { it.schluessel !in z.versteckt }.map { it.buchstabe }.toSet() + "E"
        val f = z.codeBereichSetzen(neuVon, neuBis, verfuegbar)
        fehler = f
        if (f == null) {
            neuVon = ""; neuBis = ""
            Toast.makeText(ctx, Sprache.tr("Code gespeichert"), Toast.LENGTH_SHORT).show()
        }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (!offen) {
            Abschnitt("Erst den jetzigen Code")
            BuchstabenWahl("Von", altVon) { altVon = it; fehler = null }
            BuchstabenWahl("Bis", altBis) { altBis = it; fehler = null }
            fehler?.let { Text(it, color = p.warnung, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
            Knopf("Weiter", Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { pruefen() }
        } else {
            // klare Anzeige, warum der Code nichts oeffnen wuerde
            if (!z.codeGesetzt || !z.privatAn) Text(
                if (!z.codeGesetzt) "Noch kein Code festgelegt – unten Von und Bis wählen und speichern."
                else "Der private Bereich ist aus – der Code öffnet nichts. Unten einschalten.",
                color = p.warnung, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Gruppe("Privater Bereich") {
                Schalter("Privater Bereich", z.privatAn,
                         if (z.codeGesetzt) "Versteckte Apps hinter dem Code" else "Erst unten einen Code festlegen") {
                    if (!it || z.codeGesetzt) z.privatAnSetzen(it)
                }
            }
            Gruppe("Unsichtbarer Knopf vor dem Code") {
                MenueEintrag("Neben der Uhr", rechts = if (z.geheimOrt == "uhr") "✓" else null) { z.geheimOrtSetzen("uhr") }
                // Baustein auf dem Startbildschirm (unsichtbar) - wird bei Bedarf gleich hingelegt
                MenueEintrag("Unsichtbarer Baustein", "Liegt auf dem Startbildschirm – Platz und Größe im Baukasten (lange drücken)",
                             rechts = if (z.geheimOrt == "baustein") "✓" else null) {
                    z.geheimOrtSetzen("baustein")
                    if (unsichtbarHinlegen(ctx)) Toast.makeText(ctx, Sprache.tr("Liegt jetzt auf dem Startbildschirm – im Baukasten verschieben"), Toast.LENGTH_LONG).show()
                }
                MenueEintrag("Selbst platzieren", "Irgendwo auf der Startseite, Größe frei",
                             rechts = if (z.geheimOrt == "eigen") "✓" else null) { navi.auf(Ebene.KnopfPlatzieren) }
                MenueEintrag("Kein Knopf", "Nur der Code reicht",
                             rechts = if (z.geheimOrt == "aus") "✓" else null) { z.geheimOrtSetzen("aus") }
            }
            Gruppe(if (z.codeGesetzt) "Neuer Code" else "Code festlegen") {
                Text("Auf einem Buchstaben von … bis … anfangen, hoch bis # ziehen und dort loslassen.",
                     color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                BuchstabenWahl("Von", neuVon) { neuVon = it; fehler = null }
                BuchstabenWahl("Bis", neuBis) { neuBis = it; fehler = null }
                fehler?.let { Text(it, color = p.warnung, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                Knopf("Code speichern", Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { speichern() }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** einen Buchstaben A-Z waehlen (fuer den Code: von ... bis ...) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuchstabenWahl(titel: String, wert: String, waehle: (String) -> Unit) {
    val p = LocalPalette.current
    Text(titel + if (wert.isNotEmpty()) ":  $wert" else "", color = p.text, fontSize = 16.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ('A'..'Z').forEach { c ->
            val b = c.toString()
            val an = b == wert
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (an) p.akzent else p.text.copy(alpha = 0.07f))
                    .clickable { waehle(b) }, contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(b, color = if (an) p.aufAkzent else p.text, fontSize = 15.sp)
            }
        }
    }
}

// ---------------- Eigener Lautstaerke-Regler ----------------
@Composable
internal fun LautSeite() {
    val lautNavi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen          // nach Rueckkehr aus den Bedienungshilfen neu pruefen
    val dienst = LautDienst.istAn(ctx)
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var an by remember { mutableStateOf(prefs.getBoolean("lautAn", true)) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        MenueEintrag(
            "Dienst in den Bedienungshilfen",
            if (dienst) "Eingeschaltet ✓" else "Aus – antippen, dann „${"Atelier – Randgesten & Lautstärke"}“ einschalten",
        ) {
            try { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        }
        Schalter("Eigener Regler statt Samsung", an, "Aus = Lautstärketasten wie gewohnt") {
            an = it; prefs.edit().putBoolean("lautAn", it).apply()
        }
        MenueEintrag("Aussehen", LautDesign.ARTEN.firstOrNull { it.first == LautDesign.art(ctx) }?.second + " – unter Design", rechts = "›") {
            lautNavi.auf(Ebene.Einstellungen("d_laut"))
        }
        // nur Optik: was hinter der Leiste ist, wird unscharf - der Balken selbst und die Bedienung bleiben gleich
        var blur by remember { mutableStateOf(prefs.getBoolean("lautBlur", false)) }
        var blurSt by remember { mutableStateOf(prefs.getInt("lautBlurStaerke", 24)) }
        Schalter("Hintergrund unscharf", blur, "Was hinter der Leiste liegt, wird weichgezeichnet – der Balken bleibt fest") {
            blur = it; prefs.edit().putBoolean("lautBlur", it).apply()
        }
        if (blur) {
            RgRegler("Unschärfe", blurSt.toFloat(), 4f..60f, "$blurSt", {}) { v -> blurSt = v.roundToInt(); prefs.edit().putInt("lautBlurStaerke", blurSt).apply() }
            val warum = when {
                android.os.Build.VERSION.SDK_INT < 31 -> "Braucht Android 12 oder neuer"
                LautDesign.blurBreite(LautDesign.art(ctx)) == null -> "Geht bei diesem Aussehen nicht (keine einfache Kapsel) – z. B. Schwarz & Weiß, Pille oder Glas wählen"
                ctx.getSystemService(android.view.WindowManager::class.java)?.isCrossWindowBlurEnabled != true ->
                    "Das Handy erlaubt gerade keine Unschärfe über anderen Apps. Prüfen: Energiesparmodus aus · Einstellungen → Bedienungshilfen → " +
                    "Sichtbarkeitsverbesserungen → „Transparenz und Unschärfe verringern“ aus · Entwickleroptionen → „Unschärfe auf Fensterebene zulassen“ an"
                else -> null
            }
            if (warum != null) Text(warum, color = p.warnung, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            else Text("Unschärfe ist auf diesem Handy erlaubt ✓", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        }
        Abschnitt("So funktioniert's")
        Text(
            "Lautstärketasten zeigen rechts einen schmalen Regler. Das untere Drittel (unter dem Strich) ist der " +
            "Feinbereich: leiser als Android normalerweise kann, in kleinen Schritten. Darüber die normalen Stufen. " +
            "Die Leiser-Taste geht unten in 3-dB-Schritten weiter.\n\n" +
            "Bei Anrufen und Klingeln bleibt alles wie bei Samsung. Der Dienst liest nur die Lautstärketasten und – für die Randgesten – welche App gerade vorne ist.",
            color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp),
        )
        val fein = Fein.geht
        if (fein == false) Text(
            "Dein Handy erlaubt die Feinregelung gerade nicht – der Regler hat dann nur die normalen Stufen.",
            color = p.warnung, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        if (!dienst) Text(
            "Falls sich der Schalter nicht einschalten lässt: App-Info des Launchers → ⋮ oben rechts → " +
            "„Eingeschränkte Einstellungen zulassen“, dann nochmal.",
            color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
}

// ---------------------------------------------------------------- eigene Dateien + Code-Editor
/** eigenes Bild als Hintergrund: Galerie oder Dateien (die Auswahl zeigt beides) */
internal fun bildWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("image/*"), "bild") { uri -> akt.dateiUebernehmen(uri, EigeneDateien.bild(akt)) { SperrBild.aktualisieren(akt, erzwingen = true) } }
}

/** nur fuer den Sperrbildschirm: eigenes Bild (wird als Sperrbild gesetzt) oder Video (laeuft ueber den Live-Hintergrund) */
internal fun sperrBildWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("image/*"), "sperrbild") { uri -> akt.dateiUebernehmen(uri, EigeneDateien.sperrBild(akt)) { SperrMedium.anwenden(akt) } }
}
internal fun sperrVideoWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("video/*"), "sperrvideo") { uri -> akt.dateiUebernehmen(uri, EigeneDateien.sperrVideo(akt)) { SperrMedium.anwenden(akt) } }
}

internal fun videoWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("video/*"), "video") { uri -> akt.dateiUebernehmen(uri, EigeneDateien.video(akt)) {} }
}

/** Liquid Glass: dein Hintergrundbild aus der Galerie - das Glas bricht dann genau dieses Bild */
internal fun glasFotoWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("image/*"), "glasfoto") { uri -> glasFotoUebernehmen(akt, uri) }
}

internal fun glasFotoUebernehmen(akt: MainActivity, uri: android.net.Uri) {
    // erst in eine Zwischendatei, dann umbenennen - so wird nie ein halb kopiertes Bild geladen
    val zwischen = java.io.File(EigeneDateien.ordner(akt), "glas-foto.neu")
    akt.dateiUebernehmen(uri, zwischen) {
        val ziel = GlasFoto.datei(akt)
        if (zwischen.renameTo(ziel)) {
            ziel.setLastModified(System.currentTimeMillis())
            GlasFoto.stand.intValue++
            // Live-Hintergrund und Sperrbild sollen das neue Bild auch zeigen
            akt.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putLong("glasFoto", System.currentTimeMillis()).apply()
            SperrBild.aktualisieren(akt, erzwingen = true)
        }
    }
}

internal fun glasFotoWeg(ctx: Context) {
    GlasFoto.datei(ctx).delete()
    GlasFoto.stand.intValue++
    LocalZustandHalter.z?.let { it.eigenStand++ }
    ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putLong("glasFoto", System.currentTimeMillis()).apply()
    SperrBild.aktualisieren(ctx, erzwingen = true)
}

internal fun lottieWaehlen(ctx: Context) {
    val akt = ctx as? MainActivity ?: return
    akt.dateiWaehlen(arrayOf("application/json", "text/*", "*/*"), "lottie") { uri -> akt.dateiUebernehmen(uri, EigeneDateien.lottie(akt)) {} }
}

/** damit Nicht-Composable-Code (Speichern-Knopf) den Zustand erreicht */
object LocalZustandHalter { var z: Zustand? = null }

/**
 * einfacher Code-Editor: Text bearbeiten (mit Zeilennummern), speichern, testen, Beispiel laden, aus Datei laden.
 * art = "menue" / "hintergrund" / "alphabet" (fuer "Testen")
 */
@Composable
internal fun CodeEditor(art: String, laden: (Context) -> String, speichern: (Context, String, (Boolean) -> Unit) -> Unit, beispiel: String, hinweis: String) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    LocalZustandHalter.z = LocalZustand.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var geaendert by remember { mutableStateOf(false) }
    var ergebnis by remember { mutableStateOf<TestErgebnis?>(null) }
    var testet by remember { mutableStateOf(false) }
    var beispielFrage by remember { mutableStateOf(false) }      // "Nochmal tippen = ersetzen" (3 s)
    // im Hintergrund laden (Datei im Bastel-Ordner)
    LaunchedEffect(Unit) {
        val t = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { laden(ctx) }
        if (!geaendert) text = t
    }
    LaunchedEffect(beispielFrage) {
        if (beispielFrage) { kotlinx.coroutines.delay(3_000); beispielFrage = false }
    }
    // Schrift im Code-Editor (Nummern und Text muessen gleich hoch sein)
    val code = remember {
        androidx.compose.ui.text.TextStyle(
            color = Color(0xFFE6EDF3), fontSize = 13.sp, lineHeight = 18.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        )
    }
    Column(Modifier.fillMaxWidth()) {
        Text(hinweis, color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knopf(if (geaendert) "Speichern ●" else "Speichern") {
                geaendert = false
                val fehler = JS.pruefen(text)          // nur uebersetzen - geht schnell
                ergebnis = if (fehler != null) TestErgebnis(false, fehler) else null
                speichern(ctx, text) { ok ->
                    val meldung = when {
                        !ok -> "Nur im Launcher gespeichert – Datei im Ordner ließ sich nicht schreiben"
                        fehler == null -> "Gespeichert"
                        fehler.startsWith("Zeile") -> "Gespeichert – aber Fehler in $fehler"
                        else -> "Gespeichert – aber Fehler: $fehler"
                    }
                    Toast.makeText(ctx, Sprache.tr(meldung), if (ok && fehler == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                }
            }
            Chip(if (testet) "Teste …" else "Testen") {
                if (!testet) {
                    testet = true
                    ergebnis = null
                    val quelle = text
                    scope.launch {
                        val e = withContext(Dispatchers.IO) {
                            try {
                                when (art) {
                                    "menue" -> MeinMenue.testen(ctx, quelle)
                                    "hintergrund" -> hintergrundTesten(ctx, quelle)
                                    else -> AlphabetSkript.testen(ctx, quelle)
                                }
                            } catch (t: Throwable) { TestErgebnis(false, JS.fehler(t)) }
                        }
                        ergebnis = e
                        testet = false
                    }
                }
            }
            Chip(if (beispielFrage) "Nochmal tippen = ersetzen" else "Beispiel", gewaehlt = beispielFrage) {
                if (geaendert && !beispielFrage) beispielFrage = true
                else { text = beispiel; geaendert = true; beispielFrage = false; ergebnis = null }
            }
            Chip("Aus Datei …") {
                (ctx as? MainActivity)?.let { akt ->
                    akt.dateiWaehlen(arrayOf("text/*", "application/javascript", "*/*")) { uri -> akt.textLesen(uri) { t -> text = t; geaendert = true } }
                }
            }
        }
        ergebnis?.let { e ->
            Text(
                (if (e.ok) "✓ " else "Fehler – ") + e.text,
                color = if (e.ok) p.akzent else p.warnung, fontSize = 13.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }
        Box(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().weight(1f, fill = false).heightIn(min = 300.dp)
                .clip(rund(16.dp)).background(Color(0xFF101418)).padding(12.dp),
        ) {
            // Zeilennummern + Text in EINEM senkrechten Scrollbereich; kein Zeilenumbruch (waagerecht scrollen),
            // damit die Nummern immer neben der richtigen Zeile stehen
            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val zeilen = text.count { it == '\n' } + 1
                val nummern = remember(zeilen) { (1..zeilen).joinToString("\n") }
                Text(
                    nummern,
                    style = code.copy(color = Color(0xFFE6EDF3).copy(alpha = 0.35f), textAlign = androidx.compose.ui.text.style.TextAlign.End),
                    modifier = Modifier.padding(end = 10.dp),
                )
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                    val breite = maxWidth
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = text,
                            onValueChange = { text = it; geaendert = true },
                            textStyle = code,
                            cursorBrush = SolidColor(p.akzent),
                            modifier = Modifier.widthIn(min = breite),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Bastler: Ordner + alle Code-Dateien
@Composable
internal fun BastlerSeite() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    var stand by remember { mutableIntStateOf(0) }           // nach Ordnerwahl neu zeichnen
    @Suppress("UNUSED_VARIABLE") val neu = stand + z.eigenStand
    val gesetzt = BastelOrdner.gesetzt(akt)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Gruppe("Ordner in „Eigene Dateien“") {
            Text(
                if (gesetzt) "Dein Code liegt in: " + BastelOrdner.anzeigeName(akt) +
                    "\nDort findest du menue.js, hintergrund.js, alphabet.js, LIESMICH.txt und beispiele/. " +
                    "Bearbeiten mit jeder App oder am PC per USB – der Launcher liest die Dateien beim nächsten Anzeigen neu."
                else "Wähle einen Ordner (z. B. Dokumente → neuer Ordner „Launcher“). Dann liegt dein ganzer Code dort " +
                    "als normale Dateien, sichtbar in „Eigene Dateien“, und du kannst ihn bauen, wie du willst.",
                color = p.leise, fontSize = 14.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MenueEintrag(if (gesetzt) "Anderen Ordner wählen" else "Ordner wählen") { akt.ordnerWaehlen { stand++ } }
            if (gesetzt) {
                MenueEintrag("Beispiele + Anleitung neu hineinlegen", "Deine eigenen Dateien bleiben unverändert") {
                    BastelOrdner.beispieleAnlegen(akt)
                    Toast.makeText(akt, Sprache.tr("Liegt im Ordner"), Toast.LENGTH_SHORT).show()
                }
                MenueEintrag("Ordner nicht mehr benutzen", "Code bleibt dann nur im Launcher", farbe = p.warnung) {
                    BastelOrdner.vergessen(akt); AlphabetSkript.neuLesen = true; z.eigenStand++; stand++
                }
            }
        }
        Gruppe("Code bearbeiten") {
            MenueEintrag("Hintergrund", "hintergrund.js – Design „Eigener Code“") { navi.auf(Ebene.Einstellungen("codehintergrund")) }
            MenueEintrag("Alphabet", "alphabet.js – eigene Wisch-Befehle") { navi.auf(Ebene.Einstellungen("codealphabet")) }
        }
        Gruppe("Konsole") {
            val konsoleStand = Konsole.stand.intValue            // neu zeichnen bei jeder neuen Zeile
            val zeilen = remember(konsoleStand) { Konsole.zeilen().takeLast(30) }
            Text(
                if (zeilen.isEmpty()) "Noch leer. log(\"Hallo\") in deinem Code schreibt hierher, Fehler landen auch hier."
                else zeilen.joinToString("\n"),
                color = if (zeilen.isEmpty()) p.leise else p.text, fontSize = 12.sp, lineHeight = 16.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            MenueEintrag("Leeren") { Konsole.leeren() }
            MenueEintrag("Alles neu laden", "Code neu lesen und starten (Menü, Hintergrund, Alphabet)") {
                AlphabetSkript.neuLesen = true
                z.eigenStand++
                Toast.makeText(akt, Sprache.tr("Wird neu geladen"), Toast.LENGTH_SHORT).show()
            }
        }
        Gruppe("Unsichtbarer Knopf & Code") {
            MenueEintrag("Code & Knopf", "Platzieren, weglassen, Code ändern") { navi.auf(Ebene.Einstellungen("code")) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
