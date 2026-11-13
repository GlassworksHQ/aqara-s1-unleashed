package sh.edm.s1kiosk.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * [HaThreadClient] against a scripted Home Assistant websocket that answers like
 * components/websocket_api (auth) and components/thread/websocket_api.py (2026.11).
 */
class HaThreadClientTest {

    /** A fake HA: answers each client message with [reply]. */
    class FakeHa(
        private val token: String = "AT",
        private val admin: Boolean = true,
        private val datasets: String = """[{"dataset_id":"A","network_name":"other","channel":11,"preferred":false},
            {"dataset_id":"B","network_name":"ha-thread-1234","channel":15,"preferred":true}]""",
        private val tlvs: Map<String, String> = mapOf("A" to "0e08aa", "B" to "0E080000000000010000000300000F"),
        private val unknownCommand: Boolean = false,
    ) : HaSocket {
        val sent = ArrayList<JSONObject>()
        private val inbox = ArrayDeque<String>().apply { add("""{"type":"auth_required","ha_version":"2026.11.0"}""") }
        var closed = false

        override fun send(text: String) {
            val m = JSONObject(text)
            sent += m
            when (m.optString("type")) {
                "auth" -> inbox.add(if (m.optString("access_token") == token) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"Invalid access"}""")
                else -> {
                    val id = m.getInt("id")
                    // An unrelated event first: the client must skip it.
                    inbox.add("""{"id":99,"type":"event","event":{}}""")
                    inbox.add(when {
                        unknownCommand -> """{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command","message":"Unknown command."}}"""
                        !admin -> """{"id":$id,"type":"result","success":false,"error":{"code":"unauthorized","message":"Unauthorized"}}"""
                        m.getString("type") == "thread/list_datasets" -> """{"id":$id,"type":"result","success":true,"result":{"datasets":$datasets}}"""
                        m.getString("type") == "thread/get_dataset_tlv" -> tlvs[m.getString("dataset_id")]
                            ?.let { """{"id":$id,"type":"result","success":true,"result":{"tlv":"$it"}}""" }
                            ?: """{"id":$id,"type":"result","success":false,"error":{"code":"not_found","message":"unknown dataset"}}"""
                        else -> """{"id":$id,"type":"result","success":false,"error":{"code":"unknown_command"}}"""
                    })
                }
            }
        }

        override fun receive(): String? = inbox.removeFirstOrNull()
        override fun close() { closed = true }
    }

    private fun client(ha: HaSocket, urls: MutableList<String> = ArrayList()) = HaThreadClient { url -> urls += url; ha }

    @Test
    fun fetchesThePreferredDataset() {
        val ha = FakeHa()
        val urls = ArrayList<String>()
        val r = client(ha, urls).fetchPreferred("http://homeassistant.local:8123/lovelace/0", "AT")
        assertTrue(r.toString(), r is ThreadFetch.Ok)
        r as ThreadFetch.Ok
        assertEquals("0e080000000000010000000300000f", r.tlv) // lower-cased for the helper
        assertEquals("ha-thread-1234", r.networkName)
        assertEquals(15, r.channel)
        assertEquals(listOf("ws://homeassistant.local:8123/api/websocket"), urls)
        assertEquals(listOf("auth", "thread/list_datasets", "thread/get_dataset_tlv"), ha.sent.map { it.getString("type") })
        assertEquals("B", ha.sent[2].getString("dataset_id"))
        assertTrue(ha.closed)
        assertFalse("never prints the dataset", r.toString().contains(r.tlv))
    }

    @Test
    fun notAnAdministrator() {
        val ha = FakeHa(admin = false)
        assertEquals(ThreadFetch.NotAdmin, client(ha).fetchPreferred("http://ha:8123", "AT"))
        assertTrue(ha.closed)
    }

    @Test
    fun noPreferredNetwork() {
        val none = client(FakeHa(datasets = "[]")).fetchPreferred("http://ha:8123", "AT")
        assertEquals(0, (none as ThreadFetch.NoPreferred).known)
        val notPreferred = client(FakeHa(datasets = """[{"dataset_id":"A","network_name":"x","preferred":false}]"""))
            .fetchPreferred("http://ha:8123", "AT")
        assertEquals(1, (notPreferred as ThreadFetch.NoPreferred).known)
        // No Thread integration loaded, or HA before 2023.3: the command is unknown, and the words say what to do.
        val noThread = client(FakeHa(unknownCommand = true)).fetchPreferred("http://ha:8123", "AT")
        assertEquals(HaThreadClient.NO_THREAD_API, (noThread as ThreadFetch.Failed).reason)
    }

    @Test
    fun failures() {
        assertTrue(client(FakeHa(token = "other")).fetchPreferred("http://ha:8123", "AT") is ThreadFetch.Failed)
        assertTrue(client(FakeHa(tlvs = emptyMap())).fetchPreferred("http://ha:8123", "AT") is ThreadFetch.Failed)
        assertTrue(client(FakeHa(tlvs = mapOf("B" to "not-hex"))).fetchPreferred("http://ha:8123", "AT") is ThreadFetch.Failed)
        val unreachable = HaThreadClient { throw IOException("connect timed out") }.fetchPreferred("http://ha:8123", "AT")
        assertTrue((unreachable as ThreadFetch.Failed).reason.contains("connect timed out"))
        val closes = object : HaSocket {
            override fun send(text: String) {}
            override fun receive(): String? = null
            override fun close() {}
        }
        assertTrue(client(closes).fetchPreferred("http://ha:8123", "AT") is ThreadFetch.Failed)
        assertTrue(client(FakeHa()).fetchPreferred("ftp://ha", "AT") is ThreadFetch.Failed)
    }

    @Test
    fun websocketUrls() {
        assertEquals("ws://192.168.1.10:8123/api/websocket", HaThreadClient.websocketUrl("http://192.168.1.10:8123/"))
        assertEquals("wss://ha.example.com/api/websocket", HaThreadClient.websocketUrl("https://ha.example.com/dashboard-x/0"))
        assertNull(HaThreadClient.websocketUrl("homeassistant.local"))
    }
}
