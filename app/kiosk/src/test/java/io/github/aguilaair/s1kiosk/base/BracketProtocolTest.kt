package io.github.aguilaair.s1kiosk.base

import io.github.aguilaair.s1kiosk.base.BracketProtocol.Msg
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors: frames written by Aqara's own `/vendor/bin/bracket_cli` (panel -> base) and the
 * emulator replies it accepted, from the transcripts in docs/base-protocol.md.
 */
class BracketProtocolTest {

    private fun h(s: String) = s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
    private fun res(n: String) = BracketProtocol.res(n)!!

    @Test
    fun crc16IsModbus() {
        assertEquals(0x4B37, BracketProtocol.crc16("123456789".toByteArray()))
        // bracket_cli "read 200.1.159", seq 0x4b: CRC over bytes 0..10, low byte first.
        val f = h("55 80 02 4b 01 00 03 da 00 00 80 23 5a")
        val crc = BracketProtocol.crc16(f, 0, 11)
        assertEquals(0x23, crc and 0xFF)
        assertEquals(0x5a, crc ushr 8)
    }

    @Test
    fun headerChecksum() {
        assertEquals(0xda, BracketProtocol.headerChecksum(h("55 80 02 4b 01 00 03")))
        assertEquals(0x66, BracketProtocol.headerChecksum(h("55 80 02 bf 03 00 01")))
    }

    @Test
    fun readFramesMatchBracketCli() {
        assertArrayEquals(h("55 80 02 4b 01 00 03 da 00 00 80 23 5a"), BracketProtocol.encodeRead(0x4b, res("200.1.159")))
        assertArrayEquals(h("55 80 02 bf 01 00 03 66 00 01 80 09 ed"), BracketProtocol.encodeRead(0xbf, res("8.0.2022")))
        assertArrayEquals(h("55 80 02 bf 01 00 03 66 01 00 55 98 22"), BracketProtocol.encodeRead(0xbf, res("4.1.85")))
        assertArrayEquals(h("55 80 02 c2 01 00 03 63 06 02 55 ee f2"), BracketProtocol.encodeRead(0xc2, res("0.12.85")))
    }

    @Test
    fun writeFramesMatchBracketCli() {
        // u8 (relay): 14 bytes
        assertArrayEquals(h("55 80 02 bf 02 00 04 64 01 00 55 01 da 08"), BracketProtocol.encodeWrite(0xbf, res("4.1.85"), 1))
        // float BE: 17 bytes
        assertArrayEquals(h("55 80 02 c2 02 00 07 5e 06 07 55 44 e1 10 00 22 81"), BracketProtocol.encodeWrite(0xc2, res("200.4.3"), 1800.5f))
        assertArrayEquals(h("55 80 02 31 02 00 07 ef 06 06 55 42 91 00 00 a2 7e"), BracketProtocol.encodeWrite(0x31, res("200.4.2"), 72.5f))
    }

    @Test
    fun resourceIds() {
        fun id(n: String) = res(n).let { listOf(it.cls, it.idx, it.dev) }
        assertEquals(listOf(0x01, 0x00, 0x55), id("4.1.85"))
        assertEquals(listOf(0x01, 0x02, 0x55), id("4.3.85"))
        assertEquals(listOf(0x00, 0x00, 0x80), id("200.1.159"))
        assertEquals(listOf(0x06, 0x02, 0x55), id("0.12.85"))
        assertEquals(listOf(0x05, 0x05, 0x55), id("4.103.85"))
        assertEquals(BracketProtocol.ValueType.U8, res("4.2.85").type)
        assertEquals(BracketProtocol.ValueType.U32, res("8.0.2022").type)
        assertEquals(BracketProtocol.ValueType.F32, res("0.12.85").type)
        // Host-side resources are not on the wire.
        assertNull(BracketProtocol.res("14.160.85"))
        assertNull(BracketProtocol.res("13.8.85"))
        assertNull(BracketProtocol.res("13.1.85"))
        assertEquals("4.2.85", BracketProtocol.res(0x01, 0x01, 0x55)!!.name)
        assertEquals("4.2.85", BracketProtocol.res(0x01, 0x01, 0x00)!!.name) // dev ignored like msg_parse
    }

