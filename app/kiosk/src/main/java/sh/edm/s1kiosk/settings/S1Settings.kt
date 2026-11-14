package sh.edm.s1kiosk.settings

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import dev.glassworks.frame.openSystemSettings
import dev.glassworks.frame.systemSettings
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.paneSections
import dev.glassworks.pane.settingsOrder
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.Licences

/**
 * The panel's settings: the one model the menu ([KioskSettingsOverlay]) and the Manage page both draw
 * and change (#242). The core's sections and the panes', in the order every settings surface uses. Made
 * again each time it's read, so it has the values and the running panes of now.
 *
 * [fullScreen]: what the running dashboard does when Full screen changes (the menu pushes the page's
 * config itself; the Manage page asks the activity).
 */
object S1Settings {

    fun model(
        runtime: GlassworksRuntime, prefs: KioskPrefs, panes: List<PaneDefinition>,
        /** The frame has its own settings app ([dev.glassworks.frame.Frame.systemSettings]): resolved by the caller, it costs a lookup. */
        deviceSettings: Boolean,
        fullScreen: () -> Unit,
    ): KioskSettingsOverlay.Model {
        val frame = runtime.frame
        val core = CoreSettings(
            prefs, BuildConfig.VERSION_NAME,
            status = { runtime.screen.statusLine() + " · voice=" + runtime.voiceState },
            deviceSettings = if (!deviceSettings) null else SettingsItem.Link(SettingsMenu.AQARA_TITLE, SettingsMenu.AQARA_DETAIL) { c ->
                if (!frame.openSystemSettings(c)) Toast.makeText(c, "No settings app on this panel", Toast.LENGTH_LONG).show()
            },
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) { c -> (c as? Activity)?.let(Licences::show) },
            live = CoreSettings.Live(
                fullScreen = fullScreen,
                presenceRange = { runtime.screen.applyPresenceRange() },
                brightness = { runtime.screen.refreshBrightness() },
            ),
        )
        // One pane's failure leaves the others' pages.
        val sections = panes.flatMap { d ->
            runCatching { paneSections(listOf(d), runtime::runningPane) }.onFailure { Log.w(TAG, "${d.id}'s settings failed", it) }.getOrDefault(emptyList())
        }
        return KioskSettingsOverlay.Model(settingsOrder(core.sections(), sections), BuildConfig.VERSION_NAME, sections.map { it.id }.toSet())
    }

    /** Whether [context]'s frame has its own settings app, for [model]. */
    fun hasDeviceSettings(runtime: GlassworksRuntime, context: Context): Boolean = runtime.frame.systemSettings(context) != null

    private const val TAG = "S1Kiosk.Settings"
}
