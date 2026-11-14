package sh.edm.s1kiosk.esphome

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.voice.VoicePrefs
import java.io.File
import java.net.InetAddress

/**
 * What the S1 announces to Home Assistant (#260): `aqara-s1-plus-<the reported MAC's last three bytes>`
 * as the node name and "Aqara S1 Plus" as the friendly name, the same on every start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelNameTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val runtime = GlassworksRuntime(AqaraS1Plus())
    private val devices = ArrayList<PanelDevice>()

    @Before fun setUp() {
        prefs.paneFile("assist").edit().putBoolean(VoicePrefs.KEY_ENABLED, false).commit()
        prefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
    }

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    /** Starts a device as KioskService does, stops it, and returns what it announced. */
    private fun announced(): sh.edm.esphome.DeviceIdentity {
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.start()
        exec.runDue()
        device.stop()
        return mdns.identities.single()
    }

    @Test fun theNodeNameIsTheFrameAndTheReportedMacsLastThreeBytes() {
        prefs.deviceMac = "02:00:00:A1:B2:C3"
        val id = announced()
        assertEquals("aqara-s1-plus-a1b2c3", id.name)
        assertEquals("Aqara S1 Plus", id.friendlyName)
        assertEquals("02:00:00:A1:B2:C3", id.macAddress)
    }

    @Test fun aNewPanelKeepsItsNameAcrossRestarts() {
        val first = announced()
        assertTrue(first.name, first.name.startsWith("aqara-s1-plus-"))
        assertEquals("the suffix is the MAC HA sees", first.macAddress.takeLast(8).replace(":", "").lowercase(), first.name.takeLast(6))
        assertEquals(first.name, announced().name)
    }

    @Test fun theFrameIsNamedAsItsFrameJson() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "frames").isDirectory }
        val json = JSONObject(File(repo, "frames/aqara-s1-plus/design/frame.json").readText())
        val frame = AqaraS1Plus()
        assertEquals(json.getString("id"), frame.board)
        assertEquals(json.getString("name"), frame.displayName)
    }
}
