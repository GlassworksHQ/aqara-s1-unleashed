package sh.edm.s1kiosk.timers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.TimerBoard
import sh.edm.s1kiosk.timers.DockMath.Pt
import sh.edm.s1kiosk.timers.DockState.Mode

class TimerDockTest {
    // The panel in dp: 960×480, a two-timer group 176×106, 16 dp margin.
    private val sw = 960f; private val sh = 480f; private val gw = 176f; private val gh = 106f; private val m = 16f

    @Test
    fun slotsKeepClearOfTheEdges() {
        assertEquals(Pt(16f, 16f), DockMath.slot(Anchor.TOP_LEFT, sw, sh, gw, gh, m))
        assertEquals(Pt(392f, 16f), DockMath.slot(Anchor.TOP, sw, sh, gw, gh, m))
        assertEquals(Pt(768f, 16f), DockMath.slot(Anchor.TOP_RIGHT, sw, sh, gw, gh, m))
        assertEquals(Pt(16f, 187f), DockMath.slot(Anchor.LEFT, sw, sh, gw, gh, m))
        assertEquals(Pt(768f, 187f), DockMath.slot(Anchor.RIGHT, sw, sh, gw, gh, m))
        assertEquals(Pt(16f, 358f), DockMath.slot(Anchor.BOTTOM_LEFT, sw, sh, gw, gh, m))
        assertEquals(Pt(392f, 358f), DockMath.slot(Anchor.BOTTOM, sw, sh, gw, gh, m))
        assertEquals(Pt(768f, 358f), DockMath.slot(Anchor.BOTTOM_RIGHT, sw, sh, gw, gh, m))
    }

    @Test
    fun aDropSnapsToTheNearestSpot() {
        fun near(x: Float, y: Float) = DockMath.nearest(x, y, sw, sh, gw, gh, m)
        assertEquals(Anchor.TOP_LEFT, near(150f, 92f)) // mock-up 09-1
        assertEquals(Anchor.TOP, near(380f, 60f))
        assertEquals(Anchor.RIGHT, near(700f, 200f))
        assertEquals(Anchor.BOTTOM, near(420f, 300f))
        assertEquals(Anchor.BOTTOM_RIGHT, near(900f, 470f))
        assertEquals(Anchor.LEFT, near(0f, 190f))
        // Every slot snaps to itself.
        for (a in Anchor.entries) {
            val s = DockMath.slot(a, sw, sh, gw, gh, m)
            assertEquals(a, near(s.x, s.y))
        }
    }

    @Test
    fun draggingStaysOnScreen() {
        assertEquals(Pt(0f, 0f), DockMath.clamp(-50f, -9f, sw, sh, gw, gh))
        assertEquals(Pt(784f, 374f), DockMath.clamp(2000f, 900f, sw, sh, gw, gh))
        assertEquals(Pt(300f, 200f), DockMath.clamp(300f, 200f, sw, sh, gw, gh))
    }

    @Test
    fun hiddenTimersTuckIntoTheirSideOrTheTopOrBottom() {
        assertEquals(Edge.RIGHT, Anchor.BOTTOM_RIGHT.edge)
        assertEquals(Edge.RIGHT, Anchor.RIGHT.edge)
        assertEquals(Edge.LEFT, Anchor.TOP_LEFT.edge)
        assertEquals(Edge.TOP, Anchor.TOP.edge)
        assertEquals(Edge.BOTTOM, Anchor.BOTTOM.edge)
    }

    @Test
    fun theSoonestTimerSitsNearestTheAnchorsSide() {
        assertEquals(listOf("c", "b", "a"), DockMath.order(listOf("a", "b", "c"), Anchor.BOTTOM_RIGHT))
        assertEquals(listOf("a", "b", "c"), DockMath.order(listOf("a", "b", "c"), Anchor.TOP_LEFT))
        assertEquals(listOf("a", "b", "c"), DockMath.order(listOf("a", "b", "c"), Anchor.BOTTOM))
    }

    @Test
    fun anchorsParseWithBottomRightAsDefault() {
        assertEquals(Anchor.TOP, Anchor.parse("TOP"))
        assertEquals(Anchor.BOTTOM_RIGHT, Anchor.parse(null))
        assertEquals(Anchor.BOTTOM_RIGHT, Anchor.parse("nonsense"))
    }

    // ---------------------------------------------------------------- hide / come back

    private val now = 100_000L
    private fun t(id: String, left: Int, active: Boolean = true) = TimerBoard.Shown(id, id, now + left * 1000L, active, left, 600)

    @Test
    fun hiddenStaysHiddenUntilBroughtBack() {
        val s = DockState()
        val list = listOf(t("a", 300), t("b", 500))
        assertEquals(setOf("a", "b"), s.onTimers(list))
        assertEquals(Mode.EXPANDED, s.mode(list, now))
        s.hide()
        assertEquals(Mode.COLLAPSED, s.mode(list, now))
        assertEquals(emptySet<String>(), s.onTimers(listOf(t("a", 299), t("b", 499)))) // updates: still hidden
        assertEquals(Mode.COLLAPSED, s.mode(list, now))
        s.show()
        assertEquals(Mode.EXPANDED, s.mode(list, now))
    }

    @Test
    fun theLastTenSecondsComeOutThenHideAgain() {
        val s = DockState()
        s.onTimers(listOf(t("a", 9), t("b", 500)))
        s.hide()
        assertEquals(Mode.EXPANDED, s.mode(listOf(t("a", 9), t("b", 500)), now))
        // "a" finished (done screen); "b" goes on, still hidden.
        s.onTimers(listOf(t("b", 490)))
        assertTrue(s.hidden)
        assertEquals(Mode.COLLAPSED, s.mode(listOf(t("b", 490)), now))
        // A paused timer near its end doesn't count.
        assertEquals(Mode.COLLAPSED, s.mode(listOf(t("b", 5, active = false)), now))
    }

    @Test
    fun aNewTimerOrNoTimersBringItBack() {
        val s = DockState()
        s.onTimers(listOf(t("a", 300)))
        s.hide()
        assertEquals(setOf("b"), s.onTimers(listOf(t("a", 290), t("b", 600))))
        assertFalse(s.hidden)
        s.hide()
        s.onTimers(emptyList())
        assertFalse(s.hidden)
        assertEquals(Mode.NONE, s.mode(emptyList(), now))
        s.hide() // nothing to hide
        assertFalse(s.hidden)
    }
}
