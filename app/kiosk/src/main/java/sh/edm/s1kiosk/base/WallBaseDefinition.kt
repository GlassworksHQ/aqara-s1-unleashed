package sh.edm.s1kiosk.base

import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition

/** The wall base ([WallBasePane]) for the whole process: its adb extras (frames/aqara-s1-plus/research/bases.md). */
class WallBaseDefinition(prefs: WallBasePrefs) : PaneDefinition {
    override val id = "wall-base"

    override val extras = listOf(
        // auto|uart|localservice
        IntentExtra.string("base_backend") { prefs.backend = it },
        // "" (/dev/ttyS3) or tcp:host:port (tools/bracket/base_emulator.py)
        IntentExtra.string("base_transport") { prefs.transport = it },
    )
}
