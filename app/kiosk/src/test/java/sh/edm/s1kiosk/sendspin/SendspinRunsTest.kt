package sh.edm.s1kiosk.sendspin

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A quick stop and start (Sendspin switched off and on, #83) while the old run's reader is still
 * busy. The fake connections hold the old run at a chosen point, so each interleaving is exact.
 * They ignore interrupts, as a blocking socket read does, so the player can't rely on them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SendspinRunsTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = SendspinPrefs(app.getSharedPreferences("sendspin-runs-test", 0)).apply {
        server = "ws://192.0.2.10:8927/sendspin"
    }

    /** Records what a pipeline sends and passes it on to the player's output. [holdEnd] keeps a stream/end in the decode thread. */
    private class Rec(private val out: PcmOutput, private val holdEnd: CountDownLatch?) : PcmOutput {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val endEntered = CountDownLatch(1)
        val endPassedOn = CountDownLatch(1)
        override fun start(f: SsFormat) { events += "start"; out.start(f) }
        override fun push(c: PcmChunk) { out.push(c) }
        override fun clear(fromUs: Long?) { events += "clear"; out.clear(fromUs) }
        override fun end() {
            events += "end"
            endEntered.countDown()
            holdEnd?.awaitIgnoringInterrupts()
            out.end()
            endPassedOn.countDown()
        }
    }

    private class Made(val pipeline: AudioPipeline, val rec: Rec, val flacFailed: (String) -> Unit)

    private class Fake(val hooks: SsPlayerHooks, private val script: (Fake) -> Unit) : SsConnection {
        override val timeFilter = TimeFilter()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var ranOn: Thread? = null
        @Volatile var sentStates = 0
        @Volatile var releaseOnClose = true

        override fun run(): SendspinSession.Result {
            ranOn = Thread.currentThread()
            script(this)
            return SendspinSession.Result(true, true, "closed")
        }
        override fun status() = SendspinSession.Status(SendspinSession.Phase.CLOSED, closeReason = "server closed the connection")
        val closeReasons: MutableList<String?> = Collections.synchronizedList(mutableListOf())
        val closed = CountDownLatch(1)

        override fun close(reason: String?) { closeReasons += reason; closed.countDown(); if (releaseOnClose) release.countDown() }
        override fun abort() = close(null)
        override fun sendState() { sentStates++ }
    }

    private val conns: MutableList<Fake> = Collections.synchronizedList(mutableListOf())
    private val pipelines: MutableList<Made> = Collections.synchronizedList(mutableListOf())
    @Volatile private var holdFirstEnd = false
    private val firstEndGate = CountDownLatch(1)
    private val connectGate = CountDownLatch(1)
    @Volatile private var holdFirstConnect = false
    @Volatile private var firstConnectThread: Thread? = null
    private val firstConnectEntered = CountDownLatch(1)

    private val player = SendspinPlayer(
        app, prefs, { null }, "02:00:00:00:00:01",
        connect = { _, _, hooks ->
            val first = conns.isEmpty() && firstConnectThread == null
            if (first && holdFirstConnect) {
                firstConnectThread = Thread.currentThread()
                firstConnectEntered.countDown()
                connectGate.awaitIgnoringInterrupts()
            }
            // No scenario needs a third connection: one that shows up stays silent, so it can't hide what the first two did.
            val silent = conns.size >= 2
            Fake(hooks) { f ->
                if (!silent) {
                    f.hooks.onStatus(READY)
                    f.hooks.onStreamStart(SsFormat.PCM_48K_STEREO_16, null)
                }
                f.running.countDown()
                f.release.awaitIgnoringInterrupts()
                if (!silent) {
                    f.hooks.onStreamEnd()
                    f.hooks.onStatus(f.status())
                }
            }.also { conns += it }
        },
        newPipeline = { out, onFlacFailed ->
            val rec = Rec(out, firstEndGate.takeIf { pipelines.isEmpty() && holdFirstEnd })
            AudioPipeline(rec, { error("no FLAC in this test") }, onFlacFailed).also { pipelines += Made(it, rec, onFlacFailed) }
        },
    )

    @After fun tearDown() {
        firstEndGate.countDown()
        player.stop()
        connectGate.countDown()
        conns.forEach { it.release.countDown() }
    }

    /** Start, stop with the old reader stuck (its close doesn't end it), start again. Returns the old reader's thread. */
    private fun stopAndStartWhileTheOldReaderIsStuck(): Thread {
        player.start()
        val old = conns.awaitAt(0)
        old.releaseOnClose = false
        player.stop()
        player.start()
        conns.awaitAt(1)
        return old.ranOn!!
    }

    @Test
    fun aLateStreamEndFromTheStoppedRunDoesNotEndTheNewStream() {
        val oldThread = stopAndStartWhileTheOldReaderIsStuck()
        val newPipeline = pipelines[1].pipeline
        val newRec = pipelines[1].rec
        assertTrue("new pipeline idle", newPipeline.awaitIdle(3_000))
        assertEquals("the new session started the new stream", listOf("start"), newRec.events.toList())

        conns[0].release.countDown() // the old reader wakes up and sends its stream/end
        oldThread.join(3_000)
        assertFalse("the old reader ended", oldThread.isAlive)
        assertTrue("new pipeline idle", newPipeline.awaitIdle(3_000))
        assertEquals("the old session's stream/end didn't reach the new pipeline", listOf("start"), newRec.events.toList())

        conns[1].hooks.onStreamEnd()
        assertTrue("new pipeline idle", newPipeline.awaitIdle(3_000))
        assertEquals("the new session's own stream/end does", listOf("start", "end"), newRec.events.toList())
    }

    @Test
    fun theStoppedLoopLeavesTheNewSessionAndItsStatusAlone() {
        val oldThread = stopAndStartWhileTheOldReaderIsStuck()
        assertEquals("ready, paired with Music Assistant", player.statusText())

        conns[0].release.countDown() // the old session ends: CLOSED from its hooks and from its loop
        oldThread.join(3_000)
        assertFalse("the old reader ended", oldThread.isAlive)
        assertEquals("ready, paired with Music Assistant", player.statusText())
        player.setVolume(40)
        assertEquals("a volume change still reaches the new session", 1, conns[1].sentStates)
    }

    @Test
    fun theStoppedLoopDoesNotConnectAgain() {
        val oldThread = stopAndStartWhileTheOldReaderIsStuck()

        conns[0].release.countDown()
        oldThread.join(3_000)
        assertFalse("the old loop ended", oldThread.isAlive)
        assertEquals("only the new run's session connected after the restart", 2, conns.size)
    }

    @Test
    fun aLoopStoppedBeforeItsSessionStartsNeverConnects() {
        holdFirstConnect = true
        player.start()
        assertTrue("old loop reached connect", firstConnectEntered.await(5, TimeUnit.SECONDS))
        player.stop()
        player.start()
        val new = conns.awaitAt(0)

        connectGate.countDown() // the old loop gets its session after the new run's is up
        firstConnectThread!!.join(3_000)
        assertFalse("the old loop ended", firstConnectThread!!.isAlive)
        assertTrue("the old loop's session never ran", conns.size == 1 || conns[1].ranOn == null)
        assertEquals("ready, paired with Music Assistant", player.statusText())
        player.setVolume(40)
        assertEquals("a volume change reaches the new session", 1, new.sentStates)
    }

    @Test
    fun aStreamEndStillInTheOldDecodeThreadDoesNotStopTheNewOutput() {
        holdFirstEnd = true
        player.start()
        val old = conns.awaitAt(0)
        assertTrue("old pipeline idle", pipelines[0].pipeline.awaitIdle(3_000))
        old.hooks.onStreamEnd() // queued before the stop; the decode thread is still in it when the stop comes
        assertTrue("old decode thread in stream/end", pipelines[0].rec.endEntered.await(3, TimeUnit.SECONDS))
        player.stop()
        player.start()
        conns.awaitAt(1)
        assertTrue("new pipeline idle", pipelines[1].pipeline.awaitIdle(3_000))
        assertTrue("the new stream plays", player.outputActive)

        firstEndGate.countDown()
        assertTrue("old decode thread done with stream/end", pipelines[0].rec.endPassedOn.await(3, TimeUnit.SECONDS))
        assertTrue("the old run's stream/end didn't end the new stream", player.outputActive)

        conns[1].hooks.onStreamEnd()
        assertTrue("new pipeline idle", pipelines[1].pipeline.awaitIdle(3_000))
        assertFalse("the new run's own stream/end does", player.outputActive)
    }

    @Test
    fun aFlacFailureFromTheStoppedRunLeavesTheNewRunAlone() {
        stopAndStartWhileTheOldReaderIsStuck()
        pipelines[0].flacFailed("old decoder")
        assertFalse("FLAC still offered: ${player.statusText()}", player.statusText().contains("FLAC off"))

        pipelines[1].flacFailed("new decoder")
        assertTrue(player.statusText(), player.statusText().endsWith("; FLAC off, using PCM: new decoder"))
        assertTrue("the new run's own failure reconnects it", conns[1].closed.await(3, TimeUnit.SECONDS))
        assertEquals(listOf<String?>("restart"), conns[1].closeReasons.toList())
    }

    @Test
    fun volumeMuteAndDelayFromTheStoppedRunDoNotReachTheNewRun() {
        stopAndStartWhileTheOldReaderIsStuck()
        val volume = player.volume
        val delay = player.store.outputDelayMs
        conns[0].hooks.onVolume(volume / 2)
        conns[0].hooks.onMute(true)
        conns[0].hooks.onOutputDelay(delay + 900)
        assertEquals("volume", volume, player.volume)
        assertFalse("muted", player.muted)
        assertEquals("output delay", delay, player.store.outputDelayMs)

        conns[1].hooks.onVolume(volume / 2)
        conns[1].hooks.onMute(true)
        conns[1].hooks.onOutputDelay(delay + 900)
        assertEquals("the new session's volume", volume / 2, player.volume)
        assertTrue("the new session's mute", player.muted)
        assertEquals("the new session's output delay", delay + 900, player.store.outputDelayMs)
    }

    @Test
    fun theNextRunDoesNotShowTheStoppedRunsTitle() {
        player.start()
        val old = conns.awaitAt(0)
        old.hooks.onMetadata("Old title", "Old artist")
        old.releaseOnClose = false // its stream/end comes too late to clear the title
        player.stop()
        player.start()
        val new = conns.awaitAt(1)
        new.hooks.onStatus(SendspinSession.Status(SendspinSession.Phase.PLAYING, serverName = "Music Assistant", paired = true))
        assertFalse(player.statusText(), player.statusText().contains("Old title"))

        new.hooks.onMetadata("New title", "New artist")
        assertTrue(player.statusText(), player.statusText().endsWith(": New artist – New title"))
    }

    private fun MutableList<Fake>.awaitAt(i: Int): Fake {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (size <= i) {
            assertTrue("connection $i made", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
        assertTrue("connection $i running", this[i].running.await(5, TimeUnit.SECONDS))
        return this[i]
    }

    private companion object {
        val READY = SendspinSession.Status(SendspinSession.Phase.READY, serverName = "Music Assistant", paired = true)

        fun CountDownLatch.awaitIgnoringInterrupts() {
            while (true) try { await(); return } catch (_: InterruptedException) {}
        }
    }
}
