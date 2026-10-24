package de.atelierlauncher.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import de.atelierlauncher.app.TextUe as Text

// ============================================================================
//  Eigene Bausteine (erst Atelier): jeder kann sich selbst Bausteine bauen - aus HTML, CSS und
//  JavaScript, wie eine kleine Webseite. Einstellungen → Eigene Bausteine: kurze Anleitung,
//  Beispiele, Code-Feld mit Live-Vorschau. Danach im Baukasten unter "+ Baustein".
//
//  Sicherheit: Der Code laeuft in einem abgeschotteten WebView - ohne Verbindung zum Launcher
//  (keine Apps, keine Benachrichtigungen, keine Einstellungen, keine Dateien). Internet nur, wenn
//  man es fuer diesen Baustein erlaubt. Links oeffnen im Browser, nicht im Baustein.
// ============================================================================

/** true im Baukasten-Editor: dort nur ein Platzhalter (Code laeuft nicht, Ziehen geht ungestoert) */
internal val LocalImEditor = staticCompositionLocalOf { false }

object EigeneBausteine {
    /** alle WebViews der Bausteine - werden angehalten, sobald der Launcher nicht mehr vorne ist (JS-Timer, Internet) */
    val webs = java.util.WeakHashMap<WebView, Boolean>()
    fun alleAnhalten(an: Boolean) {
        webs.keys.toList().forEach { w -> try { if (an) { w.onResume(); w.resumeTimers() } else { w.onPause(); w.pauseTimers() } } catch (t: Throwable) { } }
    }

    data class Def(val id: String, val name: String, val b: Int, val h: Int, val code: String, val netz: Boolean = false)

    /** Aenderungszaehler: Listen und Bausteine zeichnen sich neu */
    val stand = mutableIntStateOf(0)
    private var cache: List<Def>? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun alle(ctx: Context): List<Def> {
        @Suppress("UNUSED_VARIABLE") val s = stand.intValue
        cache?.let { return it }
        val l = try {
            val a = JSONArray(prefs(ctx).getString("eigeneBausteine", "[]"))
            (0 until a.length()).map { i -> a.getJSONObject(i).let { o ->
                Def(o.getString("i"), o.optString("n", "Baustein"), o.optInt("b", 4), o.optInt("h", 2), o.optString("c"), o.optBoolean("netz", false))
            } }
        } catch (e: Exception) { emptyList() }
        cache = l
        return l
    }

    fun von(ctx: Context, id: String) = alle(ctx).firstOrNull { it.id == id }

