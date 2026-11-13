package sh.edm.s1kiosk.esphome

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiServer
import sh.edm.esphome.Msg
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.frame.AqaraS1Plus
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * The panel as KioskService builds it listens on every address, so Home Assistant reaches it on the
 * LAN (#134). Tests elsewhere bind the loopback address; nothing else would notice if production did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceApiBindTest {

    @Test fun theProductionApiServerAnswersOnANonLoopbackAddress() {
        val lan = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }
        assumeTrue("this machine has no non-loopback IPv4 address", lan != null)
        val port = ApiServer.DEFAULT_PORT
        assumeTrue("port $port is in use on this machine", portFree(port))

        val device = PanelDevice(RuntimeEnvironment.getApplication(), GlassworksRuntime(AqaraS1Plus()), onVoiceEvent = {})
        try {
            device.startInternal()
            Socket().use { s ->
                s.connect(InetSocketAddress(lan, port), 2_000)
                s.soTimeout = 2_000
                s.getOutputStream().write(byteArrayOf(0, 4, Msg.HELLO_REQUEST.toByte(), 0x0a, 2, 't'.code.toByte(), 't'.code.toByte()))
                assertEquals("the API server on $lan:$port answers Hello with a plaintext frame", 0, s.getInputStream().read())
            }
        } finally {
            device.stop()
        }
    }

    private fun portFree(port: Int) = try {
        ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(null as InetAddress?, port)) }; true
    } catch (_: IOException) { false }
}
