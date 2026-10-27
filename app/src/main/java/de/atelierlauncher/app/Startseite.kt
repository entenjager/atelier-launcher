package de.atelierlauncher.app

import android.content.Context
import android.os.BatteryManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.roundToInt

// ============================================================================
//  Startbildschirm: Kopfzeile (Uhr, Datum, Wetter, Termin, Musik),
//  Favoriten, App-Liste, Suche und Alphabet-Strich
// ============================================================================

/** Was gerade ueber dem Startbildschirm liegt (Zurueck schliesst die oberste Ebene) */
sealed class Ebene {
    data class Popup(val key: String) : Ebene()
    data class Umbenennen(val key: String) : Ebene()
    data class IconWahl(val key: String) : Ebene()
    data class Buendeln(val key: String) : Ebene()
    data class Einstellungen(val seite: String = "start") : Ebene()
    object Uhr : Ebene()
    object Privat : Ebene()
    object PrivatAuswahl : Ebene()
    object KnopfPlatzieren : Ebene()
    /** Startbildschirm bearbeiten: leicht herausgezoomt, direkt auf dem Startbildschirm */
    object Baukasten : Ebene()
}

class Navi(private val ebenen: SnapshotStateList<Ebene>) {
    fun auf(e: Ebene) {
        // "Startbildschirm" (Einstellungen, lange druecken, Suche ...) = kein eigenes Menue mehr, sondern der Zoom-Baukasten
        if (e == Ebene.Einstellungen("rasterHaupt")) { ebenen.clear(); ebenen.add(Ebene.Baukasten) } else ebenen.add(e)
    }
    fun zu() { if (ebenen.isNotEmpty()) ebenen.removeAt(ebenen.lastIndex) }
    fun alleZu() { ebenen.clear() }
}

val LocalNavi = staticCompositionLocalOf<Navi> { error("Keine Navigation") }

enum class Modus { START, LISTE, SUCHE, SEITE }

/** Zeilen der App-Liste */
@Immutable
sealed class ListenZeile(val key: String) {
    class Kopf(val buchstabe: String) : ListenZeile("kopf:$buchstabe")
    class App(val app: AppEintrag) : ListenZeile(app.schluessel)
    object Schnell : ListenZeile("schnell")       // Schnellzugriff unter "<"
}

private fun ListenZeile.name() = when (this) { is ListenZeile.App -> app.name; else -> "" }
private fun ListenZeile.buchstabe() = when (this) {
    is ListenZeile.App -> app.buchstabe; is ListenZeile.Kopf -> buchstabe; ListenZeile.Schnell -> "<"
}

/** So weit rueckt der Inhalt nach rechts, wenn man auf der linken Seite liegt */
val LINKS_EINRUECKEN = 80.dp

@Composable
fun schattenStil(): TextStyle {
    val p = LocalPalette.current
    val n = Neigung.launcher
    if (!n.aktiv.value) return LocalTextStyle.current.copy(shadow = p.schatten)
    // 3D: kleiner, weicher Schatten - Licht von oben, beim Kippen wandert er gegenueber (Inhalt schwebt)
    val d = LocalDensity.current.density
    val versatz = Offset(-n.sx.floatValue * 4f * d, 2f * d - n.sy.floatValue * 4f * d)
    return LocalTextStyle.current.copy(
        shadow = Shadow(Color.Black.copy(alpha = if (p.dunkel) 0.55f else 0.22f), versatz, 5f * d))
}

