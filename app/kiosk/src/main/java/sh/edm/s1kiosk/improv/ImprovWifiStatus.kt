package sh.edm.s1kiosk.improv

import sh.edm.improv.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import sh.edm.s1kiosk.setup.ConnectivityWifiStatus
import sh.edm.s1kiosk.setup.InstallerWifi
import sh.edm.s1kiosk.setup.WifiSetupStatus

/**
 * Setup-screen Wi-Fi status on top of plain connectivity: CONNECTING with the SSID while Improv is
 * provisioning or a join sent from the computer (web installer) is running. Improv's own status
 * (Allow, waiting, errors) is shown by the Wi-Fi step from [ImprovController.setupFacts].
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
        if (s.online) return s
        val inst = ImprovController.installerWifi
        return when {
            inst?.phase == InstallerWifi.Phase.CONNECTING -> s.copy(state = WifiSetupStatus.State.CONNECTING, ssid = inst.ssid)
            ImprovController.activeState == ImprovState.PROVISIONING -> s.copy(
                state = WifiSetupStatus.State.CONNECTING,
                ssid = ImprovController.joiningSsid.ifBlank { null },
            )
            else -> s
        }
    }

    override fun observe(onChange: ((() -> Unit))?) {
        this.onChange = onChange
        base.observe(onChange)
        main.removeCallbacks(poll)
        if (onChange != null) main.postDelayed(poll, 1000) // the countdown; changes also arrive via InlineHost
    }
}
