package io.github.aguilaair.s1kiosk.dashboard

/**
 * Debounces a value that, when applied, costs something (here: rebuilding the ESPHome entity list
 * drops every API connection so HA re-lists). Same rule as the wall base's BaseTracker: a new value
 * is only due once it has been stable for [debounceMs] *and* differs from the applied one; in
 * addition, two rebuilds are at least [minIntervalMs] apart. Pure JVM, thread-safe.
 */
class StableChange<T>(
    initial: T,
    private val debounceMs: Long = 10_000,
    private val minIntervalMs: Long = 60_000,
) {
    private var applied: T = initial
    private var candidate: T? = null
    private var candidateSince = 0L
    private var lastAppliedAt = Long.MIN_VALUE / 2

    @Synchronized fun applied(): T = applied

    /** Offers the currently wanted value; returns it when it should be applied now (then call [markApplied]). */
    @Synchronized
    fun offer(value: T, now: Long): T? {
        if (value == applied) {
            candidate = null
            return null
        }
        if (value != candidate) {
            candidate = value
            candidateSince = now
            return null
        }
        if (now - candidateSince < debounceMs) return null
        if (now - lastAppliedAt < minIntervalMs) return null
        return value
    }

    @Synchronized
    fun markApplied(value: T, now: Long) {
        applied = value
        candidate = null
        lastAppliedAt = now
    }
}
