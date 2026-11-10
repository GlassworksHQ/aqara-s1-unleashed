package sh.edm.s1kiosk.radio

/**
 * The panel's EFR32 radio modes (docs/radio.md). [id] is what is stored (app prefs and the helper's
 * `persist.s1kiosk.radio_mode`); [label] is the option shown in Home Assistant's `Radio mode` select.
 *
 * There is no "Aqara (stock)" mode: with Aqara's launcher/apps disabled and its cloud blocked nothing
 * can use the hub's own Zigbee network. Stock comes back by uninstalling the app (or
 * `tools/install.sh --undo`): Aqara's services are init services started on every boot.
 *
 * Roles: Zigbee coordinator (EZSP over TCP for ZHA), Zigbee router (repeater in an existing mesh, no
 * endpoints), Thread border router, Thread router (repeater joining an existing network).
 *
 * [needsHelper]: the mode runs processes as root (TCP bridge on the root-only EZSP pty, otbr-agent on
 * the root-only CPC socket) and so needs the root helper service [RadioHelper.SERVICE]. [OFF] only
 * stops Aqara's init services, which the app uid can do (SELinux permissive).
 * [minHelperVersion]: the helper contract version the mode needs (v2: router modes, see RadioHelper).
 */
enum class RadioMode(val id: String, val label: String, val needsHelper: Boolean, val minHelperVersion: Int = 1) {
    OFF("off", "Off", false),
    ZIGBEE_TCP("zigbee_tcp", "Zigbee coordinator (TCP)", true),
    /** Repeater in an existing Zigbee mesh: the app's EZSP host (ZigbeeRouterHost) drives zigbeed. */
    ZIGBEE_ROUTER("zigbee_router", "Zigbee router", true, minHelperVersion = 2),
    THREAD("thread", "Thread border router", true),
    /** Thread repeater (FTD) joining an existing network with a dataset from HA; no BR/REST/mDNS. */
    THREAD_ROUTER("thread_router", "Thread router", true, minHelperVersion = 2);

    // Concurrent Zigbee + Thread on the one multi-PAN radio (shared channel) is deliberately not a
    // mode here: Home Assistant deprecated multiprotocol for the same hardware design (docs/radio.md).
    // The helper still accepts the experimental id "zigbee_tcp_thread" when set by hand.

    /**
     * Wanted state (true = running) of each of Aqara's radio services in this mode. Aqara's own
     * consumers (Z3Gateway, zigbee_agent, the ha_matter bridge) never run; our modes reuse only
     * cpcd (the CPC daemon) and, for Zigbee, the socat pty pair + zigbeed.
     */
    val aqaraServices: Map<String, Boolean>
        get() {
            val cpcd = this != OFF
            val zigbeed = this == ZIGBEE_TCP || this == ZIGBEE_ROUTER
            return linkedMapOf(
                AqaraRadio.CPCD to cpcd,
                AqaraRadio.SOCAT to zigbeed, // the pty pair zigbeed's EZSP interface lives on
                AqaraRadio.ZIGBEED to zigbeed,
                AqaraRadio.HOST to false,   // Z3Gateway, Aqara's coordinator app
                AqaraRadio.AGENT to false,
                AqaraRadio.MATTER to false,
            )
        }

    companion object {
        val DEFAULT = OFF
        val LABELS: List<String> = entries.map { it.label }

        fun fromLabel(label: String): RadioMode? = entries.firstOrNull { it.label == label }
        fun fromId(id: String): RadioMode? = entries.firstOrNull { it.id == id }

        /**
         * Stored value -> mode. Accepts ids and labels (the stub stored labels); unknown or retired
         * values ("Aqara (stock)", the stub's "Thread (future)") fall back to [DEFAULT].
         */
        fun parse(stored: String?): RadioMode =
            stored?.let { fromId(it) ?: fromLabel(it) } ?: DEFAULT
    }
}

