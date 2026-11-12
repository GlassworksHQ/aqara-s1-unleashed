package sh.edm.s1kiosk.screen

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import dev.glassworks.screen.ScreenPolicy
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.time.Duration
import java.util.SimpleTimeZone
import java.util.TimeZone

/**
 * Drives the screen policy the way the panel does (sensor events, screen broadcasts, time passing)
 * for [ScreenBehaviourPinTest]. Only this file follows the policy's API. The pin test does not
 * change when the policy is refactored.
 */
class ScreenPinHarness {
    val context: Context = RuntimeEnvironment.getApplication()
    val prefs = KioskPrefs(context)
    /** The policy as the app wires it, with the S1 frame's sensors. A fresh process's one on every [start]. */
    private lateinit var screen: ScreenPolicy

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximity = ShadowSensor.newInstance(Sensor.TYPE_PROXIMITY)
    private val light = ShadowSensor.newInstance(Sensor.TYPE_LIGHT)
    private val savedZone = TimeZone.getDefault()

    /** Every value the policy gave the activity's keep-screen-on hook, oldest first. */
    val keepOnCalls = mutableListOf<Boolean>()
    /** Every window brightness the policy gave the activity, oldest first (-1 = system). */
    val brightnessCalls = mutableListOf<Float>()
    /** How many times the policy told its listeners the state changed. */
    var changes = 0; private set

    init {
        shadowOf(sensors).addSensor(proximity)
        shadowOf(sensors).addSensor(light)
    }

    /** Starts the policy with the screen on, as the activity does at launch. */
    fun start(screenOn: Boolean = true) {
        if (::screen.isInitialized) detach()
        ShadowPowerManager.reset()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(screenOn)
        screen = GlassworksRuntime(AqaraS1Plus()).screen
        screen.start(context)
        screen.onKeepScreenOn = { keepOnCalls += it }
        screen.onBrightness = { brightnessCalls += it }
        screen.onStateChanged = { changes++ }
        keepOnCalls.clear()
        brightnessCalls.clear()
        changes = 0
        idle()
    }

    fun close() {
        if (::screen.isInitialized) detach()
        TimeZone.setDefault(savedZone)
    }

    /** Each test, and each restart within one, gets a policy no earlier one reports into. */
    private fun detach() {
        screen.stop()
        screen.onKeepScreenOn = null
        screen.onBrightness = null
        screen.onStateChanged = null
    }

    fun proximity(raw: Float) = send(proximity, raw)
    fun light(lux: Float) = send(light, lux)

    private fun send(sensor: Sensor, value: Float) {
        shadowOf(sensors).sendSensorEventToListeners(
            SensorEventBuilder.newBuilder().setSensor(sensor).setValues(floatArrayOf(value)).setTimestamp(0).build()
        )
        idle()
    }

    fun screenOff() = broadcast(Intent.ACTION_SCREEN_OFF, false)
    fun screenOn() = broadcast(Intent.ACTION_SCREEN_ON, true)

    private fun broadcast(action: String, interactive: Boolean) {
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(interactive)
        context.sendBroadcast(Intent(action))
        idle()
    }

    /** Lets [ms] of time pass on the main looper, running the policy's ticks. */
    fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * Makes local wall-clock time [hour]:[minute] now, by picking a default time zone whose offset
     * puts the real clock there. The policy reads the time of day from [java.util.Calendar].
     */
    fun setClock(hour: Int, minute: Int) {
        val day = 24 * 3_600_000L
        val utcOfDay = Math.floorMod(System.currentTimeMillis(), day)
        var offset = Math.floorMod((hour * 60 + minute) * 60_000L - utcOfDay, day)
        if (offset > day / 2) offset -= day
        TimeZone.setDefault(SimpleTimeZone(offset.toInt(), "pin"))
    }

    fun userActivity() { screen.userActivity(); idle() }
    fun wake(reason: String) { screen.wake(reason); idle() }
    fun sleep(reason: String) { screen.sleep(reason); idle() }
    fun applyPresenceRange() = screen.applyPresenceRange()
    fun refreshBrightness() { screen.refreshBrightness(); idle() }
    fun setManualBrightness(b: Float) { screen.setManualBrightness(b); idle() }

    val near get() = screen.near
    val proximityRaw get() = screen.proximityRaw
    val lux get() = screen.lux
    val brightness get() = screen.brightness
    val screenIsOn get() = screen.screenOn
    val night get() = screen.isNight()

    /** `settings secure proximity_threshold`, or null while nothing wrote it. */
    val proximityThreshold: Int?
        get() = runCatching { Settings.Secure.getInt(context.contentResolver, "proximity_threshold") }.getOrNull()

    /** True when the policy took a wake lock that turns the screen on. */
    val wokeScreen: Boolean get() = ShadowPowerManager.getLatestWakeLock() != null
}