// ============================================================================
@Composable
fun Launcher() {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val akt = LocalContext.current as MainActivity
    val ebenen = remember { mutableStateListOf<Ebene>() }
    val navi = remember { Navi(ebenen) }
    var modus by remember { mutableStateOf(Modus.START) }
    var suche by remember { mutableStateOf("") }
    var seite by remember { mutableStateOf("A") }            // Buchstabe, dessen Seite gerade zu sehen ist
    var seitenHoehe by remember { mutableFloatStateOf(0.5f) } // wo der Buchstabe im Alphabet sitzt (0 oben, 1 unten)
    val tastatur = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()

    // Atelier: auf # nach links -> die Seite oben erscheint in der App-Liste (statt eigener Seite)
    var abcSeite by remember { mutableStateOf(false) }
    var abcPrivat by remember { mutableStateOf(false) }
    // Foto fuer das Zurueckkommen nur, wenn der Startbildschirm "in Ruhe" ist: keine Liste / Suche / Popups /
    val ruhigFuerFoto = modus == Modus.START && !abcSeite && !abcPrivat && ebenen.isEmpty() && suche.isEmpty() && !z.musterPunkt
    SideEffect { Heim.ruhigSetzen(ruhigFuerFoto) }
    fun zurStartseite() {
        abcSeite = false; abcPrivat = false
        modus = Modus.START; navi.alleZu(); suche = ""; tastatur?.hide()
    }
    LaunchedEffect(z.heim) { if (z.heim > 0) zurStartseite() }
    LaunchedEffect(z.oeffnenWunsch) {
        val w = z.oeffnenWunsch ?: return@LaunchedEffect
        zurStartseite(); navi.auf(Ebene.Einstellungen(w)); z.oeffnenWunsch = null
    }
    // Statusleiste ganz oben weg (von oben wischen zeigt sie kurz)
    LaunchedEffect(z.statusAus, z.fortsetzen) {
        val fenster = akt.window
        androidx.core.view.WindowCompat.getInsetsController(fenster, fenster.decorView).apply {
            if (z.statusAus) {
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            } else show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
    }
    val landen = remember { Animatable(1f) }
    // nur wenn man aus einer gestarteten App zurueckkommt (nicht bei jedem onResume, z. B. Bildschirm an)
    LaunchedEffect(z.fortsetzen) {
        // (22.10.) Hereinfedern beim Zurueckkommen abgeschaltet: lief gleichzeitig mit Androids eigener
        // Heim-Animation und liess das Bild beim Hochwischen ca. 0,5 s flackern
        akt.vonApp = false
        if (landen.value < 1f) {
            // Hereingleiten wurde unterbrochen (z. B. Bildschirm kurz aus/an): sonst bliebe die Startseite
            // halb durchsichtig stehen - Uhr und Apps wirkten dunkler als auf den Buchstaben-Seiten
            landen.animateTo(1f, tween(180))
        }
    }
    LaunchedEffect(z.privatSperrZaehler) {
        abcPrivat = false
        if (modus == Modus.LISTE && !abcSeite) modus = Modus.START
        val i = ebenen.indexOfFirst { it is Ebene.Privat || it is Ebene.PrivatAuswahl }
        if (i >= 0) while (ebenen.size > i) ebenen.removeAt(ebenen.lastIndex)
    }
    BackHandler(enabled = ebenen.isNotEmpty() || modus != Modus.START) {
        if (ebenen.isNotEmpty()) navi.zu() else zurStartseite()
    }

    // Kalender und Wetter nur laden, wenn sie irgendwo angezeigt werden
    val brauchtKalender = z.kopf.termin
    val brauchtWetter = z.kopf.wetter
    // Schleifen laufen nur, solange der Launcher zu sehen ist (andere App offen = Pause)
    val sichtbar = Sichtbar.an
    // Hintergrundbild des Handys nur durchscheinen lassen, wo es gebraucht wird (sonst schlaeft die Tapete)
    LaunchedEffect(z.thema.hintergrund, z.fortsetzen) {
        val hg = z.thema.hintergrund
        // Swiss: ist unsere eigene Live-Tapete aktiv, malt die das Raster -> durchscheinen lassen
        val eigene = hg == "swiss" && EigeneTapete.aktiv(akt)
        akt.tapeteZeigen(hg == "system" || hg == "lottie" || eigene)
    }
    // Akku fuer die Wellen: einmal pro Minute reicht
    LaunchedEffect(z.fortsetzen, z.wellenAkku, sichtbar) {
        if (!z.wellenAkku) { WellenReiz.akku = 1f; return@LaunchedEffect }
        if (!sichtbar) return@LaunchedEffect
        val bm = akt.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            val a = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } catch (e: Exception) { -1 }
            if (a in 0..100) WellenReiz.akku = a / 100f
            delay(60_000L)
        }
    }
    // letzte Ladezeiten: nach dem Zurueckkommen nur nachladen, wenn der Abstand schon um ist
    val kalenderZuletzt = remember { longArrayOf(0L) }
    LaunchedEffect(brauchtKalender, sichtbar) {
        while (brauchtKalender && sichtbar) {
            val warte = kalenderZuletzt[0] + 5 * 60_000L - System.currentTimeMillis()
            if (warte > 0) delay(warte)
            Kalender.laden(akt)
            kalenderZuletzt[0] = System.currentTimeMillis()
        }
    }
    val wetterZuletzt = remember(z.ort, Wetter.version.intValue) { longArrayOf(0L) }   // neuer Ort -> sofort laden
    LaunchedEffect(brauchtWetter, z.ort, Wetter.version.intValue, sichtbar) {
        while (brauchtWetter && sichtbar) {
            val warte = wetterZuletzt[0] + 15 * 60_000L - System.currentTimeMillis()
            if (warte > 0) delay(warte)
            Wetter.laden(akt, z.ort)
            wetterZuletzt[0] = System.currentTimeMillis()
        }
    }

    val apps = z.apps
    val elemente = remember(apps, z.versteckt) {
        apps.filter { it.schluessel !in z.versteckt }.map { ListenZeile.App(it) }
    }
    // Suche: erst ab dem ersten Buchstaben eine Liste (leere Suche = nichts zu bauen -> sofort da)
    val zeilen = remember(elemente, suche) {
        val s = suche.trim()
        if (s.isEmpty()) emptyList()
        else elemente
            .filter { it.name().contains(s, ignoreCase = true) }
            .sortedWith(compareBy({ !it.name().startsWith(s, ignoreCase = true) }, { it.name().lowercase() }))
            .take(40)
    }
    // Atelier: die Einstellungen stehen nicht mehr unter E, sondern ganz unten als eigener Punkt "·"
    val buchstaben = remember(elemente) {
        listOf("#") + (elemente.map { it.buchstabe() } + EINSTELL_PUNKT).distinct().sortedBy { sortierBuchstabe(it) }
    }
    // alte Zeichen, die es im Alphabet nicht mehr gibt, auf den letzten Buchstaben abbilden
    fun gibt(x: String) = if (x in buchstaben) x else buchstaben.lastOrNull { it != EINSTELL_PUNKT } ?: "#"
    val codeStart = remember(z.codeStart, buchstaben) { z.codeStart.map { gibt(it.toString()) }.toSet() }
    val codeEnde = remember(z.codeEnde, buchstaben) { if (z.codeEnde == "#") "#" else gibt(z.codeEnde) }
    val starten: (AppEintrag) -> Unit = { tastatur?.hide(); akt.starte(it) }
    val oeffnen: (String) -> Unit = { tastatur?.hide(); navi.auf(Ebene.Popup(it)) }

    // im Liegen auf der rechten Seite: NUR Alphabet + Wellen wandern nach links, Text bleibt wie er ist.
    // Seitenwechsel als Wasser-Animation: sammeln (unten) -> rueberfliessen -> aufsteigen
    var seiteLinks by remember { mutableStateOf(z.gespiegelt) }      // Seite, auf der Wellen/Alphabet gerade stehen
    // Seitenwechsel ganz schlicht: Welle + Alphabet verblassen, wechseln die Seite, erscheinen wieder.
    // 0 = voll da, 1 = ausgeblendet
    val wechsel = remember { Animatable(0f) }
    LaunchedEffect(z.gespiegelt) {
        if (z.gespiegelt != seiteLinks) {
            wechsel.animateTo(1f, tween(200, easing = FastOutSlowInEasing))
            seiteLinks = z.gespiegelt
        }
        wechsel.animateTo(0f, tween(320, easing = FastOutSlowInEasing))
    }
    val richtung = if (seiteLinks) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
    val spiegelX = if (seiteLinks) -1f else 1f
    val wellenWechsel: androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit = {
        val pw = wechsel.value
        if (pw > 0f) alpha *= 1f - pw
    }
    val gesteStand = remember { BooleanArray(2) }
    val geheimScharf: () -> Unit = remember(z) { { z.musterBereitBis = System.currentTimeMillis() + 8_000; z.musterPunkt = true; z.musterTipp++ } }
    CompositionLocalProvider(LocalNavi provides navi) {
        // unsichtbarer Baustein: auch hier oben mitlesen (die ganze Seite bekommt jede Beruehrung zuerst)
        val hatKnopfBaustein = true && SeitenRaster.laden(akt, "start").any { it.typ == "geheim" }
        val knopfAnteil = if (hatKnopfBaustein) KnopfLage.anteil() else null
        // WICHTIG: an/aus darf sich waehrend einer Beruehrung nicht aendern (sonst bricht das Wischen uebers
        // Alphabet ab, sobald der erste Buchstabe die App-Liste oeffnet) - ob Startseite, wird erst beim Tippen geprueft
        val knopfJetzt = rememberUpdatedState(modus == Modus.START && ebenen.isEmpty())
        val knopfTipp: () -> Unit = remember(z) { { if (knopfJetzt.value) geheimScharf() } }
        Box(Modifier.fillMaxSize()
            .onGloballyPositioned { val r = it.boundsInRoot(); if (KnopfLage.wurzel.value != r) KnopfLage.wurzel.value = r }
            .leiserTipp(knopfAnteil != null, knopfAnteil, knopfTipp)
            .leiserTipp(
            modus == Modus.START && ebenen.isEmpty() && z.privatAn && z.geheimOrt == "eigen", z.geheimRect, geheimScharf)) {
            // in Liste/Suche steht der Hintergrund still -> die ganze Rechenzeit gehoert dem Scrollen
            // Wellen auf der Startseite: Untergrund hier, die Wellen selbst als Schicht ueber Uhr und Favoriten (weiter unten)
            // Wellen und Eis liegen als Schicht ueber Uhr und Apps (der Untergrund ist einfarbig)
            // Baukasten: Buchstaben-Seite / Seite bei # erscheinen IM App-Listen-Baustein - der Hintergrund bleibt dabei
            // wie auf dem Startbildschirm (nicht abdunkeln, nichts anhalten). Nur die Suche legt sich darueber.
            val listeImBaustein = true && SeitenRaster.laden(akt, "start").any { it.typ == "favoriten" }
            val wieStart = modus == Modus.START || (listeImBaustein && modus == Modus.LISTE)
            val wellenOben = z.thema.hintergrund in setOf("wellen", "eis", "pixel")
            // (mitschneiden: fuer die Welle aus Milchglas - sieht sonst ganz normal aus)
            if (wellenOben) Box(Modifier.fillMaxSize().mitschneiden { WelleGlas.hg }.background(p.grund))
            else Hintergrund(z.thema.hintergrund, Modifier.fillMaxSize().mitschneiden { WelleGlas.hg }, laeuft = wieStart, durchsichtig = true)
            if (z.thema.hintergrund == "system" && p.dunkel) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f)))))
            }
            val dunkel = animateFloatAsState(if (wieStart) 0f else 1f, tween(320), label = "dunkel")
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = dunkel.value }.background(p.schleier))

            // Platz fuer das Alphabet auf der Seite, wo es gerade steht
            // Alphabet links: Apps weit nach rechts, damit sie dem Daumen am Alphabet nicht im Weg sind.
            // Die Apps gleiten weich hinueber, waehrend das Wasser unterwegs ist.
            val randStart by androidx.compose.animation.core.animateDpAsState(
                when { z.gespiegelt -> 36.dp + LINKS_EINRUECKEN; z.linksLiegend -> 28.dp + LINKS_EINRUECKEN; else -> 28.dp },
                tween(650, delayMillis = 150, easing = FastOutSlowInEasing), label = "randStart")
            val randEnde by androidx.compose.animation.core.animateDpAsState(
                if (z.gespiegelt) 24.dp else 44.dp, tween(650, delayMillis = 150, easing = FastOutSlowInEasing), label = "randEnde")
            // 3D: der Hintergrund steht still, alles vom Launcher schwebt darueber und geht mit der Neigung
            val schwebe = Modifier.graphicsLayer {
                translationX = Neigung.launcher.x.floatValue * 10.dp.toPx()
                translationY = Neigung.launcher.y.floatValue * 10.dp.toPx()
            }
            // 3D-Schatten: graue Kopie von allem, die beim Kippen zurueckbleibt -> der Inhalt hebt sich sichtbar ab
            val schattenKopie = Modifier.drawWithContent {
                val n = Neigung.launcher
                if (n.aktiv.value) {
                    val grau = if (p.dunkel) Color(0xFF6B6B6B).copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.16f)
                    val malen = androidx.compose.ui.graphics.Paint().apply {
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(grau, androidx.compose.ui.graphics.BlendMode.SrcIn)
                    }
                    val dx = -n.x.floatValue * 9.dp.toPx()
                    val dy = 3.dp.toPx() - n.y.floatValue * 9.dp.toPx()
                    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height), malen)
                    drawContext.canvas.translate(dx, dy)
                    this@drawWithContent.drawContent()
                    drawContext.canvas.restore()
                }
                drawContent()
            }
            val inhalt = Modifier.fillMaxSize().then(schwebe).then(schattenKopie).leistenAbstand().imePadding().padding(start = randStart, end = randEnde)
            // Jeder Buchstabe hat seine eigene Seite (wie Niagara): nur dessen Apps, auf Hoehe des Fingers
            // Atelier: kein eigener Seitenwechsel - nur die App-Liste im Baustein zeigt den Buchstaben (siehe Startseite)
            if (modus == Modus.LISTE && !listeImBaustein) {
                BuchstabenSeite(seite, elemente, seitenHoehe, starten, oeffnen, inhalt)
            }
            // Suche: eigene, schlanke Liste
            if (modus == Modus.SUCHE) {
                AppListe(
                    zeilen = zeilen, listState = listState, modus = modus,
                    suche = suche, onSuche = { suche = it },
                    starten = starten, oeffnen = oeffnen,
                    modifier = inhalt,
                    onZurueck = { zurStartseite() },
                )
            }
            if (modus == Modus.SEITE) {
                // eng gesetzt wie auf dem Sperrbildschirm: alles auf einer Seite, ohne Scrollen
                CompositionLocalProvider(LocalKompakt provides true) {
                    NebenSeite(inhalt, onZurueck = { zurStartseite() }, hochZurueck = false)   // schliessen nur ueber das Alphabet
                }
            }
            if ((modus == Modus.START || (listeImBaustein && modus == Modus.LISTE)) && Ebene.Baukasten !in ebenen) {
                // Atelier: sehr grosse System-Schrift sprengt die Bausteine -> hoechstens 130 % (Einstellungen bleiben voll)
                val dd = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides if (dd.fontScale > 1.3f) androidx.compose.ui.unit.Density(dd.density, 1.3f) else dd) {
                Startseite(
                    // beim Zurueckkommen aus einer App: Startseite gleitet mit leichter Federung herein
                    // Atelier: Raster ueber die ganze Breite (das Alphabet ist selbst ein Baustein darin)
                    modifier = Modifier.mitschneiden { WelleGlas.inhalt }.then((Modifier.fillMaxSize().then(schwebe).then(schattenKopie).leistenAbstand().imePadding()
                                    .padding(horizontal = START_RAND))).graphicsLayer {
                        val l = landen.value
                        val g = 0.94f + 0.06f * l
                        scaleX = g; scaleY = g
                        alpha = 0.35f + 0.65f * l
                    },
                    onSuche = { suche = ""; modus = Modus.SUCHE },
                    // oeffentlich: (noch) keine eigene Seite oben - die Benachrichtigungen kommen
                    // Atelier: von oben kommt keine eigene Seite mehr (die gibt es nur noch in der App-Liste per #)
                    onSeite = { akt.benachrichtigungen()},
                    starten = starten, oeffnen = oeffnen,
                    buchstabe = if (modus == Modus.LISTE) seite else null, elemente = elemente, seiteHash = abcSeite, privat = abcPrivat,
                )
                }
            }
            // Wellen-Schicht ueber der Uhr (fängt keine Berührungen ab - Uhr und Knöpfe bleiben bedienbar)
            // immer dieselbe Wellen-Schicht: auf der Startseite voll, in Liste/Suche weich abgedunkelt (kein Sprung)
            // Wellen-Alphabet bei anderen Hintergruenden (Sterne, Swiss, ...): dieselbe Wellen-Schicht, immer da -
            // nicht nur waehrend der Daumen am Alphabet ist (sonst verschwand sie beim Loslassen)
            // Alphabet-Baustein → ✎ → "Hinter dem Alphabet": Welle erzwingen bzw. abschalten
            val wellenSchicht = wellenOben || when (z.abcHinter) {
                "welle" -> true
                "aus", "kante", "linie", "kreise", "rosette" -> false
                else -> z.thema.alphabet == "welle" && !z.eigeneDaumenEbene && z.thema.farben != "glas" && z.thema.farben != "skizze" && z.thema.farben != "deko" && z.thema.farben != "sw"
            }
            // Milchglas-Rahmen: sie malen die Wellen selbst (unscharf) nach
            WelleGlasEbenen(z.welleGlas && wellenSchicht && z.abcHinter in setOf("", "welle"), z.welleGlasBlur.toFloat(), spiegelX)
            SideEffect {
                MilchFlaechen.wellenArt = if (wellenSchicht) (if (wellenOben) z.thema.hintergrund else "wellen") else null
                MilchFlaechen.spiegel = spiegelX
                MilchFlaechen.laeuft = ebenen.isEmpty() && modus != Modus.SUCHE      // wie die Wellen-Schicht selbst
            }
            if (wellenSchicht) {
                val wellenHell = animateFloatAsState(if (wieStart) 1f else 0.3f, tween(320), label = "wellen")
                Hintergrund(if (wellenOben) z.thema.hintergrund else "wellen", Modifier.fillMaxSize().ohneMilch().graphicsLayer {
                    alpha = wellenHell.value; scaleX = spiegelX
                }.graphicsLayer(wellenWechsel),
                            // auf der Startseite laufen die Wellen immer; in Liste/Suche/Ebenen (abgedunkelt) stehen sie
                            laeuft = ebenen.isEmpty() && modus != Modus.SUCHE, nurWellen = true)
            }
            // Wellen-Alphabet bei Hintergruenden ohne Wellen: Daumen-Wellen in der Wellenfarbe
            // Swiss: Daumen am Alphabet = Haarlinie + roter Block (rastet ein), ueber Uhr und Liste
            if (z.alphabetRosette && ebenen.isEmpty() && modus != Modus.SUCHE)
                GeldRosetteDaumen(Modifier.fillMaxSize().graphicsLayer { scaleX = spiegelX }, p.dunkel)
            else if (z.alphabetKante && ebenen.isEmpty() && modus != Modus.SUCHE)
                SwissKanteDaumen(Modifier.fillMaxSize().graphicsLayer { scaleX = spiegelX }, p.dunkel, p.akzent)
            else if ((z.abcHinter == "linie" || (z.abcHinter == "" && z.thema.alphabet == "swiss" && z.thema.farben != "sw")) && ebenen.isEmpty() && modus != Modus.SUCHE)
                Hintergrund("swiss", Modifier.fillMaxSize().graphicsLayer { scaleX = spiegelX }, laeuft = true, nurWellen = true)
            // selbst platzierter unsichtbarer Knopf (nur Startseite)
            if (modus == Modus.START && ebenen.isEmpty() && z.privatAn && z.geheimOrt == "eigen") EigenerGeheimKnopf()
            // unsichtbarer Baustein angetippt: winziger weisser Punkt ganz oben links
            if (modus == Modus.START && ebenen.isEmpty() && z.musterPunkt && (z.geheimOrt == "baustein" || hatKnopfBaustein)) KnopfPunkt()
            // Papier-Struktur (Test) ueber allem - faengt nichts ab
            if (Papier.an.value) {
                val kachel = remember { Papier.kachel.asImageBitmap() }
                val pinsel = remember(kachel) {
                    androidx.compose.ui.graphics.ShaderBrush(androidx.compose.ui.graphics.ImageShader(
                        kachel, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated))
                }
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawRect(pinsel) }
            }
            // Kreiswellen, wo der Daumen das Alphabet beruehrt (nicht abgedunkelt)
            TropfenSchicht(Modifier.fillMaxSize().graphicsLayer(), Color(wellenFarbe(z.thema.welle, p.dunkel)))
            // neue Nachricht: kleine Welle von oben in der App-Farbe (Form wie "Hinter dem Alphabet")
            if (z.benWelle && modus == Modus.START && ebenen.isEmpty()) BenachrichtigungsSchicht(
                Modifier.fillMaxSize(),
                when (z.abcHinter) { "kante" -> "kante"; "linie" -> "linie"; "kreise" -> "kreise"; "aus" -> "aus"
                    "" -> if (z.thema.alphabet == "swiss") "linie" else "welle"; else -> "welle" },
                Color(wellenFarbe(z.thema.welle, p.dunkel)), z.benWelleTiefe, z.benWelleDauer)
            // hinter dem Alphabet: im Takt der Musik (Alphabet-Baustein → ✎)
            MusikPulsQuelle(z.abcMusik && modus == Modus.START && ebenen.isEmpty(), z.abcMusikStaerke / 100f)
            MusikKreise()
            if (ebenen.isEmpty()) CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides richtung) {
                AlphabetStrich(
                    buchstaben = buchstaben,
                    wasser = { 1.2f * (1f - wechsel.value) },
                    // Atelier: Platz und Groesse kommen vom Alphabet-Baustein auf dem Startbildschirm
                    modifier = ((Modifier.align(if (seiteLinks) Alignment.TopEnd else Alignment.TopStart).alphabetPlatz()))
                        .systemGestureExclusion(),
                    // Alphabet-Code (in den Einstellungen aenderbar): Startbuchstabe -> Ziel, dort loslassen
                    onGeste = { start, ende ->
                        // diese Geste hat schon per Links-Wisch etwas geoeffnet?
                        val privatSchon = gesteStand[0]; val linksSchon = gesteStand[1]
                        gesteStand[0] = false; gesteStand[1] = false
                        if (privatSchon) return@AlphabetStrich
                        val bereit = z.geheimOrt == "aus" || System.currentTimeMillis() < z.musterBereitBis
                        z.musterBereitBis = 0L                     // jeder Versuch verbraucht das Antippen
                        z.musterPunkt = false
                        if (z.privatAn && bereit && codeEnde != "#" && buchstaben.getOrNull(ende) == codeEnde && buchstaben.getOrNull(start) in codeStart) {
                            zurStartseite()
                            if (listeImBaustein) { abcPrivat = true; modus = Modus.LISTE } else navi.auf(Ebene.Privat)
                        } else if (buchstaben.getOrNull(ende) == EINSTELL_PUNKT && elemente.none { it.buchstabe() == EINSTELL_PUNKT }) {
                            // ganz unten auf "•" losgelassen (und dort keine Apps): Einstellungen
                            zurStartseite(); navi.auf(Ebene.Einstellungen())
                        } else {
                            // eigener Alphabet-Code (alphabet.js) - genau einmal pro Geste
                            AlphabetSkript.wisch(akt, buchstaben.getOrNull(start) ?: "", buchstaben.getOrNull(ende) ?: "", linksSchon)
                        }
                    },
                    onLinks = { start ->
                        val bereit = z.geheimOrt == "aus" || System.currentTimeMillis() < z.musterBereitBis
                        if (z.privatAn && bereit && z.codeEnde == "#" && buchstaben.getOrNull(start) in codeStart) {
                            z.musterBereitBis = 0L
                            z.musterPunkt = false
                            gesteStand[0] = true
                            zurStartseite()
                            if (listeImBaustein) { abcPrivat = true; modus = Modus.LISTE } else navi.auf(Ebene.Privat)
                        } else {
                            gesteStand[1] = true
                            navi.alleZu(); suche = ""; tastatur?.hide()
                            if (!SeitenRaster.aufStart(akt)) { zurStartseite(); akt.benachrichtigungen() }
                            else if (listeImBaustein) { abcSeite = true; modus = Modus.LISTE }
                            else modus = Modus.SEITE
                        }
                    },
                    onBuchstabe = { b ->
                        if (b == "#") zurStartseite()
                        else {
                            abcSeite = false; abcPrivat = false
                            if (modus != Modus.LISTE) { suche = ""; tastatur?.hide(); modus = Modus.LISTE }
                            seite = b
                            val i = buchstaben.indexOf(b)
                            seitenHoehe = (i + 0.5f) / buchstaben.size
                        }
                    },
                )
            }
            // erster Start (Atelier): kurze Einfuehrung ueber allem
            if (!z.einfuehrungFertig) { Einfuehrung { z.einfuehrungSetzen(true); navi.alleZu() }; return@Box }
            // Tropf-Uhr: fallende Tropfen ueber allem auf dem Startbildschirm (nicht vom Uhr-Baustein abgeschnitten)
            if (TropfZiele.aktiv && ebenen.isEmpty()) TropfSchicht(Modifier.fillMaxSize())
            // nur die oberste Ebene wird gezeigt
            ebenen.lastOrNull()?.let { e ->
                key(ebenen.size, e) { EbeneAnzeigen(e) }
            }
        }
    }
}