    private fun schreiben(ctx: Context, l: List<Def>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("i", it.id).put("n", it.name).put("b", it.b).put("h", it.h).put("c", it.code).put("netz", it.netz)) }
        prefs(ctx).edit().putString("eigeneBausteine", a.toString()).apply()
        cache = l; stand.intValue++
    }

    fun speichern(ctx: Context, d: Def) {
        val l = alle(ctx)
        schreiben(ctx, if (l.any { it.id == d.id }) l.map { if (it.id == d.id) d else it } else l + d)
    }

    fun neu(ctx: Context, vorlage: Beispiel): Def {
        val d = Def("e" + System.currentTimeMillis().toString(36), vorlage.name, vorlage.b, vorlage.h, vorlage.code)
        speichern(ctx, d)
        return d
    }

    /** loeschen - auch von beiden Seiten nehmen, wo er liegt */
    fun loeschen(ctx: Context, id: String) {
        schreiben(ctx, alle(ctx).filter { it.id != id })
        for (seite in listOf("start", "oben")) {
            val l = SeitenRaster.laden(ctx, seite)
            val ohne = l.filter { !(it.typ == "eigen" && it.wert == id) }
            if (ohne.size != l.size) SeitenRaster.speichern(ctx, ohne, seite)
        }
    }

    // ---------------------------------------------------------------- Beispiele
    data class Beispiel(val name: String, val b: Int, val h: Int, val code: String)

    fun beispiele(): List<Beispiel> {
        val en = Sprache.englisch
        return listOf(
            Beispiel(if (en) "Greeting" else "Gruß", 10, 2, """
<style>
  .mitte { display: flex; height: 100vh; align-items: center; justify-content: center; }
  h1 { margin: 0; font-size: 26px; font-weight: 300; }
</style>
<div class="mitte"><h1 id="gruss"></h1></div>
<script>
  const h = new Date().getHours();
  document.getElementById('gruss').textContent =
    h < 11 ? '${if (en) "Good morning" else "Guten Morgen"}' : h < 18 ? '${if (en) "Hello" else "Hallo"}' : '${if (en) "Good evening" else "Guten Abend"}';
</script>
""".trimIndent()),
            Beispiel(if (en) "Counter" else "Zähler", 4, 4, """
<style>
  body { display: flex; flex-direction: column; align-items: center; justify-content: center; }
  #zahl { font-size: 44px; font-weight: 600; }
  button { margin-top: 6px; border: 1px solid var(--text); background: transparent; color: var(--text);
           border-radius: 20px; padding: 4px 14px; font-size: 13px; }
</style>
<div id="zahl">0</div>
<button id="null">${if (en) "Reset" else "Auf 0"}</button>
<script>
  // localStorage merkt sich den Stand - auch nach einem Neustart
  let n = Number(localStorage.getItem('n') || 0);
  const zeig = () => { document.getElementById('zahl').textContent = n; localStorage.setItem('n', n); };
  document.getElementById('zahl').onclick = () => { n++; zeig(); };
  document.getElementById('null').onclick = () => { n = 0; zeig(); };
  zeig();
</script>
""".trimIndent()),
            Beispiel(if (en) "Countdown" else "Countdown", 6, 2, """
<style>
  body { display: flex; align-items: center; justify-content: center; gap: 10px; }
  b { font-size: 34px; color: var(--akzent); }
  span { color: var(--leise); font-size: 14px; }
</style>
<b id="tage"></b><span>${if (en) "days until Christmas" else "Tage bis Weihnachten"}</span>
<script>
  // Datum hier aendern (Jahr-Monat-Tag)
  const ziel = new Date('2026-12-24');
  const tage = Math.ceil((ziel - new Date()) / 86400000);
  document.getElementById('tage').textContent = Math.max(0, tage);
</script>
""".trimIndent()),
            Beispiel(if (en) "Dice" else "Würfel", 2, 2, """
<style>
  body { display: flex; align-items: center; justify-content: center; font-size: 56px; }
</style>
<div id="w">⚀</div>
<script>
  const seiten = ['⚀','⚁','⚂','⚃','⚄','⚅'];
  document.body.onclick = () => {
    document.getElementById('w').textContent = seiten[Math.floor(Math.random() * 6)];
  };
</script>
""".trimIndent()),
            Beispiel(if (en) "Year progress" else "Jahr in Prozent", 10, 2, """
<style>
  body { display: flex; flex-direction: column; justify-content: center; padding: 0 4px; box-sizing: border-box; }
  .leiste { height: 6px; border-radius: 3px; background: var(--linie); overflow: hidden; }
  .voll { height: 100%; background: var(--akzent); }
  p { margin: 0 0 6px; font-size: 14px; }
</style>
<p id="text"></p>
<div class="leiste"><div class="voll" id="voll"></div></div>
<script>
  const jetzt = new Date(), j = jetzt.getFullYear();
  const anteil = (jetzt - new Date(j, 0, 1)) / (new Date(j + 1, 0, 1) - new Date(j, 0, 1));
  const p = Math.floor(anteil * 100);
  document.getElementById('text').textContent = j + ': ' + p + ' %';
  document.getElementById('voll').style.width = p + '%';
</script>
""".trimIndent()),
        )
    }

    // ---------------------------------------------------------------- HTML-Seite fuer den WebView
    private fun css(c: Color): String =
        "rgba(${(c.red * 255).roundToInt()},${(c.green * 255).roundToInt()},${(c.blue * 255).roundToInt()},${"%.3f".format(java.util.Locale.US, c.alpha)})"

    /** eigener Code + Farben des Designs als CSS-Variablen (var(--text), var(--akzent) ...) */
    fun seite(code: String, p: Palette): String {
        val kopf = """
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root { --text: ${css(p.text)}; --leise: ${css(p.leise)}; --akzent: ${css(p.akzent)}; --auf-akzent: ${css(p.aufAkzent)};
          --karte: ${css(p.karte)}; --linie: ${css(p.text.copy(alpha = 0.14f))}; --grund: ${css(p.grund)};
          color-scheme: ${if (p.dunkel) "dark" else "light"}; }
  html, body { margin: 0; padding: 0; height: 100%; overflow: hidden; background: transparent; color: var(--text);
               font-family: sans-serif; -webkit-user-select: none; user-select: none; -webkit-tap-highlight-color: transparent; }
</style>
"""
        val i = code.indexOf("<head>", ignoreCase = true)
        return if (i >= 0) code.substring(0, i + 6) + kopf + code.substring(i + 6)
               else "<!doctype html><html><head>$kopf</head><body>\n$code\n</body></html>"
    }
}