    @Test
    fun parsesResponsesAndReports() {
        val fr = BracketProtocol.Framer()
        val frames = fr.feed(
            h("55 80 02 4b 04 00 07 d3 00 00 80 f1 10 00 02 7a 09") +   // read rsp 200.1.159
                h("55 80 02 bf 03 00 01 66 00 c6 ac") +                 // write ack ok
                h("55 80 02 21 05 00 04 ff 01 00 55 01 1e 54") +        // report 4.1.85=1
                h("55 80 02 c2 04 00 07 5c 06 02 55 42 70 00 00 e1 20"), // read rsp 0.12.85=60.0
        )
        assertEquals(4, frames.size)
        val m = frames.map(BracketProtocol::decode)
        val id = m[0] as Msg.ReadRsp
        assertEquals(0x4b, id.seq)
        assertEquals("200.1.159", id.res!!.name)
        assertEquals(0xF1100002L, id.value)
        assertEquals(Msg.WriteAck(0xbf, 0), m[1])
        val rep = m[2] as Msg.Report
        assertEquals("4.1.85", rep.res!!.name)
        assertEquals(1, rep.value)
        assertEquals(60.0f, (m[3] as Msg.ReadRsp).value)
        assertEquals(0, fr.junkBytes)
    }

    @Test
    fun framerResyncsAndRejectsBadCrc() {
        val fr = BracketProtocol.Framer()
        val good = h("55 80 02 21 05 00 04 ff 01 00 55 01 1e 54")
        val bad = good.copyOf().also { it[13] = (it[13].toInt() xor 1).toByte() }
        val swapped = good.copyOf().also { it[12] = good[13]; it[13] = good[12] } // CRC high byte first
        val stream = h("00 55 13 37") + bad + swapped + h("55 80") + good
        // Byte by byte, as a slow UART would deliver it.
        val out = ArrayList<BracketProtocol.Frame>()
        for (b in stream) out += fr.feed(byteArrayOf(b))
        assertEquals(1, out.size)
        assertArrayEquals(good, out[0].raw)
        assertEquals(2, fr.badCrc)
        assertTrue(fr.junkBytes > 0)
    }

    @Test
    fun identityAndDockClass() {
        val id = BracketProtocol.Identity(0xF1100002L)
        assertEquals(0xF110, id.model)
        assertEquals(2, id.channels)
        assertTrue(id.supported)
        assertEquals(0x0100F110L, id.dockClass) // = 16838928, what ha_master reports as 14.160.85
        assertEquals(3, id.relayMask)
        assertEquals(BaseKind.SWITCH, BaseProtocol.decodeDockClass(id.dockClass.toInt()).first)
        assertEquals(7, BracketProtocol.Identity(0xF1100003L).relayMask)
    }

    @Test
    fun protectionMapping() {
        assertEquals(1, BracketProtocol.protection("200.4.5", 1))
        assertEquals(2, BracketProtocol.protection("200.4.6", 1))
        assertEquals(3, BracketProtocol.protection("200.4.5", 2))
        assertEquals(4, BracketProtocol.protection("200.4.6", 2))
        assertEquals(5, BracketProtocol.protection("200.4.5", 0))
        assertEquals(6, BracketProtocol.protection("200.4.6", 0))
        assertNull(BracketProtocol.protection("4.1.85", 1))
        assertEquals("over_load", BaseProtocol.protectionText(BracketProtocol.protection("200.4.6", 1)))
    }

    @Test
    fun termiosVerification() {
        // /dev/ttyS3 as ha_master left it (busybox stty -g, read only) and after our busybox stty on a pty.
        assertNull(UartBaseLink.verifyTermios("1:0:18b2:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"))
        assertNull(UartBaseLink.verifyTermios("1:0:18b2:0:3:1c:7f:15:4:1:0:0:11:13:1a:0:12:f:17:16:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0:0"))
        // What toybox 0.8.0 stty left on the pty: ixon/icrnl, crtscts, canonical echo.
        val bad = UartBaseLink.verifyTermios("c:5:800000bd:8a3b:3:1c:7f:15:4:0:1:0:11:13:1a:0:12:f:17:16:0:0")!!
        assertTrue(bad, "crtscts" in bad && "iflag" in bad && "lflag" in bad && "opost" in bad && "speed" in bad)
        assertTrue(UartBaseLink.verifyTermios("1:0:18f2:0")!!.contains("parity"))
        assertTrue(UartBaseLink.verifyTermios("garbage")!!.startsWith("unexpected"))
    }

    @Test
    fun backendResolution() {
        assertEquals(BaseBackend.UART, BaseBackend.resolve("auto", blockAqaraCloud = true))
        assertEquals(BaseBackend.LOCALSERVICE, BaseBackend.resolve("auto", blockAqaraCloud = false))
        assertEquals(BaseBackend.UART, BaseBackend.resolve("uart", blockAqaraCloud = false))
        assertEquals(BaseBackend.LOCALSERVICE, BaseBackend.resolve("localservice", blockAqaraCloud = true))
        assertEquals(BaseBackend.UART, BaseBackend.resolve(null, blockAqaraCloud = true))
        assertEquals("127.0.0.1" to 9999, UartBaseLink.parseTcp("tcp:127.0.0.1:9999"))
        assertNull(UartBaseLink.parseTcp("/dev/ttyS3"))
        assertNull(UartBaseLink.parseTcp("tcp:host"))
    }
}
