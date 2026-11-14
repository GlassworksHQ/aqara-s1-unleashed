package sh.edm.s1kiosk.manage

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.glassworks.manage.HaGateway
import dev.glassworks.platform.WifiLink
import dev.glassworks.manage.HaSignIn
import dev.glassworks.manage.HaUser
import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.LogcatTail
import dev.glassworks.manage.ManageHost
import dev.glassworks.manage.ManageLog
import dev.glassworks.manage.ManageServer
import dev.glassworks.manage.PaneRow
import dev.glassworks.manage.paneRow
import dev.glassworks.manage.PanelActions
import dev.glassworks.manage.PanelStatus
import dev.glassworks.manage.SessionStore
import dev.glassworks.manage.Tile
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import dev.glassworks.web.HttpRequest
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.HttpServer
import org.json.JSONObject
import sh.edm.s1kiosk.AdminReceiver
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.MainActivity
import sh.edm.s1kiosk.auth.HaAuth
import sh.edm.s1kiosk.auth.HaSocket
import sh.edm.s1kiosk.auth.HaThreadClient
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.auth.StoredAuth
import sh.edm.s1kiosk.auth.ThreadJoin
import sh.edm.s1kiosk.auth.TokenResult
import sh.edm.s1kiosk.auth.WsHaSocket
import sh.edm.s1kiosk.esphome.PanelDevice
import sh.edm.s1kiosk.settings.CoreSettings
import sh.edm.s1kiosk.setup.SetupState
import sh.edm.s1kiosk.update.AppVersion
import sh.edm.s1kiosk.update.UpdateManager
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.util.Locale

/**
 * The S1's Manage page (#33): the core's [ManageServer] on port 8099 with the QR-code pages
 * ([SignInServer]) behind it, what the page shows about this panel, and its actions. Made by
 * [HaAuth.startServer], which owns the port.
 */
