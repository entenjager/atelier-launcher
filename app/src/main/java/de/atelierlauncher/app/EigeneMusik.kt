package de.atelierlauncher.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.widget.Toast
import java.io.File
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================================
//  Eigene Musik: MP3s (und andere Audio-Dateien) aus dem Ordner des Launchers, immer in zufaelliger
//  Reihenfolge. Start: auf dem Play/Pause-Knopf nach oben oder unten wischen (nochmal = naechstes Lied).
//  Laeuft ueber eine eigene Medien-Sitzung - dadurch zeigt die Musik-Karte Titel, Play/Pause, Vor/Zurueck
//  und Spulen funktionieren wie bei Spotify, und der Beat-Ring hoert mit.
//  Ordner: Android/data/<paket>/files/Musik (per USB: adb push), dazu Music/Launcher, falls lesbar.
// ============================================================================
internal object EigeneMusik {
    private val ENDUNGEN = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
    private var app: Context? = null
    private var spieler: MediaPlayer? = null
    private var sitzung: MediaSession? = null
    private var aktuell: File? = null
    private val reihe = ArrayDeque<File>()          // noch nicht gespielte Lieder (gemischt)
    private val gespielt = ArrayList<File>()        // fuer "zurueck"
    private var fokus: AudioFocusRequest? = null
    private var weiterNachFokus = false
    /** Menue mit den eigenen Liedern (von oben): Ziel offen/zu, waehrend des Ziehens folgt es dem Finger */
    val menueOffen = androidx.compose.runtime.mutableStateOf(false)
    val menueZieht = androidx.compose.runtime.mutableStateOf(false)
    val menueZug = androidx.compose.runtime.mutableFloatStateOf(0f)
    /** wie weit das Menue gerade offen ist (0..1) - auch fuer die Unschaerfe dahinter */
    val menueStand = androidx.compose.animation.core.Animatable(0f)
    /** Aufnahme des Startbildschirms (nur solange das Menue zu sehen ist) - fuer die Milchglas-Flaeche des Menues */
    var hinterEbene: androidx.compose.ui.graphics.layer.GraphicsLayer? = null
    fun menueZiehen(wert: Float) { menueZieht.value = true; menueZug.floatValue = wert.coerceIn(0f, 1f) }
    /** losgelassen: ab gut einem Drittel ganz auf, sonst zu */
    fun menueLoslassen() { menueOffen.value = menueZug.floatValue > 0.35f; menueZieht.value = false }
    /** gerade gespielte Datei (fuer das Haekchen im Menue) */
    val jetztPfad = androidx.compose.runtime.mutableStateOf<String?>(null)

    /** bestimmtes Lied aus dem Menue: danach geht es zufaellig weiter */
    fun spieleDatei(ctx: Context, f: File) {
        app = ctx.applicationContext
        reihe.removeAll { it.path == f.path }
        if (reihe.isEmpty()) { neuMischen(dateien(ctx).filter { it.path != f.path }) }
        aktuell?.let { gespielt.add(it); if (gespielt.size > 50) gespielt.removeAt(0) }
        spielen(f)
    }

    /** nur das erste Wort des Titels ("Habits (feat. …)" -> "Habits", "Lied Nr. 2 - Radio Edit" -> "Lied") */
    fun kurz(t: String): String {
        val w = t.trim().split(Regex("\\s+")).firstOrNull()?.trim('(', ')', '[', ']', '-', '–', ',', '.', ':') ?: t
        return w.ifBlank { t.trim() }
    }

    class Lied(val datei: File, val titel: String, val kuenstler: String, val bild: androidx.compose.ui.graphics.ImageBitmap?)

    /** Cover aus der Datei, verkleinert (fuer die Zeile im Menue) - null, wenn keins drin ist */
    fun cover(f: File): androidx.compose.ui.graphics.ImageBitmap? = try {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(f.path)
        val roh = r.embeddedPicture
        r.release()
        if (roh == null) null else {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(roh, 0, roh.size, o)
            var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 240) s *= 2
            android.graphics.BitmapFactory.decodeByteArray(roh, 0, roh.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
        }
    } catch (e: Throwable) { null }

