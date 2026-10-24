package de.atelierlauncher.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import de.atelierlauncher.app.TextUe as Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
//  Swiss-Iconpaket: eckige Kachel mit duenner Kante, darin ein Piktogramm
//  (im Geist der Olympia-Piktogramme der 70er: Linien gleicher Staerke, ein roter Punkt).
//  Bekannte Apps bekommen ein Piktogramm nach ihrem Zweck, alle anderen ein fettes Kuerzel.
//  Keine Markenlogos - nur allgemeine Zeichen.
// ============================================================================
object SwissIcons {
    /** Paketname -> Piktogramm (Teilwort im Paketnamen genuegt) */
    private val REGELN = listOf(
        listOf("dialer", "telecom", ".phone", "incallui") to "telefon",
        listOf("messaging", ".mms", ".sms", "android.apps.messaging") to "nachricht",
        listOf("whatsapp", "securesms", "signal", "telegram", "messenger", "discord", "threema", "snapchat") to "chat",
        listOf("camera") to "kamera",
        listOf("gallery", "photos") to "galerie",
        listOf("android.settings", ".settings") to "einstellungen",
        listOf("clock", "alarm", "deskclock") to "uhr",
        listOf("calendar") to "kalender",
        listOf("maps", "navigation", "waze", "osmand") to "karte",
        listOf(".gm", "mail", "k9", "fsck", "email", "outlook") to "mail",
        listOf("spotify", "music", "soundcloud", "deezer", "podcast") to "musik",
        listOf("youtube", "netflix", "video", "twitch", "primevideo", "disney") to "video",
        listOf("chrome", "browser", "firefox", "sbrowser", "opera", "duckduckgo", "brave") to "browser",
        listOf("notes", "keep", "memo", "notiz", "obsidian") to "notiz",
        listOf("calculator", "calc") to "rechner",
        listOf("myfiles", "files", "documents", "filemanager") to "ordner",
        listOf("vending", "galaxyapps", "store", "aurora") to "laden",
        listOf("contacts") to "person",
        listOf("weather", "wetter") to "wetter",
        listOf("bank", "sparkasse", "paypal", "wallet", "pay") to "karte_geld",
        listOf("instagram") to "kamera",
        listOf("voicerecorder", "recorder") to "mikro",
        listOf("health", "fitness") to "puls",
        listOf("translate", "deepl") to "sprache",
        listOf("authenticator", "bitwarden", "keepass", "password") to "schloss",
    )

    @Volatile private var PAKETE: Map<String, String> = emptyMap()
    fun laden(ctx: android.content.Context) {
        try {
            val id = ctx.resources.getIdentifier("swiss_pakete", "raw", ctx.packageName)
            if (id == 0) return
            PAKETE = ctx.resources.openRawResource(id).bufferedReader(Charsets.UTF_8).useLines { z ->
                z.mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            }
        } catch (e: Exception) { }
    }

    fun art(paket: String): String? {
        PAKETE[paket]?.let { return it }
        val p = paket.lowercase()
        return REGELN.firstOrNull { (woerter, _) -> woerter.any { p.contains(it) } }?.second
    }

    /** 1-2 Buchstaben fuer Apps ohne Piktogramm */
    fun kuerzel(name: String): String {
        val worte = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }
        return when {
            worte.size >= 2 -> "${worte[0].first()}${worte[1].first()}"
            name.length >= 2 -> name.take(2)
            else -> name
        }.uppercase()
    }
}

@Composable
fun SwissIcon(key: String, groesse: Dp) {
    val z = LocalZustand.current
    val p = LocalPalette.current
    val paket = key.substringBefore('/')
    val art = SwissIcons.art(paket)
    val name = z.app(key)?.name ?: paket.substringAfterLast('.')
    Box(Modifier.size(groesse), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(groesse)) {
            val s = size.minDimension
            // kein Rahmen: nur das Piktogramm selbst
            if (art == "spiel") {
                // rote Ecke oben rechts = Spiel
                val e = Path().apply { moveTo(s * 0.62f, 0f); lineTo(s, 0f); lineTo(s, s * 0.38f); close() }
                drawPath(e, p.akzent)
            } else if (art != null) piktogramm(art, s, p.text, p.akzent)
        }
        if (art == null || art == "spiel") Text(
            SwissIcons.kuerzel(name), color = p.text, fontSize = (groesse.value * 0.36f).sp,
            fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp, maxLines = 1,
        )
    }
}

