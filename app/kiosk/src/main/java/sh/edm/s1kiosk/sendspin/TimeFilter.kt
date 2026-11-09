package sh.edm.s1kiosk.sendspin

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The Sendspin time filter: a 2-D Kalman filter over clock offset (server − client) and drift,
 * exactly as the spec's "Time filter" section defines it (parameters, update steps 1–8, and the
 * conversions). All times are microseconds.
 *
 * Thread-safety: [update] runs on one thread; readers use [snapshot], an immutable copy published
 * through a volatile field, so the audio thread never sees a half-updated state.
 */
class TimeFilter(
    private val processStdDev: Double = 0.0,
    private val driftProcessStdDev: Double = 1e-11,
    private val forgetFactor: Double = 2.0,
    private val adaptiveCutoff: Double = 3.0,
    private val minSamples: Int = 100,
    private val driftSignificanceThreshold: Double = 2.0,
    private val maxErrorScale: Double = 0.5,
) {
    /** Immutable state for conversions. */
    data class Snapshot(
        val offset: Double,
        val drift: Double,
        val lastUpdate: Long,
        val useDrift: Boolean,
        val count: Int,
        val offsetStdDevUs: Double,
    ) {
        val converged: Boolean get() = count >= 1

        private val d: Double get() = if (useDrift) drift else 0.0

        /** `t_client + round(offset + d * (t_client - last_update))`. */
        fun serverTime(tClient: Long): Long = tClient + roundHalfAway(offset + d * (tClient - lastUpdate))

        /** `round((t_server - offset + d * last_update) / (1 + d))`. */
        fun clientTime(tServer: Long): Long = roundHalfAway((tServer - offset + d * lastUpdate) / (1 + d))

        /** Same as [clientTime] without rounding (for sub-microsecond scheduling). */
        fun clientTimeExact(tServer: Double): Double = (tServer - offset + d * lastUpdate) / (1 + d)
    }

    private var offset = 0.0
    private var drift = 0.0
    private var pOO = Double.POSITIVE_INFINITY
    private var pOD = 0.0
    private var pDD = 0.0
    private var lastUpdate = 0L
    private var count = 0
    private var useDrift = false

    @Volatile var snapshot: Snapshot = snap(); private set

    fun reset() {
        offset = 0.0; drift = 0.0; pOO = Double.POSITIVE_INFINITY; pOD = 0.0; pDD = 0.0
        lastUpdate = 0L; count = 0; useDrift = false
        snapshot = snap()
    }

    /** Feeds one measurement; returns false if it was discarded (step 1). */
    fun update(measurement: Long, maxError: Long, timeAdded: Long): Boolean {
        // 1.
        if (timeAdded <= lastUpdate || maxError <= 0) return false
        val dt = (timeAdded - lastUpdate).toDouble()
        lastUpdate = timeAdded
        // 2.
        val r = (maxErrorScale * maxError).let { it * it }
        val m = measurement.toDouble()
        // 3.
        if (count == 0) {
            offset = m; pOO = r; drift = 0.0; count = 1
            snapshot = snap(); return true
        }
        // 4.
        if (count == 1) {
            drift = (m - offset) / dt
            pDD = (pOO + r) / (dt * dt)
            offset = m; pOO = r; count = 2
            snapshot = snap(); return true
        }
        // 5. predict
        val offsetP = offset + drift * dt
        var pOOp = pOO + 2 * pOD * dt + pDD * dt * dt + processStdDev * processStdDev * dt
        var pODp = pOD + pDD * dt
        var pDDp = pDD + driftProcessStdDev * driftProcessStdDev * dt
        // 6. residual + adaptive forgetting
        val y = m - offsetP
        if (count < minSamples) {
            count++
        } else if (abs(y) > adaptiveCutoff * maxError) {
            val f2 = forgetFactor * forgetFactor
            pOOp *= f2; pODp *= f2; pDDp *= f2
        }
        // 7. correct
        val s = pOOp + r
        val kO = pOOp / s
        val kD = pODp / s
        offset = offsetP + kO * y
        drift += kD * y
        pOO = pOOp - kO * pOOp
        pOD = pODp - kD * pOOp
        pDD = pDDp - kD * pODp
        // 8.
        useDrift = drift * drift > driftSignificanceThreshold * driftSignificanceThreshold * pDD
        snapshot = snap()
        return true
    }

    private fun snap() = Snapshot(offset, drift, lastUpdate, useDrift, count, if (pOO.isFinite()) sqrt(pOO) else Double.POSITIVE_INFINITY)

    companion object {
        /** Round to nearest, halves away from zero (spec "Time conversion"). */
        fun roundHalfAway(x: Double): Long = if (x >= 0) floor(x + 0.5).toLong() else -floor(-x + 0.5).toLong()

        /** Spec measurement formulas; `/ 2` truncates toward zero (Kotlin Long division does). */
        fun measurement(t1: Long, t2: Long, t3: Long, t4: Long): Long = ((t2 - t1) + (t3 - t4)) / 2
        fun maxError(t1: Long, t2: Long, t3: Long, t4: Long): Long = ((t4 - t1) - (t3 - t2)) / 2
    }
}