/** Aqara's radio init services (/vendor/etc/init/gateway.rc), root, u:r:su:s0. */
object AqaraRadio {
    const val CPCD = "zigbee_cpcd"     // cpcd -c /etc/cpcd.conf (CPC daemon, owns /dev/spidev2.0)
    const val SOCAT = "zigbee_socat"   // socat pty,link=/dev/ttyZigbeeNCP pty,link=/tmp/ttyZigbeeNCP
    const val ZIGBEED = "zigbee_zigbeed" // zigbeed: spinel iid=1 <-> EZSP on /tmp/ttyZigbeeNCP
    const val HOST = "zigbee_host"     // Z3Gateway -p ttyZigbeeNCP (EZSP host on /dev/ttyZigbeeNCP)
    const val AGENT = "zigbee_agent"   // zigbee_agent (Aqara's MQTT glue around Z3Gateway)
    const val MATTER = "ha_matter"     // Aqara's Matter bridge (Wi-Fi, :5540) for its Zigbee devices

    /** Services [RadioMode.aqaraServices] manages; `tools/install.sh --undo` starts them again. */
    val ALL = listOf(CPCD, SOCAT, ZIGBEED, HOST, AGENT, MATTER)

    /**
     * Consumers first. The wrappers of socat/zigbeed/Z3Gateway run `setprop sys.gateway.config 11`
     * when their binary exits *by itself*, which restarts the whole stack; stopping a provider before
     * its consumer would make the consumer exit and trigger that.
     */
    val STOP_ORDER = listOf(MATTER, AGENT, HOST, ZIGBEED, SOCAT, CPCD)

    /** Providers first (the wrappers also sleep 3-8 s so their providers are up). */
    val START_ORDER = STOP_ORDER.reversed()
}

/**
 * Contract with the root helper that the boot-image patch will install (docs/radio.md).
 * The app writes [PROP_MODE] and `ctl.restart`s [SERVICE]; the helper (tools/radio/s1kiosk_radio.sh)
 * applies the mode, supervises its processes and reports in [PROP_STATUS].
 */
object RadioHelper {
    /** Init service name; `init.svc.<SERVICE>` is non-empty iff the .rc snippet is installed. */
    const val SERVICE = "s1kiosk_radio"
    const val PROP_SVC_STATE = "init.svc.$SERVICE"
    /** Wanted mode, a [RadioMode.id]; persistent so the helper applies it at boot before the app runs. */
    const val PROP_MODE = "persist.s1kiosk.radio_mode"
    /** Helper -> app: "<state> <mode-id>[: detail]", state = applying | ok | error. */
    const val PROP_STATUS = "sys.s1kiosk.radio.status"
    /**
     * Helper -> app: contract version the installed helper implements. v2 (tools/radio/s1kiosk_radio.sh
     * 2026-11): zigbee_router = EZSP bridge on 127.0.0.1:[ZIGBEE_ROUTER_PORT] for the app's host,
     * thread_router = dataset hand-off from the app ([DATASET_FILE]), "applying <mode>: waiting for …"
     * until the stack answers, [PROP_OTBR].
     */
    const val PROP_VERSION = "sys.s1kiosk.radio.version"
    const val VERSION = 2
    /** Helper -> app (v2): which otbr-agent is installed: `br` (border router, REST), `agent` (proof build), `none`. */
    const val PROP_OTBR = "sys.s1kiosk.radio.otbr"

    /** EZSP-over-TCP port of the coordinator modes (bellows/ZHA: socket://<panel>:6638). */
    const val ZIGBEE_TCP_PORT = 6638
    /** zigbee_router: the helper's localhost-only EZSP bridge for [ZigbeeRouterHost]. */
    const val ZIGBEE_ROUTER_PORT = 6639
    /** thread (border-router build): REST API for Home Assistant's OTBR integration. */
    const val OTBR_REST_PORT = 8081

    /**
     * thread_router: the app writes the active dataset (hex TLVs) here, in its own files dir
     * (`files/radio/thread-dataset`); the helper moves it to its root-only dir and deletes this copy.
     */
    const val DATASET_FILE = "radio/thread-dataset"

    data class Status(val state: String, val mode: RadioMode?, val detail: String)

    /** Parses [PROP_STATUS]; null if unset or malformed. */
    fun parseStatus(raw: String?): Status? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val head = s.substringBefore(':').trim()
        val detail = if (':' in s) s.substringAfter(':').trim() else ""
        val parts = head.split(' ').filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0] !in setOf("applying", "ok", "error")) return null
        return Status(parts[0], RadioMode.fromId(parts[1]), detail)
    }
}
