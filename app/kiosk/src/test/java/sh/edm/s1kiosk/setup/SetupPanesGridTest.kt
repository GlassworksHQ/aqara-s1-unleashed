package sh.edm.s1kiosk.setup

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import dev.glassworks.pane.PaneChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * Setup's Panes step with many panes (#121, the maintainer's review): the cards sit two to a row in a
 * list that scrolls, by touch, above Continue, which stays put a section gap below the list. No
 * pane's name or line is ever cut short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text sizes: what fits is what the fonts leave
class SetupPanesGridTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    /** The panes once #118 and #121 are in, then two made up for a longer list. Longest name included. */
    private val names = listOf(
        "Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Notifications" to "On-screen alerts",
        "Zigbee / Thread radio" to "For your devices", "Bluetooth proxy" to "For BLE sensors", "Wall base" to "The S1's wired base",
        "Camera" to "The panel's camera in HA", "Matter" to "Join Matter devices",
    )

    private fun model() = SetupOverlay.Model(
        step = SetupFlow.PANES, done = setOf(SetupFlow.WIFI, SetupFlow.HA),
        wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
        haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true,
    )

    private fun step(n: Int): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, names.take(n).map { (t, d) -> SetupPane(PaneChoice(t, d, on = { false }, apply = { null }), emptyList()) })
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(model())
        Mockup.idle()
        Mockup.layout(s)
        return s
    }

    private fun rect(v: View) = Rect().also { v.getGlobalVisibleRect(it) }
    private fun whole(v: View) = Rect().let { r -> v.getGlobalVisibleRect(r) && r.height() == v.height && r.width() == v.width }
    private fun card(s: View, title: String): View = generateSequence(Mockup.label(s, title) as View) { it.parent as? View }.first { it.isClickable }
    private fun list(s: View): ScrollView = generateSequence(Mockup.label(s, names[0].first) as View) { it.parent as? View }.first { it is ScrollView } as ScrollView
    private fun cut(t: TextView) = t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } ?: true

    @Test fun continueStaysPutAndWholeWhateverTheNumberOfPanes() {
        val at = (1..8).map { n -> step(n).let { s -> Mockup.label(s, "Continue").also { assertTrue("$n panes: Continue cut off", whole(it)) }.let { rect(it).top } } }
        assertEquals("Continue doesn't move with the list: $at", 1, at.toSet().size)
    }

    @Test fun aSectionGapSeparatesTheListFromContinue() {
        for (n in listOf(4, 8)) {
            val s = step(n)
            val gap = rect(Mockup.label(s, "Continue")).top - rect(list(s)).bottom
            val min = (SetupOverlay.SECTION_GAP_DP * s.resources.displayMetrics.density).toInt()
            assertTrue("$n panes: $gap px between the list and Continue, want at least $min", gap >= min)
        }
    }

    @Test fun noNameOrLineIsCutShort() {
        for (n in listOf(4, 6, 8)) {
            val s = step(n)
            for ((title, detail) in names.take(n)) {
                assertFalse("$n panes: \"$title\" cut short", cut(Mockup.label(s, title)))
                assertFalse("$n panes: \"$detail\" cut short", cut(Mockup.label(s, detail)))
            }
        }
    }

    @Test fun twoCardsToARowEachAsTallAsItsRow() {
        val s = step(8)
        for (r in names.chunked(2)) assertEquals(r.toString(), card(s, r[0].first).height, card(s, r[1].first).height)
        assertEquals("two to a row", rect(card(s, "Voice")).top, rect(card(s, "Music")).top)
    }

    @Test fun eightPanesScrollByTouchToTheLastCard() {
        val s = step(8)
        val l = list(s)
        assertTrue("the list scrolls with 8 panes", l.canScrollVertically(1))
        assertFalse("the last card starts out of view", whole(card(s, "Matter")))
        val x = l.width / 2f
        val down = SystemClock.uptimeMillis()
        val from = l.height * 0.8f
        l.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, from, 0))
        for (k in 1..10) l.dispatchTouchEvent(MotionEvent.obtain(down, down + 16L * k, MotionEvent.ACTION_MOVE, x, from - k * l.height * 0.08f, 0))
        l.dispatchTouchEvent(MotionEvent.obtain(down, down + 200, MotionEvent.ACTION_UP, x, from - l.height * 0.8f, 0))
        Mockup.idle()
        assertTrue("a drag scrolled the list", l.scrollY > 0)
        l.scrollTo(0, l.getChildAt(0).height - l.height + l.paddingBottom + l.paddingTop)
        Mockup.idle()
        Mockup.layout(s)
        assertTrue("the last card in full", whole(card(s, "Matter")))
        assertTrue("Continue still in full", whole(Mockup.label(s, "Continue")))
    }

    @Test fun fourPanesNeedNoScroll() {
        val s = step(4)
        assertFalse(list(s).canScrollVertically(1))
        for ((t, _) in names.take(4)) assertTrue("$t in full", whole(card(s, t)))
    }
}
