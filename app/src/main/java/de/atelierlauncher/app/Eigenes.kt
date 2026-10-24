package de.atelierlauncher.app

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.BatteryManager
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import java.io.File

// ============================================================================
//  Eigene Hintergruende fuer Bastler:
//   - Video:  eigene MP4, laeuft stumm in Schleife (Mitte, bildschirmfuellend)
//   - Lottie: Vektor-Animation (JSON, z. B. von LottieFiles). Daumen am Alphabet = Animation folgt dem Daumen
//   - Code:   eigenes JavaScript zeichnet jedes Bild selbst (male(g))
// ============================================================================

object EigeneDateien {
    fun ordner(ctx: android.content.Context) = File(ctx.filesDir, "eigen").apply { mkdirs() }
    fun video(ctx: android.content.Context) = File(ordner(ctx), "hintergrund.mp4")
    fun lottie(ctx: android.content.Context) = File(ordner(ctx), "hintergrund.json")
    /** eigenes Bild als Hintergrund (Galerie / Dateien) */
    fun bild(ctx: android.content.Context) = File(ordner(ctx), "hintergrund-bild")
    /** eigenes Bild / Video nur fuer den Sperrbildschirm */
    fun sperrBild(ctx: android.content.Context) = File(ordner(ctx), "sperr-bild")
    fun sperrVideo(ctx: android.content.Context) = File(ordner(ctx), "sperr-video.mp4")

    /** Bild verkleinert laden (hoechstens etwa Bildschirmgroesse) - grosse Fotos sprengen sonst den Speicher */
    fun bildLaden(f: File, maxB: Int, maxH: Int): android.graphics.Bitmap? = try {
        if (!f.exists()) null else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, o)
            var s = 1
            while (o.outWidth / (s * 2) >= maxB && o.outHeight / (s * 2) >= maxH) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        }
    } catch (e: Throwable) { null }

    /** Bild bildschirmfuellend (zugeschnitten) auf eine Leinwand malen */
    fun fuellend(c: android.graphics.Canvas, f: android.graphics.Bitmap, b: Float, h: Float) {
        val s = maxOf(b / f.width, h / f.height)
        val bw = f.width * s; val bh = f.height * s
        c.drawBitmap(f, null, android.graphics.RectF((b - bw) / 2f, (h - bh) / 2f, (b + bw) / 2f, (h + bh) / 2f),
                     android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    }
}

/** eigenes Bild als Hintergrund im Launcher */
@Composable
fun BildHintergrund(modifier: Modifier) {
    val ctx = LocalContext.current
    val stand = LocalZustand.current.eigenStand
    val dm = ctx.resources.displayMetrics
    val bild = remember(stand) { EigeneDateien.bildLaden(EigeneDateien.bild(ctx), dm.widthPixels, dm.heightPixels)?.asImageBitmap() }
    if (bild == null) { FehltHinweis(modifier, "Noch kein Bild gewählt – Einstellungen → Design → Hintergrund → Bild"); return }
    androidx.compose.foundation.Image(bild, null, modifier, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
}

@Composable
private fun FehltHinweis(modifier: Modifier, text: String) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(32.dp))
    }
}

// ---------------------------------------------------------------- Video
@Composable
fun VideoHintergrund(modifier: Modifier, laeuft: Boolean) {
    // neue Datei gewaehlt -> alles neu aufbauen (neuer Spieler, neue Flaeche)
    key(LocalZustand.current.eigenStand) { VideoInnen(modifier, laeuft) }
}