@Composable
private fun EbeneAnzeigen(e: Ebene) {
    when (e) {
        is Ebene.Popup -> AppPopup(e.key)
        is Ebene.Umbenennen -> UmbenennenBlatt(e.key)
        is Ebene.IconWahl -> IconWahlBlatt(e.key)
        is Ebene.Buendeln -> BuendelnBlatt(e.key)
        is Ebene.Einstellungen -> EinstellungenSeite(e.seite)
        Ebene.Uhr -> UhrEinstellungen()
        Ebene.Privat -> PrivatBereich()
        Ebene.PrivatAuswahl -> PrivatAuswahlBlatt()
        Ebene.KnopfPlatzieren -> KnopfPlatzierenBlatt()
        Ebene.Baukasten -> RasterEditor("start", imStart = true)
    }
}

// ---------------- Startseite ----------------
private class Geste { var aktiv = false; var summe = 0f; var oben = true; var unten = true }

@Composable
fun Startseite(modifier: Modifier, onSuche: () -> Unit, onSeite: () -> Unit, starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit,
               buchstabe: String? = null, elemente: List<ListenZeile.App> = emptyList(), seiteHash: Boolean = false, privat: Boolean = false) {
    val langView = androidx.compose.ui.platform.LocalView.current
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val akt = LocalContext.current as MainActivity
    val scroll = rememberScrollState()
    val geste = remember { Geste() }
    val sucheAktuell by rememberUpdatedState(onSuche)
    val seiteJetzt by rememberUpdatedState(seiteHash)
    val seiteAktuell by rememberUpdatedState(onSeite)
    // Runterziehen fuer die Suche: die Seite folgt dem Finger (mit Gummiband) und federt
    // beim Loslassen mit dem Schwung des Fingers zurueck
    val zug = remember { mutableFloatStateOf(0f) }
    val feder = remember { Animatable(0f) }
    var zieht by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val maxZug = with(LocalDensity.current) { 120.dp.toPx() }
    val weit = with(LocalDensity.current) { 42.dp.toPx() }
    val kurz = with(LocalDensity.current) { 12.dp.toPx() }      // reicht mit Schwung
    fun gummi(roh: Float): Float {
        val r = kotlin.math.abs(roh)
        return kotlin.math.sign(roh) * maxZug * (1f - exp(-r / (maxZug * 1.6f)))
    }
    // nach unten wischen = Suche. Laeuft ueber Nested-Scroll,
    // damit lange Favoritenlisten trotzdem scrollen
    val verbindung = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (geste.aktiv) {
                    geste.summe += available.y
                    val s = geste.summe
                    if ((s > 0f && geste.oben) || (s < 0f && geste.unten)) { zieht = true; zug.floatValue = s }
                    else if (zieht) zug.floatValue = 0f        // zurueck ueber den Start hinaus in die gesperrte Richtung
                }
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (geste.aktiv) {
                    geste.aktiv = false
                    val s = geste.summe
                    val start = if (zieht) gummi(zug.floatValue) else 0f
                    zieht = false; zug.floatValue = 0f
                    if (geste.oben && (s > weit || (s > kurz && available.y > 1500f))) seiteAktuell()
                    else if (geste.unten && (s < -weit || (s < -kurz && available.y < -1500f))) sucheAktuell()
                    if (start != 0f) scope.launch {
                        feder.snapTo(start)
                        feder.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 380f), initialVelocity = available.y * 0.3f)
                    }
                }
                return Velocity.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }
    Column(
        modifier
            // Atelier: der Startbildschirm steht fest - kein Scrollen, kein Wischen nach oben/unten (Suche = Baustein)
            .then(Modifier)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = {
                langDruckGefuehl(langView)
                when {
                    seiteJetzt -> navi.auf(Ebene.Einstellungen("raster"))
                    else -> navi.auf(Ebene.Einstellungen("rasterHaupt"))
                }
            }) }
            .then(Modifier)
    ) {
        // Liquid Glass ohne gewaehltes Foto: einmaliger Hinweis (verschwindet, sobald ein Bild gewaehlt ist)
        Spacer(Modifier.height(0.dp))
        // Atelier: der Startbildschirm ist ein Raster aus Bausteinen - die Favoriten sind selbst einer davon
            val jetzt = remember(z.fortsetzen) { System.currentTimeMillis() }
            CompositionLocalProvider(LocalSucheOeffnen provides sucheAktuell) {
            BausteinRaster(
                SeitenRaster.laden(akt, "start"), jetzt, null,
                leerText = "Lange drücken → Bausteine hinzufügen",
                favoriten = { FavoritenBaustein(starten, oeffnen, buchstabe, elemente, seiteHash, privat) },
                spiegeln = z.gespiegelt,                    // im Liegen: alles seitenverkehrt, Alphabet links
            ) { offen ->
                // Benachrichtigungs-Baustein auf dem Startbildschirm: gleich die App oeffnen
                val paket = offen.removePrefix("n:")
                akt.packageManager.getLaunchIntentForPackage(paket)?.let { i -> try { akt.startActivity(i) } catch (e: Exception) { } }
            }
            }
            if (z.thema.farben == "glas") GlasFotoHinweis()
        Spacer(Modifier.height(8.dp))   // Atelier: kein leerer Platz zum Scrollen
    }
}

