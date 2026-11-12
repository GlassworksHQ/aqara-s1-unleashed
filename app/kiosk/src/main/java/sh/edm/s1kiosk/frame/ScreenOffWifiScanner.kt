package sh.edm.s1kiosk.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log

/**
 * Starts Wi-Fi scans while the screen is off and Wi-Fi is enabled but not connected, as
 * [WifiScanPolicy] says. Without them the S1 does not reconnect until the screen wakes (#3).
 *
 * `startScan` needs the location permission and location on (Android 10 refuses the scan
 * otherwise). It returns false then, and the scanner logs it.
 */
class ScreenOffWifiScanner(context: Context) {
    companion object {
        const val TAG = "S1Kiosk.Wifi"

        /** Associated or on the way there: the framework is already connecting, no scan needed. */
        private val CONNECTING = setOf(
            SupplicantState.AUTHENTICATING, SupplicantState.ASSOCIATING, SupplicantState.ASSOCIATED,
            SupplicantState.FOUR_WAY_HANDSHAKE, SupplicantState.GROUP_HANDSHAKE, SupplicantState.COMPLETED,
        )
    }

    private val ctx = context.applicationContext
    private val wifi = ctx.getSystemService(WifiManager::class.java)
    private val power = ctx.getSystemService(PowerManager::class.java)
    private val policy = WifiScanPolicy()
    private val thread = HandlerThread("s1-wifi-scan").apply { start() }
    private val handler = Handler(thread.looper)

    private val due = Runnable { step(scanDue = true) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = step(scanDue = false)
    }

    fun start() {
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }, null, handler)
    }

    fun stop() {
        runCatching { ctx.unregisterReceiver(receiver) }
        thread.quitSafely()
    }

    private fun read() = WifiScanPolicy.Wifi(
        enabled = wifi.isWifiEnabled,
        connecting = @Suppress("DEPRECATION") wifi.connectionInfo?.supplicantState in CONNECTING,
        screenOn = power.isInteractive,
    )

    private fun step(scanDue: Boolean) {
        val wasScanning = policy.scans != null
        val w = read()
        when (val next = if (scanDue) policy.onDue(w) else policy.onChange(w)) {
            WifiScanPolicy.Next.Stop -> {
                handler.removeCallbacks(due)
                if (wasScanning) Log.i(TAG, "screen-off scans stop: " + if (w.screenOn) "screen on" else if (!w.enabled) "Wi-Fi off" else "Wi-Fi connecting")
            }
            WifiScanPolicy.Next.Keep -> Unit
            is WifiScanPolicy.Next.ScanIn -> {
                if (scanDue) {
                    @Suppress("DEPRECATION") val ok = wifi.startScan()
                    Log.i(TAG, "screen off, Wi-Fi not connected: scan ${policy.scans} " +
                        (if (ok) "started" else "refused (throttled, or no location permission or location off)") +
                        ", next in ${next.delayMs / 1000} s")
                } else {
                    Log.i(TAG, "screen off, Wi-Fi not connected: first scan in ${next.delayMs / 1000} s")
                }
                handler.removeCallbacks(due)
                handler.postDelayed(due, next.delayMs)
            }
        }
    }
}
