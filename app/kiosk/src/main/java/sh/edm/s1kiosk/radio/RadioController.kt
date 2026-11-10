package sh.edm.s1kiosk.radio

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** System properties as seen by the app (getprop/setprop; `ctl.*` controls init services). */
interface SysProps {
    fun get(name: String): String
    fun set(name: String, value: String)
    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** Real implementation: toybox getprop/setprop as the app uid (SELinux is permissive on this firmware). */
object ShellProps : SysProps {
    override fun get(name: String) = run("getprop", name)
    override fun set(name: String, value: String) { run("setprop", name, value) }

    private fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(3, TimeUnit.SECONDS)
        out
    } catch (e: Exception) {
        Log.w(RadioController.TAG, "${cmd.joinToString(" ")} failed: ${e.message}")
        ""
    }
}

/**
 * Radio mode manager (docs/radio.md). Two backends:
 *  - **Root helper installed** (`init.svc.s1kiosk_radio` set): every mode is delegated to it: the app
 *    writes [RadioHelper.PROP_MODE], `ctl.restart`s the service, and shows the helper's status.
 *  - **No helper** (today): `Off` is applied directly by stopping Aqara's radio init services;
 *    the root modes are refused with an explanatory status.
 *
 * Nothing here is persistent at the OS level without the helper: init restarts Aqara's stack on
 * boot, so [start] re-applies the stored mode after each app start (like AqaraCloud).
 * All work runs on one "s1-radio" thread; [onChange] fires after every status change.
 */
