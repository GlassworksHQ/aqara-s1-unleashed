package sh.edm.s1kiosk

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import kotlin.math.log10

/**
 * Screen policy driven by the panel's own sensors, replacing Aqara's DND/idle logic.
 *
 * Hardware (from `dumpsys sensorservice`):
 *  - "vi5300 Proximity Sensor" (ST VL53L-class time-of-flight, wake-up sensor). The Lumi HAL reports
 *    `1.0` when something is closer than the threshold in `settings secure proximity_threshold`,
 *    otherwise a larger value. The threshold is programmed by com.lumi.systemmanager.
 *  - "mk6212 Ambient Light Sensor" (lux).
 *
 * Policy:
 *  - Approaching the panel wakes the screen, and it stays on while someone is near.
 *  - After `idleOffSec` with nobody near and no touch, the screen turns off
 *    (`nightIdleOffSec` inside the night window).
 *  - Brightness follows ambient light (log curve), capped lower at night.
 *
 * Turning the screen off uses DevicePolicyManager.lockNow() when the app is an active device admin
 * (`adb shell dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver`). Otherwise it
 * releases KEEP_SCREEN_ON and lets the system screen_off_timeout expire.
 */
@SuppressLint("StaticFieldLeak")
object ScreenController : SensorEventListener {
    private const val TAG = "S1Kiosk.Screen"
    private const val TICK_MS = 1000L

    private lateinit var ctx: Context
    private lateinit var prefs: KioskPrefs
    private lateinit var pm: PowerManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    // --- live state (read by the settings dialog / web extension)
    @Volatile var near = false; private set
    @Volatile var proximityRaw = Float.NaN; private set
    @Volatile var lux = Float.NaN; private set
    @Volatile var brightness = -1f; private set
    @Volatile var screenOn = true; private set
    private var lastActivity = SystemClock.elapsedRealtime()

    /** Activity hooks: keep-screen-on flag and window brightness (0..1, or -1 = system). */
    var onKeepScreenOn: ((Boolean) -> Unit)? = null
        set(v) { field = v; v?.invoke(keepOn) }
    var onBrightness: ((Float) -> Unit)? = null
        set(v) { field = v; v?.invoke(brightness) }
    /** Fired on any state change (presence/lux/screen); used to push updates to the page. */
    var onStateChanged: (() -> Unit)? = null

    /** Extra state listeners (e.g. the ESPHome API), fired with [onStateChanged]; main thread. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun notifyChanged() {
        onStateChanged?.invoke()
        listeners.forEach { it() }
    }

    private var keepOn = true

    fun start(context: Context) {
        if (started) return
        started = true
        ctx = context.applicationContext
        prefs = KioskPrefs(ctx)
        pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(ctx, AdminReceiver::class.java)
        screenOn = pm.isInteractive

        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            Log.i(TAG, "proximity: ${it.name} vendor=${it.vendor} max=${it.maximumRange} wakeUp=${it.isWakeUpSensor}")
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            Log.i(TAG, "light: ${it.name} vendor=${it.vendor} max=${it.maximumRange}")
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        applyPresenceRange()
        handler.post(tick)
    }

    /** Lumi's threshold table (index -> decimetres), from com.lumi.systemmanager Monitor.java. */
    private val RANGE_DM = intArrayOf(0, 1, 2, 4, 6, 8, 10, 12, 15, 20, 25)

    /**
     * Program the VI5300 presence range. com.lumi.systemmanager observes
     * `secure proximity_threshold` (an index into RANGE_DM) and writes it to the sensor driver.
     * Needs WRITE_SECURE_SETTINGS (granted over adb); silently skipped otherwise.
     */
    fun applyPresenceRange() {
        val m = prefs.presenceRangeM
        if (m <= 0f) return
        val dm = (m * 10).toInt()
        val idx = RANGE_DM.indices.minByOrNull { kotlin.math.abs(RANGE_DM[it] - dm) } ?: return
        try {
            android.provider.Settings.Secure.putInt(ctx.contentResolver, "proximity_threshold", idx)
            Log.i(TAG, "presence range ${m}m -> proximity_threshold=$idx (${RANGE_DM[idx] / 10.0} m)")
        } catch (e: SecurityException) {
            Log.w(TAG, "No WRITE_SECURE_SETTINGS; can't set presence range")
        }
    }

    val isAdmin: Boolean get() = started && dpm.isAdminActive(admin)

