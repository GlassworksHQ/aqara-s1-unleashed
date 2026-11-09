package io.github.aguilaair.s1kiosk.improv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.aguilaair.s1kiosk.BuildConfig
import io.github.aguilaair.s1kiosk.KioskPrefs
import io.github.aguilaair.s1kiosk.ScreenController
import java.lang.ref.WeakReference

/**
 * Improv Wi-Fi via BLE provisioning mode (see docs/onboarding.md, step 6).
 *
 * Runs only for a limited window ([WINDOW_MS]):
 *  - automatically when the panel has had no network [BOOT_GRACE_MS] after KioskService started
 *    (boot), unless `improv_auto` is off;
 *  - on demand: `--ez improv true` (ImprovActivity / KioskService intent) or the ESPHome button.
 * Starts in AUTHORIZATION_REQUIRED; tapping "Allow" on the on-screen card (or, optionally, a
 * proximity approach) authorizes for [ImprovSession.DEFAULT_AUTH_TIMEOUT_MS].
 *
 * Main thread only.
 */
@SuppressLint("StaticFieldLeak")
object ImprovController {
    private const val TAG = "S1Kiosk.Improv"
    const val WINDOW_MS = 10 * 60_000L
    const val BOOT_GRACE_MS = 30_000L
    /** Keep advertising PROVISIONED this long so clients see it (ESPHome STOP_ADVERTISING_DELAY). */
    const val STOP_DELAY_MS = 10_000L
    private const val FIRMWARE = "S1 Kiosk"
    private const val DEVICE_NAME = "S1 Panel"

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private lateinit var prefs: KioskPrefs
    private lateinit var wifi: WifiProvisioner
    private lateinit var overlay: ImprovOverlay
    private var ble: ImprovBleServer? = null
    private var session: ImprovSession? = null
    private var initialized = false

    private var windowEndsAt = 0L
    private var auto = false
    private var reason = ""
    private var connectingSsid = ""
    private var progress: String? = null
    private var failure: String? = null
    private var provisionedUrl: String? = null
    private var lastNear = false
    private var resumed: WeakReference<Activity>? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** One-line status for logs / diagnostics. */
    @Volatile var status: String = "off"
        private set

    val active: Boolean get() = session?.started == true

    fun init(ctx: Context) {
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        prefs = KioskPrefs(app)
        wifi = WifiProvisioner(app, main).also { it.onProgress = { p -> progress = p; refreshOverlay() } }
        overlay = ImprovOverlay(app).also { it.fallbackActivity = { resumed?.get() } }
        (app as? Application)?.registerActivityLifecycleCallbacks(activityTracker)
        ScreenController.addListener(proximityListener)

        val probe = ImprovBleServer(app, main, bleListener)
        ble = probe
        Log.i(TAG, "BLE peripheral support: ${probe.capabilityReport()}")
        Log.i(TAG, "Wi-Fi provisioning: ${wifi.status()} sdk=${Build.VERSION.SDK_INT}")
        if (prefs.improvAuto) main.postDelayed(bootCheck, BOOT_GRACE_MS)
    }

    fun shutdown() {
        if (!initialized) return
        stop("service destroyed")
        main.removeCallbacks(bootCheck)
        ScreenController.removeListener(proximityListener)
        (app as? Application)?.unregisterActivityLifecycleCallbacks(activityTracker)
        initialized = false
    }

    /**
     * Intent extras (adb: `am start -n io.github.aguilaair.s1kiosk/.improv.ImprovActivity --ez improv true`):
     *   --ez improv true|false            start / stop provisioning mode
     *   --ez improv_auto true|false       auto-start when there's no network after boot (default true)
     *   --ez improv_proximity_auth true   also authorize on a proximity approach (default false)
     *   --ez release_device_owner true    clearDeviceOwnerApp() (escape hatch)
     */
    fun handleIntent(i: Intent?) {
        i ?: return
        if (!initialized) return
        if (i.hasExtra("improv_auto")) prefs.improvAuto = i.getBooleanExtra("improv_auto", true)
        if (i.hasExtra("improv_proximity_auth")) prefs.improvProximityAuth = i.getBooleanExtra("improv_proximity_auth", false)
        if (i.getBooleanExtra("release_device_owner", false)) releaseDeviceOwner()
        if (i.hasExtra("improv")) {
            if (i.getBooleanExtra("improv", false)) startOnDemand("intent") else stop("intent")
        }
    }

    fun startOnDemand(why: String) = start(why, isAuto = false)

