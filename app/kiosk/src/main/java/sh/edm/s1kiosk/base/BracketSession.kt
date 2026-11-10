package sh.edm.s1kiosk.base

import sh.edm.s1kiosk.base.BracketProtocol.Msg
import sh.edm.s1kiosk.base.BracketProtocol.Res

/**
 * Host side of the wall base UART protocol, as libha_bracket.so runs it inside ha_master, as a pure
 * (clock-injected, single-threaded) state machine. The caller feeds received bytes ([onBytes]),
 * calls [tick] often (every ~100 ms) and writes whatever [Out.send] hands it to the port.
 *
 * What it does, with the libha_bracket function it mirrors (docs/research/base-protocol.md):
 * - **Handshake**: read `200.1.159` (bracket_ready / bracket_restore). Model `0xF110` = online:
 *   synthesize `14.160.85` (dock class), `13.8.85` (channels), `14.21.85` (relay mask); write the
 *   config resources with their defaults (dev_config: 200.4.1=3, 4.103.85=1, 200.4.2=85,
 *   200.4.3=2200, 200.4.4=10000); read firmware and relays (handle_read_rsp @0x8434).
 * - A power report while offline starts the handshake (handle_report @0x8bb8).
 * - One request in flight; 200 ms response timeout, 3 transmissions (each with a new seq), at least
 *   51 ms between transmissions (dev_write_timeout / serial_busy). A write that never gets a
 *   status-0 ack is followed by a read of the same resource.
 * - Reports: relays, power/current (rounded to 0.01), protection (`200.4.5`/`200.4.6` ->
 *   `8.0.2014`), energy `0.13.85` integrated here from power (ha_master does it host-side too).
 * - Liveness: ha_master marks the base offline after 61 ticks (~61 s) without a frame. Here that
 *   triggers a handshake probe; only an unanswered probe reports the base as gone (dock class 0).
 *
 * Values are delivered with the same resource names the LocalService backend uses, so
 * [BaseTracker] doesn't care which backend fed it.
 */
class BracketSession(private val out: Out, initialEnergyWh: Float = 0f) {

    interface Out {
        fun send(frame: ByteArray)
        /** Resource values for the tracker (names as in [BaseRes]). */
        fun values(values: Map<String, Any>, why: String)
        fun log(info: Boolean, msg: String)
    }

    companion object {
        const val RESPONSE_TIMEOUT_MS = 200L
        const val MAX_SENDS = 3
        const val MIN_GAP_MS = 51L
        const val SILENCE_MS = 61_000L
        const val MAX_QUEUE = 20
        private const val POWER = "0.12.85"
        private const val ENERGY = "0.13.85"
    }

    private class Req(val write: Boolean, val res: Res, val value: Number?) {
        var seq = -1
        var sends = 0
        var sentAt = 0L
    }

    private val queue = ArrayDeque<Req>()
    private val framer = BracketProtocol.Framer()
    private var seq = 0
    private var lastSendAt = Long.MIN_VALUE / 2
    private var lastRxAt = Long.MIN_VALUE / 2

    /** Model 0xF110 answered the handshake and the base hasn't been silent long enough to probe. */
    var online = false
        private set
    var identity: BracketProtocol.Identity? = null
        private set
    /** The last handshake attempt got no answer at all (vs. never tried). */
    var handshakeFailed = false
        private set
    var energyWh = initialEnergyWh
        private set
    private var lastPower: Float? = null
    private var lastPowerAt = 0L
    private val cache = HashMap<String, Number>()
    private val unknownLogged = HashSet<Int>()
    val framesIn: Long get() = rxFrames
    private var rxFrames = 0L

    // ------------------------------------------------------------------ API

    /** Start (or re-run) the handshake. */
    fun handshake(now: Long) {
        if (queue.any { !it.write && it.res.name == BracketProtocol.IDENTITY }) return
        enqueue(Req(false, BracketProtocol.res(BracketProtocol.IDENTITY)!!, null), now, front = true)
    }

