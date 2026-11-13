package sh.edm.s1kiosk.manage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import sh.edm.s1kiosk.auth.HaSocket
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.UrlConnectionPoster
import java.util.ArrayDeque

/**
 * `auth/current_user` over HA's websocket, as components/auth/__init__.py `websocket_current_user`
 * answers it: `{id, name, is_owner, is_admin, credentials, mfa_modules}`.
 */
class ManageGatewayTest {
    private class FakeHa(private val authOk: Boolean, private val result: JSONObject?) : HaSocket {
        val sent = ArrayList<JSONObject>()
        private val out = ArrayDeque<String>().apply { add("""{"type":"auth_required","ha_version":"2026.11.0"}""") }
        override fun send(text: String) {
            val m = JSONObject(text).also { sent += it }
            when (m.optString("type")) {
                "auth" -> out.add(if (authOk) """{"type":"auth_ok"}""" else """{"type":"auth_invalid","message":"Invalid access token"}""")
                "auth/current_user" -> out.add(
                    if (result != null) JSONObject().put("id", m.getInt("id")).put("type", "result").put("success", true).put("result", result).toString()
                    else JSONObject().put("id", m.getInt("id")).put("type", "result").put("success", false).toString(),
                )
            }
        }
        override fun receive(): String? = out.poll()
        override fun close() {}
    }

    private fun gateway(ha: FakeHa) = S1Manage.Gateway(HaTokenClient(UrlConnectionPoster())) { ha }

    @Test fun anAdministrator() {
        val ha = FakeHa(true, JSONObject().put("id", "u1").put("name", "Alex").put("is_owner", false).put("is_admin", true))
        val u = gateway(ha).currentUser("http://192.0.2.10:8123", "AT")!!
        assertEquals("Alex", u.name)
        assertEquals(true, u.isAdmin)
        assertEquals("AT", ha.sent.first().getString("access_token"))
    }

    @Test fun anOrdinaryUser() {
        val u = gateway(FakeHa(true, JSONObject().put("name", "Sam").put("is_admin", false))).currentUser("http://192.0.2.10:8123", "AT")!!
        assertEquals(false, u.isAdmin)
    }

    @Test fun nothingWhenHaRefusesTheToken() {
        assertNull(gateway(FakeHa(false, null)).currentUser("http://192.0.2.10:8123", "AT"))
        assertNull(gateway(FakeHa(true, null)).currentUser("http://192.0.2.10:8123", "AT"))
    }
}
