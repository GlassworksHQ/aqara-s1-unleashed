package sh.edm.s1kiosk.sendspin

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/** The Sendspin player ([SendspinPane]) for the whole process: its on/off and adb extras (app/README.md). [file] is its settings file. */
class SendspinDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "sendspin"

    private val prefs = SendspinPrefs(file)

    // The Home Assistant switch the panel has always had: its id, name and icon stay.
    override val switch = PaneSwitch(
        file, SendspinPrefs.KEY_ENABLED, default = true, title = "Music", detail = "A Sendspin speaker",
        objectId = "sendspin", entityName = "Music Assistant player (Sendspin)", icon = "mdi:speaker-wireless",
        inSetup = true,
    )

    override val extras = listOf(
        IntentExtra.string("sendspin_server") { prefs.server = it },
        IntentExtra.bool("sendspin_unpaired_access", default = false) { prefs.unpairedAccess = it },
        IntentExtra.bool("sendspin_flac", default = true) { prefs.flac = it },
    )
}
