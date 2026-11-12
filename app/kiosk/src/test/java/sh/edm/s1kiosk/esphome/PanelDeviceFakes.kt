package sh.edm.s1kiosk.esphome

import sh.edm.esphome.DeviceIdentity
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * esphome-main for a test: nothing queued on it runs until the test says so ([runDue], [advance]),
 * on the test's own thread, and time only moves with [advance]. After [shutdownNow] it refuses new
 * work and drops what was queued, like the real one. Periodic tasks are counted but never run.
 */
internal class ManualExecutor : AbstractExecutorService(), ScheduledExecutorService {

    private inner class Task(val dueMs: Long, val seq: Long, val action: Runnable) : ScheduledFuture<Any?> {
        @Volatile var cancelled = false
        @Volatile var done = false
        override fun cancel(mayInterrupt: Boolean): Boolean {
            cancelled = true
            synchronized(this@ManualExecutor) { queue.remove(this) }
            return true
        }
        override fun isCancelled() = cancelled
        override fun isDone() = done || cancelled
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit) = unit.convert(dueMs - now, TimeUnit.MILLISECONDS)
        override fun compareTo(other: Delayed) = getDelay(TimeUnit.MILLISECONDS).compareTo(other.getDelay(TimeUnit.MILLISECONDS))
    }

    private val queue = ArrayList<Task>()
    private var now = 0L
    private var seq = 0L
    @Volatile private var down = false

    private val refusedFrom = java.util.concurrent.ConcurrentHashMap<Thread, Int>()

    /** Submissions [thread] made after the executor was shut down, which the real one refuses. */
    fun refusedFrom(thread: Thread): Int = refusedFrom[thread] ?: 0

    private fun refuse(): Nothing {
        refusedFrom.merge(Thread.currentThread(), 1, Int::plus)
        throw RejectedExecutionException("esphome-main is shut down")
    }

    /** Fixed-delay tasks running (the dashboard check and the state refresh); a shutdown cancels them. */
    @Volatile var periodic = 0
        private set

    /** Fixed-delay tasks refused because the executor was already shut down. */
    @Volatile var rejectedPeriodic = 0
        private set

    /** Called right after a one-shot [schedule] has queued its task: the test's way into a pane's start. */
    @Volatile var afterSchedule: (() -> Unit)? = null

    /** Delays, in ms from now, of what is queued. */
    @Synchronized fun pendingDelays(): List<Long> = queue.map { it.dueMs - now }

    @Synchronized private fun enqueue(delayMs: Long, action: Runnable): Task {
        if (down) refuse()
        return Task(now + delayMs, seq++, action).also { queue += it }
    }

    override fun execute(command: Runnable) { enqueue(0, command) }

    override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
        enqueue(unit.toMillis(delay), command).also { afterSchedule?.invoke() }

    override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
        throw UnsupportedOperationException()

    override fun scheduleAtFixedRate(command: Runnable, initialDelay: Long, period: Long, unit: TimeUnit): ScheduledFuture<*> =
        throw UnsupportedOperationException()

    override fun scheduleWithFixedDelay(command: Runnable, initialDelay: Long, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
        synchronized(this) {
            if (down) { rejectedPeriodic++; refuse() }
            periodic++
        }
        return Task(Long.MAX_VALUE, seq++, command)
    }

    /**
     * Takes the queued task due in [delayMs] out, to run it by hand later: that is a task the real
     * executor had already started when [shutdownNow] came, which the shutdown cannot cancel.
     */
    @Synchronized fun detach(delayMs: Long): Runnable {
        val t = queue.filter { it.dueMs - now == delayMs }.also { check(it.size == 1) { "expected one task due in $delayMs ms, found ${it.size}" } }.single()
        queue.remove(t)
        return t.action
    }

    /** Runs every task that is due now, in order, including ones they queue. */
    fun runDue() {
        while (true) {
            val t = synchronized(this) {
                queue.filter { it.dueMs <= now }.minWithOrNull(compareBy<Task>({ it.dueMs }, { it.seq }))?.also { queue.remove(it) }
            } ?: return
            t.action.run()
            t.done = true
        }
    }

    fun advance(ms: Long) {
        now += ms
        runDue()
    }

    @Synchronized override fun shutdown() { down = true; periodic = 0; queue.clear() }

    @Synchronized override fun shutdownNow(): MutableList<Runnable> {
        down = true
        periodic = 0
        return queue.map { it.action }.toMutableList().also { queue.clear() }
    }

    override fun isShutdown() = down
    override fun isTerminated() = down
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

/** mDNS for a test: records what [PanelDevice] asks for and keeps EspHomeMdns's one-record-at-a-time rule. */
internal class RecordingMdns : MdnsAdvertiser {
    data class Start(val port: Int, val encrypted: Boolean, val provisionable: Boolean)

    /** Every registration that took effect, in order. */
    val starts = ArrayList<Start>()

    /** "start" and "stop", in the order [PanelDevice] called them. */
    val calls = ArrayList<String>()

    /** True while a record is registered. */
    var registered = false
        private set

    /** Run right after a registration took effect, and at every [stop] call. */
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    override fun start(identity: DeviceIdentity, port: Int, encrypted: Boolean, provisionable: Boolean) {
        calls += "start"
        if (registered) return
        registered = true
        starts += Start(port, encrypted, provisionable)
        onStart?.invoke()
    }

    override fun stop() {
        calls += "stop"
        registered = false
        onStop?.invoke()
    }
}
