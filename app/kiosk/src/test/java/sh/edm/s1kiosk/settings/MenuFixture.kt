package sh.edm.s1kiosk.settings

import android.content.Context
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.settingsOrder
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.RadioDefinition

/** The S1's settings menu as MainActivity builds it, with illustrative values, for the mock-ups and view tests. */
object MenuFixture {
    fun model(context: Context, radio: RadioDefinition, deviceSettings: Boolean = true, version: String = "0.4.3", status: String = "screen on"): KioskSettingsOverlay.Model {
        val core = CoreSettings(
            KioskPrefs(context), version, status = { status },
            deviceSettings = if (deviceSettings) SettingsItem.Link(SettingsMenu.AQARA_TITLE, SettingsMenu.AQARA_DETAIL) {} else null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        )
        return KioskSettingsOverlay.Model(settingsOrder(core.sections(), radio.settings()), version)
    }
}
