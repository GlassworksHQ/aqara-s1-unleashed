package sh.edm.s1kiosk.voice

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch

/** The voice assistant ([AssistPane]) for the whole process: its on/off and adb extras (app/README.md). [file] is its settings file. */
class AssistDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "assist"

    private val prefs = VoicePrefs(file)

    // PanelDevice starts and stops the assistant live, and gives Home Assistant its voice features again.
    override val switch = PaneSwitch(
        file, VoicePrefs.KEY_ENABLED, default = true, title = "Voice", detail = "Assist and timers",
        objectId = "voice", icon = "mdi:microphone-message", inSetup = true,
    )

    override val extras = listOf(
        // Applied live by PanelDevice (it listens to the prefs).
        // A wake word id (okay_nabu, hey_jarvis_v0.1, ...); an old model file name is migrated.
        IntentExtra.string("wake_model") { prefs.wakeWord = WakeWords.migrate(it) },
        // The active word's threshold; another type than float resets it to the word's default.
        IntentExtra.float("wake_threshold", default = Float.NaN) { prefs.threshold = if (it.isNaN()) prefs.defaultThreshold else it },
        IntentExtra.float("mic_gain", default = 1f) { prefs.micGain = it },
        IntentExtra.bool("mic_muted", default = false) { prefs.micMuted = it },
    )
}
