package de.atelierlauncher.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// ============================================================================
//  Nach Updates suchen: ein Tipp = neueste Version auf GitHub suchen, laden und installieren.
//  Fragt nur beim Antippen bei GitHub nach. Android zeigt zur Sicherheit "Aktualisieren?" -
//  ab Android 12 entfaellt das, sobald Atelier sich einmal selbst aktualisiert hat.
// ============================================================================
object Aktualisieren {
    const val REPO = "entenjager/atelier-launcher"
    private const val AKTION = "atelier.UPDATE_STATUS"

    class Stand(val version: String, val apk: String?)

    /** was unter dem Knopf steht */
    var meldung by mutableStateOf("Ein Tipp: suchen, laden, installieren")
    var laeuft by mutableStateOf(false)
    /** wartet auf "Apps installieren erlauben" - danach geht es von selbst weiter */
    var wartetAufRecht by mutableStateOf(false)

    /** neueste Version auf GitHub, null = noch keine; Fehler = keine Verbindung */
    fun neueste(): Stand? {
        val v = java.net.URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        try {
            v.connectTimeout = 8000; v.readTimeout = 8000
            v.setRequestProperty("Accept", "application/vnd.github+json")
            if (v.responseCode == 404) return null
            if (v.responseCode != 200) throw java.io.IOException("HTTP ${v.responseCode}")
            val o = org.json.JSONObject(v.inputStream.bufferedReader().use { it.readText() })
            var apk: String? = null
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); break }
            }
            return Stand(o.optString("tag_name").removePrefix("v"), apk)
        } finally { v.disconnect() }
    }

    fun eigene(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    /** Versionen zahlenweise vergleichen ("2026.10.24.1830" > "1.0") */
    fun neuer(a: String, b: String): Boolean {
        val x = Regex("\\d+").findAll(a).map { it.value.toLongOrNull() ?: 0L }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toLongOrNull() ?: 0L }.toList()
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0L }; val q = y.getOrElse(i) { 0L }
            if (p != q) return p > q
        }
        return false
    }

    fun darfInstallieren(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    private fun rechtHolen(ctx: Context) {
        wartetAufRecht = true
        meldung = "„Apps installieren“ erlauben, dann zurück – es geht von selbst weiter"
        try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    /** der ganze Ablauf: suchen -> laden -> installieren */
    suspend fun los(ctx: Context) {
        if (laeuft) return
        laeuft = true
        try {
            meldung = "Suche …"
            val s = try { withContext(Dispatchers.IO) { neueste() } } catch (e: Exception) { meldung = "Keine Verbindung – nochmal antippen"; return }
            when {
                s == null || s.apk.isNullOrBlank() -> { meldung = "Noch keine Version auf GitHub"; return }
                !neuer(s.version, eigene(ctx)) -> { meldung = "Du hast die neueste Version"; return }
                !darfInstallieren(ctx) -> { rechtHolen(ctx); return }
            }
            wartetAufRecht = false
            val datei = File(ctx.cacheDir, "update.apk")
            try {
                withContext(Dispatchers.IO) { laden(s!!.apk!!, datei) { p -> meldung = "Lade Version ${s.version} … $p %" } }
            } catch (e: Exception) { meldung = "Laden abgebrochen – nochmal antippen"; return }
            meldung = "Installiere …"
            withContext(Dispatchers.IO) { installieren(ctx.applicationContext, datei) }
        } catch (e: Exception) {
            meldung = "Hat nicht geklappt – nochmal antippen"
        } finally { laeuft = false }
    }

    private fun laden(url: String, ziel: File, fortschritt: (Int) -> Unit) {
        var adresse = url
        repeat(5) {                                                   // GitHub leitet zum Download-Server weiter
            val v = java.net.URL(adresse).openConnection() as java.net.HttpURLConnection
            v.instanceFollowRedirects = false; v.connectTimeout = 10000; v.readTimeout = 20000
            try {
                if (v.responseCode in 300..399) { adresse = v.getHeaderField("Location") ?: throw java.io.IOException(); return@repeat }
                if (v.responseCode != 200) throw java.io.IOException("HTTP ${v.responseCode}")
                val gesamt = v.contentLengthLong
                var gelesen = 0L; var zuletzt = -1
                v.inputStream.use { i -> ziel.outputStream().use { o ->
                    val puffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = i.read(puffer); if (n < 0) break
                        o.write(puffer, 0, n); gelesen += n
                        val p = if (gesamt > 0) (gelesen * 100 / gesamt).toInt() else 0
                        if (p != zuletzt) { zuletzt = p; fortschritt(p) }
                    }
                } }
                return
            } finally { v.disconnect() }
        }
        throw java.io.IOException("zu viele Weiterleitungen")
    }

    private var empfaengerDa = false

    private fun installieren(ctx: Context, datei: File) {
        if (!empfaengerDa) {
            ContextCompat.registerReceiver(ctx, Status(), IntentFilter(AKTION), ContextCompat.RECEIVER_NOT_EXPORTED)
            empfaengerDa = true
        }
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            datei.inputStream().use { i ->
                s.openWrite("atelier.apk", 0, datei.length()).use { o -> i.copyTo(o); s.fsync(o) }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val rueck = PendingIntent.getBroadcast(ctx, id, Intent(AKTION).setPackage(ctx.packageName), flags)
            s.commit(rueck.intentSender)
        }
    }

    /** Antwort von Android: Bestaetigung zeigen oder Ergebnis melden */
    private class Status : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val bestaetigen: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                               else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    meldung = "Bei Android auf „Aktualisieren“ tippen"
                    try { bestaetigen?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e: Exception) { }
                }
                PackageInstaller.STATUS_SUCCESS -> meldung = "Aktualisiert ✓"
                PackageInstaller.STATUS_FAILURE_ABORTED -> meldung = "Abgebrochen – nochmal antippen"
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    meldung = "Passt nicht zu dieser Installation (anders signiert) – Atelier einmal von GitHub neu installieren"
                PackageInstaller.STATUS_FAILURE_STORAGE -> meldung = "Zu wenig Speicher"
                else -> meldung = "Hat nicht geklappt – nochmal antippen"
            }
        }
    }
}

@Composable
internal fun UpdateEintrag() {
    val ctx = LocalContext.current
    val z = LocalZustand.current
    val scope = rememberCoroutineScope()
    // zurueck aus "Apps installieren erlauben": gleich weitermachen
    LaunchedEffect(z.fortsetzen) {
        if (Aktualisieren.wartetAufRecht && Aktualisieren.darfInstallieren(ctx)) Aktualisieren.los(ctx)
    }
    MenueEintrag(if (Aktualisieren.laeuft) "Update läuft …" else "Aktualisieren", Aktualisieren.meldung) {
        scope.launch { Aktualisieren.los(ctx) }
    }
}
