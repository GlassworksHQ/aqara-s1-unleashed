package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.RadioMode

/**
 * The setup screen's radio step (docs/radio.md, "App side"): which modes are offered, their one-line
 * explanations, and how RadioController's answer is read back. Pure Kotlin (RadioStepTest).
 *
 * Only modes that work are offered: Off (default), Zigbee coordinator (ZHA over TCP) and, marked
 * experimental, Thread border router. Zigbee router and Thread router are listed under them as
 * [COMING_SOON], not selectable, until they work (the web installer lists the same two).
 * Skipping the step means Off. Everything can be changed later with the panel's `Radio mode` select
 * in Home Assistant, which is the same RadioController path.
 */
object RadioStep {

    data class Option(
        val mode: RadioMode,
        val title: String,
        val line: String,
        val experimental: Boolean = false,
    )

    val OPTIONS: List<Option> = listOf(
        Option(RadioMode.OFF, "Off", "Aqara's radio stack is stopped and the radio stays idle. Choose this if unsure."),
        Option(
            RadioMode.ZIGBEE_TCP, "Zigbee coordinator",
            "The panel becomes the Zigbee radio for ZHA, added in Home Assistant as a network radio over TCP.",
        ),
        Option(
            RadioMode.THREAD, "Thread border router",
            "The panel runs a Thread border router for Thread and Matter devices. Not finished yet.",
            experimental = true,
        ),
    )

    /** A role that isn't ready yet: shown, tagged "Coming soon", not selectable. */
    data class Soon(val mode: RadioMode, val title: String, val line: String)

    /** Same titles and lines as the web installer's radio step (web/src/app/ui.ts COMING_SOON). */
    val COMING_SOON: List<Soon> = listOf(
        Soon(RadioMode.ZIGBEE_ROUTER, "Zigbee router", "Extends an existing Zigbee network as a repeater."),
        Soon(RadioMode.THREAD_ROUTER, "Thread router", "Extends an existing Thread network as a repeater."),
    )

    const val LATER = "You can change this later in Home Assistant: the panel's “Radio mode” select."

    /** The serial path ZHA (bellows, radio type EZSP) uses for the coordinator mode. */
    fun zhaPath(panelIp: String?): String = "socket://${panelIp ?: "<panel IP>"}:${RadioHelper.ZIGBEE_TCP_PORT}"

    enum class Result { APPLYING, OK, REFUSED, ERROR }

    /** What happened after "Apply": [status] is RadioController.statusText() once the request ran. */
    data class Applied(val mode: RadioMode, val accepted: Boolean, val status: String) {
        val result: Result get() = classify(mode, accepted, status)
    }

    /**
     * Reads RadioController's status text. Only an explicit success counts as [Result.OK]:
     *  - not accepted (the select would snap back), or "... needs the root helper ..." → [Result.REFUSED];
     *  - "error ..." (helper or local) → [Result.ERROR];
     *  - "Off: Aqara's radio services stopped ..." (no helper) or "ok <mode>" from the helper without a
     *    "(wanted ...)" mismatch → [Result.OK];
     *  - anything else ("applying ...", "helper: ...", "Root helper running, no status yet") → [Result.APPLYING].
     */
    fun classify(mode: RadioMode, accepted: Boolean, status: String): Result {
        val s = status.trim()
        return when {
            !accepted || s.contains("needs the root helper") -> Result.REFUSED
            s.startsWith("error") -> Result.ERROR
            mode == RadioMode.OFF && s.startsWith("Off:") -> Result.OK
            s.startsWith("ok ") && s.contains(mode.label) && !s.contains("(wanted") -> Result.OK
            else -> Result.APPLYING
        }
    }

    /** True when the step may be left with "Continue" (anything accepted; errors offer Skip instead). */
    fun canContinue(a: Applied?): Boolean = a != null && (a.result == Result.OK || a.result == Result.APPLYING)
}
