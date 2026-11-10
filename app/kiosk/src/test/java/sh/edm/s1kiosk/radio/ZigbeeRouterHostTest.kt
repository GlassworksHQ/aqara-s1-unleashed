package sh.edm.s1kiosk.radio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.ezsp.Ash
import sh.edm.s1kiosk.radio.ezsp.Ezsp
import sh.edm.s1kiosk.radio.ezsp.EzspTransport
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections

/**
 * A fake zigbeed behind ASH: answers the EZSP v12 commands the router host uses and plays a stored
 * network ([nvm]: null, "coordinator" or "router"), beacons ([beacons]) and a Basic-cluster read.
 */
private class FakeNcp(var nvm: String?, val beacons: List<Ezsp.FoundNetwork> = emptyList(), val joinOk: Boolean = true) {
    private val toHost = PipedOutputStream()
    private val hostIn = PipedInputStream(toHost, 1 shl 16)
    private val fromHost = PipedOutputStream()
    private val ncpIn = PipedInputStream(fromHost, 1 shl 16)
    val commands: MutableList<Int> = Collections.synchronizedList(ArrayList())
    val sent: MutableList<ByteArray> = Collections.synchronizedList(ArrayList()) // sendUnicast payloads
    @Volatile var up = false
    private var txFrm = 0
    private var rxNext = 0
    private val out = Any()

    val transport = object : EzspTransport {
        override val input = hostIn
        override val output = fromHost
        override fun close() { runCatching { fromHost.close() }; runCatching { toHost.close() } }
    }

    init {
        Thread({
            val d = Ash.Decoder()
            val buf = ByteArray(256)
            try {
                while (true) {
                    val n = ncpIn.read(buf)
                    if (n < 0) break
                    d.feed(buf, n) { onFrame(it) }
                }
            } catch (_: Exception) {}
        }, "fake-ncp").apply { isDaemon = true; start() }
    }

    private fun write(f: Ash.Frame) = synchronized(out) { toHost.write(Ash.encode(f)); toHost.flush() }

    private fun send(ezsp: ByteArray) = synchronized(out) {
        write(Ash.Frame.Data(txFrm, rxNext, false, ezsp)); txFrm = (txFrm + 1) and 7
    }

    private fun onFrame(f: Ash.Frame) {
        when (f) {
            Ash.Frame.Rst -> { txFrm = 0; rxNext = 0; up = false; write(Ash.Frame.RstAck(2, Ash.RESET_SOFTWARE)) }
            is Ash.Frame.Data -> {
                if (f.frm != rxNext) { write(Ash.Frame.Ack(rxNext)); return } // duplicate
                rxNext = (rxNext + 1) and 7
                write(Ash.Frame.Ack(rxNext))
                handle(f.data)
            }
            else -> Unit
        }
    }

    private fun reply(seq: Int, id: Int, params: ByteArray) =
        send(byteArrayOf(seq.toByte(), 0x80.toByte(), 0x01, id.toByte(), (id shr 8).toByte()) + params)

    fun callback(id: Int, params: ByteArray) =
        send(byteArrayOf(0, 0x90.toByte(), 0x01, id.toByte(), (id shr 8).toByte()) + params)

    private fun params(type: Int, channel: Int) = Ezsp.Writer().u8(0).u8(type).also {
        Ezsp.NetworkParameters(ByteArray(8) { 0x11 }, 0x1234, 8, channel).write(it)
    }.build()

    private fun handle(b: ByteArray) {
        val seq = b[0].toInt() and 0xFF
        if (b.size == 4 && b[2].toInt() == 0) { // legacy version
            send(byteArrayOf(seq.toByte(), 0x80.toByte(), 0x00, 12, 2, 0x30, 0x73)); return
        }
        val id = (b[3].toInt() and 0xFF) or ((b[4].toInt() and 0xFF) shl 8)
        commands += id
        val p = b.copyOfRange(5, b.size)
        when (id) {
            Ezsp.NETWORK_STATE -> reply(seq, id, byteArrayOf((if (up) Ezsp.JOINED_NETWORK else Ezsp.NO_NETWORK).toByte()))
            Ezsp.NETWORK_INIT -> if (up) reply(seq, id, byteArrayOf(0x70)) else if (nvm == null) reply(seq, id, byteArrayOf(Ezsp.NOT_JOINED.toByte())) else {
                reply(seq, id, byteArrayOf(0)); up = true; callback(Ezsp.STACK_STATUS_HANDLER, byteArrayOf(Ezsp.NETWORK_UP.toByte()))
            }
            Ezsp.GET_NETWORK_PARAMETERS -> reply(seq, id, params(if (nvm == "router") Ezsp.ROUTER else Ezsp.COORDINATOR, 15))
            Ezsp.LEAVE_NETWORK -> { reply(seq, id, byteArrayOf(0)); nvm = null; up = false; callback(Ezsp.STACK_STATUS_HANDLER, byteArrayOf(Ezsp.NETWORK_DOWN.toByte())) }
            Ezsp.START_SCAN -> {
                reply(seq, id, byteArrayOf(0))
                for (n in beacons) callback(Ezsp.NETWORK_FOUND_HANDLER, Ezsp.Writer().u8(n.channel).u16(n.panId).bytes(n.extendedPanId)
                    .u8(if (n.allowingJoin) 1 else 0).u8(n.stackProfile).u8(n.nwkUpdateId).u8(n.lqi).u8(n.rssi).build())
                callback(Ezsp.SCAN_COMPLETE_HANDLER, byteArrayOf(0xFF.toByte(), 0))
            }
            Ezsp.JOIN_NETWORK -> {
                reply(seq, id, byteArrayOf(0))
                if (joinOk) { nvm = "router"; up = true; callback(Ezsp.STACK_STATUS_HANDLER, byteArrayOf(Ezsp.NETWORK_UP.toByte())) }
                else callback(Ezsp.STACK_STATUS_HANDLER, byteArrayOf(Ezsp.JOIN_FAILED.toByte()))
            }
            Ezsp.SEND_UNICAST -> { sent += p; reply(seq, id, byteArrayOf(0, 1)) }
            else -> reply(seq, id, byteArrayOf(0))
        }
    }
}