@Composable
private fun VideoInnen(modifier: Modifier, laeuft: Boolean) {
    val ctx = LocalContext.current
    val datei = remember { EigeneDateien.video(ctx) }
    if (!datei.exists()) { FehltHinweis(modifier, "Noch kein Video gewählt – Einstellungen → Design → Video"); return }
    val spieler = remember { MediaPlayer() }
    var bereit by remember { mutableStateOf(false) }
    val eingerichtet = remember { booleanArrayOf(false) }
    val flaeche = remember { arrayOfNulls<Surface>(1) }
    DisposableEffect(spieler) { onDispose { try { spieler.release() } catch (e: Exception) { } } }
    LaunchedEffect(laeuft, bereit) {
        if (!bereit) return@LaunchedEffect
        try { if (laeuft) spieler.start() else spieler.pause() } catch (e: Exception) { }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        // zurueck aus einer anderen App: Spieler laeuft schon, nur neue Flaeche geben
                        if (eingerichtet[0]) {
                            try { flaeche[0]?.release() } catch (e: Exception) { }
                            flaeche[0] = Surface(st)
                            try { spieler.setSurface(flaeche[0]) } catch (e: Exception) { }
                            return
                        }
                        eingerichtet[0] = true
                        try {
                            flaeche[0] = Surface(st)
                            spieler.setSurface(flaeche[0])
                            spieler.setDataSource(datei.path)
                            spieler.isLooping = true
                            spieler.setVolume(0f, 0f)
                            spieler.setOnPreparedListener { mp ->
                                // bildschirmfuellend zuschneiden (wie "Crop")
                                val vw = mp.videoWidth.toFloat(); val vh = mp.videoHeight.toFloat()
                                if (vw > 0 && vh > 0 && w > 0 && h > 0) {
                                    val view = w.toFloat() / h; val vid = vw / vh
                                    val m = android.graphics.Matrix()
                                    if (vid > view) m.setScale(vid / view, 1f, w / 2f, h / 2f) else m.setScale(1f, view / vid, w / 2f, h / 2f)
                                    setTransform(m)
                                }
                                bereit = true
                                if (laeuft) mp.start()
                            }
                            spieler.prepareAsync()
                        } catch (e: Exception) { }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        try { spieler.setSurface(null) } catch (e: Exception) { }
                        try { flaeche[0]?.release() } catch (e: Exception) { }
                        flaeche[0] = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

// ---------------------------------------------------------------- Lottie
@Composable
fun LottieHintergrund(modifier: Modifier, laeuft: Boolean) {
    key(LocalZustand.current.eigenStand) { LottieInnen(modifier, laeuft) }
}

@Composable
private fun LottieInnen(modifier: Modifier, laeuft: Boolean) {
    val ctx = LocalContext.current
    val datei = remember { EigeneDateien.lottie(ctx) }
    if (!datei.exists()) { FehltHinweis(modifier, "Noch keine Lottie-Datei gewählt – Einstellungen → Design → Lottie"); return }
    // ohne Zwischenspeicher, damit eine neu gewaehlte Datei wirklich neu gelesen wird
    val zusammensetzung by rememberLottieComposition(LottieCompositionSpec.File(datei.path), cacheKey = null)
    val lauf by animateLottieCompositionAsState(zusammensetzung, iterations = LottieConstants.IterateForever, isPlaying = laeuft)
    var hoehe by remember { mutableIntStateOf(1) }
    LottieAnimation(
        composition = zusammensetzung,
        // Daumen am Alphabet: die Animation folgt dem Daumen (oben = Anfang, unten = Ende)
        progress = {
            if (WellenReiz.daumenDa && WellenReiz.daumenY >= 0f) (WellenReiz.daumenY / hoehe).coerceIn(0f, 1f) else lauf
        },
        contentScale = ContentScale.Crop,
        modifier = modifier.onSizeChanged { hoehe = maxOf(1, it.height) },
    )
}

// ---------------------------------------------------------------- Code
const val HINTERGRUND_BEISPIEL = """// Eigener Hintergrund: male(g) wird ca. 30-mal pro Sekunde aufgerufen.
//
// g.breite, g.hoehe     Bildschirm in Pixeln
// g.zeit                Millisekunden seit Start
// g.daumenY             Höhe des Daumens am Alphabet (-1 = kein Daumen)
// g.akku                0 bis 100
// g.tippX, g.tippY      wo zuletzt angetippt wurde (-1 = nicht in den letzten 0,6 s)
// g.dichte              Pixel pro dp (für gleich große Dinge auf jedem Handy)
//
// g.kreis(x, y, radius, farbe)
// g.ring(x, y, radius, dicke, farbe)
// g.rechteck(x, y, breite, hoehe, farbe)
// g.verlauf(x, y, breite, hoehe, farbeOben, farbeUnten)
// g.linie(x1, y1, x2, y2, dicke, farbe)
// g.bogen(x, y, radius, startGrad, winkelGrad, dicke, farbe)
// g.pfad([x1, y1, x2, y2, ...], farbe, gefuellt, dicke)
// g.text(text, x, y, groesse, farbe, "links"/"mitte"/"rechts")
// farbe als "#RRGGBB" oder mit Durchsichtigkeit "#AARRGGBB"
// log(...) schreibt in die Konsole (Einstellungen → Bastler)

function male(g) {
    var n = 14
    for (var i = 0; i < n; i++) {
        var y = (i + 0.5) / n * g.hoehe
        var r = 30 + 14 * Math.sin(g.zeit / 800 + i)
        // unter dem Daumen werden die Blasen groß
        if (g.daumenY >= 0) r += 60 * Math.exp(-Math.pow((y - g.daumenY) / 140, 2))
        g.kreis(g.breite, y, r, "#5533CCFF")
        g.kreis(g.breite, y, r * 0.55, "#8833CCFF")
    }
}
"""

/**
 * gezeichnete Befehle eines Bildes (wachsende Felder, kein Boxing):
 *   arten:  0 Kreis, 1 Rechteck, 2 Linie, 3 Text, 4 Pfad, 5 Bogen, 6 Verlauf, 7 Ring
 *   zahlen: Werte der Befehle hintereinander, farben: Farben hintereinander (Verlauf hat 2)
 *   texte:  Texte fuer g.text (Index steht in zahlen)
 */
private class Bild {
    var arten = IntArray(128); var nArten = 0
    var zahlen = FloatArray(512); var nZahlen = 0
    var farben = IntArray(128); var nFarben = 0
    val texte = ArrayList<String>()

    /** Platz fuer einen Befehl mit [werte] Zahlen? (Grenze gegen Speicher-Explosion) */
    fun platz(werte: Int) = nArten < 20_000 && nZahlen + werte < 400_000

    fun art(a: Int) { if (nArten == arten.size) arten = arten.copyOf(nArten * 2); arten[nArten++] = a }
    fun zahl(z: Float) { if (nZahlen == zahlen.size) zahlen = zahlen.copyOf(nZahlen * 2); zahlen[nZahlen++] = z }
    fun farbe(f: Int) { if (nFarben == farben.size) farben = farben.copyOf(nFarben * 2); farben[nFarben++] = f }
}

/** "#RRGGBB" / "#AARRGGBB" -> Farbe, mit kleinem Zwischenspeicher (nur vom Skript-Faden benutzen) */
private class FarbLeser {
    private val cache = HashMap<String, Int>()
    fun lies(a: Any?): Int {
        val t = JS.text(a)
        return cache[t] ?: (try { android.graphics.Color.parseColor(t) } catch (e: Exception) { android.graphics.Color.WHITE })
            .also { if (cache.size < 256) cache[t] = it }
    }
}

/** letztes Antippen des Hintergrunds (nur beobachtet, nie verbraucht) */
object HintergrundTipp {
    @Volatile var x = -1f
    @Volatile var y = -1f
    @Volatile var zeit = 0L
}

/** das g-Objekt mit allen Zeichen-Befehlen; bild() = wohin gerade gezeichnet wird */
private fun gObjekt(cx: org.mozilla.javascript.Context, scope: org.mozilla.javascript.Scriptable,
                    bild: () -> Bild, farben: FarbLeser): org.mozilla.javascript.NativeObject {
    fun z(a: Array<Any?>, i: Int, sonst: Double = 0.0) = JS.zahl(a.getOrNull(i), sonst).toFloat()
    return JS.objekt(cx, scope, mapOf(
        "kreis" to JS.fn { a -> val b = bild(); if (b.platz(3)) { b.art(0); for (i in 0..2) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(3))) }; null },
        "rechteck" to JS.fn { a -> val b = bild(); if (b.platz(4)) { b.art(1); for (i in 0..3) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(4))) }; null },
        "linie" to JS.fn { a -> val b = bild(); if (b.platz(5)) { b.art(2); for (i in 0..4) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(5))) }; null },
        // text(text, x, y, groesse, farbe, ausrichtung "links"/"mitte"/"rechts")
        "text" to JS.fn { a ->
            val b = bild()
            if (b.platz(5) && b.texte.size < 2_000) {
                b.art(3)
                b.zahl(z(a, 1)); b.zahl(z(a, 2)); b.zahl(z(a, 3, 40.0).coerceIn(1f, 2_000f))
                b.zahl(when (JS.text(a.getOrNull(5))) { "mitte" -> 1f; "rechts" -> 2f; else -> 0f })
                b.zahl(b.texte.size.toFloat())
                b.texte += JS.text(a.getOrNull(0)).take(500)
                b.farbe(farben.lies(a.getOrNull(4)))
            }
            null
        },
        // pfad([x1, y1, x2, y2, ...], farbe, gefuellt, dicke)
        "pfad" to JS.fn { a ->
            val b = bild()
            val liste = a.getOrNull(0) as? org.mozilla.javascript.NativeArray
            val n = ((liste?.length ?: 0L).coerceAtMost(10_000L).toInt() / 2) * 2
            if (liste != null && n >= 4 && b.platz(n + 3)) {
                b.art(4)
                b.zahl(n.toFloat())
                b.zahl(if (org.mozilla.javascript.Context.toBoolean(a.getOrNull(2))) 1f else 0f)
                b.zahl(z(a, 3, 3.0))
                for (i in 0 until n) {
                    val v = liste.get(i, liste)
                    b.zahl(if (v === org.mozilla.javascript.Scriptable.NOT_FOUND) 0f else JS.zahl(v).toFloat())
                }
                b.farbe(farben.lies(a.getOrNull(1)))
            }
            null
        },
        // bogen(x, y, radius, startGrad, winkelGrad, dicke, farbe)
        "bogen" to JS.fn { a -> val b = bild(); if (b.platz(6)) { b.art(5); for (i in 0..5) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(6))) }; null },
        // verlauf(x, y, breite, hoehe, farbe1, farbe2) - oben farbe1, unten farbe2
        "verlauf" to JS.fn { a ->
            val b = bild()
            if (b.platz(4)) { b.art(6); for (i in 0..3) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(4))); b.farbe(farben.lies(a.getOrNull(5))) }
            null
        },
        // ring(x, y, radius, dicke, farbe)
        "ring" to JS.fn { a -> val b = bild(); if (b.platz(4)) { b.art(7); for (i in 0..3) b.zahl(z(a, i)); b.farbe(farben.lies(a.getOrNull(4))) }; null },
    ))
}