class RadioController(
    private val props: SysProps = ShellProps,
    private val load: () -> String?,
    private val store: (String) -> Unit,
    private val onChange: () -> Unit = {},
    private val inline: Boolean = false, // tests: run synchronously
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    companion object {
        const val TAG = "S1Kiosk.Radio"
        private const val WAIT_STEPS = 20
        private const val WAIT_STEP_MS = 100L
    }

    private val exec: ScheduledExecutorService? = if (inline) null else Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "s1-radio").apply { isDaemon = true }
    }

    @Volatile var mode: RadioMode = RadioMode.parse(load())
        private set
    /** Local (no-helper) status. */
    @Volatile private var status = "starting"
    /** What the sensor shows; recomputed on the radio thread by [refresh] (spawns getprop). */
    @Volatile private var shown = "starting"

    /** Text for the "Radio status" sensor. Cheap: no process spawn on the caller's thread. */
    fun statusText(): String = shown

    fun helperInstalled(): Boolean = props.get(RadioHelper.PROP_SVC_STATE).isNotEmpty()

    /** Modes the select can take right now. */
    fun available(m: RadioMode) = !m.needsHelper || helperInstalled()

    /** Re-apply the stored mode (after app start). Does not bounce a helper already in that mode. */
    fun start() {
        post { applyMode(mode, "startup", forceHelperRestart = false) }
        // The helper updates its status asynchronously; poll it (and init's view of the stack).
        exec?.scheduleWithFixedDelay({ post { refresh() } }, 15, 15, TimeUnit.SECONDS)
    }

    /** Radio thread (or inline): recompute [shown]; notifies [onChange] when it changed. */
    fun refresh() {
        var s = if (helperInstalled()) helperStatusText() else status
        if (s.length > 250) s = s.take(249) + "…" // ESPHome text sensors are capped at 255 bytes
        if (s != shown) {
            shown = s
            onChange()
        }
    }

    fun stop() { exec?.shutdownNow() }

    /** From the `Radio mode` select. Unknown labels are ignored; unavailable modes are refused. */
    fun request(label: String) = post {
        val m = RadioMode.fromLabel(label) ?: run {
            log("unknown radio mode '$label'")
            return@post
        }
        if (!available(m)) {
            status = "${m.label} needs the root helper (init service ${RadioHelper.SERVICE} is not installed; " +
                "see docs/radio.md). Still in ${mode.label}."
            log("refused '${m.label}': no root helper")
            refresh()
            onChange() // the select snaps back to the current mode
            return@post
        }
        mode = m
        if (load() != m.id) store(m.id) // also normalizes labels/retired values stored by older builds
        applyMode(m, "select", forceHelperRestart = true)
    }

    // ------------------------------------------------------------------ internals

    private fun post(block: () -> Unit) {
        val e = exec
        if (e == null) block() else e.execute {
            try { block() } catch (t: Throwable) { log("radio task failed: $t") }
        }
    }

    private fun applyMode(m: RadioMode, why: String, forceHelperRestart: Boolean) {
        if (helperInstalled()) {
            applyViaHelper(m, why, forceHelperRestart)
            refresh()
            return
        }
        if (m.needsHelper) {
            // The helper was removed (or an image without it): keep the choice, apply Off meanwhile.
            log("stored mode '${m.label}' unavailable ($why): no root helper, applying Off")
            val failed = applyAqaraServices(RadioMode.OFF.aqaraServices)
            status = "${m.label} needs the root helper (${RadioHelper.SERVICE} not installed); radio is Off" +
                if (failed.isEmpty()) "." else " (not stopped: ${failed.joinToString()})."
            refresh()
            return
        }
        status = "${m.label}: applying…"
        refresh()
        val failed = applyAqaraServices(m.aqaraServices)
        status = when {
            failed.isNotEmpty() -> "error: ${failed.joinToString { "$it=${state(it).ifEmpty { "?" }}" }} " +
                "(wanted ${m.label})"
            else -> "Off: Aqara's radio services stopped, radio idle. Other modes need the root helper " +
                "(${RadioHelper.SERVICE}, not installed)."
        }
        log("mode ${m.label} ($why): $status")
        refresh()
    }

    private fun applyViaHelper(m: RadioMode, why: String, forceRestart: Boolean) {
        val current = props.get(RadioHelper.PROP_MODE)
        if (current != m.id) props.set(RadioHelper.PROP_MODE, m.id)
        val helperState = state(RadioHelper.SERVICE)
        val helperMode = RadioHelper.parseStatus(props.get(RadioHelper.PROP_STATUS))?.mode
        if (forceRestart || current != m.id || helperState != "running" || helperMode != m) {
            props.set("ctl.restart", RadioHelper.SERVICE)
            log("helper restarted for ${m.label} ($why)")
        }
        status = "helper: ${m.label}"
    }

    private fun helperStatusText(): String {
        val raw = props.get(RadioHelper.PROP_STATUS)
        val st = RadioHelper.parseStatus(raw)
            ?: return "Root helper ${state(RadioHelper.SERVICE)}, no status yet (wanted ${mode.label})"
        val label = st.mode?.label ?: "?"
        val tail = if (st.detail.isNotEmpty()) ": ${st.detail}" else ""
        val mismatch = if (st.mode != mode && st.state != "applying") " (wanted ${mode.label})" else ""
        return "${st.state} $label$tail$mismatch"
    }

    /** Brings Aqara's services to [wanted]; returns the ones that didn't get there. */
    private fun applyAqaraServices(wanted: Map<String, Boolean>): List<String> {
        for (svc in AqaraRadio.STOP_ORDER) if (wanted[svc] == false) control(svc, run = false)
        for (svc in AqaraRadio.START_ORDER) if (wanted[svc] == true) control(svc, run = true)
        return wanted.filter { (svc, run) -> !isInState(svc, run) }.keys.toList()
    }

    private fun state(svc: String) = props.get("init.svc.$svc")

    private fun isInState(svc: String, run: Boolean): Boolean {
        val s = state(svc)
        return if (run) s == "running" else s == "stopped" || s.isEmpty()
    }

    private fun control(svc: String, run: Boolean) {
        if (isInState(svc, run)) return
        props.set(if (run) "ctl.start" else "ctl.stop", svc)
        for (i in 0 until WAIT_STEPS) {
            if (isInState(svc, run)) return
            props.sleep(WAIT_STEP_MS)
        }
        log("$svc did not reach '${if (run) "running" else "stopped"}' (now '${state(svc)}')")
    }
}