    /**
     * Read resources. Host-side ones (`14.160.85`, `13.8.85`, `14.21.85`, `0.13.85`, `8.0.2014`)
     * answer from state; a dock class read while not online runs the handshake (answered 0 if the
     * base doesn't reply). Names that aren't on the wire (e.g. `4.x.28`) are ignored.
     */
    fun read(names: List<String>, now: Long) {
        val synth = LinkedHashMap<String, Any>()
        for (n in names) {
            when (n) {
                BaseRes.DOCK_CLASS -> if (online) identityValues(synth) else handshake(now)
                BaseRes.CHANNEL_COUNT, BaseRes.RELAY_MASK -> if (online) identityValues(synth)
                ENERGY -> if (online) synth[ENERGY] = energyWh
                BaseRes.PROTECTION -> cache[BaseRes.PROTECTION]?.let { synth[n] = it }
                else -> {
                    val r = BracketProtocol.res(n) ?: continue
                    if (!online) continue
                    if (r.name.startsWith("4.") && r.name != BracketProtocol.POWER_ON_STATE &&
                        relayIndex(r.name) > (identity?.channels ?: 0)) continue
                    if (queue.none { !it.write && it.res == r && it.sends == 0 }) enqueue(Req(false, r, null), now)
                }
            }
        }
        if (synth.isNotEmpty()) out.values(synth, "state")
    }

    /** Write a resource (only once online); false if refused. Replaces a not-yet-sent write of it. */
    fun write(name: String, value: Number, now: Long): Boolean {
        val r = BracketProtocol.res(name) ?: return false
        if (!online) {
            out.log(true, "write $name=$value refused: base not online")
            return false
        }
        queue.removeAll { it.write && it.res == r && it.sends == 0 }
        enqueue(Req(true, r, value), now)
        return true
    }

    fun onBytes(data: ByteArray, off: Int, n: Int, now: Long) {
        for (f in framer.feed(data, off, n)) {
            rxFrames++
            lastRxAt = now
            val m = BracketProtocol.decode(f)
            out.log(false, "RX ${BracketProtocol.hex(f.raw)} $m")
            handle(m, now)
        }
    }

    fun tick(now: Long) {
        val head = queue.firstOrNull()
        if (head != null) {
            if (head.sends > 0 && now - head.sentAt >= RESPONSE_TIMEOUT_MS) {
                if (head.sends >= MAX_SENDS) {
                    queue.removeFirst()
                    timedOut(head, now)
                } else if (now - lastSendAt >= MIN_GAP_MS) {
                    transmit(head, now)
                }
            } else if (head.sends == 0 && now - lastSendAt >= MIN_GAP_MS) {
                transmit(head, now)
            }
        }
        if (online && now - lastRxAt > SILENCE_MS && queue.isEmpty()) {
            out.log(true, "base silent for ${(now - lastRxAt) / 1000} s; probing")
            lastRxAt = now // one probe per silence period
            handshake(now)
        }
    }

    val pending: Int get() = queue.size
    val junkBytes: Long get() = framer.junkBytes
    val badCrc: Long get() = framer.badCrc

    // ------------------------------------------------------------------ internals

    private fun enqueue(r: Req, now: Long, front: Boolean = false) {
        if (queue.size >= MAX_QUEUE) {
            out.log(true, "request queue full; dropping ${r.res.name}")
            return
        }
        if (front && queue.firstOrNull()?.sends == 0) queue.addFirst(r)
        else if (front && queue.isNotEmpty()) queue.add(1, r)
        else queue.addLast(r)
        tick(now)
    }

    private fun transmit(r: Req, now: Long) {
        seq = (seq + 1) and 0xFF
        r.seq = seq
        r.sends++
        r.sentAt = now
        lastSendAt = now
        val f = if (r.write) BracketProtocol.encodeWrite(seq, r.res, r.value!!) else BracketProtocol.encodeRead(seq, r.res)
        out.log(false, "TX ${BracketProtocol.hex(f)} ${if (r.write) "write ${r.res.name}=${r.value}" else "read ${r.res.name}"} (try ${r.sends})")
        out.send(f)
    }

    private fun timedOut(r: Req, now: Long) {
        out.log(true, "${if (r.write) "write" else "read"} ${r.res.name}: no answer after $MAX_SENDS tries")
        if (r.res.name == BracketProtocol.IDENTITY) {
            handshakeFailed = true
            val was = online
            online = false
            identity = null
            if (was) out.log(true, "base offline (no handshake answer)")
            // Like the LocalService "none" reply: no base answers on this port.
            out.values(mapOf(BaseRes.DOCK_CLASS to 0), "no handshake answer")
        } else if (r.write) {
            enqueue(Req(false, r.res, null), now) // refresh the real state (dev_write_timeout)
        }
    }

    private fun takeInFlight(seq: Int): Req? {
        val head = queue.firstOrNull() ?: return null
        if (head.sends == 0 || head.seq != seq) return null
        return queue.removeFirst()
    }

