package sh.edm.s1kiosk.auth

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.util.Collections

/**
 * The /signin → HA → /callback flow over real sockets against a fake Home Assistant that mimics
 * components/auth/__init__.py TokenView (form-encoded POST, single-use codes bound to client_id, and,
 * like HA 2026.10+, bound to the PKCE code_challenge the authorize request carried).
 */
class SignInServerTest {
    private lateinit var ha: HttpServer
    private lateinit var haBase: String
    private val codes = Collections.synchronizedMap(HashMap<String, String>()) // code -> client_id
    private val challenges = Collections.synchronizedMap(HashMap<String, String>()) // code -> S256 challenge
    private val tokenRequests = Collections.synchronizedList(ArrayList<Map<String, String>>())
    private lateinit var server: SignInServer
    private var signedIn: Pair<StoredAuth, TokenResult.Ok>? = null
    @Volatile private var haKnown = true
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val revoked = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        ha = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        ha.createContext("/auth/token") { ex ->
            val form = OAuth.parseQuery(ex.requestBody.readBytes().toString(Charsets.UTF_8))
            tokenRequests += form
            val ct = ex.requestHeaders.getFirst("Content-Type")
            val (status, body) = when {
                ex.requestMethod != "POST" || ct != "application/x-www-form-urlencoded" -> 400 to """{"error":"invalid_request"}"""
                form["grant_type"] == "authorization_code" -> {
                    val cid = codes.remove(form["code"])
                    val challenge = challenges.remove(form["code"])
                    val verifier = form["code_verifier"]
                    when {
                        cid == null || cid != form["client_id"] -> 400 to """{"error":"invalid_request","error_description":"Invalid code"}"""
                        challenge != null && verifier == null ->
                            400 to """{"error":"invalid_request","error_description":"Code verifier required"}"""
                        challenge != null && OAuth.codeChallenge(verifier!!) != challenge ->
                            400 to """{"error":"invalid_grant","error_description":"Invalid code verifier"}"""
                        else -> 200 to """{"access_token":"AT","token_type":"Bearer","refresh_token":"RT","expires_in":1800,"ha_auth_provider":"homeassistant"}"""
                    }
                }
                form["grant_type"] == "refresh_token" ->
                    if (form["refresh_token"] == "RT" && form["client_id"] == signedIn?.first?.clientId)
                        200 to """{"access_token":"AT2","token_type":"Bearer","expires_in":1800}"""
                    else 400 to """{"error":"invalid_grant"}"""
                else -> 400 to """{"error":"unsupported_grant_type"}"""
            }
            val b = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(status, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        ha.createContext("/auth/revoke") { ex ->
            revoked += OAuth.parseQuery(ex.requestBody.readBytes().toString(Charsets.UTF_8))["token"].orEmpty()
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        ha.start()
        haBase = "http://127.0.0.1:${ha.address.port}"
        server = signInServer()
        server.start()
    }

    private fun signInServer() = SignInServer(0, { if (haKnown) "$haBase/" else null }, HaTokenClient(UrlConnectionPoster()),
        { auth, ok -> signedIn = auth to ok }, bindAddress = loopback)

    @After
    fun tearDown() {
        server.stop()
        ha.stop(0)
    }

    /** HA issues [code] after the user signs in; it is bound to the authorize request's client_id and challenge. */
    private fun issueCode(code: String, authorize: Map<String, String>) {
        codes[code] = authorize["client_id"]!!
        authorize["code_challenge"]?.let { challenges[code] = it }
    }

    private fun get(path: String, host: String? = null): Triple<Int, String?, String> {
        val c = URL("http://127.0.0.1:${server.localPort}$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        if (host != null) c.setRequestProperty("Host", host)
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Triple(code, c.getHeaderField("Location"), body)
    }

    @Test
    fun fullFlow() {
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true")
        // The QR code's page says what signing in is for, then Continue goes to Home Assistant (#63).
        val (landing, _, page) = get("/signin")
        assertEquals(200, landing)
        assertTrue(page, page.contains("Sign in this panel.") && page.contains("href=\"${SignInServer.CONTINUE}\""))
        assertTrue(page, page.contains("never sees your password"))
        val (code, location, _) = get(SignInServer.CONTINUE)
        assertEquals(302, code)
        assertNotNull(location)
        val loc = URI(location!!)
        assertEquals("$haBase/auth/authorize", "${loc.scheme}://${loc.rawAuthority}${loc.rawPath}")
        val q = OAuth.parseQuery(loc.rawQuery)
        val panel = "127.0.0.1:${server.localPort}"
        assertEquals("code", q["response_type"])
        assertEquals("http://$panel/", q["client_id"])
        assertEquals("http://$panel/callback", q["redirect_uri"])
        val state = q["state"]!!

        // The user signs in on the phone; HA stores a code for this client_id and the frontend
        // redirects to <redirect_uri>?code=..&state=.. (data/auth.ts redirectWithAuthCode).
        issueCode("c0de", q)
        val (cb, _, body) = get("/callback?code=c0de&state=${OAuth.enc(state)}&storeToken=true")
        assertEquals(200, cb)
        assertTrue(body.contains("Panel signed in"))
        assertTrue(body.contains("#0a0b0f"))
        val (auth, ok) = signedIn!!
        assertEquals("RT", auth.refreshToken)
        assertEquals("http://$panel/", auth.clientId)
        assertEquals(haBase, auth.haBase)
        assertEquals("AT", ok.accessToken)
        val req = tokenRequests.single()
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(43, q["code_challenge"]!!.length)
        assertEquals(mapOf("grant_type" to "authorization_code", "code" to "c0de", "client_id" to "http://$panel/"),
            req - "code_verifier")
        assertEquals("the verifier hashes to the challenge from the redirect", q["code_challenge"], OAuth.codeChallenge(req["code_verifier"]!!))
        assertTrue("the verifier never goes to the phone", !location.contains(req["code_verifier"]!!))
        assertTrue("the page never shows tokens", !body.contains("RT") && !body.contains("\"AT\""))

        // Reusing the state fails gracefully.
        val (again, _, againBody) = get("/callback?code=c0de&state=${OAuth.enc(state)}")
        assertEquals(400, again)
        assertTrue(againBody.contains("expired"))

        // Refresh with the stored token and client_id.
        val r = HaTokenClient(UrlConnectionPoster()).refresh(auth) as TokenResult.Ok
        assertEquals("AT2", r.accessToken)
    }

    /**
     * #99: other processes on the machine listen on 127.0.0.1 ports too (Gradle test servers, dev
     * servers). On macOS a wildcard listener on port 0 can get one of their ports, and a request to
     * 127.0.0.1 then reaches them (a 200 instead of the 302). Holding the next ports on 127.0.0.1
     * makes that happen every time there; a server on 127.0.0.1 never gets a held port.
     */
    @Test
    fun aLoopbackListenerOnTheNextPortsDoesNotAnswerForTheServer() {
        // Pick on 127.0.0.1 too, and start low enough that 20 ports above it exist.
        val next = ServerSocket(0, 0, loopback).use { it.localPort }.coerceAtMost(65535 - 21)
        val squatters = (next + 1..next + 20).mapNotNull { p ->
            try {
                HttpServer.create(InetSocketAddress(loopback, p), 0).apply {
                    createContext("/") { ex -> ex.sendResponseHeaders(200, -1); ex.close() }
                    start()
                }
            } catch (_: java.io.IOException) { null }
        }
        // Without held ports the test would pass without reproducing #99.
        assertTrue("held ${squatters.size} of 20 loopback ports", squatters.size >= 15)
        val s = signInServer()
        try {
            s.start()
            val c = URL("http://127.0.0.1:${s.localPort}${SignInServer.CONTINUE}").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            assertEquals("the sign-in server on port ${s.localPort} answers /signin with its redirect", 302, c.responseCode)
        } finally {
            s.stop()
            squatters.forEach { it.stop(0) }
        }
    }

    @Test
    fun stolenCodeIsUselessWithoutTheVerifier() {
        val (_, location, _) = get(SignInServer.CONTINUE)
        val q = OAuth.parseQuery(URI(location!!).rawQuery)
        val http = HaTokenClient(UrlConnectionPoster())

        // Someone who read the callback URL has the code and the client_id, but not the verifier.
        issueCode("stolen1", q)
        val wrong = http.exchangeCode(haBase, q["client_id"]!!, "stolen1", OAuth.newCodeVerifier()) as TokenResult.Error
        assertEquals("invalid_grant", wrong.code)
        assertEquals("Invalid code verifier", wrong.description)

        issueCode("stolen2", q)
        val form = OAuth.form("grant_type" to "authorization_code", "code" to "stolen2", "client_id" to q["client_id"]!!)
        val (status, body) = UrlConnectionPoster().postForm("$haBase/auth/token", form)
        val none = TokenResult.parse(status, body) as TokenResult.Error
        assertEquals("invalid_request", none.code)
        assertEquals("Code verifier required", none.description)

        // A code issued for another sign-in's challenge fails at the panel's callback: no sign-in.
        val other = OAuth.codeChallenge(OAuth.newCodeVerifier())
        issueCode("stolen3", q + ("code_challenge" to other))
        val (cb, _, page) = get("/callback?code=stolen3&state=${OAuth.enc(q["state"]!!)}")
        assertEquals(502, cb)
        assertTrue(page, page.contains("refused the sign-in (invalid_grant)"))
        assertNull(signedIn)
    }

    @Test
    fun expiredOrUsedCode() {
        val (_, location, _) = get(SignInServer.CONTINUE)
        val state = OAuth.parseQuery(URI(location!!).rawQuery)["state"]!!
        val (cb, _, body) = get("/callback?code=unknown&state=${OAuth.enc(state)}")
        assertEquals(400, cb)
        assertTrue(body.contains("expired"))
        assertNull(signedIn)
    }

    @Test
    fun badRequests() {
        assertEquals(400, get("/callback?code=x&state=forged").first)
        assertEquals(400, get("/callback?code=x").first)
        val (_, location, _) = get(SignInServer.CONTINUE)
        val state = OAuth.parseQuery(URI(location!!).rawQuery)["state"]!!
        assertEquals(400, get("/callback?state=${OAuth.enc(state)}").first) // no code
        assertEquals(400, get("/callback?error=access_denied").first)
        assertEquals(404, get("/nope").first)
        assertEquals(200, get("/").first)
        assertNull(signedIn)
    }

    @Test
    fun haNotFound() {
        haKnown = false
        val (code, location, body) = get("/signin")
        assertEquals(503, code)
        assertNull(location)
        assertTrue(body.contains("Home Assistant not found"))
    }

    @Test
    fun haUnreachableAtCallback() {
        val (_, location, _) = get(SignInServer.CONTINUE)
        val state = OAuth.parseQuery(URI(location!!).rawQuery)["state"]!!
        ha.stop(0)
        val (cb, _, body) = get("/callback?code=c&state=${OAuth.enc(state)}")
        assertEquals(502, cb)
        assertTrue(body.contains("reach Home Assistant"))
    }

    @Test
    fun handlerRejectsBadHost() {
        val r = server.handle(SignInServer.Request("GET", SignInServer.CONTINUE, emptyMap(), mapOf("host" to "a@b")))
        assertEquals(400, r.status)
        val ok = server.handle(SignInServer.Request("GET", SignInServer.CONTINUE, emptyMap(), mapOf("host" to "192.0.2.39:8099")))
        assertEquals(302, ok.status)
        val q = OAuth.parseQuery(URI(ok.headers["Location"]).rawQuery)
        assertEquals("http://192.0.2.39:8099/", q["client_id"])
        assertEquals("http://192.0.2.39:8099/callback", q["redirect_uri"])
    }

    @Test
    fun tokenRequestIsFormEncoded() {
        JSONObject() // org.json available in tests
        val r = HaTokenClient(UrlConnectionPoster()).exchangeCode(haBase, "http://x/", "nope", OAuth.newCodeVerifier())
        assertTrue((r as TokenResult.Error).isInvalidCode)
    }

    // ------------------------------------------------------------------ /thread (ThreadJoin)

    private fun threadServer(delivered: MutableList<ThreadFetch.Ok>): Pair<SignInServer, ThreadJoin> {
        val tokens = HaTokenClient(UrlConnectionPoster())
        // The fake HA's websocket accepts the access token the code exchange returns ("AT").
        val join = ThreadJoin(HaThreadClient { HaThreadClientTest.FakeHa(token = "AT") }, tokens,
            { haBase }, { done -> done(null) }, { it.run() })
        join.deliver = { r -> delivered += r; null }
        val s = SignInServer(0, { "$haBase/" }, tokens, { _, _ -> error("not the panel sign-in") }, thread = join, bindAddress = loopback)
        s.start()
        return s to join
    }

    private fun get(s: SignInServer, path: String): Triple<Int, String?, String> {
        val c = URL("http://127.0.0.1:${s.localPort}$path").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        val code = c.responseCode
        val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return Triple(code, c.getHeaderField("Location"), body)
    }

    @Test
    fun threadJoinFlow() {
        val delivered = ArrayList<ThreadFetch.Ok>()
        val (s, join) = threadServer(delivered)
        try {
            // Not armed: the panel isn't showing its QR code.
            assertEquals(409, get(s, ThreadJoin.PATH).first)

            join.arm()
            // The QR code's page says who must sign in and why it's safe, then Continue (#63).
            val (landing, _, page) = get(s, ThreadJoin.PATH)
            assertEquals(200, landing)
            assertTrue(page, page.contains("administrator") && page.contains("href=\"${SignInServer.THREAD_CONTINUE}\""))
            val (code, location, _) = get(s, SignInServer.THREAD_CONTINUE)
            assertEquals(302, code)
            val q = OAuth.parseQuery(URI(location!!).rawQuery)
            val panel = "127.0.0.1:${s.localPort}"
            assertEquals("http://$panel/", q["client_id"])
            assertEquals("http://$panel/thread/callback", q["redirect_uri"])
            val state = q["state"]!!

            // A /thread state is no good at the panel's own /callback (and stays usable).
            assertEquals(400, get(s, "/callback?code=x&state=${OAuth.enc(state)}").first)

            assertEquals("S256", q["code_challenge_method"])
            issueCode("adm1n", q)
            val (cb, _, body) = get(s, "${ThreadJoin.CALLBACK}?code=adm1n&state=${OAuth.enc(state)}")
            assertEquals(200, cb)
            assertTrue(body, body.contains("Joining ha-thread-1234"))
            assertEquals("0e080000000000010000000300000f", delivered.single().tlv)
            assertTrue("the page never shows the dataset or tokens", !body.contains(delivered.single().tlv) && !body.contains("RT"))
            assertEquals("the one-off sign-in is revoked", listOf("RT"), revoked.toList())
            assertTrue(join.state is ThreadJoin.State.Joined)

            // Used once.
            assertEquals(400, get(s, "${ThreadJoin.CALLBACK}?code=adm1n&state=${OAuth.enc(state)}").first)
        } finally {
            s.stop()
        }
    }

    @Test
    fun signinStateIsNoGoodForThread() {
        val (s, join) = threadServer(ArrayList())
        try {
            val (_, location, _) = get(s, SignInServer.CONTINUE)
            val state = OAuth.parseQuery(URI(location!!).rawQuery)["state"]!!
            join.arm()
            assertEquals(400, get(s, "${ThreadJoin.CALLBACK}?code=x&state=${OAuth.enc(state)}").first)
            assertEquals(400, get(s, "${ThreadJoin.CALLBACK}?error=access_denied").first)
            assertTrue(revoked.isEmpty())
        } finally {
            s.stop()
        }
        // The panel's server without ThreadJoin doesn't serve /thread at all.
        assertEquals(404, get(ThreadJoin.PATH).first)
    }

    @Test
    fun pagesAreSelfContainedAndFollowTheDevicesTheme() {
        val r = server.handle(SignInServer.Request("GET", "/nope", emptyMap(), emptyMap()))
        val html = r.body.toString(Charsets.UTF_8)
        assertTrue(html.contains("prefers-color-scheme:light")) // dark and light follow the device
        assertTrue(html.contains("<svg")) // the mark is inline
        assertTrue(html.contains("Glassworks Panel"))
        for (external in listOf("<script", "<link", "src=", "@import", "url(http")) assertTrue(external, !html.contains(external))
        // ... and the browser enforces it: only the page's own inline style, no sniffing, no referrer.
        assertEquals("default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'", r.headers["Content-Security-Policy"])
        assertEquals("nosniff", r.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", r.headers["Referrer-Policy"])
        // The same on a page that answers: the landing.
        val landing = server.handle(SignInServer.Request("GET", "/", emptyMap(), emptyMap()))
        assertEquals(200, landing.status)
        for (h in listOf("Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy")) assertEquals(h, r.headers[h], landing.headers[h])
    }
}
