package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack

/**
 * The follow-up card for the radio's active mode: what to do next in Home Assistant (or Zigbee2MQTT),
 * with a QR code where a phone helps, and the role's own buttons. One definition, shown in two places
 * ([Place]): the setup screen's radio step after Apply, and the kiosk settings menu's Radio section.
 * Pure (RadioFollowUpTest); RadioFollowUpView draws it.
 *
 * Every button runs the same RadioController call as the Home Assistant entity it mirrors (`Radio mode`,
 * `Zigbee stack`, `Join/Leave Zigbee network`, `Leave Thread network`, `Thread dataset`). On the panel,
 * being physically there is the authorization, as for the radio allow-list in the same menu.
 */
object RadioFollowUp {

    enum class Place { SETUP, SETTINGS }

    /** What the card is drawn from. [mode] is the applied mode (RadioController.mode), not a highlighted one. */
    data class Facts(
        val mode: RadioMode,
        /** RadioController.statusText(). */
        val status: String,
        val helper: HelperFacts,
        val stack: ZigbeeStack,
        /** A Thread router network is stored (helper's yes/no); null when the helper doesn't say. */
        val threadStored: Boolean?,
        val thread: ThreadRouterState?,
        val haBase: String?,
        val panelIp: String?,
    )

    /** The role buttons, with the HA button each one mirrors. */
    enum class Action(val label: String, val busyLabel: String) {
        ROUTER_JOIN("Join Zigbee network", "Joining…"),       // zigbee_router_join
        ROUTER_LEAVE("Leave Zigbee network", "Leaving…"),     // zigbee_router_leave
        THREAD_LEAVE("Leave Thread network", "Leaving…"),     // thread_leave
    }

    /** Something the user asked for: a role button, a Zigbee stack, or (settings menu picker) a mode. */
    sealed class Request {
        data class Act(val action: Action) : Request()
        data class Stack(val stack: ZigbeeStack) : Request()
        data class Mode(val mode: RadioMode) : Request()
    }

    /** A confirmation shown inline before [request] runs. */
    data class Ask(val request: Request, val text: String, val button: String)

    data class Kv(val label: String, val value: String, val big: Boolean = false)
    data class Qr(val url: String, val caption: String)

    data class Card(
        val mode: RadioMode,
        val title: String,
        /** Paragraphs and numbered steps, in order. */
        val lines: List<String>,
        val kv: List<Kv> = emptyList(),
        val qr: Qr? = null,
        /** Live state of the role (router status, Thread role), or null. */
        val state: String? = null,
        /** Offer the Zigbee stack choice (Aqara / Silicon Labs). */
        val stackChoice: Boolean = false,
        /** A write-only Thread dataset field (settings menu only), behind a collapsed "Advanced" disclosure. */
        val datasetField: Boolean = false,
        val actions: List<Action> = emptyList(),
        /** A faint extra line under the card. */
        val note: String? = null,
    )

    const val DATASET_DISCLOSURE = "Advanced: paste a dataset on the panel"
    const val DATASET_HINT =
        "In Home Assistant: Thread page → Preferred network → ⓘ Network information → Active dataset TLVs."
    const val DATASET_KEY = "The dataset contains the network key: paste it only into the panel's Thread dataset."

    /** Labels for the stack choice (short). */
    fun stackTitle(z: ZigbeeStack): String = when (z) {
        ZigbeeStack.AQARA -> "Aqara (for ZHA)"
        ZigbeeStack.SISDK -> "Silicon Labs (for Zigbee2MQTT)"
    }

    /**
     * `Leave Thread network` is offered with helper v4: whenever the helper reports a stored Thread
     * router network (any mode, Off included, to clean up), or, when the helper doesn't say, in both
     * Thread modes. Never when the helper says nothing is stored.
     */
    fun showThreadLeave(f: Facts): Boolean = f.helper.v4 && when (f.threadStored) {
        true -> true
        false -> false
        null -> f.mode == RadioMode.THREAD || f.mode == RadioMode.THREAD_ROUTER
    }