/** alle Apps untereinander (mit Buchstaben dazwischen) - das Alphabet scrollt nur an die richtige Stelle */
@Composable
private fun AlleAppsListe(buchstabe: String, elemente: List<ListenZeile.App>, starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit) {
    val p = LocalPalette.current
    // Zeilen: Buchstabe als kleine Ueberschrift, dann seine Apps (unter E auch die Launcher-Einstellungen)
    val zeilen = remember(elemente) {
        val l = ArrayList<Pair<String, ListenZeile.App?>>()      // (Buchstabe, App) - App null = Ueberschrift, "!" = Einstellungen
        elemente.groupBy { it.app.buchstabe }.forEach { (b, apps) ->
            l += b to null
            var einst = true
            apps.forEach { a ->
                if (!einst && a.app.name.lowercase() > "einstellungen") { l += "!" to null; einst = true }
                l += b to a
            }
            if (!einst) l += "!" to null
        }
        if (l.none { it.first == EINSTELL_PUNKT && it.second == null }) l += EINSTELL_PUNKT to null; l += "!" to null
        l
    }
    val start = remember(zeilen) { zeilen.withIndex().filter { it.value.second == null && it.value.first != "!" }.associate { it.value.first to it.index } }
    val liste = rememberLazyListState()
    LaunchedEffect(buchstabe, start) { start[buchstabe]?.let { liste.scrollToItem(it) } }
    LazyColumn(Modifier.fillMaxSize(), state = liste) {
        items(zeilen.size) { i ->
            val (b, a) = zeilen[i]
            when {
                a != null -> AppZeile(a.app, gross = true, starten = starten, oeffnen = oeffnen)
                b == "!" -> EinstellungenZeile()
                else -> Text(b, color = if (b == buchstabe) p.akzent else p.leise, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                             letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            }
        }
    }
}

/** Atelier: Punkt ganz unten im Alphabet = Einstellungen */
internal const val EINSTELL_PUNKT = "•"     // derselbe Punkt wie fuer Apps mit Zahl/Zeichen vorne - nur einer

/** Atelier: Favoriten als Baustein - so viele, wie hineinpassen (hoechstens die eingestellte Zahl) */
@Composable
private fun FavoritenBaustein(starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit,
                              buchstabe: String? = null, elemente: List<ListenZeile.App> = emptyList(), seiteHash: Boolean = false,
                              privat: Boolean = false) {
    if (privat) { PrivatInListe(starten, oeffnen); return }
    if (!Gefuehl.uebergaenge) { FavoritenInhalt(starten, oeffnen, buchstabe, elemente, seiteHash); return }
    // Atelier: beim Wechsel Favoriten <-> Buchstabe <-> Seite bei # kurz ueberblenden (Buchstabe zu Buchstabe bleibt sofort)
    val art = if (seiteHash) "seiteHash" else if (buchstabe != null) "abc" else "fav"
    val letzter = remember { arrayOf("A") }
    if (buchstabe != null) letzter[0] = buchstabe
    androidx.compose.animation.Crossfade(art, Modifier.fillMaxSize(), androidx.compose.animation.core.tween(170), label = "liste") { a ->
        when (a) {
            "seiteHash" -> FavoritenInhalt(starten, oeffnen, null, elemente, true)
            "abc" -> FavoritenInhalt(starten, oeffnen, buchstabe ?: letzter[0], elemente, false)
            else -> FavoritenInhalt(starten, oeffnen, null, elemente, false)
        }
    }
}

@Composable
private fun PrivatInListe(starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val apps = z.apps.filter { it.schluessel in z.versteckt }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("PRIVAT", color = p.akzent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
             modifier = Modifier.padding(bottom = 2.dp))
        if (apps.isEmpty()) Text("Noch keine Apps", color = p.leise, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        apps.forEach { a -> AppZeile(a, gross = true, starten = starten, oeffnen = oeffnen) }
        Text("Apps auswählen", color = p.akzent, fontSize = 14.sp, fontWeight = FontWeight.Medium,
             modifier = Modifier.padding(top = 10.dp).clip(rund(8.dp)).clickable { navi.auf(Ebene.PrivatAuswahl) }.padding(vertical = 6.dp))
    }
}

@Composable
private fun FavoritenInhalt(starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit,
                            buchstabe: String?, elemente: List<ListenZeile.App>, seiteHash: Boolean) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    // auf # nach links gezogen: die Seite oben (Bausteine, Benachrichtigungen ...) hier drin statt der Favoriten
    if (seiteHash) {
        CompositionLocalProvider(LocalKompakt provides true, LocalInAppListe provides true) {
            NebenSeite(Modifier.fillMaxSize(), onZurueck = { }, hochZurueck = false)
        }
        return
    }
    // Eigenschaft "Alle Apps, nur scrollen": eine lange Liste, das Alphabet springt nur an die Stelle
    if (buchstabe != null && LocalBOpt.current?.s("modus") == "liste") { AlleAppsListe(buchstabe, elemente, starten, oeffnen); return }
    // Finger auf dem Alphabet: hier erscheinen die Apps dieses Buchstabens (nur der Baustein wechselt, nicht die Seite)
    if (buchstabe != null) {
        val gruppe = remember(buchstabe, elemente) { elemente.filter { it.app.buchstabe == buchstabe } }
        val scroll = key(buchstabe) { rememberScrollState() }
        val eintraege: List<ListenZeile.App?> = remember(gruppe, buchstabe) {
            gruppe
        }
        // Nach Namenslaenge: der schmalste Name auf Hoehe des BUCHSTABENS im Alphabet (dort kommt die Welle am
        // weitesten herein). Die Reihenfolge wird einmal am Tag (oder wenn Apps dazukommen/wegfallen) ausgerechnet
        // und bleibt dann fest - auch beim Scrollen.
        val ctxR = LocalContext.current
        var listeObenY by remember { mutableFloatStateOf(-1f) }     // erste Zeile, ungescrollt (Bildschirm-y)
        var zeilePx by remember { mutableFloatStateOf(0f) }
        var sichtUntenY by remember { mutableFloatStateOf(-1f) }    // unterer Rand der sichtbaren Liste
        val breiten = nameBreiten(eintraege)
        val reihe = if (!z.abcLaenge) eintraege else AbcReihe.holen(ctxR, buchstabe, eintraege) {
            // nur rechnen, wenn die Lage bekannt ist (sonst: null = noch nicht merken)
            val abc = WellenReiz.abcListe
            val i = abc.indexOf(buchstabe)
            if (zeilePx <= 0f || listeObenY < 0f || sichtUntenY <= 0f || i < 0 || WellenReiz.abcUnten <= WellenReiz.abcOben) null
            else {
                val buchY = WellenReiz.abcOben + (i + 0.5f) / abc.size * (WellenReiz.abcUnten - WellenReiz.abcOben)
                val sichtbar = ((sichtUntenY - listeObenY) / zeilePx).toInt().coerceIn(1, eintraege.size)
                val anker = ((buchY - listeObenY) / zeilePx).toInt().coerceIn(0, sichtbar - 1)
                nachDaumenOrdnen(eintraege, anker, breiten, sichtbar)
            }
        }
        Column(Modifier.fillMaxSize()
                .onGloballyPositioned { k -> val u = k.positionInWindow().y + k.size.height; if (kotlin.math.abs(u - sichtUntenY) > 1f) sichtUntenY = u }
                .verticalScroll(scroll)) {
            Text(buchstabe, color = p.akzent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp,
                 modifier = Modifier.padding(bottom = 2.dp))
            Column(Modifier.onGloballyPositioned { k ->
                // Lage der ersten Zeile (ohne Scrollen) und Hoehe einer Zeile
                val y = k.positionInWindow().y + scroll.value
                if (kotlin.math.abs(y - listeObenY) > 1f) listeObenY = y
                if (reihe.isNotEmpty()) { val zh = k.size.height.toFloat() / reihe.size; if (kotlin.math.abs(zh - zeilePx) > 0.5f) zeilePx = zh }
            }) {
                reihe.forEach { zeile ->
                    if (zeile == null) EinstellungenZeile()
                    else AppZeile(zeile.app, gross = true, starten = starten, oeffnen = oeffnen)
                }
            }
            // Atelier: unter "•" (ganz unten) - nach den Apps mit Zahl/Zeichen vorne
            if (buchstabe == EINSTELL_PUNKT) EinstellungenZeile()
        }
        return
    }
    val sichtbareFav = z.favoriten.filter { it !in z.versteckt }.mapNotNull { z.app(it) }.take(z.favMax)
    if (sichtbareFav.isEmpty()) {
        Text(
            "Über die Buchstaben wischen.\nApp nach rechts wischen oder lange drücken → Pop-up.\n" +
                "Auf # nach links = " + ("Seite bei #") + ". Lange auf den Startbildschirm drücken = Bausteine.",
            color = p.leise, fontSize = 15.sp, lineHeight = 23.sp, style = schattenStil(),
        )
        return
    }
    BisVoll(Modifier.fillMaxSize()) {
        // Swiss: Kopf wie in einem Einsatz-Menue - Titel links, Anzahl rechts
        if (Stil.listeEinsatz) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 12.dp).background(p.akzent))
            Spacer(Modifier.width(8.dp))
            Text("FAVORITEN", color = p.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp, modifier = Modifier.weight(1f))
            Text("%02d".format(sichtbareFav.size), color = p.leise, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
        }
        val abstand = (LocalBOpt.current?.i("abstand", 0) ?: 0).dp
        sichtbareFav.forEachIndexed { i, app ->
            Box(Modifier.padding(vertical = abstand / 2)) {
                AppZeile(app, gross = true, starten = starten, oeffnen = oeffnen, nummer = i + 1, sofort = z.favSofort, glasKarte = true)
            }
        }
    }
}

/** Buchstabengroesse aus der Groesse des Alphabet-Bausteins (in sp, vor dem Groessen-Regler) */
@Composable
private fun abcSchrift(hoehePx: Int, breitePx: Int, n: Int, swiss: Boolean): Float {
    val d = LocalDensity.current.density
    val jeBuchstabe = hoehePx / d / maxOf(1, n)              // dp Hoehe pro Buchstabe
    val breite = breitePx / d
    // so gross, dass sich die Buchstaben nicht beruehren und nicht ueber den Rand gehen
    val s = minOf(jeBuchstabe * 0.62f, breite * 0.42f)
    return s.coerceIn(8f, 40f) * (if (swiss) 1.15f else 1f)
}

/** Atelier: die normale Uhr (mit Datum, Akku, Terminen ...) als Baustein - oben angelegt, zu viel wird abgeschnitten */
@Composable
internal fun UhrBlock(modifier: Modifier) {
    // 23.10.: die Uhr fuellt den Baustein immer ganz aus - hoeher gezogen = hoehere Ziffern, breiter = breitere
    // (auch gestreckt). Gemessen wird die Uhr in ihrer natuerlichen Breite, dann in beide Richtungen auf den
    // Baustein gezogen. Antippen usw. bleibt genau wie bisher (die Beruehrung wird mit umgerechnet).
    var fx by remember { mutableFloatStateOf(1f) }
    var fy by remember { mutableFloatStateOf(1f) }
    androidx.compose.ui.layout.Layout(
        content = { Box(Modifier.graphicsLayer {
            scaleX = fx; scaleY = fy
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
        }) { Kopf(oben = 0.dp) } },
        // Tropfen-Schrift: unten nicht abschneiden - dort haengen die Tropfen
        modifier = if (LocalZustand.current.thema.uhr.let { u -> u.schrift == Schriften.TROPFEN || u.schrift == Schriften.GLITCH || uhrWechsel(u) != "keine" }) modifier else modifier.clipToBounds(),
    ) { teile, c ->
        val w = c.maxWidth; val h = c.maxHeight
        // natuerliche Breite: so breit, wie die Ziffern (bzw. das Datum) wirklich sind
        // nie begrenzen: sonst bricht die Uhr bei schmalen Bausteinen in zwei Zeilen um
        val breit = teile[0].maxIntrinsicWidth(androidx.compose.ui.unit.Constraints.Infinity).coerceAtLeast(1)
        val pl = teile[0].measure(androidx.compose.ui.unit.Constraints.fixedWidth(breit))
        val sx = if (breit > 0) w.toFloat() / breit else 1f
        val sy = if (pl.height > 0) h.toFloat() / pl.height else 1f
        if (sx != fx) fx = sx
        if (sy != fy) fy = sy
        layout(w, h) { pl.place(0, 0) }
    }
}

