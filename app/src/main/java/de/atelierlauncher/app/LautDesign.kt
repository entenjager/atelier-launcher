package de.atelierlauncher.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================================
//  Aussehen des eigenen Lautstaerke-Reglers. NUR das Zeichnen - Bedienung, Groesse und Verhalten
//  bleiben genau gleich (die stecken in LautRegler / LautDienst).
// ============================================================================
object LautDesign {
    val ARTEN = listOf(
        "pille" to "Pille", "glas" to "Glas", "welle" to "Welle",
        "aurora" to "Aurora", "blasen" to "Blasen", "neon" to "Neon", "sterne" to "Nacht",
        "kristall" to "Kristall", "abend" to "Abend",
        "swiss" to "Swiss", "linie" to "Linie", "segmente" to "Segmente", "geld" to "Banknote", "liquid" to "Liquid Glass", "skizze" to "Gezeichnet", "riss" to "Kintsugi", "sw" to "Schwarz & Weiß",
    )
    /** Breite der Kapsel (dp) fuer den unscharfen Hintergrund - null: dieses Design hat keine einfache Kapsel */
    fun blurBreite(art: String): Float? = when (art) {
        "sw" -> 40f; "pille" -> 46f; "glas", "welle" -> 48f; "liquid" -> 50f; "segmente" -> 44f
        "aurora", "blasen", "neon", "sterne", "kristall", "abend" -> 48f
        else -> null
    }
    fun blurAn(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("lautBlur", false)
    fun blurStaerke(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getInt("lautBlurStaerke", 24)

    fun art(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE).getString("lautDesign", "pille") ?: "pille"

    /** Farben aus dem Theme (einmal beim Einblenden gelesen, nicht bei jedem Bild) */
    class Farben(val akzent: Int, val welle: Int, val dunkel: Boolean, val rot: Int)

    fun farben(c: Context): Farben {
        val p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        val t = try { org.json.JSONObject(p.getString("thema", "{}") ?: "{}") } catch (e: Exception) { org.json.JSONObject() }
        val dunkel = Hell.dunkel(c)
        val eigen = t.optInt("akzent", 0)
        val akzent = if (eigen != 0) eigen or 0xFF000000.toInt()
                     else if (Build.VERSION.SDK_INT >= 31) c.getColor(android.R.color.system_accent1_300) else 0xFF83D5C5.toInt()
        return Farben(akzent, wellenFarbe(t.optInt("welle", 0), dunkel), dunkel, if (eigen != 0) akzent else 0xFFE2001A.toInt())
    }

    private val schrift: Typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)

    /** Zeichnet den Regler. b/h = Fenstergroesse, stand 0..1, zone = Grenze des Feinbereichs */
    class Maler(private val d: Float) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = schrift }
        private val schattenP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x55000000
            maskFilter = android.graphics.BlurMaskFilter(6f * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        private val r = RectF()
        private val r2 = RectF()
        private val bogen = RectF()
        private val form = Path()
        private val welle = Path()
        private val lautPfad = Path()

        fun malen(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, art: String, f: Farben) {
            when (art) {
                "glas" -> glas(c, b, h, stand, zone, f)
                "welle" -> wellig(c, b, h, stand, zone, f)
                "swiss" -> swiss(c, b, h, stand, zone, f)
                "geld" -> geld(c, b, h, stand, zone, f)
                "liquid" -> liquid(c, b, h, stand, zone, f)
                "skizze" -> skizze(c, b, h, stand, zone)
                "riss" -> kintsugi(c, b, h, stand, zone)
                "sw" -> schwarzWeiss(c, b, h, stand, f)
                "linie" -> linie(c, b, h, stand, zone, f)
                "segmente" -> segmente(c, b, h, stand, zone, f)
                "aurora" -> aurora(c, b, h, stand, zone)
                "blasen" -> blasen(c, b, h, stand, zone, f)
                "neon" -> neon(c, b, h, stand, zone, f)
                "sterne" -> sterne(c, b, h, stand, zone)
                "kristall" -> kristall(c, b, h, stand, zone, f)
                "abend" -> abend(c, b, h, stand, zone)
                else -> pille(c, b, h, stand, zone)
            }
        }

        private fun prozent(stand: Float) = (stand * 100f).roundToInt().toString()
        private fun alpha(farbe: Int, a: Float) = (farbe and 0x00FFFFFF) or ((a * 255f).roundToInt().coerceIn(0, 255) shl 24)

        /** weicher Schatten unter einer Form - damit der Regler auf hellen und dunklen Apps gleich gut steht */
        private fun schatten(c: Canvas, rr: RectF, rad: Float) {
            if (durchsicht < 1f) return                 // unscharfer Hintergrund: kein dunkler Schatten darunter
            c.save(); c.translate(0f, 2f * d); c.drawRoundRect(rr, rad, rad, schattenP); c.restore()
        }

        private fun fuellen(farbe: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = farbe }
        /** Hintergrund der Leiste ist unscharf (Milchglas): Grundflaeche durchsichtiger, damit man es sieht */
        var durchsicht = 1f
        private fun grundFuellen(farbe: Int) = fuellen(if (durchsicht >= 1f) farbe else alpha(farbe, ((farbe ushr 24) / 255f) * durchsicht))
        private fun strich(farbe: Int, breite: Float) { s.shader = null; s.color = farbe; s.strokeWidth = breite }

        /** Lautsprecher-Zeichen (24er Raster): je lauter, desto mehr Bogen; 0 = durchgestrichen */
        private fun lautsprecher(c: Canvas, cx: Float, cy: Float, g: Float, farbe: Int, stand: Float) {
            val k = g / 24f
            c.save(); c.translate(cx - 12f * k, cy - 12f * k); c.scale(k, k)
            lautPfad.reset()
            lautPfad.moveTo(3f, 9.5f); lautPfad.lineTo(7f, 9.5f); lautPfad.lineTo(11.5f, 5f); lautPfad.lineTo(11.5f, 19f)
            lautPfad.lineTo(7f, 14.5f); lautPfad.lineTo(3f, 14.5f); lautPfad.close()
            fuellen(farbe); c.drawPath(lautPfad, p)
            strich(farbe, 1.9f)
            if (stand <= 0.001f) {
                c.drawLine(15f, 9f, 21f, 15f, s); c.drawLine(21f, 9f, 15f, 15f, s)
            } else {
                bogen.set(11.5f - 4.2f, 12f - 4.2f, 11.5f + 4.2f, 12f + 4.2f); c.drawArc(bogen, -48f, 96f, false, s)
                if (stand > 0.45f) { bogen.set(11.5f - 7.6f, 12f - 7.6f, 11.5f + 7.6f, 12f + 7.6f); c.drawArc(bogen, -48f, 96f, false, s) }
            }
            c.restore()
        }

        private fun wellenPfad(rr: RectF, oben: Float, amp: Float, phase: Double): Path {
            welle.reset(); welle.moveTo(rr.left, rr.bottom)
            var x = rr.left
            while (x <= rr.right + 1f) {
                welle.lineTo(x, oben + (amp * sin(2 * PI * ((x - rr.left) / rr.width()) + phase)).toFloat())
                x += 3f * d
            }
            welle.lineTo(rr.right, rr.bottom); welle.close()
            return welle
        }

        /** kleine Kerbe innen rechts: hier beginnt der Feinbereich (Farbe passt sich an, ob gefuellt oder nicht) */
        private fun kerbe(c: Canvas, rr: RectF, zone: Float, oben: Float) {
            val zy = rr.bottom - zone * rr.height()
            strich(if (zy > oben) 0x66000000 else 0x66FFFFFF, 1.6f * d)
            c.drawLine(rr.right - 9f * d, zy, rr.right - 5f * d, zy, s)
        }

        // ---- Pille: dunkle Kapsel, weisse Fuellung mit sanfter Welle, Lautsprecher unten
        /** Schwarz & Weiss: runde Kapsel, nur Grund und Fuellung - keine Zahl, kein Zeichen */
        private fun schwarzWeiss(c: Canvas, b: Float, h: Float, stand: Float, f: Farben) {
            val pad = 10f * d; val breite = 40f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = breite / 2f
            val grund = if (f.dunkel) Color.BLACK else Color.WHITE
            val voll = if (f.dunkel) Color.WHITE else Color.BLACK
            grundFuellen(grund); c.drawRoundRect(r, rad, rad, p)
            form.reset(); form.addRoundRect(r, rad, rad, Path.Direction.CW)
            if (stand > 0.001f) {
                c.save(); c.clipPath(form)
                fuellen(voll); c.drawRect(r.left, r.bottom - stand * r.height(), r.right, r.bottom, p)
                c.restore()
            }
            strich(voll, 1f * d); c.drawRoundRect(r, rad, rad, s)
        }

        private fun pille(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            val pad = 10f * d; val breite = 46f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = breite / 2f
            schatten(c, r, rad)
            grundFuellen(0xEB17191D.toInt()); c.drawRoundRect(r, rad, rad, p)
            form.reset(); form.addRoundRect(r, rad, rad, Path.Direction.CW)
            val oben = r.bottom - stand * r.height()
            c.save(); c.clipPath(form)
            if (stand > 0.001f) {
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, oben, 0f, r.bottom, 0xFFFFFFFF.toInt(), 0xFFE6E8EC.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(wellenPfad(r, oben, 1.8f * d, 1.9), p); p.shader = null
                c.save(); c.translate(0f, 4f * d); fuellen(0x0F000000); c.drawPath(wellenPfad(r, oben, 1.8f * d, 1.9), p); c.restore()
            }
            c.restore()
            strich(0x1FFFFFFF, 1f * d); c.drawRoundRect(r, rad, rad, s)
            kerbe(c, r, zone, oben)
            val iy = r.bottom - 22f * d
            lautsprecher(c, r.centerX(), iy, 20f * d, if (iy > oben) 0xCC17191D.toInt() else 0xE6FFFFFF.toInt(), stand)
        }

        // ---- Glas: Milchglas mit Glanz, Fuellung als eigene Kapsel im Akzent-Verlauf, Zahl oben
        private fun glas(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 10f * d; val breite = 48f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = breite / 2f
            schatten(c, r, rad)
            grundFuellen(0xB31C222B.toInt()); c.drawRoundRect(r, rad, rad, p)
            p.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.35f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p); p.shader = null
            val innen = 5f * d
            r2.set(r.left + innen, r.top + innen, r.right - innen, r.bottom - innen)
            val ir = r2.width() / 2f
            val oben = (r2.bottom - stand * r2.height()).coerceAtMost(r2.bottom - 2 * ir)
            if (stand > 0.001f) {
                val hell = mischen(f.akzent, Color.WHITE, 0.5f)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, oben, 0f, r2.bottom, hell, f.akzent or 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRoundRect(r2.left, oben, r2.right, r2.bottom, ir, ir, p); p.shader = null
                if (r2.bottom - ir - (oben + ir) > 4f * d) {
                    strich(0x66FFFFFF, 2f * d); c.drawLine(r2.left + 7f * d, oben + ir, r2.left + 7f * d, r2.bottom - ir, s)
                }
            }
            strich(0x40FFFFFF, 1f * d); c.drawRoundRect(r, rad, rad, s)
            fuellen(0x99FFFFFF.toInt()); c.drawCircle(r.right - 3.5f * d, r2.bottom - zone * r2.height(), 1.6f * d, p)
            t.textSize = 12f * d; t.color = 0xF2FFFFFF.toInt(); t.textAlign = Paint.Align.CENTER
            c.drawText(prozent(stand), r.centerX(), r.top + 24f * d, t)
            lautsprecher(c, r.centerX(), r.bottom - 24f * d, 19f * d, 0xF2FFFFFF.toInt(), stand)
        }

