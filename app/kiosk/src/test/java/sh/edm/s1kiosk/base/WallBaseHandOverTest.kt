package sh.edm.s1kiosk.base

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections

/**
 * #210 verifier: the wall base turned off and on while the panel runs. A link that closes slowly (the
 * UART port, a stuck LocalService) must have closed before the next link opens, and two links never
 * run at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WallBaseHandOverTest {
    private val log = Collections.synchronizedList(ArrayList<String>())

    private inner class SlowLink(val n: Int, private val closeMs: Long) : BaseLink {
        override val describe = "fake $n"
        override val isConnected = false
        override val pollRes = emptyList<String>()
        override val pollIntervalMs = 1_000L
        override val confirmWrites = false
        override fun start() { log += "open $n" }
        override fun tick(now: Long) {}
        override fun read(res: List<String>) {}
        override fun write(res: String, value: Int) {}
        override fun stop() {
            // An interrupt cuts the close short, as it can a real one: the port may stay held.
            try { Thread.sleep(closeMs) } catch (_: InterruptedException) { log += "close $n cut off"; return }
            log += "closed $n"
        }
    }

    private object Quiet : WallBase.Listener {
        override fun onStates() {}
        override fun onLayout(layout: BaseLayout) {}
        override fun onButton(channel: Int, type: String) {}
    }

    private fun base(closeMs: Long): WallBase {
        var made = 0
        return WallBase(RuntimeEnvironment.getApplication(), BaseLayout.NONE, Quiet, WallBase.Config(BaseBackend.UART)) { _, _, _ ->
            SlowLink(++made, closeMs)
        }
    }

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(20)
        assertTrue("$what: $log", cond())
    }

    @Test fun aSlowCloseEndsBeforeTheNextLinkOpens() {
        val b = base(closeMs = 4_000) // longer than stop()'s 3 s
        b.start()
        waitFor("first link open") { "open 1" in log }
        b.stop()
        b.start()
        waitFor("second link open") { "open 2" in log }
        assertEquals(listOf("open 1", "closed 1", "open 2"), log.toList())
        b.stop()
    }

    @Test fun startingTwiceClosesTheFirstLink() {
        val b = base(closeMs = 0)
        b.start()
        b.start()
        waitFor("second link open") { "open 2" in log }
        assertEquals(listOf("open 1", "closed 1", "open 2"), log.toList())
        b.stop()
    }
}