    fun start(why: String, isAuto: Boolean) {
        if (!initialized) return
        val now = SystemClock.elapsedRealtime()
        if (active) {
            windowEndsAt = now + WINDOW_MS
            Log.i(TAG, "already running; window extended ($why)")
            refreshOverlay()
            return
        }
        val server = ble ?: ImprovBleServer(app, main, bleListener).also { ble = it }
        val s = ImprovSession(effects)
        session = s
        reason = why; auto = isAuto; failure = null; progress = null; provisionedUrl = null; connectingSsid = ""
        windowEndsAt = now + WINDOW_MS
        s.start(requireAuthorization = true, now = now)
        if (!server.start(s.state, s.capabilities)) {
            session = null
            overlay.hide()
            status = "failed to start"
            return
        }
        wifi.requestScan()
        if (isAuto) watchNetwork()
        status = "starting ($why)"
        Log.i(TAG, "Improv started: $why; window ${WINDOW_MS / 60000} min; ${wifi.status()}")
        ScreenController.wake("improv")
        refreshOverlay()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop(why: String) {
        val s = session ?: return
        Log.i(TAG, "Improv stopped: $why (state=${s.state})")
        if (s.state == ImprovState.PROVISIONING) wifi.cancel()
        s.stop()
        session = null
        ble?.stop()
        unwatchNetwork()
        main.removeCallbacks(tick)
        main.removeCallbacks(delayedStop)
        overlay.hide()
        status = "off ($why)"
    }

    fun authorize(source: String) {
        val s = session ?: return
        Log.i(TAG, "authorized by $source")
        failure = null
        s.authorize(SystemClock.elapsedRealtime())
    }

    private fun releaseDeviceOwner() {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(app.packageName)) {
            Log.i(TAG, "release_device_owner: not device owner; nothing to do")
            return
        }
        try {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(app.packageName)
            Log.i(TAG, "release_device_owner: cleared; deviceOwner=${dpm.isDeviceOwnerApp(app.packageName)}. " +
                "The device admin is removed too: re-run `dpm set-active-admin .../.AdminReceiver` for lockNow.")
        } catch (e: Exception) {
            Log.e(TAG, "clearDeviceOwnerApp failed", e)
        }
    }

    // ---- session effects

    private val effects = object : ImprovSession.Effects {
        override fun publishState(state: ImprovState) {
            Log.i(TAG, "state -> $state")
            ble?.setState(state)
            status = "${state.name.lowercase()} ($reason)"
            if (state == ImprovState.PROVISIONED) {
                main.removeCallbacks(delayedStop)
                main.postDelayed(delayedStop, STOP_DELAY_MS)
            }
            refreshOverlay()
        }

        override fun publishError(error: ImprovError) {
            if (error != ImprovError.NONE) Log.w(TAG, "error -> $error")
            ble?.setError(error)
        }

        override fun sendResult(packet: ByteArray) {
            Log.i(TAG, "RPC result ${packet.size} bytes: cmd=${packet[0]}")
            ble?.sendResult(packet)
        }

        override fun identify() {
            Log.i(TAG, "identify")
            ScreenController.wake("improv identify")
            if (!overlay.isShown) refreshOverlay()
            overlay.flash()
            beep(3)
        }

        override fun startWifi(ssid: String, password: String) {
            connectingSsid = ssid
            failure = null
            progress = null
            refreshOverlay()
            wifi.connect(ssid, password) { outcome -> main.post { onWifiOutcome(outcome) } }
        }

        override fun scanNetworks(): List<ScannedNetwork> = wifi.scan()

        override fun deviceInfo(): List<String> = listOf(
            // Build.MODEL on the panel is the SoC vendor's "QUAD-CORE R818 EVB2"; HARDWARE = sun50iw10p1.
            FIRMWARE, BuildConfig.VERSION_NAME, "Aqara Panel Hub S1 Plus/${Build.HARDWARE}", DEVICE_NAME,
        )

        override fun maxResultSize(): Int = ble?.maxNotify()?.coerceAtMost(Improv.MAX_PACKET) ?: Improv.MAX_PACKET
    }

    private fun onWifiOutcome(o: WifiProvisioner.Outcome) {
        val s = session ?: return
        val now = SystemClock.elapsedRealtime()
        when (o) {
            is WifiProvisioner.Outcome.Connected -> {
                provisionedUrl = WifiSecurityRules.redirectUrl(o.ip)
                Log.i(TAG, "connected to '${o.ssid}' via ${o.method}; ip=${o.ip}; redirect=$provisionedUrl")
                s.onWifiResult(true, provisionedUrl, now)
            }
            is WifiProvisioner.Outcome.Failed -> {
                failure = o.reason + (o.restored?.let { ". Back on $it" } ?: "")
                Log.w(TAG, "connect failed: $failure")
                s.onWifiResult(false, null, now)
                windowEndsAt = maxOf(windowEndsAt, now + 2 * 60_000L)
            }
        }
        refreshOverlay()
    }

    // ---- BLE events

    private val bleListener = object : ImprovBleServer.Listener {
        override fun onRpcWrite(chunk: ByteArray) {
            session?.onRpcWrite(chunk, SystemClock.elapsedRealtime())
        }

        override fun onClientConnected(address: String) {
            Log.i(TAG, "client connected: $address")
        }

        override fun onClientDisconnected(address: String) {
            Log.i(TAG, "client disconnected: $address")
            session?.onDisconnect()
        }

        override fun onAdvertising(ok: Boolean, detail: String) {
            if (ok) {
                Log.i(TAG, "advertising: $detail")
                status = "${session?.state?.name?.lowercase()} ($reason), advertising"
            } else {
                Log.e(TAG, "advertising failed: $detail")
                status = "advertising failed: $detail"
                if (session != null) stop("advertising failed")
            }
        }
    }

