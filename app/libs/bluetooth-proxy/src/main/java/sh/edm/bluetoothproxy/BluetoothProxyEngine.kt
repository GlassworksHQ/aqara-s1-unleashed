package sh.edm.bluetoothproxy

import sh.edm.esphome.ApiServer
import sh.edm.esphome.BluetoothProxyFeature
import sh.edm.esphome.BluetoothProxyHandler
import sh.edm.esphome.RawAdvertisement
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** What the proxy is doing, for a settings page or a diagnostic entity. */
data class ProxyStatus(
    /** A client (Home Assistant) holds the advertisement subscription. */
    val subscribed: Boolean,
    val scanning: Boolean,
    /** Why the last scan failed, until a scan works again. */
    val failure: ScanFailure?,
    /** Advertisements forwarded in the last full minute. */
    val advertisementsPerMinute: Int,
)

/**
 * A passive ESPHome Bluetooth proxy for one [ApiServer]: scans while a client is subscribed, and
 * forwards what it hears in batches, as ESPHome's bluetooth_proxy does. Set it as
 * [ApiServer.bluetooth] to turn it on; [close] takes it out of the server again.
 *
 * Every state change runs on one thread ([loop]), so the scanner is only ever started and stopped
 * from there. Addresses are never logged.
 */
class BluetoothProxyEngine internal constructor(
    private val scanner: BleScanner,
    private val send: (List<RawAdvertisement>) -> Boolean,
    private val power: ScanPower,
    private val loop: Loop,
    limits: ScanSchedule.Limits,
    /** Takes this engine out of its server, if it is still in. */
    private val detach: (BluetoothProxyEngine) -> Unit,
) : BluetoothProxyHandler {
    constructor(
        server: ApiServer,
        scanner: BleScanner,
        power: ScanPower = ScanPower.LOW_LATENCY,
    ) : this(scanner, server::sendBluetoothAdvertisements, power, ExecutorLoop(), ScanSchedule.Limits(), { server.removeBluetooth(it) })

    companion object {
        /** A partial batch waits this long: ESPHome flushes every other 100 ms tick on Wi-Fi. */
        const val FLUSH_MS = 200L
    }

    /** The engine's thread and clock; tests drive a manual one. */
    internal interface Loop {
        fun now(): Long
        fun execute(task: () -> Unit)
        /** Runs [task] at [at]; returns its cancel. */
        fun schedule(at: Long, task: () -> Unit): () -> Unit
        fun shutdown()
    }

    internal class ExecutorLoop : Loop {
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "bt-proxy").apply { isDaemon = true } }
        override fun now() = System.nanoTime() / 1_000_000
        // A scan result or a subscription change can still come in after close(); it is dropped.
        override fun execute(task: () -> Unit) {
            try { executor.execute(task) } catch (_: java.util.concurrent.RejectedExecutionException) {}
        }
        override fun schedule(at: Long, task: () -> Unit): () -> Unit {
            val f = try {
                executor.schedule(task, (at - now()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.RejectedExecutionException) { return {} }
            return { f.cancel(false) }
        }
        override fun shutdown() = executor.shutdown()
    }

    override val featureFlags = BluetoothProxyFeature.PASSIVE_SCAN or BluetoothProxyFeature.RAW_ADVERTISEMENTS

    // Owned by the loop thread.
    private val schedule = ScanSchedule(limits)
    private var wake: (() -> Unit)? = null
    private val pending = ArrayList<RawAdvertisement>(RawAdvertisement.BATCH_SIZE)
    private var flush: (() -> Unit)? = null
    private var closed = false

    @Volatile private var subscribed = false
    @Volatile private var scanning = false
    @Volatile private var failure: ScanFailure? = null
    private val counter = MinuteCounter()

    val status: ProxyStatus
        get() = ProxyStatus(subscribed, scanning, failure, counter.perMinute(loop.now()))

    /** Counts scan starts and stops. A scan's results and failure count only while its generation is current. */
    private var generation = 0

    /**
     * The listener for the scan [scanGeneration]. The scanner may hand over a result or a failure just
     * before the engine stops or replaces that scan; the loop handles it after, so it checks the generation.
     */
    private fun listener(scanGeneration: Int) = object : BleScanner.Listener {
        override fun onAdvertisement(address: Long, rssi: Int, data: ByteArray) {
            val adv = RawAdvertisement(address, rssi, addressType = 0, data = adStructures(data))
            loop.execute { if (scanGeneration == generation && schedule.scanning) add(adv) }
        }

        override fun onFailed(failure: ScanFailure) = loop.execute {
            if (closed || scanGeneration != generation) return@execute
            this@BluetoothProxyEngine.failure = failure
            schedule.onFailed(loop.now())
            reconcile()
        }

        override fun onReady() = loop.execute {
            if (closed) return@execute
            schedule.onReady()
            reconcile()
        }
    }

    override fun onSubscribed(subscribed: Boolean) = loop.execute {
        if (closed) return@execute
        this.subscribed = subscribed
        schedule.setSubscribed(subscribed, loop.now())
        if (!subscribed) pending.clear()
        reconcile()
    }

    /**
     * Stops scanning for good. Takes the engine out of its server first, so no subscription can reach
     * a closed engine. Connected clients see the proxy gone once they reconnect and reread DeviceInfo.
     */
    fun close() {
        detach(this)
        loop.execute {
            closed = true
            wake?.invoke()
            flush?.invoke()
            scanner.stop()
            scanner.release()
            scanning = false
        }
        loop.shutdown()
    }

    private fun reconcile() {
        wake?.invoke()
        val step = schedule.step(loop.now())
        when (step.action) {
            ScanSchedule.Action.NONE -> Unit
            ScanSchedule.Action.START -> scanner.start(power, listener(++generation))
            ScanSchedule.Action.STOP -> { generation++; scanner.stop() }
            ScanSchedule.Action.RESTART -> { scanner.stop(); scanner.start(power, listener(++generation)) }
        }
        scanning = schedule.scanning
        wake = step.wakeAt?.let { loop.schedule(it) { if (!closed) reconcile() } }
    }

    private fun add(adv: RawAdvertisement) {
        if (closed || !subscribed) return
        if (failure != null) failure = null
        schedule.onAdvertisement()
        pending += adv
        if (pending.size >= RawAdvertisement.BATCH_SIZE) {
            flushPending()
        } else if (flush == null) {
            flush = loop.schedule(loop.now() + FLUSH_MS) { flush = null; flushPending() }
        }
    }

    private fun flushPending() {
        if (pending.isEmpty() || closed) return
        val batch = pending.toList()
        pending.clear()
        if (send(batch)) counter.add(batch.size, loop.now())
    }
}

