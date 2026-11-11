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
    ZIGBEE_ROUTER("zigbee_router", "Zigbee router (repeater)", true, minHelperVersion = 2),
    THREAD("thread", "Thread border router", true),
    /** Thread repeater (FTD) joining an existing network with a dataset from HA; no BR/REST/mDNS. */
    THREAD_ROUTER("thread_router", "Thread router (repeater)", true, minHelperVersion = 2);

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

        /**
         * Labels before 0.4.3 ("Zigbee router", "Thread router"): still accepted when they arrive (an
         * automation written for the old select options, a stored label), never offered.
         */
        val LEGACY_LABELS: Map<String, RadioMode> = mapOf("Zigbee router" to ZIGBEE_ROUTER, "Thread router" to THREAD_ROUTER)

        fun fromLabel(label: String): RadioMode? = entries.firstOrNull { it.label == label } ?: LEGACY_LABELS[label]
        fun fromId(id: String): RadioMode? = entries.firstOrNull { it.id == id }

        /**
         * Stored value -> mode. Accepts ids and labels (the stub stored labels); unknown or retired
         * values ("Aqara (stock)", the stub's "Thread (future)") fall back to [DEFAULT].
         */
        fun parse(stored: String?): RadioMode =
            stored?.let { fromId(it) ?: fromLabel(it) } ?: DEFAULT
    }
}

/**
 * The Zigbee stack behind the coordinator mode (helper v4, [RadioHelper.PROP_ZIGBEE_STACK]). Aqara's
 * zigbeed 7.3.3 speaks EZSP 12 (ZHA only); Silicon Labs' zigbeed 8.2.2 speaks EZSP 18 (Zigbee2MQTT's
 * `ember` adapter, and ZHA), runs on the same stock radio firmware, and is downloaded by the panel from
 * Silicon Labs ([SisdkZigbeed]). The Zigbee router mode always uses Aqara's (its host speaks EZSP 12).
 */
enum class ZigbeeStack(val id: String, val label: String) {
    AQARA("aqara", "Aqara 7.3.3 (EZSP 12, ZHA)"),
    SISDK("sisdk", "Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)");