        // ---- Welle: zwei Wellen uebereinander in der Wellenfarbe, Schaumlinien, Lautsprecher
        private fun wellig(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 10f * d; val breite = 48f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = breite / 2f
            schatten(c, r, rad)
            grundFuellen(0xEB0E1418.toInt()); c.drawRoundRect(r, rad, rad, p)
            form.reset(); form.addRoundRect(r, rad, rad, Path.Direction.CW)
            val oben = r.bottom - stand * r.height()
            val wc = f.welle
            c.save(); c.clipPath(form)
            if (stand > 0.001f) {
                fuellen(alpha(wc, 0.35f)); c.drawPath(wellenPfad(r, oben - 3f * d, 4f * d, 0.6), p)
                fuellen(alpha(wc, 0.92f)); c.drawPath(wellenPfad(r, oben, 4f * d, 2.3), p)
                for (k in 1..2) {
                    form.reset()
                    var x = r.left; var erst = true
                    while (x <= r.right + 1f) {
                        val y = oben - k * 5f * d + (4f * d * sin(2 * PI * ((x - r.left) / r.width()) + 2.3 + k * 0.4)).toFloat()
                        if (erst) { form.moveTo(x, y); erst = false } else form.lineTo(x, y)
                        x += 3f * d
                    }
                    strich(alpha(wc, if (k == 1) 0.5f else 0.22f), (if (k == 1) 1.4f else 1f) * d)
                    c.drawPath(form, s)
                }
            }
            c.restore()
            strich(0x1FFFFFFF, 1f * d); c.drawRoundRect(r, rad, rad, s)
            kerbe(c, r, zone, oben)
            val iy = r.bottom - 22f * d
            lautsprecher(c, r.centerX(), iy, 20f * d, if (iy > oben) 0xCC0E1418.toInt() else 0xE6FFFFFF.toInt(), stand)
        }