/** Counts per minute; [perMinute] is the last full minute's count. */
internal class MinuteCounter {
    private var start = Long.MIN_VALUE
    private var current = 0
    private var last = 0

    @Synchronized fun add(n: Int, now: Long) {
        roll(now)
        current += n
    }

    @Synchronized fun perMinute(now: Long): Int {
        roll(now)
        return last
    }

    private fun roll(now: Long) {
        if (start == Long.MIN_VALUE) start = now
        val minutes = (now - start) / 60_000
        if (minutes == 0L) return
        last = if (minutes == 1L) current else 0
        current = 0
        start += minutes * 60_000
    }
}

/**
 * The AD structures in an Android scan record: the zero padding after them dropped, and cut to whole
 * structures within [max] bytes (ESPHome's raw advertisement holds 62).
 */
internal fun adStructures(record: ByteArray, max: Int = RawAdvertisement.MAX_DATA): ByteArray {
    var end = 0
    while (end < record.size) {
        val len = record[end].toInt() and 0xFF
        val next = end + 1 + len
        if (len == 0 || next > record.size || next > max) break
        end = next
    }
    return if (end == record.size) record else record.copyOf(end)
}

/** `AA:BB:CC:DD:EE:FF` as the 48-bit number ESPHome sends. */
internal fun parseAddress(address: String): Long = address.replace(":", "").toLong(16)
