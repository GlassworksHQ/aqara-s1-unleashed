package sh.edm.s1kiosk.settings

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsItem.TextKind
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import sh.edm.s1kiosk.KioskPrefs
import java.util.Locale

/**
 * The settings sections the app has itself, before and after the panes': Dashboard, Screen, About
 * (#63). Network, Log and Security have nothing yet. Values are read when the menu opens; each change
 * applies on its own. The ones Gecko reads at start (the dashboard's address, zoom, secure hosts) ask
 * for a restart, as their adb extras always did.
 */
class CoreSettings(
    private val prefs: KioskPrefs,
    private val version: String,
    /** The screen and sensor status line under the version. */
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
        /** The presence sensor takes the new range. */
        val presenceRange: () -> Unit = {},
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
            switch("Wake when someone's near", "Uses the presence sensor · up to ${range()}", { prefs.screenMode == "presence" }) {
                prefs.screenMode = if (it) "presence" else "always"
            },
            text(
                "Presence range", "The sensor's own (1.5 m)", TextKind.DECIMAL,
                value = { if (prefs.presenceRangeM > 0f) number(prefs.presenceRangeM) else "" },
                shown = { v -> v.toFloatOrNull()?.let { "Up to ${number(it)} m" } ?: "" },
            ) { prefs.presenceRangeM = it.toFloatOrNull() ?: 0f; live.presenceRange() },
            text(
                "Turn off when idle", "120", TextKind.NUMBER, value = { prefs.idleOffSec.toString() },
                shown = { v -> v.toIntOrNull()?.let(::afterIdle) ?: v },
            ) { prefs.idleOffSec = it.toIntOrNull() ?: 120 },
            text(
                "At night", "22:00-07:00", TextKind.PLAIN, value = { prefs.nightWindow },
                shown = { w -> if (w.isBlank()) "Off" else "${w.replace("-", " – ")} · off after ${prefs.nightIdleOffSec} s" },
            ) { prefs.nightWindow = it },
            text(
                "At night, turn off after", "20", TextKind.NUMBER, value = { prefs.nightIdleOffSec.toString() },
                shown = { v -> v.toIntOrNull()?.let { "$it s" } ?: v },
            ) { prefs.nightIdleOffSec = it.toIntOrNull() ?: 20 },
            switch("Brightness", "Follows the room's light", { prefs.autoBrightness }) { prefs.autoBrightness = it; live.brightness() },
            deviceSettings,
        )),
        SettingsPlace.ABOUT to SettingsSection("about", "About", listOf(
            SettingsItem.Info(title = { "S1 Kiosk $version" }, detail = status),
            licences,
        )),
    )

    /** Where the dashboard loads from when its address is automatic. */
    private fun found(): String = prefs.haBase?.let { b ->
        "${b.substringAfter("://").substringBefore("/").substringBefore(":")} · found automatically"
    } ?: "Found automatically"

    private fun range(): String = if (prefs.presenceRangeM > 0f) "${number(prefs.presenceRangeM)} m" else "1.5 m"

    private fun text(
        title: String, hint: String, kind: TextKind, value: () -> String,
        shown: (String) -> String = { it }, restartsApp: Boolean = false, save: (String) -> Unit,
    ) = SettingsItem.Text(title, hint, kind, value, shown = shown, restartsApp = restartsApp, save = { v, done -> save(v.trim()); done(null) })

    private fun switch(title: String, detail: String, value: () -> Boolean, save: (Boolean) -> Unit) =
        SettingsItem.Switch(title, detail, value) { v, done -> save(v); done(null) }

    companion object {
        const val LICENCES_TITLE = "Open source licences"
        const val LICENCES_DETAIL = "The open-source software in the app and the radio package"

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