        // ---- Swiss: Karte in Papier/Schwarz, grosse Zahl, roter Block mit Rahmen, Skala, Feinbereich als dicke Linie
        private fun swiss(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 10f * d; val breite = 40f * d
            val l = b - breite - 6f * d
            val grund = if (f.dunkel) 0xFF0B0B0C.toInt() else 0xFFFFFFFF.toInt()
            val tinte = if (f.dunkel) Color.WHITE else 0xFF111111.toInt()
            r2.set(l - 16f * d, pad, b - 6f * d, h - pad)
            schatten(c, r2, 0f)
            fuellen(grund); c.drawRect(r2, p)
            r.set(l, pad + 40f * d, l + breite - 8f * d, h - pad - 8f * d)
            fuellen(f.rot or 0xFF000000.toInt()); c.drawRect(r.left, r.bottom - stand * r.height(), r.right, r.bottom, p)
            s.strokeCap = Paint.Cap.BUTT
            strich(tinte, 1f * d); c.drawRect(r, s)
            for (i in 0..20) {
                val y = r.bottom - i / 20f * r.height()
                val lang = (if (i % 5 == 0) 7f else 3.5f) * d
                c.drawLine(r.right + 3f * d, y, r.right + 3f * d + lang, y, s)
            }
            strich(tinte, 2f * d)
            val zy = r.bottom - zone * r.height()
            c.drawLine(r.left - 4f * d, zy, r.right, zy, s)
            s.strokeCap = Paint.Cap.ROUND
            t.textAlign = Paint.Align.LEFT
            t.textSize = 22f * d; t.color = tinte
            c.drawText(prozent(stand), r2.left + 6f * d, pad + 28f * d, t)
            t.textSize = 7f * d; t.color = alpha(tinte, 0.55f); t.letterSpacing = 0.12f
            c.drawText("LAUT", r2.left + 6f * d, pad + 36f * d, t)
            t.letterSpacing = 0f; t.textAlign = Paint.Align.CENTER
        }