/** Piktogramme auf einem Feld der Groesse s x s. Linienstaerke einheitlich, ein roter Akzent. */
private fun DrawScope.piktogramm(art: String, s: Float, tinte: Color, rot: Color) {
    val w = s * 0.045f                       // duenne Linien
    val linie = Stroke(width = w, cap = StrokeCap.Square, join = StrokeJoin.Miter)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    fun rechteck(l: Float, o: Float, r: Float, u: Float, farbe: Color = tinte, voll: Boolean = false) =
        drawRect(farbe, p(l, o), Size((r - l) * s, (u - o) * s), style = if (voll) androidx.compose.ui.graphics.drawscope.Fill else linie)
    fun kreis(x: Float, y: Float, r: Float, farbe: Color = tinte, voll: Boolean = false) =
        drawCircle(farbe, r * s, p(x, y), style = if (voll) androidx.compose.ui.graphics.drawscope.Fill else linie)
    fun strich(x1: Float, y1: Float, x2: Float, y2: Float, farbe: Color = tinte) =
        drawLine(farbe, p(x1, y1), p(x2, y2), strokeWidth = w, cap = StrokeCap.Square)
    fun pfad(vararg pt: Float, zu: Boolean = false, farbe: Color = tinte, voll: Boolean = false) {
        val pa = Path()
        pa.moveTo(pt[0] * s, pt[1] * s)
        var i = 2
        while (i + 1 < pt.size) { pa.lineTo(pt[i] * s, pt[i + 1] * s); i += 2 }
        if (zu) pa.close()
        drawPath(pa, farbe, style = if (voll) androidx.compose.ui.graphics.drawscope.Fill else linie)
    }
    when (art) {
        "telefon" -> {
            // Hoerer: zwei Muscheln, verbunden
            pfad(0.30f, 0.30f, 0.40f, 0.26f, 0.46f, 0.40f, 0.40f, 0.46f, 0.52f, 0.60f, 0.58f, 0.54f, 0.72f, 0.60f, 0.70f, 0.70f,
                 0.60f, 0.74f, 0.42f, 0.66f, 0.30f, 0.48f, 0.26f, 0.38f, zu = true)
            kreis(0.70f, 0.30f, 0.045f, rot, voll = true)
        }
        "nachricht" -> {
            pfad(0.24f, 0.28f, 0.76f, 0.28f, 0.76f, 0.62f, 0.44f, 0.62f, 0.30f, 0.74f, 0.32f, 0.62f, 0.24f, 0.62f, zu = true)
            strich(0.34f, 0.40f, 0.66f, 0.40f); strich(0.34f, 0.50f, 0.56f, 0.50f, rot)
        }
        "chat" -> {
            pfad(0.24f, 0.30f, 0.76f, 0.30f, 0.76f, 0.64f, 0.40f, 0.64f, 0.26f, 0.76f, 0.28f, 0.64f, 0.24f, 0.64f, zu = true)
            kreis(0.38f, 0.47f, 0.035f, tinte, true); kreis(0.50f, 0.47f, 0.035f, tinte, true); kreis(0.62f, 0.47f, 0.035f, rot, true)
        }
        "kamera" -> {
            rechteck(0.22f, 0.34f, 0.78f, 0.72f)
            pfad(0.38f, 0.34f, 0.42f, 0.26f, 0.58f, 0.26f, 0.62f, 0.34f)
            kreis(0.50f, 0.53f, 0.11f)
            kreis(0.69f, 0.42f, 0.03f, rot, true)
        }
        "galerie" -> {
            rechteck(0.22f, 0.26f, 0.78f, 0.74f)
            pfad(0.26f, 0.68f, 0.42f, 0.48f, 0.54f, 0.62f, 0.62f, 0.54f, 0.74f, 0.68f)
            kreis(0.64f, 0.38f, 0.055f, rot, true)
        }
        "einstellungen" -> {
            kreis(0.50f, 0.50f, 0.17f)
            for (k in 0 until 8) {
                val a = k * PI.toFloat() / 4f
                strich(0.5f + 0.20f * cos(a), 0.5f + 0.20f * sin(a), 0.5f + 0.28f * cos(a), 0.5f + 0.28f * sin(a))
            }
            kreis(0.50f, 0.50f, 0.05f, rot, true)
        }
        "uhr" -> {
            kreis(0.50f, 0.50f, 0.26f)
            strich(0.50f, 0.50f, 0.50f, 0.32f); strich(0.50f, 0.50f, 0.63f, 0.50f)
            strich(0.50f, 0.50f, 0.40f, 0.64f, rot)
        }
        "kalender" -> {
            rechteck(0.24f, 0.28f, 0.76f, 0.74f)
            rechteck(0.24f, 0.28f, 0.76f, 0.38f, rot, voll = true)
            for (r in 0 until 2) for (c in 0 until 3) kreis(0.36f + c * 0.14f, 0.50f + r * 0.12f, 0.025f, tinte, true)
        }
        "karte" -> {
            pfad(0.50f, 0.78f, 0.32f, 0.50f, 0.32f, 0.40f, 0.40f, 0.26f, 0.60f, 0.26f, 0.68f, 0.40f, 0.68f, 0.50f, zu = true)
            kreis(0.50f, 0.42f, 0.06f, rot, true)
        }
        "mail" -> {
            rechteck(0.22f, 0.32f, 0.78f, 0.70f)
            pfad(0.22f, 0.32f, 0.50f, 0.54f, 0.78f, 0.32f)
            kreis(0.70f, 0.40f, 0.035f, rot, true)
        }
        "musik" -> {
            strich(0.42f, 0.30f, 0.42f, 0.64f); strich(0.42f, 0.30f, 0.66f, 0.26f); strich(0.66f, 0.26f, 0.66f, 0.58f)
            kreis(0.36f, 0.66f, 0.06f, tinte, true); kreis(0.60f, 0.60f, 0.06f, rot, true)
        }
        "video" -> {
            rechteck(0.22f, 0.30f, 0.78f, 0.70f)
            pfad(0.44f, 0.40f, 0.60f, 0.50f, 0.44f, 0.60f, zu = true, farbe = rot, voll = true)
        }
        "browser" -> {
            kreis(0.50f, 0.50f, 0.26f)
            strich(0.24f, 0.50f, 0.76f, 0.50f)
            drawOval(tinte, p(0.39f, 0.24f), Size(0.22f * s, 0.52f * s), style = linie)
            kreis(0.50f, 0.24f, 0.035f, rot, true)
        }
        "notiz" -> {
            rechteck(0.28f, 0.24f, 0.72f, 0.76f)
            strich(0.36f, 0.38f, 0.64f, 0.38f); strich(0.36f, 0.50f, 0.64f, 0.50f); strich(0.36f, 0.62f, 0.54f, 0.62f, rot)
        }
        "rechner" -> {
            rechteck(0.28f, 0.22f, 0.72f, 0.78f)
            rechteck(0.36f, 0.30f, 0.64f, 0.40f, rot, voll = true)
            for (r in 0 until 3) for (c in 0 until 3) kreis(0.38f + c * 0.12f, 0.50f + r * 0.10f, 0.022f, tinte, true)
        }
        "ordner" -> {
            pfad(0.22f, 0.32f, 0.42f, 0.32f, 0.48f, 0.38f, 0.78f, 0.38f, 0.78f, 0.72f, 0.22f, 0.72f, zu = true)
            strich(0.22f, 0.46f, 0.78f, 0.46f, rot)
        }
        "laden" -> {
            pfad(0.26f, 0.38f, 0.74f, 0.38f, 0.70f, 0.76f, 0.30f, 0.76f, zu = true)
            pfad(0.40f, 0.38f, 0.40f, 0.30f, 0.46f, 0.24f, 0.54f, 0.24f, 0.60f, 0.30f, 0.60f, 0.38f)
            pfad(0.46f, 0.50f, 0.58f, 0.57f, 0.46f, 0.64f, zu = true, farbe = rot, voll = true)
        }
        "person" -> {
            kreis(0.50f, 0.38f, 0.10f)
            pfad(0.28f, 0.76f, 0.30f, 0.62f, 0.40f, 0.54f, 0.60f, 0.54f, 0.70f, 0.62f, 0.72f, 0.76f)
            kreis(0.50f, 0.38f, 0.03f, rot, true)
        }
        "wetter" -> {
            kreis(0.42f, 0.42f, 0.10f, rot, true)
            pfad(0.30f, 0.70f, 0.70f, 0.70f, 0.74f, 0.62f, 0.68f, 0.54f, 0.58f, 0.54f, 0.52f, 0.48f, 0.42f, 0.50f, 0.36f, 0.58f, 0.28f, 0.62f, zu = true)
        }
        "schule" -> {
            rechteck(0.24f, 0.26f, 0.76f, 0.74f)
            strich(0.24f, 0.40f, 0.76f, 0.40f); strich(0.41f, 0.26f, 0.41f, 0.74f); strich(0.59f, 0.26f, 0.59f, 0.74f)
            rechteck(0.59f, 0.40f, 0.76f, 0.57f, rot, voll = true)
        }
        "karte_geld" -> {
            rechteck(0.20f, 0.32f, 0.80f, 0.70f)
            rechteck(0.20f, 0.40f, 0.80f, 0.48f, tinte, voll = true)
            rechteck(0.28f, 0.56f, 0.40f, 0.62f, rot, voll = true)
        }
        "mikro" -> {
            drawRoundRect(tinte, p(0.42f, 0.22f), Size(0.16f * s, 0.32f * s), CornerRadius(0.08f * s), style = linie)
            pfad(0.32f, 0.46f, 0.34f, 0.58f, 0.50f, 0.64f, 0.66f, 0.58f, 0.68f, 0.46f)
            strich(0.50f, 0.64f, 0.50f, 0.76f, rot)
        }
        "puls" -> {
            pfad(0.20f, 0.52f, 0.36f, 0.52f, 0.42f, 0.36f, 0.50f, 0.68f, 0.58f, 0.44f, 0.62f, 0.52f, 0.80f, 0.52f)
            kreis(0.50f, 0.68f, 0.035f, rot, true)
        }
        "sprache" -> {
            rechteck(0.22f, 0.26f, 0.56f, 0.56f)
            rechteck(0.44f, 0.44f, 0.78f, 0.74f, rot)
        }
        "schloss" -> {
            rechteck(0.30f, 0.46f, 0.70f, 0.76f)
            pfad(0.38f, 0.46f, 0.38f, 0.34f, 0.44f, 0.26f, 0.56f, 0.26f, 0.62f, 0.34f, 0.62f, 0.46f)
            kreis(0.50f, 0.60f, 0.04f, rot, true)
        }
        "ki" -> {
            // vierzackiger Funke
            pfad(0.50f, 0.20f, 0.56f, 0.44f, 0.80f, 0.50f, 0.56f, 0.56f, 0.50f, 0.80f, 0.44f, 0.56f, 0.20f, 0.50f, 0.44f, 0.44f, zu = true)
            kreis(0.72f, 0.28f, 0.04f, rot, true)
        }
        "lernen" -> {
            rechteck(0.26f, 0.34f, 0.66f, 0.70f)
            pfad(0.34f, 0.34f, 0.34f, 0.26f, 0.74f, 0.26f, 0.74f, 0.62f, 0.66f, 0.62f)
            strich(0.34f, 0.52f, 0.58f, 0.52f, rot)
        }
        "quiz" -> {
            pfad(0.38f, 0.38f, 0.40f, 0.28f, 0.50f, 0.24f, 0.60f, 0.28f, 0.62f, 0.38f, 0.50f, 0.48f, 0.50f, 0.58f)
            kreis(0.50f, 0.70f, 0.04f, rot, true)
        }
        "launcher" -> {
            rechteck(0.26f, 0.26f, 0.46f, 0.46f); rechteck(0.54f, 0.26f, 0.74f, 0.46f)
            rechteck(0.26f, 0.54f, 0.46f, 0.74f); rechteck(0.54f, 0.54f, 0.74f, 0.74f, rot, voll = true)
        }
        "werkzeug" -> {
            strich(0.30f, 0.70f, 0.58f, 0.42f)
            pfad(0.54f, 0.30f, 0.62f, 0.24f, 0.72f, 0.26f, 0.66f, 0.34f, 0.68f, 0.40f, 0.74f, 0.38f, 0.76f, 0.46f, 0.68f, 0.50f, 0.58f, 0.46f)
            kreis(0.30f, 0.70f, 0.035f, rot, true)
        }
        "pinsel" -> {
            strich(0.66f, 0.26f, 0.42f, 0.56f)
            pfad(0.42f, 0.56f, 0.34f, 0.58f, 0.28f, 0.74f, 0.44f, 0.66f, 0.46f, 0.60f, zu = true, farbe = rot, voll = true)
        }
        "animation" -> {
            rechteck(0.22f, 0.22f, 0.54f, 0.54f); rechteck(0.34f, 0.34f, 0.66f, 0.66f)
            rechteck(0.46f, 0.46f, 0.78f, 0.78f, rot)
        }
        "tastatur" -> {
            rechteck(0.20f, 0.32f, 0.80f, 0.68f)
            for (c in 0 until 4) kreis(0.30f + c * 0.13f, 0.44f, 0.022f, tinte, true)
            strich(0.34f, 0.57f, 0.66f, 0.57f, rot)
        }
        "geste" -> {
            strich(0.76f, 0.30f, 0.76f, 0.70f)
            strich(0.70f, 0.50f, 0.30f, 0.50f, rot)
            pfad(0.40f, 0.40f, 0.30f, 0.50f, 0.40f, 0.60f)
        }
        "tippen" -> {
            kreis(0.50f, 0.40f, 0.16f)
            kreis(0.50f, 0.40f, 0.05f, rot, true)
            strich(0.50f, 0.56f, 0.50f, 0.76f)
        }
        "code" -> {
            pfad(0.38f, 0.32f, 0.24f, 0.50f, 0.38f, 0.68f)
            pfad(0.62f, 0.32f, 0.76f, 0.50f, 0.62f, 0.68f)
            strich(0.54f, 0.30f, 0.46f, 0.70f, rot)
        }
        "sozial" -> {
            kreis(0.40f, 0.40f, 0.08f); kreis(0.62f, 0.44f, 0.07f, rot)
            pfad(0.24f, 0.72f, 0.28f, 0.58f, 0.52f, 0.58f, 0.56f, 0.72f)
            pfad(0.56f, 0.60f, 0.74f, 0.60f, 0.78f, 0.72f)
        }
        "dokument" -> {
            pfad(0.30f, 0.22f, 0.58f, 0.22f, 0.70f, 0.34f, 0.70f, 0.78f, 0.30f, 0.78f, zu = true)
            strich(0.38f, 0.46f, 0.62f, 0.46f); strich(0.38f, 0.56f, 0.62f, 0.56f); strich(0.38f, 0.66f, 0.52f, 0.66f, rot)
        }
        "zeitung" -> {
            rechteck(0.22f, 0.28f, 0.78f, 0.72f)
            rechteck(0.30f, 0.36f, 0.48f, 0.52f, rot, voll = true)
            strich(0.56f, 0.38f, 0.70f, 0.38f); strich(0.56f, 0.48f, 0.70f, 0.48f); strich(0.30f, 0.62f, 0.70f, 0.62f)
        }
        "sim" -> {
            for (k in 0 until 4) strich(0.32f + k * 0.12f, 0.70f, 0.32f + k * 0.12f, 0.62f - k * 0.10f, if (k == 3) rot else tinte)
        }
        "ticket" -> {
            pfad(0.20f, 0.34f, 0.80f, 0.34f, 0.80f, 0.44f, 0.74f, 0.50f, 0.80f, 0.56f, 0.80f, 0.66f, 0.20f, 0.66f,
                 0.20f, 0.56f, 0.26f, 0.50f, 0.20f, 0.44f, zu = true)
            strich(0.56f, 0.38f, 0.56f, 0.62f, rot)
        }
        "drucker" -> {
            rechteck(0.22f, 0.40f, 0.78f, 0.64f)
            rechteck(0.34f, 0.24f, 0.66f, 0.40f)
            rechteck(0.34f, 0.56f, 0.66f, 0.76f)
            kreis(0.70f, 0.48f, 0.03f, rot, true)
        }
        "scan" -> {
            pfad(0.22f, 0.36f, 0.22f, 0.22f, 0.36f, 0.22f); pfad(0.64f, 0.22f, 0.78f, 0.22f, 0.78f, 0.36f)
            pfad(0.22f, 0.64f, 0.22f, 0.78f, 0.36f, 0.78f); pfad(0.64f, 0.78f, 0.78f, 0.78f, 0.78f, 0.64f)
            strich(0.28f, 0.50f, 0.72f, 0.50f, rot)
        }
        "geraet" -> {
            rechteck(0.34f, 0.20f, 0.66f, 0.80f)
            strich(0.44f, 0.70f, 0.56f, 0.70f, rot)
        }
        "info" -> {
            kreis(0.50f, 0.50f, 0.26f)
            strich(0.50f, 0.46f, 0.50f, 0.64f)
            kreis(0.50f, 0.36f, 0.035f, rot, true)
        }
        else -> drawRect(Color.Transparent, Offset.Zero, Size(s, s))
    }
}
