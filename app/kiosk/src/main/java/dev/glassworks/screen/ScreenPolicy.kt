/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.log10

/**
 * When the screen is on and how bright it is, from the frame's [presence] and [light] sources.
 *
 *  - Approaching the panel wakes the screen, and it stays on while someone is near.
 *  - After `idleOffSec` with nobody near and no touch, the screen turns off
 *    (`nightIdleOffSec` inside the night window).
 *  - Brightness follows ambient light (log curve), capped lower at night.
 *  - [hold] keeps the screen on at the day's brightness, past night dimming and the idle timeout,
 *    while something urgent is on it (a high or critical notification), until [release].
 *
 * Turning the screen off uses DevicePolicyManager.lockNow() when [admin] is an active device admin.
 * Otherwise it releases KEEP_SCREEN_ON and lets the system screen_off_timeout expire.
 *
 * One per process (on the app's runtime). Everything runs on the main thread unless a member says otherwise.
 */
class ScreenPolicy(
    private val presence: PresenceSource?,
    private val light: LightSource?,
    /** The app's DeviceAdminReceiver. */
    private val admin: Class<*>,
    private val settings: (Context) -> ScreenSettings,
    /** The logcat tag. The app picks it, so its existing log filters keep working. */
    private val logTag: String,
    /** The wake lock's tag, as `dumpsys power` shows it. */
    private val wakeLockTag: String,
) {
    internal companion object {
        const val TICK_MS = 1000L

        /** The most automatic brightness may reach: lower at night, unless something [held] the screen. */
        fun brightnessCap(night: Boolean, held: Boolean, max: Float, nightMax: Float): Float =
            if (night && !held) minOf(max, nightMax) else max
    }

    private lateinit var ctx: Context
    private lateinit var prefs: ScreenSettings
    private lateinit var pm: PowerManager
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private var brightnessObserver: ContentObserver? = null

    // --- live state, readable from any thread
    @Volatile var near = false; private set
    @Volatile var proximityRaw = Float.NaN; private set
    @Volatile var lux = Float.NaN; private set
    @Volatile var brightness = -1f; private set
    @Volatile var screenOn = true; private set
    private var lastActivity = SystemClock.elapsedRealtime()
    /** Why the screen is held ([hold]). Main thread. */
    private val holds = HashSet<String>()

    /** Something holds the screen on ([hold]). */
    val held: Boolean get() = holds.isNotEmpty()

    /** Activity hooks: keep-screen-on flag and window brightness (0..1, or -1 = system). */
    var onKeepScreenOn: ((Boolean) -> Unit)? = null
        set(v) { field = v; v?.invoke(keepOn) }
    var onBrightness: ((Float) -> Unit)? = null
        set(v) { field = v; v?.invoke(brightness) }
    /** Fired on any state change (presence/lux/screen); used to push updates to the page. */
    var onStateChanged: (() -> Unit)? = null

    /** Extra state listeners (e.g. the ESPHome API), fired with [onStateChanged]; main thread. */
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

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
        prefs = settings(ctx)
        pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(ctx, admin)
        lastActivity = SystemClock.elapsedRealtime()
        screenOn = pm.isInteractive
        watchSystemBrightness()
        // The light sensor reports only on change, so auto brightness off can't wait for a reading.
        updateBrightness()
        presence?.start(ctx, ::onPresence)
        light?.start(ctx, ::onLux)
        ctx.registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        applyPresenceRange()
        handler.post(tick)
    }

    /** Undoes [start]: the sensors, the screen broadcasts, the brightness observer and the tick. [start] may run again. */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(tick)
        presence?.stop(ctx)
        light?.stop(ctx)
        // start() may have thrown before it registered the receiver.
        runCatching { ctx.unregisterReceiver(screenReceiver) }
        brightnessObserver?.let { ctx.contentResolver.unregisterContentObserver(it) }
        brightnessObserver = null
    }

    /** Passes the saved presence range to the presence source: null, or why it didn't take. */
    fun applyPresenceRange(): String? = if (started) presence?.setRange(ctx, prefs.presenceRangeM) else null

    val isAdmin: Boolean get() = started && dpm.isAdminActive(adminComponent)

    fun removeAdmin() {
        if (isAdmin) dpm.removeActiveAdmin(adminComponent)
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
        Log.i(logTag, "wake ($reason)")
        lastActivity = SystemClock.elapsedRealtime()
        setKeepOn(true)
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE, wakeLockTag
            )
            wl.acquire(3000)
        }
    }

    /**
     * Wakes the screen and keeps it on, at the day's maximum brightness, past night dimming and the idle
     * timeout, until [release] with the same [reason]. Several reasons can hold it at once.
     */
    fun hold(reason: String) {
        val first = holds.isEmpty()
        holds += reason
        if (started) wake(reason)
        if (first && started) updateBrightness(immediate = true)
    }

    /** Lets go of a [hold]. The idle timeout counts from now, so the screen doesn't go dark at once. */
    fun release(reason: String) {
        if (!holds.remove(reason)) return
        Log.i(logTag, "release ($reason)")
        lastActivity = SystemClock.elapsedRealtime()
        if (holds.isEmpty() && started) updateBrightness(immediate = true)
    }

    fun sleep(reason: String) {
        Log.i(logTag, "sleep ($reason) admin=$isAdmin")
        if (isAdmin) {
            try {
                dpm.lockNow()
                return
            } catch (e: SecurityException) {
                Log.w(logTag, "lockNow failed", e)
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
            if (near || holds.isNotEmpty()) lastActivity = now
            val timeoutSec = if (isNight()) prefs.nightIdleOffSec else prefs.idleOffSec
            if (timeoutSec > 0 && keepOn && now - lastActivity >= timeoutSec * 1000L) {
                sleep("idle ${timeoutSec}s")
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            Log.i(logTag, "screen ${if (screenOn) "ON" else "OFF"}")
            if (screenOn) {
                lastActivity = SystemClock.elapsedRealtime()
                setKeepOn(true)
            }
            notifyChanged()
        }
    }

    private fun onPresence(nowNear: Boolean, raw: Float) {
        proximityRaw = raw
        if (nowNear == near) return
        near = nowNear
        Log.i(logTag, "presence ${if (near) "NEAR" else "far"} (raw=$raw)")
        if (near) {
            lastActivity = SystemClock.elapsedRealtime()
            if (!screenOn && prefs.wakeOnApproach) wake("approach")
        }
        notifyChanged()
    }

    private fun onLux(value: Float) {
        val prev = lux
        lux = value
        updateBrightness()
        if (prev.isNaN() || kotlin.math.abs(prev - lux) > maxOf(5f, prev * 0.2f)) notifyChanged()
    }

    /** Re-applies the brightness policy after a settings change (main thread). */
    fun refreshBrightness() {
        if (started) updateBrightness()
    }

    /**
     * The system brightness slider (notification shade) only writes Settings.System.SCREEN_BRIGHTNESS;
     * our own brightness is a window override that beats it. This app never writes that setting, so a
     * change there is the user moving the slider: switch auto brightness off and follow the system
     * value. Turning auto brightness back on (Home Assistant or app settings) resumes it.
     */
    private fun watchSystemBrightness() {
        val startedAt = SystemClock.elapsedRealtime()
        val uri = android.provider.Settings.System.getUriFor(android.provider.Settings.System.SCREEN_BRIGHTNESS)
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                if (SystemClock.elapsedRealtime() - startedAt < 30_000) return // boot-time writes by the firmware
                if (!prefs.autoBrightness && prefs.manualBrightness <= 0f) return // already following the system
                Log.i(logTag, "system brightness slider moved: following it (auto brightness off)")
                prefs.autoBrightness = false
                prefs.manualBrightness = 0f // <= 0: no window override, i.e. the system value
                refreshBrightness()
                notifyChanged()
            }
        }
        ctx.contentResolver.registerContentObserver(uri, false, observer)
        brightnessObserver = observer
    }

    /** Fixed brightness from Home Assistant: turns auto brightness off (main thread). */
    fun setManualBrightness(b: Float) {
        prefs.manualBrightness = b.coerceIn(0.01f, 1f)
        prefs.autoBrightness = false
        refreshBrightness()
    }

    private fun updateBrightness(immediate: Boolean = false) {
        if (!prefs.autoBrightness || lux.isNaN()) {
            val manual = if (prefs.autoBrightness) -1f else prefs.manualBrightness.let { if (it > 0f) it else -1f }
            if (brightness != manual) {
                brightness = manual
                onBrightness?.invoke(manual)
                notifyChanged()
            }
            return
        }
        val max = brightnessCap(isNight(), held, prefs.maxBrightness, prefs.nightMaxBrightness)
        val min = minOf(prefs.minBrightness, max)
        // 0 lux -> min, ~1000 lux -> max, logarithmic in between.
        val t = (log10(lux.coerceAtLeast(0f) + 1f) / 3f).coerceIn(0f, 1f)
        val target = min + (max - min) * t
        // Smooth, and ignore tiny changes to avoid flicker.
        val next = if (brightness < 0f || immediate) target else brightness + (target - brightness) * 0.3f
        if (brightness < 0f || kotlin.math.abs(next - brightness) > 0.01f) {
            brightness = next
            onBrightness?.invoke(next)
        }
    }

    fun statusLine(): String =
        "presence=${if (near) "near" else "far"} (raw ${proximityRaw}) · lux=${lux} · " +
            "brightness=${"%.2f".format(brightness)} · night=${isNight()} · admin=${isAdmin}"
}
