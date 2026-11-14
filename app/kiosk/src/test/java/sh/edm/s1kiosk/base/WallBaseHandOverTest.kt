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
import java.util.concurrent.CountDownLatch

/**
 * #210 verifier: the wall base turned off and on while the panel runs. A link that closes slowly (the
 * UART port, a stuck LocalService) must have closed before the next link opens, and two links never
 * run at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WallBaseHandOverTest {
    private val log = Collections.synchronizedList(ArrayList<String>())

    /** Link [n], opened with [transport]. Its close takes [closeMs], or waits for [gate] when given. */
    private inner class SlowLink(val n: Int, private val transport: String, private val closeMs: Long, private val gate: CountDownLatch?) : BaseLink {
        override val describe = "fake $n"
        override val isConnected = false
        override val pollRes = emptyList<String>()
        override val pollIntervalMs = 1_000L
        override val confirmWrites = false
        override fun start() { log += "open $n"; opened += transport }
        override fun tick(now: Long) {}
        override fun read(res: List<String>) {}
        override fun write(res: String, value: Int) {}
        override fun stop() {
            // An interrupt cuts the close short, as it can a real one: the port may stay held.
            try { if (gate != null) gate.await() else Thread.sleep(closeMs) } catch (_: InterruptedException) { log += "close $n cut off"; return }
            log += "closed $n"
        }
    }

    private object Quiet : WallBase.Listener {
        override fun onStates() {}
        override fun onLayout(layout: BaseLayout) {}
        override fun onButton(channel: Int, type: String) {}
    }

    /** The transport of each link, as it opened. */
    private val opened = Collections.synchronizedList(ArrayList<String>())

    /** A base whose link [gated] closes only when the test releases [gate]; the others take [closeMs]. */
    private fun base(closeMs: Long, closeWaitMs: Long = 30_000, gated: Int = 0, gate: CountDownLatch? = null): WallBase {
        var made = 0
        return WallBase(
            RuntimeEnvironment.getApplication(), BaseLayout.NONE, Quiet, WallBase.Config(BaseBackend.UART, "tcp:c0"),
            { c, _, _ -> ++made; SlowLink(made, c.transport, closeMs, gate.takeIf { made == gated }) }, closeWaitMs,
        )
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

    /**
     * #210 review: a close that outlasts the next start's wait. The new link opens anyway; the old close,
     * ending later, must not clear it, so the next stop still closes it.
     */
    @Test fun aCloseOutlastingTheWaitLeavesItsSuccessorClosable() {
        val gate = CountDownLatch(1)
        val b = base(closeMs = 0, closeWaitMs = 500, gated = 1, gate = gate)
        try {
            b.start()
            waitFor("first link open") { "open 1" in log }
            b.stop()   // returns after 3 s; the close is still held
            b.start()  // waits 0.5 s, then opens anyway
            waitFor("second link open while the first still closes") { "open 2" in log }
            gate.countDown()
            waitFor("old link closed") { "closed 1" in log }
            b.stop()
            waitFor("the second link closed by stop()") { "closed 2" in log }
        } finally {
            gate.countDown()
            b.stop()
        }
    }

    /**
     * #210 review: a reconfigure stuck in a slow close, then the base stopped and started with another
     * config. When the close ends, the stale reconfigure opens nothing: the newer config stays.
     */
    @Test fun aStaleReconfigureDoesntOverwriteANewerStart() {
        val gate = CountDownLatch(1)
        val b = base(closeMs = 0, closeWaitMs = 500, gated = 1, gate = gate)
        try {
            b.start()
            waitFor("first link open") { "open 1" in log }
            b.reconfigure(WallBase.Config(BaseBackend.UART, "tcp:c1")) // held in link 1's close: gives up after 5 s
            b.stop()
            b.start(WallBase.Config(BaseBackend.UART, "tcp:c2"))
            waitFor("the new config's link open") { "tcp:c2" in opened }
            gate.countDown()
            waitFor("old link closed") { "closed 1" in log }
            Thread.sleep(300) // the stale tasks' turn: the reconfigure, then the stop queued behind it
            assertEquals("tcp:c2", b.config.transport)
            assertEquals("only the first link and the new config's: $opened", listOf("tcp:c0", "tcp:c2"), opened.toList())
            assertTrue("the stale stop left the new link open: $log", "closed 2" !in log)
            b.stop()
            waitFor("the new link closed by the next stop") { "closed 2" in log }
        } finally {
            gate.countDown()
            b.stop()
        }
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