    private fun handle(m: Msg, now: Long) {
        when (m) {
            is Msg.ReadRsp -> {
                val req = takeInFlight(m.seq)
                if (req == null || req.write || m.res != req.res) {
                    // Put a mismatching in-flight request back (it times out and is retried).
                    if (req != null) queue.addFirst(req)
                    out.log(true, "unexpected read response seq=${m.seq} ${m.res?.name ?: "0x%06x".format(m.id)}")
                    return
                }
                if (m.value != null) onValue(m.res!!, m.value, now, "read")
                tick(now)
            }
            is Msg.WriteAck -> {
                val head = queue.firstOrNull()
                if (head == null || !head.write || head.sends == 0 || head.seq != m.seq) {
                    out.log(true, "unexpected write ack seq=${m.seq} status=${m.status}")
                    return
                }
                if (m.status != 0) {
                    out.log(true, "write ${head.res.name}=${head.value}: status ${m.status}; will retry")
                    return
                }
                queue.removeFirst()
                if (!head.res.isConfig || head.res.name == BracketProtocol.POWER_ON_STATE) onValue(head.res, head.value!!, now, "write ack")
                else cache[head.res.name] = head.value!!
                tick(now)
            }
            is Msg.Report -> {
                val r = m.res
                if (r == null || m.value == null) {
                    if (unknownLogged.add(m.id)) out.log(true, "report of unknown resource 0x%06x (logged once)".format(m.id))
                    return
                }
                if (!online && r.name == POWER) {
                    out.log(true, "power report while offline: handshake")
                    handshake(now)
                }
                onValue(r, m.value, now, "report")
            }
            is Msg.Other -> out.log(true, "ignored frame type ${m.type} seq=${m.seq}")
        }
    }

    private fun identityValues(into: MutableMap<String, Any>) {
        val id = identity ?: return
        into[BaseRes.DOCK_CLASS] = id.dockClass
        into[BaseRes.CHANNEL_COUNT] = id.channels
        into[BaseRes.RELAY_MASK] = id.relayMask
    }

    private fun relayIndex(name: String): Int = name.removePrefix("4.").substringBefore(".").toIntOrNull() ?: 0

    private fun onValue(r: Res, v: Number, now: Long, why: String) {
        cache[r.name] = v
        val vals = LinkedHashMap<String, Any>()
        when (r.name) {
            BracketProtocol.IDENTITY -> {
                val id = BracketProtocol.Identity(v.toLong())
                handshakeFailed = false
                if (!id.supported) {
                    online = false
                    identity = null
                    out.log(true, "unsupported base model 0x%04x (200.1.159=0x%08x); ha_master would stay offline too".format(id.model, id.raw))
                    // Shown as "unknown (0x7f)": something answers, but not a base we know how to drive.
                    vals[BaseRes.DOCK_CLASS] = 0x7F000000L or id.model.toLong()
                } else {
                    val fresh = !online || identity != id
                    online = true
                    identity = id
                    identityValues(vals)
                    if (fresh) {
                        out.log(true, "base online: model 0x%04x, %d channel(s)".format(id.model, id.channels))
                        for (c in BracketProtocol.RES.filter { it.isConfig }) {
                            queue.addLast(Req(true, c, c.default))
                        }
                        for (a in BracketProtocol.RES.filter { it.attr and 1 != 0 && it.name != BracketProtocol.IDENTITY }) {
                            if (a.name.startsWith("4.") && relayIndex(a.name) > id.channels) continue
                            queue.addLast(Req(false, a, null))
                        }
                        lastPowerAt = now
                        vals[ENERGY] = energyWh
                    }
                }
            }
            POWER -> {
                val p = BracketProtocol.round2(v.toFloat())
                integrate(now)
                lastPower = p
                vals[POWER] = p
                vals[ENERGY] = energyWh
            }
            BracketProtocol.CURRENT -> cache[r.name] = BracketProtocol.round2(v.toFloat())
            BracketProtocol.OVERHEAT, BracketProtocol.OVERLOAD -> {
                BracketProtocol.protection(r.name, v.toInt())?.let {
                    cache[BaseRes.PROTECTION] = it
                    vals[BaseRes.PROTECTION] = it
                }
            }
            ENERGY -> Unit // host-side counter; the base's value isn't used (like ha_master)
            else -> vals[r.name] = v
        }
        if (vals.isNotEmpty()) out.values(vals, why)
    }

    private fun integrate(now: Long) {
        val p = lastPower
        if (p != null && p > 0f && now > lastPowerAt) energyWh += p * ((now - lastPowerAt) / 3_600_000f)
        lastPowerAt = now
    }
}