    /** Titel fuer die Liste: aus der Datei (Tag), sonst der Dateiname */
    fun titel(f: File): Pair<String, String> = try {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(f.path)
        val t = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: f.nameWithoutExtension
        val k = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: ""
        r.release()
        kurz(t) to k
    } catch (e: Exception) { kurz(f.nameWithoutExtension) to "" }

    fun ordner(ctx: Context): File = File(ctx.getExternalFilesDir(null), "Musik").apply { mkdirs() }

    fun dateien(ctx: Context): List<File> {
        val orte = listOf(ordner(ctx), File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC), "Launcher"))
        return orte.flatMap { o -> try { o.listFiles()?.toList() ?: emptyList() } catch (e: Exception) { emptyList() } }
            .filter { it.isFile && it.canRead() && it.extension.lowercase() in ENDUNGEN }
            .distinctBy { it.name }
    }

    /** Wischen auf Play/Pause: zufaelliges eigenes Lied (laeuft schon eins: das naechste zufaellige) */
    fun zufall(ctx: Context) {
        app = ctx.applicationContext
        val alle = dateien(ctx)
        if (alle.isEmpty()) {
            Toast.makeText(ctx, "Keine eigenen Lieder gefunden", Toast.LENGTH_SHORT).show()
            return
        }
        // neue / geloeschte Dateien beruecksichtigen
        reihe.retainAll { f -> alle.any { it.path == f.path } }
        if (reihe.isEmpty()) neuMischen(alle)
        naechstes()
    }

    private fun neuMischen(alle: List<File>) {
        val m = alle.shuffled().toMutableList()
        // nicht dasselbe Lied zweimal hintereinander
        if (m.size > 1 && m.first().path == aktuell?.path) { m.add(m.removeAt(0)) }
        reihe.clear(); reihe.addAll(m)
    }

    private fun naechstes() {
        val ctx = app ?: return
        if (reihe.isEmpty()) { val alle = dateien(ctx); if (alle.isEmpty()) { stopp(); return }; neuMischen(alle) }
        aktuell?.let { gespielt.add(it); if (gespielt.size > 50) gespielt.removeAt(0) }
        spielen(reihe.removeFirst())
    }

    private fun vorheriges() {
        val sp = spieler
        // mehr als 3 s im Lied: an den Anfang (wie ueberall)
        if (sp != null && try { sp.currentPosition > 3000 } catch (e: Exception) { false }) { sp.seekTo(0); stand(); return }
        val f = gespielt.removeLastOrNull() ?: run { sp?.seekTo(0); stand(); return }
        aktuell?.let { reihe.addFirst(it) }
        aktuell = null
        spielen(f)
    }

    private fun spielen(f: File) {
        val ctx = app ?: return
        if (!fokusHolen(ctx)) return
        try { spieler?.release() } catch (e: Exception) { }
        spieler = null
        aktuell = f
        jetztPfad.value = f.path
        try {
            spieler = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(f.path)
                setOnCompletionListener { naechstes() }
                setOnErrorListener { _, _, _ -> Konsole.schreib("musik", "Eigene Musik: Fehler bei " + f.name); naechstes(); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Konsole.schreib("musik", "Eigene Musik: " + f.name + " - " + e.message)
            spieler = null
        }
        sitzungAnlegen(ctx)
        metadaten()
        stand()
    }

    private fun pause() { try { spieler?.pause() } catch (e: Exception) { }; stand() }
    private fun weiter() {
        val ctx = app ?: return
        val sp = spieler ?: run { zufall(ctx); return }
        if (!fokusHolen(ctx)) return
        try { sp.start() } catch (e: Exception) { }
        stand()
    }

    private fun stopp() {
        try { spieler?.release() } catch (e: Exception) { }
        spieler = null; aktuell = null; jetztPfad.value = null
        fokusAbgeben()
        try { sitzung?.isActive = false; sitzung?.release() } catch (e: Exception) { }
        sitzung = null
    }

    // ---------------------------------------------------------------- Medien-Sitzung
    private fun sitzungAnlegen(ctx: Context) {
        if (sitzung != null) return
        sitzung = MediaSession(ctx, "EigeneMusik").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = weiter()
                override fun onPause() = pause()
                override fun onStop() = stopp()
                override fun onSkipToNext() = naechstes()
                override fun onSkipToPrevious() = vorheriges()
                override fun onSeekTo(pos: Long) { try { spieler?.seekTo(pos.toInt()) } catch (e: Exception) { }; stand() }
            })
            isActive = true
        }
    }

    private fun metadaten() {
        val f = aktuell ?: return
        val dauer = try { spieler?.duration?.toLong() ?: 0L } catch (e: Exception) { 0L }
        // Titel/Kuenstler aus der Datei, sonst der Dateiname
        var titel = kurz(f.nameWithoutExtension); var kuenstler = "Eigene Musik"
        try {
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(f.path)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let { titel = it }
            titel = kurz(titel)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let { kuenstler = it }
            val bild = r.embeddedPicture?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
            r.release()
            sitzung?.setMetadata(MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, titel)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, kuenstler)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dauer)
                .apply { if (bild != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bild) }
                .build())
            return
        } catch (e: Exception) { }
        sitzung?.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, titel)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, kuenstler)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, dauer).build())
    }

    private fun stand() {
        val sp = spieler
        val spielt = try { sp?.isPlaying == true } catch (e: Exception) { false }
        val pos = try { sp?.currentPosition?.toLong() ?: 0L } catch (e: Exception) { 0L }
        sitzung?.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP)
            .setState(if (spielt) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, if (spielt) 1f else 0f)
            .build())
    }

    // ---------------------------------------------------------------- Audio-Fokus (Spotify & Co. pausieren dann)
    private val fokusHoerer = AudioManager.OnAudioFocusChangeListener { f ->
        when (f) {
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); fokusAbgeben() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { weiterNachFokus = try { spieler?.isPlaying == true } catch (e: Exception) { false }; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> try { spieler?.setVolume(0.3f, 0.3f) } catch (e: Exception) { }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try { spieler?.setVolume(1f, 1f) } catch (e: Exception) { }
                if (weiterNachFokus) { weiterNachFokus = false; try { spieler?.start() } catch (e: Exception) { }; stand() }
            }
        }
    }

    private fun fokusHolen(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        if (Build.VERSION.SDK_INT < 26) return true
        val r = fokus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(fokusHoerer).build().also { fokus = it }
        return am.requestAudioFocus(r) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun fokusAbgeben() {
        val ctx = app ?: return
        if (Build.VERSION.SDK_INT >= 26) fokus?.let { try { ctx.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) } catch (e: Exception) { } }
    }
}


