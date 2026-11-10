package sh.edm.s1kiosk.sendspin

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsMessagesTest {

    private val info = SsHelloInfo(
        name = "S1 Panel", productName = "Panel Hub S1 Plus (S1 Kiosk)", manufacturer = "Aqara",
        softwareVersion = "S1 Kiosk 0.1.0", macAddress = "aa:bb:cc:dd:ee:ff",
        formats = listOf(SsFormat.PCM_48K_STEREO_16), bufferCapacity = 1_500_000,
    )

    @Test
    fun clientInit() {
        val o = JSONObject(SsMessages.clientInit("A".repeat(43)))
        assertEquals("client/init", o.getString("type"))
        val p = o.getJSONObject("payload")
        assertEquals(1, p.getInt("version"))
        assertEquals("25519_ChaChaPoly_SHA256", p.getString("suite"))
        assertEquals(setOf("client_id", "version", "suite"), p.keyNames())
    }

    /** The current client/hello shape: what MA 2.10.9 warned the old web client about must not appear. */
    @Test
    fun clientHelloIsCurrentSpec() {
        val o = JSONObject(SsMessages.clientHello(info, unpairedAccess = false))
        assertEquals("client/hello", o.getString("type"))
        val p = o.getJSONObject("payload")
        assertEquals("S1 Panel", p.getString("name"))
        assertEquals(listOf("player@v1", "metadata@v1"), p.getJSONArray("supported_roles").toList())
        // supported_pair_methods is an object keyed by method, offering pairing_psk
        val methods = p.getJSONObject("supported_pair_methods")
        assertEquals(setOf("pairing_psk"), methods.keyNames())
        assertFalse(p.getJSONObject("unpaired_access").getBoolean("enabled"))
        assertFalse(p.has("trust_level"))
        val support = p.getJSONObject("player@v1_support")
        assertEquals(setOf("supported_formats", "buffer_capacity"), support.keyNames()) // no hello-level supported_commands
        val f = support.getJSONArray("supported_formats").getJSONObject(0)
        assertEquals("pcm", f.getString("codec"))
        assertEquals(48000, f.getInt("sample_rate"))
        assertEquals(2, f.getInt("channels"))
        assertEquals(16, f.getInt("bit_depth"))
        val d = p.getJSONObject("device_info")
        assertEquals("aa:bb:cc:dd:ee:ff", d.getString("mac_address"))
        assertEquals("Aqara", d.getString("manufacturer"))
        assertEquals(setOf("name", "device_info", "supported_roles", "player@v1_support", "supported_pair_methods", "unpaired_access"), p.keyNames())
    }

    /** The default hello: FLAC preferred (first), PCM as the fallback, same rate and channels. */
    @Test
    fun clientHelloOffersFlacThenPcm() {
        val o = JSONObject(SsMessages.clientHello(info.copy(formats = listOf(SsFormat.FLAC_48K_STEREO_16, SsFormat.PCM_48K_STEREO_16)), unpairedAccess = false))
        val f = o.getJSONObject("payload").getJSONObject("player@v1_support").getJSONArray("supported_formats")
        assertEquals(2, f.length())
        assertEquals("flac", f.getJSONObject(0).getString("codec"))
        assertEquals("pcm", f.getJSONObject(1).getString("codec"))
        for (i in 0..1) {
            val e = f.getJSONObject(i)
            assertEquals(setOf("codec", "channels", "sample_rate", "bit_depth"), e.keyNames())
            assertEquals(48000, e.getInt("sample_rate")); assertEquals(2, e.getInt("channels")); assertEquals(16, e.getInt("bit_depth"))
        }
    }

    @Test
    fun streamStartCodecHeader() {
        val h = FlacTest.hex(FlacTest.AIOSENDSPIN_HEADER)
        val b64 = java.util.Base64.getEncoder().encodeToString(h) // standard alphabet, padded
        assertArrayEquals(h, SsMessages.codecHeader(JSONObject().put("codec", "flac").put("codec_header", b64)))
        assertNull(SsMessages.codecHeader(JSONObject().put("codec", "pcm")))
        assertNull(SsMessages.codecHeader(JSONObject().put("codec_header", JSONObject.NULL)))
        assertNull(SsMessages.codecHeader(JSONObject().put("codec_header", "not base64!")))
        assertNull(SsMessages.codecHeader(JSONObject().put("codec_header", 5)))
        assertEquals(SsFormat.FLAC_48K_STEREO_16, SsFormat.fromJson(JSONObject().put("codec", "flac").put("channels", 2).put("sample_rate", 48000).put("bit_depth", 16).put("codec_header", b64)))
    }

    @Test
    fun clientStateCarriesEveryRequiredField() {
        val o = JSONObject(SsMessages.clientState(true, SsPlayerState(42, true, 120, 300, 200)))
        val p = o.getJSONObject("payload")
        assertTrue(p.getBoolean("available"))
        val pl = p.getJSONObject("player")
        assertEquals(42, pl.getInt("volume"))
        assertTrue(pl.getBoolean("muted"))
        assertEquals(120, pl.getInt("output_delay_ms"))
        assertEquals(300, pl.getInt("required_lead_time_ms"))
        assertEquals(200, pl.getInt("min_buffer_ms"))
        assertEquals(listOf("volume", "mute", "set_output_delay"), pl.getJSONArray("supported_commands").toList())
        assertFalse(pl.has("static_delay_ms"))
        assertFalse(pl.has("state"))
    }

    @Test
    fun pairingMessages() {
        val init = JSONObject(SsMessages.pairInit(1))
        assertEquals("client/pair-init", init.getString("type"))
        assertEquals(1, init.getJSONObject("payload").getInt("pairing_index"))
        val psk = ByteArray(32) { it.toByte() }
        val fin = JSONObject(SsMessages.pairFinalize(psk)).getJSONObject("payload")
        assertEquals(43, fin.getString("long_term_psk").length)
        assertArrayEquals(psk, SsCrypto.unb64url(fin.getString("long_term_psk")))
        assertEquals(setOf("long_term_psk"), fin.keyNames())
    }

    @Test
    fun parseRejectsMalformedEnvelopes() {
        assertNull(SsMessages.parse("[]"))
        assertNull(SsMessages.parse("{\"type\":1,\"payload\":{}}"))
        assertNull(SsMessages.parse("{\"type\":\"x\"}"))
        assertNull(SsMessages.parse("not json"))
        assertEquals("server/time", SsMessages.parse("{\"type\":\"server/time\",\"payload\":{\"a\":1},\"extra\":2}")!!.type)
    }

    @Test
    fun audioHeaderRoundTrip() {
        val audio = byteArrayOf(1, 2, 3, 4)
        val msg = SsMessages.audioMessage(0x0102030405060708L, 0xFFFFFFFFL, audio)
        assertEquals(17, msg.size)
        assertEquals(4, msg[0].toInt())
        val c = SsMessages.parseAudio(msg)!!
        assertEquals(0x0102030405060708L, c.timestampUs)
        assertEquals(0xFFFFFFFFL, c.sendAheadUs)
        assertArrayEquals(audio, c.data.copyOfRange(c.dataOffset, c.data.size))
        assertNull(SsMessages.parseAudio(msg.copyOf(12)))
        // negative (pre-epoch) server times are signed int64
        assertEquals(-5L, SsMessages.parseAudio(SsMessages.audioMessage(-5L, 0, ByteArray(0)))!!.timestampUs)
    }

    @Test
    fun fragmentReassembly() {
        val r = SsReassembler()
        assertArrayEquals(byteArrayOf(4, 9), r.accept(byteArrayOf(4, 9)))
        assertNull(r.accept(byteArrayOf(1, 0x02, 4, 1, 2)))
        assertNull(r.accept(byteArrayOf(1, 0x00, 3)))
        assertArrayEquals(byteArrayOf(4, 1, 2, 3, 5), r.accept(byteArrayOf(1, 0x01, 5)))
        // a single-frame "first and last"
        assertArrayEquals(byteArrayOf(0, 7), r.accept(byteArrayOf(1, 0x03, 0, 7)))
    }

    @Test
    fun malformedFragmentSequencesAreProtocolErrors() {
        fun bad(vararg frames: ByteArray) {
            val r = SsReassembler()
            try {
                frames.forEach { r.accept(it) }
                throw AssertionError("expected a protocol error")
            } catch (_: SsProtocolException) {}
        }
        bad(byteArrayOf(1, 0x00, 1))                                  // continuation without start
        bad(byteArrayOf(1, 0x02, 4), byteArrayOf(1, 0x02, 4))         // second first
        bad(byteArrayOf(1, 0x02, 4), byteArrayOf(4, 0))               // plain message mid-sequence
        bad(byteArrayOf(1, 0x06, 4))                                  // reserved bit
        bad(byteArrayOf(1, 0x02, 1))                                  // orig_type 1
        bad(byteArrayOf(1))                                           // no flags
        bad(byteArrayOf(1, 0x02))                                     // no orig_type
        bad(ByteArray(0))                                             // empty plaintext
    }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private fun org.json.JSONArray.toList() = (0 until length()).map { getString(it) }
}
