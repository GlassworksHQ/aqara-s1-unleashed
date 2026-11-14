package sh.edm.bluetoothproxy

/**
 * When to start, stop and restart the one long-running scan. It only decides: [BluetoothProxyEngine]
 * feeds it the time and the events, and does what [step] returns. Not thread-safe.
 *
 * The rules, from Android 10's Bluetooth service (packages/apps/Bluetooth, android10-release):
 * - GattService refuses a 6th scanner registration within 30 s (AppScanStats
 *   `NUM_SCAN_DURATIONS_KEPT`, `EXCESSIVE_SCANNING_PERIOD_MS`), so starts are budgeted.
 * - ScanManager turns a scan opportunistic after 30 min (`SCAN_TIMEOUT_MS`) unless its filters fit
 *   in the controller. The S1's controller has no filter slots (`mMaxScanFilters: 0`), so the scan
 *   is restarted before then.
 */
internal class ScanSchedule(private val limits: Limits = Limits()) {
    data class Limits(
        /** Starts allowed per [windowMs]: Android allows 5, one is kept spare. */
        val startsPerWindow: Int = 4,
        val windowMs: Long = 30_000,
        /** Scanning goes on this long after the subscriber leaves: Home Assistant usually comes straight back. */
        val lingerMs: Long = 15_000,
        val restartAfterMs: Long = 25 * 60_000,
        /** The first retry after a failure; each further failure doubles it, up to [maxRetryMs]. */
        val retryMs: Long = 30_000,
        val maxRetryMs: Long = 5 * 60_000,
    )

    enum class Action { NONE, START, STOP, RESTART }

    /** Do [action] now, and call [step] again at [wakeAt] (null: only on the next event). */
    data class Step(val action: Action, val wakeAt: Long?)

    private var subscribed = false
    private var lingerUntil = Long.MIN_VALUE
    private var runningSince: Long? = null
    private val starts = ArrayDeque<Long>()
    /** When the next start may happen after a failure (Long.MIN_VALUE: none waiting). */
    var retryAt = Long.MIN_VALUE
        private set
    private var failures = 0

    val scanning: Boolean get() = runningSince != null

    fun setSubscribed(on: Boolean, now: Long) {
        if (subscribed && !on) lingerUntil = now + limits.lingerMs
        subscribed = on
    }

    /** The scan stopped on its own (Android refused or ended it). */
    fun onFailed(now: Long) {
        runningSince = null
        failures++
        val backoff = limits.retryMs shl (failures - 1).coerceAtMost(16)
        retryAt = now + backoff.coerceAtMost(limits.maxRetryMs)
    }

    /** Whatever made the scan fail is gone (Bluetooth is back): start again as soon as the budget allows. */
    fun onReady() {
        failures = 0
        retryAt = Long.MIN_VALUE
    }

    /** An advertisement arrived, so scanning works: the next failure retries quickly again. */
    fun onAdvertisement() {
        failures = 0
    }

    fun step(now: Long): Step {
        while (starts.isNotEmpty() && starts.first() <= now - limits.windowMs) starts.removeFirst()
        val since = runningSince
        if (!subscribed) {
            if (since != null && now < lingerUntil) return Step(Action.NONE, lingerUntil)
            runningSince = null
            return Step(if (since != null) Action.STOP else Action.NONE, null)
        }
        if (since != null && now - since < limits.restartAfterMs) return Step(Action.NONE, since + limits.restartAfterMs)

        val startAt = maxOf(retryAt, if (starts.size >= limits.startsPerWindow) starts.first() + limits.windowMs else now)
        // A restart that has to wait keeps the old scan going until then.
        if (now < startAt) return Step(Action.NONE, startAt)
        starts.addLast(now)
        runningSince = now
        return Step(if (since != null) Action.RESTART else Action.START, now + limits.restartAfterMs)
    }
}
