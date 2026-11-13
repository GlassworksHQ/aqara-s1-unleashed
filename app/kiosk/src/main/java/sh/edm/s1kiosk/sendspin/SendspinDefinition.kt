package sh.edm.s1kiosk.sendspin

import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneDefinition

/** The Sendspin player ([SendspinPane]) for the whole process: its Panes card and adb extras (app/README.md). */
class SendspinDefinition(prefs: SendspinPrefs) : PaneDefinition {
    override val id = "sendspin"

    // SendspinPane follows the setting live.
    override val setupChoice = PaneChoice(
        title = "Music", detail = "A Sendspin speaker", on = { prefs.enabled },
        apply = { on -> prefs.enabled = on; null },
    )

    override val extras = listOf(
        IntentExtra.bool("sendspin_enabled", default = true) { prefs.enabled = it },
        IntentExtra.string("sendspin_server") { prefs.server = it },
        IntentExtra.bool("sendspin_unpaired_access", default = false) { prefs.unpairedAccess = it },
        IntentExtra.bool("sendspin_flac", default = true) { prefs.flac = it },
    )
}
