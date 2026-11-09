package io.github.aguilaair.s1kiosk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OAuthTest {

    @Test
    fun authorizeUrlMatchesHaExpectations() {
        val cid = OAuth.clientId("192.168.3.39:8099")
        val redirect = OAuth.redirectUri("192.168.3.39:8099")
        assertEquals("http://192.168.3.39:8099/", cid)
        assertEquals("http://192.168.3.39:8099/callback", redirect)
        val u = OAuth.authorizeUrl("http://192.168.1.100:8123/", cid, redirect, "st/+=")
        assertTrue(u.startsWith("http://192.168.1.100:8123/auth/authorize?response_type=code&"))
        val q = OAuth.parseQuery(URI(u).rawQuery)
        assertEquals("code", q["response_type"])
        assertEquals(cid, q["client_id"])
        assertEquals(redirect, q["redirect_uri"])
        assertEquals("st/+=", q["state"])
        // IndieAuth (indieauth.py verify_redirect_uri): same scheme + netloc, no fetch needed.
        val c = URI(cid); val r = URI(redirect)
        assertEquals(c.scheme, r.scheme)
        assertEquals(c.rawAuthority, r.rawAuthority)
    }

    @Test
    fun hostHeaderIsSanitized() {
        assertEquals("192.168.3.39:8099", OAuth.sanitizeHost("192.168.3.39:8099"))
        assertEquals("panel.lan:8099", OAuth.sanitizeHost("Panel.LAN:8099"))
        assertNull(OAuth.sanitizeHost(null))
        assertNull(OAuth.sanitizeHost("evil@host:1"))
        assertNull(OAuth.sanitizeHost("host/path"))
        assertNull(OAuth.sanitizeHost("host:8099#x"))
        assertNull(OAuth.sanitizeHost(""))
    }

    @Test
    fun origin() {
        assertEquals("http://192.168.1.100:8123", OAuth.origin("http://192.168.1.100:8123/s1-panel/home"))
        assertEquals("https://ha.example.com", OAuth.origin("https://HA.example.com/"))
        assertNull(OAuth.origin("ftp://x/"))
        assertNull(OAuth.origin("auto"))
    }

    @Test
    fun stateIsSingleUseAndExpires() {
        var now = 1_000L
        val s = StateStore(ttlMs = 600_000, max = 3, clock = { now })
        val a = s.create("cid", "redir", "http://ha")
        val b = s.create("cid", "redir", "http://ha")
        assertNotEquals(a, b)
        assertTrue(a.length >= 32)
        assertEquals("cid", s.consume(a)?.clientId)
        assertNull("single use", s.consume(a))
        assertNull(s.consume("forged"))
        assertNull(s.consume(null))
        now += 600_000
        assertNull("expired after 10 min", s.consume(b))
        // Bounded: the oldest outstanding state is dropped.
        val x = s.create("c", "r", "h"); s.create("c", "r", "h"); s.create("c", "r", "h"); s.create("c", "r", "h")
        assertNull(s.consume(x))
        assertEquals(3, s.size())
    }

    @Test
    fun tokenResponses() {
        // components/auth/__init__.py TokenView, authorization_code grant
        val ok = TokenResult.parse(200, """{"access_token":"AT","token_type":"Bearer","refresh_token":"RT","expires_in":1800,"ha_auth_provider":"homeassistant"}""")
        ok as TokenResult.Ok
        assertEquals("AT", ok.accessToken)
        assertEquals("RT", ok.refreshToken)
        assertEquals(1800L, ok.expiresIn)
        assertFalse("toString hides tokens", ok.toString().contains("AT") || ok.toString().contains("RT"))
        // refresh_token grant: no refresh_token in the reply
        val r = TokenResult.parse(200, """{"access_token":"AT2","token_type":"Bearer","expires_in":1800}""") as TokenResult.Ok
        assertNull(r.refreshToken)

        val code = TokenResult.parse(400, """{"error":"invalid_request","error_description":"Invalid code"}""") as TokenResult.Error
        assertTrue(code.isInvalidCode)
        assertFalse(code.isRevoked)
        val revoked = TokenResult.parse(400, """{"error":"invalid_grant"}""") as TokenResult.Error
        assertTrue(revoked.isRevoked)
        val denied = TokenResult.parse(403, """{"error":"access_denied","error_description":"User is not active"}""") as TokenResult.Error
        assertTrue(denied.isRevoked)
        val net = TokenResult.parse(-1, "connect timed out") as TokenResult.Error
        assertEquals("network_error", net.code)
        assertEquals("http_502", (TokenResult.parse(502, "<html>") as TokenResult.Error).code)
        assertEquals("invalid_response", (TokenResult.parse(200, "{}") as TokenResult.Error).code)
    }

    @Test
    fun accessTokenCache() {
        var now = 0L
        val c = AccessTokenCache(marginMs = 60_000, clock = { now })
        assertNull(c.get())
        c.put("AT", 1800)
        assertEquals("AT", c.get())
        assertEquals(1800L, c.secondsLeft())
        now = 1_739_000L
        assertEquals("AT", c.get())
        now = 1_741_000L // less than 60 s left
        assertNull(c.get())
        c.put("AT2", 1800); c.clear()
        assertNull(c.get())
    }

    @Test
    fun storedAuthToStringHidesTheToken() {
        assertFalse(StoredAuth("http://ha", "cid", "SECRET").toString().contains("SECRET"))
    }

    @Test
    fun formAndQuery() {
        assertEquals("grant_type=authorization_code&client_id=http%3A%2F%2F1.2.3.4%3A8099%2F",
            OAuth.form("grant_type" to "authorization_code", "client_id" to "http://1.2.3.4:8099/"))
        assertEquals(mapOf("code" to "a b", "state" to "x"), OAuth.parseQuery("code=a+b&state=x&state=y"))
        assertEquals(emptyMap<String, String>(), OAuth.parseQuery(null))
    }

    @Test
    fun externalAuthFlag() {
        assertEquals("http://h:8123/x?external_auth=1", FrontendUrls.withFlag("http://h:8123/x"))
        assertEquals("http://h:8123/x?a=1&external_auth=1#f", FrontendUrls.withFlag("http://h:8123/x?a=1#f"))
        assertEquals("http://h:8123/x?external_auth=1", FrontendUrls.withFlag("http://h:8123/x?external_auth=1"))
        assertTrue(FrontendUrls.hasFlag("http://h/?a=2&external_auth=1"))
        assertFalse(FrontendUrls.hasFlag("http://h/?external_auth=10"))
        val base = "http://192.168.1.100:8123"
        assertTrue(FrontendUrls.needsFlag("http://192.168.1.100:8123/s1-panel/home", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/s1-panel/home?external_auth=1", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/auth/authorize?x", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/api/camera_proxy/x", base))
        assertFalse(FrontendUrls.needsFlag("http://other:8123/", base))
        assertFalse(FrontendUrls.needsFlag("http://192.168.1.100:8123/", null))
        assertTrue(FrontendUrls.isAuthorizePage("http://192.168.1.100:8123/auth/authorize?response_type=code", base))
        assertFalse(FrontendUrls.isAuthorizePage("http://192.168.1.100:8123/lovelace", base))
    }
}
