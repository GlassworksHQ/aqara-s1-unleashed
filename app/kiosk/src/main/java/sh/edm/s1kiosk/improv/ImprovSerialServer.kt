package sh.edm.s1kiosk.improv

import android.util.Log
import sh.edm.s1kiosk.AdbSocketServer
import sh.edm.s1kiosk.BuildConfig
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
    private const val TAG = "S1Kiosk.ImprovSerial"
    private const val FIRMWARE = "S1 Kiosk"
    private const val CHIP = "Allwinner A133"
    private const val DEVICE_NAME = "S1 Panel"
    /** The client waits 20 s for a scan; Wi-Fi may have to be turned on first. */
    private const val SCAN_WAIT_MS = 15_000L

    private var server: AdbSocketServer? = null

    fun start() {
        if (server != null) return
        server = AdbSocketServer(ImprovSerial.SOCKET, TAG, ::serve).also { it.start() }
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun serve(input: java.io.InputStream, output: OutputStream) {
        val session = ImprovSerialSession(Effects(output)) { Log.i(TAG, it) }
        val buf = ByteArray(512)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) session.onBytes(buf, n)
        }
    }

    private class Effects(private val out: OutputStream) : ImprovSerialSession.Effects {
        override fun send(packet: ByteArray) = synchronized(out) { out.write(packet); out.flush() }

        override fun online(): Boolean = InstallerGlue.wlanIpv4() != null

        override fun redirectUrl(): String = redirectFor(InstallerGlue.wlanIpv4())

        override fun info(): ByteArray = ImprovSerial.info(FIRMWARE, BuildConfig.VERSION_NAME, CHIP, DEVICE_NAME)

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
