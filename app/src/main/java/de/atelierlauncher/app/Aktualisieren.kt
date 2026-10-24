package de.atelierlauncher.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================================
//  Nach Updates suchen: fragt nur beim Antippen die neueste Version auf GitHub ab.
//  Gibt es eine neuere, oeffnet der zweite Tipp den APK-Download im Browser.
// ============================================================================
object Aktualisieren {
    const val REPO = "entenjager/atelier-launcher"

    class Stand(val version: String, val link: String)

    /** neueste Version auf GitHub, null = noch keine; Fehler = keine Verbindung */
    fun neueste(): Stand? {
        val v = java.net.URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as java.net.HttpURLConnection
        try {
            v.connectTimeout = 8000; v.readTimeout = 8000
            v.setRequestProperty("Accept", "application/vnd.github+json")
            if (v.responseCode == 404) return null
            if (v.responseCode != 200) throw java.io.IOException("HTTP ${v.responseCode}")
            val o = org.json.JSONObject(v.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets")
            var link = o.optString("html_url", "https://github.com/$REPO/releases/latest")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { link = a.optString("browser_download_url", link); break }
            }
            return Stand(o.optString("tag_name").removePrefix("v"), link)
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
}

@Composable
internal fun UpdateEintrag() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var unter by remember { mutableStateOf("Fragt nur beim Antippen bei GitHub nach") }
    var titel by remember { mutableStateOf("Nach Updates suchen") }
    var link by remember { mutableStateOf<String?>(null) }
    var sucht by remember { mutableStateOf(false) }
    MenueEintrag(titel, unter, rechts = if (link != null) "↓" else null) {
        val l = link
        if (l != null) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
            return@MenueEintrag
        }
        if (sucht) return@MenueEintrag
        sucht = true; unter = "Suche …"
        scope.launch {
            val r = withContext(Dispatchers.IO) { try { Result.success(Aktualisieren.neueste()) } catch (e: Exception) { Result.failure(e) } }
            sucht = false
            val s = r.getOrNull()
            when {
                r.isFailure -> unter = "Keine Verbindung – nochmal antippen"
                s == null -> unter = "Noch keine Version auf GitHub"
                Aktualisieren.neuer(s.version, Aktualisieren.eigene(ctx)) -> {
                    titel = "Update laden"; unter = "Neue Version ${s.version} – antippen, dann die APK öffnen"; link = s.link
                }
                else -> unter = "Du hast die neueste Version"
            }
        }
    }
}
