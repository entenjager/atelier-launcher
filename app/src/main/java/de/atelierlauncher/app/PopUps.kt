package de.atelierlauncher.app

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlin.math.exp
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================
//  Bausteine (Blatt, Knoepfe, Eingabe) und alle Pop-ups:
// ============================================================================

/** Karte, die von unten hereinkommt. Tippen daneben schliesst sie */
@Composable
fun Blatt(
    onSchliessen: () -> Unit,
    vollbild: Boolean = false,
    schleier: Float = 0.45f,
    hoechstens: Float? = null,          // Anteil der Bildschirmhoehe
    vonOben: Boolean = false,           // kommt von oben herein (Griff unten, zum Schliessen nach oben schieben)
    inhalt: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    val bildschirm = LocalConfiguration.current.screenHeightDp
    val r = if (vonOben) -1f else 1f        // Richtung, in die das Blatt verschwindet
    val rein = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rein.animateTo(1f, tween(200, easing = FastOutSlowInEasing)) }
    // Am Griff ziehen: die Karte folgt genau dem Finger, beim Loslassen
    // federt sie zurueck oder fliegt mit dem Schwung des Fingers raus
    val zurueck = remember { Animatable(0f) }
    val zieh = remember { mutableFloatStateOf(0f) }
    var zieht by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val schliessen by rememberUpdatedState(onSchliessen)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = rein.value }
            .background(Color.Black.copy(alpha = schleier))
            .pointerInput(Unit) { detectTapGestures { onSchliessen() } }
            .systemBarsPadding()
            .imePadding()
            .padding(12.dp),
        contentAlignment = if (vonOben) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (vollbild) Modifier.fillMaxHeight() else Modifier)
                .then(if (hoechstens != null) Modifier.heightIn(max = (bildschirm * hoechstens).dp) else Modifier)
                .graphicsLayer {
                    translationY = r * ((1f - rein.value) * 120.dp.toPx() + if (zieht) zieh.floatValue else zurueck.value)
                }
                .clip(rund(26.dp))
                .background(p.karte)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(top = if (vonOben) 10.dp else 0.dp, bottom = if (vonOben) 0.dp else 10.dp),
        ) {
            val griff: @Composable () -> Unit = {
                Box(
                    Modifier.fillMaxWidth().height(24.dp).pointerInput(Unit) {
                        val tempo = VelocityTracker()
                        var weg = 0f
                        detectVerticalDragGestures(
                            onDragStart = { tempo.resetTracking(); weg = zurueck.value; zieh.floatValue = weg; zieht = true },
                            onDragEnd = {
                                val v = tempo.calculateVelocity().y
                                val start = zieh.floatValue
                                zieht = false
                                scope.launch {
                                    zurueck.snapTo(start)
                                    if (start > 110.dp.toPx() || v > 1300f) {
                                        zurueck.animateTo(start + 900.dp.toPx(), tween(170), initialVelocity = v)
                                        schliessen()
                                    } else {
                                        zurueck.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 420f), initialVelocity = v)
                                    }
                                }
                            },
                            onDragCancel = {
                                val start = zieh.floatValue
                                zieht = false
                                scope.launch { zurueck.snapTo(start); zurueck.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 420f)) }
                            },
                        ) { change, d ->
                            change.consume()
                            weg += d * r                     // in Schliess-Richtung gemessen
                            // in die andere Richtung nur ein kleines bisschen (Gummiband)
                            zieh.floatValue = if (weg >= 0f) weg else -12.dp.toPx() * (1f - exp(weg / 60.dp.toPx()))
                            tempo.addPosition(change.uptimeMillis, Offset(0f, weg))
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(rund(2.dp)).background(p.leise.copy(alpha = 0.45f)))
                }
            }
            if (!vonOben) griff()
            inhalt()
            if (vonOben) griff()
        }
    }
}

