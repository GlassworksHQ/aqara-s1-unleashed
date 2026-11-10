package sh.edm.s1kiosk.update

import sh.edm.s1kiosk.esphome.UpdateStateData

/**
 * The update entity's state machine (pure: no Android, no I/O; [UpdateManager] drives it).
 *
 * ```
 * not checked (missing_state) --check ok--> IDLE (latest known)
 * IDLE --install, update available--> DOWNLOADING (progress %) --> VERIFYING --> INSTALLING
 *   any phase --failure / timeout--> IDLE with "Install of X failed: why" on top of the summary
 * ```
 * A successful install replaces the app (the process is killed and restarted), so there is no
 * "installed" transition here. Each install is an [Attempt]; callbacks for an attempt that has
 * already failed or timed out are ignored, so a late download can't revive it. Thread-safe.
 */
class UpdateModel(val currentVersion: String, val title: String = TITLE) {

    enum class Phase { IDLE, DOWNLOADING, VERIFYING, INSTALLING }

    data class Attempt(val id: Int, val offer: UpdateOffer)

    private enum class StatusKind { NONE, INFO, CHECK_ERROR, INSTALL_ERROR }

    private val current = AppVersion.parse(currentVersion)
    private var checked = false
    private var offer: UpdateOffer? = null
    private var attempt: Attempt? = null
    private var nextId = 1
    private var phase = Phase.IDLE
    private var phaseSince = 0L
    private var percent = 0
    private var status = ""
    private var statusKind = StatusKind.NONE

    /** Shown on top of the summary while an update is available and nothing else is reported. */
    @Volatile var installNote: String = ""

    @get:Synchronized val currentPhase: Phase get() = phase
    @get:Synchronized val latestOffer: UpdateOffer? get() = offer

    /** True if the last check found a release newer than this app (or this app's version is unparseable). */
    @get:Synchronized val updateAvailable: Boolean
        get() = offer.let { o -> o != null && (current == null || o.version > current) }

    /** A check succeeded; [offer] is null when the repo has no usable release. */
    @Synchronized fun onChecked(offer: UpdateOffer?) {
        checked = true
        this.offer = offer
        if (statusKind == StatusKind.CHECK_ERROR) setStatus("", StatusKind.NONE)
    }

    /** A check failed. Keeps what is known; only reported while idle. */
    @Synchronized fun onCheckFailed(reason: String) {
        if (phase == Phase.IDLE && statusKind != StatusKind.INSTALL_ERROR) {
            setStatus("Update check failed: $reason", StatusKind.CHECK_ERROR)
        }
    }

    /** Informational status (e.g. the result of the previous install, read at start). */
    @Synchronized fun onInfo(message: String) {
        if (phase == Phase.IDLE) setStatus(message, StatusKind.INFO)
    }

    /** Starts an install of the latest offer, or null (already running / nothing newer to install). */
    @Synchronized fun beginInstall(now: Long): Attempt? {
        if (phase != Phase.IDLE || !updateAvailable) return null
        val a = Attempt(nextId++, offer!!)
        attempt = a
        enter(Phase.DOWNLOADING, now)
        setStatus("Downloading ${a.offer.version}", StatusKind.INFO)
        return a
    }

    /** Whether [a] is still the running install (the downloader polls this to abort). */
    @Synchronized fun isActive(a: Attempt): Boolean = attempt === a && phase != Phase.IDLE

    /** Download progress (0..1). Returns true when the whole percent changed (worth publishing). */
    @Synchronized fun onProgress(a: Attempt, fraction: Double): Boolean {
        if (!isActive(a) || phase != Phase.DOWNLOADING) return false
        val p = (fraction.coerceIn(0.0, 1.0) * 100).toInt()
        if (p == percent) return false
        percent = p
        return true
    }

    @Synchronized fun onVerifying(a: Attempt, now: Long) {
        if (!isActive(a)) return
        enter(Phase.VERIFYING, now)
        setStatus("Verifying ${a.offer.version}", StatusKind.INFO)
    }

