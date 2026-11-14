package sh.edm.bluetoothproxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertSame
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
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
        if (running) calls += "stop"
        running = false
    }

    override fun release() { calls += "release" }

    fun hear(n: Int) = listener!!.onAdvertisement(0xC0FFEE000000L + n, -50 - n, byteArrayOf(2, 1, 6, 0, 0, 0))
}

private val IDENTITY = DeviceIdentity(
    name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
    esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM", voiceFeatures = 0,
)

class BluetoothProxyEngineTest {
    private val loop = ManualLoop()
    private val scanner = FakeScanner()
    private val sent = ArrayList<List<RawAdvertisement>>()
    private val engine = BluetoothProxyEngine(
        scanner, { sent += it; true }, ScanPower.LOW_LATENCY, loop, ScanSchedule.Limits(), detach = { scanner.calls += "detach" },
    )

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

    @Test fun aFailureTheEngineHandlesAfterARestartDoesNotEndTheNewScan() {
        subscribe(true)
        val first = scanner.listener!!
        loop.advanceBy(25 * 60_000L) // the 25 min restart: the first scan stops, a second starts
        assertEquals(listOf("start LOW_LATENCY", "stop", "start LOW_LATENCY"), scanner.calls)
        first.onFailed(ScanFailure.ANDROID_ERROR) // handed over before the restart, handled after it
        loop.advanceBy(60_000)
        assertEquals("no third start over the running scan", 3, scanner.calls.size)
        assertTrue(engine.status.scanning)
        assertNull(engine.status.failure)
    }

    @Test fun anOldScansLateAdvertisementNeitherCountsNorClearsTheNewScansFailure() {
        subscribe(true)
        val first = scanner.listener!!
        loop.advanceBy(25 * 60_000L) // restart: the second scan starts
        scanner.running = false
        scanner.listener!!.onFailed(ScanFailure.LOCATION_OFF) // and fails
        loop.advanceBy(0)
        first.onAdvertisement(0xC0FFEE000001L, -50, byteArrayOf(2, 1, 6)) // the first scan's, late
        loop.advanceBy(1_000)
        assertEquals("the failure stays", ScanFailure.LOCATION_OFF, engine.status.failure)
        assertEquals("nothing forwarded", emptyList<List<RawAdvertisement>>(), sent)
    }

    @Test fun bluetoothComingBackRetriesAtOnce() {
        subscribe(true)
        repeat(3) {
            scanner.running = false
            scanner.listener!!.onFailed(ScanFailure.BLUETOOTH_OFF)
            loop.advanceBy(0)
            loop.advanceBy(5 * 60_000L) // the backoff has grown past 1 min by the third failure
        }
        scanner.running = false
        scanner.listener!!.onFailed(ScanFailure.BLUETOOTH_OFF)
        loop.advanceBy(1_000)
        val before = scanner.calls.size
        scanner.listener!!.onReady()
        loop.advanceBy(0)
        assertEquals("started without waiting for the backoff", "start LOW_LATENCY", scanner.calls.drop(before).single())
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

    @Test fun theLastMinuteCountsDifferentDevicesAndTheLastHourKeepsEachMinute() {
        subscribe(true)
        repeat(30) { scanner.hear(it % 3) } // 30 advertisements from 3 devices
        loop.advanceBy(60_000)
        val s = engine.status
        assertEquals(30, s.advertisementsPerMinute)
        assertEquals(3, s.devicesPerMinute)
        loop.advanceBy(2 * 60_000L)
        assertEquals("one line point per minute, quiet ones as 0", listOf(30, 0, 0), engine.status.lastHour)
        loop.advanceBy(90 * 60_000L)
        assertEquals("an hour at most", 60, engine.status.lastHour.size)
    }

    @Test fun aFailureSaysWhenTheNextTryIs() {
        subscribe(true)
        scanner.running = false
        scanner.listener!!.onFailed(ScanFailure.ANDROID_ERROR)
        loop.advanceBy(0)
        assertEquals(30_000L, engine.status.retryInMs)
        loop.advanceBy(10_000)
        assertEquals(20_000L, engine.status.retryInMs)
        loop.advanceBy(20_000)
        assertNull("scanning again", engine.status.retryInMs)
    }

    @Test fun closeStopsTheScan() {
        subscribe(true)
        engine.close()
        loop.advanceBy(0)
        assertEquals("out of the server before anything stops", listOf("start LOW_LATENCY", "detach", "stop", "release"), scanner.calls)
        subscribe(true)
        assertEquals("stays closed", 4, scanner.calls.size)
    }

    @Test fun closeReleasesTheScannerAfterAFailureToo() {
        subscribe(true)
        scanner.running = false
        scanner.listener!!.onFailed(ScanFailure.LOCATION_OFF)
        loop.advanceBy(0)
        engine.close()
        loop.advanceBy(0)
        assertEquals(listOf("start LOW_LATENCY", "detach", "release"), scanner.calls)
    }

    @Test fun closeTakesTheEngineOutOfItsServerButNotAReplacement() {
        val server = ApiServer(0, { IDENTITY }, emptyList(), keys = null)
        val proxy = BluetoothProxyEngine(server, FakeScanner())
        server.bluetooth = proxy
        proxy.close()
        assertNull("no later subscription reaches the closed engine", server.bluetooth)

        val replaced = BluetoothProxyEngine(server, FakeScanner())
        server.bluetooth = replaced
        val newer = BluetoothProxyEngine(server, FakeScanner())
        server.bluetooth = newer
        replaced.close()
        assertSame("the engine that replaced it stays", newer, server.bluetooth)
        newer.close()
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
