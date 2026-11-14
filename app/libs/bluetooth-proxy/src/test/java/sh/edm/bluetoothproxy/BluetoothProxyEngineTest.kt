package sh.edm.bluetoothproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.esphome.RawAdvertisement
import java.util.PriorityQueue

/** A clock and a queue the test advances by hand: the engine's thread, without the thread. */
internal class ManualLoop : BluetoothProxyEngine.Loop {
    private class Task(val at: Long, val seq: Long, val run: () -> Unit) { var cancelled = false }

    private val queue = PriorityQueue<Task>(compareBy({ it.at }, { it.seq }))
    private var seq = 0L
    var now = 0L
        private set

    override fun now() = now
    override fun execute(task: () -> Unit) { schedule(now, task) }
    override fun schedule(at: Long, task: () -> Unit): () -> Unit {
        val t = Task(at, seq++, task)
        queue += t
        return { t.cancelled = true }
    }
    override fun shutdown() = Unit

    /** Runs everything due up to [to], in time order, and moves the clock there. */
    fun advanceTo(to: Long) {
        while (true) {
            val t = queue.peek() ?: break
            if (t.at > to) break
            queue.poll()
            now = maxOf(now, t.at)
            if (!t.cancelled) t.run()
        }
        now = to
    }

    fun advanceBy(ms: Long) = advanceTo(now + ms)
}

/** Records starts and stops; the test plays the radio through [listener]. */
internal class FakeScanner : BleScanner {
    val calls = ArrayList<String>()
    var listener: BleScanner.Listener? = null
    var running = false

    override fun start(power: ScanPower, listener: BleScanner.Listener) {
        check(!running) { "started twice" }
        calls += "start $power"; running = true; this.listener = listener
    }

    override fun stop() {
        calls += "stop"; running = false
    }

    fun hear(n: Int) = listener!!.onAdvertisement(0xC0FFEE000000L + n, -50 - n, byteArrayOf(2, 1, 6, 0, 0, 0))
}

class BluetoothProxyEngineTest {
    private val loop = ManualLoop()
    private val scanner = FakeScanner()
    private val sent = ArrayList<List<RawAdvertisement>>()
    private val engine = BluetoothProxyEngine(scanner, { sent += it; true }, ScanPower.LOW_LATENCY, loop, ScanSchedule.Limits())

    private fun subscribe(on: Boolean) { engine.onSubscribed(on); loop.advanceBy(0) }

    @Test fun scansOnlyWhileHomeAssistantListens() {
        loop.advanceBy(60_000)
        assertEquals(emptyList<String>(), scanner.calls)
        subscribe(true)
        assertEquals(listOf("start LOW_LATENCY"), scanner.calls)
        assertTrue(engine.status.scanning)
        subscribe(false)
        loop.advanceBy(14_000)
        assertTrue("lingers", scanner.running)
        loop.advanceBy(2_000)
        assertEquals(listOf("start LOW_LATENCY", "stop"), scanner.calls)
        assertEquals(false, engine.status.scanning)
    }

    @Test fun fullBatchesGoAtOnceAndPartialOnesAfter200ms() {
        subscribe(true)
        repeat(RawAdvertisement.BATCH_SIZE + 3) { scanner.hear(it) }
        loop.advanceBy(0)
        assertEquals(listOf(RawAdvertisement.BATCH_SIZE), sent.map { it.size })
        loop.advanceBy(199)
        assertEquals(1, sent.size)
        loop.advanceBy(1)
        assertEquals(listOf(RawAdvertisement.BATCH_SIZE, 3), sent.map { it.size })
        val first = sent[0][0]
        assertEquals(0xC0FFEE000000L, first.address)
        assertEquals(-50, first.rssi)
        assertArrayEquals("the padding is gone", byteArrayOf(2, 1, 6), first.data)
    }

    @Test fun nothingIsForwardedWhileNobodyListens() {
        subscribe(true)
        subscribe(false)
        scanner.hear(1)
        loop.advanceBy(1_000)
        assertEquals(emptyList<List<RawAdvertisement>>(), sent)
    }

    @Test fun aFailureShowsUntilTheScanWorksAgain() {
        subscribe(true)
        scanner.running = false
        scanner.listener!!.onFailed(ScanFailure.LOCATION_OFF)
        loop.advanceBy(0)
        assertEquals(ScanFailure.LOCATION_OFF, engine.status.failure)
        assertEquals(false, engine.status.scanning)
        loop.advanceBy(30_000)
        assertEquals("retried after 30 s", listOf("start LOW_LATENCY", "start LOW_LATENCY"), scanner.calls)
        scanner.hear(1)
        loop.advanceBy(0)
        assertNull(engine.status.failure)
    }

    @Test fun advertisementsPerMinuteCountsTheLastFullMinute() {
        subscribe(true)
        repeat(40) { scanner.hear(it) }
        loop.advanceBy(1_000)
        assertEquals(0, engine.status.advertisementsPerMinute)
        loop.advanceBy(60_000)
        assertEquals(40, engine.status.advertisementsPerMinute)
        loop.advanceBy(60_000)
        assertEquals(0, engine.status.advertisementsPerMinute)
    }

    @Test fun closeStopsTheScan() {
        subscribe(true)
        engine.close()
        loop.advanceBy(0)
        assertEquals(listOf("start LOW_LATENCY", "stop"), scanner.calls)
        subscribe(true)
        assertEquals("stays closed", 2, scanner.calls.size)
    }

    @Test fun adStructuresDropAndroidsPaddingAndKeepWholeStructures() {
        val adv = byteArrayOf(2, 1, 6, 4, 0xFF.toByte(), 1, 2, 3)
        assertArrayEquals(adv, adStructures(adv + ByteArray(54)))
        assertArrayEquals("a broken length stops it", byteArrayOf(2, 1, 6), adStructures(byteArrayOf(2, 1, 6, 9, 1)))
        val long = ByteArray(80).also { it[0] = 30; it[31] = 30; it[62] = 16 }
        assertEquals("cut to whole structures within 62 bytes", 62, adStructures(long).size)
    }

    @Test fun addressesAreBigEndian() = assertEquals(0xAABBCCDDEEFFL, parseAddress("AA:BB:CC:DD:EE:FF"))
}
