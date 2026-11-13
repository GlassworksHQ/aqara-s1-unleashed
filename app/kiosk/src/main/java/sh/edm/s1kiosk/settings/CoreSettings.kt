package sh.edm.s1kiosk.settings

import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsItem.TextKind
import dev.glassworks.pane.SettingsPlace
import dev.glassworks.pane.SettingsSection
import sh.edm.s1kiosk.KioskPrefs

/**
 * The settings sections the app has itself, before and after the panes': Home Assistant and the
 * dashboard, the screen ("Panel"), About. Network, Log and Security have nothing yet. Values are read
 * when the menu opens; everything is saved with "Save & restart", the same saves as before.
 */
class CoreSettings(
    private val prefs: KioskPrefs,
    private val version: String,
    /** The screen and sensor status line under the version. */
    private val status: () -> String,
    /** The device's own settings app, when it has one (the frame's systemSettings). */
    private val deviceSettings: SettingsItem.Link?,
    private val licences: SettingsItem.Link,
) {
    fun sections(): Map<SettingsPlace, SettingsSection> = mapOf(
        SettingsPlace.DASHBOARD to SettingsSection("dashboard", "Home Assistant & dashboard", listOf(
            text("Dashboard URL", "auto (find Home Assistant)", TextKind.ADDRESS, { prefs.urlSetting }) {
                prefs.urlSetting = it.ifBlank { KioskPrefs.AUTO }
            },
            text("Zoom (1.0 – 2.0)", "auto (device density)", TextKind.DECIMAL, { if (prefs.zoom > 0f) prefs.zoom.toString() else "" }, narrow = true) {
                prefs.zoom = it.toFloatOrNull() ?: 0f
            },
            text("Extra secure-context hosts", "e.g. homeassistant.local,192.168.1.100", TextKind.ADDRESS, { prefs.extraSecureHosts }, narrow = true) {
                prefs.extraSecureHosts = it
            },
            switch("Hide Home Assistant's header & sidebar", "The dashboard fills the screen", { prefs.hideHeader }) {
                prefs.hideHeader = it
                prefs.hideSidebar = it
            },
        )),
        SettingsPlace.SCREEN to SettingsSection("screen", "Panel", listOfNotNull(
            switch("Screen follows presence", "Off when idle, on when someone comes close", { prefs.screenMode == "presence" }) {
                prefs.screenMode = if (it) "presence" else "always"
            },
            text("Screen off after idle (s, 0 = never)", "120", TextKind.NUMBER, { prefs.idleOffSec.toString() }, narrow = true) {
                prefs.idleOffSec = it.toIntOrNull() ?: 120
            },
            text("At night: off after (s)", "20", TextKind.NUMBER, { prefs.nightIdleOffSec.toString() }, narrow = true) {
                prefs.nightIdleOffSec = it.toIntOrNull() ?: 20
            },
            text("Night window", "22:00-07:00 (empty = off)", TextKind.PLAIN, { prefs.nightWindow }, narrow = true) {
                prefs.nightWindow = it
            },
            text("Presence range (m, 0.1 – 2.5)", "firmware default (1.5 m)", TextKind.DECIMAL,
                { if (prefs.presenceRangeM > 0f) prefs.presenceRangeM.toString() else "" }, narrow = true) {
                prefs.presenceRangeM = it.toFloatOrNull() ?: 0f
            },
            switch("Auto brightness", "Follows the light sensor", { prefs.autoBrightness }) { prefs.autoBrightness = it },
            deviceSettings,
        )),
        SettingsPlace.ABOUT to SettingsSection("about", "About", listOf(
            SettingsItem.Info(title = { "S1 Kiosk $version" }, detail = status),
            licences,
        )),
    )

    private fun text(title: String, hint: String, kind: TextKind, value: () -> String, narrow: Boolean = false, save: (String) -> Unit) =
        SettingsItem.Text(title, hint, kind, value, narrow = narrow, save = { v, done -> save(v); done(null) })

    private fun switch(title: String, detail: String, value: () -> Boolean, save: (Boolean) -> Unit) =
        SettingsItem.Switch(title, detail, value) { v, done -> save(v); done(null) }

    companion object {
        const val LICENCES_TITLE = "Open source licences"
        const val LICENCES_DETAIL = "The open-source software in the app and the radio package"
    }
}
