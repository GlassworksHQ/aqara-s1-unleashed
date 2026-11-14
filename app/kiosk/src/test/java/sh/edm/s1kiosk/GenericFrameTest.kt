package sh.edm.s1kiosk

import android.app.Application
import android.content.pm.PackageManager
import dev.glassworks.frame.Capability
import dev.glassworks.frame.GenericAndroid
import dev.glassworks.pane.CantRun
import dev.glassworks.pane.PaneState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.esphome.ManualExecutor
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.esphome.RecordingMdns
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.improv.ImprovIdentity
import sh.edm.s1kiosk.manage.S1Manage
import sh.edm.s1kiosk.sendspin.SendspinPane
import sh.edm.s1kiosk.sendspin.SendspinPrefs
import sh.edm.s1kiosk.settings.SettingsMenu
import java.io.File
import java.net.InetAddress

/**
 * #23, minimal part: the app runs the S1's frame only on the S1's hardware. Any other device runs the
 * generic Android frame: it goes by its own maker and model everywhere it names itself, and runs the
 * panes its features allow. The S1 stays as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericFrameTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app)
    private val devices = ArrayList<PanelDevice>()
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "frames").isDirectory }

    private fun features(vararg on: String) {
        val pm = shadowOf(app.packageManager)
        for (f in listOf(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)) {
            pm.setSystemFeature(f, f in on)
        }
    }

    @Before fun aPixelTablet() {
        ShadowBuild.setManufacturer("Google")
        ShadowBuild.setModel("Pixel Tablet")
        ShadowBuild.setHardware("tangorpro")
        features(PackageManager.FEATURE_MICROPHONE, PackageManager.FEATURE_AUDIO_OUTPUT, PackageManager.FEATURE_BLUETOOTH_LE, PackageManager.FEATURE_CAMERA_ANY)
        prefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, false).commit()
        prefs.wallBase.backend = BaseBackend.LOCALSERVICE.pref
        prefs.deviceMac = "02:00:00:A1:B2:C3"
    }

    @After fun tearDown() = devices.forEach { runCatching { it.stop() } }

    private fun generic() = GlassworksRuntime(GenericAndroid(app))

    /** Starts the device the way KioskService does, and returns what it announced and the panes it built. */
    private fun run(runtime: GlassworksRuntime): Pair<DeviceIdentity, PanelDevice> {
        val exec = ManualExecutor()
        val mdns = RecordingMdns()
        val device = PanelDevice(app, runtime, onVoiceEvent = {}, onApiHello = { _, _ -> }, exec = exec, mdns = mdns, apiPort = 0,
            apiBindAddress = InetAddress.getLoopbackAddress())
        devices += device
        device.start()
        exec.runDue()
        return mdns.identities.single() to device
    }

    @Test fun theS1sHardwarePicksTheS1sFrameAndAnythingElseTheGenericOne() {
        val generic = GenericAndroid(app)
        assertTrue(AqaraS1Plus.orElse("sun50iw10p1") { generic } is AqaraS1Plus)
        for (hw in listOf("tangorpro", "qcom", "robolectric", "")) assertEquals(hw, generic, AqaraS1Plus.orElse(hw) { generic })
    }

    @Test fun aTabletAnnouncesItselfAsWhatItIs() {
        val (id, _) = run(generic())
        assertEquals("pixel-tablet-a1b2c3", id.name)
        assertEquals("Pixel Tablet", id.friendlyName)
        assertEquals("Google", id.manufacturer)
        assertEquals("Pixel Tablet (S1 Kiosk)", id.model)
        assertEquals("it has a microphone, so it offers Assist", PanelDevice.VOICE_FEATURES, id.voiceFeatures)
    }

    @Test fun theS1StillAnnouncesItselfAsAnS1() {
        val (id, _) = run(GlassworksRuntime(AqaraS1Plus()))
        assertEquals("aqara-s1-plus-a1b2c3", id.name)
        assertEquals("Aqara S1 Plus", id.friendlyName)
        assertEquals("Aqara", id.manufacturer)
        assertEquals("Panel Hub S1 Plus (S1 Kiosk)", id.model)
    }

    @Test fun aTabletRunsThePanesItsHardwareAllows() {
        val runtime = generic()
        val definitions = runtime.paneDefinitions(app)
        assertEquals("the S1 frame's own panes aren't there", listOf("assist", "sendspin", "radio", "bluetooth-proxy", "diagnostics"), definitions.map { it.id })
        assertEquals(listOf("radio"), definitions.filterIsInstance<CantRun>().map { it.id })
        assertFalse("setup asks nothing about a radio", runtime.setupSteps(app).any { it.id == "radio" })
        assertFalse(runtime.setupPanes(app).any { it.choice.title == "Radio" })

        val (_, device) = run(runtime)
        val built = device.paneList().map { it.id }
        assertEquals(listOf("sendspin", "assist", "timers", "bluetooth-proxy", "diagnostics"), built)
        assertFalse("no radio entities in Home Assistant", device.entityList().any { it.objectId.startsWith("radio_") })
    }

    @Test fun theManagePageSaysTheRadioCantRunAndWhy() {
        val tokens = HaTokenClient(object : HttpPoster {
            override fun postForm(url: String, body: String) = 0 to ""
            override fun get(url: String, maxBytes: Int) = 0 to ""
        })
        val status = S1Manage(app, generic(), SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs).status()
        assertEquals(PaneState(PaneState.Kind.CANT_RUN, "it has no Silicon Labs radio"), status.panes.single { it.id == "radio" }.state)
        assertEquals("Pixel Tablet", status.frame)
        assertEquals("Google Pixel Tablet", status.about.toMap()["Frame"])
    }

    @Test fun aDeviceWithoutAMicrophoneHasNoAssist() {
        features(PackageManager.FEATURE_AUDIO_OUTPUT)
        val runtime = generic()
        assertEquals(listOf("assist", "radio", "bluetooth-proxy"), runtime.paneDefinitions(app).filterIsInstance<CantRun>().map { it.id })
        val (id, device) = run(runtime)
        assertEquals("no voice features although Assist's switch is on", 0, id.voiceFeatures)
        assertFalse(device.paneList().any { it.id == "assist" })
        assertFalse(device.entityList().any { it.objectId == "mute_microphone" })
    }

    @Test fun musicAssistantAndImprovUseTheDevicesOwnName() {
        prefs.paneFile("sendspin").edit().putBoolean(SendspinPrefs.KEY_ENABLED, true).commit()
        for ((runtime, name, product, maker) in listOf(
            Quad(generic(), "Pixel Tablet", "Pixel Tablet (S1 Kiosk)", "Google"),
            Quad(GlassworksRuntime(AqaraS1Plus()), "Aqara S1 Plus", "Panel Hub S1 Plus (S1 Kiosk)", "Aqara"),
        )) {
            val (_, device) = run(runtime)
            val hello = (device.paneList().single { it.id == "sendspin" } as SendspinPane).player.hello()
            assertEquals(name, hello.name)
            assertEquals(product, hello.productName)
            assertEquals(maker, hello.manufacturer)
            assertEquals(name, ImprovIdentity.ble(runtime.frame)[3])
            assertEquals(name, ImprovIdentity.serial(runtime.frame)[3])
            device.stop()
        }
        assertEquals("Aqara Panel Hub S1 Plus/tangorpro", ImprovIdentity.ble(AqaraS1Plus())[2])
        assertEquals("Allwinner A133", ImprovIdentity.serial(AqaraS1Plus())[2])
        assertEquals("Google Pixel Tablet/tangorpro", ImprovIdentity.ble(GenericAndroid(app))[2])
        assertEquals("tangorpro", ImprovIdentity.serial(GenericAndroid(app))[2])
    }

    @Test fun theDeviceSettingsLinkNamesTheDevicesOwnApp() {
        assertEquals("Aqara's own settings", SettingsMenu.deviceSettingsTitle(AqaraS1Plus()))
        assertEquals("Android settings", SettingsMenu.deviceSettingsTitle(GenericAndroid(app)))
    }

    /** The data agrees with the code: the S1's `provides`, and each pane's `requires`, as in their JSON files. */
    @Test fun theFramesAndPanesMatchTheirJson() {
        fun words(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }.toSet()
        val s1 = JSONObject(File(repo, "frames/aqara-s1-plus/design/frame.json").readText())
        assertEquals(words(s1.getJSONArray("provides")), AqaraS1Plus().provides.map { it.id }.toSet())
        assertFalse("generic has no fixed list: it depends on the device", JSONObject(File(repo, "frames/generic-android/design/frame.json").readText()).has("provides"))

        val paneJson = File(repo, "app/kiosk/src/main/java").walkTopDown().filter { it.name == "pane.json" }
            .map { JSONObject(it.readText()) }.associate { it.getString("id") to words(it.getJSONArray("requires")) }
        val definitions = GlassworksRuntime(AqaraS1Plus()).paneDefinitions(app)
        for (d in definitions) assertEquals(d.id, paneJson.getValue(d.id), d.requires.map { it.id }.toSet())
        val known = Capability.entries.map { it.id }.toSet()
        for ((id, req) in paneJson) assertTrue("$id requires a word the app doesn't know: $req", known.containsAll(req))
    }

    private data class Quad(val runtime: GlassworksRuntime, val name: String, val product: String, val maker: String)
}
