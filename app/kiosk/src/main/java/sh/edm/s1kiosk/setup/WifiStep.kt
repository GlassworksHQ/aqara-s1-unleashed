package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.improv.ImprovState

/**
 * Improv (Wi-Fi over Bluetooth) as the setup screen sees it: a plain snapshot that
 * [sh.edm.s1kiosk.improv.ImprovController.setupFacts] fills in, so the Wi-Fi step's text and buttons
 * are decided here, in pure Kotlin (see WifiStepTest).
 */
data class ImprovFacts(
    /** A provisioning window is open (a session exists), including while Bluetooth is turning on. */
    val running: Boolean = false,
    /** The advertisement is up: Home Assistant can discover the panel now. */
    val advertising: Boolean = false,
    /** BLE centrals connected to the Improv service (HA, a Bluetooth proxy, improv-wifi.com). */
    val clients: Int = 0,
    /** The session's state while [running], else null. */
    val state: ImprovState? = null,
    /** Time left on the "Allow" authorization (AUTHORIZED only). */
    val authLeftMs: Long = 0,
    /** The network being joined / last tried ("" if none yet). */
    val ssid: String = "",
    /** Progress from the Wi-Fi side (e.g. "approve the notification"), while connecting. */
    val progress: String? = null,
    /** Why the last connect failed (the session is back to AUTHORIZED, waiting for a retry). */
    val failure: String? = null,
    /** Why Bluetooth setup couldn't start or stopped by itself (no adapter, advertising failed...). */
    val startError: String? = null,
    /** Joining needs the user to approve a Wi-Fi suggestion notification (no legacy Wi-Fi API). */
    val needsApproval: Boolean = false,
)

/** Wi-Fi details sent from the computer (web installer, `--es wifi_ssid`): one attempt. */
data class InstallerWifi(val ssid: String, val phase: Phase, val detail: String? = null) {
    enum class Phase { CONNECTING, CONNECTED, FAILED }
}

object WifiStep {

    enum class Phase { OFF, UNAVAILABLE, STARTING, READY, ASKING, AUTHORIZED, FAILED, CONNECTING, CONNECTED }

    enum class Tone { MUTED, AMBER, GREEN, ROSE }

    /**
     * Inline buttons. [ALLOW] / [NOT_NOW] mean the same as the overlay's "Allow" / "Not now"
     * (authorize for 2 min / end the provisioning window); [STOP] ends an allowed window; [START]
     * opens a new one.
     */
    enum class Action(val label: String) { ALLOW("Allow"), NOT_NOW("Not now"), STOP("Stop"), START("Use Bluetooth"), RETRY("Try again") }

    /** One status block for the Wi-Fi step: a label, a title, a detail line and its buttons. */
    data class ImprovLine(
        val phase: Phase,
        val label: String,
        val title: String,
        val detail: String,
        val tone: Tone,
        val actions: List<Action>,
    )

    /** Where Home Assistant lists the panel. Shown whenever Bluetooth setup is on. */
    const val HA_HINT = "In Home Assistant: Settings → Devices & services → Discovered → “Improv via BLE”."

    fun improv(f: ImprovFacts): ImprovLine {
        val label = "Bluetooth setup"
        if (!f.running) {
            val err = f.startError
            return if (err != null) ImprovLine(
                Phase.UNAVAILABLE, "$label · off", "Bluetooth setup couldn't start",
                "${sentence(err)} Choose a network here instead, or try again.", Tone.ROSE, listOf(Action.RETRY),
            ) else ImprovLine(
                Phase.OFF, "$label · off", "Set up Wi-Fi from Home Assistant",
                "Turn on Bluetooth setup, then add the panel from Home Assistant (“Improv via BLE”).",
                Tone.MUTED, listOf(Action.START),
            )
        }
        val ssid = f.ssid.ifBlank { null }
        return when (f.state) {
            null, ImprovState.AUTHORIZATION_REQUIRED -> when {
                !f.advertising -> ImprovLine(
                    Phase.STARTING, "$label · starting", "Starting Bluetooth…",
                    "The panel shows up in Home Assistant a few seconds after this.", Tone.AMBER, listOf(Action.NOT_NOW),
                )
                f.clients > 0 -> ImprovLine(
                    Phase.ASKING, "$label · on · device connected", "Allow Wi-Fi setup over Bluetooth?",
                    "A device connected to the panel and wants to send it Wi-Fi details. " +
                        "Only allow this if you're setting it up now.",
                    Tone.AMBER, listOf(Action.ALLOW, Action.NOT_NOW),
                )
                else -> ImprovLine(
                    Phase.READY, "$label · on", "Bluetooth setup is on",
                    "$HA_HINT Configure it there, then tap Allow here.",
                    Tone.AMBER, listOf(Action.ALLOW, Action.NOT_NOW),
                )
            }
            ImprovState.AUTHORIZED -> {
                val fail = f.failure
                if (fail != null) ImprovLine(
                    Phase.FAILED, "$label · allowed for ${clock(f.authLeftMs)}",
                    "Couldn't connect to ${ssid ?: "the network"}",
                    "${sentence(fail)} Check the password and send it again.", Tone.ROSE, listOf(Action.STOP),
                ) else ImprovLine(
                    Phase.AUTHORIZED, "$label · allowed for ${clock(f.authLeftMs)}", "Waiting for Wi-Fi details",
                    "Enter your Wi-Fi in Home Assistant's “Improv via BLE” dialog." +
                        if (f.needsApproval) " The panel will then ask you to approve the network in a notification." else "",
                    Tone.AMBER, listOf(Action.STOP),
                )
            }
            ImprovState.PROVISIONING -> ImprovLine(
                Phase.CONNECTING, "$label · connecting", "Connecting to ${ssid ?: "Wi-Fi"}…",
                f.progress ?: "This takes up to 30 seconds.", Tone.AMBER, emptyList(),
            )
            ImprovState.PROVISIONED -> ImprovLine(
                Phase.CONNECTED, "$label · done", if (ssid != null) "Connected to $ssid" else "The panel is online",
                "Bluetooth setup turns itself off in a few seconds.", Tone.GREEN, emptyList(),
            )
        }
    }

    /** m:ss, rounded up (the overlay's countdown). */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 999) / 1000
        return "${s / 60}:${"%02d".format(s % 60)}"
    }

    private fun sentence(s: String): String {
        val t = s.trim().replaceFirstChar { it.uppercaseChar() }
        return if (t.endsWith(".") || t.endsWith("!") || t.endsWith("?")) t else "$t."
    }
}
