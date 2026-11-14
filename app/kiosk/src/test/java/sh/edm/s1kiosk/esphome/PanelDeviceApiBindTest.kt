package sh.edm.s1kiosk.esphome

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
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
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * The panel as KioskService builds it listens on every address, so Home Assistant reaches it on the
 * LAN (#134). Tests elsewhere bind the loopback address; nothing else would notice if production did.
 * And the address a PanelDevice is given must reach the server: a listener on every address also
 * answers on loopback, so the tests that bind loopback wouldn't notice if it were dropped (#180).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceApiBindTest {

    @Test fun theProductionApiServerAnswersOnANonLoopbackAddress() {
        val lan = lanAddress()
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

    @Test fun aServerBoundToLoopbackRefusesTheLanAddress() {
        val lan = lanAddress()
        assumeTrue("this machine has no non-loopback IPv4 address", lan != null)
        val mdns = RecordingMdns()
        val device = PanelDevice(RuntimeEnvironment.getApplication(), GlassworksRuntime(AqaraS1Plus()),
            onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = ManualExecutor(), mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        try {
            device.startInternal()
            val port = mdns.starts.single().port
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000) }
            try {
                Socket().use { it.connect(InetSocketAddress(lan, port), 2_000) }
                fail("a server bound to loopback accepted a connection on $lan:$port")
            } catch (_: ConnectException) {}
        } finally {
            device.stop()
        }
    }

    /** `adb forward tcp:6053` listens on 127.0.0.1 only; the port isn't free for this test then. */
    @Test fun aPortWithALoopbackListenerIsNotFree() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { held ->
            assertFalse("port ${held.localPort} has a listener on 127.0.0.1", portFree(held.localPort))
        }
    }

    private fun lanAddress(): InetAddress? = NetworkInterface.getNetworkInterfaces().asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { it is Inet4Address && !it.isLinkLocalAddress }

    /**
     * SO_REUSEADDR off: a JDK ServerSocket turns it on by default, and with it macOS binds every
     * address next to a listener on 127.0.0.1, so the test would run and take the port from it.
     */
    private fun portFree(port: Int) = try {
        ServerSocket().use { it.reuseAddress = false; it.bind(InetSocketAddress(null as InetAddress?, port)) }; true
    } catch (_: IOException) { false }
}