/** Werte, die sich jedes Bild aendern, ins g-Objekt schreiben */
private fun gWerte(g: org.mozilla.javascript.NativeObject, breite: Float, hoehe: Float, zeit: Double, akku: Double, dichte: Float) {
    g.put("breite", g, breite.toDouble())
    g.put("hoehe", g, hoehe.toDouble())
    g.put("zeit", g, zeit)
    g.put("daumenY", g, if (WellenReiz.daumenDa) WellenReiz.daumenY.toDouble() else -1.0)
    g.put("akku", g, akku)
    g.put("dichte", g, dichte.toDouble())
    val frisch = HintergrundTipp.x >= 0f && System.currentTimeMillis() - HintergrundTipp.zeit <= 600
    g.put("tippX", g, if (frisch) HintergrundTipp.x.toDouble() else -1.0)
    g.put("tippY", g, if (frisch) HintergrundTipp.y.toDouble() else -1.0)
}

/** hintergrund.js einmal testen: ausfuehren + male(g) einmal aufrufen. Blockiert - nicht auf dem Bildschirm-Faden! */
fun hintergrundTesten(ctx: android.content.Context, code: String): TestErgebnis {
    val dichte = ctx.resources.displayMetrics.density
    val breite = ctx.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
    val hoehe = ctx.resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1f)
    return JS.testLauf(5_000) { cx, scope ->
        JS.global(scope, "log", Konsole.jsLog("test", 20))
        cx.evaluateString(scope, code, "Hintergrund", 1, null)
        val m = scope.get("male", scope) as? org.mozilla.javascript.Function
        if (m == null) TestErgebnis(false, "Es fehlt: function male(g) { ... }")
        else {
            val bild = Bild()
            val g = gObjekt(cx, scope, { bild }, FarbLeser())
            gWerte(g, breite, hoehe, 0.0, 80.0, dichte)
            m.call(cx, scope, scope, arrayOf<Any?>(g))
            TestErgebnis(true, "OK – male(g) zeichnet " + bild.nArten + " Dinge")
        }
    }
}