    fun removeAdmin() {
        if (isAdmin) dpm.removeActiveAdmin(admin)
    }

    /** Touch / page interaction / voice activity. */
    fun userActivity() {
        lastActivity = SystemClock.elapsedRealtime()
        if (!keepOn) setKeepOn(true)
    }

    fun isNight(): Boolean {
        val w = prefs.nightWindow
        val m = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""").find(w) ?: return false
        val (sh, sm, eh, em) = m.destructured
        val start = sh.toInt() * 60 + sm.toInt()
        val end = eh.toInt() * 60 + em.toInt()
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (start <= end) now in start until end else now >= start || now < end
    }

    fun wake(reason: String) {
        Log.i(TAG, "wake ($reason)")
        lastActivity = SystemClock.elapsedRealtime()
        setKeepOn(true)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE, "s1kiosk:wake"
            )
            wl.acquire(3000)
        }
    }

    fun sleep(reason: String) {
        Log.i(TAG, "sleep ($reason) admin=$isAdmin")
        if (isAdmin) {
            try {
                dpm.lockNow()
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "lockNow failed", e)
            }
        }
        // Fallback: let the system timeout turn the screen off.
        setKeepOn(false)
    }

    private fun setKeepOn(v: Boolean) {
        if (keepOn == v) return
        keepOn = v
        onKeepScreenOn?.invoke(v)
    }

    private val tick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, TICK_MS)
            if (!screenOn) return
            if (prefs.screenMode == "always") {
                setKeepOn(true)
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (near) lastActivity = now
            val timeoutSec = if (isNight()) prefs.nightIdleOffSec else prefs.idleOffSec
            if (timeoutSec > 0 && keepOn && now - lastActivity >= timeoutSec * 1000L) {
                sleep("idle ${timeoutSec}s")
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            Log.i(TAG, "screen ${if (screenOn) "ON" else "OFF"}")
            if (screenOn) {
                lastActivity = SystemClock.elapsedRealtime()
                setKeepOn(true)
            }
            notifyChanged()
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                proximityRaw = e.values[0]
                val nowNear = e.values[0] == 1f
                if (nowNear != near) {
                    near = nowNear
                    Log.i(TAG, "presence ${if (near) "NEAR" else "far"} (raw=${e.values[0]})")
                    if (near) {
                        lastActivity = SystemClock.elapsedRealtime()
                        if (!screenOn && prefs.wakeOnApproach) wake("approach")
                    }
                    notifyChanged()
                }
            }
            Sensor.TYPE_LIGHT -> {
                val prev = lux
                lux = e.values[0]
                updateBrightness()
                if (prev.isNaN() || kotlin.math.abs(prev - lux) > maxOf(5f, prev * 0.2f)) notifyChanged()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Re-applies the brightness policy after a settings change (main thread). */
    fun refreshBrightness() {
        if (started) updateBrightness()
    }

    /** Fixed brightness from Home Assistant: turns auto brightness off (main thread). */
    fun setManualBrightness(b: Float) {
        prefs.manualBrightness = b.coerceIn(0.01f, 1f)
        prefs.autoBrightness = false
        refreshBrightness()
    }

    private fun updateBrightness() {
        if (!prefs.autoBrightness || lux.isNaN()) {
            val manual = if (prefs.autoBrightness) -1f else prefs.manualBrightness.let { if (it > 0f) it else -1f }
            if (brightness != manual) {
                brightness = manual
                onBrightness?.invoke(manual)
                notifyChanged()
            }
            return
        }
        val max = if (isNight()) minOf(prefs.maxBrightness, prefs.nightMaxBrightness) else prefs.maxBrightness
        val min = minOf(prefs.minBrightness, max)
        // 0 lux -> min, ~1000 lux -> max, logarithmic in between.
        val t = (log10(lux.coerceAtLeast(0f) + 1f) / 3f).coerceIn(0f, 1f)
        val target = min + (max - min) * t
        // Smooth, and ignore tiny changes to avoid flicker.
        val next = if (brightness < 0f) target else brightness + (target - brightness) * 0.3f
        if (brightness < 0f || kotlin.math.abs(next - brightness) > 0.01f) {
            brightness = next
            onBrightness?.invoke(next)
        }
    }

    fun statusLine(): String =
        "presence=${if (near) "near" else "far"} (raw ${proximityRaw}) · lux=${lux} · " +
            "brightness=${"%.2f".format(brightness)} · night=${isNight()} · admin=${isAdmin}"
}
