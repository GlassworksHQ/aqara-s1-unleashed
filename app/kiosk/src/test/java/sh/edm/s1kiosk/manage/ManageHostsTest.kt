package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.web.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.esphome.ManualExecutor
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.esphome.RecordingMdns
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.voice.VoicePrefs
import java.net.InetAddress

/** The Manage page answers to the panel's ESPHome name as `.local` (#260), and not to another panel's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManageHostsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })
    private val runtime = GlassworksRuntime(AqaraS1Plus())
    private val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)

    private fun status(host: String) = manage.server.handle(HttpRequest("GET", "/manage/code", emptyMap(), mapOf("host" to "$host:8099"))).status

    /** The device KioskService runs, started, with its engines off. */
    private fun startDevice(): PanelDevice {
        prefs.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        prefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        val exec = ManualExecutor()
        return PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = RecordingMdns(), apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress()).also { it.start(); exec.runDue(); runtime.device = it }
    }

    @After fun tearDown() { runtime.device?.stop(); runtime.device = null }

    @Test fun itAnswersToTheNameItsDeviceAnnounced() {
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        assertEquals("no device running: no name yet", 400, status("aqara-s1-plus-a1b2c3.local"))
        startDevice()
        assertNotEquals(400, status("aqara-s1-plus-a1b2c3.local"))
        assertEquals("another panel's name", 400, status("aqara-s1-plus-d4e5f6.local"))
        assertEquals("the old shared name", 400, status("s1-panel.local"))
    }
}