    /** The part of RadioController's status after "ok <mode>: ", or the whole status otherwise. */
    fun statusDetail(f: Facts): String {
        val prefix = "ok ${f.mode.label}: "
        return if (f.status.startsWith(prefix)) f.status.removePrefix(prefix) else f.status
    }

    fun card(f: Facts, place: Place): Card {
        val leave = if (showThreadLeave(f)) listOf(Action.THREAD_LEAVE) else emptyList()
        val settings = place == Place.SETTINGS
        return when (f.mode) {
            RadioMode.OFF -> Card(
                f.mode, "The radio is off",
                listOf(
                    "Zigbee coordinator: the Zigbee radio for ZHA or Zigbee2MQTT. Your Zigbee devices pair to the panel.",
                    "Thread border router: runs a Thread network for Matter devices, through Home Assistant's OpenThread Border Router integration.",
                    "Zigbee router (repeater), Thread router (repeater): the panel joins a Zigbee or Thread network you already have and extends it.",
                ),
                actions = leave,
                note = if (leave.isNotEmpty()) "A Thread network from the Thread router role is still stored on the panel." else null,
            )
            RadioMode.ZIGBEE_TCP -> {
                val add = RadioStep.zhaAddUrl(f.haBase)
                val sisdk = f.stack == ZigbeeStack.SISDK && f.helper.v4
                val lines = mutableListOf(
                    "In Home Assistant, add Zigbee Home Automation (ZHA): radio type EZSP, and this serial path." +
                        if (add != null) " Then scan the code to pair Zigbee devices." else "",
                )
                val kv = mutableListOf(Kv("Serial path (ZHA)", RadioStep.zhaPath(f.panelIp)))
                var note: String? = null
                if (sisdk) {
                    lines += "Or Zigbee2MQTT (experimental): use these settings."
                    kv += Kv("Zigbee2MQTT adapter", "ember")
                    kv += Kv("Zigbee2MQTT port", RadioStep.z2mPort(f.panelIp))
                    note = "Zigbee2MQTT on a computer other than Home Assistant: add its address to " +
                        (if (settings) "“Who may connect” below." else "the panel's “Radio allowed clients” in Home Assistant.")
                } else if (!f.helper.v4) {
                    note = "Zigbee2MQTT needs Silicon Labs' stack, which needs the panel's boot image updated once with the web installer."
                }
                Card(
                    f.mode, "Add the radio to ZHA", lines, kv,
                    qr = add?.let { Qr(it, "Scan to pair Zigbee devices in ZHA") },
                    stackChoice = f.helper.v4,
                    actions = leave, note = note,
                )
            }
            RadioMode.ZIGBEE_ROUTER -> {
                val add = RadioStep.zhaAddUrl(f.haBase)
                Card(
                    f.mode, "Join your Zigbee network",
                    listOf(
                        "First open joining in ZHA (“Add device”" + (if (add != null) "; the code opens it" else "") + "), then press Join. " +
                            "The panel shows up in ZHA as a router.",
                    ),
                    qr = add?.let { Qr(it, "Scan to open joining in ZHA") },
                    state = statusDetail(f),
                    actions = listOf(Action.ROUTER_JOIN, Action.ROUTER_LEAVE) + leave,
                )
            }
            RadioMode.THREAD -> {
                val add = RadioStep.otbrAddUrl(f.haBase)
                Card(
                    f.mode, "Add the border router to Home Assistant",
                    listOf(
                        (if (add != null) "Scan to add “OpenThread Border Router” in Home Assistant" else "In Home Assistant, add “OpenThread Border Router”") +
                            ", using this URL:",
                    ),
                    kv = listOf(Kv("URL", RadioStep.otbrUrl(f.panelIp), big = true)),
                    qr = add?.let { Qr(it, "Scan to add OpenThread Border Router") },
                    actions = leave,
                    note = "The border router runs its own Thread network for Home Assistant. To join a Thread network " +
                        "you already have as a repeater, choose Thread router (repeater) instead.",
                )
            }
            RadioMode.THREAD_ROUTER -> {
                val page = RadioStep.threadPageUrl(f.haBase)
                Card(
                    f.mode, "Join your Thread network",
                    listOf(
                        "1. Open Home Assistant's Thread page" + (if (page != null) " (scan the code)" else "") +
                            ". Under Preferred network, open ⓘ Network information and copy “Active dataset TLVs”.",
                        "2. Paste it into this panel's Thread dataset in Home Assistant: the panel's device page → Configuration.",
                        DATASET_KEY,
                    ),
                    qr = page?.let { Qr(it, "Scan to open Home Assistant's Thread page") },
                    state = f.thread?.describe() ?: statusDetail(f),
                    datasetField = settings,
                    actions = leave,
                    note = "Home Assistant shows the TLVs to admin users, for a network that comes from an OpenThread Border Router it manages.",
                )
            }
        }
    }

