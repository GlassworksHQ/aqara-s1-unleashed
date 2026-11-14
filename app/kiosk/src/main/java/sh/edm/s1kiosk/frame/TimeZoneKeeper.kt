package sh.edm.s1kiosk.frame

import android.util.Log
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.ShellProps
import sh.edm.s1kiosk.radio.SysProps
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The panel's time zone (docs/install.md "Time zone"). Aqara's SystemManager resets it at every boot
 * (with its own cloud-side setting empty it picks the first zone at UTC+8, Antarctica/Casey), and the
 * app can't set the zone itself (SET_TIME_ZONE is a privileged permission). So the app only records
 * the wanted zone in [PROP]; the root helper (v4) applies it and puts it back whenever it drifts, and
 * reports in [PROP_STATUS].
 *
 * `Time zone` (HA text entity, authenticated connections only) reads and sets the wanted zone;
 * `Time zone status` says what the panel does with it. Props are read on this class's own thread
 * ([refresh], every 30 s and after a change), so no getprop runs on the ESPHome thread.
 */
class TimeZoneKeeper(
    private val props: SysProps = ShellProps,
    /** Zones this Android knows (its ICU/tzdata); what the helper's `service call alarm` accepts. */
    private val zones: () -> Set<String> = { TimeZone.getAvailableIDs().toSet() },
    private val onChange: () -> Unit = {},
    private val inline: Boolean = false,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    companion object {
        const val TAG = "S1Kiosk.TimeZone"
        const val PROP = "persist.s1kiosk.timezone"
        const val PROP_STATUS = "sys.s1kiosk.timezone.status"

        /**
         * [PROP] while the pane is off (#67). Not a zone id, so the v4 helper keeps no zone, adopts none
         * and reports it as `error: '(off)' is not a time zone id`. An empty value can't mean off: the
         * helper adopts the zone in place then (docs/radio.md, Time zone).
         */
        const val OFF = "(off)"
        private const val OFF_SEEN = "error: '$OFF'"
        private const val OFF_TEXT = "off: the panel keeps no time zone"
        private const val CONFIRM_EVERY_MS = 2_000L
        private const val CONFIRM_FOR_MS = 60_000L
        private val ID = Regex("^[A-Za-z0-9_+-]+(/[A-Za-z0-9_+-]+){0,2}$")
    }

    /** Its own thread while it runs ([start] to [stop]); null otherwise, and always when [inline]. */
    @Volatile private var exec: ScheduledExecutorService? = null
    /** After [release]: checks the helper let go of the zone, then ends. Null otherwise. */
    @Volatile private var confirm: ScheduledExecutorService? = null
    /** The zone [release] let go of, for the status while it waits. */
    @Volatile private var released = ""

    @Volatile private var wanted = ""
    @Volatile private var shown = ""

    /** `Time zone`: the wanted zone ("" = none chosen yet). Cheap. */
    fun text(): String = wanted

    /** `Time zone status`. Cheap. */
    fun statusText(): String = shown

    /** Starts the checks. Again after [stop] too: the pane can be turned off and on (#67). */
    fun start() {
        confirm?.shutdownNow(); confirm = null
        if (!inline) exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-timezone").apply { isDaemon = true } }
        post { refresh() }
        exec?.scheduleWithFixedDelay({ post { refresh() } }, 30, 30, TimeUnit.SECONDS)
    }

    /**
     * Stops the checks, and waits up to 5 s for a running write: `setprop` keeps going when its thread is
     * interrupted, and [release] must not clear the zone before it lands.
     */
    fun stop() {
        val e = exec ?: return
        exec = null
        e.shutdownNow()
        try {
            e.awaitTermination(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** From the `Time zone` entity. Returns null when accepted, else why not (nothing changes then). */
    fun set(input: String): String? {
        val id = input.trim()
        if (!ID.matches(id) || id.length > 64) return "'$id' is not a time zone id (like Europe/Berlin or America/New_York)"
        if (id !in zones()) return "'$id' is not a time zone this panel knows"
        val queued = post {
            props.set(PROP, id)
            log("time zone to keep: $id")
            refresh()
        }
        return if (queued) null else "the time zone pane is off"
    }

    /**
     * The pane was turned off: [PROP] becomes [OFF], so the root helper lets go of the zone and leaves it
     * to Aqara again. Returns the zone it kept, "" for none. On the caller's thread. The status says off
     * only once the helper has seen [OFF] (or no helper keeps zones here); until then, that it waits.
     */
    fun release(): String {
        val zone = props.get(PROP).trim().takeIf { it != OFF }.orEmpty()
        if (props.get(PROP).trim() != OFF) {
            props.set(PROP, OFF)
            log("time zone pane off: no longer keeping ${zone.ifEmpty { "a zone" }}")
        }
        wanted = ""
        released = zone
        val since = System.currentTimeMillis()
        if (!checkOff() && !inline) {
            val c = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "s1-timezone-off").apply { isDaemon = true } }
            confirm = c
            c.scheduleWithFixedDelay({
                if (checkOff() || System.currentTimeMillis() - since > CONFIRM_FOR_MS) {
                    if (shown != OFF_TEXT) setShown("off asked, but the root helper hasn't let go of ${zone.ifEmpty { "the zone" }} yet")
                    c.shutdown()
                }
            }, CONFIRM_EVERY_MS, CONFIRM_EVERY_MS, TimeUnit.MILLISECONDS)
        }
        return zone
    }

    /** After [release]: whether the helper keeps no zone now. Sets the status either way. */
    internal fun checkOff(): Boolean {
        val zone = released
        val helperV = props.get(RadioHelper.PROP_VERSION).trim().toIntOrNull() ?: 0
        val off = props.get(PROP).trim() == OFF &&
            (helperV < RadioHelper.BUNDLE_VERSION || props.get(PROP_STATUS).trim().startsWith(OFF_SEEN))
        setShown(if (off) OFF_TEXT else "turning off: waiting for the root helper to let go of ${zone.ifEmpty { "the zone" }}")
        return off
    }

    private fun setShown(s: String) {
        if (s == shown) return
        shown = s
        onChange()
    }

    /**
     * The pane was turned on: hands [zone] to the root helper again, or, with none, lets it choose as
     * before (it adopts the zone in place). Only over [OFF]: a zone set meanwhile (the installer) stays.
     */
    fun keepAgain(zone: String) {
        if (props.get(PROP).trim() != OFF) return
        props.set(PROP, zone)
        log(if (zone.isEmpty()) "time zone pane on: no zone kept before" else "time zone pane on: keeping $zone again")
    }

    /** Re-reads the props and recomputes [text]/[statusText]; [onChange] when something changed. */
    fun refresh() {
        val w = props.get(PROP).trim().takeIf { it != OFF }.orEmpty()
        val st = props.get(PROP_STATUS).trim()
        val now = props.get("persist.sys.timezone").trim().ifEmpty { "?" }
        val helperV = props.get(RadioHelper.PROP_VERSION).trim().toIntOrNull() ?: 0
        val s = when {
            st.startsWith("ok ") -> "${st.removePrefix("ok ")}: kept by the panel's root helper"
            st.startsWith("none") -> "now $now: no zone chosen, so Aqara's reset at boot stays (set Time zone)"
            st.startsWith("error") -> "now $now: ${st.removePrefix("error").trim()}"
            helperV < RadioHelper.BUNDLE_VERSION ->
                "now $now: Aqara resets it at every boot; keeping a zone needs the root helper v${RadioHelper.BUNDLE_VERSION} " +
                    "(update the panel's boot image once)"
            else -> "now $now: waiting for the root helper"
        }.let { if (it.length > 250) it.take(249) + "…" else it }
        if (w != wanted || s != shown) {
            wanted = w; shown = s
            onChange()
        }
    }

    /** Runs [block] on its thread, or now when [inline]. False while it isn't running. */
    private fun post(block: () -> Unit): Boolean {
        if (inline) { block(); return true }
        val e = exec ?: return false
        return runCatching { e.execute { try { block() } catch (t: Throwable) { log("time zone task failed: $t") } } }.isSuccess
    }
}
