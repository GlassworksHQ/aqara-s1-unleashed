package sh.edm.s1kiosk.improv

import sh.edm.s1kiosk.AdminReceiver
import android.content.ComponentName
import sh.edm.improv.*
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
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.ScreenController
import sh.edm.s1kiosk.setup.ImprovFacts
import sh.edm.s1kiosk.setup.InstallerWifi
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
 * Where "Allow" is shown: while the setup screen is up ([inlineHost] says so), Improv is part of its
 * Wi-Fi step ([setupFacts] → setup/WifiStep) and the [ImprovOverlay] window stays hidden. Otherwise
 * (started later on demand, from the intent or HA's button) the overlay is shown as before.
 *
 * It also runs Wi-Fi joins sent from the computer over Improv Serial ([connectFromInstaller],
 * [ImprovSerialServer]) on the same [WifiProvisioner], so the two never fight over the Wi-Fi
 * configuration.
 *
 * Main thread only.
 */
@SuppressLint("StaticFieldLeak")
object ImprovController {
    // Keep the library's BLE logs under the tag the docs use (`adb logcat -s S1Kiosk.Improv`).
    init { ImprovBleServer.logTag = "S1Kiosk.Improv"; WifiProvisioner.logTag = "S1Kiosk.Improv" }

    private const val TAG = "S1Kiosk.Improv"
    /** How long after a join's result a process restart still waits (the result must get out first). */
    private const val SETTLE_MS = 8_000L
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

    /** Improv's state while it is running (advertising), else null. For the setup screen. */
    val activeState: ImprovState? get() = if (ble != null) session?.state else null
    /** SSID being joined while provisioning ("" if none). */
    val joiningSsid: String get() = connectingSsid

    /** The setup screen, while it can show Improv inline. Main thread. */
    interface InlineHost {
        /** True while Improv is shown inline (the setup screen is up and visible): no overlay then. */
        fun showsImprovInline(): Boolean
        /** Improv (or an installer Wi-Fi join) changed; re-read [setupFacts] / [installerWifi]. */
        fun onImprovChanged()
        /** Identify was requested over BLE while shown inline. */
        fun onImprovIdentify() {}
    }

    var inlineHost: InlineHost? = null
        set(v) { field = v; if (initialized) refreshOverlay() }

    /** Improv as the setup screen's Wi-Fi step sees it (pure data, see setup/WifiStep). */
    val setupFacts: ImprovFacts
        get() {
            val s = session
            if (!initialized || s == null) return ImprovFacts(startError = startError)
            return ImprovFacts(
                running = true,
                advertising = advertising,
                clients = clients.size,
                state = s.state,
                authLeftMs = if (s.state == ImprovState.AUTHORIZED) s.authorizedUntil - SystemClock.elapsedRealtime() else 0L,
                ssid = connectingSsid,
                progress = progress,
                failure = failure,
                needsApproval = !wifi.legacyApiAllowed,
            )
        }

    /** The last Wi-Fi join sent from the computer (web installer), null if none in this process. */
    var installerWifi: InstallerWifi? = null
        private set

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
    private var advertising = false
    private val clients = HashSet<String>()
    /** Why the last start failed / advertising stopped by itself; cleared by the next start. */
    private var startError: String? = null
    /** The Wi-Fi attempt in flight belongs to the installer (progress goes there), not to Improv. */
    private var installerJoining = false

    /** Until when a finished join's result may still be on its way to the installer or HA (ms, elapsedRealtime). */
    @Volatile private var settleUntil = 0L

    /**
     * A Wi-Fi join (from the installer or over Bluetooth) is in flight, or its result was sent moments
     * ago. MainActivity must not restart the process then: joining Wi-Fi is exactly when Home Assistant
     * gets discovered, and a restart would cut the Improv connection before the result arrives.
     */
    val busy: Boolean
        get() = installerJoining || session?.state == ImprovState.PROVISIONING ||
            SystemClock.elapsedRealtime() < settleUntil
    /** Installer requests in this process; an outcome only updates [installerWifi] if it's the latest. */
    private var installerSeq = 0
    private var notifying = false

    /** One-line status for logs / diagnostics. */
    @Volatile var status: String = "off"
        private set

    val active: Boolean get() = session?.started == true

    fun init(ctx: Context) {
        sh.edm.s1kiosk.setup.WifiSetupStatus.provider = { c -> ImprovWifiStatus(c) }
        if (initialized) return
        initialized = true
        app = ctx.applicationContext
        prefs = KioskPrefs(app)
        wifi = WifiProvisioner(app, main, ComponentName(app, AdminReceiver::class.java)).also {
            it.onProgress = { p ->
                val inst = installerWifi
                if (installerJoining && inst != null) installerWifi = inst.copy(detail = p) else progress = p
                refreshOverlay()
            }
        }
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
     * Intent extras (adb: `am start -n sh.edm.s1kiosk/.improv.ImprovActivity --ez improv true`):
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
        startError = null; advertising = false; clients.clear()
        windowEndsAt = now + WINDOW_MS
        s.start(requireAuthorization = true, now = now)
        if (!server.start(s.state, s.capabilities)) {
            session = null
            overlay.hide()
            status = "failed to start"
            if (startError == null) startError = "Bluetooth didn't start"
            notifyHost()
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
        if (s.state == ImprovState.PROVISIONING && !installerJoining) wifi.cancel()
        s.stop()
        session = null
        ble?.stop()
        unwatchNetwork()
        main.removeCallbacks(tick)
        main.removeCallbacks(delayedStop)
        overlay.hide()
        advertising = false
        clients.clear()
        status = "off ($why)"
        notifyHost()
    }

    fun authorize(source: String) {
        val s = session ?: return
        Log.i(TAG, "authorized by $source")
        failure = null
        s.authorize(SystemClock.elapsedRealtime())
    }

    /**
     * Joins [ssid] with the Wi-Fi details sent from the computer (the web installer, over Improv
     * Serial: [ImprovSerialServer]), on the same [WifiProvisioner] as Improv via BLE (it turns Wi-Fi
     * on first if it is off). Progress shows in the setup screen's Wi-Fi step through
     * [installerWifi]; [done] gets the outcome (main thread), also when a newer request superseded
     * this one. [psk] is never logged. Returns false if the controller isn't up yet (KioskService
     * not created).
     */
    fun connectFromInstaller(
        ssid: String,
        psk: String,
        hidden: Boolean? = null,
        done: ((WifiProvisioner.Outcome) -> Unit)? = null,
    ): Boolean {
        if (!initialized) return false
        if (session?.state == ImprovState.PROVISIONING) {
            // The installer wins; Improv's attempt reports "superseded" and asks for the details again.
            Log.i(TAG, "installer Wi-Fi supersedes the Improv attempt")
        }
        val seq = ++installerSeq
        Log.i(TAG, "installer Wi-Fi: joining '$ssid'")
        installerWifi = InstallerWifi(ssid, InstallerWifi.Phase.CONNECTING)
        installerJoining = true
        ScreenController.wake("installer wifi")
        refreshOverlay()
        wifi.connect(ssid, psk, hidden) { outcome -> main.post { onInstallerOutcome(seq, ssid, outcome); done?.invoke(outcome) } }
        return true
    }

    /**
     * A fresh Wi-Fi scan for the installer (turns Wi-Fi on if it is off); [done] on the main thread
     * with what WifiManager reports. False if the controller isn't up yet.
     */
    fun scanFromInstaller(done: (List<ScannedNetwork>) -> Unit): Boolean {
        if (!initialized) return false
        wifi.scanFresh(ImprovSerialServer.SCAN_TIMEOUT_MS, done)
        return true
    }

    private fun onInstallerOutcome(seq: Int, ssid: String, o: WifiProvisioner.Outcome) {
        settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
        val result = when (o) {
            is WifiProvisioner.Outcome.Connected -> {
                Log.i(TAG, "installer Wi-Fi: connected to '$ssid'")
                InstallerWifi(ssid, InstallerWifi.Phase.CONNECTED, o.ip)
            }
            is WifiProvisioner.Outcome.Failed -> {
                val why = o.reason + (o.restored?.let { ". Back on $it" } ?: "")
                Log.i(TAG, "installer Wi-Fi: '$ssid' failed: $why")
                InstallerWifi(ssid, InstallerWifi.Phase.FAILED, why)
            }
        }
        if (seq != installerSeq) return // superseded by a newer request: that one is on screen
        installerJoining = false
        installerWifi = result
        refreshOverlay()
    }

    /** The host's visibility changed: show or hide the overlay accordingly. */
    fun refreshUi() {
        if (initialized) refreshOverlay()
    }

    private fun notifyHost() {
        if (notifying) return
        notifying = true
        try { inlineHost?.onImprovChanged() } catch (t: Throwable) { Log.w(TAG, "setup screen update failed", t) }
        finally { notifying = false }
    }

    private fun inline(): Boolean = runCatching { inlineHost?.showsImprovInline() == true }.getOrDefault(false)

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
            if (state == ImprovState.PROVISIONED) settleUntil = SystemClock.elapsedRealtime() + SETTLE_MS
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
            if (inline()) {
                inlineHost?.onImprovIdentify()
            } else {
                if (!overlay.isShown) refreshOverlay()
                overlay.flash()
            }
            beep(3)
        }

        override fun startWifi(ssid: String, password: String) {
            connectingSsid = ssid
            failure = null
            progress = null
            installerJoining = false
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
            if (session != null) clients += address
            refreshOverlay()
        }

        override fun onClientDisconnected(address: String) {
            Log.i(TAG, "client disconnected: $address")
            clients -= address
            session?.onDisconnect()
            refreshOverlay()
        }

        override fun onAdvertising(ok: Boolean, detail: String) {
            if (ok) {
                Log.i(TAG, "advertising: $detail")
                status = "${session?.state?.name?.lowercase()} ($reason), advertising"
                if (session != null) advertising = true
                refreshOverlay()
            } else {
                Log.e(TAG, "advertising failed: $detail")
                status = "advertising failed: $detail"
                startError = detail
                if (session != null) stop("advertising failed") else notifyHost()
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
            if ((overlay.isShown || inline()) && s.state != ImprovState.PROVISIONED) ScreenController.userActivity()
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
        notifyHost() // first: it may bring up the setup screen (offline + Improv running)
        val s = session ?: return overlay.hide()
        if (inline()) return overlay.hide()
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
