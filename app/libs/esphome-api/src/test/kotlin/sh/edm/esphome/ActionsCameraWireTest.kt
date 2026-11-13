package sh.edm.esphome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format of actions and cameras. The golden bytes come from aioesphomeapi 46.25.0's own
 * protobuf classes (`src/test/resources/esphome_golden.py` prints them).
 */
class ActionsCameraWireTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private val allTypes = ApiAction(
        "all_types",
        args = listOf(
            ActionArg("b", ActionArgType.BOOL), ActionArg("i", ActionArgType.INT),
            ActionArg("f", ActionArgType.FLOAT), ActionArg("s", ActionArgType.STRING),
            ActionArg("bs", ActionArgType.BOOL_ARRAY), ActionArg("is", ActionArgType.INT_ARRAY),
            ActionArg("fs", ActionArgType.FLOAT_ARRAY), ActionArg("ss", ActionArgType.STRING_ARRAY),
        ),
        supportsResponse = ActionResponse.OPTIONAL,
    ) { """{"ok":true}""" }

    @Test
    fun listEntitiesServicesMatchesTheClient() {
        val a = ApiAction(
            "notify",
            args = listOf(
                ActionArg("title", ActionArgType.STRING), ActionArg("flag", ActionArgType.BOOL),
                ActionArg("levels", ActionArgType.INT_ARRAY),
            ),
            supportsResponse = ActionResponse.OPTIONAL,
        ) { null }
        assertEquals(Msg.LIST_ENTITIES_SERVICES, a.listType)
        assertEquals(
            "0a066e6f74696679153c7e6da01a090a057469746c6510031a060a04666c61671a0a0a066c6576656c7310052001",
            a.info().toByteArray().hex(),
        )
    }

    @Test
    fun executeServiceRequestDecodesEveryArgumentType() {
        val call = allTypes.decode(ProtoMessage(hex(
            "0d78563412120208011202285312051d0000c03f1208220668c3a96c6c6f120630013000300112073802380338d804" +
                "120a450000003f45000000c012084a01614a004a016318072001",
        )))
        assertEquals(true, call.bool("b"))
        assertEquals(-42, call.int("i"))
        assertEquals(1.5f, call.float("f"))
        assertEquals("héllo", call.string("s"))
        assertEquals(listOf(true, false, true), call.bools("bs"))
        assertEquals(listOf(1, -2, 300), call.ints("is"))
        assertEquals(listOf(0.5f, -2f), call.floats("fs"))
        assertEquals(listOf("a", "", "c"), call.strings("ss"))
        assertTrue(call.returnResponse)
    }

    @Test
    fun legacyIntFromApiOlderThan13() {
        val a = ApiAction("n", args = listOf(ActionArg("i", ActionArgType.INT))) { null }
        assertEquals(-5, a.decode(ProtoMessage(hex("0d78563412120b10fbffffffffffffffff01"))).int("i"))
    }

    @Test
    fun packedArraysDecodeToo() {
        // bool_array [1,0], int_array zigzag [2,3] (1,-2) and float_array [0.5] as packed fields
        val arg = ProtoWriter().bytes(6, byteArrayOf(1, 0)).bytes(7, byteArrayOf(2, 3))
            .bytes(8, hex("0000003f"))
        val a = ApiAction(
            "p",
            args = listOf(
                ActionArg("bs", ActionArgType.BOOL_ARRAY), ActionArg("is", ActionArgType.INT_ARRAY),
                ActionArg("fs", ActionArgType.FLOAT_ARRAY),
            ),
        ) { null }
        val call = a.decode(ProtoMessage(ProtoWriter().message(2, arg).message(2, arg).message(2, arg).toByteArray()))
        assertEquals(listOf(true, false), call.bools("bs"))
        assertEquals(listOf(1, -2), call.ints("is"))
        assertEquals(listOf(0.5f), call.floats("fs"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongArgumentCountIsRefused() {
        allTypes.decode(ProtoMessage(hex("0d78563412120208011801")))
    }

    @Test
    fun responsesCarryTheJsonOnlyWhenAskedFor() {
        val wanted = ActionCall(allTypes, emptyMap(), returnResponse = true)
        assertEquals("0807100122" + "0b" + """{"ok":true}""".toByteArray().hex(), allTypes.execute(wanted, 7).toByteArray().hex())
        val statusOnly = ActionCall(allTypes, emptyMap(), returnResponse = false)
        assertEquals("08071001", allTypes.execute(statusOnly, 7).toByteArray().hex())

        val status = ApiAction("s", supportsResponse = ActionResponse.STATUS) { """{"x":1}""" }
        assertEquals("08071001", status.execute(ActionCall(status, emptyMap(), returnResponse = true), 7).toByteArray().hex())
    }

    @Test
    fun aThrowingHandlerFailsTheCallWithItsMessage() {
        val a = ApiAction("boom", supportsResponse = ActionResponse.STATUS) { throw IllegalStateException("no") }
        assertEquals("08071a026e6f", a.execute(ActionCall(a, emptyMap(), false), 7).toByteArray().hex())
    }

    @Test(expected = IllegalArgumentException::class)
    fun actionNamesAreSnakeCase() {
        ApiAction("Ring Bell") { null }
    }

    @Test
    fun listEntitiesCameraMatchesTheClient() {
        val cam = CameraEntity("porch", "Porch", icon = "mdi:cctv", category = EntityCategory.DIAGNOSTIC) { null }
        assertEquals(Msg.LIST_ENTITIES_CAMERA, cam.listType)
        assertEquals("0a05706f72636815a5fb38d81a05506f72636832086d64693a636374763802", cam.info().toByteArray().hex())
    }

    @Test
    fun aSmallImageIsOneDoneChunk() {
        val cam = CameraEntity("porch", "Porch") { null }
        val r = cam.responses(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
        assertEquals(listOf("0da5fb38d81203ffd8ff1801"), r.map { it.hex() })
    }

    @Test
    fun noImageIsAnEmptyDoneChunk() {
        val cam = CameraEntity("porch", "Porch") { null }
        assertEquals(listOf("0da5fb38d81801"), cam.responses(null).map { it.hex() })
        assertEquals(listOf("0da5fb38d81801"), cam.responses(ByteArray(0)).map { it.hex() })
    }

    @Test
    fun aBigImageIsChunkedAndOnlyTheLastIsDone() {
        val jpeg = ByteArray(2 * CameraEntity.CHUNK_BYTES + 100) { it.toByte() }
        val cam = CameraEntity("porch", "Porch") { jpeg }
        val chunks = cam.responses(cam.capture()).map(::ProtoMessage)
        assertEquals(3, chunks.size)
        assertEquals(listOf(false, false, true), chunks.map { it.bool(3) })
        assertTrue(chunks.all { it.fixed32(1) == cam.key })
        assertEquals(listOf(CameraEntity.CHUNK_BYTES, CameraEntity.CHUNK_BYTES, 100), chunks.map { it.bytes(2).size })
        assertArrayEquals(jpeg, chunks.fold(ByteArray(0)) { acc, m -> acc + m.bytes(2) })
    }

    @Test
    fun anImageTheClientWouldDropIsNotSent() {
        val cam = CameraEntity("porch", "Porch") { ByteArray(CameraEntity.MAX_FRAME_BYTES + 1) }
        assertEquals(null, cam.capture())
        val failing = CameraEntity("porch", "Porch") { throw IllegalStateException("camera busy") }
        assertEquals(null, failing.capture())
        assertFalse(cam.responses(cam.capture()).single().let(::ProtoMessage).has(2))
    }
}
