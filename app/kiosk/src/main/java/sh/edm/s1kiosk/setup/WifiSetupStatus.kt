package sh.edm.s1kiosk.setup

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * What the setup screen's Wi-Fi step shows. The default ([ConnectivityWifiStatus]) only looks at
 * Android connectivity. Improv (Wi-Fi over Bluetooth) can feed its own state instead by setting
 * [WifiSetupStatus.provider], e.g. "Waiting for Home Assistant over Bluetooth…" while advertising,
 * or "Joining <ssid>…" while connecting.
 */
interface WifiSetupStatus {

    enum class State { CONNECTED, NOT_CONNECTED, CONNECTING }

    data class Snapshot(
        val state: State,
        /** Network name when known (Android 10 hides it without location permission). */
        val ssid: String? = null,
        /** Extra line for the Wi-Fi step (Improv progress / errors); null = the default text. */
        val message: String? = null,
        /** Connected over Ethernet (wall base) rather than Wi-Fi. */
        val ethernet: Boolean = false,
    ) {
        val online: Boolean get() = state == State.CONNECTED
    }

    fun snapshot(): Snapshot

    /** [onChange] runs on the main thread whenever the snapshot may have changed; null stops. */
    fun observe(onChange: (() -> Unit)?)

    companion object {
        /** Set by other code (Improv) to replace the default; read when the setup screen starts. */
        @Volatile var provider: ((Context) -> WifiSetupStatus)? = null

        fun create(context: Context): WifiSetupStatus =
            provider?.invoke(context) ?: ConnectivityWifiStatus(context.applicationContext)
    }
}

/** Plain Android connectivity: online = an active network with INTERNET capability. */
class ConnectivityWifiStatus(private val ctx: Context) : WifiSetupStatus {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun snapshot(): WifiSetupStatus.Snapshot {
        val caps = try { cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) } } catch (_: Exception) { null }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED)
        }
        val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, ssid = if (ethernet) null else ssid(), ethernet = ethernet)
    }

    @Suppress("DEPRECATION")
    private fun ssid(): String? = try {
        val wm = ctx.getSystemService(WifiManager::class.java)
        unquoteSsid(wm?.connectionInfo?.ssid)
    } catch (_: Exception) {
        null
    }

    override fun observe(onChange: (() -> Unit)?) {
        callback?.let { runCatching { cm?.unregisterNetworkCallback(it) } }
        callback = null
        if (onChange == null || cm == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post(onChange) }
            override fun onLost(network: Network) { main.post(onChange) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post(onChange) }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            callback = cb
        } catch (_: Exception) {
        }
    }

    companion object {
        /** `"MyWifi"` → `MyWifi`; Android's placeholders for a hidden SSID → null. */
        fun unquoteSsid(raw: String?): String? {
            val s = raw?.trim() ?: return null
            if (s.isEmpty() || s == "<unknown ssid>" || s == "0x") return null
            return if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
        }
    }
}
