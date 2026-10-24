package de.atelierlauncher.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
//  Erster Start (Atelier): drei kurze Seiten - Startbildschirm festlegen, Benachrichtigungen erklaeren,
//  die wichtigsten Handgriffe. Laesst sich unter "Über Atelier" nochmal anzeigen.
// ============================================================================
@Composable
internal fun Einfuehrung(onFertig: () -> Unit) {
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val z = LocalZustand.current
    @Suppress("UNUSED_VARIABLE") val neu = z.fortsetzen          // nach dem Zurueckkommen (Rechte gegeben?) neu zeichnen
    var seite by remember { mutableIntStateOf(0) }
    Box(
        Modifier.fillMaxSize().background(p.grund)
            .pointerInput(Unit) { }                                // nichts dahinter anfassen
            .systemBarsPadding().padding(24.dp)
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(40.dp))
            when (seite) {
                0 -> {
                    Titel("Willkommen bei ${Variante.appName}")
                    Absatz("Ein Startbildschirm, den du selbst baust: Uhr, Apps, Knöpfe und Anzeigen frei anordnen.")
                    Absatz("Damit die Home-Taste hierher führt, leg ${Variante.appName} als Startbildschirm fest.")
                    val ok = akt.istStandard()
                    if (ok) Hinweiszeile("✓  ${Variante.appName} ist dein Startbildschirm")
                    else Knopf("Als Startbildschirm festlegen", Modifier.fillMaxWidth().padding(top = 12.dp)) { akt.standardLauncher() }
                }
                1 -> {
                    Titel("Benachrichtigungen")
                    Absatz("Für die Musik-Steuerung und den Baustein „Benachrichtigungen“ braucht ${Variante.appName} Zugriff auf Benachrichtigungen.")
                    Absatz("Alles bleibt auf deinem Handy. ${Variante.appName} schickt nichts irgendwohin und hat keine Werbung.")
                    if (Musik.hatRecht(akt)) Hinweiszeile("✓  Zugriff erlaubt")
                    else Knopf("Zugriff erlauben", Modifier.fillMaxWidth().padding(top = 12.dp)) { akt.benachrichtigungsZugriff() }
                    Absatz("Du kannst das auch weglassen – dann gehen nur diese beiden Sachen nicht.")
                }
                2 -> RandgestenEinf(akt)
                3 -> LautEinf(akt)
                else -> {
                    Titel("So geht's")
                    Handgriff("Lange auf den Startbildschirm drücken", "Bausteine hinzufügen, verschieben, größer ziehen. ✎ = Eigenschaften")
                    Handgriff("Über die Buchstaben wischen", "Deine Apps nach Anfangsbuchstaben – loslassen, dann antippen")
                    Handgriff("Auf # nach links ziehen", "Deine eigene Seite mit Bausteinen")
                    Handgriff("App nach rechts wischen oder lange drücken", "Umbenennen, Icon ändern, Favorit, Deinstallieren")
                    Handgriff("Einstellungen", "Im Baukasten unten „Alle Einstellungen“ – oder unter E im Alphabet")
                }
            }
        }
        // unten: Punkte + Weiter
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(SEITEN) { i -> Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == seite) p.akzent else p.text.copy(alpha = 0.25f))) }
            }
            if (seite < SEITEN - 1) Text("Überspringen", color = p.leise, fontSize = 15.sp,
                                modifier = Modifier.clickable { onFertig() }.padding(horizontal = 12.dp, vertical = 10.dp))
            Knopf(if (seite < SEITEN - 1) "Weiter" else "Los geht's") { if (seite < SEITEN - 1) seite++ else onFertig() }
        }
    }
}

private const val SEITEN = 5

/** Name des Dienstes, wie er in den Bedienungshilfen steht */
private val dienstName get() = "Atelier – Randgesten & Lautstärke"

