package de.atelierlauncher.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

// ============================================================================
//  Bastel-Ordner: ein normaler Ordner auf dem Handy (z. B. Dokumente/Launcher), den man
//  in "Eigene Dateien" sieht, am PC per USB bearbeiten oder in jeder Editor-App oeffnen kann.
//  Darin liegt der ganze eigene Code:
//      menue.js        Mein Menü
//      hintergrund.js  Design "Eigener Code"
//      alphabet.js     eigene Aktionen beim Wischen uebers Alphabet
//      LIESMICH.txt    alle Befehle erklaert
//      beispiele/      fertige Beispiele zum Abschauen
//  Der Launcher bekommt einmal die Erlaubnis fuer genau diesen Ordner (Android-Ordnerauswahl).
// ============================================================================
object BastelOrdner {
    private const val PREF = "bastelOrdner"
    private const val ROH = "application/octet-stream"     // Dateiname bleibt genau so (kein ".txt" angehaengt)

    fun uri(ctx: Context): Uri? =
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString(PREF, null)?.let { Uri.parse(it) }

    fun gesetzt(ctx: Context) = uri(ctx) != null

    /** Ordner merken (dauerhafte Erlaubnis) und Beispiele hineinlegen */
    fun setzen(ctx: Context, baum: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(baum,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putString(PREF, baum.toString()).apply()
        beispieleAnlegen(ctx)
    }

    fun vergessen(ctx: Context) {
        ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().remove(PREF).apply()
    }

    /** lesbarer Name des Ordners, z. B. "Documents/Launcher" */
    fun anzeigeName(ctx: Context): String = uri(ctx)?.let {
        try { DocumentsContract.getTreeDocumentId(it).substringAfter(':').ifBlank { "Hauptspeicher" } } catch (e: Exception) { "?" }
    } ?: "kein Ordner"

    // ---------------------------------------------------------------- Dateien finden
    private class LeseFehler : Exception()

    /** Kind-Dokument suchen. null = gibt es nicht; wirft LeseFehler, wenn der Ordner nicht lesbar ist */
    private fun kind(ctx: Context, baum: Uri, elternId: String, name: String): String? {
        val kinder = DocumentsContract.buildChildDocumentsUriUsingTree(baum, elternId)
        val c = try {
            ctx.contentResolver.query(kinder, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        } catch (e: Exception) { null } ?: throw LeseFehler()
        c.use { while (it.moveToNext()) if (it.getString(1) == name) return it.getString(0) }
        return null
    }

    /** Dokument fuer einen Pfad wie "beispiele/regen.js" (anlegen = fehlende Ordner/Datei erstellen) */
    private fun finde(ctx: Context, pfad: String, anlegen: Boolean): Uri? {
        val baum = uri(ctx) ?: return null
        var id = try { DocumentsContract.getTreeDocumentId(baum) } catch (e: Exception) { return null }
        val teile = pfad.split('/').filter { it.isNotBlank() }
        for ((i, teil) in teile.withIndex()) {
            val letzter = i == teile.lastIndex
            // Lesefehler -> NICHT neu anlegen (sonst entsteht "menue (1).js")
            val gefunden = try { kind(ctx, baum, id, teil) } catch (e: LeseFehler) { return null }
            id = gefunden ?: run {
                if (!anlegen) return null
                val eltern = DocumentsContract.buildDocumentUriUsingTree(baum, id)
                val neu = try {
                    DocumentsContract.createDocument(ctx.contentResolver, eltern,
                        if (letzter) (if (teil.endsWith(".txt")) "text/plain" else ROH) else DocumentsContract.Document.MIME_TYPE_DIR, teil)
                } catch (e: Exception) { null } ?: return null
                DocumentsContract.getDocumentId(neu)
            }
        }
        return DocumentsContract.buildDocumentUriUsingTree(baum, id)
    }

    fun lesen(ctx: Context, pfad: String): String? {
        val u = finde(ctx, pfad, anlegen = false) ?: return null
        return try { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().toString(Charsets.UTF_8) } } catch (e: Exception) { null }
    }

    /** direkt schreiben (nur aus [schreiber] heraus aufrufen, damit nie zwei gleichzeitig anlegen) */
    private fun schreibenJetzt(ctx: Context, pfad: String, text: String): Boolean {
        val u = finde(ctx, pfad, anlegen = true) ?: return false
        return try {
            ctx.contentResolver.openOutputStream(u, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } != null
        } catch (e: Exception) { false }
    }

    /** alle Schreibvorgaenge nacheinander auf einem Faden */
    private val schreiber = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "bastel-schreiber").apply { isDaemon = true } }
    private val haupt = android.os.Handler(android.os.Looper.getMainLooper())

    /** im Hintergrund schreiben, danach fertig(ok) auf dem Bildschirm-Faden */
    fun speichern(ctx: Context, pfad: String, text: String, fertig: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        schreiber.execute {
            val ok = try { schreibenJetzt(app, pfad, text) } catch (e: Exception) { false }
            haupt.post { fertig(ok) }
        }
    }

    fun gibtEs(ctx: Context, pfad: String) = finde(ctx, pfad, anlegen = false) != null

    /** Beispiele + Anleitung hineinlegen. Eigene Dateien werden NIE ueberschrieben (nur LIESMICH + beispiele/) */
    fun beispieleAnlegen(ctx: Context) {
        val app = ctx.applicationContext
        schreiber.execute {
            try {
                schreibenJetzt(app, "LIESMICH.txt", LIESMICH)
                BEISPIELE.forEach { (name, text) -> schreibenJetzt(app, "beispiele/$name", text) }
                // nur anlegen, wenn sicher fehlt (Lesefehler zaehlt nicht als "fehlt")
                fun fehlt(p: String): Boolean {
                    val baum = uri(app) ?: return false
                    val wurzel = try { DocumentsContract.getTreeDocumentId(baum) } catch (e: Exception) { return false }
                    return try { kind(app, baum, wurzel, p) == null } catch (e: LeseFehler) { false }
                }
                if (fehlt("menue.js")) schreibenJetzt(app, "menue.js", MENUE_BEISPIEL)
                if (fehlt("hintergrund.js")) schreibenJetzt(app, "hintergrund.js", HINTERGRUND_BEISPIEL)
                if (fehlt("alphabet.js")) schreibenJetzt(app, "alphabet.js", ALPHABET_BEISPIEL)
            } catch (e: Exception) { }
        }
    }
}