    companion object {
        val DEFAULT = AQARA
        val LABELS: List<String> = entries.map { it.label }
        fun fromLabel(label: String): ZigbeeStack? = entries.firstOrNull { it.label == label }
        fun parse(stored: String?): ZigbeeStack = entries.firstOrNull { it.id == stored || it.label == stored } ?: DEFAULT
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
    const val VERSION = 4
    /** v3: the firewall ([PROP_ALLOW], [PROP_ALLOWED]); otherwise the same as v2. */
    const val FIREWALL_VERSION = 3
    /**
     * v4 (boot image with the radio bundle loader): signed radio bundles ([RadioBundle]), the Zigbee
     * stack choice ([PROP_ZIGBEE_STACK]), radio firmware updates ([RadioFirmware]). Otherwise as v3.
     */
    const val BUNDLE_VERSION = 4
    /** Helper -> app (v4): which helper runs: "boot[ (why)]" | "bundle <version>" | "dev". */
    const val PROP_SOURCE = "sys.s1kiosk.radio.source"
    /** Helper -> app (v4): the installed radio bundle: "none" | "ok <version>" | "bad: <why>". */
    const val PROP_BUNDLE = "sys.s1kiosk.radio.bundle"
    /** App -> helper (v4): Zigbee stack for the coordinator mode, a [ZigbeeStack] id (persistent). */
    const val PROP_ZIGBEE_STACK = "persist.s1kiosk.radio_zigbee"
    /**
     * Helper -> app (v4): "aqara" | "sisdk ready <ver>" | "sisdk missing <ver> <sha256>" (the app should
     * download it) | "sisdk no-runtime" (the radio bundle lacks it) | "sisdk bad <why>".
     */
    const val PROP_ZIGBEE = "sys.s1kiosk.radio.zigbee"
    /** Helper -> app (v4): "<idle|flashing|ok|error> <id>[: detail]" ([RadioFirmware]). */
    const val PROP_FW = "sys.s1kiosk.radio.fw"
    /** Helper -> app (v4): flashable images, "<id>:<sha256 prefix>:<APP version> ..." (stock first). */
    const val PROP_FW_TARGETS = "sys.s1kiosk.radio.fw_targets"
    /** App -> helper (v4): "<id> <sha256 prefix>\n" in the app's files dir; taken within 3 s, ignored after 2 min. */
    const val FW_REQUEST_FILE = "radio/fw-request"
    /** App -> helper (v4): Silicon Labs' zigbeed, downloaded by the app; taken only with the pinned SHA-256. */
    const val ZIGBEED_FILE = "radio/zigbeed-${SisdkZigbeed.VERSION}"

    /** Radio bundle install service (boot image with the loader): oneshot, started by the app. */
    const val BUNDLE_SERVICE = "s1kiosk_bundle"
    /** Set to 1 by init at boot when [BUNDLE_SERVICE] exists. Informational only: any app can set sys.*
     *  props, so the app decides from [RadioBundle.PUB_FILE] instead. */
    const val PROP_BUNDLE_LOADER = "sys.s1kiosk.bundle.loader"
    /** Service -> app: "requested" (app) | "running: …" | "ok: …" | "error: …". */
    const val PROP_BUNDLE_STATUS = "sys.s1kiosk.bundle.status"
    /** App -> helper (v4): "Leave Thread network" request in the app's files dir (any mode). */
    const val THREAD_LEAVE_FILE = "radio/thread-leave"
    /** Helper -> app (v4): "<ok|error> <epoch seconds>: <detail>" for the last Thread leave. */
    const val PROP_THREAD_LEAVE = "sys.s1kiosk.radio.thread_leave"
    /** Where the app stages the bundle for [BUNDLE_SERVICE] (files/radio/bundle-in). */
    const val BUNDLE_STAGE_DIR = "radio/bundle-in"
    /**
     * Helper -> app (0.4.3 bundle): "yes" when a Thread router network is stored (a dataset or the
     * router's settings, what `Leave Thread network` deletes), "no" when not; unset with older helpers.
     */
    const val PROP_THREAD_STORED = "sys.s1kiosk.radio.thread_stored"
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

    /**
     * App -> helper (v3): who may reach tcp:[ZIGBEE_TCP_PORT] and tcp:[OTBR_REST_PORT] (iptables, every
     * mode but off). "" = nobody but loopback (no Home Assistant address known yet), "any" = no firewall,
     * else space-separated IPv4/IPv6 addresses or CIDRs ([RadioAllow.decide]). Persistent so the helper
     * has it at boot before the app runs; re-read every 3 s (no helper restart). `RADIO_ALLOW` in the
     * helper's radio.conf wins over it.
     */
    const val PROP_ALLOW = "persist.s1kiosk.radio_allow"
    /** Helper -> app (v3): what the firewall enforces now: "<app|conf> <any|none|addr ...>"; "" in off. */
    const val PROP_ALLOWED = "sys.s1kiosk.radio.allowed"

    data class Status(val state: String, val mode: RadioMode?, val detail: String)

    /** [PROP_ALLOWED] parsed. [source]: "app" (from [PROP_ALLOW]) or "conf" (radio.conf RADIO_ALLOW). */
    data class Allowed(val source: String, val any: Boolean, val addresses: List<String>)

    /** Parses [PROP_ALLOWED]; null if unset (off, or a helper without the firewall) or malformed. */
    fun parseAllowed(raw: String?): Allowed? {
        val parts = raw?.trim().orEmpty().split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2 || parts[0] !in setOf("app", "conf")) return null
        val rest = parts.drop(1)
        return when {
            rest == listOf(RadioAllow.ANY) -> Allowed(parts[0], true, emptyList())
            rest == listOf("none") -> Allowed(parts[0], false, emptyList())
            else -> Allowed(parts[0], false, rest)
        }
    }

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