    // ---- timers

    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            val now = SystemClock.elapsedRealtime()
            s.tick(now)
            if (now >= windowEndsAt && s.state != ImprovState.PROVISIONING && s.state != ImprovState.PROVISIONED) {
                stop("window expired")
                return
            }
            if (overlay.isShown && s.state != ImprovState.PROVISIONED) ScreenController.userActivity()
            if (s.state == ImprovState.AUTHORIZED) refreshOverlay() // countdown
            main.postDelayed(this, 1000)
        }
    }

    private val delayedStop = Runnable { stop("provisioned") }

    private val bootCheck = Runnable {
        if (!hasNetwork() && prefs.improvAuto) {
            Log.i(TAG, "no network ${BOOT_GRACE_MS / 1000}s after start: starting Improv")
            start("no network after boot", isAuto = true)
        } else {
            Log.i(TAG, "network present after boot; Improv not needed")
        }
    }

    private fun hasNetwork(): Boolean = runCatching {
        app.getSystemService(ConnectivityManager::class.java).activeNetwork != null
    }.getOrDefault(false)

    /** Auto mode: if a network shows up by other means, report PROVISIONED and stop (like ESPHome). */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    val s = session ?: return@post
                    if (s.state == ImprovState.AUTHORIZATION_REQUIRED || s.state == ImprovState.AUTHORIZED) {
                        Log.i(TAG, "network appeared externally; ending provisioning")
                        s.onProvisionedExternally()
                    }
                }
            }
        }
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb) }
            .onSuccess { netCallback = cb }
    }

    private fun unwatchNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
    }

    // ---- physical authorization alternatives

    private val proximityListener: () -> Unit = {
        val n = ScreenController.near
        if (n && !lastNear && prefs.improvProximityAuth && session?.state == ImprovState.AUTHORIZATION_REQUIRED) {
            authorize("proximity")
        }
        lastNear = n
    }

    private val activityTracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(a: Activity) { resumed = WeakReference(a) }
        override fun onActivityPaused(a: Activity) { if (resumed?.get() === a) resumed = null }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    private fun beep(times: Int) {
        val tg = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return
        for (k in 0 until times) main.postDelayed({ runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150) } }, k * 400L)
        main.postDelayed({ tg.release() }, times * 400L + 300)
    }

    // ---- overlay

    private fun refreshOverlay() {
        val s = session ?: return overlay.hide()
        val m = when (s.state) {
            ImprovState.AUTHORIZATION_REQUIRED -> ImprovOverlay.Model(
                label = "Wi-Fi setup · Bluetooth",
                title = "Allow Wi-Fi setup over Bluetooth?",
                body = (if (auto) "This panel has no network. " else "") +
                    "Home Assistant (Improv via BLE) or improv-wifi.com on a phone can send it Wi-Fi details. " +
                    "Only allow this if you're setting it up now.",
                primary = "Allow" to { authorize("button") },
                secondary = "Not now" to { stop("dismissed on panel") },
            )
            ImprovState.AUTHORIZED -> {
                val left = ((s.authorizedUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
                val fail = failure
                ImprovOverlay.Model(
                    label = "Wi-Fi setup · allowed for ${left / 60}:${"%02d".format(left % 60)}",
                    title = if (fail != null) "Couldn't connect to $connectingSsid" else "Ready for Wi-Fi details",
                    body = if (fail != null) "$fail. Check the password and send it again." else
                        "Open the “Improv via BLE” discovery for this panel in Home Assistant and enter your Wi-Fi." +
                            if (!wifi.legacyApiAllowed) " You'll then be asked to approve the network in a notification." else "",
                    secondary = "Stop" to { stop("stopped on panel") },
                    accent = if (fail != null) ImprovOverlay.ROSE else ImprovOverlay.SKY,
                )
            }
            ImprovState.PROVISIONING -> ImprovOverlay.Model(
                label = "Wi-Fi setup",
                title = "Connecting to $connectingSsid…",
                body = progress ?: "This takes up to 30 seconds.",
                accent = ImprovOverlay.AMBER,
                busy = true,
            )
            ImprovState.PROVISIONED -> ImprovOverlay.Model(
                label = "Wi-Fi setup · done",
                title = if (connectingSsid.isNotEmpty()) "Connected to $connectingSsid" else "Panel is online",
                body = provisionedUrl?.let { "Continue setup at $it" } ?: "Wi-Fi is set up.",
                primary = "Done" to { overlay.hide() },
                accent = ImprovOverlay.GREEN,
            )
        }
        overlay.show(m)
    }
}
