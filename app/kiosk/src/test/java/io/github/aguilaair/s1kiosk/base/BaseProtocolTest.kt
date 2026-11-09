package io.github.aguilaair.s1kiosk.base

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseProtocolTest {
    private val did = "lumi.0123456789abcdef"

    @Test
    fun dockClassDecoding() {
        assertEquals(BaseKind.NONE, BaseProtocol.decodeDockClass(null).first)
        assertEquals(BaseKind.NONE, BaseProtocol.decodeDockClass(0).first)
        assertEquals(BaseKind.NONE, BaseProtocol.decodeDockClass(-1).first)
        assertEquals(BaseKind.NONE, BaseProtocol.decodeDockClass(0x00FFFFFF).first)
        // Values used by the stock settings app (ResCommon.SWITCH_DOCK / ETH_DOCK).
        assertEquals(BaseKind.SWITCH, BaseProtocol.decodeDockClass(16838928).first)
        assertEquals(BaseKind.ETHERNET, BaseProtocol.decodeDockClass(268435457).first)
        assertEquals(BaseKind.UNKNOWN to 5, BaseProtocol.decodeDockClass(0x05000000))
    }

    @Test
    fun channelsFromMaskAndCount() {
        assertEquals(listOf(1, 2, 3), BaseProtocol.channels(7, 2))
        assertEquals(listOf(1, 2), BaseProtocol.channels(3, null))
        assertEquals(listOf(1, 3), BaseProtocol.channels(5, 3))
        assertEquals(listOf(1, 2), BaseProtocol.channels(0, 2))
        assertEquals(listOf(1, 2), BaseProtocol.channels(null, 2))
        assertEquals(listOf(1, 2, 3), BaseProtocol.channels(null, 8))
        assertEquals(listOf(1, 2), BaseProtocol.channels(0b1000, 2)) // no usable mask bit -> count
        assertEquals(emptyList<Int>(), BaseProtocol.channels(null, null))
        assertEquals(emptyList<Int>(), BaseProtocol.channels(-0, 0))
    }

    @Test
    fun layoutDescribeAndPersist() {
        assertEquals("none", BaseLayout.NONE.describe())
        assertEquals("unavailable", BaseLayout.UNAVAILABLE.describe())
        assertEquals("switch (2ch)", BaseLayout(BaseKind.SWITCH, listOf(1, 2)).describe())
        assertEquals("ethernet", BaseLayout(BaseKind.ETHERNET).describe())
        assertEquals("unknown (0x05)", BaseLayout(BaseKind.UNKNOWN, rawClass = 5).describe())
        for (l in listOf(BaseLayout.NONE, BaseLayout(BaseKind.SWITCH, listOf(1, 3)), BaseLayout(BaseKind.UNKNOWN, rawClass = 9))) {
            assertEquals(l, BaseLayout.decode(l.encode()))
        }
        assertNull(BaseLayout.decode(""))
        assertNull(BaseLayout.decode("BOGUS:1"))
        assertEquals(BaseLayout(BaseKind.SWITCH, listOf(1, 2)), BaseLayout.decode("SWITCH:2,1,9,x"))
    }

    @Test
    fun requests() {
        val r = JSONObject(BaseProtocol.readRequest(did, listOf(BaseRes.DOCK_CLASS)))
        assertEquals(did, r.getString("did"))
        assertEquals("14.160.85", r.getString("res"))
        val a = JSONObject(BaseProtocol.readRequest(did, listOf("4.1.85", "4.2.85")))
        assertEquals("[\"4.1.85\",\"4.2.85\"]", a.getJSONArray("res").toString())
        val w = JSONObject(BaseProtocol.writeRequest(did, "4.1.85", 1, 1_700_000_000_000))
        assertEquals("3,,1700000000000,,,", w.getString("src"))
        assertEquals(1, w.getInt("value"))
        assertEquals("4.1.85", w.getString("res"))
    }

    @Test
    fun parsesReadReply() {
        val p = BaseProtocol.parse("""{"did":"$did","result":{"14.160.85":0},"id":12}""", did)!!
        assertFalse(p.foreign)
        assertEquals(mapOf<String, Any>("14.160.85" to 0), p.values)
        val multi = BaseProtocol.parse("""{"did":"$did","result":{"4.1.85":1,"4.2.85":"0","0.12.85":12.5,"8.0.2022":"1.0.3"}}""", did)!!
        assertEquals(1, BaseProtocol.asInt(multi.values["4.1.85"]))
        assertEquals(0, BaseProtocol.asInt(multi.values["4.2.85"]))
        assertEquals(12.5f, BaseProtocol.asFloat(multi.values["0.12.85"]))
        assertEquals("1.0.3", BaseProtocol.asText(multi.values["8.0.2022"]))
    }

    @Test
    fun parsesOtherShapes() {
        val resValue = BaseProtocol.parse("""{"did":"$did","result":{"res":"4.1.85","value":1}}""", did)!!
        assertEquals(mapOf<String, Any>("4.1.85" to 1), resValue.values)
        val event = BaseProtocol.parse("""{"did":"$did","action":"report","res":"13.2.85","value":"2"}""", did)!!
        assertEquals("report", event.action)
        assertEquals(mapOf<String, Any>("13.2.85" to "2"), event.values)
        val list = BaseProtocol.parse(
            """{"action":"heart_beat","did":"$did","res_list":[{"res_name":"3.7.85","value":1},{"res_name":"0.12.85","value":3.5}]}""", did)!!
        assertEquals(setOf("3.7.85", "0.12.85"), list.values.keys)
        assertEquals(1, BaseProtocol.asInt(list.values["3.7.85"]))
        assertEquals(3.5f, BaseProtocol.asFloat(list.values["0.12.85"])) // BigDecimal on the JVM, Double on Android
        val params = BaseProtocol.parse("""{"cmd":"report","params":[{"res_name":"4.2.85","value":0}]}""", did)!!
        assertEquals(mapOf<String, Any>("4.2.85" to 0), params.values)
    }

    @Test
    fun foreignDidIgnored() {
        val p = BaseProtocol.parse("""{"did":"lumi.zigbee1234","action":"report","res":"13.1.85","value":1}""", did)!!
        assertTrue(p.foreign)
        assertTrue(p.values.isEmpty())
    }

    @Test
    fun garbageIsSafe() {
        assertNull(BaseProtocol.parse(null, did))
        assertNull(BaseProtocol.parse("", did))
        assertNull(BaseProtocol.parse("not json", did))
        assertNull(BaseProtocol.parse("[1,2]", did))
        assertEquals(emptyMap<String, Any>(), BaseProtocol.parse("""{"result":0,"code":-1}""", did)!!.values)
        assertEquals(emptyMap<String, Any>(), BaseProtocol.parse("""{"result":{"14.160.85":null}}""", did)!!.values)
    }

    @Test
    fun clickTypes() {
        assertEquals("single", BaseProtocol.clickType(1))
        assertEquals("double", BaseProtocol.clickType("2"))
        assertEquals("long", BaseProtocol.clickType(0))
        assertEquals("long", BaseProtocol.clickType(16))
        assertEquals("release", BaseProtocol.clickType(255))
        assertEquals("unknown", BaseProtocol.clickType(42))
        assertEquals("double", BaseProtocol.clickType("double_click"))
        assertTrue(BaseProtocol.EVENT_TYPES.containsAll(listOf("single", "double", "triple", "long", "release", "unknown")))
    }

    @Test
    fun protectionText() {
        assertNull(BaseProtocol.protectionText(null))
        assertEquals("normal", BaseProtocol.protectionText(0))
        assertEquals("over_load", BaseProtocol.protectionText(2))
        assertEquals("over_load_removed", BaseProtocol.protectionText(6))
        assertEquals("unknown (9)", BaseProtocol.protectionText(9))
    }

    @Test
    fun redactsDid() {
        val s = """{"did":"$did","x":"0123456789abcdef"}"""
        val r = BaseProtocol.redact(s, did)
        assertFalse(r.contains("0123456789abcdef"))
        assertEquals("""{"did":"<did>","x":"<did>"}""", r)
        assertEquals(s, BaseProtocol.redact(s, null))
    }
}
