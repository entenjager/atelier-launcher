package de.atelierlauncher.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import android.widget.Toast

// ============================================================================
//  Spotify "Heruntergeladen" abspielen, auch wenn gerade nichts laeuft.
//  Weg: mit Spotifys Medien-Katalog verbinden (dieselbe Schnittstelle wie Android Auto),
//  dort den Eintrag "Heruntergeladen"/"Downloaded" suchen und abspielen.
//  Gibt Spotify den Katalog nicht frei: letzte Musik weiterspielen (Medientaste).
// ============================================================================
object SpotifyHeruntergeladen {
    private const val PAKET = "com.spotify.music"
    private val NAMEN = listOf("heruntergeladen", "downloaded", "downloads", "download")
    private val haupt = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null
    private var fertig = false

    fun abspielen(ctx: Context) {
        val app = ctx.applicationContext
        val dienst = katalogDienst(app)
        if (dienst == null) { ersatz(app, "Spotify nicht gefunden"); return }
        haupt.removeCallbacksAndMessages(null)   // Reste vom letzten Tippen weg
        aufraeumen()
        fertig = false
        // nach 6 s ohne Erfolg: aufgeben und weiterspielen, was zuletzt lief
        haupt.postDelayed({ if (!fertig) { fertig = true; aufraeumen(); ersatz(app, null) } }, 6000)
        val b = MediaBrowser(app, dienst, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val br = browser ?: return
                suchen(app, br, MediaController(app, br.sessionToken), listOf(br.root), 0)
            }
            override fun onConnectionFailed() { if (!fertig) { fertig = true; aufraeumen(); ersatz(app, null) } }
            override fun onConnectionSuspended() { }
        }, null)
        browser = b
        try { b.connect() } catch (e: Exception) { fertig = true; aufraeumen(); ersatz(app, null) }
    }

    /** Breitensuche (max. 3 Ebenen) nach dem Eintrag "Heruntergeladen" */
    private fun suchen(ctx: Context, br: MediaBrowser, steuer: MediaController, ebene: List<String>, tiefe: Int) {
        if (fertig || ebene.isEmpty() || tiefe > 3) return
        val naechste = mutableListOf<String>()
        var offen = ebene.size
        for (id in ebene) {
            br.subscribe(id, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, kinder: MutableList<MediaBrowser.MediaItem>) {
                    try { br.unsubscribe(parentId) } catch (e: Exception) { }
                    if (fertig) return
                    for (k in kinder) {
                        val name = k.description.title?.toString()?.lowercase() ?: ""
                        if (NAMEN.any { name.contains(it) }) {
                            val mid = k.mediaId ?: continue
                            if (k.isPlayable) { los(ctx, steuer, mid); return }
                            // Ordner "Heruntergeladen": den ganzen Ordner abspielen (Spotify spielt dann alles darin)
                            try { steuer.transportControls.playFromMediaId(mid, null) } catch (e: Exception) { }
                            fertig = true
                            haupt.postDelayed({ ersterTitel(ctx, br, steuer, mid) }, 400)
                            return
                        }
                        if (k.isBrowsable) k.mediaId?.let { naechste.add(it) }
                    }
                    offen--
                    if (offen == 0) suchen(ctx, br, steuer, naechste.take(12), tiefe + 1)
                }
                override fun onError(parentId: String) {
                    offen--
                    if (offen == 0) suchen(ctx, br, steuer, naechste.take(12), tiefe + 1)
                }
            })
        }
    }

    /** Falls der Ordner selbst nicht abspielbar ist: ersten Titel darin starten */
    private fun ersterTitel(ctx: Context, br: MediaBrowser, steuer: MediaController, ordner: String) {
        val st = steuer.playbackState?.state
        if (st == android.media.session.PlaybackState.STATE_PLAYING || st == android.media.session.PlaybackState.STATE_BUFFERING) {
            aufraeumen(); Musik.start(ctx); return
        }
        try {
            br.subscribe(ordner, object : MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId: String, kinder: MutableList<MediaBrowser.MediaItem>) {
                    val t = kinder.firstOrNull { it.isPlayable }?.mediaId
                    if (t != null) los(ctx, steuer, t) else { aufraeumen(); ersatz(ctx, null) }
                }
                override fun onError(parentId: String) { aufraeumen(); ersatz(ctx, null) }
            })
        } catch (e: Exception) { aufraeumen(); ersatz(ctx, null) }
    }

    private fun los(ctx: Context, steuer: MediaController, mediaId: String) {
        fertig = true
        try { steuer.transportControls.playFromMediaId(mediaId, null) } catch (e: Exception) { ersatz(ctx, null) }
        // Verbindung noch kurz halten, dann loesen; Karte aktualisieren
        haupt.postDelayed({ aufraeumen(); Musik.start(ctx) }, 1500)
    }

    /** Plan B: letzte Musik weiterspielen */
    private fun ersatz(ctx: Context, grund: String?) {
        Musik.taste(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
        Toast.makeText(ctx, Sprache.tr(grund ?: "Spotify gibt „Heruntergeladen“ nicht frei – spiele deine letzte Musik"), Toast.LENGTH_SHORT).show()
    }

    private fun aufraeumen() {
        try { browser?.disconnect() } catch (e: Exception) { }
        browser = null
    }

    private fun katalogDienst(ctx: Context): ComponentName? = try {
        ctx.packageManager.queryIntentServices(Intent(MediaBrowserService.SERVICE_INTERFACE).setPackage(PAKET), 0)
            .firstOrNull()?.serviceInfo?.let { ComponentName(it.packageName, it.name) }
    } catch (e: Exception) { null }
}
