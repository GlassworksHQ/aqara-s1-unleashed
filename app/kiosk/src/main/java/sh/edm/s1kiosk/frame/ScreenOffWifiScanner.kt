package sh.edm.s1kiosk.frame

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import sh.edm.s1kiosk.frame.WifiScanPolicy.Link

/**
 * Starts Wi-Fi scans while the screen is off and Wi-Fi is enabled but not connected, as
 * [WifiScanPolicy] says. Without them the S1 does not reconnect until the screen wakes (#3).
 *
 * `startScan` needs the location permission, location on, and the location app-op allowed. With
 * the screen off, Android 10 allows that op only because KioskService runs with the location type
 * ([sh.edm.s1kiosk.KioskForeground]). A refused scan returns false, and the scanner logs why.
 *
 * Runs on its own thread, or on [looper] when given. [start] after [stop] starts again.
 */
class ScreenOffWifiScanner(
    context: Context,
    private val looper: Looper? = null,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    companion object {
        const val TAG = "S1Kiosk.Wifi"
        private const val RETRY_MS = 120_000L

        private val CONNECTING = setOf(
            SupplicantState.AUTHENTICATING, SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED,
            SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE,
        )
    }

    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val policy = WifiScanPolicy()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var loggedFailure = false

    private val due = Runnable { step() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = step()
    }

    @Synchronized fun start() {
        if (handler != null) return
        val h = Handler(looper ?: HandlerThread("s1-wifi-scan").also { it.start(); thread = it }.looper)
        handler = h
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }, null, h)
        h.post(due)
    }

    @Synchronized fun stop() {
        val h = handler ?: return
        handler = null
        runCatching { ctx.unregisterReceiver(receiver) }
        h.removeCallbacks(due)
        thread?.quitSafely()
        thread = null
    }

    /** On the handler's thread. The lock covers the policy only, so [stop] never waits on a binder call. */
    private fun step() {
        val h = synchronized(this) { handler } ?: return
        val w = guard("reading the Wi-Fi state") { read() } ?: run {
            h.removeCallbacks(due)
            h.postDelayed(due, RETRY_MS)
            return
        }
        val wasScanning: Boolean
        val d: WifiScanPolicy.Decision
        val scans: Int?
        synchronized(this) {
            if (handler !== h) return
            wasScanning = policy.scans != null
            d = policy.decide(w, clock())
            scans = policy.scans
        }
        if (scans == null && wasScanning) Log.i(TAG, "screen-off scans stop: ${why(w, scans)}")
        if (d.scanNow) {
            @Suppress("DEPRECATION") val ok = guard("startScan") { wifi.startScan() } ?: false
            Log.i(TAG, "screen off, ${why(w, scans)}: scan $scans " +
                (if (ok) "started" else "refused (${refusal()})") + ", next in ${d.wakeInMs!! / 1000} s")
        } else if (scans != null && !wasScanning) {
            Log.i(TAG, "screen off, ${why(w, scans)}: first scan in ${d.wakeInMs!! / 1000} s")
        }
        h.removeCallbacks(due)
        d.wakeInMs?.let { h.postDelayed(due, it) }
    }

    private fun read(): WifiScanPolicy.Wifi {
        @Suppress("DEPRECATION") val s = wifi.connectionInfo?.supplicantState
        return WifiScanPolicy.Wifi(
            enabled = wifi.isWifiEnabled,
            link = when (s) {
                SupplicantState.COMPLETED -> Link.CONNECTED
                in CONNECTING -> Link.CONNECTING
                else -> Link.DOWN
            },
            screenOn = power.isInteractive,
        )
    }

    private fun why(w: WifiScanPolicy.Wifi, scans: Int?) = when {
        w.screenOn -> "screen on"
        !w.enabled -> "Wi-Fi off"
        w.link == Link.CONNECTED -> "Wi-Fi connected"
        w.link == Link.CONNECTING && scans == null -> "Wi-Fi connecting"
        w.link == Link.CONNECTING -> "Wi-Fi stuck connecting"
        else -> "Wi-Fi not connected"
    }

    @Suppress("DEPRECATION")
    private fun refusal(): String = WifiScanPolicy.refusal(
        permission = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        locationOn = runCatching { Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE) }.getOrDefault(1) != 0,
        opAllowed = runCatching {
            ctx.getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(true),
        deviceIdle = power.isDeviceIdleMode,
    )

    private fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (t: Throwable) {
        if (!loggedFailure) Log.w(TAG, "$what failed", t) else Log.w(TAG, "$what failed: $t")
        loggedFailure = true
        null
    }
}
