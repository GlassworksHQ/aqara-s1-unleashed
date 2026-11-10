package sh.edm.s1kiosk.update

/**
 * When to ask GitHub for the latest release (pure; times are elapsedRealtime ms): the first check
 * as soon as there is a network, then every [intervalMs]; failures back off exponentially from
 * [minRetryMs] to [maxRetryMs] (or until GitHub's rate-limit reset, whichever is later, but never
 * past the regular interval). Manual checks (HA's "check" command) are allowed once a minute.
 */
class CheckSchedule(
    private val intervalMs: Long = 24 * HOUR,
    private val minRetryMs: Long = 15 * MINUTE,
    private val maxRetryMs: Long = 6 * HOUR,
    private val manualGapMs: Long = MINUTE,
) {
    var nextAt = 0L
        private set
    var failures = 0
        private set
    private var lastManual = Long.MIN_VALUE / 2

    fun due(now: Long) = now >= nextAt

    fun onSuccess(now: Long) {
        failures = 0
        nextAt = now + intervalMs
    }

    /** [retryAfterMs]: GitHub's rate-limit reset or Retry-After, if it sent one. */
    fun onFailure(now: Long, retryAfterMs: Long? = null) {
        failures++
        val backoff = (minRetryMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxRetryMs)
        nextAt = now + maxOf(backoff, retryAfterMs ?: 0L).coerceAtMost(intervalMs)
    }

    /** A manual check: true if allowed now (and recorded), false if one ran less than a minute ago. */
    fun tryManual(now: Long): Boolean {
        if (now - lastManual < manualGapMs) return false
        lastManual = now
        return true
    }

    companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}

/** Interpreting GitHub API responses (pure). */
object GithubHttp {
    /**
     * How long to wait after a rate-limited response (403/429 with `x-ratelimit-remaining: 0`, or a
     * `retry-after`), or null if [code] is not a rate limit. [header] looks a header up case-insensitively.
     */
    fun rateLimitWaitMs(code: Int, header: (String) -> String?, nowEpochMs: Long): Long? {
        if (code != 403 && code != 429) return null
        header("retry-after")?.trim()?.toLongOrNull()?.let { return (it * 1000).coerceIn(0, 24 * CheckSchedule.HOUR) }
        if (header("x-ratelimit-remaining")?.trim() == "0") {
            val reset = header("x-ratelimit-reset")?.trim()?.toLongOrNull() ?: return CheckSchedule.HOUR
            return (reset * 1000 - nowEpochMs + CheckSchedule.MINUTE).coerceIn(0, 24 * CheckSchedule.HOUR)
        }
        return if (code == 429) CheckSchedule.HOUR else null
    }
}
