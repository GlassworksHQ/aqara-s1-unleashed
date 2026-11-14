/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.util.Collections

/** A Manage server over fakes: a panel, Home Assistant and a browser. Illustrative values only. */
class ManageFixture(
    var haBase: String? = "http://192.0.2.10:8123",
    var setUp: Boolean = true,
    /** The QR pages; by default a stub that names the path. */
    private val qrPages: ((HttpRequest) -> HttpResponse)? = null,
) {
    val dir: File = Files.createTempDirectory("manage").toFile()
    var now = 1_763_000_000_000L
    val log = ManageLog(File(dir, "log"), clock = { now })
    val sessions = SessionStore(File(dir, "sessions.json"), clock = { now })
    val code = PanelCode(clock = { now })
    val shown = Collections.synchronizedList(ArrayList<PanelCode.Shown?>())
    val ran = Collections.synchronizedList(ArrayList<String>())
    val qrSeen = Collections.synchronizedList(ArrayList<String>())

    // Fake Home Assistant.
    var user: HaUser? = HaUser("Alex", isAdmin = true)
    var tokens: HaGateway.Tokens = HaGateway.Tokens.Ok("ACCESS", "REFRESH")
    val exchanged = Collections.synchronizedList(ArrayList<String>())
    val revoked = Collections.synchronizedList(ArrayList<String>())
    val gateway = object : HaGateway {
        override fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String): HaGateway.Tokens {
            exchanged += "$haBase $clientId $code"
            return tokens
        }
        override fun currentUser(haBase: String, accessToken: String) = user
        override fun revoke(haBase: String, clientId: String, refreshToken: String): Boolean { revoked += refreshToken; return revokeWorks }
    }
    val signIn = HaSignIn(gateway, resolve = { listOf(InetAddress.getByName(it)) }, clock = { now },
        local = { HaSignIn.isLocalAddress(it) || it.hostAddress.startsWith("192.0.2.") }, warn = { warnings += it })

    val warnings = Collections.synchronizedList(ArrayList<String>())
    val events = Collections.synchronizedList(ArrayList<String>())
    var revokeWorks = true
    var canReboot = true
    val actions = object : PanelActions {
        override fun reloadDashboard(): String? { ran += "reload"; return null }
        override fun restartApp(): String? { ran += "restart"; return null }
        override val canReboot get() = this@ManageFixture.canReboot
        override fun reboot(): String? { ran += "reboot"; return null }
        override fun checkForUpdates(): String? { ran += "update"; return null }
    }

    var status = PanelStatus(
        name = "Glassworks Panel", frame = "S1 Plus", version = "0.9.2", uptimeMs = 3 * 86_400_000L + 4 * 3_600_000L,
        haBase = "http://192.0.2.10:8123",
        tiles = listOf(
            Tile("Home Assistant", "Connected", "homeassistant.local", Tile.Tone.OK),
            Tile("Wi-Fi", "Example Wi-Fi", "−52 dBm · 192.0.2.40", Tile.Tone.OK),
            Tile("Engine", "GeckoView 159", "Version 159.0", Tile.Tone.OK),
            Tile("Memory", "1.1 of 2 GB", "Free 0.9 GB", null),
        ),
        panes = listOf(
            PaneRow("assist", "Voice", PaneState(PaneState.Kind.RUNNING, "Wake word “Okay Nabu” · Assist"), null),
            PaneRow("sendspin", "Music", PaneState(PaneState.Kind.RUNNING, "Sendspin · Music Assistant", "Playing"), null),
            PaneRow("radio", "Radio", PaneState(PaneState.Kind.RUNNING, "Zigbee coordinator (TCP) · ZHA connected"), "radio",
                links = listOf("Share Home Assistant's Thread network (start it on the panel first)" to "/thread")),
            PaneRow("wall-base", "Wall base", PaneState(PaneState.Kind.CANT_RUN, "Needs the wall base attached"), null),
        ),
        sections = listOf(
            SettingsSection("dashboard", "Home Assistant & dashboard", listOf(
                SettingsItem.Text("Dashboard URL", "auto (find Home Assistant)", SettingsItem.TextKind.ADDRESS, { "http://homeassistant.local:8123/lovelace/0" }) { _, d -> d(null) },
                SettingsItem.Switch("Hide Home Assistant's header & sidebar", "The dashboard fills the screen", { true }) { _, d -> d(null) },
            )),
            SettingsSection("screen", "Panel", listOf(
                SettingsItem.Switch("Screen follows presence", "Off when idle, on when someone comes close", { true }) { _, d -> d(null) },
                SettingsItem.Text("Screen off after idle (s, 0 = never)", "120", SettingsItem.TextKind.NUMBER, { "120" }) { _, d -> d(null) },
            )),
            SettingsSection("radio", "Radio", listOf(
                SettingsItem.Info({ "Zigbee coordinator (TCP)" }, { "socket://192.0.2.40:6638" }),
                SettingsItem.Native("radio-mode") { throw UnsupportedOperationException() },
            )),
        ),
        about = listOf("Glassworks Panel" to "S1 Kiosk 0.9.2", "Frame" to "Aqara Panel Hub S1 Plus", "Android" to "10"),
    )

    val host = object : ManageHost {
        override val name = "Glassworks Panel"
        override fun status() = status
        override val actions = this@ManageFixture.actions
        override fun diagnostics() = "Home Assistant: http://192.0.2.10:8123\naccess_token=eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl\nnoise key: AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
    }

    val server = ManageServer(
        host = host, haBase = { haBase }, setUp = { setUp }, sessions = sessions, code = code, signIn = signIn, log = log,
        qrPaths = setOf("/signin", "/signin/continue", "/callback", "/thread", "/thread/continue", "/thread/callback"),
        qr = { req -> qrSeen += req.path; qrPages?.invoke(req) ?: HttpResponse.html(200, "<header>QR ${req.path}<!--gw-header-right--></header>") },
        pages = ManagePages(clock = { now }),
        event = { events += it },
        ownHosts = { setOf("192.0.2.40", "glassworks-panel.local", "fd00::40", "127.0.0.1") },
    )

    init {
        code.display = { shown += it }
        code.canDisplay = { true }
    }

    // ------------------------------------------------------------------ a browser

    inner class Browser(val host: String = "192.0.2.40:8099", val remote: String = "192.0.2.77", val ua: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15") {
        val cookies = LinkedHashMap<String, String>()

        fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse = send("GET", path, emptyMap(), headers)

        fun post(path: String, form: Map<String, String>, headers: Map<String, String> = mapOf("origin" to "http://$host")): HttpResponse =
            send("POST", path, form, headers)

        fun send(method: String, target: String, form: Map<String, String>, headers: Map<String, String>): HttpResponse {
            val path = target.substringBefore('?')
            val query = dev.glassworks.web.HttpServer.parseQuery(target.substringAfter('?', ""))
            val body = form.entries.joinToString("&") { (k, v) -> dev.glassworks.web.HttpServer.enc(k) + "=" + dev.glassworks.web.HttpServer.enc(v) }
            val h = HashMap<String, String>()
            h["host"] = host
            h["user-agent"] = ua
            if (cookies.isNotEmpty()) h["cookie"] = cookies.entries.joinToString("; ") { (k, v) -> "$k=$v" }
            if (method == "POST") h["content-type"] = "application/x-www-form-urlencoded"
            h.putAll(headers)
            val r = server.handle(HttpRequest(method, path, query, h, body.toByteArray(), remote))
            r.headers["Set-Cookie"]?.split('\n')?.forEach { c ->
                val (k, v) = c.substringBefore(';').split('=', limit = 2)
                if (c.contains("Max-Age=0")) cookies.remove(k) else cookies[k] = v
            }
            return r
        }

        /** Signs in through the fake HA. Returns the callback's response. */
        fun signInWithHa(remember: Boolean = true, next: String = "/"): HttpResponse {
            get(next)
            val start = post("/manage/ha", mapOf("next" to next) + if (remember) mapOf("remember" to "1") else emptyMap())
            val state = dev.glassworks.web.HttpServer.parseQuery(start.headers["Location"]!!.substringAfter('?'))["state"]!!
            return get("/manage/callback?code=CODE&state=$state", mapOf("sec-fetch-site" to "cross-site"))
        }

        fun csrf(): String = Regex("name=\"csrf\" value=\"([^\"]+)\"").find(get("/").text())!!.groupValues[1]
    }
}

fun HttpResponse.text() = String(body, Charsets.UTF_8)
