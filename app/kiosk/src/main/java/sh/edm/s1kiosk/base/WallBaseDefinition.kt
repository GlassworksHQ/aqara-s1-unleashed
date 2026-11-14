package sh.edm.s1kiosk.base

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/** The wall base ([WallBasePane]) for the whole process: its on/off and adb extras (frames/aqara-s1-plus/research/bases.md). */
class WallBaseDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "wall-base"

    private val prefs = WallBasePrefs(file)

    override val switch = PaneSwitch(
        file, WallBasePrefs.KEY_ENABLED, default = true, title = "Wall base", detail = "The S1's wired base",
        objectId = "wall_base", icon = "mdi:light-switch", inSetup = true,
    )

    override val extras = listOf(
        // auto|uart|localservice
        IntentExtra.string("base_backend") { prefs.backend = it },
        // "" (/dev/ttyS3) or tcp:host:port (tools/bracket/base_emulator.py)
        IntentExtra.string("base_transport") { prefs.transport = it },
    )
}