class S1Manage(context: Context, private val runtime: GlassworksRuntime, signIn: SignInServer, tokens: HaTokenClient, private val prefs: KioskPrefs) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "manage")
    val log = ManageLog(File(dir, "log"))
    private val tail = LogcatTail(log, ::logSource, onError = { Log.w(TAG, it) })

    private val host = Host()

    val server = ManageServer(
        host = host,
        haBase = { prefs.haBase },
        setUp = ::setUp,
        sessions = SessionStore(File(dir, "sessions.json")),
        code = runtime.panelCode,
        signIn = HaSignIn(Gateway(tokens) { url -> WsHaSocket.connect(url) }, warn = { Log.w(TAG, it) }),
        log = log,
        qrPaths = setOf("/signin", SignInServer.CONTINUE, "/callback", ThreadJoin.PATH, SignInServer.THREAD_CONTINUE, ThreadJoin.CALLBACK),
        qr = { req -> qr(signIn, req) },
        ownHosts = ::ownHosts,
        event = { msg -> Log.i(TAG, msg); runCatching { log.append(LogEntry(System.currentTimeMillis(), LogEntry.Level.INFO, "Manage", msg)) } },
    )

    fun http(port: Int) = HttpServer(port, server::handle, log = { Log.d(TAG, it) })

    /** What the Manage page shows now; tests read it. */
    internal fun status(): PanelStatus = host.status()

    internal val actions: PanelActions get() = host.actions

    fun startLog() = tail.start()

    fun stopLog() = tail.stop()

    private inner class Host : ManageHost {
        override val actions: PanelActions = Actions()

        override val name = PanelDevice.FRIENDLY_NAME

        override fun status(): PanelStatus {
            val defs = runtime.paneDefinitions(app)
            val core = CoreSettings(prefs, AppVersion.shown, status = { runtime.screen.statusLine() },
                deviceSettings = null, licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {})
            // The panes' sections as the panel's menu has them, each with its on/off (#67) as the master.
            val panes = defs.flatMap { d -> runCatching { paneSections(listOf(d), runtime::runningPane) }.onFailure { Log.w(TAG, "${d.id}'s settings failed", it) }.getOrDefault(emptyList()) }
            val sections = settingsOrder(core.sections(), panes)
            return PanelStatus(
                name = name,
                frame = "S1 Plus",
                version = AppVersion.shown,
                // The app's, as the line it sits on is the app's: the device's is in the diagnostics (#263).
                uptimeMs = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime(),
                haBase = prefs.haBase,
                tiles = listOf(haTile(), wifiTile(), engineTile(), memoryTile()),
                panes = defs.map { d -> s1PaneRow(d.id, panes.firstOrNull { it.id == d.id }) },
                sections = sections.filter { it.id != "about" },
                about = listOf(
                    "Glassworks Panel" to "S1 Kiosk ${AppVersion.shown}",
                    "Frame" to "Aqara Panel Hub S1 Plus",
                    "Android" to Build.VERSION.RELEASE,
                    "Engine" to runtime.engine.name.ifEmpty { "Unknown" },
                    "Licence" to "MIT, with the Glassworks core under MPL-2.0",
                    "Source" to "github.com/GlassworksHQ/glassworks",
                ),
            )
        }

        override fun diagnostics(): String = buildString {
            val s = status()
            appendLine("Version: S1 Kiosk ${s.version} (${BuildConfig.VERSION_CODE})")
            appendLine("Frame: ${runtime.frame.board}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
            appendLine("Device up: ${SystemClock.elapsedRealtime() / 1000} s; app up ${s.uptimeMs / 1000} s")
            appendLine("Home Assistant: ${s.haBase ?: "none"}; panel signed in: ${HaAuth.signedIn}; added: ${prefs.haSeen}")
            for (t in s.tiles) appendLine("${t.label}: ${t.value} (${t.detail})")
            appendLine()
            appendLine("Panes:")
            for (p in s.panes) appendLine("  ${p.id}: ${p.state.kind} ${p.state.detail}")
            appendLine()
            appendLine("Settings:")
            for (sec in s.sections) {
                appendLine("  [${sec.id}] ${sec.title}")
                for (line in itemLines(sec.items)) appendLine("    $line")
            }
            appendLine()
            appendLine("Root side:")
            for (k in listOf("sys.s1kiosk.radio.status", "sys.s1kiosk.radio.version", "sys.s1kiosk.radio.fw", "sys.s1kiosk.net.status",
                "sys.s1kiosk.update.version", "sys.s1kiosk.timezone.status", "persist.s1kiosk.radio_mode", "persist.s1kiosk.radio_zigbee")) {
                appendLine("  $k = ${prop(k)}")
            }
        }
    }

    private fun itemLines(items: List<SettingsItem>): List<String> = items.flatMap { i ->
        fun v(f: () -> Any?) = runCatching { f()?.toString().orEmpty() }.getOrDefault("?")
        when (i) {
            is SettingsItem.Text -> listOf("${i.title}: ${v(i.value)}")
            is SettingsItem.Switch -> listOf("${i.title}: ${v(i.value)}")
            is SettingsItem.Slider -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            is SettingsItem.Duration -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            is SettingsItem.TimeRange -> listOf("${i.title}: ${v { i.shown(i.value()) }}")
            is SettingsItem.Info -> listOf("${v(i.title)}: ${v(i.detail)}")
            is SettingsItem.Note -> listOf(v(i.text))
            is SettingsItem.Group -> listOf("${i.title}:") + itemLines(i.items).map { "  $it" }
            is SettingsItem.Choice -> listOf("${i.title}: ${v(i.value)}")
            is SettingsItem.Status -> listOf(v(i.text))
            is SettingsItem.States -> listOf("${i.title}:") + runCatching { i.rows() }.getOrDefault(emptyList()).map { "  ${it.name}: ${it.value}" }
            is SettingsItem.Tiles -> runCatching { i.tiles() }.getOrDefault(emptyList()).map { "${it.name}: ${it.value} ${it.unit}".trim() }
            is SettingsItem.Link, is SettingsItem.Native -> emptyList()
        }
    }

    private fun haTile(): Tile {
        val base = prefs.haBase
        val host = base?.substringAfter("://")?.substringBefore('/')?.substringBefore(':') ?: "No address yet"
        val connected = runtime.device?.homeAssistantConnected() == true
        val value = when {
            connected -> "Connected"
            prefs.haSeen -> "Not connected"
            else -> "Not added yet"
        }
        // The dashboard's own sign-in is the QR flow: offer it here while the panel has none.
        return if (!HaAuth.signedIn && base != null) Tile("Home Assistant", value, "Dashboard not signed in · Sign in", if (connected) Tile.Tone.WORKING else Tile.Tone.OFF, href = "/signin")
        else Tile("Home Assistant", value, host, if (connected) Tile.Tone.OK else if (prefs.haSeen) Tile.Tone.PROBLEM else Tile.Tone.OFF)
    }

    private fun wifiTile(): Tile {
        val link = runCatching { WifiLink.of(app) }.getOrNull() ?: return Tile("Wi-Fi", "Not connected", "", Tile.Tone.PROBLEM)
        return Tile("Wi-Fi", link.ssid ?: "Connected", listOfNotNull(link.rssi?.let { "$it dBm".replace('-', '−') }, link.ipv4).joinToString(" · "), Tile.Tone.OK)
    }

    private fun engineTile(): Tile {
        val name = runtime.engine.name.ifEmpty { "Unknown" }
        val major = name.substringAfterLast(' ').substringBefore('.')
        return Tile("Engine", name.substringBeforeLast(' ') + " " + major, "Version ${name.substringAfterLast(' ')}", Tile.Tone.OK)
    }

    private fun memoryTile(): Tile {
        val mi = ActivityManager.MemoryInfo()
        runCatching { app.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi) }
        fun gb(b: Long) = String.format(Locale.ENGLISH, "%.1f", b / 1e9).removeSuffix(".0")
        if (mi.totalMem <= 0) return Tile("Memory", "Unknown", "", null)
        return Tile("Memory", "${gb(mi.totalMem - mi.availMem)} of ${gb(mi.totalMem)} GB", "Free ${gb(mi.availMem)} GB", null)
    }

    /** A pane's row ([paneRow]): what the S1 can say about a running Voice, Music and Radio. */
    /** Each pane's state is its section's own ([dev.glassworks.pane.paneState]), as the panel's rail shows it (#243). */
    private fun s1PaneRow(id: String, section: SettingsSection?): PaneRow = when (id) {
        "radio" -> paneRow(id, section, listOf("Share Home Assistant's Thread network (start it on the panel first)" to ThreadJoin.PATH))
        else -> paneRow(id, section)
    }

    private inner class Actions : PanelActions {
        private val main = Handler(Looper.getMainLooper())
        private val dpm get() = app.getSystemService(DevicePolicyManager::class.java)

        private fun toActivity(extra: String, delayMs: Long = 0): String? {
            val i = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(extra, true)
            main.postDelayed({
                val sink = runtime.commandSink
                if (sink != null) sink(i) else runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "can't start the activity", it) }
            }, delayMs)
            return null
        }

        override fun reloadDashboard() = toActivity("reload")

        // After the page's answer has gone out: the restart ends this process, and the HTTP worker with it.
        override fun restartApp() = toActivity("restart", ACTION_DELAY_MS)

        override val canReboot: Boolean get() = runCatching { dpm?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false)

        override fun reboot(): String? {
            if (!canReboot) return "The app can only reboot the panel as its device owner."
            main.postDelayed({ runCatching { dpm?.reboot(ComponentName(app, AdminReceiver::class.java)) }.onFailure { Log.w(TAG, "reboot failed", it) } }, ACTION_DELAY_MS)
            return null
        }

        override fun checkForUpdates(): String? {
            val u = runtime.updates ?: return "The update service isn't running."
            u.onCommand(UpdateManager.CMD_CHECK)
            return null
        }
    }

    /**
     * The panel is set up, so `/` is the Manage page and not the QR sign-in: its dashboard is signed in,
     * or setup has passed Home Assistant ([SetupState.setUpBefore]: added, keyed or skipped), which also
     * covers a panel that signed in on its own screen ("Sign in on this panel instead") and so holds no
     * HA credentials. Every QR code names its own path (`/signin`, `/thread`), so none of them needs `/`.
     */
    internal fun setUp(): Boolean =
        HaAuth.signedIn || SetupState.setUpBefore(prefs.haBase != null, prefs.haSeen, prefs.apiEncryption, prefs.haSetupSkipped)

    /** The panel's IP addresses now, `localhost`, its ESPHome name and its Android host name, as `.local`. */
    private fun ownHosts(): Set<String> {
        val out = hashSetOf("localhost", "${PanelDevice.NAME}.local")
        runCatching {
            for (i in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) for (a in i.inetAddresses.toList()) {
                a.hostAddress?.let { out += it.substringBefore('%').lowercase() }
            }
        }.onFailure { Log.w(TAG, "can't list the panel's addresses", it) }
        prop("net.hostname").lowercase().takeIf { it.isNotEmpty() }?.let { out += it; out += "$it.local" }
        return out
    }

    private fun prop(key: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
    }.getOrDefault("")

    private fun qr(signIn: SignInServer, req: HttpRequest): HttpResponse {
        val r = signIn.handle(SignInServer.Request(req.method, req.path, req.query, req.headers))
        return HttpResponse(r.status, r.headers, r.body)
    }

    /** HA for the Manage page's sign-in: the token endpoint, `auth/current_user` over the websocket, revoke. */
    internal class Gateway(private val tokens: HaTokenClient, private val connect: (String) -> HaSocket) : HaGateway {
        override fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String): HaGateway.Tokens =
            when (val r = tokens.exchangeCode(haBase, clientId, code, codeVerifier)) {
                is TokenResult.Ok -> HaGateway.Tokens.Ok(r.accessToken, r.refreshToken)
                is TokenResult.Error -> HaGateway.Tokens.Failed(r.code, invalidCode = r.isInvalidCode, unreachable = r.httpStatus <= 0)
            }

        override fun currentUser(haBase: String, accessToken: String): HaUser? {
            val url = HaThreadClient.websocketUrl(haBase) ?: return null
            val ws = try { connect(url) } catch (e: IOException) { return null }
            try {
                if (next(ws)?.optString("type") != "auth_required") return null
                ws.send(JSONObject().put("type", "auth").put("access_token", accessToken).toString())
                if (next(ws)?.optString("type") != "auth_ok") return null
                ws.send(JSONObject().put("id", 1).put("type", "auth/current_user").toString())
                while (true) {
                    val m = next(ws) ?: return null
                    if (m.optString("type") != "result" || m.optInt("id", -1) != 1) continue
                    if (!m.optBoolean("success", false)) return null
                    val u = m.optJSONObject("result") ?: return null
                    return HaUser(u.optString("name").ifEmpty { "Home Assistant user" }, u.optBoolean("is_admin", false))
                }
            } catch (_: Exception) {
                return null
            } finally {
                runCatching { ws.close() }
            }
        }

        override fun revoke(haBase: String, clientId: String, refreshToken: String): Boolean =
            tokens.revoke(StoredAuth(haBase, clientId, refreshToken))

        private fun next(ws: HaSocket): JSONObject? = ws.receive()?.let(::JSONObject)
    }

    companion object {
        const val TAG = "S1Manage"

        /** How long a restart or reboot waits, so the page that started it reaches the browser first. */
        const val ACTION_DELAY_MS = 1_000L

        /** The S1's log tags, in words, for the Manage log. The rest (Gecko, Android) stays out. */
        fun logSource(tag: String): String? = when {
            tag.startsWith("S1Kiosk.Radio") || tag.startsWith("S1Radio") -> "Radio"
            tag.startsWith("S1Kiosk.Screen") -> "Screen"
            tag.startsWith("S1Kiosk.Wifi") -> "Wi-Fi"
            tag.startsWith("S1Kiosk.Update") -> "Updates"
            tag.startsWith("S1Kiosk.Improv") || tag.startsWith("S1Kiosk.SetupChannel") -> "Setup"
            tag.startsWith("S1Kiosk.TimeZone") -> "Time zone"
            tag.startsWith("S1Kiosk.Voice") || tag == "S1Voice" -> "Voice"
            tag == "S1Sendspin" -> "Music"
            tag == "S1Base" -> "Wall base"
            tag == "S1Auth" -> "Sign-in"
            tag == "S1Discovery" -> "Home Assistant"
            tag == PanelDevice.ESPHOME_LOG_TAG -> "Home Assistant"
            tag == "S1Kiosk" || tag.startsWith("S1Kiosk.") -> "App"
            else -> null
        }
    }
}