/** Liste der eigenen Lieder - kommt von oben herein und folgt dem Finger. Antippen = abspielen, daneben tippen / hochwischen = zu */
@androidx.compose.runtime.Composable
internal fun EigeneMusikMenue() {
    val offen = EigeneMusik.menueOffen.value
    val zieht = EigeneMusik.menueZieht.value
    val zug = EigeneMusik.menueZug.floatValue
    val stand = EigeneMusik.menueStand
    val ziel = if (zieht) zug else if (offen) 1f else 0f
    androidx.compose.runtime.LaunchedEffect(ziel, zieht) {
        if (zieht) stand.snapTo(ziel)
        else stand.animateTo(ziel, androidx.compose.animation.core.spring(dampingRatio = 0.86f, stiffness = 420f))
    }
    val sichtbar = zieht || offen || stand.value > 0.001f
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = LocalPalette.current
    val lieder by androidx.compose.runtime.produceState<List<EigeneMusik.Lied>?>(null, sichtbar) {
        if (!sichtbar) return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            EigeneMusik.dateien(ctx).map { f -> val (t, k) = EigeneMusik.titel(f); EigeneMusik.Lied(f, t, k, EigeneMusik.cover(f)) }
                .sortedBy { it.titel.lowercase() }
        }
    }
    val zu = { EigeneMusik.menueZieht.value = false; EigeneMusik.menueOffen.value = false }
    androidx.activity.compose.BackHandler(offen) { zu() }
    if (!sichtbar) return
    var hoehePx by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val dichteM = androidx.compose.ui.platform.LocalDensity.current
    var basis by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val form = androidx.compose.foundation.shape.RoundedCornerShape(24.dp)
    // wie weit das Menue gerade nach oben geschoben ist (px) - aus dem Stand, damit sich alles mitbewegt
    fun androidx.compose.ui.unit.Density.versatz(): Float {
        val h = if (hoehePx > 0) hoehePx.toFloat() else 420.dp.toPx()
        return -(1f - stand.value) * (h + 40.dp.toPx())
    }
    // Schleier: daneben tippen schliesst
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
        .graphicsLayer { alpha = stand.value.coerceIn(0f, 1f) }
        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.25f))
        .then(if (offen && !zieht) androidx.compose.ui.Modifier.clickable(
            interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) { zu() }
            else androidx.compose.ui.Modifier))
    androidx.compose.foundation.layout.Box(
        androidx.compose.ui.Modifier.fillMaxWidth()
            .onSizeChanged { hoehePx = it.height }
            .graphicsLayer { translationY = versatz() }
            .statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
            // Lage ohne Verschiebung merken (fuer den weichgezeichneten Hintergrund)
            .onGloballyPositioned { k -> val o = k.positionInWindow(); basis = androidx.compose.ui.geometry.Offset(o.x, o.y - with(dichteM) { versatz() }) }
            .clip(form)
            .heightIn(max = 440.dp)
    ) {
        // Milchglas: der Startbildschirm hinter dem Menue, stark weichgezeichnet
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.matchParentSize()
            .graphicsLayer {
                if (Build.VERSION.SDK_INT >= 31) renderEffect = androidx.compose.ui.graphics.BlurEffect(34.dp.toPx(), 34.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp)
                clip = true
            }
            .drawBehind {
                val e = EigeneMusik.hinterEbene ?: return@drawBehind
                translate(-basis.x, -(basis.y + versatz())) { drawLayer(e) }
            })
        // leicht eingefaerbt, damit die Schrift lesbar bleibt
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.matchParentSize()
            .background(p.karte.copy(alpha = if (Build.VERSION.SDK_INT >= 31) 0.45f else 0.85f))
            .border(1.dp, p.text.copy(alpha = 0.14f), form))
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 10.dp)) {
        // Kopf: hier ziehen = Menue mit dem Finger hoch / runter
        val ziehen = androidx.compose.ui.Modifier.pointerInput(Unit) {
            val hoch = 260.dp.toPx()
            detectVerticalDragGestures(
                onDragStart = { EigeneMusik.menueZiehen(stand.value) },
                onDragEnd = { EigeneMusik.menueLoslassen() },
                onDragCancel = { EigeneMusik.menueLoslassen() },
            ) { c, weg -> c.consume(); EigeneMusik.menueZiehen(EigeneMusik.menueZug.floatValue + weg / hoch) }
        }
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.fillMaxWidth().then(ziehen).padding(horizontal = 18.dp, vertical = 6.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextUe("Lokale Musik", color = p.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                   modifier = androidx.compose.ui.Modifier.weight(1f))
            TextUe("Zufällig", color = p.akzent, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                   modifier = androidx.compose.ui.Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                       .clickable { EigeneMusik.zufall(ctx); zu() }.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        val l = lieder
        if (l == null) TextUe("…", color = p.leise, fontSize = 15.sp, modifier = androidx.compose.ui.Modifier.padding(18.dp))
        else if (l.isEmpty()) TextUe("Keine eigenen Lieder gefunden (Ordner Music/Launcher)", color = p.leise, fontSize = 14.sp,
                                     modifier = androidx.compose.ui.Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        else androidx.compose.foundation.lazy.LazyColumn(androidx.compose.ui.Modifier.weight(1f, fill = false)) {
            items(l.size) { i ->
                val lied = l[i]
                val f = lied.datei
                val laeuft = EigeneMusik.jetztPfad.value == f.path
                // jede Zeile: ihr eigenes Cover leicht weich im Hintergrund
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .clickable { EigeneMusik.spieleDatei(ctx, f); zu() }) {
                    val bildZ = lied.bild
                    if (bildZ != null) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.matchParentSize()
                        .graphicsLayer {
                            alpha = if (laeuft) 0.9f else 0.75f
                            clip = true
                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        }
                        .drawWithContent {
                            // weicher Verlauf (Kosinus): Mitte voll, nach links und rechts sanft ganz auslaufen
                            drawContent()
                            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(*COVER_VERLAUF),
                                     blendMode = androidx.compose.ui.graphics.BlendMode.DstIn)
                        }) {
                        // etwas hineingezoomt: keine Raender des Bildes, nur das Motiv
                        androidx.compose.foundation.Image(bildZ, null,
                            androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                                scaleX = 1.45f; scaleY = 1.45f
                                if (Build.VERSION.SDK_INT >= 31) renderEffect = androidx.compose.ui.graphics.BlurEffect(1.5.dp.toPx(), 1.5.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp)
                            }, contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    }
                    androidx.compose.foundation.layout.Row(
                        androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.weight(1f)) {
                            androidx.compose.material3.Text(lied.titel, color = if (laeuft) p.akzent else p.text, fontSize = 16.sp, maxLines = 1,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = schattenStil())
                            if (lied.kuenstler.isNotEmpty()) androidx.compose.material3.Text(lied.kuenstler, color = p.text.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = schattenStil())
                        }
                        if (laeuft) androidx.compose.material3.Text("♪", color = p.akzent, fontSize = 18.sp)
                    }
                }
            }
        }
        // Griff unten: auch hier hochziehen = zu
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxWidth().then(ziehen).padding(vertical = 10.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(36.dp, 4.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)).background(p.text.copy(alpha = 0.3f)))
        }
    }
    }
}