/** Kopfzeile wie bei Niagara. Uhr = Wecker, Datum = Kalender, unsichtbares Feld neben der Uhr = Uhr anpassen */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Kopf(oben: androidx.compose.ui.unit.Dp = 36.dp) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val akt = LocalContext.current as MainActivity
    // als Baustein: eigene Eigenschaften (Datum/Akku/Sekunden/Ausrichtung) ueberschreiben das Design
    val bo = LocalBOpt.current
    val stil = z.thema.uhr.let { u -> if (bo == null) u else u.copy(
        datum = bo.b("datum", u.datum), akku = bo.b("akku", u.akku), sekunden = bo.b("sek", u.sekunden),
        mittig = when (bo.s("ausr")) { "m" -> true; "l" -> false; else -> u.mittig }) }
    var jetzt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var akku by remember { mutableIntStateOf(-1) }
    // Uhrzeit/Zeitzone von Hand geaendert, Datumswechsel: sofort neu
    var zeitAenderung by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: android.content.Intent?) { zeitAenderung++ }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_TIME_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_DATE_CHANGED)
        }
        try { androidx.core.content.ContextCompat.registerReceiver(akt, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED) } catch (e: Exception) { }
        onDispose { try { akt.unregisterReceiver(r) } catch (e: Exception) { } }
    }
    // z.fortsetzen: nach dem Entsperren sofort die richtige Zeit (der Zaehler schlaeft im Tiefschlaf mit)
    val sichtbar = Sichtbar.an
    LaunchedEffect(stil.sekunden, z.fortsetzen, zeitAenderung, sichtbar) {
        if (!sichtbar) return@LaunchedEffect          // andere App offen: Uhr nicht weiterzaehlen
        val bm = akt.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            jetzt = System.currentTimeMillis()
            akku = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            delay(if (stil.sekunden) 1000 - jetzt % 1000 else minOf(30_000L, 60_000 - jetzt % 60_000))
        }
    }
    val d = Date(jetzt)
    val farbe = bFarbe(uhrFarbe(stil.farbe, p))
    val dicke = bDicke(when (stil.dicke) { 0 -> FontWeight.Light; 1 -> FontWeight.Normal; else -> FontWeight.Bold }) ?: FontWeight.Normal
    // Swiss: Uhr eng gesetzt wie eine Plakat-Headline
    val schatten = schattenStil()
    val schattenUhr = schatten.let { if (Stil.typoSwiss) it.copy(letterSpacing = (-0.045f).em) else it }
        // eigene Uhr-Schrift (Uhr anpassen → Schrift der Uhr)
        .let { s -> if (stil.schrift.isNotEmpty()) s.copy(fontFamily = Schriften.familie(stil.schrift)) else s }
        // Gezeichnet: nichts mit Tinte ausmalen - die Ziffern nur als duenner Kuli-Umriss
        .let { if (Skizze.an) it.copy(drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(
            width = with(androidx.compose.ui.platform.LocalDensity.current) { 1.3.dp.toPx() },
            join = androidx.compose.ui.graphics.StrokeJoin.Round, cap = androidx.compose.ui.graphics.StrokeCap.Round)) else it }
    val mitte = if (stil.mittig) Alignment.CenterHorizontally else Alignment.Start
    val scharfMachen: () -> Unit = remember(z) { { z.musterBereitBis = System.currentTimeMillis() + 8_000; z.musterPunkt = true; z.musterTipp++ } }

    // links liegend ist alles eingerueckt - eine mittige Uhr soll aber in der Mitte bleiben
    val zurueckRuecken = (z.linksLiegend || z.gespiegelt) && stil.mittig
    Column(
        Modifier
            .then(if (zurueckRuecken) Modifier.layout { m, c ->
                val e = LINKS_EINRUECKEN.roundToPx()
                val pl = m.measure(c.copy(minWidth = c.maxWidth + e, maxWidth = c.maxWidth + e))
                layout(c.maxWidth, pl.height) { pl.place(-e, 0) }
            } else Modifier)
            .fillMaxWidth().padding(top = oben),
        horizontalAlignment = mitte,
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                // kleiner weisser Punkt links neben der Uhr (nur Anzeige, verschwindet nach 8 Sekunden).
                // Wird nur gezeichnet - verschiebt nichts am Layout.
                .drawBehind {
                    if (z.musterPunkt) drawCircle(Color.White, radius = 2.dp.toPx(), center = Offset(-12.dp.toPx(), size.height / 2f))
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stil.mittig) Spacer(Modifier.weight(1f))
            val uhrTipp = z.privatAn && z.geheimOrt == "uhr"
            val uhrMod = Modifier.combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                onDoubleClick = if (uhrTipp) ({ akt.uhr() }) else null) { if (uhrTipp) scharfMachen() else akt.uhr() }
                .then(if (Stil.typoGeld) Modifier.geldOval(p.akzent) else if (Glas.an) Modifier.glas(30.dp, rand = 14.dp, weich = 5.dp) else Modifier)
            if (stil.gestapelt) {
                Column(uhrMod, horizontalAlignment = mitte) {
                    UhrZiffern(SimpleDateFormat("HH", Sprache.locale).format(d), farbe, stil.groesse.sp, dicke, (stil.groesse * 0.92f).sp, schattenUhr)
                    UhrZiffern(SimpleDateFormat("mm", Sprache.locale).format(d), farbe, stil.groesse.sp, dicke, (stil.groesse * 0.92f).sp, schattenUhr)
                    if (stil.sekunden) Text(SimpleDateFormat("ss", Sprache.locale).format(d), color = farbe.copy(alpha = 0.7f),
                         fontSize = (stil.groesse / 3).sp, fontWeight = dicke, style = schattenUhr)
                }
            } else {
                UhrZiffern(SimpleDateFormat(if (stil.sekunden) "HH:mm:ss" else "HH:mm", Sprache.locale).format(d),
                           farbe, stil.groesse.sp, dicke, androidx.compose.ui.unit.TextUnit.Unspecified, schattenUhr, uhrMod)
            }
            // Unsichtbare Flaeche rechts neben der Uhr (Teil des Alphabet-Codes, siehe Einstellungen)
            Box(Modifier.weight(1f).fillMaxHeight().leiserTipp(z.privatAn && z.geheimOrt == "uhr", onTipp = scharfMachen))
            LaunchedEffect(z.musterTipp) {
                if (z.musterPunkt) { delay(8_000); z.musterPunkt = false }
            }
        }

        // Datum · Wetter · Akku
        val wetter = if (z.kopf.wetter) Wetter.daten else null
        val zeigeDatum = stil.datum
        val zeigeAkku = stil.akku && akku in 0..100
        if (zeigeDatum || wetter != null || zeigeAkku) {
            Row(
                Modifier.clip(rund(8.dp)).clickable { akt.kalenderOeffnen() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                var trenner = false
                if (zeigeDatum) {
                    if (Stil.typoGeld) Text(SimpleDateFormat("EEEE · d. MMMM yyyy", Sprache.locale).format(d).uppercase(),
                                         color = p.text.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                         letterSpacing = 2.4.sp, style = schatten)
                    else if (Stil.typoSwiss) Text(SimpleDateFormat("EEEE  dd.MM.yyyy", Sprache.locale).format(d).uppercase(),
                                         color = p.text.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                         letterSpacing = 1.8.sp, style = schatten)
                    else Text(SimpleDateFormat(if (Sprache.englisch) "EEEE, MMMM d" else "EEEE, d. MMMM", Sprache.locale).format(d), color = p.text.copy(alpha = 0.85f), fontSize = 16.sp, style = schatten)
                    trenner = true
                }
                if (wetter != null) {
                    if (trenner) Text("  ·  ", color = p.leise, fontSize = 16.sp, style = schatten)
                    WetterKurz(wetter, p.text.copy(alpha = 0.85f)); trenner = true
                }
                if (zeigeAkku) {
                    if (trenner) Text("  ·  ", color = p.leise, fontSize = 16.sp, style = schatten)
                    Text("$akku %", color = p.text.copy(alpha = 0.85f), fontSize = 16.sp, style = schatten)
                }
            }
        }

        // naechster Termin (heute oder in den naechsten 24 Stunden)
        if (z.kopf.termin) {
            val t = remember(Kalender.termine, jetzt / 60_000) {
                Kalender.termine.firstOrNull { t ->
                    if (t.ganztag) terminTag(t) == terminTag(t.copy(beginn = jetzt, ganztag = false))
                    else t.ende > jetzt && t.beginn < jetzt + 24 * 3_600_000L
                }
            }
            if (t != null) {
                Row(
                    Modifier.padding(top = 6.dp).clip(rund(8.dp)).clickable { Kalender.oeffnen(akt, t) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("termin", p.text.copy(alpha = 0.85f), 16.dp)
                    Spacer(Modifier.width(8.dp))
                    val wann = if (t.ganztag) "" else SimpleDateFormat("HH:mm", Sprache.locale).format(Date(t.beginn)) + "  "
                    Text(wann + t.titel, color = p.text.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1,
                         overflow = TextOverflow.Ellipsis, style = schatten)
                }
            }
        }

        // eigene Termine von heute (z. B. Geburtstage)
        val kinder = remember(jetzt / 3_600_000L, Geburtstage.stand) { Geburtstage.heute(akt, jetzt) }
        if (kinder.isNotEmpty()) {
            Text("Heute: " + kinder.joinToString(" · "), color = p.text.copy(alpha = 0.85f), fontSize = 15.sp, maxLines = 1,
                 overflow = TextOverflow.Ellipsis, style = schatten, modifier = Modifier.padding(top = 6.dp).padding(vertical = 2.dp))
        }

        val m = if (z.musikZeile) Musik.info else null
        if (m != null) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f, fill = false).clip(rund(8.dp)).clickable { Musik.oeffnen(akt) }.padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Symbol("note", p.text.copy(alpha = 0.85f), 16.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(m.titel + if (m.kuenstler.isNotBlank()) " – " + m.kuenstler else "", color = p.text.copy(alpha = 0.85f),
                         fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, style = schatten)
                }
                Spacer(Modifier.width(6.dp))
                MusikSteuerung(m, 18.dp)
            }
        }
    }
}