/**
 * Clock-sync bursts (spec "Clock Synchronization"): each burst sends [burstSize] `client/time`
 * messages, each after the previous reply; only the burst's lowest-`max_error` measurement is fed to
 * the filter. A probe unanswered for [probeTimeoutUs] aborts the burst (nothing is fed).
 *
 * Not thread-safe by itself: the session calls it under its own lock.
 */
class TimeSyncBurst(private val filter: TimeFilter, private val burstSize: Int = 8, private val probeTimeoutUs: Long = 2_000_000) {
    private var active = false
    private var sent = 0
    private var inFlight: Long? = null
    private var inFlightAt = 0L
    private var best: LongArray? = null // measurement, maxError, t4
    var bursts = 0; private set
    var lastBestMaxErrorUs = 0L; private set
    var lastRttUs = 0L; private set

    val isActive get() = active

    /** Starts a burst unless one is running; returns the first T1 to send, or null. */
    fun start(nowUs: Long): Long? {
        if (active) return null
        active = true; sent = 0; best = null
        return nextProbe(nowUs)
    }

    private fun nextProbe(nowUs: Long): Long {
        sent++
        inFlight = nowUs
        inFlightAt = nowUs
        return nowUs
    }

    /** Records the T1 actually sent for the probe in flight (taken just before the send). */
    fun markSent(t1: Long) {
        if (!active) return
        inFlight = t1
        inFlightAt = t1
    }

    /** Aborts the burst if its probe timed out. */
    fun checkTimeout(nowUs: Long) {
        if (active && inFlight != null && nowUs - inFlightAt > probeTimeoutUs) abort()
    }

    fun abort() { active = false; inFlight = null; best = null }

    /**
     * Handles a `server/time` reply; returns the next T1 to send, or null when the burst is over
     * (or the reply was not for the probe in flight).
     */
    fun onReply(t1: Long, t2: Long, t3: Long, t4: Long, nowUs: Long): Long? {
        if (!active || inFlight != t1) return null
        inFlight = null
        val m = TimeFilter.measurement(t1, t2, t3, t4)
        val e = TimeFilter.maxError(t1, t2, t3, t4)
        val b = best
        if (e > 0 && (b == null || e < b[1])) best = longArrayOf(m, e, t4)
        if (sent < burstSize) return nextProbe(nowUs)
        finish()
        return null
    }

    private fun finish() {
        active = false
        val b = best ?: return
        if (filter.update(b[0], b[1], b[2])) {
            bursts++
            lastBestMaxErrorUs = b[1]
            lastRttUs = 2 * b[1]
        }
    }
}
