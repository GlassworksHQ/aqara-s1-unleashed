package sh.edm.s1kiosk.settings

import android.content.Context
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import dev.glassworks.diagnostics.DiagnosticsDefinition
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.WallBaseDefinition
import sh.edm.s1kiosk.frame.TimeZoneDefinition
import sh.edm.s1kiosk.radio.RadioDefinition

/**
 * The S1's settings menu as MainActivity builds it, with illustrative values, for the mock-ups and view
 * tests: the core's sections, then the panes' with their on/off ([paneSections]), and the panes' ids
 * (#63 D6: the rail's Panes group and "Panes ›").
 */
object MenuFixture {
    /** Voice and Music as the app has them: a card on setup's Panes step and no settings of their own. */
    fun cardOnly(id: String, title: String, detail: String, on: () -> Boolean = { true }, apply: (Boolean) -> String? = { null }) =
        object : PaneDefinition {
            override val id = id
            override val setupChoice = PaneChoice(title, detail, on = on, apply = apply)
        }

    /** The S1's panes after the radio, as the app has them (#67): the wall base, the time zone and diagnostics, each with only its on/off. */
    fun switchOnly(context: Context): List<PaneDefinition> {
        val prefs = KioskPrefs(context)
        return listOf(
            WallBaseDefinition(prefs.paneFile("wall-base")), TimeZoneDefinition(prefs.paneFile("time-zone")),
            DiagnosticsDefinition(prefs.paneFile("diagnostics")),
        )
    }

    fun model(
        context: Context, radio: RadioDefinition, deviceSettings: Boolean = true, version: String = "0.4.3", status: String = "screen on",
        panes: List<PaneDefinition> = listOf(cardOnly("voice", "Voice", "Assist and timers"), cardOnly("sendspin", "Music", "A Sendspin speaker"), radio),
        live: CoreSettings.Live = CoreSettings.Live(),
        running: (String) -> Pane? = { null },
    ): KioskSettingsOverlay.Model {
        val core = CoreSettings(
            KioskPrefs(context), version, status = { status },
            deviceSettings = if (deviceSettings) SettingsItem.Link(SettingsMenu.AQARA_TITLE, SettingsMenu.AQARA_DETAIL) {} else null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
            live = live,
        )
        val sections = paneSections(panes, running)
        return KioskSettingsOverlay.Model(settingsOrder(core.sections(), sections), version, sections.map { it.id }.toSet())
    }
}
