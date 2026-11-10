package sh.edm.s1kiosk.radio

import sh.edm.s1kiosk.radio.ezsp.AshLink
import sh.edm.s1kiosk.radio.ezsp.Ezsp
import sh.edm.s1kiosk.radio.ezsp.EzspClient
import sh.edm.s1kiosk.radio.ezsp.EzspException
import sh.edm.s1kiosk.radio.ezsp.EzspTransport
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/** What RadioController needs from the Zigbee router host (a fake in tests). */
interface RouterHost {
    /** One line for the `Radio status` sensor. */
    val status: String
    fun start()
    fun stop()
    fun requestJoin()
    fun requestLeave()
}

/**
 * The Zigbee router role (docs/radio.md "Zigbee router"): a small EZSP host that drives zigbeed
 * (a full EmberZNet stack) as an EMBER_ROUTER. Routing, link status, child tables and ZDO all run in
 * zigbeed; this host only has to
 *  1. bring the stack up on every session (ASH reset, `version`, `networkInit`),
 *  2. join on request: well-known TC link key, active scan, `joinNetwork(ROUTER)` on the joinable
 *     network (the user opens permit-join in ZHA first), then update the TC link key,
 *  3. stay connected and answer ZHA's Basic-cluster reads (manufacturer/model) on endpoint 1.
 *
 * zigbeed keeps the joined network in its NVM, so later sessions are just `networkInit`.
 * Safety: zigbeed's NVM is shared with the Zigbee coordinator mode. If it holds a coordinator
 * network (ZHA's), this host does not bring it up and does not erase it on its own; only an explicit
 * [requestJoin] or [requestLeave] does (the helper keeps NVM copies in $DIR/zigbee-nvm).
 *
 * Runs on its own thread; [status] is a one-line text for the `Radio status` sensor.
 */
