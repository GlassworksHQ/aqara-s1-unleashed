package sh.edm.s1kiosk.setup

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Mockup
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Setup's Panes group (#63 round 2): the Panes step only chooses which panes are on, Continue applies
 * every card; a pane that is on then has its own steps under Panes. And moving between steps: a step
 * that is done says so with Continue, the rail and Continue move in a preview.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupPanesTest {

    private val applied = Collections.synchronizedList(ArrayList<String>())
    private var voice = true
    private var radioOn = false

    /** A pane step that only shows its title, answered with its own Continue. */
    private fun step(id: String, title: String) = object : SetupStep {
        override val id = id
        override val title = title
        override val reason = "$id not set up"
        override fun done() = false
        override fun screen(context: Context, host: SetupHost) = object : SetupScreen {
            override fun bind(card: SetupCard, entered: Boolean) {
                card.title("$title, set up")
                card.body(null)
                card.content(null)
                card.primary("Continue") { host.next() }
            }
        }
    }

    private val radioStep = step("radio", "Radio")

    private val panes = listOf(
        SetupPane(PaneChoice("Voice", "Assist and timers", on = { voice }, restartsApp = true, apply = { on -> applied += "voice $on"; null }), emptyList()),
        SetupPane(PaneChoice("Zigbee / Thread radio", "For your devices", on = { radioOn }, apply = { on -> applied += "radio $on"; null }), listOf(radioStep)),
    )

    private fun model(step: String, done: Set<String> = SetupFlow.ids(true).takeWhile { it != step }.toSet(), preview: Boolean = false, online: Boolean = true) =
        SetupOverlay.Model(
            step = step, done = done,
            wifi = if (online) WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi") else WifiSetupStatus.Snapshot(WifiSetupStatus.State.NOT_CONNECTED),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true, preview = preview,
        )

    private fun overlay(ps: List<SetupPane> = panes, m: SetupOverlay.Model = model(SetupFlow.PANES)): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, ps)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(m)
        Mockup.idle()
        return s
    }

    private fun <T : View> all(root: View, type: Class<T>): List<T> = buildList {
        fun walk(v: View) {
            if (type.isInstance(v) && v.isShown) add(type.cast(v)!!)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** The text view on screen that says exactly [text]. */
    private fun shown(root: View, text: String): TextView? = all(root, TextView::class.java).firstOrNull { it.text.toString() == text }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < until) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50)); Thread.sleep(10) }
        assertEquals(what, true, cond())
    }

    /** Taps Continue on Panes and waits for the cards to be applied; the answer onPanesDone got. */
    private fun continueAndWait(s: SetupOverlay): Boolean? {
        var restart: Boolean? = null
        val done = CountDownLatch(1)
        s.onPanesDone = { restart = it; done.countDown() }
        shown(s, "Continue")!!.performClick()
        val until = System.currentTimeMillis() + 5_000
        while (done.count > 0 && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            done.await(20, TimeUnit.MILLISECONDS)
        }
        return restart
    }

    @Test
    fun panesOnlyChoosesAndContinueAppliesEveryCard() {
        val s = overlay()
        assertEquals(null, shown(s, "Zigbee coordinator")) // no mode here: the radio's own step asks it
        assertFalse(s.wanted("radio"))
        all(s, Glass.Toggle::class.java)[1].performClick() // the radio on
        assertEquals(false, continueAndWait(s))
        assertEquals(setOf("voice true", "radio true"), applied.toSet()) // each card on its own thread: any order
        assertTrue(s.panesChosen)
        assertTrue(s.wanted("radio")) // its step now sits under Panes
    }

    @Test
    fun turningVoiceOffAsksForARestart() {
        val s = overlay()
        all(s, Glass.Toggle::class.java)[0].performClick()
        assertEquals(true, continueAndWait(s))
        assertEquals(setOf("voice false", "radio false"), applied.toSet()) // any order
    }

    @Test
    fun everyProblemShowsAndTheStepHolds() {
        val failing = listOf(
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { "the player didn't start" }), emptyList()),
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { "the microphone is busy" }), emptyList()),
        )
        val s = overlay(failing)
        var called = false
        s.onPanesDone = { called = true }
        shown(s, "Continue")!!.performClick()
        waitFor("refused") { shown(s, "Music: the player didn't start\nVoice: the microphone is busy") != null }
        assertFalse(called)
        assertTrue(s.holdsPanes)
    }

    @Test
    fun aCardThatDoesntAnswerIsNamed() {
        val stuck = CountDownLatch(1)
        val slow = listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { stuck.await(10, TimeUnit.SECONDS); null }), emptyList()))
        val s = overlay(slow)
        s.panesBudgetMs = 300
        shown(s, "Continue")!!.performClick()
        waitFor("named") { shown(s, "The Music didn't answer.") != null }
        assertTrue(s.holdsPanes) // there is a way out: try again, or turn it off
        assertTrue(shown(s, "Continue")!!.isEnabled)
        stuck.countDown()
    }

    @Test
    fun cardsAreLockedWhileContinueApplies() {
        val release = CountDownLatch(1)
        var music = true
        val s = overlay(listOf(SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { music }, apply = { on -> release.await(5, TimeUnit.SECONDS); music = on; null }), emptyList())))
        shown(s, "Continue")!!.performClick()
        waitFor("applying") { s.holdsPanes }
        val t = all(s, Glass.Toggle::class.java)[0]
        t.performClick() // the person taps while it applies
        assertEquals(true, t.checked) // nothing changes
        release.countDown()
        waitFor("done") { !s.holdsPanes }
        assertEquals(true, music)
    }

    @Test
    fun aRefusalHoldsTheStepAndARestartStaysOwed() {
        var refuse = true
        val s = overlay(listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { voice }, restartsApp = true, apply = { on -> voice = on; null }), emptyList()),
            SetupPane(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { if (refuse) "the player didn't start" else null }), emptyList()),
        ))
        var restart: Boolean? = null
        s.onPanesDone = { restart = it }
        all(s, Glass.Toggle::class.java)[0].performClick() // voice off: needs a restart
        shown(s, "Continue")!!.performClick()
        waitFor("refused") { shown(s, "Music: the player didn't start") != null }
        assertTrue(s.holdsPanes)
        assertEquals(null, restart)
        refuse = false
        shown(s, "Continue")!!.performClick() // voice is already saved off: the restart is still owed
        waitFor("done") { restart != null }
        assertEquals(true, restart)
        assertFalse(s.holdsPanes)
    }

    @Test
    fun aPreviewNeverAppliesAndMovesOn() {
        radioOn = true
        val s = overlay(m = model(SetupFlow.PANES, preview = true))
        shown(s, "Continue")!!.performClick()
        Mockup.idle()
        assertEquals(emptyList<String>(), applied)
        assertTrue(shown(s, "Radio, set up") != null) // on to the radio's own step
    }

    @Test
    fun aWifiStepThatIsDoneLeadsWithContinue() {
        // Reopened on a connected panel (a preview here): Continue moves on, another network is second.
        val s = overlay(m = model(SetupFlow.WIFI, done = setOf(SetupFlow.WIFI), preview = true))
        assertTrue(shown(s, "Connected to Example-WiFi.") != null)
        assertTrue(shown(s, "Use another network") != null)
        shown(s, "Continue")!!.performClick()
        Mockup.idle()
        assertTrue(shown(s, "Add it in Home Assistant.") != null || shown(s, "Added to Home Assistant.") != null)
    }

    @Test
    fun theRailMovesAPreviewAndHoldsALiveSetup() {
        // A preview: any row, by touch.
        val p = overlay(m = model(SetupFlow.WIFI, done = emptySet(), preview = true, online = false))
        all(p, View::class.java).first { it.contentDescription == "Home Assistant" && it.isClickable }.performClick()
        Mockup.idle()
        assertTrue(shown(p, "Add it in Home Assistant.") != null)
        // Live: a step that isn't reached yet doesn't open.
        val live = overlay(m = model(SetupFlow.WIFI, done = emptySet(), online = false))
        all(live, View::class.java).firstOrNull { it.contentDescription == "Home Assistant" && it.isClickable }?.performClick()
        Mockup.idle()
        assertTrue(shown(live, "Connect to Wi-Fi.") != null)
    }
}
