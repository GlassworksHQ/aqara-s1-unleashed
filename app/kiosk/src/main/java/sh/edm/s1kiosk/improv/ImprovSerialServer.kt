package sh.edm.s1kiosk.improv

import dev.glassworks.frame.Frame
import sh.edm.improv.*
import android.util.Log
import sh.edm.s1kiosk.InstallerGlue
import sh.edm.s1kiosk.auth.OAuth
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Improv Serial for the web installer on `@s1kiosk_improv` (adb `localabstract:`), started by
 * KioskService. Thin glue around [ImprovSerialSession]: joins go through
 * [ImprovController.connectFromInstaller], so the setup screen's Wi-Fi step shows their progress
 * exactly like any installer join; scans come from WifiManager ([ImprovController.scanFromInstaller]).
 */
object ImprovSerialServer {
    /** Abstract socket name (adb: `localabstract:s1kiosk_improv`). */
    const val SOCKET = "s1kiosk_improv"
    private const val TAG = "S1Kiosk.ImprovSerial"
    /** The client waits 20 s for a scan; Wi-Fi may have to be turned on first. */
    private const val SCAN_WAIT_MS = 15_000L

    private var server: ImprovAdbServer? = null

    /** [frame]: the device it speaks for ([ImprovIdentity.serial]). */
    fun start(frame: Frame) {
        if (server != null) return
        val info = ImprovIdentity.serial(frame)
        server = ImprovAdbServer(SOCKET, TAG) { output -> Effects(output, info) }.also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private class Effects(private val out: OutputStream, private val info: List<String>) : ImprovSerialSession.Effects {
        override fun send(packet: ByteArray) = synchronized(out) { out.write(packet); out.flush() }

        override fun online(): Boolean = InstallerGlue.wlanIpv4() != null

        override fun redirectUrl(): String = redirectFor(InstallerGlue.wlanIpv4())

        override fun info(): ByteArray = info.let { (firmware, version, chip, name) -> ImprovSerial.info(firmware, version, chip, name) }

        override fun scan(): List<SerialNetwork> {
            val got = AtomicReference<List<ScannedNetwork>>(emptyList())
            val done = CountDownLatch(1)
            val started = InstallerGlue.onMain {
                ImprovController.scanFromInstaller { nets -> got.set(nets); done.countDown() }
            } == true
            if (!started) return emptyList()
            done.await(SCAN_WAIT_MS + 1_000L, TimeUnit.MILLISECONDS)
            return got.get().map { SerialNetwork(it.ssid, it.rssi, secure = it.auth != "NO") }
        }

        override fun connect(ssid: String, password: String, done: (redirect: String?) -> Unit) {
            InstallerGlue.post {
                val ok = ImprovController.connectFromInstaller(ssid, password, hidden = null) { o ->
                    when (o) {
                        is WifiProvisioner.Outcome.Connected -> done(redirectFor(o.ip ?: InstallerGlue.wlanIpv4()))
                        is WifiProvisioner.Outcome.Failed -> done(null)
                    }
                }
                if (!ok) {
                    Log.w(TAG, "Improv controller not ready; Wi-Fi not joined")
                    done(null)
                }
            }
        }
    }

    /** The panel's sign-in page, the same link as its QR code ("" without an address). */
    fun redirectFor(ip: String?): String = ip?.let { "http://$it:${OAuth.SIGNIN_PORT}/signin" } ?: ""

    internal const val SCAN_TIMEOUT_MS = SCAN_WAIT_MS
}
