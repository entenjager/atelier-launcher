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

// Einstellungen: Kopfzeile, Wetter, Berechtigungen, Uhr
// ---------------------------------------------------------------- Kopfzeile & Wetter
@Composable
internal fun KopfSeite() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val k = z.kopf
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("Unter der Uhr anzeigen", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
        Schalter("Nächster Termin", k.termin, if (k.termin && !Kalender.hatRecht(akt)) "Kalender-Zugriff fehlt – antippen" else null) {
            z.kopfSetzen(k.copy(termin = it))
            if (it && !Kalender.hatRecht(akt)) akt.kalenderErlauben()
        }
        Schalter("Wetter", k.wetter, if (k.wetter && z.ort == null) "Unten einen Ort festlegen" else null) {
            z.kopfSetzen(k.copy(wetter = it))
        }
            // ---- Geburtstage: Name + Tag eintragen, erscheinen bei den Terminen und am Tag oben unter dem Datum
            var gebNeu by remember { mutableStateOf(false) }
            var gebWeg by remember { mutableStateOf<Geburtstage.G?>(null) }
            @Suppress("UNUSED_VARIABLE") val gebStand = Geburtstage.stand
            Abschnitt("Eigene Termine")
            Geburtstage.alle(akt).forEach { g ->
                MenueEintrag(g.name, Geburtstage.datumText(g) + " · antippen zum Löschen") { gebWeg = g }
            }
            MenueEintrag("+ Termin eintragen", "Erscheint am Tag oben unter dem Datum") { gebNeu = true }
            if (gebNeu) GeburtstagNeuDialog { gebNeu = false }
            gebWeg?.let { g -> GeburtstagLoeschenDialog(g) { gebWeg = null } }
        WetterOrt()
    }
}


// ---------------------------------------------------------------- Wetter (oeffentliche Version: eigene Seite)
@Composable
internal fun WetterSeite() {
    val z = LocalZustand.current
    val k = z.kopf
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Schalter("Wetter unter der Uhr", k.wetter, if (k.wetter && z.ort == null) "Unten einen Ort festlegen" else null) {
            z.kopfSetzen(k.copy(wetter = it))
        }
        WetterOrt()
    }
}

/** Ort fuer das Wetter suchen und festlegen (Teil von KopfSeite und WetterSeite) */
@Composable
internal fun WetterOrt() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val scope = rememberCoroutineScope()
    var suche by remember { mutableStateOf("") }
    var treffer by remember { mutableStateOf<List<Ort>?>(null) }
    var fehler by remember { mutableStateOf<String?>(null) }
    fun suchen() {
        if (suche.isBlank()) return
        fehler = null; treffer = null
        scope.launch {
            try {
                val l = withContext(Dispatchers.IO) { Wetter.suchen(suche) }
                treffer = l
                if (l.isEmpty()) fehler = "Nichts gefunden"
            } catch (e: Exception) { fehler = "Keine Verbindung zum Wetterdienst" }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Abschnitt("Ort für das Wetter")
        Text(z.ort?.name ?: "Noch kein Ort festgelegt", fontSize = 16.sp, modifier = Modifier.padding(horizontal = 20.dp))
        Eingabe(suche, { suche = it }, "Stadt suchen, z. B. Berlin", onFertig = { suchen() })
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Knopf("Suchen") { suchen() }
            if (z.ort != null) Chip("Ort entfernen") { z.ortSetzen(null) }
        }
        fehler?.let { Text(it, color = p.warnung, fontSize = 14.sp, modifier = Modifier.padding(20.dp)) }
        treffer?.forEach { o ->
            MenueEintrag(o.name) { z.ortSetzen(o); treffer = null; suche = "" }
        }
        Text("Wetterdaten: Open-Meteo.com (kostenlos, ohne Konto). Dorthin gehen nur der Suchbegriff bzw. die Koordinaten des Orts.", color = p.leise, fontSize = 12.sp,
             modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp))
    }
}