// ============================================================================
//  Anleitung und Beispiele (landen als Dateien im Bastel-Ordner)
// ============================================================================
const val LIESMICH = """EIGENER CODE IM LAUNCHER
========================

Alles ist JavaScript. Du kannst die Dateien in jeder App bearbeiten
(oder am PC per USB). Nach dem Speichern: zur Startseite zurück -
der Launcher liest die Dateien beim nächsten Anzeigen neu.

DATEIEN
  menue.js        Mein Menü (auf # zur Seite wischen)
  hintergrund.js  Design "Eigener Code" (Einstellungen -> Design)
  alphabet.js     eigene Aktionen beim Wischen übers Alphabet
  beispiele/      zum Abschauen - einfach Inhalt in die Dateien oben kopieren

-------------------------------------------------------------
MEIN MENÜ (menue.js) - läuft jedes Mal, wenn das Menü aufgeht
-------------------------------------------------------------
  ui.titel(text)                kleine Überschrift
  ui.text(text)                 Text
  ui.knopf(text, funktion)      Knopf
  ui.zeile(titel, unter, fn)    Zeile wie eine App (fn darf fehlen)
  ui.abstand()                  Platz
  ui.trenner()                  dünne Linie
  ui.eingabe(hinweis, fn)       Textfeld mit OK-Knopf. fn(text) bekommt den Text
                                (auch "Fertig" auf der Tastatur schickt ab)
  ui.schalter(text, an, fn)     Schalter (an = true/false). fn(neu) bekommt true/false
  ui.alle(sekunden, fn)         fn regelmäßig aufrufen, solange das Menü offen ist
                                (mindestens alle 5 s). Beispiel: ui.alle(10, function () { ui.neu() })
  ui.neu()                      Menü neu aufbauen (z. B. in einem Knopf)

-------------------------------------------------------------
ÜBERALL (menue.js und alphabet.js)
-------------------------------------------------------------
  app.oeffnen("paket.name")     App starten
  app.link("https://...")       Webseite / Link öffnen
  app.meldung("Text")           kurze Meldung unten
  app.liste()                   alle Apps: [{ name, paket }, ...]
  netz.holen("https://...")     Text einer Webseite oder API (JSON -> JSON.parse)
                                nur https, nicht ins Heimnetz, max. 1 MB, 10 s,
                                30 Abrufe pro Minute. Fehler -> ""
  handy.akku()                  0..100
  handy.zeit()                  Millisekunden (oder new Date())
  handy.nachrichten("paket")    neue Benachrichtigungen einer App: [{ titel, text, zeit }]
  handy.speichern(name, wert)   etwas merken (bleibt erhalten)
  handy.laden(name)             wieder holen (Text oder null)
  handy.loeschen(name)          vergessen (danach liefert handy.laden(name) null)

-------------------------------------------------------------
ÜBERALL (menue.js, alphabet.js und hintergrund.js)
-------------------------------------------------------------
  log(wert, wert, ...)          schreibt eine Zeile in die Konsole (Werte mit Leerzeichen
                                dazwischen, Objekte und Listen als JSON).
                                Anschauen: Einstellungen -> Bastler -> Konsole.
                                Fehler aus deinem Code landen dort auch.
                                Aus hintergrund.js höchstens 20 Zeilen pro Sekunde,
                                der Rest wird ausgelassen (male läuft ja 30x pro Sekunde).

-------------------------------------------------------------
HINTERGRUND (hintergrund.js) - function male(g) ca. 30x pro Sekunde
-------------------------------------------------------------
  g.breite, g.hoehe             Bildschirm in Pixeln
  g.zeit                        Millisekunden seit Start
  g.daumenY                     Höhe des Daumens am Alphabet, -1 = kein Daumen
  g.akku                        0..100
  g.tippX, g.tippY              wo zuletzt angetippt wurde (Pixel). -1, wenn nichts
                                angetippt wurde oder es länger als 0,6 s her ist.
                                tippX geht dort, wo nichts anderes liegt (nicht auf
                                Uhr, Alphabet, Apps ...) - nur auf freiem Hintergrund.
  g.dichte                      Pixel pro dp: 10 * g.dichte ist auf jedem Handy gleich groß

  g.kreis(x, y, radius, farbe)              gefüllter Kreis
  g.ring(x, y, radius, dicke, farbe)        Kreis-Linie (nur der Rand)
  g.rechteck(x, y, breite, hoehe, farbe)    x, y = linke obere Ecke
  g.verlauf(x, y, breite, hoehe, farbeOben, farbeUnten)
                                            Rechteck mit Farbverlauf von oben nach unten
  g.linie(x1, y1, x2, y2, dicke, farbe)
  g.bogen(x, y, radius, startGrad, winkelGrad, dicke, farbe)
                                            Stück eines Kreises. 0 Grad = rechts (3 Uhr),
                                            -90 = oben, es geht im Uhrzeigersinn.
                                            winkelGrad 360 = ganzer Kreis
  g.pfad([x1, y1, x2, y2, ...], farbe, gefuellt, dicke)
                                            Linienzug durch alle Punkte (mind. 2 Punkte).
                                            gefuellt = true: Fläche füllen (dicke egal)
  g.text(text, x, y, groesse, farbe, ausrichtung)
                                            y = Grundlinie (Unterkante der Buchstaben),
                                            groesse in Pixeln (z. B. 16 * g.dichte),
                                            ausrichtung "links" (Standard), "mitte", "rechts"
  Farben: "#RRGGBB" oder mit Durchsichtigkeit "#AARRGGBB" (00 = unsichtbar, FF = voll)
  Reihenfolge: was später gemalt wird, liegt oben.
  Tipp: Variablen außerhalb von male() bleiben zwischen den Bildern erhalten.

-------------------------------------------------------------
ALPHABET (alphabet.js)
-------------------------------------------------------------
  function wisch(w) wird nach jedem Wisch übers Alphabet aufgerufen:
    w.start   Buchstabe, wo der Finger angefangen hat ("#", "A" ... "Z")
    w.ende    Buchstabe, wo er losgelassen hat
    w.links   true, wenn auf # zur Seite gewischt wurde

TESTEN UND FEHLER FINDEN
  Im Launcher: Einstellungen -> Bastler -> Code bearbeiten.
    Zeilennummern links helfen, die Zeile aus einer Fehlermeldung zu finden.
    "Testen" führt den Code einmal aus (wie echt) und sagt "OK" oder den Fehler:
      menue.js        baut das Menü einmal und zählt die Elemente
      hintergrund.js  ruft male(g) einmal auf
      alphabet.js     prüft, ob es function wisch(w) gibt
    "Speichern" prüft den Code vorher. Bei einem Tippfehler wird trotzdem gespeichert,
    aber du siehst gleich, in welcher Zeile er steckt.
  Einstellungen -> Bastler -> Konsole: alles aus log(...) und alle Fehler.
    "Alles neu laden" liest deinen Code neu und startet ihn frisch.

SPRACHE
  JavaScript wie ES5 plus einiges aus ES6 (let, const, Pfeilfunktionen).
  Keine Backtick-Texte (Vorlagen mit Dollar-Klammer) - statt dessen "a" + b + "c".
  menue.js startet bei jedem Öffnen frisch - Dauerhaftes mit handy.speichern.

GRENZEN
  Kein Zugriff auf Dateien, Java oder Android-Innereien - nur auf die Befehle oben.
  Endlosschleifen werden abgebrochen, ebenso zu viel Speicher.
  Hängt ein Skript 3× fest, wird es gesperrt, bis du es änderst und speicherst.
"""