/** der Baustein selbst (Startbildschirm / Seite bei #) */
@Composable
internal fun EigenerBaustein(b: Baustein, modifier: Modifier) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    @Suppress("UNUSED_VARIABLE") val s = EigeneBausteine.stand.intValue
    val d = EigeneBausteine.von(ctx, b.wert)
    if (d == null || LocalImEditor.current) {
        // Platzhalter: im Editor (Ziehen soll gehen) oder wenn der Baustein geloescht wurde
        Column(modifier.clip(bForm(18.dp)).bKarte(p, 18.dp, 0.06f).padding(6.dp),
               horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("</>", color = p.akzent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(d?.name ?: "Gelöscht", color = p.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        return
    }
    BausteinWeb(d.code, d.netz, d.id, p, modifier)
}

/** abgeschotteter WebView: kein Zugriff auf Dateien/Launcher, Internet nur wenn erlaubt, Links im Browser */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun BausteinWeb(code: String, netz: Boolean, schluessel: String, p: Palette, modifier: Modifier,
                         konsole: ((String) -> Unit)? = null) {
    val ctx = LocalContext.current
    val html = remember(code, p) { EigeneBausteine.seite(code, p) }
    val konsoleJetzt = androidx.compose.runtime.rememberUpdatedState(konsole)
    val web = remember(schluessel) {
        WebView(ctx).also { EigeneBausteine.webs[it] = true }.apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
            isLongClickable = false; setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true                    // localStorage (z. B. Zaehlerstand) - je Baustein getrennt
                allowFileAccess = false; allowContentAccess = false
                @Suppress("DEPRECATION") run { allowFileAccessFromFileURLs = false; allowUniversalAccessFromFileURLs = false }
                setGeolocationEnabled(false)
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mediaPlaybackRequiresUserGesture = true
                textZoom = 100
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            webViewClient = object : WebViewClient() {
                // Links nicht im Baustein oeffnen, sondern im Browser (nur http/https)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (request.isForMainFrame && (u.scheme == "http" || u.scheme == "https") && request.hasGesture()) try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: Exception) { }
                    return true
                }
            }
            // Konsole (Fehler im Code) - nur die Vorschau in den Einstellungen zeigt sie an
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    val text = (if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) "✗ " else "") +
                               (if (m.lineNumber() > 0) Sprache.tr("Zeile") + " ${m.lineNumber()}: " else "") + m.message()
                    konsoleJetzt.value?.invoke(text)
                    return true
                }
            }
        }
    }
    LaunchedEffect(web, html, netz) {
        web.settings.blockNetworkLoads = !netz
        web.settings.blockNetworkImage = !netz
        // eigene Herkunft je Baustein: localStorage bleibt getrennt, nichts wird von dort geladen
        web.loadDataWithBaseURL("https://$schluessel.baustein.invalid/", html, "text/html", "utf-8", null)
    }
    DisposableEffect(web) { onDispose { try { web.stopLoading(); web.destroy() } catch (e: Exception) { } } }
    AndroidView(factory = { web }, modifier = modifier)
}

