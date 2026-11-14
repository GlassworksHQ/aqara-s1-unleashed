package sh.edm.s1kiosk.frame

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import android.util.Log
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import kotlin.math.abs

private const val TAG = "S1Kiosk.Screen"

/**
 * The S1's "vi5300 Proximity Sensor" (ST VL53L-class time-of-flight, wake-up sensor). The Lumi HAL
 * reports `1.0` when something is closer than the threshold in `settings secure proximity_threshold`,
 * otherwise a larger value. com.lumi.systemmanager observes that setting and programs the driver.
 */
class Vi5300Presence(
    /** Settings.Secure.putInt; tests stand in for the provider. */
    private val writeSecure: (Context, String, Int) -> Boolean = { c, name, v -> Settings.Secure.putInt(c.contentResolver, name, v) },
) : PresenceSource {
    internal companion object {
        /** The S1's sensor is `vi5300 Proximity Sensor` (vendor lumi, docs/sensors.md). */
        fun isVi5300(name: String?): Boolean = name?.contains("vi5300", ignoreCase = true) == true

        /** Lumi's threshold table (index -> decimetres), from com.lumi.systemmanager Monitor.java. */
        val RANGE_DM = intArrayOf(0, 1, 2, 4, 6, 8, 10, 12, 15, 20, 25)
        const val NO_RANGE = "The sensor's range didn't change: the app needs the permission to write secure settings (docs/sensors.md)."
        const val NOT_WRITTEN = "The sensor's range didn't change: Android didn't save the setting. Try again."
    }

    private var registered: SensorEventListener? = null

    /**
     * The S1 app also runs on devices without the S1's sensor (a tablet, until #23). Only the VI5300
     * counts: this source reads 1.0 as near, and another device's proximity sensor reports a distance.
     */
    override fun available(context: Context): Boolean =
        isVi5300((context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PROXIMITY)?.name)

    override fun start(context: Context, onReading: (near: Boolean, raw: Float) -> Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            Log.i(TAG, "proximity: ${it.name} vendor=${it.vendor} max=${it.maximumRange} wakeUp=${it.isWakeUpSensor}")
            val l = listener(Sensor.TYPE_PROXIMITY) { v -> onReading(v == 1f, v) }
            sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL)
            registered = l
        }
    }

    override fun stop(context: Context) = unregister(context, registered).also { registered = null }

    /** Writes the nearest index of [RANGE_DM]. Needs WRITE_SECURE_SETTINGS (granted over adb); skipped otherwise. */
    override fun setRange(context: Context, metres: Float): String? {
        if (metres <= 0f) return null
        val dm = (metres * 10).toInt()
        val idx = RANGE_DM.indices.minByOrNull { abs(RANGE_DM[it] - dm) } ?: return null
        return try {
            // putInt says false without throwing when the settings provider failed (a RemoteException).
            if (!writeSecure(context, "proximity_threshold", idx)) {
                Log.w(TAG, "presence range ${metres}m: the settings provider didn't take proximity_threshold=$idx")
                return NOT_WRITTEN
            }
            Log.i(TAG, "presence range ${metres}m -> proximity_threshold=$idx (${RANGE_DM[idx] / 10.0} m)")
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "No WRITE_SECURE_SETTINGS; can't set presence range")
            NO_RANGE
        }
    }
}

/** The S1's "mk6212 Ambient Light Sensor", in lux. */
class Mk6212Light : LightSource {
    private var registered: SensorEventListener? = null

    override fun start(context: Context, onLux: (Float) -> Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            Log.i(TAG, "light: ${it.name} vendor=${it.vendor} max=${it.maximumRange}")
            val l = listener(Sensor.TYPE_LIGHT, onLux)
            sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL)
            registered = l
        }
    }

    override fun stop(context: Context) = unregister(context, registered).also { registered = null }
}

private fun unregister(context: Context, l: SensorEventListener?) {
    if (l != null) (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(l)
}

private fun listener(type: Int, onValue: (Float) -> Unit) = object : SensorEventListener {
    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == type) onValue(e.values[0])
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
