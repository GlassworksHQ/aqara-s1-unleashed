package sh.edm.s1kiosk.radio

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Action
import sh.edm.s1kiosk.radio.ui.RadioFollowUp.Request
import sh.edm.s1kiosk.radio.ui.RadioSectionView
import sh.edm.s1kiosk.radio.ui.RadioSetupScreen
import sh.edm.s1kiosk.ui.Mockup

/**
 * The radio's own step in setup (#63 round 2, verifier 4bc205dc): it holds until its follow-up's
 * Continue, a preview never reaches the radio or Home Assistant, and setup leaves out the controls that
 * ask first (it has nowhere to ask).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class RadioSetupScreenTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = KioskPrefs(app)
    private val init = TestInit()

    private fun definition(): RadioDefinition {
        val c = RadioController(init, load = { prefs.radio.mode }, store = { prefs.radio.mode = it }, inline = true, log = {}, filesDir = tmp.root)
        return radioDefinition(app, TestReadout(c, prefs))
    }

    /** A card that keeps its buttons, so the test can press them. */
    private class Card : SetupCard {
        var title = ""; var primary: (() -> Unit)? = null; var secondary: (() -> Unit)? = null
        override fun title(text: String) { title = text }
        override fun body(text: String?) {}
        override fun status(text: String?, tone: SetupCard.Tone) {}
        override fun qr(url: String?) {}
        override fun content(view: View?) {}
        override fun primary(text: String, enabled: Boolean, onClick: () -> Unit) { primary = onClick }
        override fun secondary(text: String?, onClick: () -> Unit) { secondary = if (text == null) null else onClick }
        override fun tightButtons() {}
    }

    private fun host(preview: Boolean, onNext: () -> Unit = {}) = object : SetupHost {
        override val preview = preview
        override fun redraw() {}
        override fun next() = onNext()
    }

    @Test
    fun changeTheModeKeepsTheStepUntilItsCardIsClosed() {
        val d = definition()
        prefs.radio.mode = RadioMode.ZIGBEE_TCP.id // applied: the controller stores it before the helper answers
        d.followUpOpen = true
        var next = 0
        val screen = RadioSetupScreen(Mockup.activity(), host(false) { next++ }, d)
        val card = Card()
        screen.bind(card, entered = true)
        card.secondary!!() // "Change the mode"
        assertFalse(d.followUpOpen)
        assertFalse(d.step.done()) // before: done, and setup ended on Done
        d.answering = false; d.followUpOpen = true
        screen.bind(card, entered = true)
        card.primary!!() // the follow-up's Continue
        assertTrue(d.step.done())
        assertEquals(1, next)
    }

    @Test
    fun aPreviewsChangeTheModeLeavesTheLiveStepAlone() {
        // Verifier 6d0f2cd7: a preview's tap closed the live follow-up, and setup went to Done.
        val d = definition()
        prefs.radio.mode = RadioMode.ZIGBEE_TCP.id
        d.followUpOpen = true // the live setup shows the card
        val screen = RadioSetupScreen(Mockup.activity(), host(true), d)
        val card = Card()
        screen.bind(card, entered = true)
        card.secondary!!() // "Change the mode", in the preview
        assertTrue(d.followUpOpen)
        assertFalse(d.answering)
        assertFalse(d.step.done())
    }

    @Test
    fun continueHoldsTheStepWhileTheRadioApplies() {
        val d = definition()
        val screen = RadioSetupScreen(Mockup.activity(), host(false), d)
        val card = Card()
        screen.bind(card, entered = true)
        card.primary!!() // Continue on the mode: the request runs on its thread
        prefs.radio.mode = RadioMode.ZIGBEE_TCP.id // stored before the helper's answer
        assertFalse(d.step.done()) // before: the 2 s tick read this as done and showed Done
    }

    @Test
    fun aPreviewNeverReachesTheRadioOrHomeAssistant() {
        val d = definition()
        val screen = RadioSetupScreen(Mockup.activity(), host(true), d)
        val before = HashMap(init.props)
        val answers = ArrayList<String?>()
        for (r in listOf(Request.Act(Action.THREAD_JOIN_HA), Request.Act(Action.THREAD_LEAVE), Request.Act(Action.ROUTER_JOIN),
            Request.Act(Action.ROUTER_LEAVE), Request.Stack(ZigbeeStack.SISDK), Request.Mode(RadioMode.THREAD))) {
            screen.section.onRun!!(r) { answers += it }
        }
        assertEquals(List(6) { RadioSetupScreen.PREVIEW_INERT }, answers)
        assertEquals(RadioSetupScreen.PREVIEW_INERT, screen.section.onDataset!!("0e080000000000010000"))
        assertEquals(before, init.props) // no property, no service, nothing
        assertFalse(sh.edm.s1kiosk.auth.HaAuth.threadJoin.state.let { it is sh.edm.s1kiosk.auth.ThreadJoin.State.Working })
        // ... and the setup step itself stays as it was.
        assertFalse(d.answering)
    }

    @Test
    fun setupLeavesOutTheControlsThatAskFirst() {
        fun shownTexts(v: View): List<String> = buildList {
            fun walk(x: View) {
                if (x is TextView && x.isShown) add(x.text.toString())
                if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
            }
            walk(v)
        }
        val facts = RadioFollowUp.Facts(
            RadioMode.ZIGBEE_TCP, "ok Zigbee coordinator (TCP): listening on :6638", HelperFacts(true, 4), ZigbeeStack.AQARA,
            null, null, "http://homeassistant.local:8123", "192.0.2.10",
        )
        val a = Mockup.activity()
        val inSetup = RadioSectionView(a)
        a.setContentView(inSetup.panel, ViewGroup.LayoutParams(-1, -1))
        inSetup.bind(facts)
        Mockup.idle()
        assertFalse(shownTexts(inSetup.panel).any { it.startsWith("Silicon Labs") }) // the stack asks first
        val b = Mockup.activity()
        val inSettings = RadioSectionView(b).apply { onAsk = { _, _ -> } }
        b.setContentView(inSettings.panel, ViewGroup.LayoutParams(-1, -1))
        inSettings.bind(facts)
        Mockup.idle()
        assertTrue(shownTexts(inSettings.panel).any { it.startsWith("Silicon Labs") })
    }
}
