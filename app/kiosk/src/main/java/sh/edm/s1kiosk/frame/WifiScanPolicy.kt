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
 * a foreground app (the kiosk's foreground service counts) start 4 scans in 120 s, counting a scan
 * exactly 120 s old. A 40 s gap allows at most 3 scans in any 120 s, so no scan is throttled.
 *
 * A supplicant stuck in a connecting state for [connectTimeoutMs] counts as not connected.
 */
class WifiScanPolicy(
    private val delaysMs: List<Long> = listOf(10_000, 40_000, 60_000, 120_000),
    private val minGapMs: Long = 40_000,
    private val connectTimeoutMs: Long = 60_000,
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

    /** After any change to [w], and when the last [Decision.wakeInMs] is up. [now] is a monotonic clock in ms. */
    fun decide(w: Wifi, now: Long): Decision {
        val since = if (w.link == Link.CONNECTING) connectingSince ?: now else null
        connectingSince = since
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

    private fun notBeforeGap(t: Long) = lastScanAt?.let { maxOf(t, it + minGapMs) } ?: t
}
