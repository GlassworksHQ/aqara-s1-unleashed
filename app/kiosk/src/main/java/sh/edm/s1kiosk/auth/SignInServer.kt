package sh.edm.s1kiosk.auth

import dev.glassworks.web.PageShell
import dev.glassworks.web.PageShell.Look
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Log facade: android.util.Log on the device, stdout in JVM tests. Never pass secrets. */
internal object AuthLog {
    private const val TAG = "S1Auth"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

/**
 * The panel's sign-in web server (plain HTTP on the LAN, default port 8099). The phone scans the QR
 * code for `http://<panel-ip>:8099/signin` and:
 *
 * 1. `GET /signin` → `302` to `<HA>/auth/authorize?response_type=code&client_id=http://<host>/&
 *    redirect_uri=http://<host>/callback&state=<random>&code_challenge=<S256>&
 *    code_challenge_method=S256`, `<host>` being the `Host` header the phone
 *    used (so client_id and redirect_uri always share scheme + netloc, which is what HA's IndieAuth
 *    `verify_redirect_uri` accepts without fetching anything).
 * 2. The user signs in to HA on the phone; HA's frontend sends the phone to
 *    `/callback?code=…&state=…`.
 * 3. `GET /callback` → the state is checked (single use, 10 min), the code is exchanged at
 *    `<HA>/auth/token` with the same client_id and the PKCE `code_verifier`, and [onSignedIn] gets
 *    the refresh token. The phone sees "Panel signed in ✓".
 *
 * `/thread` and `/thread/callback` are the same flow for [ThreadJoin]: an administrator signs in once
 * so the panel can read HA's Thread dataset; the tokens are revoked right after. `/thread` only works
 * while the panel shows its QR code ([ThreadJoin.armed]).
 *
 * Pure JVM (tested in SignInServerTest against a fake HA).
 */
class SignInServer(
    private val port: Int,
    /** HA base URL (`http://host:8123`), or null if not known yet. */
    private val haBase: () -> String?,
    private val tokens: HaTokenClient,
    /** Called with the new credentials (on the connection thread). */
    private val onSignedIn: (StoredAuth, TokenResult.Ok) -> Unit,
    private val states: StateStore = StateStore(),
    /** "Join Home Assistant's Thread network" (`/thread`), or null to not serve it. */
    private val thread: ThreadJoin? = null,
    /**
     * The address to listen on, or null for every address (the panel: phones reach it on the LAN).
     * Tests pass the loopback address they connect to. On macOS a wildcard listener can get a port
     * another process holds on 127.0.0.1, and that process then answers 127.0.0.1 (#99).
     */
    private val bindAddress: InetAddress? = null,
) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "signin-http").apply { isDaemon = true } }
    private val ids = AtomicInteger()

    val localPort: Int get() = serverSocket?.localPort ?: -1

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        serverSocket = ss
        running = true
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: IOException) { if (running) AuthLog.w("accept failed", e); break }
                pool.execute { serve(s) }
            }
        }, "signin-accept").apply { isDaemon = true; start() }
        AuthLog.i("Sign-in server listening on ${ss.localPort}")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        pool.shutdownNow()
    }

    class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    class Response(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray)

    private fun serve(s: Socket) {
        val id = ids.incrementAndGet()
        s.use { sock ->
            try {
                sock.soTimeout = 15_000
                val req = readRequest(BufferedInputStream(sock.getInputStream())) ?: return
                val res = handle(req)
                AuthLog.i("[$id] ${req.method} ${req.path} -> ${res.status}")
                write(sock.getOutputStream(), res)
            } catch (e: IOException) {
                AuthLog.w("[$id] ${e.message}")
            }
        }
    }

    /** Routes one request. Public for tests. */
    fun handle(req: Request): Response {
        if (req.method != "GET" && req.method != "HEAD") return page(405, "Method Not Allowed", "Not allowed", "Only GET is supported.")
        return when (req.path) {
            "/", "", "/signin" -> landing()
            CONTINUE -> signin(req)
            "/callback" -> callback(req)
            ThreadJoin.PATH -> threadLanding()
            THREAD_CONTINUE -> threadStart(req)
            ThreadJoin.CALLBACK -> threadCallback(req)
            "/favicon.ico" -> Response(404, "Not Found", mapOf("Content-Type" to "text/plain"), ByteArray(0))
            else -> page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        }
    }

    /** What the QR code opens (#63): what signing in is for, and the one way on. */
    private fun landing(): Response {
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        return page(
            200, "OK", "Sign in this panel", "So it can show your Home Assistant dashboard.", Look.START,
            tile = haTile(ha), button = button("Continue to Home Assistant", CONTINUE),
            foot = "You sign in on Home Assistant's own page. The panel never sees your password.",
        )
    }

    private fun notFoundHa() = page(
        503, "Service Unavailable", "Home Assistant not found yet",
        "Enter its address on the panel, like <b>homeassistant.local</b>, then scan the code again.",
    )

    private fun signin(req: Request): Response {
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        val host = OAuth.sanitizeHost(req.headers["host"])
            ?: return page(400, "Bad Request", "Can't sign in", "Open this page with the panel's address (scan the QR code).")
        val clientId = OAuth.clientId(host)
        val redirect = OAuth.redirectUri(host)
        val sign = states.create(clientId, redirect, ha)
        val location = OAuth.authorizeUrl(ha, clientId, redirect, sign.state, sign.codeChallenge)
        return Response(
            302, "Found",
            mapOf("Location" to location, "Cache-Control" to "no-store", "Content-Type" to "text/plain; charset=utf-8"),
            "Redirecting to Home Assistant".toByteArray(),
        )
    }

    private fun callback(req: Request): Response {
        val q = req.query
        q["error"]?.let { err ->
            return page(400, "Bad Request", "Sign-in cancelled", "Home Assistant answered: ${esc(err)}. Scan the code to try again.")
        }
        val pending = states.consume(q["state"], PendingSignIn.PURPOSE_SIGNIN)
            ?: return expired()
        val code = q["code"]?.takeIf { it.isNotEmpty() }
            ?: return page(400, "Bad Request", "Can't sign in", "Home Assistant didn't send an authorization code. Scan the code to try again.")
        return when (val r = tokens.exchangeCode(pending.haBase, pending.clientId, code, pending.codeVerifier)) {
            is TokenResult.Ok -> {
                val rt = r.refreshToken
                    ?: return page(502, "Bad Gateway", "Can't sign in", "Home Assistant didn't return a refresh token.")
                try {
                    onSignedIn(StoredAuth(pending.haBase, pending.clientId, rt), r)
                } catch (t: Throwable) {
                    AuthLog.w("storing the token failed", t)
                    return page(500, "Internal Server Error", "Can't sign in", "The panel couldn't store the sign-in.")
                }
                AuthLog.i("Signed in to ${pending.haBase} (refresh token stored)")
                page(200, "OK", "Panel signed in", "It's loading your dashboard now. You can close this page.", Look.DONE,
                    tile = haTile(pending.haBase, dot = true), footHtml = MANAGE_FOOT)
            }
            is TokenResult.Error -> {
                AuthLog.w("code exchange failed: ${r.code} (${r.httpStatus})")
                when {
                    r.isInvalidCode -> expired()
                    r.httpStatus <= 0 -> page(502, "Bad Gateway", "Can't reach Home Assistant",
                        "The panel couldn't reach ${esc(pending.haBase)}. Check the network and scan the code again.")
                    else -> page(502, "Bad Gateway", "Can't sign in",
                        "Home Assistant refused the sign-in (${esc(r.code)}). Scan the code to try again.")
                }
            }
        }
    }

    /** What the Thread QR code opens (#63): who must sign in, and why it's safe. */
    private fun threadLanding(): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        if (!join.armed()) return notWaiting()
        haBase() ?: return notFoundHa()
        return page(
            200, "OK", "Share your Thread network", "The panel joins it as a Thread router.", Look.START,
            tile = infoTile("Sign in as a Home Assistant <b>administrator</b>. The panel uses it once to copy the network's settings, then signs out."),
            button = button("Continue to Home Assistant", THREAD_CONTINUE),
            foot = "Your network key is never shown or logged.",
        )
    }

    private fun notWaiting() = page(409, "Conflict", "Not waiting for a sign-in",
        "Start “Join Home Assistant's Thread network” on the panel first, then scan the code it shows.")

    private fun expired() = page(400, "Bad Request", "This link has expired", "Scan the code on the panel again. It shows a new one.",
        foot = "Codes last 10 minutes and work once.")

    private fun threadStart(req: Request): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        if (!join.armed()) return notWaiting()
        val ha = haBase()?.let(OAuth::trimBase) ?: return notFoundHa()
        val host = OAuth.sanitizeHost(req.headers["host"])
            ?: return page(400, "Bad Request", "Can't sign in", "Open this page with the panel's address (scan the QR code).")
        val clientId = OAuth.clientId(host)
        val redirect = "http://$host${ThreadJoin.CALLBACK}"
        val sign = states.create(clientId, redirect, ha, PendingSignIn.PURPOSE_THREAD)
        return Response(
            302, "Found",
            mapOf("Location" to OAuth.authorizeUrl(ha, clientId, redirect, sign.state, sign.codeChallenge), "Cache-Control" to "no-store",
                "Content-Type" to "text/plain; charset=utf-8"),
            "Redirecting to Home Assistant".toByteArray(),
        )
    }

    private fun threadCallback(req: Request): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        val q = req.query
        q["error"]?.let { err ->
            return page(400, "Bad Request", "Sign-in cancelled", "Home Assistant answered: ${esc(err)}. Scan the code to try again.")
        }
        val pending = states.consume(q["state"], PendingSignIn.PURPOSE_THREAD)
            ?: return expired()
        // Closed on the panel, or timed out, since this sign-in started: don't join after all.
        if (!join.armed()) return page(409, "Conflict", "Not waiting for a sign-in",
            "The panel stopped waiting. Start “Get it from Home Assistant” on the panel again, then scan the new code.")
        val code = q["code"]?.takeIf { it.isNotEmpty() }
            ?: return page(400, "Bad Request", "Can't sign in", "Home Assistant didn't send an authorization code. Scan the code to try again.")
        return when (val r = tokens.exchangeCode(pending.haBase, pending.clientId, code, pending.codeVerifier)) {
            is TokenResult.Ok -> {
                val rt = r.refreshToken
                    ?: return page(502, "Bad Gateway", "Can't sign in", "Home Assistant didn't return a refresh token.")
                val o = join.onGrant(StoredAuth(pending.haBase, pending.clientId, rt), r)
                if (o.ok) page(200, "OK", o.title, esc(o.text), Look.DONE, tile = o.network?.let { networkTile(it, o.channel) }.orEmpty(),
                    footHtml = MANAGE_FOOT)
                else page(502, "Bad Gateway", o.title, esc(o.text))
            }
            is TokenResult.Error -> {
                AuthLog.w("thread: code exchange failed: ${r.code} (${r.httpStatus})")
                if (r.isInvalidCode) expired()
                else page(502, "Bad Gateway", "Can't sign in",
                    "Home Assistant refused the sign-in (${esc(r.code)}). Scan the code to try again.")
            }
        }
    }

    // ------------------------------------------------------------------ HTTP plumbing

    private fun readRequest(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (head.size() > 16 * 1024) throw IOException("request head too large")
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val target = try { URI(parts[1]) } catch (_: Exception) { return Request(parts[0], "?", emptyMap(), headers) }
        return Request(parts[0], target.rawPath ?: "/", OAuth.parseQuery(target.rawQuery), headers)
    }

    private fun write(out: OutputStream, r: Response) {
        val sb = StringBuilder("HTTP/1.1 ${r.status} ${r.reason}\r\n")
        for ((k, v) in r.headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(r.body)
        out.flush()
    }

    companion object {
        /** The sign-in and Thread pages' "Continue to Home Assistant": the redirect to HA's sign-in. */
        const val CONTINUE = "/signin/continue"
        const val THREAD_CONTINUE = "/thread/continue"

        fun esc(s: String) = PageShell.esc(s)

        private fun host(base: String) = base.substringAfter("://").substringBefore("/").substringBefore(":")

        fun haTile(base: String, dot: Boolean = false) = """<div class="tile"><span class="ic"><svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M4 11.5 12 4.5l8 7V20h-5.5v-5.5h-5V20H4z"/></svg></span><div><small>Home Assistant</small>${esc(host(base))}</div>${if (dot) "<i class=\"ok\"></i>" else ""}</div>"""

        fun infoTile(html: String) = """<div class="tile info"><span class="ii">i</span><div class="txt">$html</div></div>"""

        fun networkTile(name: String, channel: Int?) =
            """<div class="tile"><i class="ok lead"></i><div><small>Thread network</small>${esc(name)}${channel?.let { " · channel $it" } ?: ""}</div></div>"""

        fun button(label: String, href: String) = """<a class="btn" href="$href">${esc(label)} <span aria-hidden="true">↗</span></a>"""

        /**
         * One QR-code page (#63), drawn by the shell every page on the panel shares ([PageShell]). [text]
         * is HTML (escape what comes from outside). Done pages have no button; errors say the one fix on the
         * panel. [footHtml] is HTML under the button (a done page's way on to the Manage page).
         */
        fun page(
            status: Int, reason: String, title: String, text: String, look: Look = Look.PROBLEM,
            tile: String = "", button: String = "", foot: String = "", footHtml: String = "",
        ): Response {
            val html = PageShell.qrPage(sh.edm.s1kiosk.esphome.PanelDevice.FRIENDLY_NAME, title, text, look, tile, button, foot, footHtml,
                headerRight = PageShell.HEADER_SLOT)
            return Response(status, reason, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store") +
                PageShell.securityHeaders(formAction = "'none'"), html.toByteArray(Charsets.UTF_8))
        }

        /** Under a done page: the Manage page is the next place to go. */
        const val MANAGE_FOOT = """<a href="/">Manage this panel</a>"""
    }
}
