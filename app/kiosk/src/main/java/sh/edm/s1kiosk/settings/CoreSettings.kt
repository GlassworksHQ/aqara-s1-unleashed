package sh.edm.s1kiosk.settings

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsItem.TextKind
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import sh.edm.s1kiosk.KioskPrefs
import java.util.Locale

/**
 * The settings sections the app has itself, before and after the panes': Dashboard, Screen, Log and
 * About (#63). Network and Security have nothing yet. Durations and the night window are picked, the
 * presence range is a slider: nothing on the Screen page is typed (#63 round 2). Values are read when the menu opens; each change
 * applies on its own. The ones Gecko reads at start (the dashboard's address, zoom, secure hosts) ask
 * for a restart, as their adb extras always did.
 */
class CoreSettings(
    private val prefs: KioskPrefs,
    private val version: String,
    /** The screen and sensor status line, on the Log page (diagnostics, not About's). */
    private val status: () -> String,
    /** The device's own settings app, when it has one (the frame's systemSettings). */
    private val deviceSettings: SettingsItem.Link?,
    private val licences: SettingsItem.Link,
    /** What the running app does when a setting it follows live changes. */
    private val live: Live = Live(),
) {
    class Live(
        /** Full screen: the page hides or shows HA's header and sidebar. */
        val fullScreen: () -> Unit = {},
        /** The presence sensor takes the new range: null, or why it couldn't. */
        val presenceRange: () -> String? = { null },
        /** Brightness follows the light sensor, or stops. */
        val brightness: () -> Unit = {},
    )

    fun sections(): Map<SettingsPlace, SettingsSection> = mapOf(
        SettingsPlace.DASHBOARD to SettingsSection("dashboard", "Dashboard", listOf(
            text(
                "Home Assistant", "Found automatically", TextKind.ADDRESS,
                value = { prefs.urlSetting.takeIf { it != KioskPrefs.AUTO }.orEmpty() },
                shown = { it.ifBlank { found() } }, restartsApp = true,
            ) { prefs.urlSetting = it.ifBlank { KioskPrefs.AUTO } },
            switch("Full screen", "Hide Home Assistant's header and sidebar", { prefs.hideHeader }) {
                prefs.hideHeader = it
                prefs.hideSidebar = it
                live.fullScreen()
            },
            text(
                "Zoom", "Auto", TextKind.DECIMAL, value = { if (prefs.zoom > 0f) number(prefs.zoom) else "" },
                shown = { v -> v.toFloatOrNull()?.let { "${number(it)}×" } ?: "Auto" }, restartsApp = true,
            ) { prefs.zoom = it.toFloatOrNull() ?: 0f },
            text(
                "Secure hosts", "Other addresses the dashboard loads over HTTP, like a camera", TextKind.ADDRESS,
                value = { prefs.extraSecureHosts }, restartsApp = true,
            ) { prefs.extraSecureHosts = it },
        )),
        SettingsPlace.SCREEN to SettingsSection("screen", "Screen", listOfNotNull(
            // 0.4.3's "Screen follows presence" (#63's design names it this): on, the screen wakes when
            // someone comes close and goes off when idle; off, it stays on. On means both: the screen mode
            // (HA's `Screen mode` select) and waking on approach (`wake_on_approach`, no other control).
            switch("Wake when someone's near", "Uses the presence sensor${range()}", { prefs.screenMode == "presence" && prefs.wakeOnApproach }) {
                prefs.screenMode = if (it) "presence" else "always"
                if (it) prefs.wakeOnApproach = true
            },
            SettingsItem.Slider(
                "Presence range", value = { prefs.presenceRangeM }, min = 0f, max = RANGE_MAX_M, step = 0.1f,
                // 0 leaves the sensor's threshold as it is (Vi5300Presence.setRange): no distance to promise.
                shown = { v -> if (v <= 0f) "The sensor's own setting" else "Up to ${number(v)} m" },
            ) { v, done ->
                val old = prefs.presenceRangeM
                prefs.presenceRangeM = v
                val why = live.presenceRange()
                if (why != null) prefs.presenceRangeM = old
                done(why)
            },
            SettingsItem.Duration(
                "Turn off when idle", value = { prefs.idleOffSec }, choices = IDLE_CHOICES, shown = ::afterIdle,
            ) { v, done -> prefs.idleOffSec = v; done(null) },
            SettingsItem.TimeRange(
                "At night", value = { prefs.nightWindow },
                shown = { w -> if (w.isBlank()) "Off" else w.replace("-", " – ") },
            ) { v, done -> prefs.nightWindow = v; live.brightness(); done(null) }, // the night cap follows the window
            SettingsItem.Duration(
                "At night, turn off after", value = { prefs.nightIdleOffSec }, choices = NIGHT_CHOICES, shown = ::afterIdle,
            ) { v, done -> prefs.nightIdleOffSec = v; done(null) },
            switch("Brightness", "Follows the room's light", { prefs.autoBrightness }) { prefs.autoBrightness = it; live.brightness() },
            deviceSettings,
        )),
        SettingsPlace.LOG to SettingsSection("log", "Log", listOf(
            SettingsItem.Info(title = { "Screen and sensors" }, detail = status),
        )),
        SettingsPlace.ABOUT to SettingsSection("about", "About", listOf(
            SettingsItem.Info(title = { "S1 Kiosk $version" }, detail = { "" }),
            licences,
        )),
    )

    /** Where the dashboard loads from when its address is automatic. */
    private fun found(): String = prefs.haBase?.let { b ->
        "${b.substringAfter("://").substringBefore("/").substringBefore(":")} · found automatically"
    } ?: "Found automatically"

    /** " · up to 1.8 m", or nothing while the sensor keeps its own setting. */
    private fun range(): String = if (prefs.presenceRangeM > 0f) " · up to ${number(prefs.presenceRangeM)} m" else ""

    private fun text(
        title: String, hint: String, kind: TextKind, value: () -> String,
        shown: (String) -> String = { it }, restartsApp: Boolean = false, save: (String) -> Unit,
    ) = SettingsItem.Text(title, hint, kind, value, shown = shown, restartsApp = restartsApp, save = { v, done -> save(v.trim()); done(null) })

    private fun switch(title: String, detail: String, value: () -> Boolean, save: (Boolean) -> Unit) =
        SettingsItem.Switch(title, detail, value) { v, done -> save(v); done(null) }

    companion object {
        const val LICENCES_TITLE = "Open source licences"
        const val LICENCES_DETAIL = "The open-source software in the app and the radio package"

        /** The presence sensor's furthest (Home Assistant's `Presence range` number goes as far). */
        const val RANGE_MAX_M = 2.5f
        val IDLE_CHOICES = listOf(30, 60, 120, 300, 600, 1800, 0)
        val NIGHT_CHOICES = listOf(10, 20, 30, 60, 120, 300)

        /** "After 2 minutes", "After 45 s", "Never" (0). */
        fun afterIdle(sec: Int): String = when {
            sec <= 0 -> "Never"
            sec % 60 == 0 -> "After ${sec / 60} minute${if (sec == 60) "" else "s"}"
            else -> "After $sec s"
        }

        /** 1.25, 1.5, 2: no trailing zeros. */
        fun number(v: Float): String = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    }
}