// ---------------- Liquid Glass ----------------
/** einmaliger Hinweis im Glas-Look: dein Hintergrundbild waehlen (laesst sich wegtippen) */
@Composable
private fun GlasFotoHinweis() {
    val z = LocalZustand.current
    val ctx = LocalContext.current
    if (z.thema.hintergrund != "glas") return
    val stand = GlasFoto.stand.intValue + z.eigenStand
    val prefs = remember { ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    var weg by remember { mutableStateOf(prefs.getBoolean("glasFotoHinweisWeg", false)) }
    val da = remember(stand) { GlasFoto.datei(ctx).exists() }
    if (da || weg) return
    Row(
        Modifier.padding(top = 14.dp).fillMaxWidth()
            .glas(22.dp, weich = 5.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable { glasFotoWaehlen(ctx) }
            .padding(start = 18.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Dein Hintergrundbild ins Glas", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Tippen und dasselbe Bild wie dein Handy-Hintergrund wählen", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
        SymbolKnopf("kreuz", Color.White.copy(alpha = 0.8f), 14.dp) {
            weg = true; prefs.edit().putBoolean("glasFotoHinweisWeg", true).apply()
        }
    }
}

// ---------------- Seite eines Buchstabens ----------------
/**
 * Nur die Apps eines Buchstabens, ungefaehr auf Hoehe des Buchstabens im Alphabet (wie Niagara).
 * Beim Wechsel wird nur diese kleine Gruppe neu gebaut - keine lange Liste, die scrollen muss.
 */
@Composable
fun BuchstabenSeite(
    b: String, elemente: List<ListenZeile.App>, hoehe: Float,
    starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit, modifier: Modifier,
) {
    val gruppe = remember(b, elemente) { elemente.filter { it.app.buchstabe == b } }
    val ziel by animateFloatAsState(hoehe, tween(110), label = "seite")
    val dichte = LocalDensity.current
    val oben = with(dichte) { 130.dp.toPx() }          // Alphabet beginnt 130 dp unter dem oberen Rand
    val scroll = key(b) { rememberScrollState() }       // jede Buchstabenseite startet oben
    val unten = with(dichte) { 24.dp.toPx() }
    val nachLaenge = LocalZustand.current.abcLaenge
    // Hoehe einer Zeile und des Platzes (aus dem Layout) - damit wir wissen, welche Zeile auf Hoehe des Daumens liegt
    var zeilePx by remember { mutableFloatStateOf(0f) }
    var platzPx by remember { mutableIntStateOf(0) }
    // Eintraege: null = die Launcher-Einstellungen (unter "E")
    val eintraege: List<ListenZeile.App?> = remember(gruppe, b) {
        if (b != "E") gruppe else {
            val i = gruppe.indexOfFirst { it.app.name.lowercase() > "einstellungen" }.let { if (it < 0) gruppe.size else it }
            gruppe.subList(0, i) + listOf(null) + gruppe.subList(i, gruppe.size)
        }
    }
    val breitenSeite = nameBreiten(eintraege)
    val reihe: List<ListenZeile.App?> = if (!nachLaenge || eintraege.size < 3) eintraege else {
        val n = eintraege.size
        // Zeile auf Hoehe des Daumens (gleiche Rechnung wie beim Platzieren unten, mit dem Ziel statt der Animation)
        val anker = if (zeilePx <= 0f || platzPx <= 0) n / 2 else {
            val gesamt = zeilePx * n
            val mitte = oben + hoehe * (platzPx - oben - unten)
            val y = (mitte - gesamt / 2f).coerceIn(0f, maxOf(0f, platzPx - gesamt))
            ((mitte - y) / zeilePx).toInt().coerceIn(0, n - 1)
        }
        nachDaumenOrdnen(eintraege, anker, breitenSeite)
    }
    Column(
        modifier
            .layout { m, c ->
                val pl = m.measure(c.copy(minHeight = 0))
                if (platzPx != c.maxHeight) platzPx = c.maxHeight
                if (reihe.isNotEmpty()) { val zh = pl.height.toFloat() / reihe.size; if (kotlin.math.abs(zh - zeilePx) > 0.5f) zeilePx = zh }
                val frei = (c.maxHeight - pl.height).coerceAtLeast(0)
                val mitte = oben + ziel * (c.maxHeight - oben - unten)
                val y = (mitte - pl.height / 2f).roundToInt().coerceIn(0, frei)
                layout(c.maxWidth, c.maxHeight) { pl.place(0, y) }
            }
            .verticalScroll(scroll)
    ) {
        // unter "E": die Launcher-Einstellungen (null) - alphabetisch oder nach Namenslaenge mit eingeordnet
        reihe.forEach { zeile ->
            if (zeile == null) EinstellungenZeile()
            else AppZeile(zeile.app, gross = true, starten = starten, oeffnen = oeffnen)
        }
    }
}

// ---------------- Suche ----------------
@Composable
fun AppListe(
    zeilen: List<ListenZeile>,
    listState: LazyListState,
    modus: Modus,
    suche: String,
    onSuche: (String) -> Unit,
    starten: (AppEintrag) -> Unit,
    oeffnen: (String) -> Unit,
    modifier: Modifier,
    onZurueck: () -> Unit = {},
) {
    val p = LocalPalette.current
    // Nach unten wischen schliesst die Suche wieder. Der Inhalt folgt dabei dem Finger
    // (Gummiband) und federt zurueck, wenn man es sich anders ueberlegt.
    val zieh = remember { mutableFloatStateOf(0f) }
    val feder = remember { Animatable(0f) }
    var zieht by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val zurueck by rememberUpdatedState(onZurueck)
    val dichte = LocalDensity.current
    val maxZug = with(dichte) { 120.dp.toPx() }
    val schwelle = with(dichte) { 24.dp.toPx() }
    val genug = with(dichte) { 90.dp.toPx() }
    fun gummi(weg: Float) = maxZug * (1f - exp(-weg.coerceAtLeast(0f) / (maxZug * 1.6f)))
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val tempo = VelocityTracker()
                var weg = 0f
                var basis = Float.NaN
                var aktiv = false
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    weg += c.position.y - c.previousPosition.y
                    tempo.addPosition(c.uptimeMillis, Offset(0f, weg))
                    // nur wenn die Trefferliste schon ganz oben ist (oder leer), sonst normal scrollen
                    if (basis.isNaN() && !listState.canScrollBackward) basis = weg
                    if (!aktiv && !basis.isNaN() && weg - basis > schwelle) aktiv = true
                    if (aktiv) { zieht = true; zieh.floatValue = gummi(weg - basis - schwelle); c.consume() }
                }
                if (aktiv) {
                    val v = tempo.calculateVelocity().y
                    val start = zieh.floatValue
                    zieht = false
                    if (weg - basis > genug || v > 1200f) zurueck()
                    else scope.launch {
                        feder.snapTo(start)
                        feder.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 380f), initialVelocity = v * 0.3f)
                    }
                }
            }
        }
    ) {
    Column(Modifier.fillMaxSize().graphicsLayer { translationY = if (zieht) zieh.floatValue else feder.value }) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        ) {
            items(zeilen, key = { it.key }, contentType = { when (it) { is ListenZeile.Kopf -> 0; ListenZeile.Schnell -> 2; else -> 1 } }) { z ->
                when (z) {
                    is ListenZeile.Kopf -> Text(
                        z.buchstabe, color = p.leise, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                    )
                    is ListenZeile.App -> AppZeile(z.app, gross = false, starten = starten, oeffnen = oeffnen)
                    ListenZeile.Schnell -> SchnellListe()
                }
            }
        }
        if (modus == Modus.SUCHE) {
            val fokus = remember { FocusRequester() }
            val tastatur = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) { try { fokus.requestFocus() } catch (e: Exception) { }; tastatur?.show() }
            // Atelier (einstellbar): passt nur noch eine App, startet sie nach einer kurzen Tipp-Pause von selbst
            LaunchedEffect(suche, zeilen) {
                if (Gefuehl.sucheSofort && suche.trim().length >= 2) {
                    val apps = zeilen.filterIsInstance<ListenZeile.App>()
                    if (apps.size == 1) { kotlinx.coroutines.delay(450); starten(apps[0].app) }
                }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    .then(if (Glas.an) Modifier.glas(24.dp).padding(horizontal = 18.dp, vertical = 10.dp)
                          else if (Skizze.an) Modifier.skizzeRahmen(22.dp).padding(horizontal = 18.dp, vertical = 10.dp)
                          else if (Deko.an) Modifier.dekoRahmen(14.dp).padding(horizontal = 18.dp, vertical = 10.dp) else Modifier),
                contentAlignment = Alignment.CenterStart) {
                if (suche.isEmpty()) Text("Apps suchen", color = p.leise, fontSize = 20.sp)
                BasicTextField(
                    value = suche,
                    onValueChange = onSuche,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = p.text, fontSize = 20.sp),
                    cursorBrush = SolidColor(p.akzent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        when (val erste = zeilen.firstOrNull { it !is ListenZeile.Kopf }) {
                            is ListenZeile.App -> starten(erste.app)
                            else -> {}
                        }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(fokus),
                )
            }
        }
    }
}
}

/** Nach rechts wischen = Pop-up (wie Niagara) */
private fun Modifier.wischNachRechts(key: String, aktion: () -> Unit) = pointerInput(key) {
    var summe = 0f
    detectHorizontalDragGestures(
        onDragStart = { summe = 0f },
        onDragEnd = { if (summe > 80f) aktion() },
    ) { change, d -> summe += d; if (summe > 0f) change.consume() }
}

/** Eine App: Icon + Name. Schlank gehalten, damit die Liste fluessig bleibt */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppZeile(app: AppEintrag, gross: Boolean, starten: (AppEintrag) -> Unit, oeffnen: (String) -> Unit, nummer: Int? = null,
             sofort: Boolean = false, glasKarte: Boolean = false) {
    val ohneIcons = LocalZustand.current.thema.icons == "keine" || LocalBOpt.current?.b("icons", true) == false
    val swiss = Stil.listeEinsatz
    val p = LocalPalette.current
    // wo die Zeile auf dem Bildschirm liegt -> von hier aus geht die App beim Oeffnen auf
    val rahmen = remember { IntArray(4) }
    // Liquid Glass: beim Antippen ploppt ein Glastropfen unter der Zeile auf
    val druck = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val gedrueckt = druck.collectIsPressedAsState()
    val glasDeck = androidx.compose.animation.core.animateFloatAsState(
        if (gedrueckt.value) 1f else 0f, androidx.compose.animation.core.tween(if (gedrueckt.value) 70 else 280), label = "glas")
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { k ->
                val o = k.positionInWindow()
                rahmen[0] = o.x.toInt(); rahmen[1] = o.y.toInt(); rahmen[2] = k.size.width; rahmen[3] = k.size.height
            }
            .then(if (sofort) Modifier.pointerInput(app.schluessel) {
                // Schnell-Start (Favoriten): nicht erst beim Loslassen. Bleibt der Finger 25 ms ruhig
                // (oder geht vorher hoch), startet die App sofort. Bewegt er sich vorher, ist es Wischen -
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val ruhig = 6.dp.toPx()
                    // null = 25 ms ruhig gehalten, true = vorher losgelassen, false = gewischt
                    var ergebnis: Boolean? = null
                    withTimeoutOrNull(25) {
                        while (ergebnis == null) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            ergebnis = when {
                                c == null -> false
                                !c.pressed -> true
                                (c.position - down.position).getDistance() > ruhig || c.isConsumed -> false
                                else -> null
                            }
                        }
                    }
                    if (ergebnis != false) { StartRahmen.merken(rahmen); starten(app) }
                }
            } else Modifier.combinedClickable(
                interactionSource = druck,
                indication = if (Glas.an || Skizze.an || Deko.an) null else androidx.compose.foundation.LocalIndication.current,
                onClick = { StartRahmen.merken(rahmen); starten(app) }, onLongClick = { oeffnen(app.schluessel) }))
            // Favoriten im Glas-Look: jede Zeile eine Glaskapsel (ragt seitlich etwas heraus, verschiebt nichts);
            // sonst ploppt das Glas nur beim Antippen auf
            .then(if (Glas.an && glasKarte) Modifier.glas(24.dp, rand = 10.dp, randY = (-3).dp, weich = 5.dp, schatten = 8.dp,
                                                          deckung = { 0.85f + 0.15f * glasDeck.value })
                  else if (Glas.an) Modifier.glas(20.dp, rand = 4.dp, deckung = { glasDeck.value })
                  // Gezeichnet: beim Antippen wird die Zeile schnell umrahmt
                  else if (Skizze.an) Modifier.skizzeRahmen(20.dp, aus = 4.dp, deckung = { glasDeck.value })
                  // Art Deco: beim Antippen leuchtet ein Goldrahmen um die Zeile auf
                  else if (Deko.an) Modifier.dekoRahmen(12.dp, deckung = { glasDeck.value }) else Modifier)
            .wischNachRechts(app.schluessel) { oeffnen(app.schluessel) }
            // Swiss: Haarlinie unter jeder grossen Zeile, wie eine Einsatzliste
            .then(if (swiss && gross) Modifier.drawBehind {
                drawLine(p.linie, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            } else Modifier)
            .padding(vertical = if (gross) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (swiss && nummer != null) {
            Text("%02d".format(nummer), color = p.akzent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                 letterSpacing = 1.sp, modifier = Modifier.width(30.dp))
        }
        if (!ohneIcons) {
            AppIcon(app.schluessel, if (gross) 38.dp else 32.dp)
            Spacer(Modifier.width(18.dp))
        }
        Text(
            if (swiss) app.name.uppercase() else app.name,
            fontSize = if (swiss) (if (gross) 20.sp else 17.sp) else if (gross) 23.sp else 19.sp,
            fontWeight = if (swiss) FontWeight.SemiBold else null,
            letterSpacing = if (swiss) 0.6.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = if (gross) schattenStil() else LocalTextStyle.current,
        )
    }
}