const val ALPHABET_BEISPIEL = """// Eigene Aktionen beim Wischen übers Alphabet.
// w.start / w.ende = Buchstaben, w.links = auf # zur Seite gewischt

function wisch(w) {
    // von S nach M wischen und dort loslassen: Spotify öffnen
    if (w.start == "S" && w.ende == "M") app.oeffnen("com.spotify.music")

    // von K nach # : Kamera
    if (w.start == "K" && w.ende == "#") app.oeffnen("com.sec.android.app.camera")
}
"""

val BEISPIELE = listOf(
    // ---------------- Hintergruende
    "hintergrund-blasen.js" to HINTERGRUND_BEISPIEL,
    "hintergrund-regen.js" to """// Digitaler Regen: Linien fallen rechts herunter, unter dem Daumen schneller und heller.
var tropfen = []
for (var i = 0; i < 40; i++) tropfen.push({ x: Math.random(), y: Math.random(), v: 0.3 + Math.random() * 0.7 })

function male(g) {
    for (var i = 0; i < tropfen.length; i++) {
        var t = tropfen[i]
        var x = g.breite - t.x * g.breite * 0.35          // nur im rechten Drittel
        var y = t.y * g.hoehe
        var nah = g.daumenY >= 0 ? Math.exp(-Math.pow((y - g.daumenY) / 150, 2)) : 0
        t.y += 0.004 * t.v * (1 + 3 * nah)
        if (t.y > 1.05) { t.y = -0.05; t.x = Math.random() }
        var a = Math.floor(80 + 175 * Math.max(nah, 0.3 * t.v)).toString(16)
        if (a.length < 2) a = "0" + a
        g.linie(x, y, x, y + 40 + 60 * t.v, 2, "#" + a + "5CFF8F")
    }
}
""",
    "hintergrund-equalizer.js" to """// Equalizer-Balken am rechten Rand. Der Daumen "spielt" die Balken, der Akku bestimmt die Grundhöhe.
function male(g) {
    var n = 28
    var h = g.hoehe / n
    for (var i = 0; i < n; i++) {
        var y = i * h
        var grund = 20 + g.akku * 0.6
        var welle = 25 * (1 + Math.sin(g.zeit / 300 + i * 0.7))
        var daumen = g.daumenY >= 0 ? 160 * Math.exp(-Math.pow((y - g.daumenY) / 90, 2)) : 0
        var laenge = grund + welle + daumen
        g.rechteck(g.breite - laenge, y + 2, laenge, h - 4, i % 2 == 0 ? "#AAFF5FB4" : "#AAB57BFF")
    }
}
""",
    "hintergrund-puls.js" to """// Ringe, die vom rechten Rand aus pulsieren. Wo der Daumen ist, entsteht ein eigener Ring.
function male(g) {
    for (var k = 0; k < 5; k++) {
        var p = ((g.zeit / 2500) + k / 5) % 1
        var r = p * g.breite * 0.9
        var a = Math.floor((1 - p) * 120).toString(16); if (a.length < 2) a = "0" + a
        ring(g, g.breite, g.hoehe / 2, r, "#" + a + "4FE3FF")
    }
    if (g.daumenY >= 0) ring(g, g.breite, g.daumenY, 60 + 20 * Math.sin(g.zeit / 120), "#CCFFFFFF")
}

// Ring aus vielen kurzen Linien
function ring(g, cx, cy, r, farbe) {
    var n = 48
    for (var i = 0; i < n; i++) {
        var a1 = i / n * Math.PI * 2, a2 = (i + 1) / n * Math.PI * 2
        g.linie(cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, cx + Math.cos(a2) * r, cy + Math.sin(a2) * r, 3, farbe)
    }
}
""",
    "hintergrund-uhr.js" to """// Analoge Uhr am rechten Rand: Ring, Striche, Zeiger, Sekunden-Bogen und Datum.
// Uhr antippen: ein Ring blitzt auf.
var blitz = 0

function male(g) {
    var d = g.dichte
    var r = 70 * d                          // Radius: auf jedem Handy gleich groß
    var mx = g.breite - r - 24 * d          // Mitte der Uhr
    var my = g.hoehe * 0.3
    var jetzt = new Date()

    g.kreis(mx, my, r, "#55000000")
    g.ring(mx, my, r, 3 * d, "#CCFFFFFF")
    for (var i = 0; i < 12; i++) {
        var w = i / 12 * Math.PI * 2
        var innen = i % 3 == 0 ? r * 0.76 : r * 0.86
        g.linie(mx + Math.sin(w) * innen, my - Math.cos(w) * innen,
                mx + Math.sin(w) * r * 0.94, my - Math.cos(w) * r * 0.94, 2 * d, "#AAFFFFFF")
    }

    var s = jetzt.getSeconds() + jetzt.getMilliseconds() / 1000
    var m = jetzt.getMinutes() + s / 60
    var h = (jetzt.getHours() % 12) + m / 60
    zeiger(g, mx, my, h / 12, r * 0.5, 5 * d, "#FFFFFFFF")
    zeiger(g, mx, my, m / 60, r * 0.75, 3 * d, "#FFFFFFFF")
    zeiger(g, mx, my, s / 60, r * 0.85, 1.5 * d, "#FFFF5252")
    g.kreis(mx, my, 4 * d, "#FFFF5252")

    // Sekunden zusätzlich als Bogen außen herum (-90 Grad = oben)
    g.bogen(mx, my, r + 8 * d, -90, s / 60 * 360, 2 * d, "#88FF5252")

    var tage = ["So", "Mo", "Di", "Mi", "Do", "Fr", "Sa"]
    var datum = tage[jetzt.getDay()] + " " + jetzt.getDate() + "." + (jetzt.getMonth() + 1) + "."
    g.text(datum, mx, my + r + 34 * d, 18 * d, "#CCFFFFFF", "mitte")

    // Uhr angetippt? Dann blitzt ein Ring auf und wird langsam unsichtbar
    if (g.tippX >= 0 && Math.abs(g.tippX - mx) < r && Math.abs(g.tippY - my) < r) {
        if (blitz <= 0) log("Uhr angetippt um", jetzt.toLocaleTimeString())
        blitz = 1
    }
    if (blitz > 0) {
        g.ring(mx, my, r + 24 * d * (1 - blitz), 4 * d, durchsichtig(blitz, "FFFFFF"))
        blitz -= 0.04
    }
}

function zeiger(g, x, y, anteil, laenge, dicke, farbe) {
    var w = anteil * Math.PI * 2
    g.linie(x, y, x + Math.sin(w) * laenge, y - Math.cos(w) * laenge, dicke, farbe)
}

// a = 0 (unsichtbar) bis 1 (voll), rgb wie "FFFFFF"
function durchsichtig(a, rgb) {
    var h = Math.floor(Math.max(0, Math.min(1, a)) * 255).toString(16)
    if (h.length < 2) h = "0" + h
    return "#" + h + rgb
}
""",
    // ---------------- Mein Menü
    "menue-start.js" to MENUE_BEISPIEL,
    "menue-notizen.js" to """// Notizen: oben eintippen und OK. Antippen = löschen. Gespeichert als JSON.
var notizen = JSON.parse(handy.laden("notizen") || "[]")
var zeitZeigen = handy.laden("notizZeit") != "nein"

function sichern() { handy.speichern("notizen", JSON.stringify(notizen)) }

function uhrzeit(ms) {
    var d = new Date(ms)
    var min = d.getMinutes() < 10 ? "0" + d.getMinutes() : "" + d.getMinutes()
    return d.getDate() + "." + (d.getMonth() + 1) + ". " + d.getHours() + ":" + min
}

ui.titel("Notizen")
ui.eingabe("Neue Notiz …", function (text) {
    if (text.trim() == "") return
    notizen.unshift({ text: text, zeit: handy.zeit() })
    sichern()
    log("Neue Notiz:", text)
    ui.neu()
})
ui.schalter("Zeit anzeigen", zeitZeigen, function (an) {
    handy.speichern("notizZeit", an ? "ja" : "nein")
    ui.neu()
})
ui.trenner()

if (notizen.length == 0) ui.text("Noch keine Notizen")
notizen.forEach(function (n, i) {
    ui.zeile(n.text, (zeitZeigen ? uhrzeit(n.zeit) + "  ·  " : "") + "antippen = löschen", function () {
        notizen.splice(i, 1)
        sichern()
        ui.neu()
    })
})

if (notizen.length > 0) {
    ui.abstand()
    ui.knopf("Alle löschen", function () { handy.loeschen("notizen"); ui.neu() })
}
""",
    "menue-wetter.js" to """// Wetter von wttr.in (kostenlos, ohne Konto). Ort unten anpassen.
var ort = "Berlin"
ui.titel("Wetter in " + ort)
var roh = netz.holen("https://wttr.in/" + ort + "?format=j1")
if (roh == "") {
    ui.text("Keine Verbindung")
} else {
    var d = JSON.parse(roh)
    var jetzt = d.current_condition[0]
    ui.text(jetzt.temp_C + " °C, gefühlt " + jetzt.FeelsLikeC + " °C")
    ui.text("Wind " + jetzt.windspeedKmph + " km/h")
    ui.abstand()
    ui.titel("Nächste Tage")
    d.weather.forEach(function (tag) {
        ui.zeile(tag.date, tag.mintempC + " bis " + tag.maxtempC + " °C")
    })
}
ui.knopf("Aktualisieren", function () { ui.neu() })
""",
    "menue-apps.js" to """// Eigene Schnellstart-Liste + Suche nach Apps
var meine = [
    ["Kamera", "com.sec.android.app.camera"],
    ["Rechner", "com.sec.android.app.popupcalculator"],
    ["Einstellungen", "com.android.settings"],
]
ui.titel("Schnellstart")
meine.forEach(function (m) {
    ui.knopf(m[0], function () { app.oeffnen(m[1]) })
})

ui.abstand()
ui.titel("Alle Apps mit 'Google'")
app.liste().filter(function (a) { return a.name.indexOf("Google") >= 0 })
    .forEach(function (a) { ui.zeile(a.name, a.paket, function () { app.oeffnen(a.paket) }) })
""",
    "menue-aufgaben.js" to """// Kleine Aufgabenliste: antippen = erledigt / wieder offen. Wird gespeichert.
var aufgaben = ["Hausaufgaben", "Sport", "Zimmer aufräumen", "Wasser trinken"]
var fertig = JSON.parse(handy.laden("fertig") || "{}")

ui.titel("Heute")
aufgaben.forEach(function (a) {
    var erledigt = fertig[a] == true
    ui.zeile((erledigt ? "✓ " : "○ ") + a, erledigt ? "erledigt" : "", function () {
        fertig[a] = !erledigt
        handy.speichern("fertig", JSON.stringify(fertig))
        ui.neu()
    })
})
ui.abstand()
ui.knopf("Alles zurücksetzen", function () { handy.speichern("fertig", "{}"); ui.neu() })
""",
    "alphabet-beispiel.js" to ALPHABET_BEISPIEL,
)
