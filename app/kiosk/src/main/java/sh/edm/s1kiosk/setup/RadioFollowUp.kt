package sh.edm.s1kiosk.setup

import sh.edm.s1kiosk.radio.HelperFacts
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.radio.ThreadRouterState
import sh.edm.s1kiosk.radio.ZigbeeStack

/**
 * The follow-up card for the radio's active mode: what to do next in Home Assistant (or Zigbee2MQTT),
 * with a QR code where a phone helps, and the role's own buttons. One definition, two versions ([Place]):
 * the setup screen's radio step after Apply (short, RadioFollowUpView) and the kiosk settings menu's
 * Radio section (role tiles + panel, settings/RadioSectionView). Pure (RadioFollowUpTest).
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
        /** auth/ThreadJoin: the preferred network's dataset from Home Assistant (QR code for an admin sign-in if needed). */
        THREAD_JOIN_HA("Get it from Home Assistant", "Getting…"), // thread_join_ha
    }

    /** Something the user asked for: a role button, a Zigbee stack, or (settings menu picker) a mode. */
    sealed class Request {
        data class Act(val action: Action) : Request()
        data class Stack(val stack: ZigbeeStack) : Request()
        data class Mode(val mode: RadioMode) : Request()
    }

    /**
     * A confirmation shown before [request] runs (the settings menu's centred dialog): [title], the
     * consequence in plain words ([body]), and the button: white "Switch", or red ([destructive]) "Leave".
     * [from] -> [to]: the roles a mode switch goes between (the dialog's icons), else null.
     */
    data class Ask(
        val request: Request,
        val title: String,
        val body: String,
        val button: String,
        val destructive: Boolean = false,
        val from: RadioMode? = null,
        val to: RadioMode? = null,
    ) {
        val text: String get() = "$title $body"
    }

    /** How the active role is doing, for the panel's status pill and the tile's dot. */
    enum class Health(val label: String) {
        OFF("Off"), RUNNING("Running"), JOINED("Joined"), WAITING("Waiting"), STARTING("Starting"), ERROR("Error")
    }

    /** [Health] from RadioController's status (and the Thread router's parsed state). */
    fun health(f: Facts): Health {
        val s = f.status.trim()
        return when {
            f.mode == RadioMode.OFF -> if (s.startsWith("error")) Health.ERROR else Health.OFF
            s.startsWith("error") || s.contains("needs the root helper") -> Health.ERROR
            !s.startsWith("ok ") -> Health.STARTING // "applying …", "helper: …", "… applying…", no status yet
            f.mode == RadioMode.THREAD_ROUTER -> when {
                f.thread?.joined == true -> Health.JOINED
                else -> Health.WAITING
            }
            f.mode == RadioMode.ZIGBEE_ROUTER -> when {
                statusDetail(f).startsWith("in a network") -> Health.JOINED
                statusDetail(f).startsWith("router host error") -> Health.ERROR
                else -> Health.WAITING
            }
            else -> Health.RUNNING
        }
    }

    /** What a role does, for the tile preview (before it is turned on). */
    fun pitch(m: RadioMode): String = when (m) {
        RadioMode.OFF -> "Stops the radio. Nothing is sent or received."
        RadioMode.ZIGBEE_TCP -> "Your Zigbee devices connect to the panel. For ZHA, or Zigbee2MQTT (experimental)."
        RadioMode.ZIGBEE_ROUTER -> "Joins the Zigbee network you already have and extends its range. You'll open joining in ZHA and press Join."
        RadioMode.THREAD -> "Starts a Thread network for Matter devices, through Home Assistant's OpenThread Border Router integration."
        RadioMode.THREAD_ROUTER -> "Joins the Thread network you already have and extends its range. The panel gets the network from Home Assistant."
    }

    /** A refused mode switch (RadioController's status) in plain words. */
    fun refusedText(status: String): String = when {
        status.contains("needs the root helper v") ->
            "The panel's boot software is too old for this role. Update it once with the web installer; your settings stay."
        status.contains("needs the root helper") ->
            "The panel has no root helper, so only Off works. Install the panel's software with the web installer first."
        status.contains("firmware update is running") -> "A radio firmware update is running. Try again when it's done."
        else -> status
    }

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
        /** Settings: a large line instead of the title (the joined Thread network's name), and its second line. */
        val headline: String? = null,
        val subline: String? = null,
        /** Settings: a small neutral pill (the dataset state). */
        val badge: String? = null,
        /** Settings: the longer explanation behind "How does this work?". */
        val more: List<String> = emptyList(),
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
        if (place == Place.SETUP) return setupCard(f)
        val leave = if (showThreadLeave(f)) listOf(Action.THREAD_LEAVE) else emptyList()
        val allowHint = "Only Home Assistant may connect to the radio, unless you add other addresses under Who may connect."
        return when (f.mode) {
            RadioMode.OFF -> Card(
                f.mode, "The radio is off", listOf("Want to turn it on? Tap a role above."),
                actions = leave,
                note = if (leave.isNotEmpty()) "A Thread network from the Thread router role is still stored on the panel." else null,
            )
            RadioMode.ZIGBEE_TCP -> {
                val add = RadioStep.zhaAddUrl(f.haBase)
                val sisdk = f.stack == ZigbeeStack.SISDK && f.helper.v4
                val kv = mutableListOf(Kv("Radio type", "EZSP"), Kv("Serial path", RadioStep.zhaPath(f.panelIp)))
                var note: String? = null
                if (sisdk) {
                    kv += Kv("Zigbee2MQTT adapter", "ember")
                    kv += Kv("Zigbee2MQTT port", RadioStep.z2mPort(f.panelIp))
                    note = "Zigbee2MQTT on another computer: add its address under Who may connect, below."
                } else if (!f.helper.v4) {
                    note = "Zigbee2MQTT needs Silicon Labs' stack, which needs the panel's boot software updated once with the web installer."
                }
                Card(
                    f.mode, if (add != null) "Add it to ZHA · scan to pair" else "Add it to ZHA", emptyList(), kv,
                    qr = add?.let { Qr(it, "Scan to pair Zigbee devices in ZHA") },
                    stackChoice = f.helper.v4,
                    actions = leave, note = note,
                    more = listOf(
                        "In Home Assistant, add Zigbee Home Automation (ZHA): radio type EZSP and this serial path." +
                            (if (add != null) " The code opens ZHA's Add device page on your phone, to pair devices." else ""),
                        "Zigbee2MQTT (experimental): choose Silicon Labs as the Zigbee stack, then use adapter ember and the port shown.",
                        allowHint,
                    ),
                )
            }
            RadioMode.ZIGBEE_ROUTER -> {
                val add = RadioStep.zhaAddUrl(f.haBase)
                Card(
                    f.mode, "Open joining in ZHA, then press Join", emptyList(),
                    qr = add?.let { Qr(it, "Scan to open joining in ZHA") },
                    state = statusDetail(f),
                    actions = listOf(Action.ROUTER_JOIN, Action.ROUTER_LEAVE) + leave,
                    more = listOf(
                        "The panel joins your Zigbee network as a router and extends its range. Open joining in ZHA " +
                            "(Add device" + (if (add != null) "; the code opens it" else "") + "), then press Join within that window. " +
                            "ZHA lists the panel as a router.",
                        "Leave makes the panel forget the network; remove it from ZHA too.",
                    ),
                )
            }
            RadioMode.THREAD -> {
                val add = RadioStep.otbrAddUrl(f.haBase)
                Card(
                    f.mode, if (add != null) "Scan to add OpenThread Border Router" else "Add OpenThread Border Router in Home Assistant",
                    listOf("Then enter this URL:"),
                    kv = listOf(Kv("URL", RadioStep.otbrUrl(f.panelIp), big = true)),
                    qr = add?.let { Qr(it, "Scan to add OpenThread Border Router") },
                    actions = leave,
                    note = "This runs its own Thread network. To join yours as a repeater, choose Thread repeater.",
                    more = listOf(
                        "The panel becomes a Thread border router with a Thread network of its own, for Matter devices. " +
                            "In Home Assistant, add the OpenThread Border Router integration with this URL.",
                        "To extend a Thread network you already have instead, choose Thread repeater: it joins that network.",
                        allowHint,
                    ),
                )
            }
            RadioMode.THREAD_ROUTER -> {
                val t = f.thread
                val more = listOf(
                    "The panel joins your Thread network as a router and extends its range. It needs the network's active " +
                        "dataset, which Home Assistant shows on its Thread page: Preferred network → ⓘ Network information → " +
                        "Active dataset TLVs (for admin users, for a network from an OpenThread Border Router it manages).",
                    "Paste it into the panel's Thread dataset in Home Assistant (the panel's device page → Configuration), " +
                        "or under Advanced here. $DATASET_KEY",
                    "Get it from Home Assistant does that for you: Home Assistant only gives the dataset to administrators, " +
                        "so the panel uses its own sign-in if it is one, or asks for a one-time administrator sign-in on a " +
                        "phone (a QR code). That sign-in is deleted right after.",
                    "It joins as a child first, and becomes a router when the network promotes it, which takes a few minutes.",
                )
                if (t?.joined == true) Card(
                    f.mode, "", emptyList(),
                    headline = t.network ?: "Thread network",
                    subline = when (t.role) {
                        "child" -> "Child · becomes a router when the network promotes it (a few minutes)"
                        "leader" -> "Leader · relaying for your Thread devices"
                        else -> "Router · relaying for your Thread devices"
                    },
                    badge = "Dataset set",
                    datasetField = true, actions = leave, more = more,
                ) else Card(
                    f.mode, "Give the panel your Thread network",
                    listOf("Get it from Home Assistant. Not signed in as an admin? It shows a code to sign in once."),
                    // No network yet: the title says it all, and the one way forward is Get it from Home Assistant.
                    // A network but not in it yet (joining): Leave, to start over.
                    state = if (t?.dataset == true) t.describe() else if (t == null) statusDetail(f) else null,
                    badge = if (t?.dataset == true) "Joining" else "Not joined",
                    note = DATASET_KEY,
                    datasetField = true,
                    actions = if (t?.dataset == true) leave else listOf(Action.THREAD_JOIN_HA), more = more,
                )
            }
        }
    }

    /**
     * The setup screen's version: calm and short, so it fits the step card (480 dp screen) without
     * scrolling. What to do and the QR code, the URL or serial path, the live state and the one button
     * the step needs (Join). The full explanation, the Zigbee stack choice, Leave buttons and notes are
     * in the settings menu's card and docs/radio-guide.md.
     */
    private fun setupCard(f: Facts): Card = when (f.mode) {
        RadioMode.OFF -> Card(f.mode, "The radio is off", listOf("You can choose a role later in the panel's settings menu or in Home Assistant."))
        RadioMode.ZIGBEE_TCP -> {
            val add = RadioStep.zhaAddUrl(f.haBase)
            val kv = mutableListOf(Kv("Serial path (ZHA)", RadioStep.zhaPath(f.panelIp)))
            if (f.stack == ZigbeeStack.SISDK && f.helper.v4) kv += Kv("Zigbee2MQTT", "ember · ${RadioStep.z2mPort(f.panelIp)}")
            Card(
                f.mode, "Add the radio to ZHA",
                listOf("In Home Assistant, add ZHA: radio type EZSP, this serial path." + if (add != null) " Then scan to pair devices." else ""),
                kv, qr = add?.let { Qr(it, "Scan to pair Zigbee devices in ZHA") },
            )
        }
        RadioMode.ZIGBEE_ROUTER -> {
            val add = RadioStep.zhaAddUrl(f.haBase)
            Card(
                f.mode, "Join your Zigbee network",
                listOf("Open joining in ZHA" + (if (add != null) " (scan the code)" else "") + ", then press Join."),
                qr = add?.let { Qr(it, "Scan to open joining in ZHA") },
                state = statusDetail(f),
                actions = listOf(Action.ROUTER_JOIN),
            )
        }
        RadioMode.THREAD -> {
            val add = RadioStep.otbrAddUrl(f.haBase)
            Card(
                f.mode, "Add the border router to Home Assistant",
                listOf(
                    (if (add != null) "Scan to add" else "In Home Assistant, add") + " “OpenThread Border Router” with this URL.",
                ),
                kv = listOf(Kv("URL", RadioStep.otbrUrl(f.panelIp), big = true)),
                qr = add?.let { Qr(it, "Scan to add OpenThread Border Router") },
                note = "It runs its own Thread network. To join yours, choose Thread router (repeater).",
            )
        }
        RadioMode.THREAD_ROUTER -> {
            Card(
                f.mode, "Join your Thread network",
                listOf("Get it from Home Assistant. Not signed in as an admin? It shows a code to sign in once."),
                state = f.thread?.short() ?: statusDetail(f),
                actions = if (f.thread?.joined == true) emptyList() else listOf(Action.THREAD_JOIN_HA),
            )
        }
    }

    /** The confirmation [r] needs, or null when it runs at once. */
    fun ask(r: Request, f: Facts?): Ask? = when (r) {
        is Request.Act -> when (r.action) {
            Action.ROUTER_JOIN -> null
            Action.ROUTER_LEAVE -> Ask(r, "Leave the Zigbee network?", "The panel forgets this Zigbee network; remove it from ZHA too.", "Leave", destructive = true)
            Action.THREAD_LEAVE -> Ask(r, "Leave the Thread network?", "Deletes the stored Thread network and its key from the panel.", "Leave", destructive = true)
            Action.THREAD_JOIN_HA -> null
        }
        is Request.Stack -> if (f != null && f.stack == r.stack) null else Ask(
            r, "Switch the Zigbee stack to ${stackTitle(r.stack)}?",
            (if (f?.mode == RadioMode.ZIGBEE_TCP) "The radio restarts. " else "") + "ZHA or Zigbee2MQTT may need to be set up again for it.",
            "Switch",
        )
        is Request.Mode -> if (f != null && f.mode == r.mode) null else {
            val to = RadioStep.OPTIONS.firstOrNull { it.mode == r.mode }?.title ?: r.mode.label
            Ask(
                r,
                if (r.mode == RadioMode.OFF) "Turn the radio off?" else "Switch to $to?",
                switchConsequence(f?.mode, r.mode),
                if (r.mode == RadioMode.OFF) "Turn off" else "Switch",
                from = f?.mode, to = r.mode,
            )
        }
    }

    /** What a mode switch costs, in plain words. */
    fun switchConsequence(from: RadioMode?, to: RadioMode): String {
        val lose = when (from) {
            RadioMode.ZIGBEE_TCP -> "ZHA loses its Zigbee radio and your Zigbee devices stop working until you switch back."
            RadioMode.ZIGBEE_ROUTER -> "The panel stops repeating for your Zigbee network."
            RadioMode.THREAD -> "Home Assistant loses this border router, and Thread devices that use it lose their connection."
            RadioMode.THREAD_ROUTER -> "The panel stops repeating for your Thread network; it keeps the dataset."
            RadioMode.OFF, null -> null
        }
        val start = if (to == RadioMode.OFF) "The radio stops." else if (from == RadioMode.OFF || from == null) "The radio starts." else "The radio restarts."
        return listOfNotNull(start, lose).joinToString(" ")
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
                // Runs on its own screen (setup/ThreadJoinOverlay): the button is free again at once.
                Action.THREAD_JOIN_HA -> true
            }
            if (!done) return false
            running = null
            result = when {
                r.action == Action.THREAD_LEAVE && status.contains("Thread network left") -> "Thread network left."
                r.action == Action.THREAD_JOIN_HA -> null
                status == before -> "No change yet: see the status line."
                else -> null
            }
            return true
        }
    }
}
