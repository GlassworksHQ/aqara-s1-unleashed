package sh.edm.s1kiosk.timers

import android.app.Application
import dev.glassworks.pane.PanelEvent
import dev.glassworks.pane.PanelTimer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.TimerBoard
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimersPaneTest {
    private val jobs = ArrayList<CompletableFuture<Unit>>()
    private var played = 0
    private var silenced = 0
    private var woke = 0
    private var changed = 0

    private val pane = TimersPane(
        chimes = TimerChimes(
            schedule = { _, _ -> CompletableFuture<Unit>().also { jobs += it } as Future<*> },
            play = { played++ },
            silence = { silenced++ },
        ),
        ui = { it() },
        wakeScreen = { woke++ },
        screenChanged = { changed++ },
    )

    @Before fun setUp() = TimerBoard.reset()
    @After fun tearDown() = TimerBoard.reset()

    @Test
    fun aFinishedTimerShowsTheDoneScreenWakesTheScreenAndChimes() {
        pane.onEvent(PanelEvent.TimerFinished("Pasta"))
        assertEquals(listOf("Pasta"), TimerBoard.done.map { it.name })
        assertEquals(1, woke)
        assertEquals(1, changed)
        assertEquals(1, played)
        assertEquals(TimerChimes.COUNT - 1, jobs.size)
    }

    @Test
    fun theFinishedTimerKeepsItsLengthFromTheList() {
        pane.onEvent(PanelEvent.TimersChanged(listOf(PanelTimer("a", "Pasta", 600, 0, true))))
        pane.onEvent(PanelEvent.TimerFinished("Pasta"))
        pane.onEvent(PanelEvent.TimersChanged(emptyList()))
        assertEquals(600, TimerBoard.done.single().totalSeconds)
        assertTrue(TimerBoard.timers.isEmpty())
    }

    @Test
    fun changedTimersFeedTheBoardAndRefreshTheScreen() {
        pane.onEvent(PanelEvent.TimersChanged(listOf(PanelTimer("b", " Eggs ", 420, 220, true), PanelTimer("a", "", 60, 30, false))))
        assertEquals(listOf("a", "b"), TimerBoard.timers.map { it.id })
        assertEquals("Eggs", TimerBoard.timers.last().name)
        assertEquals(1, changed)
        assertEquals(0, woke)
        assertEquals(0, played)
    }

    @Test
    fun tappingTheDoneScreenStopsTheChimesStillToCome() {
        pane.start()
        pane.onEvent(PanelEvent.TimerFinished("Pasta"))
        TimerBoard.dismissFinished()
        assertEquals(1, silenced)
        assertTrue(jobs.all { it.isCancelled })
    }

    @Test
    fun withoutStartATapDoesNotTouchTheChimes() {
        pane.onEvent(PanelEvent.TimerFinished("Pasta"))
        TimerBoard.dismissFinished()
        assertEquals(0, silenced)
    }

    @Test
    fun stopDropsTheDismissHookAndSilencesTheChimes() {
        pane.start()
        assertNotNull(TimerBoard.onDismissed)
        pane.onEvent(PanelEvent.TimerFinished("Pasta"))
        pane.stop()
        assertNull(TimerBoard.onDismissed)
        assertEquals(1, silenced)
        assertTrue(jobs.all { it.isCancelled })
    }

    @Test
    fun eventsOfOtherPanesAreIgnored() {
        pane.onEvent(PanelEvent.SettingChanged("mic_muted"))
        pane.onEvent(PanelEvent.SpeakerClaimed(true))
        assertEquals(0, played + woke + changed + silenced)
        assertTrue(TimerBoard.done.isEmpty() && TimerBoard.timers.isEmpty())
    }
}
