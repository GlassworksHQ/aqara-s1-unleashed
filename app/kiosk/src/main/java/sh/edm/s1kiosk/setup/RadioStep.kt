package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.RadioMode

/**
 * The setup screen's radio step (docs/radio.md, "App side"): which modes are offered, their one-line
 * explanations, and how RadioController's answer is read back. Pure Kotlin (RadioStepTest).
 *
 * Offered, as verified on the reference panel (docs/radio.md):
 *  - Off (default) and Zigbee coordinator (ZHA over TCP): verified end to end with bellows.
 *  - Thread border router, experimental: Home Assistant's OTBR REST API verified, routing between
 *    the LAN and Thread devices not yet tested with a real device.
 *  - Zigbee router and Thread router ([Option.repeater]), experimental: the stacks start and are
 *    ready to join, joining a real network not yet tested. Both need the v2 root helper.
 * [COMING_SOON] is empty now; it stays for roles that are shown but not selectable.
 * Skipping the step means Off. Everything can be changed later with the panel's `Radio mode` select
 * in Home Assistant, which is the same RadioController path.
 */
object RadioStep {

    data class Option(
        val mode: RadioMode,
        val title: String,
        val line: String,
        val experimental: Boolean = false,
        /** A repeater role in someone else's network: shown in the second, compact row. */
        val repeater: Boolean = false,
    )

    val OPTIONS: List<Option> = listOf(
        Option(RadioMode.OFF, "Off", "Aqara's radio stack stopped, radio idle. Choose this if unsure."),
        Option(
            RadioMode.ZIGBEE_TCP, "Zigbee coordinator",
            "The Zigbee radio for ZHA, as a network radio over TCP.",
        ),
        Option(
            RadioMode.THREAD, "Thread border router",
            "For Home Assistant's OpenThread Border Router integration.",
            experimental = true,
        ),
        Option(
            RadioMode.ZIGBEE_ROUTER, "Zigbee router",
            "A repeater in your Zigbee network.",
            experimental = true, repeater = true,
        ),
        Option(
            RadioMode.THREAD_ROUTER, "Thread router",
            "A repeater in your Thread network.",
            experimental = true, repeater = true,
        ),
    )

    /** A role that isn't ready yet: shown, tagged "Coming soon", not selectable. */
    data class Soon(val mode: RadioMode, val title: String, val line: String)

    /** None right now (the web installer's COMING_SOON should match: web/src/app/ui.ts). */
    val COMING_SOON: List<Soon> = emptyList()

    const val LATER = "You can change this later in Home Assistant: the panel's “Radio mode” select."

    /** The serial path ZHA (bellows, radio type EZSP) uses for the coordinator mode. */
    fun zhaPath(panelIp: String?): String = sh.edm.s1kiosk.radio.zhaPath(panelIp)

    /**
     * ZHA's "Add devices" page (permit-join on ZHA's network), for the coordinator step's QR code:
     * once ZHA uses the panel as its radio, a phone scans this to pair devices. Null without HA's address.
     */
    fun zhaAddUrl(haBase: String?): String? =
        haBase?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { "$it/config/zha/add" }

    /** What to do in Home Assistant after a mode was applied (shown under the options), or null. */
    fun nextStep(mode: RadioMode, panelIp: String?): String? = when (mode) {
        RadioMode.ZIGBEE_TCP -> "Then in ZHA: radio type EZSP, serial path ${zhaPath(panelIp)}"
        RadioMode.THREAD -> "Then add “OpenThread Border Router” in Home Assistant: http://${panelIp ?: "<panel IP>"}:${RadioHelper.OTBR_REST_PORT}"
        RadioMode.ZIGBEE_ROUTER -> "Then open permit-join in ZHA and press the panel's “Join Zigbee network”."
        RadioMode.THREAD_ROUTER -> "Then paste the network's active dataset into the panel's “Thread dataset”."
        RadioMode.OFF -> null
    }

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
