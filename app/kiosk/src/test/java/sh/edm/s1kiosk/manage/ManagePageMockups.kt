package sh.edm.s1kiosk.manage

import dev.glassworks.manage.ManageFixture
import dev.glassworks.manage.text
import dev.glassworks.manage.LogEntry
import dev.glassworks.manage.PaneRow
import dev.glassworks.pane.NO_SETTINGS
import dev.glassworks.pane.PaneState
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.web.HttpResponse
import dev.glassworks.web.PageShell.Look
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.auth.UrlConnectionPoster
import java.io.File

/**
 * The Manage page (#33) as the real server answers it, with a signed-in fake browser, plus the QR pages
 * it links with: HTML files to render at desktop and phone width, dark and light
 * (tools in the #33 trail). Illustrative values only. Skipped unless asked:
 *   ./gradlew :kiosk:testDebugUnitTest --tests '*ManagePageMockups*' -Ps1MockupDir=<dir>
 */
class ManagePageMockups {
    private fun save(name: String, r: HttpResponse) {
        val out = File(System.getProperty("s1.mockupDir")!!, "manage").apply { mkdirs() }
        File(out, "$name.html").writeBytes(r.body)
    }

    @Test
    fun mockups() {
        assumeTrue("set -Ps1MockupDir=<dir> to write the mock-ups", System.getProperty("s1.mockupDir") != null)
        val ha = "http://homeassistant.local:8123"
        val signIn = SignInServer(0, { ha }, HaTokenClient(UrlConnectionPoster()), { _, _ -> })
        val f = ManageFixture(qrPages = { req ->
            // The QR flow's done page, as /callback draws it after a sign-in.
            val r = if (req.path == "/callback") SignInServer.page(200, "OK", "Panel signed in", "It's loading your dashboard now. You can close this page.",
                Look.DONE, tile = SignInServer.haTile(ha, dot = true), footHtml = SignInServer.MANAGE_FOOT)
            else signIn.handle(SignInServer.Request(req.method, req.path, req.query, req.headers))
            HttpResponse(r.status, r.headers, r.body)
        })
        val t = f.now
        listOf(
            Triple(9 * 3600_000L + 41 * 60_000 + 7_000, LogEntry.Level.INFO, "Voice" to "Wake word heard (0.82)"),
            Triple(9 * 3600_000L + 41 * 60_000 + 6_000, LogEntry.Level.INFO, "App" to "Reloaded the dashboard"),
            Triple(9 * 3600_000L + 38 * 60_000 + 52_000, LogEntry.Level.WARNING, "Radio" to "ZHA reconnected after 4 s"),
            Triple(9 * 3600_000L + 38 * 60_000 + 48_000, LogEntry.Level.WARNING, "Radio" to "ZHA disconnected"),
            Triple(9 * 3600_000L + 12 * 60_000 + 30_000, LogEntry.Level.INFO, "Music" to "Sendspin stream started, FLAC 48 kHz"),
            Triple(8 * 3600_000L + 2 * 60_000 + 11_000, LogEntry.Level.INFO, "Screen" to "Woke: someone near (0.9 m)"),
            Triple(3 * 3600_000L + 14 * 60_000 + 55_000, LogEntry.Level.ERROR, "Wi-Fi" to "Lost the network for 12 s"),
            Triple(3 * 3600_000L + 14 * 60_000 + 43_000, LogEntry.Level.INFO, "Wi-Fi" to "Reconnected to Example Wi-Fi"),
        ).reversed().forEach { (ago, level, st) -> f.log.append(LogEntry(t - (10 * 3600_000L - ago), level, st.first, st.second)) }

        val anon = f.Browser()
        save("01-sign-in", anon.get("/"))
        f.haBase = null
        save("01b-sign-in-no-ha", f.Browser().get("/"))
        f.haBase = "http://192.0.2.10:8123"
        anon.post("/manage/code", mapOf("next" to "/"))
        save("01c-code-sign-in", anon.get("/manage/code?next=%2F&remember=1"))

        val b = f.Browser()
        b.signInWithHa()
        f.Browser(ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0 Safari/537.36").signInWithHa(remember = false)
        save("02-overview", b.get("/"))
        save("02b-overview-notice", b.get("/?done=restart"))
        save("03-pane-voice", b.get("/assist"))
        save("04-pane-radio", b.get("/radio"))
        save("05-dashboard", b.get("/dashboard"))
        save("06-log", b.get("/log"))
        save("06b-log-warnings", b.get("/log?level=warning"))
        save("07-security", b.get("/security"))
        b.post("/action/reboot", mapOf("csrf" to b.csrf()))
        save("07b-reboot-code", b.get("/confirm/reboot"))
        save("08-about", b.get("/about"))
        save("09-wall-base", b.get("/wall-base"))
        // The QR flow and back: the done page offers Manage; signed in, every QR page's header leads back.
        save("10-qr-done", anon.get("/callback"))
        save("10b-qr-done-signed-in", b.get("/callback"))
        save("10c-qr-sign-in-signed-in", b.get("/signin"))
        save("10d-qr-sign-in", anon.get("/signin"))

        // #243: a long HA user name in the nav's foot (made up). The phone's menu, open, shows the same foot.
        f.user = dev.glassworks.manage.HaUser("Alexandra Montgomery-Fitzgerald", isAdmin = true)
        val long = f.Browser()
        long.signInWithHa()
        val page = long.get("/")
        save("12-long-name", page)
        save("12b-long-name-menu", HttpResponse(page.status, page.headers,
            page.text().replace("<details class=\"menu\">", "<details class=\"menu\" open>").toByteArray()))
        f.user = dev.glassworks.manage.HaUser("Alex", isAdmin = true)

        // #67: every pane has its on/off. Music off; Time zone and Diagnostics have only their switch.
        val on = SettingsItem.Switch("Time zone", "Keeps the zone Aqara resets at boot", { true }) { _, d -> d(null) }
        f.status = f.status.copy(
            panes = f.status.panes.map { if (it.id == "sendspin") it.copy(state = PaneState(PaneState.Kind.OFF, "Turned off")) else it } + listOf(
                PaneRow("time-zone", "Time zone", PaneState(PaneState.Kind.RUNNING, "Keeps the zone Aqara resets at boot", "On"), "time-zone"),
                PaneRow("diagnostics", "Diagnostics", PaneState(PaneState.Kind.RUNNING, "CPU, temperature, memory and Wi-Fi in Home Assistant", "On"), "diagnostics"),
            ),
            sections = f.status.sections + SettingsSection("time-zone", "Time zone", listOf(SettingsItem.Note { NO_SETTINGS }), master = on),
        )
        save("11-overview-panes-on-off", b.get("/"))
        save("11b-pane-off", b.get("/sendspin"))
        save("11c-pane-switch-only", b.get("/time-zone"))
    }
}
