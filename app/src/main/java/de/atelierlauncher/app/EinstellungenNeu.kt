package de.atelierlauncher.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Neue Einstellungen (erst Atelier): ganze Seite statt Karte, grosser Titel, Suche, Aussehen-Karte,
//  Zeilen mit Symbol, echte Umschalter, Regler mit Wert, Abschnitte in Versalien.
//  Die bestehenden Bausteine (Gruppe, MenueEintrag, Schalter, Wahl, Regler ...) schalten selbst um -
//  so sieht jede Unterseite gleich aus, ohne dass jede einzeln umgebaut werden muss.
// ============================================================================
object NeuStil {
    val an get() = true
}

/** innerhalb einer Gruppe: Zeilen bekommen oben eine feine Trennlinie */
internal val LocalInGruppe = staticCompositionLocalOf { false }

/** Linienfarbe: Schwarz & Weiss = volle Schriftfarbe (kein Grau), sonst zart */
@Composable
internal fun neuLinie(): Color { val p = LocalPalette.current; return if (SW.an) p.text else p.text.copy(alpha = 0.14f) }

/** Karte der neuen Einstellungen: S&W mit Rahmen, sonst zarte Flaeche */
@Composable
internal fun Modifier.neuKarte(): Modifier {
    val p = LocalPalette.current
    return this.clip(rund(22.dp)).then(
        if (SW.an) Modifier.border(1.dp, p.text, rund(22.dp))
        else Modifier.background(p.text.copy(alpha = 0.06f)))
}

/** feine Linie oben an einer Zeile (die erste in der Gruppe wird von der Gruppe abgeschnitten) */
@Composable
internal fun Modifier.neuTrenner(): Modifier {
    if (!LocalInGruppe.current) return this
    val f = neuLinie()
    return this.drawBehind { drawLine(f, Offset(0f, 0f), Offset(size.width, 0f), 1f) }
}

// ---------------------------------------------------------------- Seite
@Composable
internal fun NeuSeite(seite: String, titel: String, inhalt: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val rein = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rein.animateTo(1f, tween(if (Gefuehl.uebergaenge) 220 else 1, easing = FastOutSlowInEasing)) }
    // Editoren (Baukasten, Code): kleiner Kopf, damit Platz bleibt
    val kompakt = seite in setOf("rasterHaupt", "raster", "codehintergrund", "codealphabet", "meinmenue") || seite.startsWith("eigen_")
    Column(
        Modifier.fillMaxSize()
            .graphicsLayer { translationX = (1f - rein.value) * 48.dp.toPx(); alpha = 0.4f + 0.6f * rein.value }
            .background(p.grund)
            .pointerInput(Unit) { detectTapGestures { } }       // nichts geht durch auf den Startbildschirm
            .systemBarsPadding().imePadding(),
    ) {
        if (seite != "start") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RundKnopf("‹") { navi.zu() }
                Spacer(Modifier.width(12.dp))
                Text(if (kompakt) titel else "Zurück", color = p.text, fontSize = if (kompakt) 20.sp else 15.sp,
                     fontWeight = if (kompakt) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.weight(1f))
            }
            if (!kompakt) Text(titel, color = p.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp,
                               lineHeight = 36.sp, maxLines = 2, modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 4.dp, bottom = 6.dp))
        }
        inhalt()
    }
}

