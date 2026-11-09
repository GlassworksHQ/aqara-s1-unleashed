package sh.edm.s1kiosk.improv

import android.content.Context
import android.os.Handler
import android.os.Looper
import sh.edm.s1kiosk.setup.ConnectivityWifiStatus
import sh.edm.s1kiosk.setup.WifiSetupStatus

/**
 * Setup-screen Wi-Fi status that adds Improv's progress on top of plain connectivity:
 * "Waiting for Wi-Fi details over Bluetooth" while advertising, "Joining <ssid>…" while provisioning.
 */
class ImprovWifiStatus(context: Context) : WifiSetupStatus {
    private val base = ConnectivityWifiStatus(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private var onChange: (() -> Unit)? = null
    private val poll = object : Runnable {
        override fun run() { onChange?.invoke(); main.postDelayed(this, 1000) }
    }

    override fun snapshot(): WifiSetupStatus.Snapshot {
        val s = base.snapshot()
        return when (ImprovController.activeState) {
            ImprovState.PROVISIONING -> s.copy(
                state = WifiSetupStatus.State.CONNECTING,
                message = "Joining ${ImprovController.joiningSsid.ifBlank { "the network" }}…",
            )
            ImprovState.AUTHORIZATION_REQUIRED, ImprovState.AUTHORIZED -> if (s.online) s else s.copy(
                message = "Waiting for Wi-Fi details over Bluetooth: Home Assistant will show " +
                    "\"Improv via BLE\" discovered, or use improv-wifi.com on a phone.",
            )
            else -> s
        }
    }

    override fun observe(onChange: ((() -> Unit))?) {
        this.onChange = onChange
        base.observe(onChange)
        main.removeCallbacks(poll)
        if (onChange != null) main.postDelayed(poll, 1000) // Improv state has no listener; poll cheaply
    }
}
