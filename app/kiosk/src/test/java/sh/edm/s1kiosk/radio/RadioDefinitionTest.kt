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
        assertEquals("Radio", d.step.title) // its row under Panes
        // Its card on the Panes step only says on or off; the mode is its own sub-step.
        assertFalse(d.setupChoice.on())
    }

    @Test
    fun onLeavesTheModeToTheRadiosOwnStep() {
        val d = definition(controller())
        assertNull(d.setupChoice.apply(true))
        assertFalse(d.step.done()) // the radio's sub-step under Panes still asks
    }

    @Test
    fun theFollowUpHoldsTheStepUntilContinue() {
        val d = definition()
        prefs.radio.mode = RadioMode.THREAD.id // the mode is applied...
        d.followUpOpen = true // ...and setup shows what to do next
        assertFalse(d.step.done())
        d.followUpOpen = false
        assertTrue(d.step.done())
    }

    @Test
    fun continueWithTheModeInEffectAnswersTheStep() {
        val d = definition(controller())
        assertFalse(d.step.done())
        assertNull(d.setupChoice.apply(false)) // Off, as it is: nothing to ask the radio
        assertTrue(d.step.done())
    }

    @Test
    fun offOnARadioInAnErrorAsksTheRadioAgain() {
        // F10: the mode is already Off, but Aqara's services wouldn't stop. Off on the Panes step must
        // not take the shortcut ("already Off") but ask the radio again, and say what it answered.
        val stuck = object : SysProps by TestInit() {
            override fun set(name: String, value: String) {} // ctl.stop does nothing: the services keep running
        }
        prefs.radio.mode = RadioMode.OFF.id
        val c = controller(stuck).also { it.start() }
        assertTrue(c.statusText(), c.statusText().startsWith("error"))
        val d = definition(c)
        val answer = d.setupChoice.apply(false)
        assertTrue("$answer", answer != null && answer.startsWith("error")) // the shortcut would say null
    }

    @Test
    fun settingsSwitchAsksBeforeOffAndOnShowsTheRoles() {
        // #63 round 2: the radio's on/off at the top right of its page.
        prefs.radio.mode = RadioMode.ZIGBEE_TCP.id
        val d = definition()
        val master = d.settings().single().master!!
        assertTrue(master.value())
        assertEquals("Turn the radio off?", master.confirm(false)!!.title)
        prefs.radio.mode = RadioMode.OFF.id
        assertNull(master.confirm(false)) // already off: nothing to ask
        assertFalse(master.value())
        var answer: String? = "unanswered"
        master.save(true) { answer = it }
        assertNull(answer)
        assertTrue(d.choosing) // on, with no role yet: the roles show, the radio stays off
        assertTrue(master.value())
    }

    @Test
    fun offWhileARoleIsStillQueuedWins() {
        // CodeRabbit on #163: the role request runs on the radio thread and sets the mode only then. Off in
        // that window read "already off", sent nothing, and the queued role started the radio after it.
        val base = TestInit(helper = true)
        val gate = java.util.concurrent.CountDownLatch(1)
        val hold = java.util.concurrent.atomic.AtomicBoolean(false)
        val props = object : SysProps by base {
            override fun get(name: String): String { if (hold.get()) gate.await(5, java.util.concurrent.TimeUnit.SECONDS); return base.get(name) }
        }
        prefs.radio.mode = RadioMode.OFF.id
        val c = RadioController(props, load = { prefs.radio.mode }, store = { prefs.radio.mode = it }, inline = false, log = {}, filesDir = tmp.root)
        val d = radioDefinition(app, TestReadout(c, prefs))
        hold.set(true) // the radio thread stalls on its next property read
        c.request(RadioMode.ZIGBEE_TCP.label, "test") // the role, queued
        assertEquals(RadioMode.OFF, c.mode) // not applied yet
        assertTrue(c.requestPending)
        val master = d.settings().single().master!!
        master.save(false) {} // Off, in that window
        hold.set(false)
        gate.countDown()
        val until = System.currentTimeMillis() + 10_000
        while ((c.requestPending || c.mode != RadioMode.OFF) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse(c.requestPending)
        assertEquals(RadioMode.OFF, c.mode) // Off ran after the role
    }

    @Test
    fun cantRunReadsOnlyTheRadiosCachedFacts() {
        // CodeRabbit on #163: the menu asks on the main thread; helperInstalled() may spawn getprop.
        val base = TestInit(helper = false)
        var reads = 0
        val props = object : SysProps by base { override fun get(name: String): String { reads++; return base.get(name) } }
        val c = RadioController(props, load = { prefs.radio.mode }, store = { prefs.radio.mode = it }, inline = true, log = {}, filesDir = tmp.root)
        val d = radioDefinition(app, TestReadout(c, prefs))
        val section = d.settings().single()
        reads = 0
        assertEquals(null, section.unavailable()) // before the radio's first refresh: not known, not "can't run"
        assertEquals(0, reads)
        c.refresh()
        reads = 0
        assertEquals(RadioDefinition.NO_HELPER, section.unavailable())
        assertEquals(0, reads) // no property read on the caller's thread
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