/** Erweiterung freischalten und direkt zur Dienst-Seite in den Bedienungshilfen (sonst zur Liste) */
private fun zuDenBedienungshilfen(akt: MainActivity) {
    if (!Erweiterung.randgesten) Erweiterung.randgestenSetzen(akt, true)
    val neu = android.os.Build.VERSION.SDK_INT >= 31
    try {
        if (!neu) throw IllegalStateException()
        akt.startActivity(android.content.Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(android.content.Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(akt, LautDienst::class.java).flattenToString())
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try { akt.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                  .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
    }
}

private fun appInfo(akt: MainActivity) {
    try { akt.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                   android.net.Uri.parse("package:" + akt.packageName))
              .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
}

/** Einfuehrung: Randgesten erklaeren und beim Einschalten in den Bedienungshilfen helfen */
@Composable private fun RandgestenEinf(akt: MainActivity) {
    val prefs = remember { akt.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    var gesten by remember { androidx.compose.runtime.mutableStateOf(Randgesten.an(prefs)) }
    val dienst = LautDienst.istAn(akt)
    Titel("Randgesten")
    Absatz("Vom Bildschirmrand reinwischen für Zurück, Home, letzte Apps und mehr – mit Animationen passend zu deinem Design.")
    Absatz("Dafür braucht ${Variante.appName} einen Dienst in den Bedienungshilfen. Er merkt nur, wo du am Rand wischst – er liest keine Inhalte und schickt nichts weg.")
    if (dienst && gesten) Hinweiszeile("✓  Randgesten sind an – wisch mal vom rechten Rand rein")
    else if (dienst) Knopf("Randgesten einschalten", Modifier.fillMaxWidth().padding(top = 12.dp)) {
        prefs.edit().putBoolean("rg_an", true).apply(); gesten = true
    }
    else {
        Knopf("Zu den Bedienungshilfen", Modifier.fillMaxWidth().padding(top = 12.dp)) {
            prefs.edit().putBoolean("rg_an", true).apply(); gesten = true
            zuDenBedienungshilfen(akt)
        }
        Handgriff("1.  „$dienstName“ antippen", "Steht er nicht direkt da: unter „Installierte Apps“ oder „Heruntergeladene Apps“")
        Handgriff("2.  Schalter an, dann „Erlauben“", "Danach mit Zurück hierher – der Haken erscheint von selbst")
        Handgriff("Steht da „Eingeschränkte Einstellung“?", "Android sperrt das bei Apps aus dem Browser. App-Info → ⋮ oben rechts → „Eingeschränkte Einstellungen zulassen“, dann nochmal")
        Text("App-Info öffnen", color = LocalPalette.current.akzent, fontSize = 15.sp,
             modifier = Modifier.clickable { appInfo(akt) }.padding(vertical = 10.dp))
    }
    Absatz("Kannst du auch weglassen und später in den Einstellungen einschalten.")
}

/** Einfuehrung: eigener Lautstaerke-Regler (gleicher Dienst wie die Randgesten) */
@Composable private fun LautEinf(akt: MainActivity) {
    val prefs = remember { akt.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE) }
    var an by remember { androidx.compose.runtime.mutableStateOf(prefs.getBoolean("lautAn", true)) }
    val dienst = LautDienst.istAn(akt)
    Titel("Lautstärke-Regler")
    Absatz("Statt dem Regler vom Handy erscheint beim Drücken der Lautstärketasten ein eigener – im Stil deines Designs, mit feinen Stufen ganz unten.")
    if (!dienst) {
        Absatz("Er läuft über denselben Dienst wie die Randgesten.")
        Knopf("Zu den Bedienungshilfen", Modifier.fillMaxWidth().padding(top = 12.dp)) {
            prefs.edit().putBoolean("lautAn", true).apply(); an = true
            zuDenBedienungshilfen(akt)
        }
        Handgriff("„$dienstName“ einschalten", "Wie auf der Seite davor – danach mit Zurück hierher")
    } else {
        Schalter("Eigener Lautstärke-Regler", an, if (an) "Drück mal eine Lautstärketaste" else "Aus = Lautstärketasten wie gewohnt") {
            an = it; prefs.edit().putBoolean("lautAn", it).apply()
        }
    }
    Absatz("Aussehen und Feinheiten findest du später in den Einstellungen.")
}

@Composable private fun Titel(t: String) =
    Text(t, color = LocalPalette.current.text, fontSize = 30.sp, fontWeight = FontWeight.Medium, lineHeight = 36.sp, modifier = Modifier.padding(bottom = 16.dp))

@Composable private fun Absatz(t: String) =
    Text(t, color = LocalPalette.current.text.copy(alpha = 0.85f), fontSize = 17.sp, lineHeight = 24.sp, modifier = Modifier.padding(vertical = 6.dp))

@Composable private fun Hinweiszeile(t: String) =
    Text(t, color = LocalPalette.current.akzent, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 14.dp))

@Composable private fun Handgriff(was: String, wozu: String) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(was, color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text(wozu, color = p.leise, fontSize = 14.sp, lineHeight = 19.sp)
    }
}

/** Ueber Atelier: Version, Datenschutz in einem Satz, Lizenzen der Schriften, Einfuehrung nochmal */
@Composable
internal fun UeberSeite() {
    val p = LocalPalette.current
    val z = LocalZustand.current
    val ctx = LocalContext.current
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" } }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(Variante.appName, color = p.text, fontSize = 28.sp, fontWeight = FontWeight.Medium)
        if (version.isNotBlank()) Text("Version $version", color = p.leise, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))
        Text("Datenschutz", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("Alles bleibt auf deinem Handy. ${Variante.appName} schickt keine Daten weg, hat keine Werbung und keine Analyse.",
             color = p.leise, fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(16.dp))
        Text("Fehler gefunden?", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Text("Sag dem Entwickler direkt Bescheid – es wird nichts automatisch gemeldet.", color = p.leise, fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(16.dp))
        Text("Schriften", color = p.text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        listOf("Inter" to "The Inter Project Authors", "Kalam" to "Indian Type Foundry", "Poiret One" to "Denis Masharov",
               "Michroma" to "The Michroma Project Authors").forEach { (n, wer) ->
            Text("$n – © $wer, SIL Open Font License 1.1", color = p.leise, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Text("Uhr-Schriften: Monoton, Bungee (Shade, Outline, Inline, Hairline), Train One, Rampart One, Codystar, Major Mono Display, Megrim, " +
             "Orbitron, Wallpoet, Rubik Glitch, Silkscreen, VT323, Monofett, Plaster, Bebas Neue, Six Caps, Abril Fatface, Limelight, Fascinate, " +
             "Shrikhand, Pacifico, Sancreek, Ewert, Nosifer, Fredericka the Great, Londrina Outline, Zilla Slab Highlight – " +
             "© ihre Autorinnen und Autoren (Google Fonts), SIL Open Font License 1.1", color = p.leise, fontSize = 14.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(20.dp))
        UpdateEintrag()
        MenueEintrag("Einführung nochmal zeigen") { z.einfuehrungSetzen(false) }
        Spacer(Modifier.height(24.dp))
    }
}
