package de.atelierlauncher.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.mutableFloatStateOf

// ============================================================================
//  3D-Effekt beim Neigen (Parallaxe): Hintergrund wandert gegen die Neigung,
//  Uhr und Apps ein kleines Stueck mit ihr -> sie scheinen ueber dem Hintergrund zu schweben.
//  Gemessen wird nur die AENDERUNG der Neigung: egal wie du das Handy haeltst, in Ruhe ist alles mittig.
//  Laeuft nur, solange der Launcher (bzw. der Sperrbildschirm) zu sehen ist.
// ============================================================================
class Neigung {
    /** -1..1 (links/rechts bzw. oben/unten). Nur im Zeichnen lesen -> kein Neuaufbau je Bewegung */
    val x = mutableFloatStateOf(0f)
    val y = mutableFloatStateOf(0f)
    /** grob gerastert (Zehntel) - fuer Schatten an Texten, damit nicht bei jedem Zittern alles neu gebaut wird */
    val sx = mutableFloatStateOf(0f)
    val sy = mutableFloatStateOf(0f)
    /** laeuft gerade (dann gibt es den 3D-Schatten) */
    val aktiv = androidx.compose.runtime.mutableStateOf(false)
    // fuer Code ausserhalb von Compose (Live-Hintergrund)
    @Volatile var wx = 0f
    @Volatile var wy = 0f
    private var basisNick = Float.NaN
    private var basisRoll = Float.NaN
    private var sm: SensorManager? = null
    private val matrix = FloatArray(9)
    private val winkel = FloatArray(3)
    private var nachricht: (() -> Unit)? = null

    private val hoerer = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(matrix, e.values)
            SensorManager.getOrientation(matrix, winkel)
            val nick = winkel[1]; val roll = winkel[2]
            if (basisNick.isNaN()) { basisNick = nick; basisRoll = roll }
            // Ruhelage folgt langsam (ca. 1,5 s) -> jede Haltung wird zur Mitte
            basisNick += (nick - basisNick) * 0.02f
            basisRoll += (roll - basisRoll) * 0.02f
            val zx = ((roll - basisRoll) / 0.35f).coerceIn(-1f, 1f)
            val zy = (-(nick - basisNick) / 0.35f).coerceIn(-1f, 1f)     // oben/unten war vertauscht
            // weich glaetten gegen Zittern
            val nx = x.floatValue + (zx - x.floatValue) * 0.25f
            val ny = y.floatValue + (zy - y.floatValue) * 0.25f
            // winzige Aenderungen (Sensor-Rauschen, Handy liegt still) ignorieren: sonst wurde der ganze
            // Launcher ~50x pro Sekunde neu gezeichnet, obwohl sich sichtbar nichts bewegt (gemessen)
            if (kotlin.math.abs(nx - x.floatValue) < 0.012f && kotlin.math.abs(ny - y.floatValue) < 0.012f) return
            x.floatValue = nx; y.floatValue = ny
            wx = nx; wy = ny
            val qx = kotlin.math.round(nx * 10f) / 10f; val qy = kotlin.math.round(ny * 10f) / 10f
            if (qx != sx.floatValue) sx.floatValue = qx
            if (qy != sy.floatValue) sy.floatValue = qy
            nachricht?.invoke()
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    fun start(ctx: Context, beiAenderung: (() -> Unit)? = null) {
        if (sm != null) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return
        nachricht = beiAenderung
        basisNick = Float.NaN
        try { m.registerListener(hoerer, s, SensorManager.SENSOR_DELAY_UI); sm = m; aktiv.value = true } catch (e: Exception) { }
    }

    fun stop() {
        try { sm?.unregisterListener(hoerer) } catch (e: Exception) { }
        sm = null; nachricht = null
        x.floatValue = 0f; y.floatValue = 0f; wx = 0f; wy = 0f
        sx.floatValue = 0f; sy.floatValue = 0f; aktiv.value = false
    }

    companion object {
        /** der Launcher hat seine eigene */
        val launcher = Neigung()
        fun an(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE).getBoolean("neigung3d", false)
    }
}