class ZigbeeRouterHost(
    private val connect: () -> EzspTransport = { tcp("127.0.0.1", RadioHelper.ZIGBEE_ROUTER_PORT) },
    private val log: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
    /** Tests/bring-up: scan and report, but never call joinNetwork. */
    private val dryRunJoin: Boolean = false,
) : RouterHost {
    enum class State { STARTING, NOT_JOINED, HOLDS_COORDINATOR, JOINING, JOINED, ERROR, STOPPED }

    data class Snapshot(val state: State, val detail: String)

    companion object {
        const val MANUFACTURER = "S1 Unleashed"
        const val MODEL = "S1 Panel Zigbee router"
        private const val ENDPOINT = 1
        private const val PROFILE_HA = 0x0104
        private const val DEVICE_RANGE_EXTENDER = 0x0008
        private const val CLUSTER_BASIC = 0x0000
        private const val TX_POWER_DBM = 8
        private const val JOIN_TIMEOUT_MS = 60_000L
        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val SCAN_DURATION = 3 // 2^3 + 1 beacon intervals per channel (~140 ms)

        fun tcp(host: String, port: Int): EzspTransport {
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 5_000)
            s.tcpNoDelay = true
            return object : EzspTransport {
                override val input = s.getInputStream()
                override val output = s.getOutputStream()
                override fun close() = s.close()
            }
        }

        /** Status texts name the channel only: never PAN ids, extended PAN ids or keys. */
        fun describe(channel: Int) = "channel $channel"
    }

    private val snap = AtomicReference(Snapshot(State.STOPPED, "not running"))
    val snapshot: Snapshot get() = snap.get()
    override val status: String get() = snapshot.let { statusText(it) }

    @Volatile private var running = false
    @Volatile private var pending: String? = null // "join" | "leave"
    private var thread: Thread? = null
    @Volatile private var link: AshLink? = null

    // session state (host thread only)
    private var stackUp = false
    private var stackStatus = -1
    private val found = ArrayList<Ezsp.FoundNetwork>()
    private var scanDone: Int? = null
    private var children = 0

    override fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "s1-zigbee-router").apply { isDaemon = true; start() }
    }

    override fun stop() {
        if (!running) return
        running = false
        link?.close()
        thread?.interrupt()
        thread = null
        set(State.STOPPED, "not running")
    }

    /** Join a network that allows joining (open permit-join in ZHA first). Replaces any network in zigbeed's NVM. */
    override fun requestJoin() { pending = "join" }

    /** Leave the current network (zigbeed forgets it). */
    override fun requestLeave() { pending = "leave" }

    private fun set(s: State, detail: String) {
        val n = Snapshot(s, detail)
        if (snap.getAndSet(n) != n) { log("router: ${s.name.lowercase()}: $detail"); onStatus(statusText(n)) }
    }

    /** Shown after "ok Zigbee router: " in the `Radio status` sensor. */
    private fun statusText(s: Snapshot) = when (s.state) {
        State.JOINED -> "in a network on ${s.detail}"
        State.NOT_JOINED -> "ready to join: ${s.detail}"
        State.HOLDS_COORDINATOR -> "idle: ${s.detail}"
        State.JOINING -> "joining: ${s.detail}"
        State.STARTING -> "starting: ${s.detail}"
        State.ERROR -> "router host error: ${s.detail}"
        State.STOPPED -> "router host stopped"
    }

    // ------------------------------------------------------------------ host thread

    private fun loop() {
        var backoff = 2_000L
        while (running) {
            try {
                set(State.STARTING, "connecting to zigbeed")
                session()
                backoff = 2_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                set(State.ERROR, "${e.message ?: e.javaClass.simpleName}; retrying")
            } finally {
                link?.close(); link = null
            }
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun session() {
        val l = AshLink(connect(), log).also { link = it }
        val ezsp = EzspClient(l, log)
        ezsp.onCallback = ::callback
        stackUp = false; children = 0; replies.clear()
        set(State.STARTING, "resetting zigbeed")
        initStack(l, ezsp)

        val init = ezsp.command(Ezsp.NETWORK_INIT, Ezsp.Writer().u16(0).build())[0].toInt() and 0xFF
        when (init) {
            Ezsp.SUCCESS -> {
                waitFor(ezsp, 15_000) { stackUp }
                val (nodeType, params) = networkParameters(ezsp)
                if (nodeType == Ezsp.ROUTER) {
                    set(State.JOINED, describe(params.radioChannel))
                } else {
                    // A coordinator (ZHA's) network: don't run it from here. Reset brings the stack
                    // down again without touching the NVM.
                    initStack(l, ezsp)
                    set(State.HOLDS_COORDINATOR, "the radio holds a Zigbee coordinator network (from coordinator mode). " +
                        "Joining as a router replaces it; open permit-join in ZHA, then press Join.")
                }
            }
            Ezsp.NOT_JOINED -> set(State.NOT_JOINED, "open permit-join in ZHA, then press Join")
            else -> throw EzspException("networkInit: ${Ezsp.statusName(init)}")
        }

        var lastNop = System.currentTimeMillis()
        while (running) {
            when (pending.also { pending = null }) {
                "join" -> join(ezsp)
                "leave" -> leave(ezsp)
            }
            ezsp.pollCallbacks(250) // also how quickly a Join/Leave request is picked up
            flushReplies(ezsp)
            if (System.currentTimeMillis() - lastNop > 30_000) { ezsp.command(Ezsp.NOP); lastNop = System.currentTimeMillis() }
        }
    }

    /**
     * ASH reset + `version` + the configuration phase. Needed after every reset: without the config
     * phase zigbeed's networkInit reports NOT_JOINED even with a network in its NVM (seen on the panel).
     */
    private fun initStack(l: AshLink, ezsp: EzspClient) {
        l.reset()
        stackUp = false
        val (proto, stackType, stackVer) = ezsp.version()
        if (proto != Ezsp.PROTOCOL_VERSION || stackType != Ezsp.STACK_TYPE_MESH) {
            throw EzspException("unexpected EZSP $proto / stack type $stackType")
        }
        log("router: EZSP $proto, stack 0x%04X".format(stackVer))
        // Config values may only be set before the network comes up; zigbeed already defaults to these.
        setConfig(ezsp, Ezsp.CONFIG_STACK_PROFILE, 2)
        setConfig(ezsp, Ezsp.CONFIG_SECURITY_LEVEL, 5)
        addEndpoint(ezsp)
    }

    private fun setConfig(ezsp: EzspClient, id: Int, value: Int) {
        val st = ezsp.command(Ezsp.SET_CONFIGURATION_VALUE, Ezsp.Writer().u8(id).u16(value).build())[0].toInt() and 0xFF
        if (st != 0) log("router: setConfigurationValue 0x%02X=%d -> 0x%02X (kept zigbeed's value)".format(id, value, st))
    }

    /** Endpoint 1, HA profile, range extender, Basic server: so ZHA's interview finds a name. */
    private fun addEndpoint(ezsp: EzspClient) {
        val p = Ezsp.Writer().u8(ENDPOINT).u16(PROFILE_HA).u16(DEVICE_RANGE_EXTENDER).u8(0)
            .u8(1).u8(0).u16(CLUSTER_BASIC).build()
        val st = ezsp.command(Ezsp.ADD_ENDPOINT, p)[0].toInt() and 0xFF
        if (st != 0) log("router: addEndpoint -> 0x%02X".format(st))
    }

    private fun networkParameters(ezsp: EzspClient): Pair<Int, Ezsp.NetworkParameters> {
        val r = Ezsp.Reader(ezsp.command(Ezsp.GET_NETWORK_PARAMETERS))
        val st = r.u8()
        if (st != Ezsp.SUCCESS) throw EzspException("getNetworkParameters: ${Ezsp.statusName(st)}")
        val type = r.u8()
        return type to Ezsp.NetworkParameters.read(r)
    }

    private fun waitFor(ezsp: EzspClient, ms: Long, cond: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (!cond()) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return false
            ezsp.pollCallbacks(left.coerceAtMost(500))
        }
        return true
    }

    private fun networkState(ezsp: EzspClient): Int = ezsp.command(Ezsp.NETWORK_STATE)[0].toInt() and 0xFF

    /** Brings the stored network up (if it isn't) so that it can be left; true when up. */
    private fun bringUp(ezsp: EzspClient): Boolean {
        if (stackUp || networkState(ezsp) == Ezsp.JOINED_NETWORK) { stackUp = true; return true }
        val init = ezsp.command(Ezsp.NETWORK_INIT, Ezsp.Writer().u16(0).build())[0].toInt() and 0xFF
        log("router: networkInit -> ${Ezsp.statusName(init)}")
        return init == Ezsp.SUCCESS && waitFor(ezsp, 15_000) { stackUp }
    }

    /** Leaves (erases) the network in zigbeed's NVM; verified with networkState. */
    private fun leaveCurrent(ezsp: EzspClient): Boolean {
        if (bringUp(ezsp)) {
            val st = ezsp.command(Ezsp.LEAVE_NETWORK)[0].toInt() and 0xFF
            log("router: leaveNetwork -> ${Ezsp.statusName(st)}")
            if (st == Ezsp.SUCCESS) waitFor(ezsp, 10_000) { !stackUp }
        }
        val ns = networkState(ezsp)
        if (ns != Ezsp.NO_NETWORK) log("router: network state after leave: $ns")
        stackUp = ns == Ezsp.JOINED_NETWORK
        return ns == Ezsp.NO_NETWORK
    }

    private fun leave(ezsp: EzspClient) {
        if (leaveCurrent(ezsp)) set(State.NOT_JOINED, "left the network; open permit-join in ZHA, then press Join")
        else set(State.ERROR, "couldn't leave the network")
    }

    private fun join(ezsp: EzspClient) {
        set(State.JOINING, "leaving the current network")
        if (!leaveCurrent(ezsp)) { set(State.ERROR, "couldn't leave the current network"); return }
        val sec = ezsp.command(Ezsp.SET_INITIAL_SECURITY_STATE, Ezsp.joinSecurityState())[0].toInt() and 0xFF
        if (sec != Ezsp.SUCCESS) { set(State.ERROR, "setInitialSecurityState: ${Ezsp.statusName(sec)}"); return }

        set(State.JOINING, "scanning for a network that allows joining")
        found.clear(); scanDone = null
        val scan = Ezsp.Writer().u8(1 /* active */).u32(Ezsp.ALL_CHANNELS).u8(SCAN_DURATION).build()
        val st = ezsp.command(Ezsp.START_SCAN, scan)[0].toInt() and 0xFF
        if (st != Ezsp.SUCCESS) { set(State.ERROR, "startScan: ${Ezsp.statusName(st)}"); return }
        if (!waitFor(ezsp, SCAN_TIMEOUT_MS) { scanDone != null }) { set(State.ERROR, "scan did not complete"); return }

        val candidates = found.filter { it.allowingJoin && it.stackProfile == 2 }.distinctBy { it.extendedPanId.toList() }
        log("router: scan found ${found.distinctBy { it.extendedPanId.toList() }.size} network(s), ${candidates.size} allowing joins")
        val target = candidates.maxByOrNull { it.rssi }
        if (target == null) {
            set(State.NOT_JOINED, "no Zigbee network allows joining right now (${found.distinctBy { it.extendedPanId.toList() }.size} seen); " +
                "open permit-join in ZHA, then press Join again")
            return
        }
        if (candidates.size > 1) log("router: ${candidates.size} networks allow joining; taking the strongest (${describe(target.channel)})")
        if (dryRunJoin) {
            set(State.NOT_JOINED, "dry run: would join the network on ${describe(target.channel)}")
            return
        }
        set(State.JOINING, "joining the network on ${describe(target.channel)}")
        stackStatus = -1
        val params = Ezsp.NetworkParameters(
            extendedPanId = target.extendedPanId, panId = target.panId, radioTxPower = TX_POWER_DBM,
            radioChannel = target.channel, joinMethod = 0, nwkManagerId = 0, nwkUpdateId = target.nwkUpdateId, channels = 0,
        )
        val w = Ezsp.Writer().u8(Ezsp.ROUTER); params.write(w)
        val js = ezsp.command(Ezsp.JOIN_NETWORK, w.build())[0].toInt() and 0xFF
        if (js != Ezsp.SUCCESS) { set(State.ERROR, "joinNetwork: ${Ezsp.statusName(js)}"); return }
        waitFor(ezsp, JOIN_TIMEOUT_MS) { stackUp || (stackStatus != -1 && stackStatus != Ezsp.NETWORK_UP) }
        if (!stackUp) {
            set(State.NOT_JOINED, "join failed (${if (stackStatus == -1) "timeout" else Ezsp.statusName(stackStatus)}); " +
                "check that permit-join is open in ZHA, then press Join again")
            return
        }
        // Zigbee 3.0: replace the well-known TC link key with a unique one (ZHA's trust center allows
        // legacy devices that skip this, so a failure isn't fatal).
        runCatching { ezsp.command(Ezsp.UPDATE_TC_LINK_KEY, byteArrayOf(5)) }.onFailure { log("router: updateTcLinkKey: $it") }
        val (_, p) = networkParameters(ezsp)
        set(State.JOINED, describe(p.radioChannel))
    }

    // ------------------------------------------------------------------ callbacks

    private fun callback(id: Int, p: ByteArray) {
        when (id) {
            Ezsp.STACK_STATUS_HANDLER -> {
                val s = p[0].toInt() and 0xFF
                stackStatus = s
                when (s) {
                    Ezsp.NETWORK_UP -> stackUp = true
                    Ezsp.NETWORK_DOWN -> {
                        val was = stackUp
                        stackUp = false
                        if (was && snapshot.state == State.JOINED) set(State.JOINED, "network down, the stack is rejoining")
                    }
                }
                log("router: stack status ${Ezsp.statusName(s)}")
            }
            Ezsp.NETWORK_FOUND_HANDLER -> found += Ezsp.FoundNetwork.read(p)
            Ezsp.SCAN_COMPLETE_HANDLER -> {
                val st = p[1].toInt() and 0xFF
                if (st == Ezsp.SUCCESS) scanDone = st else log("router: scan error on channel ${p[0].toInt() and 0xFF}: ${Ezsp.statusName(st)}")
            }
            Ezsp.CHILD_JOIN_HANDLER -> {
                val joining = p[1].toInt() != 0
                children = (children + if (joining) 1 else -1).coerceAtLeast(0)
            }
            Ezsp.INCOMING_MESSAGE_HANDLER -> onIncoming(Ezsp.Incoming.read(p))
        }
    }

    /**
     * ZCL replies waiting to be sent. Callbacks are dispatched while a command waits for its own
     * response, so a reply is never sent from inside one: the host loop sends them ([flushReplies]),
     * within about a second, well inside ZHA's read timeout.
     */
    private val replies = ArrayList<ByteArray>()

    private fun onIncoming(m: Ezsp.Incoming) {
        if (m.aps.profileId != PROFILE_HA || m.aps.clusterId != CLUSTER_BASIC || m.aps.dstEp != ENDPOINT) return
        val reply = Zcl.basicReadReply(m.message, MANUFACTURER, MODEL) ?: return
        val aps = Ezsp.ApsFrame(PROFILE_HA, CLUSTER_BASIC, ENDPOINT, m.aps.srcEp, 0x0140 /* retry + route discovery */, 0, 0)
        val w = Ezsp.Writer().u8(0 /* direct */).u16(m.sender)
        aps.write(w)
        w.u8(0).lv(reply)
        if (replies.size < 16) replies += w.build()
    }

    private fun flushReplies(ezsp: EzspClient) {
        while (replies.isNotEmpty()) {
            val frame = replies.removeAt(0)
            runCatching { ezsp.command(Ezsp.SEND_UNICAST, frame) }.onFailure { log("router: Basic reply failed: $it") }
        }
    }
}

