package sh.edm.s1kiosk.improv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.improv.ImprovSerial.RPC_REQUEST_INFO
import sh.edm.s1kiosk.improv.ImprovSerial.RPC_REQUEST_STATE
import sh.edm.s1kiosk.improv.ImprovSerial.RPC_SCAN_WIFI
import sh.edm.s1kiosk.improv.ImprovSerial.RPC_WIFI_SETTINGS
import sh.edm.s1kiosk.improv.ImprovSerial.TYPE_ERROR
import sh.edm.s1kiosk.improv.ImprovSerial.TYPE_RPC
import sh.edm.s1kiosk.improv.ImprovSerial.TYPE_RPC_RESULT
import sh.edm.s1kiosk.improv.ImprovSerial.TYPE_STATE
import sh.edm.s1kiosk.improv.SerialPacketReader.Item

/** Byte examples and the simulated panel's behaviour from web/test/improv.test.ts. */
class ImprovSerialTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun framesExactlyAsTheSpec() {
        val sum = (0x49 + 0x4d + 0x50 + 0x52 + 0x4f + 0x56 + 1 + 3 + 2 + 2) and 0xff
        assertEquals(
            listOf(0x49, 0x4d, 0x50, 0x52, 0x4f, 0x56, 1, 0x03, 2, 0x02, 0, sum),
            ImprovSerial.rpc(RPC_REQUEST_STATE).map { it.toInt() and 0xff },
        )
        val w = ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("Net", "pw"))
        assertEquals(
            listOf(0x01, 7, 3, 0x4e, 0x65, 0x74, 2, 0x70, 0x77),
            w.copyOfRange(9, 9 + w[8]).map { it.toInt() and 0xff },
        )
        assertEquals(SerialRpc.WifiSettings("Net", "pw"), ImprovSerial.parseRpc(w.copyOfRange(9, 9 + w[8])))
    }

    @Test
    fun readerResyncsAfterGarbageAndACorruptPacket() {
        val good = ImprovSerial.state(ImprovSerial.STATE_READY)
        val bad = ImprovSerial.state(ImprovSerial.STATE_PROVISIONED).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xff).toByte() }
        val r = SerialPacketReader()
        val got = r.push(bytes(1, 2, 3)) + r.push(bad) + r.push(good.copyOfRange(0, 4)) + r.push(good.copyOfRange(4, good.size))
        assertEquals(listOf(Item.Packet(TYPE_STATE, bytes(ImprovSerial.STATE_READY))), got.filterIsInstance<Item.Packet>())
        assertEquals(1, got.filterIsInstance<Item.Bad>().size)
    }

    @Test
    fun parsesRpcPayloads() {
        assertEquals(SerialRpc.RequestState, ImprovSerial.parseRpc(bytes(2, 0)))
        assertEquals(SerialRpc.RequestInfo, ImprovSerial.parseRpc(bytes(3, 0)))
        assertEquals(SerialRpc.ScanWifi, ImprovSerial.parseRpc(bytes(4, 0)))
        assertEquals(SerialRpc.Unknown(9), ImprovSerial.parseRpc(bytes(9, 0)))
        assertTrue(ImprovSerial.parseRpc(bytes(1, 5, 3, 0x4e)) is SerialRpc.Invalid) // length mismatch
        assertTrue(ImprovSerial.parseRpc(bytes(1, 2, 3, 0x4e)) is SerialRpc.Invalid) // string overruns
        assertTrue(ImprovSerial.parseRpc(bytes(1, 4, 3, 0x4e, 0x65, 0x74)) is SerialRpc.Invalid) // one string
        assertTrue(ImprovSerial.parseRpc(bytes(1)) is SerialRpc.Invalid)
        assertEquals("WifiSettings(ssid=Net, password=<6 chars>)", SerialRpc.WifiSettings("Net", "secret").toString())
    }

    @Test
    fun scanResultSequence() {
        val packets = ImprovSerial.scanResults(listOf(
            SerialNetwork("Home Net", -61, true),
            SerialNetwork("Cafe", -70, false),
            SerialNetwork("Home Net", -48, true),
            SerialNetwork("", -40, true), // hidden
            SerialNetwork("x".repeat(33), -30, false), // not a valid SSID
        ))
        assertEquals(
            listOf(listOf("Home Net", "-48", "YES"), listOf("Cafe", "-70", "NO"), emptyList()),
            packets.map { resultStrings(it, RPC_SCAN_WIFI) },
        )
        // An empty scan is just the terminator: [4, 0].
        val end = ImprovSerial.scanResults(emptyList()).single()
        assertEquals(listOf(4, 0), end.copyOfRange(9, 9 + end[8]).map { it.toInt() })
    }

    // ---- session against the client's requests

    private class Fake(var online: Boolean = false, var password: String = "right") : ImprovSerialSession.Effects {
        val sent = mutableListOf<Item.Packet>()
        private val reader = SerialPacketReader()
        var connects = mutableListOf<Pair<String, (String?) -> Unit>>()
        var autoAnswer = true
        var scanned = listOf(SerialNetwork("Home Net", -61, true), SerialNetwork("Cafe", -70, false), SerialNetwork("Home Net", -48, true))

        override fun send(packet: ByteArray) { reader.push(packet).forEach { sent += it as Item.Packet } }
        override fun online() = online
        override fun redirectUrl() = if (online) "http://192.168.1.50:8099/signin" else ""
        override fun info() = ImprovSerial.info("S1 Kiosk", "0.1.0", "Allwinner A133", "S1 Panel")
        override fun scan() = scanned
        override fun connect(ssid: String, password: String, done: (String?) -> Unit) {
            connects += ssid to done
            if (autoAnswer) done(if (password == this.password) "http://192.168.1.50:8099/signin" else null)
        }
    }

    private fun resultStrings(p: ByteArray, cmd: Int): List<String> {
        val item = SerialPacketReader().push(p).single() as Item.Packet
        assertEquals(TYPE_RPC_RESULT, item.type)
        assertEquals(cmd, item.data[0].toInt())
        return resultStrings(item)
    }

    private fun resultStrings(item: Item.Packet): List<String> {
        val out = mutableListOf<String>()
        var o = 2
        while (o < 2 + (item.data[1].toInt() and 0xff)) {
            val n = item.data[o].toInt() and 0xff
            out += String(item.data, o + 1, n, Charsets.UTF_8)
            o += 1 + n
        }
        return out
    }

    private fun describe(p: Item.Packet): String = when (p.type) {
        TYPE_STATE -> "state ${p.data[0]}"
        TYPE_ERROR -> "error ${p.data[0]}"
        TYPE_RPC_RESULT -> "result ${p.data[0]} ${resultStrings(p)}"
        else -> "type ${p.type}"
    }

    @Test
    fun stateInfoScan() {
        val fx = Fake()
        val s = ImprovSerialSession(fx)
        s.onBytes(ImprovSerial.rpc(RPC_REQUEST_STATE))
        s.onBytes(ImprovSerial.rpc(RPC_REQUEST_INFO))
        s.onBytes(ImprovSerial.rpc(RPC_SCAN_WIFI))
        assertEquals(listOf(
            "state 2",
            "result 3 [S1 Kiosk, 0.1.0, Allwinner A133, S1 Panel]",
            "result 4 [Home Net, -48, YES]",
            "result 4 [Cafe, -70, NO]",
            "result 4 []",
        ), fx.sent.map(::describe))
    }

    @Test
    fun alreadyOnWifiStartsProvisionedWithTheUrl() {
        val fx = Fake(online = true)
        ImprovSerialSession(fx).onBytes(ImprovSerial.rpc(RPC_REQUEST_STATE))
        assertEquals(listOf("state 4", "result 2 [http://192.168.1.50:8099/signin]"), fx.sent.map(::describe))
    }

    @Test
    fun provisionsOrReportsUnableToConnect() {
        val fx = Fake()
        val s = ImprovSerialSession(fx)
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("Home Net", "right")))
        assertEquals(listOf("state 3", "state 4", "result 1 [http://192.168.1.50:8099/signin]"), fx.sent.map(::describe))
        fx.sent.clear()
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("Home Net", "wrong")))
        assertEquals(listOf("state 3", "error 3", "state 2"), fx.sent.map(::describe))
        assertEquals(ImprovSerial.STATE_READY, s.state)
    }

    @Test
    fun anEmptyRedirectMeansNoUrlString() {
        val fx = Fake().apply { autoAnswer = false }
        val s = ImprovSerialSession(fx)
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("Open", "")))
        fx.connects.single().second("")
        assertEquals(listOf("state 3", "state 4", "result 1 []"), fx.sent.map(::describe))
    }

    @Test
    fun onlyTheLatestAttemptAnswers() {
        val fx = Fake().apply { autoAnswer = false }
        val s = ImprovSerialSession(fx)
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("A", "pw")))
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("B", "pw")))
        fx.connects[0].second(null) // superseded
        fx.connects[1].second("http://10.0.0.2:8099/signin")
        assertEquals(listOf("state 3", "state 3", "state 4", "result 1 [http://10.0.0.2:8099/signin]"), fx.sent.map(::describe))
    }

    @Test
    fun malformedAndUnknown() {
        val fx = Fake()
        val s = ImprovSerialSession(fx)
        val corrupt = ImprovSerial.rpc(RPC_REQUEST_STATE).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        s.onBytes(corrupt)
        s.onBytes(ImprovSerial.frame(TYPE_RPC, bytes(1, 9, 3, 0x4e))) // inner length wrong
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("only ssid")))
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("", "pw")))
        s.onBytes(ImprovSerial.rpc(RPC_WIFI_SETTINGS, listOf("x".repeat(33), "pw")))
        s.onBytes(ImprovSerial.frame(TYPE_STATE, bytes(2))) // not an RPC
        s.onBytes(ImprovSerial.rpc(0x09))
        assertEquals(List(6) { "error 1" } + "error 2", fx.sent.map(::describe))
        assertTrue(fx.connects.isEmpty())
        // Still works afterwards.
        s.onBytes(ImprovSerial.rpc(RPC_REQUEST_STATE))
        assertEquals("state 2", describe(fx.sent.last()))
    }

    @Test
    fun reassemblesByteByByteWithGarbageBetween() {
        val fx = Fake()
        val s = ImprovSerialSession(fx)
        val stream = bytes(0, 0x49, 7) + ImprovSerial.rpc(RPC_REQUEST_INFO) + bytes(0x0a) + ImprovSerial.rpc(RPC_REQUEST_STATE)
        stream.forEach { s.onBytes(byteArrayOf(it)) }
        assertEquals(listOf("result 3 [S1 Kiosk, 0.1.0, Allwinner A133, S1 Panel]", "state 2"), fx.sent.map(::describe))
    }

    @Test
    fun infoFitsOnePacket() {
        val p = ImprovSerial.info("f".repeat(200), "v".repeat(200), "c".repeat(200), "n".repeat(200))
        assertTrue(p.size <= 9 + 255 + 1)
        assertEquals(4, resultStrings(p, RPC_REQUEST_INFO).size)
    }
}
