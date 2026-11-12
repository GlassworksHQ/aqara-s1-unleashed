package sh.edm.s1kiosk.frame

/**
 * When to ask Android for a Wi-Fi scan while the screen is off (#3). Plain Kotlin, driven by
 * [ScreenOffWifiScanner] on the panel and by tests on the JVM.
 *
 * Android 10 does no periodic scans with the screen off while disconnected. It relies on PNO scans,
 * which the S1's Wi-Fi chip rejects (`PnoScanListener onFailure: -3(not supported)`). So nothing
 * scans and nothing reconnects until the screen wakes. Any scan's results go through the framework's
 * network selection, so an app scan is enough to reconnect.
 *
 * Scans come [delaysMs] apart, backing off to the last value. A screen wake or a reconnect starts the
 * back-off over, but no scan ever comes less than [minGapMs] after the previous one. Android 10 lets
 * a foreground app (the kiosk's foreground service counts) start a scan when fewer than 4 of its
 * scans are 120 s old or newer (`ScanRequestProxy` evicts only those older than 120 s). So the 5th
 * scan must come more than 120 s after the 1st. With a 40 s gap it comes 160 s or more after, so no
 * scan is throttled. This holds within one instance, so within one run of the service.
 *
 * A supplicant in a connecting state for [connectTimeoutMs] counts as not connected. Drops back to
 * not connected shorter than [blipMs] do not start the attempt over, so a connect that keeps
 * failing and retrying still reaches the deadline.
 */
class WifiScanPolicy(
    private val delaysMs: List<Long> = listOf(10_000, 40_000, 60_000, 120_000),
    private val minGapMs: Long = 40_000,
    private val connectTimeoutMs: Long = 60_000,
    private val blipMs: Long = 15_000,
) {
    enum class Link { DOWN, CONNECTING, CONNECTED }

    data class Wifi(val enabled: Boolean, val link: Link, val screenOn: Boolean)

    /** Scan now when [scanNow]. Call [decide] again after [wakeInMs], or never when it is null. */
    data class Decision(val scanNow: Boolean, val wakeInMs: Long?)

    /** Scans started since the scans began; null while no scans are wanted. */
    var scans: Int? = null
        private set

    private var nextAt = 0L
    private var lastScanAt: Long? = null
    private var connectingSince: Long? = null
    private var connectingPausedAt: Long? = null

    /** After any change to [w], and when the last [Decision.wakeInMs] is up. [now] is a monotonic clock in ms. */
    fun decide(w: Wifi, now: Long): Decision {
        val since = connectAttempt(w.link, now)
        val stuck = since != null && now - since >= connectTimeoutMs
        val wanted = w.enabled && !w.screenOn && (w.link == Link.DOWN || stuck)
        if (!wanted) {
            scans = null
            val stuckAt = if (since != null && w.enabled && !w.screenOn) since + connectTimeoutMs else null
            return Decision(false, stuckAt?.minus(now))
        }
        val done = scans ?: 0.also { nextAt = notBeforeGap(now + delaysMs[0]) }
        if (now < nextAt) {
            scans = done
            return Decision(false, nextAt - now)
        }
        lastScanAt = now
        scans = done + 1
        nextAt = notBeforeGap(now + delaysMs[minOf(done + 1, delaysMs.lastIndex)])
        return Decision(true, nextAt - now)
    }

    /** When the current connect attempt began, while [link] is connecting; null otherwise. */
    private fun connectAttempt(link: Link, now: Long): Long? {
        when (link) {
            Link.CONNECTED -> { connectingSince = null; connectingPausedAt = null }
            Link.DOWN -> if (connectingSince != null && connectingPausedAt == null) connectingPausedAt = now
            Link.CONNECTING -> {
                val paused = connectingPausedAt
                if (connectingSince == null || (paused != null && now - paused > blipMs)) connectingSince = now
                connectingPausedAt = null
            }
        }
        return if (link == Link.CONNECTING) connectingSince else null
    }

    private fun notBeforeGap(t: Long) = lastScanAt?.let { maxOf(t, it + minGapMs) } ?: t

    /** Why Android refused a scan, as [refusal] tells it. */
    enum class Refusal(val text: String) {
        NO_PERMISSION("no location permission"),
        LOCATION_OFF("location off"),
        FOREGROUND_ONLY("location not allowed in the background (app-op foreground-only)"),
        DOZE("device idle (Doze)"),
        THROTTLED("throttled"),
    }

    companion object {
        /**
         * Why Android refused a scan, from what the app can read. Android 10 checks the permission,
         * then location on, then the location app-op, which with a granted permission is allowed
         * in the foreground only. Throttling is what is left.
         */
        fun refusal(permission: Boolean, locationOn: Boolean, opAllowed: Boolean, deviceIdle: Boolean): Refusal = when {
            !permission -> Refusal.NO_PERMISSION
            !locationOn -> Refusal.LOCATION_OFF
            !opAllowed -> Refusal.FOREGROUND_ONLY
            deviceIdle -> Refusal.DOZE
            else -> Refusal.THROTTLED
        }
    }
}