class ZigbeeRouterHostTest {
    private var host: ZigbeeRouterHost? = null

    @After fun tearDown() { host?.stop() }

    private fun start(ncp: FakeNcp, dryRun: Boolean = false): ZigbeeRouterHost =
        ZigbeeRouterHost(connect = { ncp.transport }, dryRunJoin = dryRun).also { host = it; it.start() }

    private fun ZigbeeRouterHost.waitFor(state: ZigbeeRouterHost.State, ms: Long = 8_000): ZigbeeRouterHost.Snapshot {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (snapshot.state == state) return snapshot
            Thread.sleep(20)
        }
        throw AssertionError("wanted $state, still ${snapshot.state}: ${snapshot.detail}")
    }

    private fun beacon(join: Boolean, rssi: Int, channel: Int = 20, epid: Byte = 1) =
        Ezsp.FoundNetwork(channel, 0x4242, ByteArray(8) { epid }, join, 2, 0, 200, rssi)

    @Test
    fun emptyNvmIsReadyToJoin() {
        val ncp = FakeNcp(nvm = null)
        val h = start(ncp)
        assertTrue(h.waitFor(ZigbeeRouterHost.State.NOT_JOINED).detail.contains("permit-join"))
        assertTrue(Ezsp.ADD_ENDPOINT in ncp.commands)
        assertTrue(h.status, h.status.startsWith("ready to join: open permit-join"))
    }

    @Test
    fun storedRouterNetworkComesBackUp() {
        val ncp = FakeNcp(nvm = "router")
        val h = start(ncp)
        assertEquals("channel 15", h.waitFor(ZigbeeRouterHost.State.JOINED).detail)
        assertTrue(ncp.up)
    }

    @Test
    fun coordinatorNetworkIsNeitherRunNorErased() {
        val ncp = FakeNcp(nvm = "coordinator")
        val h = start(ncp)
        h.waitFor(ZigbeeRouterHost.State.HOLDS_COORDINATOR)
        Thread.sleep(200)
        assertEquals("coordinator", ncp.nvm)
        assertFalse("reset after networkInit takes the coordinator network down again", ncp.up)
        assertFalse(Ezsp.LEAVE_NETWORK in ncp.commands)
    }

    @Test
    fun joinPicksTheStrongestJoinableNetwork() {
        val ncp = FakeNcp(nvm = "coordinator", beacons = listOf(beacon(false, -40, epid = 1), beacon(true, -70, 11, 2), beacon(true, -55, 25, 3)))
        val h = start(ncp)
        h.waitFor(ZigbeeRouterHost.State.HOLDS_COORDINATOR)
        h.requestJoin()
        h.waitFor(ZigbeeRouterHost.State.JOINED, 15_000)
        assertEquals("router", ncp.nvm)
        val i = ncp.commands.indexOf(Ezsp.SET_INITIAL_SECURITY_STATE)
        assertTrue(i >= 0 && i < ncp.commands.indexOf(Ezsp.START_SCAN) && ncp.commands.indexOf(Ezsp.START_SCAN) < ncp.commands.indexOf(Ezsp.JOIN_NETWORK))
        assertTrue(Ezsp.UPDATE_TC_LINK_KEY in ncp.commands)
    }

    @Test
    fun noJoinableNetworkStaysReady() {
        val ncp = FakeNcp(nvm = null, beacons = listOf(beacon(false, -40)))
        val h = start(ncp)
        h.waitFor(ZigbeeRouterHost.State.NOT_JOINED)
        h.requestJoin()
        val until = System.currentTimeMillis() + 5_000
        while (!h.snapshot.detail.contains("no Zigbee network allows joining") && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(ZigbeeRouterHost.State.NOT_JOINED, h.snapshot.state)
        assertTrue(h.snapshot.detail, h.snapshot.detail.contains("no Zigbee network allows joining right now (1 seen)"))
        assertFalse(Ezsp.JOIN_NETWORK in ncp.commands)
    }

    @Test
    fun dryRunNeverJoins() {
        val ncp = FakeNcp(nvm = null, beacons = listOf(beacon(true, -40)))
        val h = start(ncp, dryRun = true)
        h.waitFor(ZigbeeRouterHost.State.NOT_JOINED)
        h.requestJoin()
        val until = System.currentTimeMillis() + 5_000
        while (!h.snapshot.detail.startsWith("dry run") && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(h.snapshot.detail, h.snapshot.detail.startsWith("dry run: would join"))
        assertFalse(Ezsp.JOIN_NETWORK in ncp.commands)
    }

    @Test
    fun leaveErasesACoordinatorNetworkThatWasNotRunning() {
        val ncp = FakeNcp(nvm = "coordinator")
        val h = start(ncp)
        h.waitFor(ZigbeeRouterHost.State.HOLDS_COORDINATOR)
        h.requestLeave()
        h.waitFor(ZigbeeRouterHost.State.NOT_JOINED)
        assertEquals(null, ncp.nvm)
        assertTrue(Ezsp.LEAVE_NETWORK in ncp.commands)
    }

    @Test
    fun leaveErasesTheNetwork() {
        val ncp = FakeNcp(nvm = "router")
        val seen = java.util.Collections.synchronizedList(ArrayList<String>())
        val h = ZigbeeRouterHost(connect = { ncp.transport }, onStatus = { seen += it }).also { host = it; it.start() }
        h.waitFor(ZigbeeRouterHost.State.JOINED)
        h.requestLeave()
        h.waitFor(ZigbeeRouterHost.State.NOT_JOINED)
        val until = System.currentTimeMillis() + 2_000
        while (ncp.nvm != null && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(null, ncp.nvm)
        // the NETWORK_DOWN that an explicit leave causes is not reported as "rejoining"
        assertTrue(seen.toString(), seen.none { "rejoining" in it })
    }

    @Test
    fun answersZhasBasicClusterRead() {
        val ncp = FakeNcp(nvm = "router")
        val h = start(ncp)
        h.waitFor(ZigbeeRouterHost.State.JOINED)
        // ZHA reads manufacturer (0x0004), model (0x0005), power source (0x0007) on endpoint 1
        val zcl = byteArrayOf(0x00, 0x2A, 0x00, 0x04, 0x00, 0x05, 0x00, 0x07, 0x00)
        val w = Ezsp.Writer().u8(0)
        Ezsp.ApsFrame(0x0104, 0x0000, 1, 1, 0, 0, 7).write(w)
        ncp.callback(Ezsp.INCOMING_MESSAGE_HANDLER, w.u8(255).u8(-40).u16(0x0000).u8(0xFF).u8(0xFF).lv(zcl).build())
        val until = System.currentTimeMillis() + 5_000
        while (ncp.sent.isEmpty() && System.currentTimeMillis() < until) Thread.sleep(20)
        val p = ncp.sent.firstOrNull()
        assertNotNull("sendUnicast", p)
        val r = Ezsp.Reader(p!!)
        assertEquals(0, r.u8())            // direct
        assertEquals(0x0000, r.u16())      // to the coordinator
        val aps = Ezsp.ApsFrame.read(r)
        assertEquals(0x0000, aps.clusterId); assertEquals(1, aps.srcEp); assertEquals(1, aps.dstEp)
        r.u8()                              // tag
        val reply = r.lv()
        val text = String(reply, Charsets.ISO_8859_1)
        assertEquals(0x18, reply[0].toInt()); assertEquals(0x2A, reply[1].toInt()); assertEquals(0x01, reply[2].toInt())
        assertTrue(text.contains(ZigbeeRouterHost.MANUFACTURER)); assertTrue(text.contains(ZigbeeRouterHost.MODEL))
    }

    @Test
    fun zclBasicReplyMarksUnknownAttributesUnsupported() {
        val r = Zcl.basicReadReply(byteArrayOf(0x00, 0x05, 0x00, 0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()), "M", "X")!!
        // tsn 5, read attr response: 0x0000 ok uint8 3, 0xFFFE unsupported (0x86)
        assertEquals(listOf(0x18, 0x05, 0x01, 0x00, 0x00, 0x00, 0x20, 0x03, 0xFE, 0xFF, 0x86), r.map { it.toInt() and 0xFF })
        assertEquals(null, Zcl.basicReadReply(byteArrayOf(0x01, 0x05, 0x00), "M", "X")) // cluster-specific: not ours
    }
}