/** alles dahinter weichzeichnen, solange das Lieder-Menue offen ist (ab Android 12) */
internal fun androidx.compose.ui.Modifier.hinterMusikMenue(): androidx.compose.ui.Modifier = this.graphicsLayer {
    // der Startbildschirm selbst nur ganz leicht weich
    val v = EigeneMusik.menueStand.value
    renderEffect = if (v > 0.01f && Build.VERSION.SDK_INT >= 31)
        androidx.compose.ui.graphics.BlurEffect(v * 7.dp.toPx(), v * 7.dp.toPx(), androidx.compose.ui.graphics.TileMode.Clamp) else null
}.drawWithContent {
    // solange das Menue zu sehen ist: Startbildschirm (scharf) aufnehmen - das Menue zeichnet ihn stark weich hinter sich
    val e = EigeneMusik.hinterEbene
    if (e == null || EigeneMusik.menueStand.value <= 0.001f) { drawContent(); return@drawWithContent }
    e.record { this@drawWithContent.drawContent() }
    drawLayer(e)
}


/** Verlauf fuer die Cover in den Zeilen: Kosinus-Kurve, 1 in der Mitte, 0 an beiden Raendern (viele Stufen = keine Kanten) */
private val COVER_VERLAUF: Array<Pair<Float, androidx.compose.ui.graphics.Color>> = Array(21) { i ->
    val x = i / 20f
    val d = kotlin.math.abs(x - 0.5f) / 0.5f
    val a = (0.5f + 0.5f * kotlin.math.cos(Math.PI.toFloat() * d)).let { it * it }
    x to androidx.compose.ui.graphics.Color.White.copy(alpha = a)
}