        // ---- Banknote: Creme-Papier mit Doppelrahmen, Fuellung aus verschlungenen gruenen Linien (Guilloche),
        //      Zahl in Serifen oben, Feinbereich als kleine Raute
        private val serif = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER
        }
        private val feld = RectF()
        private fun geld(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 10f * d; val breite = 46f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val papier = Geld.papier(f.dunkel); val gruen = Geld.gruen(f.dunkel); val tinte = Geld.tinte(f.dunkel)
            val rad = 6f * d
            schatten(c, r, rad)
            fuellen(papier); c.drawRoundRect(r, rad, rad, p)
            // alter Pullover als Grund
            p.shader = android.graphics.BitmapShader(Geld.strick(d, f.dunkel), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            c.drawRoundRect(r, rad, rad, p); p.shader = null
            s.strokeCap = Paint.Cap.BUTT
            strich(alpha(gruen, 0.9f), 1.3f * d); c.drawRoundRect(r, rad, rad, s)
            r2.set(r.left + 4f * d, r.top + 4f * d, r.right - 4f * d, r.bottom - 4f * d)
            strich(alpha(gruen, 0.5f), 0.7f * d); c.drawRoundRect(r2, rad * 0.6f, rad * 0.6f, s)
            // Feld fuer die Fuellung (zwischen Zahl oben und Lautsprecher unten)
            feld.set(r.left + 9f * d, r.top + 38f * d, r.right - 9f * d, r.bottom - 34f * d)
            val oben = feld.bottom - stand * feld.height()
            if (stand > 0.001f) {
                c.save(); c.clipRect(feld.left, oben, feld.right, feld.bottom)
                fuellen(alpha(gruen, 0.16f)); c.drawRect(feld, p)
                // verschlungene Linien wie im Schein: fuenf phasenverschobene Wellen
                val cx = feld.centerX(); val amp = feld.width() / 2f - 1.5f * d; val per = 18f * d
                strich(alpha(gruen, 0.85f), 0.8f * d)
                for (k in 0 until 5) {
                    welle.reset(); var y = feld.top
                    val ph = k * 2.0 * PI / 5
                    welle.moveTo(cx + (amp * sin(ph)).toFloat(), y)
                    while (y <= feld.bottom + 2f * d) { welle.lineTo(cx + (amp * sin(2 * PI * y / per + ph)).toFloat(), y); y += 2f * d }
                    c.drawPath(welle, s)
                }
                c.restore()
                strich(gruen or 0xFF000000.toInt(), 1.6f * d); c.drawLine(feld.left - 2f * d, oben, feld.right + 2f * d, oben, s)
            }
            strich(alpha(gruen, 0.6f), 0.7f * d); c.drawRect(feld, s)
            // Feinbereich: kleine Raute am Rand
            val zy = feld.bottom - zone * feld.height()
            val k = 3f * d; val rx = feld.right + 3.5f * d
            form.reset(); form.moveTo(rx, zy - k); form.lineTo(rx + k, zy); form.lineTo(rx, zy + k); form.lineTo(rx - k, zy); form.close()
            fuellen(gruen or 0xFF000000.toInt()); c.drawPath(form, p)
            s.strokeCap = Paint.Cap.ROUND
            // Zahl in Serifen
            serif.textSize = 17f * d; serif.color = tinte
            c.drawText(prozent(stand), r.centerX(), r.top + 28f * d, serif)
            lautsprecher(c, r.centerX(), r.bottom - 19f * d, 16f * d, gruen or 0xFF000000.toInt(), stand)
        }

        // ---- Liquid Glass: Glaskapsel mit Glanzstreifen und hellem Saum, darin eine leuchtende Fluessigkeit
        //      (Akzentfarbe) mit Meniskus und kleinen Blasen
        private fun liquid(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            // wie die Lautstaerke beim iPhone (iOS 26): breite Glas-Pille, weisse Fuellung von unten,
            // Lautsprecher unten (grau auf Weiss), haarfeiner heller Glasrand
            val pad = 10f * d; val breite = 50f * d          // Fenster ist 64 dp breit
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = 22f * d
            schatten(c, r, rad)
            // Glas: dunkel-milchig (lesbar auf jeder App), oben ein Hauch heller
            grundFuellen(0x9E2A2A2E.toInt()); c.drawRoundRect(r, rad, rad, p)
            p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0x22FFFFFF, 0x06FFFFFF, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, rad, rad, p); p.shader = null
            // weisse Fuellung, an der Pille abgeschnitten
            val oben = r.bottom - stand * r.height()
            form.reset(); form.addRoundRect(r, rad, rad, Path.Direction.CW)
            if (stand > 0.001f) {
                c.save(); c.clipPath(form)
                fuellen(0xF7FFFFFF.toInt()); c.drawRect(r.left, oben, r.right, r.bottom, p)
                c.restore()
            }
            // Glasrand: oben links und unten rechts heller (wie Licht auf der Kante)
            s.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(0xD9FFFFFF.toInt(), 0x1AFFFFFF, 0x80FFFFFF.toInt()),
                                      floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            s.strokeWidth = 1.2f * d
            val e = 0.6f * d
            r2.set(r.left + e, r.top + e, r.right - e, r.bottom - e)
            c.drawRoundRect(r2, rad - e, rad - e, s); s.shader = null
            kerbe(c, r, zone, oben)
            val ty = r.top + 24f * d
            t.textSize = 12f * d; t.textAlign = Paint.Align.CENTER
            t.color = if (ty - 6f * d > oben) 0xFF5C5C61.toInt() else 0xF2FFFFFF.toInt()
            c.drawText(prozent(stand), r.centerX(), ty, t)
            val iy = r.bottom - 26f * d
            lautsprecher(c, r.centerX(), iy, 20f * d, if (iy - 10f * d > oben) 0xFF5C5C61.toInt() else 0xE6FFFFFF.toInt(), stand)
        }

        // ---- Gezeichnet: Kuli auf Papier. Fuellung = Schraffur. Ganz voll -> nicht zumalen, sondern Striche drumherum
        private val skForm = Path()
        private val skUmriss = Path()
        private val skNach = Path()
        private val skSchraff = Path()
        private val skVoll1 = Path()
        private val skVoll2 = Path()
        private val skPegel = Path()
        private val skKerbe = Path()
        private val skLaut = Path()
        private var skLautAus: Boolean? = null
        private var skB = -1f
        private var skH = -1f
        private val skStift = Skizze.stift(d, 1.6f)
        private val skLeicht = Skizze.stift(d, 0.9f, alpha = 80)
        private val skSchr = Skizze.stift(d, 1.1f, alpha = 170)
        private val skPapier = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skizze.PAPIER }
        private val skText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Skizze.TINTE; textAlign = Paint.Align.CENTER }

        // ---- Kintsugi: Marmorsaeule mit Goldrahmen, ein goldener Riss waechst mit der Lautstaerke nach oben.
        //      Ganz laut: die Saeule ist durchgerissen und ein paar Splitter springen ab.
        private val kiRiss = Path()
        private val kiSplitter = Path()
        private var kiB = -1f; private var kiH = -1f
        private val kiFuge = Deko.fugeStift()
        private val kiGlanz = Deko.glanzStift()
        private var kiGold: Paint? = null
        private val kiRahmen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val kiBild = Paint(Paint.FILTER_BITMAP_FLAG)
        private val kiText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val kiForm = Path()

        private fun kintsugi(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            val pad = 12f * d; val breite = 44f * d
            val l = b - breite - 10f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = 7f * d
            if (b != kiB || h != kiH) {
                kiB = b; kiH = h
                // ein durchgehender Riss von unten nach oben (zackig), mit kurzen Abzweigen
                kiRiss.reset()
                val z = Skizze.Zufall(1925)
                var x = r.centerX(); var y = r.bottom - 2f * d
                kiRiss.moveTo(x, y)
                while (y > r.top + 2f * d) {
                    y -= z.z(6f, 12f) * d
                    x = (x + z.z(-6f, 6f) * d).coerceIn(r.left + 7f * d, r.right - 7f * d)
                    kiRiss.lineTo(x, maxOf(y, r.top + 2f * d))
                    if (z.f() < 0.22f) {
                        val q = Path()
                        Deko.riss(q, x, y, (if (z.f() < 0.5f) 3.1416f else 0f) + z.z(-0.5f, 0.5f), z.z(6f, 14f) * d, z, d * 0.7f, 2)
                        kiRiss.addPath(q); kiRiss.moveTo(x, maxOf(y, r.top + 2f * d))
                    }
                }
                // Splitter (nur ganz laut): kleine Dreiecke oben neben der Saeule
                kiSplitter.reset()
                for ((sx, sy, g) in listOf(Triple(r.left - 6f * d, r.top + 10f * d, 4f), Triple(r.right + 5f * d, r.top + 4f * d, 3f), Triple(r.right + 3f * d, r.top + 22f * d, 2.5f))) {
                    kiSplitter.moveTo(sx, sy - g * d); kiSplitter.lineTo(sx + g * d, sy + g * 0.6f * d); kiSplitter.lineTo(sx - g * 0.8f * d, sy + g * 0.4f * d); kiSplitter.close()
                }
                kiGold = Deko.goldStift(b, h)
                kiForm.reset(); kiForm.addRoundRect(r, rad, rad, Path.Direction.CW)
                kiRahmen.shader = Deko.goldShader(r.left, r.top, r.right, r.bottom)
            }
            val gold = kiGold ?: return
            schatten(c, r, rad)
            // Marmor in der Saeule
            c.save(); c.clipPath(kiForm)
            val m = Deko.marmorBild(400, 1200)          // eigenes kleines Stueck Marmor (fuer alle Fenstergroessen gleich)
            r2.set(r.left - 40f * d, r.top, r.right + 40f * d, r.bottom)
            c.drawBitmap(m, null, r2, kiBild)
            // Riss bis zur Lautstaerke (von unten)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); c.clipRect(r.left, oben, r.right, r.bottom)
                Deko.rissMalen(c, kiRiss, d, kiFuge, gold, kiGlanz, breite = 1.15f)
                c.restore()
                // Glanzpunkt an der Spitze des Risses
                fuellen(Deko.GOLD_HELL); p.alpha = 200
                c.drawCircle(r.centerX(), oben, 1.6f * d, p)
            }
            c.restore()
            // Goldrahmen: aussen kraeftig, innen fein
            kiRahmen.strokeWidth = 1.4f * d; kiRahmen.alpha = 255
            c.drawRoundRect(r, rad, rad, kiRahmen)
            r2.set(r.left + 3.5f * d, r.top + 3.5f * d, r.right - 3.5f * d, r.bottom - 3.5f * d)
            kiRahmen.strokeWidth = 0.6f * d; kiRahmen.alpha = 150
            c.drawRoundRect(r2, rad - 3f * d, rad - 3f * d, kiRahmen)
            // ganz laut: Splitter springen ab
            if (stand >= 0.995f) {
                p.shader = Deko.goldShader(r.left, r.top, r.right, r.top + 30f * d); p.style = Paint.Style.FILL; p.alpha = 255
                c.drawPath(kiSplitter, p); p.shader = null
            }
            // Feinbereich: kleine Goldkerbe rechts
            val zy = r.bottom - zone * r.height()
            kiRahmen.strokeWidth = 1.2f * d; kiRahmen.alpha = 230
            c.drawLine(r.right - 9f * d, zy, r.right - 4f * d, zy, kiRahmen)
            // Zahl in Art-Deco-Schrift
            kiText.typeface = Deko.schrift ?: Typeface.SERIF
            kiText.textSize = 15f * d; kiText.color = Deko.GOLD_HELL
            c.drawText(prozent(stand), r.centerX(), r.top + 24f * d, kiText)
            lautsprecher(c, r.centerX(), r.bottom - 22f * d, 18f * d, Deko.ELFENBEIN, stand)
        }

        private fun skizze(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            Skizze.nachfaerben(skStift, skLeicht, skSchr, skText); Skizze.papierFaerben(skPapier)
            val pad = 12f * d; val breite = 44f * d          // Fenster ist 64 dp breit: Platz fuer die Striche drumherum
            val l = b - breite - 10f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = 16f * d
            if (b != skB || h != skH) {
                skB = b; skH = h
                for (pf in listOf(skForm, skUmriss, skNach, skSchraff, skVoll1, skVoll2)) pf.reset()
                skForm.addRoundRect(r.left + 2.5f * d, r.top + 2.5f * d, r.right - 2.5f * d, r.bottom - 2.5f * d, rad, rad, Path.Direction.CW)
                Skizze.kasten(skUmriss, r.left, r.top, r.right, r.bottom, rad, 4711, d)
                Skizze.kasten(skNach, r.left, r.top, r.right, r.bottom, rad, 4788, d, 0.7f * d)
                Skizze.schraffur(skSchraff, r.left, r.top, r.right, r.bottom, 815, d, 5f)
                Skizze.kasten(skVoll1, r.left, r.top, r.right, r.bottom, rad, 4801, d, 4f * d)
                Skizze.kasten(skVoll2, r.left, r.top, r.right, r.bottom, rad, 4802, d, 8f * d)
            }
            // Papier unter dem Regler (damit er auf jeder App lesbar ist) - Papier ist keine Tinte
            schatten(c, r, rad)
            c.drawRoundRect(r, rad, rad, skPapier)
            val oben = r.bottom - stand * r.height()
            val voll = stand >= 0.995f
            if (stand > 0.001f && !voll) {
                c.save(); c.clipPath(skForm); c.clipRect(r.left, oben, r.right, r.bottom)
                c.drawPath(skSchraff, skSchr)
                c.restore()
                // Pegel: wackliger Strich quer (auf 2 dp gerastert, damit er nicht flimmert)
                skPegel.reset()
                Skizze.linie(skPegel, r.left + 5f * d, oben, r.right - 5f * d, oben, (oben / (2f * d)).toInt(), d, 0.6f)
                c.drawPath(skPegel, skStift)
            }
            c.drawPath(skUmriss, skStift)
            c.drawPath(skNach, skLeicht)
            if (voll) {
                // ganz voll: Striche drumherum statt Tinte hinein
                c.drawPath(skVoll1, skStift)
                c.drawPath(skVoll2, skLeicht)
            }
            // Feinbereich: kleine Kerbe rechts
            val zy = r.bottom - zone * r.height()
            skKerbe.reset()
            Skizze.linie(skKerbe, r.right - 11f * d, zy, r.right - 4f * d, zy, 99, d, 0.3f)
            c.drawPath(skKerbe, skStift)
            // Zahl in Handschrift
            skText.typeface = Skizze.schrift ?: Typeface.DEFAULT
            skText.textSize = 16f * d
            c.drawText(prozent(stand), r.centerX(), r.top + 28f * d, skText)
            // Lautsprecher, gezeichnet (stumm = Kreuz daneben)
            val aus = stand <= 0.001f
            val cx = r.centerX() - 3f * d; val cy = r.bottom - 26f * d
            if (skLautAus != aus) {
                skLautAus = aus
                skLaut.reset()
                val pk = floatArrayOf(-7f, -3f, -3f, -3f, 2f, -8f, 2f, 8f, -3f, 3f, -7f, 3f)
                for (i in 0 until 6) {
                    val j = (i + 1) % 6
                    Skizze.linie(skLaut, pk[2 * i] * d, pk[2 * i + 1] * d, pk[2 * j] * d, pk[2 * j + 1] * d, 300 + i, d, 0.25f)
                }
                if (aus) {
                    Skizze.linie(skLaut, 6f * d, -4f * d, 13f * d, 4f * d, 320, d, 0.3f)
                    Skizze.linie(skLaut, 13f * d, -4f * d, 6f * d, 4f * d, 321, d, 0.3f)
                } else {
                    skLaut.addArc(-1f * d, -6f * d, 11f * d, 6f * d, -55f, 110f)
                    skLaut.addArc(-1f * d, -10f * d, 17f * d, 10f * d, -50f, 100f)
                }
            }
            c.save(); c.translate(cx, cy)
            c.drawPath(skLaut, skStift)
            c.restore()
        }

        // ---- Linie: feine Spur, Knopf mit Schatten, Zahl in einem kleinen Schild
        private fun linie(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 24f * d
            val x = b - 20f * d
            val unten = h - pad; val oben = pad
            val y = unten - stand * (unten - oben)
            strich(0x8C000000.toInt(), 9f * d); c.drawLine(x, oben, x, unten, s)
            strich(0x59FFFFFF, 4f * d); c.drawLine(x, oben, x, unten, s)
            strich(f.akzent or 0xFF000000.toInt(), 5f * d)
            if (stand > 0.001f) c.drawLine(x, y, x, unten, s)
            fuellen(0xCCFFFFFF.toInt()); c.drawCircle(x, unten - zone * (unten - oben), 1.8f * d, p)
            r.set(x - 11f * d, y - 11f * d, x + 11f * d, y + 11f * d); schatten(c, r, 11f * d)
            fuellen(Color.WHITE); c.drawCircle(x, y, 11f * d, p)
            fuellen(f.akzent or 0xFF000000.toInt()); c.drawCircle(x, y, 6f * d, p)
            val txt = prozent(stand)
            t.textSize = 11f * d; t.color = Color.WHITE; t.textAlign = Paint.Align.CENTER
            val tw = t.measureText(txt)
            val cx = x - 30f * d
            r.set(cx - tw / 2 - 6f * d, y - 9.5f * d, cx + tw / 2 + 6f * d, y + 9.5f * d)
            schatten(c, r, 9.5f * d)
            fuellen(0xF017191D.toInt()); c.drawRoundRect(r, 9.5f * d, 9.5f * d, p)
            strich(0x33FFFFFF, 1f * d); c.drawRoundRect(r, 9.5f * d, 9.5f * d, s)
            c.drawText(txt, cx, y + 4f * d, t)
        }

        // ---- Segmente: 14 Balken im Akzent-Verlauf, Feinbereich etwas heller, Lautsprecher unten
        private fun segmente(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            val pad = 10f * d; val breite = 44f * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = 16f * d
            schatten(c, r, rad)
            grundFuellen(0xEB17191D.toInt()); c.drawRoundRect(r, rad, rad, p)
            strich(0x1FFFFFFF, 1f * d); c.drawRoundRect(r, rad, rad, s)
            val n = 14
            val innen = 7f * d; val luecke = 3.5f * d
            val unten0 = r.bottom - 44f * d
            val hoehe = (unten0 - (r.top + innen) - (n - 1) * luecke) / n
            val voll = stand * n
            val hellAkzent = mischen(f.akzent, Color.WHITE, 0.45f)
            for (i in 0 until n) {
                val u = unten0 - i * (hoehe + luecke)
                r2.set(r.left + innen, u - hoehe, r.right - innen, u)
                fuellen(0x1AFFFFFF); c.drawRoundRect(r2, 3.5f * d, 3.5f * d, p)
                val an = (voll - i).coerceIn(0f, 1f)
                if (an > 0f) {
                    var farbe = mischen(hellAkzent, f.akzent or 0xFF000000.toInt(), i / (n - 1f))
                    if ((i + 0.5f) / n <= zone) farbe = mischen(farbe, Color.WHITE, 0.25f)
                    fuellen(alpha(farbe, an)); c.drawRoundRect(r2, 3.5f * d, 3.5f * d, p)
                }
            }
            lautsprecher(c, r.centerX(), r.bottom - 22f * d, 19f * d, 0xE6FFFFFF.toInt(), stand)
        }

        // ================= Kapsel-Varianten: gleiche Kapsel wie "Pille", nur das Innere ist anders
        private val weich = Paint(Paint.ANTI_ALIAS_FLAG)
        private fun weichzeichnen(radius: Float) {
            weich.maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        /** Kapsel zeichnen, danach ist auf r der Innenraum und auf form der Umriss */
        private fun kapsel(c: Canvas, b: Float, h: Float, breiteDp: Float = 48f) {
            val pad = 10f * d; val breite = breiteDp * d
            val l = b - breite - 6f * d
            r.set(l, pad, l + breite, h - pad)
            val rad = breite / 2f
            schatten(c, r, rad)
            grundFuellen(0xEB15171B.toInt()); c.drawRoundRect(r, rad, rad, p)
            form.reset(); form.addRoundRect(r, rad, rad, Path.Direction.CW)
        }
        /** Innenraum bis zum Stand freigeben (in save/restore benutzen) */
        private fun innenClip(c: Canvas, oben: Float) { c.clipPath(form); c.clipRect(r.left, oben, r.right, r.bottom) }
        private fun kapselEnde(c: Canvas, stand: Float, zone: Float, oben: Float, iconDunkel: Boolean) {
            val rad = r.width() / 2f
            strich(0x24FFFFFF, 1f * d); c.drawRoundRect(r, rad, rad, s)
            kerbe(c, r, zone, oben)
            val iy = r.bottom - 22f * d
            lautsprecher(c, r.centerX(), iy, 20f * d,
                if (iy > oben) (if (iconDunkel) 0xCC101215.toInt() else 0xF2FFFFFF.toInt()) else 0xE6FFFFFF.toInt(), stand)
        }
        /** helle Oberflaeche einer Fluessigkeit: Linie + Glanz darunter */
        private fun meniskus(c: Canvas, oben: Float, farbe: Int) {
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.shader = LinearGradient(0f, oben, 0f, oben + 14f * d, alpha(farbe, 0.35f), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(r.left, oben, r.right, oben + 14f * d, p); p.shader = null
            strich(farbe, 1.2f * d); c.drawLine(r.left, oben + 0.6f * d, r.right, oben + 0.6f * d, s)
        }

        // ---- Aurora: Nordlicht-Verlauf (tuerkis -> blau -> violett -> rosa) mit weichen Lichtflecken
        private fun aurora(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); innenClip(c, oben)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, r.bottom, 0f, r.top,
                    intArrayOf(0xFF0FB5A6.toInt(), 0xFF3E7BFA.toInt(), 0xFF9B5CF6.toInt(), 0xFFF472B6.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(r, p); p.shader = null
                weichzeichnen(12f * d)
                for ((fx, fy, rad, farbe) in listOf(
                    Quad(0.2f, 0.85f, 26f, 0x8822D3EE.toInt()), Quad(0.8f, 0.6f, 30f, 0x77A78BFA),
                    Quad(0.3f, 0.35f, 28f, 0x66F9A8D4), Quad(0.75f, 0.15f, 24f, 0x6693C5FD))) {
                    weich.color = farbe
                    c.drawCircle(r.left + fx * r.width(), r.top + fy * r.height(), rad * d, weich)
                }
                meniskus(c, oben, 0x99FFFFFF.toInt())
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, false)
        }

        // ---- Blasen: Sprudel in der Akzentfarbe, Blasen mit Glanzpunkt
        private fun blasen(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); innenClip(c, oben)
                val a = f.akzent or 0xFF000000.toInt()
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, oben, 0f, r.bottom, mischen(a, Color.WHITE, 0.35f), mischen(a, Color.BLACK, 0.25f), Shader.TileMode.CLAMP)
                c.drawRect(r, p); p.shader = null
                val zufall = java.util.Random(7)
                repeat(22) {
                    val x = r.left + (0.15f + 0.7f * zufall.nextFloat()) * r.width()
                    val y = r.top + (0.05f + 0.95f * zufall.nextFloat()) * r.height()
                    val rad = (1.5f + 3.5f * zufall.nextFloat()) * d
                    fuellen(0x26FFFFFF); c.drawCircle(x, y, rad, p)
                    strich(0x8CFFFFFF.toInt(), 0.9f * d); c.drawCircle(x, y, rad, s)
                    fuellen(0xCCFFFFFF.toInt()); c.drawCircle(x - rad * 0.35f, y - rad * 0.35f, rad * 0.28f, p)
                }
                meniskus(c, oben, 0xCCFFFFFF.toInt())
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, false)
        }

        // ---- Neon: leuchtende Linie an der Oberflaeche, darunter ein Hauch Akzent, leuchtender Rand
        private fun neon(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            val a = f.akzent or 0xFF000000.toInt()
            c.save(); c.clipPath(form)
            p.style = Paint.Style.FILL; p.color = Color.WHITE
            p.shader = LinearGradient(0f, oben, 0f, r.bottom, alpha(a, 0.55f), alpha(a, 0.05f), Shader.TileMode.CLAMP)
            c.drawRect(r.left, oben, r.right, r.bottom, p); p.shader = null
            if (stand > 0.001f) {
                weichzeichnen(6f * d); weich.style = Paint.Style.STROKE; weich.strokeWidth = 6f * d; weich.color = a
                c.drawLine(r.left, oben, r.right, oben, weich); weich.style = Paint.Style.FILL
                strich(mischen(a, Color.WHITE, 0.6f), 2f * d); c.drawLine(r.left, oben, r.right, oben, s)
            }
            c.restore()
            if (stand > 0.001f) {
                c.save(); c.clipRect(r.left - 10f * d, oben, r.right + 10f * d, r.bottom + 10f * d)
                strich(alpha(a, 0.9f), 1.6f * d); c.drawRoundRect(r, r.width() / 2f, r.width() / 2f, s)
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, false)
        }

        // ---- Nacht: Nachthimmel mit Sternen, oben an der Oberflaeche ein Mond
        private fun sterne(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); innenClip(c, oben)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF1E1B4B.toInt(), 0xFF312E81.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(r, p); p.shader = null
                val zufall = java.util.Random(3)
                repeat(40) {
                    val x = r.left + (0.1f + 0.8f * zufall.nextFloat()) * r.width()
                    val y = r.top + zufall.nextFloat() * r.height()
                    val g = (0.5f + zufall.nextFloat()) * d
                    fuellen(alpha(Color.WHITE, 0.4f + 0.6f * zufall.nextFloat())); c.drawCircle(x, y, g, p)
                }
                if (r.bottom - oben > 40f * d) {
                    val mx = r.centerX() + 6f * d; val my = oben + 16f * d
                    weichzeichnen(6f * d); weich.color = 0x55FDE68A; c.drawCircle(mx, my, 9f * d, weich)
                    fuellen(0xFFFDE68A.toInt()); c.drawCircle(mx, my, 6f * d, p)
                    fuellen(0xFF2A2770.toInt()); c.drawCircle(mx + 2.5f * d, my - 1.5f * d, 5f * d, p)
                }
                strich(0x66C7D2FE, 1f * d); c.drawLine(r.left, oben + 0.5f * d, r.right, oben + 0.5f * d, s)
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, false)
        }

        // ---- Kristall: Facetten (kleine Dreiecke) in hellen Toenen der Akzentfarbe
        private val dreieck = Path()
        private fun kristall(c: Canvas, b: Float, h: Float, stand: Float, zone: Float, f: Farben) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); innenClip(c, oben)
                val a = f.akzent or 0xFF000000.toInt()
                val zufall = java.util.Random(11)
                val zeilen = 24; val spalten = 3
                val w = r.width() / (spalten - 1); val hh = r.height() / zeilen
                val px = Array(zeilen + 1) { FloatArray(spalten) }; val py = Array(zeilen + 1) { FloatArray(spalten) }
                for (z in 0..zeilen) for (s2 in 0 until spalten) {
                    val rx = zufall.nextFloat() - 0.5f; val ry = zufall.nextFloat() - 0.5f
                    px[z][s2] = r.left + s2 * w + (if (s2 in 1 until spalten - 1) rx * 0.5f * w else 0f)
                    py[z][s2] = r.top + z * hh + (if (z in 1 until zeilen) ry * 0.6f * hh else 0f)
                }
                for (z in 0 until zeilen) for (s2 in 0 until spalten - 1) {
                    for (k in 0..1) {
                        dreieck.reset()
                        dreieck.moveTo(px[z][s2], py[z][s2])
                        if (k == 0) { dreieck.lineTo(px[z][s2 + 1], py[z][s2 + 1]); dreieck.lineTo(px[z + 1][s2 + 1], py[z + 1][s2 + 1]) }
                        else { dreieck.lineTo(px[z + 1][s2 + 1], py[z + 1][s2 + 1]); dreieck.lineTo(px[z + 1][s2], py[z + 1][s2]) }
                        dreieck.close()
                        fuellen(mischen(mischen(a, Color.WHITE, 0.15f + 0.5f * zufall.nextFloat()), Color.BLACK, 0.15f * zufall.nextFloat()))
                        c.drawPath(dreieck, p)
                        strich(0x22FFFFFF, 0.6f * d); c.drawPath(dreieck, s)
                    }
                }
                meniskus(c, oben, 0xCCFFFFFF.toInt())
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, true)
        }

        // ---- Abend: Sonnenuntergang (orange -> pink -> violett), Sonne mit Spiegelung auf dem Wasser
        private fun abend(c: Canvas, b: Float, h: Float, stand: Float, zone: Float) {
            kapsel(c, b, h)
            val oben = r.bottom - stand * r.height()
            if (stand > 0.001f) {
                c.save(); innenClip(c, oben)
                p.style = Paint.Style.FILL; p.color = Color.WHITE
                p.shader = LinearGradient(0f, oben, 0f, r.bottom,
                    intArrayOf(0xFFFDBA74.toInt(), 0xFFF472B6.toInt(), 0xFF7C3AED.toInt()), null, Shader.TileMode.CLAMP)
                c.drawRect(r, p); p.shader = null
                if (r.bottom - oben > 80f * d) {
                    val sx = r.centerX(); val sy = oben + 18f * d
                    weichzeichnen(8f * d); weich.color = 0x88FFF7ED.toInt(); c.drawCircle(sx, sy, 14f * d, weich)
                    fuellen(0xFFFFF7ED.toInt()); c.drawCircle(sx, sy, 9f * d, p)
                    strich(0x55FFFFFF, 1.2f * d)
                    for (i in 0..3) {
                        val y = sy + 12f * d + i * 7f * d
                        c.drawLine(r.left + (8 + i * 3) * d, y, r.right - (8 + i * 3) * d, y, s)
                    }
                }
                c.restore()
            }
            kapselEnde(c, stand, zone, oben, false)
        }
        private data class Quad(val a: Float, val b: Float, val c: Float, val d: Int)

        private fun mischen(a: Int, b: Int, t: Float): Int {
            fun k(x: Int, sh: Int) = (x shr sh) and 0xFF
            fun m(sh: Int) = (k(a, sh) + (k(b, sh) - k(a, sh)) * t).roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
        }
    }
}
