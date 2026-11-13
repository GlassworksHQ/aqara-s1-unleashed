package sh.edm.s1kiosk.manage

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.glassworks.manage.HaGateway
import dev.glassworks.manage.HaSignIn
import dev.glassworks.manage.HaUser
import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.LogcatTail
import dev.glassworks.manage.ManageHost
import dev.glassworks.manage.ManageLog
import dev.glassworks.manage.ManageServer
import dev.glassworks.manage.PaneRow
import dev.glassworks.manage.PanelActions
import dev.glassworks.manage.PanelStatus
import dev.glassworks.manage.SessionStore
import dev.glassworks.manage.Tile
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
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
import sh.edm.s1kiosk.radio.RadioMode
import sh.edm.s1kiosk.settings.CoreSettings
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
    private val tail = LogcatTail(log, ::logSource) { Log.w(TAG, it) }

    val server = ManageServer(
        host = Host(),
        haBase = { prefs.haBase },
        setUp = { HaAuth.signedIn },
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

    fun startLog() = tail.start()

    fun stopLog() = tail.stop()

    private inner class Host : ManageHost {
        override val actions: PanelActions = Actions()

        override val name = PanelDevice.FRIENDLY_NAME

        override fun status(): PanelStatus {
            val defs = runtime.paneDefinitions(app)
            val core = CoreSettings(prefs, BuildConfig.VERSION_NAME, status = { runtime.screen.statusLine() },
                deviceSettings = null, licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {})
            val sections = settingsOrder(core.sections(), defs.flatMap { runCatching { it.settings() }.getOrDefault(emptyList()) })
            return PanelStatus(
                name = name,
                frame = "S1 Plus",
                version = BuildConfig.VERSION_NAME,
                uptimeMs = SystemClock.elapsedRealtime(),
                haBase = prefs.haBase,
                tiles = listOf(haTile(), wifiTile(), engineTile(), memoryTile()),
                panes = defs.map { d -> paneRow(d.id, sections.firstOrNull { it.id == d.id }?.id) },
                sections = sections.filter { it.id != "about" },
                about = listOf(
                    "Glassworks Panel" to "S1 Kiosk ${BuildConfig.VERSION_NAME}",
                    "Frame" to "Aqara Panel Hub S1 Plus",
                    "Android" to Build.VERSION.RELEASE,
                    "Engine" to runtime.engine.name.ifEmpty { "Unknown" },
                    "Licence" to "MIT, with the Glassworks core under MPL-2.0",
                    "Source" to "github.com/GlassworksHQ/aqara-s1-unleashed",
                ),
            )
        }

        override fun diagnostics(): String = buildString {
            val s = status()
            appendLine("Version: S1 Kiosk ${s.version} (${BuildConfig.VERSION_CODE})")
            appendLine("Frame: ${runtime.frame.board}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}")
            appendLine("Up: ${s.uptimeMs / 1000} s; app up ${(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000} s")
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
            is SettingsItem.Info -> listOf("${v(i.title)}: ${v(i.detail)}")
            is SettingsItem.Note -> listOf(v(i.text))
            is SettingsItem.Group -> listOf("${i.title}:") + itemLines(i.items).map { "  $it" }
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

    @Suppress("DEPRECATION")
    private fun wifiTile(): Tile {
        val info = runCatching { app.getSystemService(WifiManager::class.java)?.connectionInfo }.getOrNull()
        if (info == null || info.networkId == -1) return Tile("Wi-Fi", "Not connected", "", Tile.Tone.PROBLEM)
        val ssid = info.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: "Connected"
        val ip = info.ipAddress.let { a -> if (a == 0) null else "${a and 255}.${a shr 8 and 255}.${a shr 16 and 255}.${a shr 24 and 255}" }
        return Tile("Wi-Fi", ssid, listOfNotNull("${info.rssi} dBm".replace('-', '−'), ip).joinToString(" · "), Tile.Tone.OK)
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

    /** The panes' names and states. They move into each PaneDefinition with the per-pane switch (#67). */
    private fun paneRow(id: String, sectionId: String?): PaneRow {
        val title = TITLES[id] ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() }
        val state = when (id) {
            "assist" -> if (prefs.voice.enabled) PaneState(PaneState.Kind.RUNNING, "Wake word “${wakeWord(prefs.voice.wakeModel)}” · Assist")
                else PaneState(PaneState.Kind.OFF, "Turned off")
            "sendspin" -> if (prefs.sendspin.enabled) PaneState(PaneState.Kind.RUNNING, "Sendspin · Music Assistant")
                else PaneState(PaneState.Kind.OFF, "Turned off")
            "radio" -> radioState()
            else -> PaneState.UNKNOWN
        }
        val links = if (id == "radio") listOf("Share Home Assistant's Thread network (start it on the panel first)" to ThreadJoin.PATH) else emptyList()
        return PaneRow(id, title, state, sectionId, links)
    }

    private fun radioState(): PaneState {
        val c = runtime.radio
        val mode = c?.mode ?: RadioMode.parse(prefs.radio.mode)
        if (mode == RadioMode.OFF) return PaneState(PaneState.Kind.OFF, "Turned off")
        val status = c?.statusText().orEmpty()
        val problem = status.startsWith("error") || status.contains("failed", ignoreCase = true)
        return PaneState(if (problem) PaneState.Kind.PROBLEM else PaneState.Kind.RUNNING, listOf(mode.label, status).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    private fun wakeWord(model: String) =
        model.substringAfterLast('/').substringBefore('.').split('_', '-').filter { it.isNotEmpty() && !it.startsWith("v") }
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.ifEmpty { "Okay Nabu" }

    private inner class Actions : PanelActions {
        private val main = Handler(Looper.getMainLooper())
        private val dpm get() = app.getSystemService(DevicePolicyManager::class.java)

        private fun toActivity(extra: String): String? {
            val i = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(extra, true)
            main.post {
                val sink = runtime.commandSink
                if (sink != null) sink(i) else runCatching { app.startActivity(i) }.onFailure { Log.w(TAG, "can't start the activity", it) }
            }
            return null
        }

        override fun reloadDashboard() = toActivity("reload")

        override fun restartApp() = toActivity("restart")

        override val canReboot: Boolean get() = runCatching { dpm?.isDeviceOwnerApp(app.packageName) == true }.getOrDefault(false)

        override fun reboot(): String? {
            if (!canReboot) return "The app can only reboot the panel as its device owner."
            main.postDelayed({ runCatching { dpm?.reboot(ComponentName(app, AdminReceiver::class.java)) }.onFailure { Log.w(TAG, "reboot failed", it) } }, 1_000)
            return null
        }

        override fun checkForUpdates(): String? {
            val u = runtime.updates ?: return "The update service isn't running."
            u.onCommand(UpdateManager.CMD_CHECK)
            return null
        }
    }

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
        private val TITLES = mapOf("assist" to "Voice", "sendspin" to "Music", "radio" to "Radio", "wall-base" to "Wall base")
    }
}