/** The few ZCL bits the router answers: Basic cluster Read Attributes. Pure (ZigbeeRouterHostTest). */
object Zcl {
    private const val STATUS_SUCCESS = 0x00
    private const val UNSUPPORTED_ATTRIBUTE = 0x86

    /**
     * Reply to a Basic-cluster Read Attributes request ([req] = ZCL frame), or null if it isn't one.
     * Supported: ZCL version, app/stack/HW version, manufacturer, model, power source (mains).
     */
    fun basicReadReply(req: ByteArray, manufacturer: String, model: String): ByteArray? {
        if (req.size < 3) return null
        val fc = req[0].toInt() and 0xFF
        val mfrSpecific = fc and 0x04 != 0
        val hdr = if (mfrSpecific) 5 else 3
        if (req.size < hdr || fc and 0x03 != 0 /* not profile-wide */ || fc and 0x08 != 0 /* not client->server */) return null
        val tsn = req[hdr - 2]
        if (req[hdr - 1].toInt() != 0x00 /* Read Attributes */ || mfrSpecific) return null
        val out = Ezsp.Writer().u8(0x18 /* profile-wide, server->client, no default response */).u8(tsn.toInt()).u8(0x01)
        var i = hdr
        while (i + 1 < req.size) {
            val attr = (req[i].toInt() and 0xFF) or ((req[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            out.u16(attr)
            when (attr) {
                0x0000 -> out.u8(STATUS_SUCCESS).u8(0x20).u8(3)        // ZCLVersion uint8
                0x0001 -> out.u8(STATUS_SUCCESS).u8(0x20).u8(1)        // ApplicationVersion
                0x0002 -> out.u8(STATUS_SUCCESS).u8(0x20).u8(0x73)     // StackVersion (EmberZNet 7.3)
                0x0003 -> out.u8(STATUS_SUCCESS).u8(0x20).u8(1)        // HWVersion
                0x0004 -> out.u8(STATUS_SUCCESS).u8(0x42).lv(manufacturer.toByteArray(Charsets.UTF_8))
                0x0005 -> out.u8(STATUS_SUCCESS).u8(0x42).lv(model.toByteArray(Charsets.UTF_8))
                0x0007 -> out.u8(STATUS_SUCCESS).u8(0x30).u8(0x01)     // PowerSource: mains (single phase)
                else -> out.u8(UNSUPPORTED_ATTRIBUTE)
            }
        }
        return out.build()
    }
}