/** Eintrag "Einstellungen" unter E - sieht aus wie eine App */
@Composable
private fun EinstellungenZeile() {
    val navi = LocalNavi.current
    val p = LocalPalette.current
    val ohneIcons = LocalZustand.current.thema.icons == "keine"
    Row(
        Modifier.fillMaxWidth().clickable { navi.auf(Ebene.Einstellungen()) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!ohneIcons) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(p.akzent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Symbol("zahnrad", p.text, 22.dp)
            }
            Spacer(Modifier.width(18.dp))
        }
        Text("Einstellungen", fontSize = 23.sp, maxLines = 1, style = schattenStil())
        Text("  Launcher", color = p.text.copy(alpha = 0.55f), fontSize = 15.sp, maxLines = 1, style = schattenStil())
    }
}

// ---------------- Alphabet-Strich mit Welle ----------------
@Composable
fun AlphabetStrich(
    buchstaben: List<String>,
    modifier: Modifier,
    onGeste: (start: Int, ende: Int) -> Unit = { _, _ -> },
    onLinks: (start: Int) -> Unit = {},
    wasser: () -> Float = { 1.2f },          // Wasserstand fuer den Seitenwechsel: Buchstaben darunter sind sichtbar
    onBuchstabe: (String) -> Unit,
) {
    val p = LocalPalette.current
    val gr = LocalZustand.current.alphabetGroesse / 100f          // eingestellte Alphabet-Groesse
    // Atelier: die Buchstaben wachsen mit dem Baustein (Hoehe je Buchstabe und Breite), 100 % = wie bisher
    var breitePx by remember { mutableIntStateOf(1) }
    // Atelier: Schriftart / Staerke aus den Eigenschaften des Alphabet-Bausteins
    val abcOpt = (SeitenRaster.laden(LocalContext.current, "start").firstOrNull { it.typ == "alphabet" }?.opt
                     ?.takeIf { it.isNotBlank() }?.let { remember(it) { BOpt.aus(it) } })
    val abcFamilie = abcOpt?.s("schrift")?.takeIf { it.isNotEmpty() }?.let { Schriften.familie(it) }
    val abcDicke = when (abcOpt?.s("dicke")) { "0" -> FontWeight.Light; "1" -> FontWeight.Normal; "2" -> FontWeight.Bold; else -> null }
    var hoehe by remember { mutableIntStateOf(1) }
    // Fingerposition als Buchstaben-Index - nur beim Zeichnen gelesen, also kein Neuaufbau je Bewegung
    val finger = remember { mutableFloatStateOf(0f) }
    // wie weit der Daumen links neben dem Alphabet ist (px) - der Bogen geht so weit mit, dass man es immer sieht
    val zug = remember { mutableFloatStateOf(0f) }
    val daumen = with(LocalDensity.current) { 64.dp.toPx() }      // ungefaehr eine Daumenbreite
    val halbeSpalte = with(LocalDensity.current) { 22.dp.toPx() }  // halbe Breite der Buchstaben-Spalte
    // Atelier: ganz aussen reicht die Beruehr-Flaeche bis an den Bildschirmrand (Buchstaben bleiben im Baustein)
    val (randAnfang, randEnde) = alphabetAussen()
    val randAnfangPx by rememberUpdatedState(with(LocalDensity.current) { randAnfang.toPx() })
    val randEndePx by rememberUpdatedState(with(LocalDensity.current) { randEnde.toPx() })
    val daumenAnlauf = with(LocalDensity.current) { 32.dp.toPx() }
    val staerke = remember { Animatable(0f) }
    var beruehrt by remember { mutableStateOf(false) }
    // Ein-/Ausblenden haengt NUR an "beruehrt" -> kann nicht haengen bleiben, auch bei ganz kurzem Tippen
    LaunchedEffect(beruehrt) { staerke.animateTo(if (beruehrt) 1f else 0f, tween(if (beruehrt) 90 else 250)) }
    val n = buchstaben.size
    val aktiv by remember(n) {
        derivedStateOf { if (beruehrt && n > 0) finger.floatValue.toInt().coerceIn(0, n - 1) else -1 }
    }
    val haptik = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val dichte = LocalDensity.current
    val welle = with(dichte) { 56.dp.toPx() }
    val blasenAbstand = with(dichte) { 120.dp.toPx() }
    val blasenHalb = with(dichte) { 30.dp.toPx() }
    val melden by rememberUpdatedState(onBuchstabe)
    val rtlJetzt = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val rtl by rememberUpdatedState(rtlJetzt)
    val lage = remember { FloatArray(3) }
    val daumenAn by rememberUpdatedState(LocalZustand.current.wellenDaumen)
    // Swiss-Kante: entweder Kante ODER Wellen am Daumen - nie beides
    val kanteAn by rememberUpdatedState(LocalZustand.current.eigeneDaumenEbene)
    val tropfenAn by rememberUpdatedState(LocalZustand.current.wellenTropfen)           // Lage des Strichs auf dem Bildschirm (x, y, Breite) fuer die Wellen
    val gesteMelden by rememberUpdatedState(onGeste)
    val linksMelden by rememberUpdatedState(onLinks)
    val linksDp = LocalZustand.current.linksWeg
    val linksSchwelle by rememberUpdatedState(with(dichte) { linksDp.dp.toPx() })
    val schatten = schattenStil()
    // Mitziehen: geht der Daumen ueber das obere Ende hinaus, wandert die ganze Leiste mit nach oben;
    // geht er dann unter das untere Ende, zieht er sie wieder mit nach unten (hoechstens bis zur Ruhelage).
    // Beim Loslassen gleitet sie zurueck.
    val verschub = remember { mutableFloatStateOf(0f) }        // px, <= 0 (nach oben)
    var zurueck by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val obenFrei = with(dichte) { 40.dp.toPx() }               // Statusleiste frei lassen
    val mitziehen by rememberUpdatedState(LocalZustand.current.alphabetMitziehen)
    val klick by rememberUpdatedState(LocalZustand.current.alphabetKlick)
    val tonBei by rememberUpdatedState(LocalZustand.current.alphabetTonBei)
    val tonArt by rememberUpdatedState(LocalZustand.current.alphabetTonArt)
    val audioCtx = LocalContext.current
    val audio = remember { audioCtx.getSystemService(android.media.AudioManager::class.java) }
    LaunchedEffect(Unit) { Klack.vorbereiten(audioCtx.applicationContext) }

    Box(
        modifier
            .onSizeChanged { hoehe = maxOf(1, it.height); breitePx = maxOf(1, it.width) }
            .onGloballyPositioned { k -> val o = k.positionInRoot(); lage[0] = o.x; lage[1] = o.y; lage[2] = k.size.width.toFloat()
                WellenReiz.abcX = o.x + k.size.width / 2f; WellenReiz.abcOben = o.y; WellenReiz.abcUnten = o.y + k.size.height
                if (WellenReiz.abcListe != buchstaben) WellenReiz.abcListe = buchstaben }
            .pointerInput(buchstaben) {
                awaitEachGesture {
                    var zuletzt = -1
                    var start = -1
                    var linksAn = false
                    fun setze(yRoh: Float, x: Float) {
                        // yRoh: Finger relativ zur Leiste in Ruhelage. Erst mitziehen, dann relativ zur verschobenen Leiste
                        val ganzOben = -(lage[1] - obenFrei).coerceAtLeast(0f)
                        val alt = verschub.floatValue
                        val v = when {
                            !mitziehen -> 0f
                            yRoh < alt -> yRoh.coerceAtLeast(ganzOben)                       // ueber dem oberen Ende -> mit nach oben
                            yRoh > alt + hoehe && alt < 0f -> (yRoh - hoehe).coerceAtMost(0f)  // unter dem unteren Ende -> mit nach unten
                            else -> alt
                        }
                        if (v != alt) verschub.floatValue = v
                        val y = yRoh - v
                        val yAbs = lage[1] + yRoh          // Finger auf dem Bildschirm
                        // Abstand links vom Alphabet; sobald der Daumen nach links zieht, kommt eine Daumenbreite dazu,
                        // damit die Buchstaben links NEBEN dem Daumen stehen und nicht darunter
                        // gespiegelt: Alphabet steht links, der Daumen zieht nach rechts
                        // gemessen ab der Buchstaben-Spalte (Mitte des Bausteins), nicht ab dem Rand des Bausteins -
                        // sonst musste man bei einem breiten Alphabet-Baustein erst ganz aus ihm heraus, bevor etwas passiert
                        val innenBreite = size.width - randAnfangPx - randEndePx
                        val mitte = (if (rtl) randEndePx else randAnfangPx) + innenBreite / 2f
                        val halb = minOf(innenBreite / 2f, halbeSpalte)
                        val links = (if (rtl) x - (mitte + halb) else (mitte - halb) - x).coerceAtLeast(0f)
                        WellenReiz.fingerY = yAbs
                        if (daumenAn || kanteAn) { WellenReiz.daumenY = yAbs; WellenReiz.zug = links
                                        if (kanteAn) WellenReiz.kanteDa = true else WellenReiz.daumenDa = true
                                        if (!WellenReiz.beruehrt.value) WellenReiz.beruehrt.value = true }
                        zug.floatValue = links + daumen * (links / daumenAnlauf).coerceAtMost(1f)
                        if (n == 0) return
                        val f = (y / hoehe * n).coerceIn(0f, n - 0.001f)
                        finger.floatValue = f
                        val i = f.toInt()
                        if (i != zuletzt) {
                            if (tropfenAn) WellenReiz.tropfen(lage[0] + lage[2] / 2f, yAbs, erzwingen = zuletzt < 0)
                            zuletzt = i
                            linksAn = false
                            haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // Atelier: eigener Klack ueber die Medien-Lautstaerke (der Systemton war oft nicht zu hoeren)
                            // Atelier: bei ausgewaehlten Buchstaben ein anderer Ton (auch wenn das Klack aus ist)
                            if (buchstaben[i] in tonBei) Klack.spielen(audioCtx, tonArt)
                            else if (klick) Klack.spielen(audioCtx)
                            melden(buchstaben[i])
                        }
                        if (start < 0) start = i
                        // auf "#" ploetzlich nach links -> sofort ausloesen (nicht erst beim Loslassen)
                        if (buchstaben[i] == "#") {
                            if (!linksAn && links > linksSchwelle) {
                                linksAn = true
                                haptik.performHapticFeedback(HapticFeedbackType.LongPress)
                                linksMelden(start)
                            } else if (linksAn && links < linksSchwelle * 0.4f) {
                                // wieder nach rechts zurueck -> Startseite
                                linksAn = false
                                haptik.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                melden("#")
                            }
                        }
                    }
                    val down = awaitFirstDown()
                    down.consume()
                    zurueck?.cancel()
                    try {
                        beruehrt = true
                        setze(down.position.y, down.position.x)
                        while (true) {
                            val ev = awaitPointerEvent()
                            // genau DIESEN Finger verfolgen; ist er weg (oder Android bricht ab) -> Ende
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed || ev.changes.none { it.pressed }) break
                            c.consume()
                            setze(c.position.y, c.position.x)
                        }
                    } finally {
                        // laeuft immer - auch bei ganz kurzem Antippen oder wenn Android die Beruehrung abbricht
                        beruehrt = false
                        WellenReiz.daumenDa = false
                        WellenReiz.kanteDa = false
                        WellenReiz.beruehrt.value = false
                        WellenReiz.zug = 0f
                        gesteMelden(start, zuletzt)
                        // Leiste gleitet zurueck an ihren Platz
                        if (verschub.floatValue != 0f) zurueck = scope.launch {
                            androidx.compose.animation.core.animate(verschub.floatValue, 0f, animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f)) { w, _ ->
                                verschub.floatValue = w
                            }
                        }
                    }
                }
            }
            .offset { IntOffset(0, verschub.floatValue.roundToInt()) }
            .then(if (Glas.an) Modifier.glas(18.dp, staerke = 0.6f) else Modifier)
    ) {
        Column(Modifier.fillMaxSize().padding(start = randAnfang, end = randEnde)) {
            val swiss = Stil.alphabetSwiss
            buchstaben.forEachIndexed { i, b ->
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    // Hervorhebung und Welle nur beim Zeichnen -> kein Neuaufbau, wenn der Finger wandert
                    Text(
                        b,
                        color = p.text,
                        fontSize = (abcSchrift(hoehe, breitePx, buchstaben.size, swiss)).times(gr).sp,
                        fontWeight = abcDicke ?: if (swiss) FontWeight.SemiBold else FontWeight.Medium,
                        fontFamily = abcFamilie,
                        style = if (swiss) schatten.copy(letterSpacing = 0.sp) else schatten,
                        modifier = Modifier.graphicsLayer {
                            if (swiss) {
                                // Swiss: die Leiste bewegt sich nicht - jeder Buchstabe bleibt lesbar an seinem Platz.
                                // Was gewaehlt ist, zeigt die Index-Karte links neben dem Daumen.
                                val gewaehlt = beruehrt && finger.floatValue.toInt() == i
                                alpha = if (gewaehlt || SW.an) 1f else 0.92f - 0.25f * staerke.value
                                return@graphicsLayer
                            }
                            val d = i + 0.5f - finger.floatValue
                            // zieht der Daumen nach links, wird der Bogen tiefer und breiter
                            val z = zug.floatValue
                            val breite = 2.4f + (z / welle).coerceAtMost(5f) * 1.2f
                            val nah = staerke.value * exp(-(d * d) / (2f * breite * breite))
                            translationX = -(welle + z) * nah * (if (rtlJetzt) -1f else 1f)
                            scaleX = 1f + 1.1f * nah
                            scaleY = 1f + 1.1f * nah
                            // in Ruhe leicht sichtbar, beim Anfassen voll da
                            val ziel = if (beruehrt && finger.floatValue.toInt() == i) 1f else 0.55f
                            alpha = 0.22f + (ziel - 0.22f) * staerke.value
                            // beim Seitenwechsel: verschwinden mit sinkendem / erscheinen mit steigendem Wasser
                            val stand = wasser()
                            if (stand < 1.15f) {
                                val vonUnten = 1f - (i + 0.5f) / n
                                alpha *= ((stand - vonUnten) / 0.12f + 0.5f).coerceIn(0f, 1f)
                            }
                        }.then(if (swiss) Modifier.drawBehind {
                            // gewaehlter Buchstabe an der Spitze: rotes Quadrat
                            if (beruehrt && finger.floatValue.toInt() == i && !SW.an) {
                                val k = 22.dp.toPx()
                                drawRect(p.akzent, Offset((size.width - k) / 2f, (size.height - k) / 2f), androidx.compose.ui.geometry.Size(k, k))
                            }
                        } else Modifier),
                    )
                }
            }
        }
        // Grosser Buchstabe links neben dem Finger (eigener Baustein: nur er wird beim Buchstabenwechsel neu gebaut)
        GrosserBuchstabe({ aktiv }, buchstaben, finger, staerke, n, { hoehe }, blasenAbstand, blasenHalb, zug)
    }
}