    @Synchronized fun onInstalling(a: Attempt, now: Long, detail: String) {
        if (!isActive(a)) return
        if (phase != Phase.INSTALLING) enter(Phase.INSTALLING, now)
        setStatus(detail, StatusKind.INFO)
    }

    /** Ends [a] with an error; false if it had already ended. */
    @Synchronized fun onFailed(a: Attempt, reason: String): Boolean {
        if (!isActive(a)) return false
        fail(reason)
        return true
    }

    /** Fails the running attempt, whichever it is (e.g. the package installer reported an error). */
    @Synchronized fun failActive(reason: String): Boolean {
        if (phase == Phase.IDLE) return false
        fail(reason)
        return true
    }

    /**
     * Ends an attempt stuck in one phase for too long (no in_progress forever); returns the reason,
     * or null if nothing expired.
     */
    @Synchronized fun expire(now: Long): String? {
        val limit = when (phase) {
            Phase.IDLE -> return null
            Phase.DOWNLOADING -> DOWNLOAD_TIMEOUT_MS
            Phase.VERIFYING, Phase.INSTALLING -> INSTALL_TIMEOUT_MS
        }
        if (now - phaseSince < limit) return null
        val why = "timed out while ${phase.name.lowercase()}"
        fail(why)
        return why
    }

    /** The entity's `UpdateStateResponse`. */
    @Synchronized fun view(): UpdateStateData {
        val o = offer
        val available = updateAvailable
        val top = status.ifEmpty { if (available && phase == Phase.IDLE) installNote else "" }
        return UpdateStateData(
            missingState = !checked,
            inProgress = phase != Phase.IDLE,
            hasProgress = phase == Phase.DOWNLOADING,
            progress = if (phase == Phase.DOWNLOADING) percent.toFloat() else 0f,
            currentVersion = currentVersion,
            // Until a check succeeded: latest = installed. Home Assistant (2026.11, esphome/update.py)
            // ignores missing_state for update entities, and an empty latest_version can't be compared,
            // which HA's UpdateEntity.has_update counts as "update available": ON with a blank version.
            latestVersion = if (!checked) currentVersion else o?.version?.toString() ?: currentVersion,
            title = title,
            releaseSummary = Releases.summary(if (available) o!!.notes else "", top),
            releaseUrl = o?.releaseUrl.orEmpty(),
        )
    }

    /** One line for the diagnostic "Update status" sensor (and logs). */
    @Synchronized fun statusText(): String {
        val o = offer
        return when {
            phase == Phase.DOWNLOADING -> "$status: $percent%"
            phase != Phase.IDLE -> status
            statusKind == StatusKind.INSTALL_ERROR || statusKind == StatusKind.CHECK_ERROR -> status
            !checked -> status.ifEmpty { "not checked yet" }
            updateAvailable -> "${o!!.version} available" + if (status.isNotEmpty()) " ($status)" else ""
            else -> "up to date ($currentVersion)" + if (status.isNotEmpty()) "; $status" else ""
        }.take(MAX_STATUS)
    }

    private fun fail(reason: String) {
        val v = attempt?.offer?.version
        attempt = null
        enter(Phase.IDLE, phaseSince)
        setStatus("Install of ${v ?: "the update"} failed: $reason", StatusKind.INSTALL_ERROR)
    }

    private fun enter(p: Phase, now: Long) {
        phase = p
        phaseSince = now
        if (p != Phase.DOWNLOADING) percent = 0
    }

    private fun setStatus(s: String, kind: StatusKind) {
        status = s
        statusKind = kind
    }

    companion object {
        const val TITLE = "S1 Kiosk"
        const val DOWNLOAD_TIMEOUT_MS = 30 * 60_000L
        const val INSTALL_TIMEOUT_MS = 10 * 60_000L
        /** HA keeps text sensor states to 255 characters. */
        const val MAX_STATUS = 255
    }
}
