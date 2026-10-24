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

// Einstellungen: Randgesten
// ---------------------------------------------------------------- Randgesten (eigene Einhand-Bedienung)
@Composable
internal fun RandgestenSeite() {
    // oeffentlich: Randgesten sind eine Erweiterung - erst laden, dann einstellen
    if (!Erweiterung.randgesten) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { ErweiterungKarte() }
        return
    }
    RandgestenEinstellungen()
}

@Composable
private fun RandgestenEinstellungen() {
    val rgNavi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val ctx = LocalContext.current
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen          // nach Rueckkehr aus den Bedienungshilfen neu pruefen
    val dienst = LautDienst.istAn(ctx)
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var stand by remember { mutableIntStateOf(0) }               // nach jedem Speichern neu zeichnen
    fun neuLesen() { stand++ }
    // solange die Seite offen ist: Griffe auch hier ueber dem Launcher zeigen
    androidx.compose.runtime.DisposableEffect(Unit) {
        Randgesten.vorschauSetzen(true)
        onDispose { Randgesten.vorschauSetzen(false) }
    }
    var gewaehlt by remember { mutableStateOf("r") }               // welche Randgeste gerade bearbeitet wird
    var sperrModus by remember { mutableIntStateOf(0) }           // 0 = normal, 1 = Sperrbildschirm
    @Suppress("UNUSED_VARIABLE") val liesIds = stand
    val alleIds = Randgesten.menueIds(prefs)
    if (gewaehlt !in alleIds) gewaehlt = "r"
    val s = gewaehlt
    val rd = Randgesten.rand(prefs, s)
    val unten = rd == "e"
    var wahl by remember { mutableStateOf<String?>(null) }       // Schluessel, fuer den gerade eine Aktion gewaehlt wird
    var ausnahmenOffen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ErweiterungKarte()
        Gruppe {
            val lies = stand                                  // neu zeichnen nach Speichern
            Schalter("Randgesten", Randgesten.an(prefs), "Vom Rand reinwischen = Aktion") {
                prefs.edit().putBoolean("rg_an", it).apply(); neuLesen()
            }
            Schalter("Vibrieren", Randgesten.vibrieren(prefs)) { prefs.edit().putBoolean("rg_vib", it).apply(); neuLesen() }
            Schalter("Von unten wischen = Startbildschirm", UntenWisch.an(prefs),
                     if (UntenWisch.samsungWischt(ctx)) "Ruht gerade: Samsungs Wischgesten sind an – sonst ginge der Startbildschirm doppelt auf. Wirkt nur mit Samsungs Tasten-Leiste"
                     else "Sofort, schon nach einem kurzen Stück nach oben – ohne Animation. Tippen auf die Leiste geht weiter") {
                prefs.edit().putBoolean("rg_unten", it).apply(); neuLesen()
            }
            val stil = Randgesten.stil(prefs)
            MenueEintrag("Animation", (Randgesten.STILE.firstOrNull { it.first == stil }?.second ?: stil) + " – unter Design", rechts = "›") {
                rgNavi.auf(Ebene.Einstellungen("d_randgesten"))
            }
            Schalter("Auf dem Startbildschirm aus", Randgesten.aufStartseiteAus(prefs), "Damit das Alphabet am Rand frei bleibt") {
                prefs.edit().putBoolean("rg_start_aus", it).apply(); neuLesen()
            }
            val aus = Randgesten.ausnahmen(prefs)
            MenueEintrag("In diesen Apps aus", if (aus.isEmpty()) "Keine" else "${aus.size} App(s)") { ausnahmenOffen = true }
        }

        // alle Randgesten (wie bei One Hand Operation +): auswaehlen oder neue anlegen
        Gruppe {
            val lies = stand
            Schalter("Links und rechts gleich", Randgesten.gleich(prefs),
                     "Eine Einstellung für beide Seiten (gespiegelt) – alles nur einmal einstellen") {
                prefs.edit().putBoolean("rg_lr_gleich", it).apply(); if (it) gewaehlt = "r"; neuLesen()
            }
        }
        Wahl("Randgeste", alleIds.map { it to Randgesten.anzeigeName(prefs, it) }, s) { gewaehlt = it }
        MenueEintrag("+ Neue Randgeste", "Zum Beispiel unten in den Ecken – Größe und Aktionen frei wählbar") {
            gewaehlt = Randgesten.neu(ctx); neuLesen()
        }

        Gruppe("Griff") {
            val lies = stand
            if (s != "l" && s != "r") {
                Wahl("Rand", listOf("l" to "Links", "r" to "Rechts", "e" to "Unten in den Ecken"), rd) {
                    prefs.edit().putString("rg_${s}_rand", it).apply(); neuLesen()
                }
            }
            Schalter("Griff " + (if (unten) "unten links und rechts" else if (s == "r" && Randgesten.gleich(prefs)) "links und rechts" else "am " + (if (rd == "l") "linken" else "rechten") + " Rand"), Randgesten.seiteAn(prefs, s)) {
                prefs.edit().putBoolean("rg_${s}_an", it).apply(); neuLesen()
            }
            val pos = Randgesten.position(prefs, s)
            if (!unten) RgRegler("Position", pos, -0.1f..1.1f, "${(pos * 100).roundToInt()} %" + when {
                pos < 0f -> if (unten) " (übers linke Ende)" else " (übers obere Ende)"
                pos > 1f -> if (unten) " (übers rechte Ende)" else " (übers untere Ende)"
                else -> if (unten) " von links" else "" }, { neuLesen() }) {
                prefs.edit().putFloat("rg_${s}_pos", it).apply()
            }
            val h = Randgesten.hoehe(prefs, s)
            RgRegler(if (unten) "Breite der Ecken" else "Höhe", h, if (unten) 0.03f..0.45f else 0.02f..1.0f, "${(h * 100).roundToInt()} % vom Bildschirm" + if (unten) " (je Ecke)" else "", { neuLesen() }) { prefs.edit().putFloat("rg_${s}_hoehe", it).apply() }
            val b = Randgesten.breite(prefs, s)
            RgRegler(if (unten) "Höhe der Ecken" else "Breite", b.toFloat(), if (unten) 4f..120f else 2f..44f, "$b dp", { neuLesen() }) { prefs.edit().putInt("rg_${s}_breite", it.roundToInt()).apply() }
            Wahl("Animation kommt von", listOf("seite" to (if (unten) "Der Seite (links / rechts)" else "Der Seite"), "unten" to "Unten"),
                 if (Randgesten.animUnten(prefs, s)) "unten" else "seite") {
                prefs.edit().putString("rg_${s}_anim", it).apply(); neuLesen()
            }
            val a = Randgesten.deckkraft(prefs, s)
            RgRegler("Sichtbarkeit", a, -0.1f..1.1f, when { a <= 0f -> "100 % durchsichtig"; a > 1f -> "${(a * 100).roundToInt()} % (extra breit)"; else -> "${(a * 100).roundToInt()} %" }, { neuLesen() }) {
                prefs.edit().putFloat("rg_${s}_alpha", if (it < 0.02f) 0f else it).apply()
            }
        }

        // eigene Belegung fuer den Sperrbildschirm
        Segmente(listOf("Normal", "Sperrbildschirm"), sperrModus) { sperrModus = it }
        val sperre = sperrModus == 1
        Gruppe("Gesten " + (if (unten) "in den unteren Ecken" else if (s == "r" && Randgesten.gleich(prefs)) "links und rechts" else "am " + (if (rd == "l") "linken" else "rechten") + " Rand") + (if (sperre) " · gesperrt" else "")) {
            val lies = stand
            val lang = false          // kein "Halten" mehr - nur Wischen
            for ((r, rName) in Randgesten.RICHTUNGEN) {
                val k = if (sperre) Randgesten.schluesselSperre(s, r, lang) else Randgesten.schluessel(s, r, lang)
                val eigen = !sperre || prefs.getString(k, null) != null
                @Suppress("UNUSED_VARIABLE") val n0 = rName
                MenueEintrag(
                    Randgesten.richtungName(rd, r),
                    (if (eigen) "" else "Wie normal: ") +
                        Randgesten.name(ctx, if (sperre) Randgesten.aktionSperre(prefs, s, r, lang) else Randgesten.aktion(prefs, s, r, lang)),
                ) { wahl = k }
            }
            if (sperre) MenueEintrag("Alles wie normal", "Sperrbildschirm-Belegung für diesen Rand löschen") {
                val e = prefs.edit()
                for ((r, _) in Randgesten.RICHTUNGEN) for (lang in listOf(false, true)) e.remove(Randgesten.schluesselSperre(s, r, lang))
                e.apply(); neuLesen()
            }
        }
        if (s != "l" && s != "r") MenueEintrag("Diese Randgeste löschen", "Nr. " + s.removePrefix("g") + " mit allen Einstellungen", farbe = p.warnung) {
            Randgesten.loeschen(ctx, s); gewaehlt = "r"; neuLesen()
        }
        Abschnitt("So funktioniert's")
        Text(
            "Vom Griff aus ins Bild wischen: gerade, schräg nach oben oder schräg nach unten (untere Ecken: gerade hoch, schräg zur Mitte oder flach zur Mitte – links und rechts gleich). " +
            "Mit „+ Neue Randgeste“ legst du beliebig viele an – jede mit eigenem Rand, eigener Größe und eigenen Aktionen. Sobald du ein Fünftel " +
            "der Breite drin bist, passiert die Aktion – ohne Loslassen. Vorher loslassen = abbrechen.\n\n" +
            "Solange diese Seite offen ist, siehst du die Berührfläche blau markiert – auch wenn der Griff 100 % durchsichtig ist. Alle Regler lassen sich um 10 % übersteuern.\n\n" +
            "Wenn Samsungs Wischgesten „Von der Seite und unten wischen“ an sind, kann sich das mit dem Zurück-Wisch beißen. " +
            "Dann den Griff nur auf einen Teil der Höhe legen oder bei Samsung „Von unten wischen“ nehmen.",
            color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(40.dp))
    }

    wahl?.let { k ->
        AktionWahlDialog(
            aktuell = prefs.getString(k, null),
            onWahl = { neuWert -> prefs.edit().putString(k, neuWert).apply(); wahl = null; neuLesen() },
            onZu = { wahl = null },
        )
    }
    if (ausnahmenOffen) AusnahmenDialog(
        start = Randgesten.ausnahmen(prefs),
        onFertig = { set -> prefs.edit().putStringSet("rg_aus", set).apply(); ausnahmenOffen = false; neuLesen() },
        onZu = { ausnahmenOffen = false },
    )
}