object CodeHintergrundDaten {
    @Volatile var bild: Any? = null
    var fehler by mutableStateOf<String?>(null)
    var zaehler by mutableIntStateOf(0)
    private val haupt = android.os.Handler(android.os.Looper.getMainLooper())

    /** Fehler sicher vom Skript-Faden aus setzen */
    fun fehlerMelden(t: String?) { haupt.post { fehler = t } }

    /** zuerst hintergrund.js aus dem Bastel-Ordner, sonst aus der App. Nicht auf dem Bildschirm-Faden aufrufen! */
    fun code(ctx: android.content.Context): String =
        BastelOrdner.lesen(ctx, "hintergrund.js")
            ?: ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("codeHintergrund", null)
            ?: HINTERGRUND_BEISPIEL

    /** speichern; fertig(ok) kommt erst, wenn auch die Datei im Ordner geschrieben ist -> dann neu laden */
    fun codeSpeichern(ctx: android.content.Context, code: String, fertig: (Boolean) -> Unit = {}) {
        ctx.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).edit().putString("codeHintergrund", code).apply()
        fehler = null
        if (BastelOrdner.gesetzt(ctx)) BastelOrdner.speichern(ctx, "hintergrund.js", code, fertig) else fertig(true)
    }
}

/** was ueber Neustarts des Effekts hinweg erhalten bleibt (globale Variablen des Skripts!) */
private class HintergrundLauf {
    val lauf = SkriptLauf("hintergrund")
    @Volatile var male: org.mozilla.javascript.Function? = null
    @Volatile var g: org.mozilla.javascript.NativeObject? = null
    @Volatile var aktuell = Bild()
    @Volatile var quelle: String? = null
    @Volatile var stand = -1
    @Volatile var beschaeftigtSeit = 0L
    @Volatile var akku = 100.0
    @Volatile var akkuZeit = 0L
}

