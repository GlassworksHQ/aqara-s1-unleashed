package sh.edm.s1kiosk.radio

import java.io.File

/**
 * Radio (EFR32 RCP) firmware updates, helper v4 (docs/radio.md "Radio firmware"). Never automatic:
 * the owner picks an image in the `Radio firmware image` select, which arms it for [ARM_MS], and then
 * presses `Flash radio firmware`. The app only writes a request naming the image and the start of its
 * SHA-256 ([RadioHelper.FW_REQUEST_FILE]); the root helper does everything else with Aqara's own cpcd
 * (stop the radio, flash, read the version back, roll back to Aqara's stock image on failure).
 *
 * Images: Aqara's stock image from /vendor/firmware (always offered when the helper finds it) and the
 * one a signed radio bundle may carry. This project ships no radio firmware.
 */
class RadioFirmware(
    private val props: SysProps,
    private val filesDir: File?,
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    data class Target(val id: String, val sha: String, val app: String)
    data class Status(val state: String, val id: String, val detail: String)

    @Volatile private var armed: String? = null
    @Volatile private var armedAt = 0L
    @Volatile private var note: String? = null

    fun helperVersion(): Int = props.get(RadioHelper.PROP_VERSION).trim().toIntOrNull() ?: 1
    fun supported(): Boolean = props.get(RadioHelper.PROP_SVC_STATE).isNotEmpty() && helperVersion() >= RadioHelper.BUNDLE_VERSION
    fun targets(): List<Target> = parseTargets(props.get(RadioHelper.PROP_FW_TARGETS))
    fun status(): Status? = parseStatus(props.get(RadioHelper.PROP_FW))
    fun flashing(): Boolean = status()?.state == "flashing"

    /** The select's state: the armed option while the arming window lasts, else [KEEP]. */
    fun selected(): String {
        val a = armed ?: return KEEP
        if (now() - armedAt > ARM_MS) { armed = null; return KEEP }
        return a
    }

    /** From the select. Returns null when accepted, else why not. */
    fun select(option: String): String? {
        if (option == KEEP) { armed = null; note = null; return null }
        if (option !in OPTIONS) return "unknown option"
        if (!supported()) return needsV4().also { note = it }
        if (target(option) == null) return "${option}: not available on this panel".also { note = it; armed = null }
        armed = option; armedAt = now(); note = null
        log("radio firmware: '$option' armed for ${ARM_MS / 1000} s")
        return null
    }

    /** `Flash radio firmware`. Returns null when the request was handed to the helper, else why not. */
    fun flash(): String? {
        val why = flashBlocker()
        if (why != null) { note = why; log("radio firmware: not flashing: $why"); return why }
        val t = target(selected())!!
        val dir = filesDir ?: return "no files dir".also { note = it }
        val f = File(dir, RadioHelper.FW_REQUEST_FILE)
        return try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".fw-request.tmp")
            tmp.writeText("${t.id} ${t.sha}\n")
            tmp.setReadable(false, false); tmp.setReadable(true, true)
            if (!tmp.renameTo(f)) throw java.io.IOException("rename failed")
            armed = null
            note = "requested ${t.id} (APP ${t.app}); the helper starts within seconds"
            log("radio firmware: flash of ${t.id} (${t.sha}, APP ${t.app}) requested")
            null
        } catch (e: Exception) {
            "couldn't write the request: ${e.message}".also { note = it }
        }
    }

    private fun flashBlocker(): String? = when {
        !supported() -> needsV4()
        flashing() -> "a flash is already running"
        selected() == KEEP -> "choose an image in “Radio firmware image” first (it stays armed for ${ARM_MS / 1000} s)"
        target(selected()) == null -> "${selected()} is not available on this panel"
        else -> null
    }

    /** Clears a request the helper never took (no v4 helper running, or older than the helper accepts). */
    fun cleanup() {
        val f = filesDir?.let { File(it, RadioHelper.FW_REQUEST_FILE) } ?: return
        if (f.exists() && now() - f.lastModified() > 3 * 60_000) f.delete()
    }

    /** Text for the `Radio firmware` sensor. */
    fun statusText(): String {
        if (!supported()) return "Radio firmware updates ${needsV4()}"
        val st = status()
        val base = when (st?.state) {
            null -> "no status from the root helper yet"
            "idle" -> "Aqara's stock firmware, never flashed by the kiosk"
            "flashing" -> "FLASHING ${st.id}: ${st.detail}. Don't unplug the panel."
            "ok" -> "${label(st.id)}: ${st.detail}"
            else -> "error ${st.id}: ${st.detail}"
        }
        val a = selected().takeIf { it != KEEP }
        val left = ((ARM_MS - (now() - armedAt)) / 1000).coerceAtLeast(0)
        return listOfNotNull(base, a?.let { "armed: $it, press “Flash radio firmware” within $left s" }, note?.takeIf { a == null })
            .joinToString(". ")
    }

    private fun target(option: String): Target? = when (option) {
        STOCK -> targets().firstOrNull { it.id == "stock" }
        BUNDLE -> targets().firstOrNull { it.id != "stock" }
        else -> null
    }

    private fun label(id: String) = if (id == "stock") "Aqara stock" else "bundle image $id"

    private fun needsV4() = "need the root helper v${RadioHelper.BUNDLE_VERSION} (installed: v${helperVersion()}): " +
        "update the panel's boot image once with the web installer"

    companion object {
        const val KEEP = "Keep current"
        const val STOCK = "Aqara stock image"
        const val BUNDLE = "Image from the radio bundle"
        val OPTIONS = listOf(KEEP, STOCK, BUNDLE)
        const val ARM_MS = 120_000L

        fun parseTargets(raw: String): List<Target> = raw.trim().split(' ').mapNotNull { t ->
            val p = t.split(':')
            if (p.size == 3 && p[0].matches(Regex("[a-z0-9-]{1,16}")) && p[1].matches(Regex("[0-9a-f]{12,64}")) &&
                p[2].matches(Regex("[A-Za-z0-9._-]{1,16}"))) Target(p[0], p[1], p[2]) else null
        }

        /** "<state> <id>[: detail]" -> Status. */
        fun parseStatus(raw: String): Status? {
            val s = raw.trim().ifEmpty { return null }
            val head = s.substringBefore(':').trim().split(' ')
            if (head.size != 2 || head[0] !in setOf("idle", "flashing", "ok", "error")) return null
            return Status(head[0], head[1], if (':' in s) s.substringAfter(':').trim() else "")
        }
    }
}