// ---------------------------------------------------------------- Berechtigungen
@Composable
internal fun RechteSeite() {
    val z = LocalZustand.current
    val akt = LocalContext.current as MainActivity
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen       // nach Rueckkehr aus den Android-Einstellungen neu pruefen
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        MenueEintrag("Startbildschirm", if (akt.istStandard()) Variante.appName + " ist der Startbildschirm ✓"
                     else "Noch nicht festgelegt – App-Kurzbefehle im Pop-up gehen erst danach") { akt.standardLauncher() }
        MenueEintrag("Kalender", if (Kalender.hatRecht(akt)) "Erlaubt ✓" else "Nicht erlaubt – für den nächsten Termin unter der Uhr") {
            akt.kalenderErlauben()
        }
        MenueEintrag("Musik (Benachrichtigungszugriff)", if (Musik.hatRecht(akt)) "Erlaubt ✓"
                     else "Nicht erlaubt – für Musik und „Nicht stören“") {
            akt.benachrichtigungsZugriff()
        }
        // oeffentlich: kurz erklaeren, wofuer der Zugriff gebraucht wird, bevor Android fragt
        Text(
            "Wofür? Damit der Launcher die laufende Musik anzeigen und „Nicht stören“ schalten kann. " +
            "Eigene Skripte können mit handy.nachrichten() neue Benachrichtigungen einer App lesen. " +
            "Alles bleibt auf dem Handy – nichts wird gespeichert oder verschickt. " +
            "Antippen öffnet die Android-Einstellung, dort den Launcher einschalten.",
            color = LocalPalette.current.leise, fontSize = 13.sp,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
        )
        MenueEintrag("App-Info des Launchers", "Falls Android nicht mehr nachfragt") { akt.info(akt.packageName) }
    }
}


// ============================================================================
//  Uhr anpassen: Karte unten, die Uhr oben aendert sich sofort mit
// ============================================================================
internal val UHR_VORLAGEN = listOf(
    "Klassisch" to UhrStil(groesse = 58, dicke = 0),
    "Groß" to UhrStil(groesse = 96, dicke = 0),
    "Fett" to UhrStil(groesse = 64, dicke = 2),
    "Untereinander" to UhrStil(groesse = 88, dicke = 0, gestapelt = true),
    "Mittig" to UhrStil(groesse = 70, dicke = 0, mittig = true),
    "Klein" to UhrStil(groesse = 40, dicke = 1),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UhrEinstellungen() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val stil = z.thema.uhr
    fun aendern(u: UhrStil) = z.uhrSetzen(u)
    Blatt(onSchliessen = { navi.zu() }, schleier = 0.15f, hoechstens = 0.6f) {
        BlattKopf("Uhr anpassen") { navi.zu() }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Stile", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UHR_VORLAGEN.forEach { (n, v) ->
                    Chip(n) {
                        aendern(stil.copy(groesse = v.groesse, dicke = v.dicke, gestapelt = v.gestapelt, mittig = v.mittig))
                    }
                }
            }
            UhrSchriftWahl(stil.schrift) { aendern(stil.copy(schrift = it)) }
            UhrMaterialWahl(stil) { aendern(it) }
            var groesseZieh by remember(stil.groesse) { mutableStateOf(stil.groesse.toFloat()) }
            Text("Größe: ${groesseZieh.roundToInt()}", fontSize = 15.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            Slider(value = groesseZieh, onValueChange = { groesseZieh = it },
                   onValueChangeFinished = { aendern(stil.copy(groesse = groesseZieh.roundToInt())) },
                   valueRange = 30f..140f, modifier = Modifier.padding(horizontal = 16.dp))
            Text("Strichstärke", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Dünn", "Normal", "Fett").forEachIndexed { i, t ->
                    Chip(t, gewaehlt = stil.dicke == i) { aendern(stil.copy(dicke = i)) }
                }
            }
            Text("Farbe", fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                UHR_FARBEN.indices.forEach { i ->
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(uhrFarbe(i, p))
                            .border(if (stil.farbe == i) 3.dp else 1.dp, if (stil.farbe == i) p.akzent else p.linie, CircleShape)
                            .clickable { aendern(stil.copy(farbe = i)) }
                    )
                }
            }
            Schalter("Sekunden anzeigen", stil.sekunden) { aendern(stil.copy(sekunden = it)) }
            Schalter("Datum anzeigen", stil.datum) { aendern(stil.copy(datum = it)) }
            Schalter("Akku anzeigen", stil.akku) { aendern(stil.copy(akku = it)) }
            Schalter("Mittig", stil.mittig) { aendern(stil.copy(mittig = it)) }
            Schalter("Stunden und Minuten untereinander", stil.gestapelt) { aendern(stil.copy(gestapelt = it)) }
            Text("Die Schrift der Uhr kommt aus Einstellungen → Schrift.", color = p.leise, fontSize = 13.sp,
                 modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
        }
    }
}