/** runder Knopf mit Rahmen (Zurueck, Schliessen) */
@Composable
internal fun RundKnopf(zeichen: String, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(Modifier.size(40.dp).clip(CircleShape).border(1.dp, if (SW.an) p.text else p.text.copy(alpha = 0.3f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(zeichen, color = p.text, fontSize = 20.sp)
    }
}

// ---------------------------------------------------------------- Bausteine der Seiten
/** Abschnitts-Ueberschrift in Versalien */
@Composable
internal fun NeuUeberschrift(text: String) {
    val p = LocalPalette.current
    Text(text.uppercase(), color = p.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp,
         modifier = Modifier.padding(start = 26.dp, end = 26.dp, top = 18.dp, bottom = 8.dp))
}

/** Karte mit Zeilen - die erste Trennlinie wird abgeschnitten */
@Composable
internal fun NeuGruppe(titel: String?, inhalt: @Composable () -> Unit) {
    if (titel != null) NeuUeberschrift(titel)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().neuKarte()) {
        Column(Modifier.fillMaxWidth().layout { m, c ->
            val pl = m.measure(c)
            layout(pl.width, (pl.height - 1).coerceAtLeast(0)) { pl.place(0, -1) }
        }) {
            androidx.compose.runtime.CompositionLocalProvider(LocalInGruppe provides true) { inhalt() }
        }
    }
}

/** Schalter wie bei iOS / One UI: Spur mit Knopf, gleitet */
@Composable
internal fun SchalterKnopf(an: Boolean) {
    val p = LocalPalette.current
    val x by animateFloatAsState(if (an) 1f else 0f, tween(if (Gefuehl.uebergaenge) 160 else 1), label = "schalter")
    val rand = if (SW.an) p.text else p.leise
    Canvas(Modifier.size(50.dp, 30.dp)) {
        val r = size.height / 2f
        val w = 1.5.dp.toPx()
        if (an) drawRoundRect(p.akzent, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        else drawRoundRect(rand, style = Stroke(w), topLeft = Offset(w / 2, w / 2),
                           size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        val kr = r - 4.dp.toPx()
        val cx = r + (size.width - 2 * r) * x
        drawCircle(if (an) p.aufAkzent else rand, kr, Offset(cx, r))
    }
}

/** Regler: Spur + runder Knopf mit Rahmen - antippen oder ziehen */
@Composable
internal fun NeuRegler(wert: Float, bereich: ClosedFloatingPointRange<Float>, setzen: (Float) -> Unit) {
    val p = LocalPalette.current
    val anteil = ((wert - bereich.start) / (bereich.endInclusive - bereich.start)).coerceIn(0f, 1f)
    val setzenJetzt = androidx.compose.runtime.rememberUpdatedState(setzen)
    fun nach(x: Float, breite: Float, rand: Float) {
        val a = ((x - rand) / (breite - 2 * rand)).coerceIn(0f, 1f)
        setzenJetzt.value(bereich.start + a * (bereich.endInclusive - bereich.start))
    }
    Canvas(
        Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp)
            .pointerInput(bereich) { detectTapGestures { o -> nach(o.x, size.width.toFloat(), 12.dp.toPx()) } }
            .pointerInput(bereich) {
                detectHorizontalDragGestures(onDragStart = { o -> nach(o.x, size.width.toFloat(), 12.dp.toPx()) }) { c, _ ->
                    c.consume(); nach(c.position.x, size.width.toFloat(), 12.dp.toPx())
                }
            }
    ) {
        val rand = 12.dp.toPx()
        val y = size.height / 2f
        val x = rand + (size.width - 2 * rand) * anteil
        val dick = 4.dp.toPx()
        drawLine(if (SW.an) p.text else p.text.copy(alpha = 0.25f), Offset(rand, y), Offset(size.width - rand, y), dick, StrokeCap.Round)
        drawLine(if (SW.an) p.text else p.akzent, Offset(rand, y), Offset(x, y), dick, StrokeCap.Round)
        drawCircle(p.grund, 11.dp.toPx(), Offset(x, y))
        drawCircle(if (SW.an) p.text else p.akzent, 11.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

/** Umschalter mit 2-3 Feldern (statt Chips) */
@Composable
internal fun NeuSegmente(optionen: List<Pair<String, String>>, aktuell: String, waehle: (String) -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(rund(14.dp))
            .border(1.dp, if (SW.an) p.text else p.text.copy(alpha = 0.25f), rund(14.dp))) {
        optionen.forEach { (wert, name) ->
            val an = wert == aktuell
            Box(Modifier.weight(1f).background(if (an) p.akzent else Color.Transparent).clickable { waehle(wert) }.padding(vertical = 10.dp),
                contentAlignment = Alignment.Center) {
                Text(name, color = if (an) p.aufAkzent else p.text, fontSize = 14.sp, fontWeight = if (an) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Symbol in einem Kaestchen links in der Zeile */
@Composable
internal fun NeuSymbol(art: String) {
    val p = LocalPalette.current
    Box(Modifier.size(34.dp).clip(rund(10.dp)).border(1.5.dp, if (SW.an) p.text else p.text.copy(alpha = 0.35f), rund(10.dp)),
        contentAlignment = Alignment.Center) {
        when (art) {
            "zahnrad", "lupe", "termin" -> Symbol(art, p.text, 18.dp)
            "A", "#", "i", "</>" -> androidx.compose.material3.Text(art, color = p.text, fontSize = if (art == "</>") 12.sp else 16.sp, fontWeight = FontWeight.Medium)
            else -> Canvas(Modifier.size(19.dp)) {
                val s = size.minDimension; val st = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                fun pt(x: Float, y: Float) = Offset(x / 24f * s, y / 24f * s)
                when (art) {
                    "raster" -> {
                        drawRoundRect(p.text, pt(4f, 4f), androidx.compose.ui.geometry.Size(7f / 24f * s, 7f / 24f * s), androidx.compose.ui.geometry.CornerRadius(3f), style = st)
                        drawRoundRect(p.text, pt(13f, 4f), androidx.compose.ui.geometry.Size(7f / 24f * s, 7f / 24f * s), androidx.compose.ui.geometry.CornerRadius(3f), style = st)
                        drawRoundRect(p.text, pt(4f, 13f), androidx.compose.ui.geometry.Size(16f / 24f * s, 7f / 24f * s), androidx.compose.ui.geometry.CornerRadius(3f), style = st)
                    }
                    "welle" -> drawPath(Path().apply { moveTo(pt(3f, 12f).x, pt(3f, 12f).y)
                        cubicTo(pt(7f, 4f).x, pt(7f, 4f).y, pt(9f, 4f).x, pt(9f, 4f).y, pt(12f, 12f).x, pt(12f, 12f).y)
                        cubicTo(pt(15f, 20f).x, pt(15f, 20f).y, pt(17f, 20f).x, pt(17f, 20f).y, pt(21f, 12f).x, pt(21f, 12f).y) }, p.text, style = st)
                    "rand" -> { drawLine(p.text, pt(5f, 4f), pt(5f, 20f), st.width, StrokeCap.Round)
                                drawPath(Path().apply { moveTo(pt(10f, 8f).x, pt(10f, 8f).y); lineTo(pt(17f, 12f).x, pt(17f, 12f).y); lineTo(pt(10f, 16f).x, pt(10f, 16f).y) }, p.text, style = st) }
                    "pinsel" -> { drawCircle(p.text, s * 0.32f, pt(12f, 12f), style = st)
                                  drawCircle(p.text, s * 0.06f, pt(9f, 10f)); drawCircle(p.text, s * 0.06f, pt(14f, 9f)); drawCircle(p.text, s * 0.06f, pt(15f, 14f)) }
                    "pfeile" -> { drawLine(p.text, pt(12f, 4f), pt(12f, 15f), st.width, StrokeCap.Round)
                                  drawPath(Path().apply { moveTo(pt(8f, 11f).x, pt(8f, 11f).y); lineTo(pt(12f, 15f).x, pt(12f, 15f).y); lineTo(pt(16f, 11f).x, pt(16f, 11f).y) }, p.text, style = st)
                                  drawLine(p.text, pt(5f, 19f), pt(19f, 19f), st.width, StrokeCap.Round) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Hauptseite
/** was die Suche findet: Titel, Stichworte, Seite */
private data class Treffer(val titel: String, val worte: String, val seite: String, val wo: String)
private val SUCHE = listOf(
    Treffer("Design", "aussehen theme themes vorlage", "design", "Design"),
    Treffer("Themes", "vorlagen designs laden teilen code", "themen", "Design"),
    Treffer("Weitere Designs laden", "wellen swiss gezeichnet art deco minimal", "themen", "Design → Themes"),
    Treffer("Farben", "hell dunkel akzent farbschema wellenfarbe", "d_farben", "Design"),
    Treffer("Hintergrund", "tapete wellen sterne papier 3d tiefe", "d_hintergrund", "Design"),
    Treffer("Sperrbildschirm", "sperre lockscreen", "d_sperr", "Design"),
    Treffer("Uhr", "datum akku sekunden uhrzeit", "d_uhr", "Design"),
    Treffer("Schrift", "font schriftart text ueberschriften", "d_schrift", "Design"),
    Treffer("Eigene Schrift laden", "ttf otf font datei", "schrift", "Design → Schrift"),
    Treffer("Icons", "symbole icon stil pakete", "d_apps", "Design"),
    Treffer("Icon-Pakete", "icon pack arcticons", "icons", "Design → Icons"),
    Treffer("Startbildschirm", "bausteine baukasten favoriten knoepfe raster", "rasterHaupt", "Startbildschirm"),
    Treffer("Alphabet", "buchstaben leiste welle groesse", "alphabet", "Alphabet"),
    Treffer("Klack-Geräusch", "ton klick sound", "alphabet", "Alphabet"),
    Treffer("Code", "privat versteckt geheim von bis", "code", "Alphabet"),
    Treffer("Seite bei #", "raute seite oben nachrichten", "raster", "Seite bei #"),
    Treffer("Gefühl", "vibration federn uebergaenge animation", "gefuehl", "Gefühl"),
    Treffer("Suche startet sofort", "suche starten", "gefuehl", "Gefühl"),
    Treffer("Randgesten", "rand wischen gesten zurueck home", "randgesten", "Randgesten"),
    Treffer("Lautstärke-Regler", "lautstaerke volume regler", "d_laut", "Design"),
    Treffer("System", "statusleiste sprache language", "system", "System"),
    Treffer("Sichern & Wiederherstellen", "backup sicherung export import", "system", "System"),
    Treffer("Berechtigungen", "rechte zugriff benachrichtigungen kalender", "rechte", "System"),
    Treffer("Eigene Bausteine", "code html javascript selbst bauen", "eigene", "Eigene Bausteine"),
    Treffer("Über", "version datenschutz lizenzen einfuehrung", "ueber", "Über"),
)

@Composable
internal fun NeuStartSeite() {
    val navi = LocalNavi.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    var suche by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 16.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Einstellungen", color = p.text, fontSize = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.1).sp,
                 maxLines = 1, modifier = Modifier.weight(1f))
            RundKnopf("✕") { navi.alleZu() }
        }
        // ---- Suche
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(rund(16.dp))
                .then(if (SW.an) Modifier.border(1.dp, p.text, rund(16.dp)) else Modifier.background(p.text.copy(alpha = 0.06f)))
                .padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Symbol("lupe", p.text, 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (suche.isEmpty()) Text("Einstellung suchen", color = p.leise, fontSize = 16.sp)
                BasicTextField(suche, { suche = it }, singleLine = true, cursorBrush = SolidColor(p.akzent),
                               textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 16.sp), modifier = Modifier.fillMaxWidth())
            }
            if (suche.isNotEmpty()) Text("✕", color = p.text, fontSize = 15.sp, modifier = Modifier.clip(CircleShape).clickable { suche = "" }.padding(4.dp))
        }
        if (suche.isNotBlank()) {
            val q = suche.trim().lowercase()
            val treffer = SUCHE.filter { t -> Sprache.tr(t.titel).lowercase().contains(q) || t.titel.lowercase().contains(q) || t.worte.contains(q) }
            Spacer(Modifier.height(10.dp))
            if (treffer.isEmpty()) Text("Nichts gefunden", color = p.leise, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 12.dp))
            else NeuGruppe(null) { treffer.forEach { t -> MenueEintrag(t.titel, t.wo, rechts = "›") { navi.auf(Ebene.Einstellungen(t.seite)) } } }
            Spacer(Modifier.height(24.dp))
            return@Column
        }
        if (!akt.istStandard()) Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth().clip(rund(18.dp)).background(p.akzent)
                .clickable { akt.standardLauncher() }.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Als Startbildschirm festlegen", color = p.aufAkzent, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text("→", color = p.aufAkzent, fontSize = 18.sp)
        }
        // ---- Aussehen
        val t = z.thema
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().neuKarte()
                .clickable { navi.auf(Ebene.Einstellungen("design")) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniBild()
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("AUSSEHEN", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp)
                Text(t.name, color = p.text, fontSize = 21.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                     modifier = Modifier.padding(top = 2.dp))
                Text(Sprache.tr(schriftName(t.schrift)) + " · " + Sprache.tr(iconName(t.icons)) + " · " + Sprache.tr(if (t.alphabet == "swiss") "Feste Leiste" else "Welle"),
                     color = p.leise, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(10.dp))
                Knopf("Design ändern") { navi.auf(Ebene.Einstellungen("design")) }
            }
        }
        NeuGruppe(null) {
            MenueEintrag("Startbildschirm", "Bausteine, Favoriten, Knöpfe", rechts = "›", symbol = "raster") { navi.auf(Ebene.Einstellungen("rasterHaupt")) }
            MenueEintrag("Alphabet", "Aussehen, Wischen, Klack, Code", rechts = "›", symbol = "A") { navi.auf(Ebene.Einstellungen("alphabet")) }
            MenueEintrag("Seite bei #", "In der App-Liste nach links", rechts = "›", symbol = "#") { navi.auf(Ebene.Einstellungen("raster")) }
        }
        Spacer(Modifier.height(10.dp))
        NeuGruppe(null) {
            MenueEintrag("Gefühl", "Vibration, Federn, Übergänge", rechts = "›", symbol = "welle") { navi.auf(Ebene.Einstellungen("gefuehl")) }
            MenueEintrag("Randgesten & Lautstärke", if (Erweiterung.randgesten) "Vom Rand reinwischen" else "Erweiterung – noch nicht geladen",
                         rechts = "›", symbol = "rand") { navi.auf(Ebene.Einstellungen("randgesten")) }
            MenueEintrag("System", "Rechte, Statusleiste, Sprache, Sichern", rechts = "›", symbol = "zahnrad") { navi.auf(Ebene.Einstellungen("system")) }
        }
        Spacer(Modifier.height(10.dp))
        NeuGruppe(null) {
            MenueEintrag("Eigene Bausteine", "Selbst bauen – mit Anleitung", rechts = "›", symbol = "</>") { navi.auf(Ebene.Einstellungen("eigene")) }
            MenueEintrag("Über ${Variante.appName}", "Version, Datenschutz, Schriften", rechts = "›", symbol = "i") { navi.auf(Ebene.Einstellungen("ueber")) }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** kleines Bild vom Startbildschirm in den Farben des Designs */
@Composable
private fun MiniBild() {
    val p = LocalPalette.current
    val z = LocalZustand.current
    Box(Modifier.size(74.dp, 120.dp).clip(rund(12.dp)).background(p.grund)
            .border(1.dp, if (SW.an) p.text else p.text.copy(alpha = 0.3f), rund(12.dp)).padding(8.dp)) {
        Column {
            androidx.compose.material3.Text("12:34", color = p.text, fontSize = 14.sp, fontWeight = FontWeight.Light,
                                            fontFamily = Schriften.familie(z.thema.schrift))
            Spacer(Modifier.height(6.dp))
            listOf(0.8f, 0.62f, 0.7f, 0.52f).forEach { b ->
                Box(Modifier.padding(vertical = 3.dp).fillMaxWidth(b * 0.85f).height(3.dp).clip(rund(2.dp)).background(p.text))
            }
        }
        Canvas(Modifier.align(Alignment.CenterEnd).size(3.dp, 80.dp)) {
            var y = 0f
            while (y < size.height) { drawLine(p.text, Offset(size.width / 2, y), Offset(size.width / 2, y + 2.dp.toPx()), size.width); y += 6.dp.toPx() }
        }
    }
}

/** Gefuehl: Vibration, Federn, Uebergaenge, Suche (frueher unter System) */
@Composable
internal fun GefuehlSeite() {
    val akt = LocalContext.current
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Gruppe("Antippen") {
            Schalter("Vibration beim Antippen", Gefuehl.vibration, "Kurzes Klicken bei Bausteinen, deutlicher beim langen Drücken") { Gefuehl.setzen(akt, "vibration", it) }
            Schalter("Bausteine federn", Gefuehl.federn, "Beim Drücken minimal kleiner, dann federn sie zurück") { Gefuehl.setzen(akt, "federn", it) }
        }
        Gruppe("Bewegung") {
            Schalter("Weiche Übergänge", Gefuehl.uebergaenge, "Bausteine gleiten im Baukasten an ihren Platz, Favoriten / Buchstabe / Seite bei # blenden über") { Gefuehl.setzen(akt, "weich", it) }
        }
        Gruppe("Suche") {
            Schalter("Suche startet sofort", Gefuehl.sucheSofort, "Passt nur noch eine App, startet sie ohne Antippen") { Gefuehl.setzen(akt, "sofort", it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}


/** Schrift der Uhr: Karten mit "12:34" in jeder Schrift zum Antippen (waagerecht wischen) */
@Composable
internal fun UhrSchriftWahl(aktuell: String, waehle: (String) -> Unit) {
    val p = LocalPalette.current
    Text("Schrift der Uhr", color = p.text, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val alle = listOf("" to "Wie Design") + Schriften.KUNST
        items(alle.size) { i ->
            val (wert, name) = alle[i]
            val an = wert == aktuell
            val familie = remember(wert) { if (wert.isEmpty()) null else Schriften.familie(wert) }
            Column(
                Modifier.width(118.dp).clip(rund(16.dp))
                    .border(if (an) 2.dp else 1.dp, if (an) p.akzent else if (SW.an) p.text else p.text.copy(alpha = 0.25f), rund(16.dp))
                    .clickable { waehle(wert) }.padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text("12:34", color = p.text, fontSize = 30.sp, maxLines = 1, softWrap = false,
                    fontFamily = familie ?: LocalTextStyle.current.fontFamily)
                Text(name, color = p.leise, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