@Composable
fun CodeHintergrund(modifier: Modifier, laeuft: Boolean) {
    val ctx = LocalContext.current
    val stand = LocalZustand.current.eigenStand
    var groesse by remember { mutableStateOf(Size(1f, 1f)) }
    val h = remember { HintergrundLauf() }
    val dichte = LocalDensity.current.density
    val stift = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
    val pfad = remember { Path() }
    DisposableEffect(h) {
        h.lauf.beiHaenger = { t ->
            Konsole.schreib("hintergrund", "Fehler: $t")
            CodeHintergrundDaten.fehlerMelden(t); h.male = null; h.quelle = null
        }
        onDispose { h.lauf.ende() }
    }
    // bei Rueckkehr zur Startseite pruefen, ob hintergrund.js im Ordner geaendert wurde.
    // Neu ausgefuehrt wird nur bei echter Aenderung -> Variablen ausserhalb von male() bleiben erhalten.
    val zurueck = LocalZustand.current.fortsetzen
    LaunchedEffect(laeuft, stand, zurueck) {
        val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE) as BatteryManager
        val start = System.currentTimeMillis()
        val farben = FarbLeser()
        if (stand != h.stand) h.lauf.freigeben()          // gespeichert -> gesperrtes Skript wieder erlauben
        h.lauf.spaeter(5_000) {
            try {
                val quelle = CodeHintergrundDaten.code(ctx)
                if (quelle == h.quelle && stand == h.stand && h.male != null) return@spaeter      // nichts geaendert
                h.quelle = quelle; h.stand = stand
                h.male = null
                CodeHintergrundDaten.bild = null                     // altes Bild nicht stehen lassen
                CodeHintergrundDaten.fehlerMelden(null)
                h.lauf.zuruecksetzenJetzt()
                h.lauf.mitKontext(3000) { cx, scope ->
                    // log() hoechstens 20 Zeilen pro Sekunde (male laeuft 30x pro Sekunde)
                    JS.global(scope, "log", Konsole.jsLog("hintergrund", 20))
                    cx.evaluateString(scope, quelle, "Hintergrund", 1, null)
                    val m = scope.get("male", scope) as? org.mozilla.javascript.Function
                    if (m == null) {
                        CodeHintergrundDaten.fehlerMelden("Es fehlt: function male(g) { ... }")
                        Konsole.schreib("hintergrund", "Es fehlt: function male(g) { ... }")
                    }
                    h.g = gObjekt(cx, scope, { h.aktuell }, farben)
                    h.male = m
                }
            } catch (e: Throwable) {
                val t = JS.fehler(e)
                CodeHintergrundDaten.fehlerMelden(t)
                Konsole.schreib("hintergrund", "Fehler: $t")
            }
        }
        if (!laeuft) return@LaunchedEffect
        while (true) {
            // nur ein Bild gleichzeitig: braucht male() laenger, werden Bilder ausgelassen statt sich zu stauen.
            // (Haengt ein Auftrag, gibt die Sperre nach 1,5 s von selbst nach.)
            val jetzt = System.currentTimeMillis()
            if (h.male != null && (h.beschaeftigtSeit == 0L || jetzt - h.beschaeftigtSeit > 1_500)) {
              h.beschaeftigtSeit = jetzt
              h.lauf.spaeter(2_000) {
                val f = h.male
                val gg = h.g
                try {
                    if (f != null && gg != null) {
                        val bild = Bild()
                        h.aktuell = bild
                        if (System.currentTimeMillis() - h.akkuZeit > 30_000) {
                            h.akku = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble()
                            h.akkuZeit = System.currentTimeMillis()
                        }
                        h.lauf.mitKontext(500) { cx, scope ->
                            gWerte(gg, groesse.width, groesse.height, (System.currentTimeMillis() - start).toDouble(), h.akku, dichte)
                            f.call(cx, scope, scope, arrayOf(gg))
                        }
                        CodeHintergrundDaten.bild = bild
                    }
                } catch (e: Throwable) {
                    val t = JS.fehler(e)
                    CodeHintergrundDaten.fehlerMelden(t); h.male = null; h.quelle = null
                    Konsole.schreib("hintergrund", "Fehler: $t")
                }
                finally { h.beschaeftigtSeit = 0L }
              }
            }
            CodeHintergrundDaten.zaehler++
            delay(33)
        }
    }
    Box(
        modifier.onSizeChanged { groesse = Size(it.width.toFloat(), it.height.toFloat()) }
            // Antippen nur beobachten (nie verbrauchen) -> g.tippX / g.tippY.
            // Kommt nur an, wo nichts anderes ueber dem Hintergrund liegt.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val unten = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    HintergrundTipp.x = unten.position.x
                    HintergrundTipp.y = unten.position.y
                    HintergrundTipp.zeit = System.currentTimeMillis()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tick = CodeHintergrundDaten.zaehler    // neu zeichnen bei jedem Bild
            drawRect(Color.Black)
            val b = CodeHintergrundDaten.bild as? Bild ?: return@Canvas
            val zz = b.zahlen
            var z = 0
            var f = 0
            try {
            for (i in 0 until b.nArten) {
                val farbe = Color(b.farben[f++])
                when (b.arten[i]) {
                    0 -> { drawCircle(farbe, zz[z + 2], Offset(zz[z], zz[z + 1])); z += 3 }
                    1 -> { drawRect(farbe, Offset(zz[z], zz[z + 1]), Size(zz[z + 2], zz[z + 3])); z += 4 }
                    2 -> { drawLine(farbe, Offset(zz[z], zz[z + 1]), Offset(zz[z + 2], zz[z + 3]), zz[z + 4]); z += 5 }
                    3 -> {       // Text: x, y, groesse, ausrichtung, index
                        stift.color = b.farben[f - 1]
                        stift.textSize = zz[z + 2]
                        stift.textAlign = when (zz[z + 3]) {
                            1f -> android.graphics.Paint.Align.CENTER
                            2f -> android.graphics.Paint.Align.RIGHT
                            else -> android.graphics.Paint.Align.LEFT
                        }
                        val t = b.texte.getOrNull(zz[z + 4].toInt())
                        if (t != null) drawContext.canvas.nativeCanvas.drawText(t, zz[z], zz[z + 1], stift)
                        z += 5
                    }
                    4 -> {       // Pfad: anzahl, gefuellt, dicke, punkte...
                        val n = zz[z].toInt()
                        val gefuellt = zz[z + 1] != 0f
                        val dicke = zz[z + 2].coerceAtLeast(0f)
                        val p0 = z + 3
                        pfad.reset()
                        pfad.moveTo(zz[p0], zz[p0 + 1])
                        var k = 2
                        while (k + 1 < n) { pfad.lineTo(zz[p0 + k], zz[p0 + k + 1]); k += 2 }
                        if (gefuellt) { pfad.close(); drawPath(pfad, farbe) }
                        else drawPath(pfad, farbe, style = Stroke(width = dicke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        z += 3 + n
                    }
                    5 -> {       // Bogen: x, y, radius, start, winkel, dicke
                        val r = zz[z + 2]
                        drawArc(farbe, zz[z + 3], zz[z + 4], useCenter = false,
                                topLeft = Offset(zz[z] - r, zz[z + 1] - r), size = Size(2 * r, 2 * r),
                                style = Stroke(width = zz[z + 5].coerceAtLeast(0f), cap = StrokeCap.Round))
                        z += 6
                    }
                    6 -> {       // Verlauf: x, y, breite, hoehe (zweite Farbe folgt)
                        val farbe2 = Color(b.farben[f++])
                        val y = zz[z + 1]
                        drawRect(Brush.verticalGradient(listOf(farbe, farbe2), startY = y, endY = y + zz[z + 3]),
                                 Offset(zz[z], y), Size(zz[z + 2], zz[z + 3]))
                        z += 4
                    }
                    else -> {    // Ring: x, y, radius, dicke
                        drawCircle(farbe, zz[z + 2], Offset(zz[z], zz[z + 1]), style = Stroke(width = zz[z + 3].coerceAtLeast(0f)))
                        z += 4
                    }
                }
            }
            } catch (e: RuntimeException) { }     // unvollstaendiges Bild (z. B. abgebrochener Faden): Rest weglassen
        }
        CodeHintergrundDaten.fehler?.let {
            Text("Fehler im Hintergrund-Code – $it", color = Color(0xFFFF8A80), fontSize = 13.sp,
                 modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}