// ============================================================================
//  Einstellungen → Eigene Bausteine: Anleitung, Liste, neuer Baustein
// ============================================================================
@Composable
internal fun EigeneBausteineSeite() {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    @Suppress("UNUSED_VARIABLE") val s = EigeneBausteine.stand.intValue
    val liste = EigeneBausteine.alle(ctx)
    var anleitung by remember { mutableStateOf(liste.isEmpty()) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Gruppe("Anleitung") {
            MenueEintrag("So baust du einen eigenen Baustein", if (anleitung) "Antippen zum Zuklappen" else "4 Schritte – antippen zum Aufklappen",
                         rechts = if (anleitung) "▴" else "▾") { anleitung = !anleitung }
            if (anleitung) {
                Schritt(1, "Neuer Baustein", "Unten „Neuer Baustein“ antippen. Er startet mit einem kleinen Beispiel, das du ändern kannst.")
                Schritt(2, "Code einfügen", "Ein Baustein ist eine kleine Webseite: HTML für den Inhalt, <style> für das Aussehen, <script> für alles, was sich bewegt oder rechnet. Code aus dem Netz einfach ins Feld kopieren – oder „Aus Datei …“.")
                Schritt(3, "Vorschau ansehen", "Die Vorschau über dem Code zeigt sofort, wie er aussieht – in der Größe auf dem Startbildschirm. Fehler im Code stehen darunter.")
                Schritt(4, "Platzieren", "„Speichern“ – dann lange auf den Startbildschirm drücken → „+ Baustein“ → unter „Eigene Bausteine“ antippen. Größe und Platz wie bei jedem Baustein.")
                Text("Farben vom Design", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                     modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                Text("Mit var(--text), var(--leise), var(--akzent), var(--karte) und var(--linie) passt dein Baustein immer zum Design – auch wenn du es wechselst. Der Hintergrund ist durchsichtig.",
                     color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                Text("Werte merken", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                     modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                Text("localStorage.setItem('name', wert) speichert etwas im Baustein (z. B. einen Zählerstand) – es bleibt auch nach einem Neustart.",
                     color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
                Text("Sicher", color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                     modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                Text("Ein eigener Baustein läuft abgeschottet: Er sieht keine Apps, Nachrichten, Termine oder Einstellungen des Launchers. Ins Internet darf er nur, wenn du es bei ihm erlaubst. Links öffnen im Browser.",
                     color = p.leise, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp))
            }
        }
        Gruppe("Meine Bausteine") {
            if (liste.isEmpty()) Text("Noch keine eigenen Bausteine.", color = p.leise, fontSize = 14.sp,
                                      modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))
            liste.forEach { d ->
                MenueEintrag(d.name, "${d.b} × ${d.h} Felder" + if (d.netz) " · Internet erlaubt" else "", rechts = "›") {
                    navi.auf(Ebene.Einstellungen("eigen_" + d.id))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Knopf("Neuer Baustein") {
                    val d = EigeneBausteine.neu(ctx, EigeneBausteine.beispiele().first().copy(name = Sprache.tr("Mein Baustein")))
                    navi.auf(Ebene.Einstellungen("eigen_" + d.id))
                }
            }
        }
        Gruppe {
            MenueEintrag("Zum Startbildschirm-Baukasten", "Dort „+ Baustein“ → Eigene Bausteine", rechts = "›") {
                navi.auf(Ebene.Einstellungen("rasterHaupt"))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Schritt(nr: Int, titel: String, text: String) {
    val p = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(p.akzent), contentAlignment = Alignment.Center) {
            Text(nr.toString(), color = p.aufAkzent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titel, color = p.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text, color = p.leise, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

// ============================================================================
//  einen eigenen Baustein bearbeiten: Name, Groesse, Internet, Vorschau, Code
// ============================================================================
@Composable
internal fun EigenerBausteinEditor(id: String) {
    val ctx = LocalContext.current
    val p = LocalPalette.current
    val navi = LocalNavi.current
    val alt = remember(id) { EigeneBausteine.von(ctx, id) }
    if (alt == null) {
        Text("Dieser Baustein wurde gelöscht.", color = p.leise, modifier = Modifier.padding(20.dp))
        return
    }
    var name by remember { mutableStateOf(alt.name) }
    var code by remember { mutableStateOf(alt.code) }
    var breite by remember { mutableIntStateOf(alt.b) }
    var hoehe by remember { mutableIntStateOf(alt.h) }
    var netz by remember { mutableStateOf(alt.netz) }
    var gespeichert by remember { mutableStateOf(alt) }
    val jetzt = EigeneBausteine.Def(id, name.ifBlank { Sprache.tr("Mein Baustein") }, breite, hoehe, code, netz)
    val geaendert = jetzt != gespeichert
    // Vorschau erst nach kurzer Tipp-Pause neu laden
    var vorschauCode by remember { mutableStateOf(code) }
    LaunchedEffect(code) { kotlinx.coroutines.delay(600); vorschauCode = code }
    val meldungen = remember { mutableStateListOf<String>() }
    LaunchedEffect(vorschauCode, netz) { meldungen.clear() }
    var beispielOffen by remember { mutableStateOf(false) }
    var loeschenFrage by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // ---- Leiste
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Knopf(if (geaendert) "Speichern ●" else "Gespeichert ✓") {
                EigeneBausteine.speichern(ctx, jetzt); gespeichert = jetzt
                android.widget.Toast.makeText(ctx, Sprache.tr("Gespeichert"), android.widget.Toast.LENGTH_SHORT).show()
            }
            Chip("Beispiel …") { beispielOffen = true }
            Chip("Aus Datei …") {
                (ctx as? MainActivity)?.let { akt ->
                    akt.dateiWaehlen(arrayOf("text/html", "text/*", "application/javascript", "*/*")) { uri -> akt.textLesen(uri) { t -> code = t } }
                }
            }
            Chip(if (loeschenFrage) "Nochmal tippen = löschen" else "Löschen", gewaehlt = loeschenFrage, farbe = p.warnung) {
                if (!loeschenFrage) loeschenFrage = true
                else { EigeneBausteine.loeschen(ctx, id); navi.zu() }
            }
        }
        LaunchedEffect(loeschenFrage) { if (loeschenFrage) { kotlinx.coroutines.delay(3_000); loeschenFrage = false } }

        // ---- Vorschau in echter Groesse (Feld = Bildschirmbreite / 10)
        Text("Vorschau", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 8.dp))
        val feld = (LocalConfiguration.current.screenWidthDp.dp - START_RAND * 2 + 8.dp) / (SeitenRaster.ZELLEN / SeitenRaster.FEIN)
        Box(Modifier.fillMaxWidth().padding(horizontal = START_RAND, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp)).background(p.grund).border(1.dp, p.text.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                .padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(feld * breite - 8.dp, feld * hoehe - 8.dp)) {
                BausteinWeb(vorschauCode, netz, "vorschau_$id", p, Modifier.fillMaxSize()) { m -> if (meldungen.size < 20) meldungen.add(m) }
            }
        }
        if (meldungen.isNotEmpty()) Text(meldungen.joinToString("\n"), color = p.warnung, fontSize = 12.sp, lineHeight = 16.sp,
                                         fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))

        // ---- Name, Groesse, Internet
        Gruppe {
            Eingabe(name, { name = it }, "Name")
            RgRegler("Breite", breite.toFloat(), 1f..(SeitenRaster.ZELLEN / SeitenRaster.FEIN).toFloat(), "$breite " + Sprache.tr("Felder"), {}) { breite = it.roundToInt().coerceIn(1, SeitenRaster.ZELLEN / SeitenRaster.FEIN) }
            RgRegler("Höhe", hoehe.toFloat(), 1f..20f, "$hoehe " + Sprache.tr("Felder"), {}) { hoehe = it.roundToInt().coerceIn(1, 20) }
            Text("Die Größe gilt beim Hinzufügen – danach im Baukasten an den Ecken ziehen.", color = p.leise, fontSize = 13.sp,
                 modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            Schalter("Internet erlauben", netz, "Nur nötig, wenn dein Code Daten oder Bilder aus dem Netz holt. Aus = alles bleibt auf dem Handy.") { netz = it }
        }

        // ---- Code
        Text("Code (HTML, CSS, JavaScript)", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, top = 10.dp))
        val stil = remember { TextStyle(color = Color(0xFFE6EDF3), fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace) }
        Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().heightIn(min = 260.dp)
                .clip(RoundedCornerShape(16.dp)).background(Color(0xFF101418)).padding(12.dp)) {
            BasicTextField(value = code, onValueChange = { code = it }, textStyle = stil, cursorBrush = SolidColor(p.akzent),
                           modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(40.dp))
    }

    // ---- Beispiel waehlen (ersetzt den Code)
    if (beispielOffen) Dialog(onDismissRequest = { beispielOffen = false }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.grund).padding(vertical = 12.dp)) {
            Text("Beispiel nehmen", color = p.text, fontSize = 19.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            Text("Ersetzt den Code und die Größe.", color = p.leise, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            EigeneBausteine.beispiele().forEach { bsp ->
                MenueEintrag(bsp.name, "${bsp.b} × ${bsp.h} Felder") {
                    code = bsp.code; breite = bsp.b; hoehe = bsp.h
                    if (name.isBlank() || name == Sprache.tr("Mein Baustein") || EigeneBausteine.beispiele().any { it.name == name }) name = bsp.name
                    beispielOffen = false
                }
            }
        }
    }
}