    /** The confirmation [r] needs, or null when it runs at once. */
    fun ask(r: Request, f: Facts?): Ask? = when (r) {
        is Request.Act -> when (r.action) {
            Action.ROUTER_JOIN -> null
            Action.ROUTER_LEAVE -> Ask(r, "Leave the Zigbee network? The panel forgets this Zigbee network; remove it from ZHA too.", "Leave")
            Action.THREAD_LEAVE -> Ask(r, "Leave the Thread network? Deletes the stored Thread network and its key from the panel.", "Leave")
        }
        is Request.Stack -> if (f != null && f.stack == r.stack) null else Ask(
            r,
            "Switch the Zigbee stack to ${stackTitle(r.stack)}? " +
                (if (f?.mode == RadioMode.ZIGBEE_TCP) "This restarts the radio; " else "") +
                "ZHA or Zigbee2MQTT may need to be set up again for it.",
            "Switch",
        )
        is Request.Mode -> if (f != null && f.mode == r.mode) null else Ask(
            r,
            "Switch the radio to ${RadioStep.OPTIONS.firstOrNull { it.mode == r.mode }?.title ?: r.mode.label}? " +
                "This restarts the radio: whatever uses the current mode loses it.",
            "Switch",
        )
    }

    /**
     * Inline confirmation and "request running" for one card. [press] returns the request to run now
     * (no confirmation needed) or null (it asks first, or something is already running). The host
     * calls [started] when it hands the request to RadioController, then [finish] with the outcome, or
     * [tick] with the status for requests that have no answer of their own (Join/Leave).
     */
    class Flow(private val clock: () -> Long = System::currentTimeMillis) {
        var asking: Ask? = null
            private set
        var running: Request? = null
            private set
        /** The last outcome, shown under the buttons; null = nothing to say. */
        var result: String? = null
            private set
        private var since = 0L
        private var before = ""

        fun press(r: Request, f: Facts?): Request? {
            if (running != null) return null
            val a = ask(r, f)
            if (a != null) { asking = a; result = null; return null }
            asking = null
            return r
        }

        /** The asked request, to run now. */
        fun confirm(): Request? {
            val a = asking ?: return null
            asking = null
            return if (running == null) a.request else null
        }

        fun cancel() { asking = null }

        fun started(r: Request, status: String) {
            running = r; since = clock(); before = status; result = null
        }

        /** The request's own answer: null message = accepted (the status line tells the rest). */
        fun finish(message: String?) {
            running = null
            result = message
        }

        /** Requests without an answer of their own end when the status says so (or after a while). */
        fun tick(status: String): Boolean {
            val r = running as? Request.Act ?: return false
            val waited = clock() - since
            val done = when (r.action) {
                Action.ROUTER_JOIN -> (status != before && !status.contains(": joining")) || waited > 120_000
                Action.ROUTER_LEAVE -> status != before || waited > 30_000
                Action.THREAD_LEAVE -> status.contains("Thread network left") || status.contains("Thread leave") || waited > 40_000
            }
            if (!done) return false
            running = null
            result = when {
                r.action == Action.THREAD_LEAVE && status.contains("Thread network left") -> "Thread network left."
                status == before -> "No change yet: see the status line."
                else -> null
            }
            return true
        }
    }
}
