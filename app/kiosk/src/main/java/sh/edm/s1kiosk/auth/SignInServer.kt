package sh.edm.s1kiosk.auth

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
            "/", "" -> index(req)
            "/signin" -> signin(req)
            "/callback" -> callback(req)
            ThreadJoin.PATH -> threadStart(req)
            ThreadJoin.CALLBACK -> threadCallback(req)
            "/favicon.ico" -> Response(404, "Not Found", mapOf("Content-Type" to "text/plain"), ByteArray(0))
            else -> page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        }
    }

    private fun index(req: Request): Response {
        val ha = haBase()
        return if (ha == null) notFoundHa()
        else page(200, "OK", "Sign in the panel", "Sign in to Home Assistant to show your dashboard on the panel.",
            action = "<a class=\"btn\" href=\"/signin\">Sign in</a>")
    }

    private fun notFoundHa() = page(
        503, "Service Unavailable", "Home Assistant not found yet",
        "The panel hasn't found Home Assistant. Enter its address on the panel, then scan the code again.",
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
            ?: return page(400, "Bad Request", "This sign-in link has expired",
                "Scan the QR code on the panel again.")
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
                page(200, "OK", "Panel signed in ✓", "You can close this page. The panel is loading your dashboard.", ok = true)
            }
            is TokenResult.Error -> {
                AuthLog.w("code exchange failed: ${r.code} (${r.httpStatus})")
                when {
                    r.isInvalidCode -> page(400, "Bad Request", "This sign-in link has expired",
                        "The code from Home Assistant was already used or is too old. Scan the QR code on the panel again.")
                    r.httpStatus <= 0 -> page(502, "Bad Gateway", "Can't reach Home Assistant",
                        "The panel couldn't reach ${esc(pending.haBase)}. Check the network and scan the code again.")
                    else -> page(502, "Bad Gateway", "Can't sign in",
                        "Home Assistant refused the sign-in (${esc(r.code)}). Scan the code to try again.")
                }
            }
        }
    }

    private fun threadStart(req: Request): Response {
        val join = thread ?: return page(404, "Not Found", "Not found", "This panel only serves the sign-in pages.")
        if (!join.armed()) return page(409, "Conflict", "Not waiting for a sign-in",
            "Start “Join Home Assistant's Thread network” on the panel first, then scan the code it shows.")
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
            ?: return page(400, "Bad Request", "This link has expired", "Scan the QR code on the panel again.")
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
                page(if (o.ok) 200 else 502, if (o.ok) "OK" else "Bad Gateway", o.title, esc(o.text), ok = o.ok)
            }
            is TokenResult.Error -> {
                AuthLog.w("thread: code exchange failed: ${r.code} (${r.httpStatus})")
                if (r.isInvalidCode) page(400, "Bad Request", "This link has expired",
                    "The code from Home Assistant was already used or is too old. Scan the QR code on the panel again.")
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
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        /** Small page in the dashboard's visual language (tablet-home-card tokens). */
        fun page(status: Int, reason: String, title: String, text: String, ok: Boolean = false, action: String = ""): Response {
            val accent = if (ok) "#34d399" else "#a1a1aa"
            val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="dark">
<title>S1 Panel</title><style>
:root{--bg:#0a0b0f;--s1:rgba(255,255,255,.045);--s2:rgba(255,255,255,.08);--bd:rgba(255,255,255,.07);--tx:#f4f4f5;--mu:#a1a1aa;--fa:#63636b}
*{box-sizing:border-box}html,body{margin:0;min-height:100%}
body{min-height:100vh;display:grid;place-items:center;padding:24px;color:var(--tx);
font-family:"Google Sans","Product Sans",Inter,system-ui,-apple-system,sans-serif;
background:radial-gradient(900px 600px at -10% -20%,rgba(56,189,248,.13),transparent 60%),radial-gradient(800px 600px at 110% 120%,rgba(167,139,250,.14),transparent 60%),var(--bg)}
.card{width:100%;max-width:440px;background:var(--s1);border:1px solid var(--bd);border-radius:26px;padding:28px;backdrop-filter:blur(20px)}
.lbl{font-size:12px;letter-spacing:.14em;text-transform:uppercase;color:var(--fa);font-weight:600}
h1{font-size:32px;font-weight:300;letter-spacing:-.02em;margin:12px 0 10px;color:$accent}
p{font-size:16px;line-height:1.5;color:var(--mu);margin:0}
.btn{display:inline-block;margin-top:20px;padding:12px 18px;border-radius:16px;background:var(--s2);color:var(--tx);text-decoration:none;font-size:15px}
</style></head><body><div class="card"><div class="lbl">S1 Panel</div><h1>${esc(title)}</h1><p>$text</p>$action</div></body></html>"""
            return Response(status, reason, mapOf("Content-Type" to "text/html; charset=utf-8", "Cache-Control" to "no-store"),
                html.toByteArray(Charsets.UTF_8))
        }
    }
}