@Composable
fun BlattKopf(titel: String, rechts: String = "Fertig", onRechts: () -> Unit) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(titel, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(rechts, color = p.akzent, fontSize = 17.sp,
             modifier = Modifier.clip(rund(10.dp)).clickable(onClick = onRechts)
                 .padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
fun Abschnitt(text: String) {
    if (NeuStil.an && !Stil.typoSwiss) { NeuUeberschrift(text); return }
    if (Stil.typoGeld) {
        val p = LocalPalette.current
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            GeldZier(p.akzent)
            Spacer(Modifier.width(8.dp))
            Text(text.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 2.2.sp)
        }
        return
    }
    if (Stil.typoSwiss) {
        val p = LocalPalette.current
        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 12.dp).background(p.akzent))
            Spacer(Modifier.width(8.dp))
            Text(text.uppercase(), color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp)
        }
        return
    }
    Text(text.uppercase(), color = LocalPalette.current.akzent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
         letterSpacing = 1.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
fun Chip(text: String, gewaehlt: Boolean = false, farbe: Color? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    // Gezeichnet: nichts mit Tinte zumalen - die Wahl wird umrahmt
    if (Skizze.an) {
        Text(
            text, color = farbe ?: p.text, fontSize = 15.sp, maxLines = 1,
            modifier = Modifier
                .then(if (gewaehlt) Modifier.skizzeRahmen(14.dp, aus = 1.dp, saat = text.hashCode()) else Modifier)
                .clip(rund(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        )
        return
    }
    Text(
        text,
        color = if (gewaehlt) p.aufAkzent else farbe ?: p.text,
        fontSize = 14.sp, maxLines = 1,
        modifier = Modifier
            .clip(rund(14.dp))
            .background(if (gewaehlt) p.akzent else p.linie)
            // Schwarz & Weiss: nicht gewaehlt = nur Rahmen (Schrift bleibt lesbar)
            .then(if (SW.an && !gewaehlt) Modifier.border(1.dp, p.text, rund(14.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
fun Knopf(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Text(
        text, color = p.aufAkzent, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
        modifier = modifier.clip(rund(16.dp)).background(p.akzent).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    )
}

@Composable
fun Schalter(text: String, an: Boolean, unter: String? = null, onWechsel: (Boolean) -> Unit) {
    val p = LocalPalette.current
    // neue Einstellungen (Atelier): Zeile mit Trennlinie, eigener Schalter
    if (NeuStil.an && !Skizze.an) {
        Row(Modifier.fillMaxWidth().neuTrenner().clickable { onWechsel(!an) }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(text, color = p.text, fontSize = 17.sp)
                if (unter != null) Text(unter, color = p.leise, fontSize = 12.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(12.dp))
            SchalterKnopf(an)
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clickable { onWechsel(!an) }.padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 16.sp)
            if (unter != null) Text(unter, color = p.leise, fontSize = 13.sp)
        }
        if (Skizze.an) SkizzeHaken(an, text.hashCode()) else Switch(checked = an, onCheckedChange = onWechsel)
    }
}

@Composable
fun MenueEintrag(text: String, unter: String? = null, farbe: Color? = null, rechts: String? = null, symbol: String? = null, onClick: () -> Unit) {
    val p = LocalPalette.current
    // neue Einstellungen (Atelier): Symbol im Kaestchen, Trennlinie, Pfeil
    if (NeuStil.an) {
        Row(Modifier.fillMaxWidth().neuTrenner().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = if (symbol != null) 12.dp else 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (symbol != null) { NeuSymbol(symbol); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(text, color = farbe ?: p.text, fontSize = 17.sp)
                if (unter != null) Text(unter, color = p.leise, fontSize = 12.5.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp))
            }
            if (rechts != null) Text(rechts, color = p.text, fontSize = if (rechts == "›") 22.sp else 14.sp, modifier = Modifier.padding(start = 8.dp))
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text, color = farbe ?: p.text, fontSize = 17.sp)
            if (unter != null) Text(unter, color = p.leise, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (rechts != null) Text(rechts, color = p.leise, fontSize = 14.sp)
    }
}

@Composable
fun Eingabe(
    wert: String,
    onWert: (String) -> Unit,
    hinweis: String,
    modifier: Modifier = Modifier,
    fokus: Boolean = false,
    geheim: Boolean = false,              // Passwortfeld: Punkte statt Buchstaben
    onFertig: (() -> Unit)? = null,
) {
    val p = LocalPalette.current
    val anfrage = remember { FocusRequester() }
    Box(
        modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(rund(14.dp))
            .background(p.linie)
            .then(if (SW.an) Modifier.border(1.dp, p.text, rund(14.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (wert.isEmpty()) Text(hinweis, color = p.leise, fontSize = 17.sp)
        BasicTextField(
            value = wert, onValueChange = onWert, singleLine = true,
            textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 17.sp),
            cursorBrush = SolidColor(p.akzent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done,
                keyboardType = if (geheim) KeyboardType.Password else KeyboardType.Text),
            keyboardActions = KeyboardActions(onDone = { onFertig?.invoke() }),
            visualTransformation = if (geheim) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().focusRequester(anfrage),
        )
    }
    if (fokus) LaunchedEffect(Unit) { try { anfrage.requestFocus() } catch (e: Exception) { } }
}

// ============================================================================
//  App-Pop-up (nach rechts wischen oder lange druecken)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppPopup(key: String) {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    val app = z.app(key)
    if (app == null) { LaunchedEffect(key) { navi.zu() }; return }
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val kurz by produceState(emptyList<Kurzbefehl>(), key) {
        value = withContext(Dispatchers.IO) { akt.kurzbefehle(app.paket) }
    }
    val fav = z.istFavorit(key)
    val pos = z.favoriten.indexOf(key)

    Blatt(onSchliessen = { navi.zu() }) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            // Kopf: Icon + Name, Tippen startet die App
            Row(
                Modifier.fillMaxWidth().clickable { navi.alleZu(); akt.starte(app) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(key, 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.name, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (z.namen.containsKey(key)) Text("umbenannt", color = p.leise, fontSize = 12.sp)
                }
            }
            BuendelReihe(key)
            if (kurz.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                kurz.forEach { k ->
                    Row(
                        Modifier.fillMaxWidth().clickable { navi.alleZu(); akt.kurzbefehlStarten(k) }
                            .padding(horizontal = 22.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(26.dp)) { k.bild?.let { Image(it, null, Modifier.fillMaxSize()) } }
                        Spacer(Modifier.width(16.dp))
                        Text(k.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Chip(if (fav) "Favorit ✓" else "Zu Favoriten", gewaehlt = fav) {
                    if (!fav && z.favoriten.size >= z.favMax)
                        Toast.makeText(akt, Sprache.tr("Höchstens ${z.favMax} Favoriten"), Toast.LENGTH_SHORT).show()
                    else z.favoritUmschalten(key)
                }
                if (fav && pos > 0) Chip("Nach oben") { z.favoritVerschieben(key, -1) }
                if (fav && pos in 0 until z.favoriten.size - 1) Chip("Nach unten") { z.favoritVerschieben(key, 1) }
                Chip("Umbenennen") { navi.auf(Ebene.Umbenennen(key)) }
                Chip("Icon ändern") { navi.auf(Ebene.IconWahl(key)) }
                Chip("Apps bündeln") { navi.auf(Ebene.Buendeln(key)) }
                Chip(if (key in z.versteckt) "Aus Privat entfernen" else "Privat machen") { navi.zu(); z.verstecken(key) }
                Chip("App-Info") { navi.alleZu(); akt.info(app.paket) }
                Chip("Deinstallieren", farbe = p.warnung) { navi.alleZu(); akt.deinstallieren(app.paket) }
            }
        }
    }
}

/** Gebuendelte Apps: kleine Reihe, ein Tippen startet sie */
@Composable
private fun BuendelReihe(key: String) {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    val akt = LocalContext.current as MainActivity
    val liste = z.buendel[key].orEmpty().mapNotNull { z.app(it) }
    if (liste.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp)) {
        liste.forEach { a ->
            Column(
                Modifier.width(72.dp).clip(rund(14.dp)).clickable { navi.alleZu(); akt.starte(a) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AppIcon(a.schluessel, 40.dp)
                Spacer(Modifier.height(4.dp))
                Text(a.name, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

// ============================================================================
//  Umbenennen
// ============================================================================
@Composable
fun UmbenennenBlatt(key: String) {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    val original = z.rohApps.firstOrNull { it.schluessel == key }?.name
    var name by remember { mutableStateOf(z.app(key)?.name ?: "") }
    fun speichern() {
        z.umbenennen(key, if (name.trim() == original) null else name)
        navi.zu()
    }
    Blatt(onSchliessen = { navi.zu() }) {
        BlattKopf("Umbenennen", "Speichern") { speichern() }
        Eingabe(name, { name = it }, "Name", fokus = true, onFertig = { speichern() })
        if (original != null) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Chip("Originalname: $original") { z.umbenennen(key, null); navi.zu() }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ============================================================================
//  Icon-Assistent: Icon aus einem installierten Icon-Paket waehlen
// ============================================================================
@Composable
fun IconWahlBlatt(key: String) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val akt = LocalContext.current as MainActivity
    val app = z.app(key)
    val pakete by produceState<List<IconPaket>?>(null) { value = withContext(Dispatchers.IO) { IconPakete.installiert(akt) } }
    var paket by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pakete) {
        if (paket == null) {
            val aktuell = z.thema.icons.removePrefix("paket:")
            paket = pakete?.firstOrNull { it.paket == aktuell }?.paket ?: pakete?.firstOrNull()?.paket
        }
    }
    var suche by remember { mutableStateOf(app?.name?.substringBefore(' ')?.lowercase() ?: "") }
    val namen by produceState(emptyList<String>(), paket) {
        value = paket?.let { pk -> withContext(Dispatchers.IO) { IconPakete.alleBilder(akt, pk) } } ?: emptyList()
    }
    val vorschlag by produceState<String?>(null, paket) {
        value = paket?.let { pk -> withContext(Dispatchers.IO) { IconPakete.zuordnung(akt, pk)[key] } }
    }
    val gefiltert = remember(namen, suche) {
        val s = suche.trim().lowercase().replace(' ', '_')
        if (s.isEmpty()) namen else namen.filter { it.contains(s) }
    }

    Blatt(onSchliessen = { navi.zu() }, vollbild = true) {
        BlattKopf("Icon ändern" + (app?.let { " · ${it.name}" } ?: "")) { navi.zu() }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Original verwenden", gewaehlt = key !in z.eigeneIcons) { z.eigenesIcon(key, null); navi.zu() }
        }
        val liste = pakete
        when {
            liste == null -> Text("Suche Icon-Pakete …", color = p.leise, modifier = Modifier.padding(20.dp))
            liste.isEmpty() -> Text(
                "Kein Icon-Paket installiert.\n\nIcon-Pakete sind normale Apps. Kostenlos und schön sind z. B. " +
                    "„Arcticons“ (feine Linien, ähnlich Anycons), „Lawnicons“ oder „Delta“ – im Play Store oder bei F-Droid. " +
                    "Danach hier wieder öffnen.",
                color = p.leise, fontSize = 15.sp, modifier = Modifier.padding(20.dp),
            )
            else -> {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    liste.forEach { ip -> Chip(ip.name, gewaehlt = ip.paket == paket) { paket = ip.paket } }
                }
                Eingabe(suche, { suche = it }, "Icon suchen")
                val pk = paket
                if (pk != null) {
                    LazyVerticalGrid(GridCells.Adaptive(68.dp), Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
                        val v = vorschlag
                        if (v != null && suche.isNotEmpty() && v !in gefiltert) {
                            item(key = "vorschlag") { IconZelle(pk, v, true) { z.eigenesIcon(key, "paket:$pk:$v"); navi.zu() } }
                        }
                        items(gefiltert, key = { it }) { name ->
                            IconZelle(pk, name, name == v) { z.eigenesIcon(key, "paket:$pk:$name"); navi.zu() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IconZelle(paket: String, name: String, vorschlag: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val bild by produceState<ImageBitmap?>(null, paket, name) {
        value = withContext(Dispatchers.IO) { IconPakete.vorschau(ctx, paket, name) }
    }
    Column(
        Modifier.padding(3.dp).clip(rund(14.dp))
            .background(if (vorschlag) p.akzent.copy(alpha = 0.22f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(44.dp)) { bild?.let { Image(it, null, Modifier.fillMaxSize()) } }
        Spacer(Modifier.height(4.dp))
        Text(name.replace('_', ' '), fontSize = 10.sp, color = p.leise, maxLines = 1, overflow = TextOverflow.Ellipsis,
             modifier = Modifier.padding(horizontal = 2.dp))
    }
}

// ============================================================================
// ============================================================================
@Composable
fun AppAuswahl(
    titel: String,
    start: List<String>,
    knopf: String = "Fertig",
    hinweis: String? = null,
    ausschliessen: Set<String> = emptySet(),
    kopfZusatz: @Composable () -> Unit = {},
    onFertig: (List<String>) -> Unit,
) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val gewaehlt = remember { mutableStateListOf<String>().apply { addAll(start) } }
    var suche by remember { mutableStateOf("") }
    val alle = z.apps
    val liste = remember(alle, suche) {
        alle.filter { it.schluessel !in ausschliessen && (suche.isBlank() || it.name.contains(suche.trim(), true)) }
    }
    Blatt(onSchliessen = { navi.zu() }, vollbild = true) {
        BlattKopf(titel, knopf) { onFertig(gewaehlt.toList()) }
        if (hinweis != null) Text(hinweis, color = p.leise, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
        kopfZusatz()
        Eingabe(suche, { suche = it }, "Apps suchen")
        Text("${gewaehlt.size} ausgewählt", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(liste, key = { it.schluessel }) { a ->
                val an = a.schluessel in gewaehlt
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { if (an) gewaehlt.remove(a.schluessel) else gewaehlt.add(a.schluessel) }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.schluessel, 32.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(a.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Checkbox(checked = an, onCheckedChange = {
                        if (an) gewaehlt.remove(a.schluessel) else gewaehlt.add(a.schluessel)
                    })
                }
            }
        }
    }
}

@Composable
fun BuendelnBlatt(key: String) {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    AppAuswahl(
        titel = "Apps bündeln",
        start = z.buendel[key].orEmpty(),
        hinweis = "Diese Apps erscheinen im Pop-up von „${z.app(key)?.name ?: ""}“, z. B. Kamera + Galerie.",
        ausschliessen = setOf(key),
    ) { auswahl -> z.buendelSetzen(key, auswahl); navi.zu() }
}

// ============================================================================
//  Apps hier tauchen sonst nirgends auf - nicht in der Liste, nicht in der Suche.
// ============================================================================
@Composable
fun PrivatBereich() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val akt = LocalContext.current as MainActivity
    val apps = z.apps.filter { it.schluessel in z.versteckt }
    Blatt(onSchliessen = { navi.zu() }, vollbild = true, schleier = 0.6f) {
        BlattKopf("Privat", "Schließen") { navi.zu() }
        if (apps.isEmpty()) {
            Text("Hier ist noch nichts. Tippe unten auf „Apps auswählen“ – die Apps verschwinden dann aus " +
                 "der Liste, der Suche und den Favoriten und sind nur noch hier zu finden.",
                 color = p.leise, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp)) {
            items(apps, key = { it.schluessel }) { a ->
                AppZeile(a, gross = false, starten = { akt.starte(it) }, oeffnen = { navi.auf(Ebene.Popup(it)) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Knopf("Apps auswählen") { navi.auf(Ebene.PrivatAuswahl) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun PrivatAuswahlBlatt() {
    val z = LocalZustand.current
    val navi = LocalNavi.current
    AppAuswahl(
        titel = "Private Apps",
        start = z.versteckt.toList(),
        knopf = "Speichern",
        hinweis = "Ausgewählte Apps sind nur noch im privaten Bereich zu sehen.",
    ) { auswahl -> z.versteckteSetzen(auswahl.toSet()); navi.zu() }
}