/** Aktion fuer eine Geste waehlen: feste Aktionen oder eine App */
@Composable
internal fun AktionWahlDialog(aktuell: String?, onWahl: (String) -> Unit, onZu: () -> Unit) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    var appModus by remember { mutableStateOf(false) }
    var suche by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onZu) {
        Column(Modifier.fillMaxWidth().clip(rund(24.dp)).background(p.karte).padding(vertical = 12.dp)) {
            Text(if (appModus) "App wählen" else "Aktion wählen", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Medium,
                 modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (!appModus) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(Randgesten.AKTIONEN, key = { it.first }) { (k, name) ->
                        Row(Modifier.fillMaxWidth().clickable { onWahl(k) }.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = p.text, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (aktuell == k) Text("✓", color = p.akzent, fontSize = 17.sp)
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth().clickable { appModus = true }.padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("App öffnen …", color = p.akzent, fontSize = 17.sp, modifier = Modifier.weight(1f))
                            if (aktuell?.startsWith("app:") == true) Text("✓", color = p.akzent, fontSize = 17.sp)
                        }
                    }
                }
            } else {
                Eingabe(suche, { suche = it }, "Apps suchen")
                val liste = remember(z.apps, suche) { z.apps.filter { suche.isBlank() || it.name.contains(suche.trim(), true) } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(liste, key = { it.schluessel }) { a ->
                        Row(Modifier.fillMaxWidth().clickable { onWahl("app:" + a.schluessel) }.padding(horizontal = 20.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.schluessel, 32.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(a.name, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (aktuell == "app:" + a.schluessel) Text("✓", color = p.akzent, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Apps, in denen die Randgesten aus sind (z. B. Spiele) */
@Composable
internal fun AusnahmenDialog(start: Set<String>, onFertig: (Set<String>) -> Unit, onZu: () -> Unit) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val gewaehlt = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(start) } }
    var suche by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onZu) {
        Column(Modifier.fillMaxWidth().clip(rund(24.dp)).background(p.karte).padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Randgesten aus in …", color = p.text, fontSize = 19.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("Fertig", color = p.akzent, fontSize = 17.sp, modifier = Modifier.clip(rund(12.dp))
                    .clickable { onFertig(gewaehlt.toSet()) }.padding(horizontal = 10.dp, vertical = 6.dp))
            }
            Eingabe(suche, { suche = it }, "Apps suchen")
            // Ausnahmen gelten pro App (Paket), nicht pro Startsymbol
            val liste = remember(z.apps, suche) {
                z.apps.distinctBy { it.paket }.filter { suche.isBlank() || it.name.contains(suche.trim(), true) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                items(liste, key = { it.paket }) { a ->
                    val an = a.paket in gewaehlt
                    Row(Modifier.fillMaxWidth().clickable { if (an) gewaehlt.remove(a.paket) else gewaehlt.add(a.paket) }
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(a.schluessel, 32.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(a.name, color = p.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Checkbox(checked = an, onCheckedChange = { if (an) gewaehlt.remove(a.paket) else gewaehlt.add(a.paket) })
                    }
                }
            }
        }
    }
}


@Composable
internal fun RgRegler(titel: String, wert: Float, bereich: ClosedFloatingPointRange<Float>, anzeige: String, danach: () -> Unit, setzen: (Float) -> Unit) {
    val p = LocalPalette.current
    if (NeuStil.an) {
        Column(Modifier.fillMaxWidth().neuTrenner().padding(top = 12.dp, bottom = 4.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(titel, fontSize = 16.sp, color = p.text, modifier = Modifier.weight(1f))
                Text(anzeige, fontSize = 16.sp, color = p.text)
            }
            NeuRegler(wert, bereich) { setzen(it); danach() }
        }
        return
    }
    Text(Sprache.tr(titel) + ": " + Sprache.tr(anzeige), fontSize = 16.sp, color = p.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
    Slider(value = wert, onValueChange = { setzen(it); danach() }, valueRange = bereich, modifier = Modifier.padding(horizontal = 16.dp))
}
