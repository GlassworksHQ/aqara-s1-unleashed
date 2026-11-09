package io.github.aguilaair.s1kiosk.voice

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class WyomingCodecTest {

    @Test
    fun roundTripWithDataAndPayload() {
        val payload = ByteArray(2560) { it.toByte() }
        val ev = WyomingEvent(
            "audio-chunk",
            JSONObject().put("rate", 16000).put("width", 2).put("channels", 1).put("timestamp", 80),
            payload,
        )
        val bytes = WyomingCodec.encode(ev)
        val nl = bytes.indexOf('\n'.code.toByte())
        val header = JSONObject(String(bytes, 0, nl, Charsets.UTF_8))
        assertEquals("audio-chunk", header.getString("type"))
        assertEquals(2560, header.getInt("payload_length"))
        assertEquals(false, header.has("data")) // current format: data in its own block
        val dataLen = header.getInt("data_length")
        assertEquals(bytes.size, nl + 1 + dataLen + 2560)

        val back = WyomingCodec.read(ByteArrayInputStream(bytes))!!
        assertEquals("audio-chunk", back.type)
        assertEquals(16000, back.data.getInt("rate"))
        assertEquals(80, back.data.getInt("timestamp"))
        assertArrayEquals(payload, back.payload)
    }

    @Test
    fun emptyDataHasNoDataLength() {
        val bytes = WyomingCodec.encode(WyomingEvent("describe"))
        val header = JSONObject(String(bytes, Charsets.UTF_8).trim())
        assertEquals("describe", header.getString("type"))
        assertEquals(false, header.has("data_length"))
        assertEquals(false, header.has("payload_length"))
    }

    @Test
    fun readsInlineDataAndMergesDataBlock() {
        val block = """{"text":"hello","code":"x"}""".toByteArray()
        val stream = (
            """{"type":"ping","data":{"text":"inline"}}""" + "\n" +
                """{"type":"error","data":{"text":"old","keep":1},"data_length":${block.size}}""" + "\n"
            ).toByteArray() + block
        val input = ByteArrayInputStream(stream)
        val a = WyomingCodec.read(input)!!
        assertEquals("ping", a.type)
        assertEquals("inline", a.data.getString("text"))
        val b = WyomingCodec.read(input)!!
        assertEquals("error", b.type)
        assertEquals("hello", b.data.getString("text")) // block overrides inline, like dict.update
        assertEquals(1, b.data.getInt("keep"))
        assertNull(b.payload)
        assertNull(WyomingCodec.read(input))
    }

    @Test
    fun utf8DataLengthIsInBytes() {
        val ev = WyomingEvent("transcript", JSONObject().put("text", "¿Qué hora es? 時間"))
        val back = WyomingCodec.read(ByteArrayInputStream(WyomingCodec.encode(ev)))!!
        assertEquals("¿Qué hora es? 時間", back.data.getString("text"))
    }
}
