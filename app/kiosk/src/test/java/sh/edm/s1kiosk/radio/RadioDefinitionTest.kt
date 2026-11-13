package sh.edm.s1kiosk.radio

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.ui.RadioFollowUp

/** Init without a root helper unless asked: `ctl.start/stop` flip init.svc.*, other props are stored. */
internal class TestInit(helper: Boolean = false) : SysProps {
    val props = HashMap<String, String>()

    init {
        for (s in AqaraRadio.ALL) props["init.svc.$s"] = "running" // stock boot state
        if (helper) {
            props[RadioHelper.PROP_SVC_STATE] = "running"
            props[RadioHelper.PROP_VERSION] = "4"
        }
    }

    override fun get(name: String) = props[name] ?: ""
    override fun set(name: String, value: String) {
        when (name) {
            "ctl.start" -> props["init.svc.$value"] = "running"
            "ctl.stop" -> props["init.svc.$value"] = "stopped"
            "ctl.restart" -> {}
            else -> props[name] = value
        }
    }
    override fun sleep(ms: Long) {}
}

/** The app's readout over a controller the test owns. */
internal class TestReadout(var controller: RadioController?, private val prefs: KioskPrefs) : RadioReadout {
    override fun controller() = controller
    override fun mode() = controller?.mode ?: RadioMode.parse(prefs.radio.mode)
    override fun facts(): RadioFollowUp.Facts? = null
    override fun haBase() = prefs.haBase
    override fun panelIp() = "192.0.2.10"
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RadioDefinitionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = KioskPrefs(app)

    private fun controller(init: SysProps = TestInit()) = RadioController(
        init, load = { prefs.radio.mode }, store = { prefs.radio.mode = it }, inline = true, log = {}, filesDir = tmp.root,
    )

    private fun definition(c: RadioController? = null) = radioDefinition(app, TestReadout(c, prefs))

    @Test
    fun aFreshPanelGetsTheStep() {
        val d = definition()
        assertFalse(d.step.done())
        assertEquals("radio mode not chosen", d.step.reason)
        // Its card on the Panes step: off, the modes as chips, the coordinator offered first.
        val c = d.setupChoice
        assertFalse(c.on())
        assertEquals(listOf("Zigbee coordinator", "Thread border router", "Zigbee repeater β", "Thread repeater β"), c.options.map { it.label })
        assertEquals(RadioMode.ZIGBEE_TCP.id, c.option())
    }

    @Test
    fun continueWithTheModeInEffectAnswersTheStep() {
        val d = definition(controller())
        assertFalse(d.step.done())
        assertNull(d.setupChoice.apply(false, null)) // Off, as it is: nothing to ask the radio
        assertTrue(d.step.done())
    }

    @Test
    fun aPanelSetUpBeforeTheStepNeverSeesIt() {
        prefs.urlSetting = "http://homeassistant.local:8123/" // a saved dashboard URL is a trace of setup
        assertTrue(definition().step.done())
    }

    @Test
    fun theOneTimeCheckRunsOnce() {
        definition()
        prefs.urlSetting = "http://homeassistant.local:8123/" // set up after the first start: still asks
        assertFalse(definition().step.done())
    }

    @Test
    fun aModeChosenInHomeAssistantAnswersIt() {
        val d = definition()
        prefs.radio.mode = RadioMode.THREAD.id
        assertTrue(d.step.done())
        assertTrue(d.setupChoice.on())
        assertEquals(RadioMode.THREAD.id, d.setupChoice.option())
    }

    @Test
    fun setModeOverTheChannelAnswersTheStep() {
        val c = controller().also { it.start() }
        val d = definition(c)
        assertEquals("Unknown radio mode 'nonsense'.", d.step.ops.getValue("set_mode").run(org.json.JSONObject("""{"mode":"nonsense"}""")))
        // No root helper: only Off works, and a refusal doesn't answer the step.
        val refused = d.step.ops.getValue("set_mode").run(org.json.JSONObject("""{"mode":"zigbee_tcp"}"""))
        assertTrue(refused, refused!!.contains("root helper"))
        assertFalse(d.step.done())
        assertNull(d.step.ops.getValue("set_mode").run(org.json.JSONObject("""{"mode":"off"}""")))
        assertTrue(d.step.done())
    }

    @Test
    fun withoutTheServiceTheChannelSaysSo() {
        val d = definition()
        assertEquals(
            "The radio controller isn't running yet; try again in a moment.",
            d.step.ops.getValue("set_mode").run(org.json.JSONObject("""{"mode":"off"}""")),
        )
        val s = d.step.channelState()!!
        assertEquals("off", s.getString("mode"))
        assertEquals("The radio controller isn't running yet", s.getString("status"))
        assertFalse(s.getBoolean("helper"))
        assertFalse(s.has("allowed"))
    }

    @Test
    fun aDatasetTheRadioTookIsNoError() {
        // The settings menu's dataset field: null means the radio took it. It used to show
        // "The kiosk service isn't running yet" after a dataset was stored.
        val d = definition(controller())
        assertNull(d.setDataset(DATASET))
        assertEquals("The kiosk service isn't running yet; try again in a moment.", definition().setDataset(DATASET))
        assertTrue(definition(controller()).setDataset("not hex")!!.isNotEmpty())
    }

    @Test
    fun settingsWithAndWithoutTheController() {
        val running = definition(controller()).settings().single()
        assertEquals("radio", running.id)
        assertTrue(running.items.first() is dev.glassworks.pane.SettingsItem.Native)
        val stopped = definition().settings().single()
        assertTrue(stopped.items.none { it is dev.glassworks.pane.SettingsItem.Native })
    }

    private companion object {
        /** Hex the radio accepts: an active timestamp TLV only, no network key or other values. */
        const val DATASET = "0e080000000000010000"
    }
}