/**
 * Swiss: Index-Karte links neben dem Daumen - wie eine Auswahlliste in einem Einsatz-Menue.
 * Der gewaehlte Buchstabe gross im roten Feld, darueber und darunter die Nachbarn klein.
 * Schwarze Karte mit Haarlinie, damit sie auf jedem Hintergrund lesbar ist.
 */
@Composable
private fun SwissIndexKarte(buchstaben: List<String>, aktiv: Int) {
    val p = LocalPalette.current
    Column(
        Modifier.width(64.dp).background(p.grund).border(1.dp, p.linie),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (o in -2..2) {
            val b = buchstaben.getOrNull(aktiv + o)
            if (o == 0) Box(Modifier.fillMaxWidth().height(64.dp).background(p.akzent), contentAlignment = Alignment.Center) {
                Text(b ?: "", color = p.aufAkzent, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold,
                     style = LocalTextStyle.current.copy(shadow = null, letterSpacing = 0.sp))
            } else Box(Modifier.fillMaxWidth().height(26.dp), contentAlignment = Alignment.Center) {
                Text(b ?: "", color = p.text.copy(alpha = if (kotlin.math.abs(o) == 1) 0.75f else 0.4f),
                     fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                     style = LocalTextStyle.current.copy(shadow = null, letterSpacing = 0.sp))
            }
        }
    }
}

@Composable
private fun GrosserBuchstabe(
    aktivLesen: () -> Int, buchstaben: List<String>, finger: MutableFloatState,
    staerke: Animatable<Float, AnimationVector1D>, n: Int, hoeheLesen: () -> Int,
    blasenAbstand: Float, blasenHalb: Float, zug: MutableFloatState,
) {
    val p = LocalPalette.current
    val aktiv = aktivLesen()
    if (aktiv < 0 || aktiv >= buchstaben.size) return
    val swiss = Stil.indexKarte
    // Swiss: Karte 64 x 168 dp (rotes Feld 64 + 4 Nachbarn je 26), mittig auf Hoehe des Daumens
    val halb = if (swiss) with(LocalDensity.current) { 84.dp.toPx() } else blasenHalb
    Box(
        Modifier
            .offset {
                val y = finger.floatValue / n * hoeheLesen()
                // Swiss-Kante: Buchstabe sitzt direkt vor dem Akzent-Balken der Kante (gleiche Bewegung, gleiches Tempo)
                IntOffset(-(blasenAbstand + zug.floatValue).roundToInt(), (y - halb).roundToInt())
            }
            .then(if (swiss) Modifier.size(64.dp, 168.dp) else Modifier.size(60.dp))
            .then(if (Glas.an && !swiss) Modifier.glas(30.dp, staerke = 1.4f, deckung = { staerke.value })
                  // Gezeichnet: der Buchstabe am Daumen wird umkringelt (jeder Buchstabe anders)
                  else if (Skizze.an && !swiss) Modifier.skizzeRahmen(kreis = true, aus = 4.dp, saat = aktiv * 7919 + 13, deckung = { staerke.value })
                  // Art Deco: Sonnenstrahlen-Medaillon
                  else if (Deko.an && !swiss) Modifier.dekoSonne(deckung = { staerke.value })
                  else Modifier)
            .graphicsLayer {
                alpha = staerke.value
                val g = 0.85f + 0.15f * staerke.value
                scaleX = g; scaleY = g
            },
        contentAlignment = Alignment.Center,
    ) {
        if (Stil.indexKarte) SwissIndexKarte(buchstaben, aktiv)
        else Text(
            buchstaben[aktiv], color = p.text, fontSize = 46.sp, fontWeight = FontWeight.Light,
            style = LocalTextStyle.current.copy(
                shadow = Shadow(if (p.dunkel) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.8f), Offset(0f, 2f), 18f)),
        )
    }
}


/** wie breit die App-Namen wirklich gezeichnet werden (px) - "Einstellungen" fuer null */
@Composable
internal fun nameBreiten(eintraege: List<ListenZeile.App?>): Map<ListenZeile.App?, Int> {
    val messer = androidx.compose.ui.text.rememberTextMeasurer()
    val stil = schattenStil()
    return remember(eintraege, stil) {
        eintraege.associateWith { e ->
            messer.measure(e?.app?.name ?: "Einstellungen", stil.copy(fontSize = 23.sp), maxLines = 1).size.width
        }
    }
}

/**
 * Ordnet die Eintraege so, dass der schmalste Name auf Zeile "anker" (Hoehe des Daumens) steht und die Namen
 * nach oben und unten abwechselnd breiter werden - die Welle am Alphabet verdeckt dann keinen Namen.
 */
internal fun nachDaumenOrdnen(eintraege: List<ListenZeile.App?>, anker0: Int, breiten: Map<ListenZeile.App?, Int>,
                              sichtbar0: Int = eintraege.size): List<ListenZeile.App?> {
    val n = eintraege.size
    if (n < 3) return eintraege
    // sichtbar: so viele Zeilen passen ohne Scrollen hin - die langen Namen, die nicht mehr passen, kommen ans Ende
    val sichtbar = sichtbar0.coerceIn(1, n)
    val anker = anker0.coerceIn(0, sichtbar - 1)
    val schmal = eintraege.sortedWith(compareBy({ breiten[it] ?: 0 }, { (it?.app?.name ?: "Einstellungen").lowercase() }))
    val plaetze = (0 until sichtbar).sortedWith(compareBy({ kotlin.math.abs(it - anker) }, { if (it < anker) 0 else 1 })) + (sichtbar until n)
    val ergebnis = arrayOfNulls<ListenZeile.App?>(n).toMutableList()
    plaetze.forEachIndexed { k, platz -> ergebnis[platz] = schmal[k] }
    return ergebnis
}


/**
 * Feste Reihenfolge je Buchstabe (nach Namenslaenge): wird einmal am Tag ausgerechnet - oder wenn bei dem
 * Buchstaben Apps dazukommen / wegfallen - und bleibt dazwischen gleich (gespeichert, auch nach Neustart).
 */
internal object AbcReihe {
    private var geladen = false
    private var tag = ""
    private val reihen = HashMap<String, List<String>>()        // Buchstabe -> Schluessel ("" = Einstellungen)

    private fun heute() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
    private fun schl(e: ListenZeile.App?) = e?.app?.schluessel ?: ""

    private fun laden(ctx: android.content.Context) {
        if (geladen) return
        geladen = true
        try {
            val o = org.json.JSONObject(ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("abcReihe", "{}") ?: "{}")
            tag = o.optString("tag")
            val r = o.optJSONObject("r") ?: return
            for (k in r.keys()) { val a = r.getJSONArray(k); reihen[k] = (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) { }
    }

    private fun speichern(ctx: android.content.Context) {
        try {
            val r = org.json.JSONObject()
            for ((k, l) in reihen) r.put(k, org.json.JSONArray(l))
            ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit()
                .putString("abcReihe", org.json.JSONObject().put("tag", tag).put("r", r).toString()).apply()
        } catch (e: Exception) { }
    }

    /** rechne() = neue Reihenfolge, oder null, wenn die Lage noch nicht bekannt ist */
    fun holen(ctx: android.content.Context, buchstabe: String, eintraege: List<ListenZeile.App?>,
              rechne: () -> List<ListenZeile.App?>?): List<ListenZeile.App?> {
        laden(ctx)
        val h = heute()
        if (tag != h) { reihen.clear(); tag = h }
        val nachSchl = eintraege.associateBy { schl(it) }
        val alt = reihen[buchstabe]
        // gespeicherte Reihenfolge passt noch (gleiche Apps): genau so zeigen
        if (alt != null && alt.size == eintraege.size && alt.toSet() == nachSchl.keys) return alt.map { nachSchl[it] }
        val neu = rechne() ?: return eintraege
        reihen[buchstabe] = neu.map { schl(it) }
        speichern(ctx)
        return neu
    }
}
