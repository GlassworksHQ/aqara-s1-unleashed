package sh.edm.s1kiosk.voice

import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneDefinition

/** The voice assistant ([AssistPane]) for the whole process: its adb extras (app/README.md). */
class AssistDefinition(prefs: VoicePrefs) : PaneDefinition {
    override val id = "assist"

    override val extras = listOf(
        // PanelDevice reads it once per start: voice adds or drops the microphone and its entities.
        IntentExtra.bool("voice_enabled", default = true, restartsApp = true) { prefs.enabled = it },
        // The rest is applied live by PanelDevice (it listens to the prefs).
        IntentExtra.string("wake_model") { prefs.wakeModel = it },
        IntentExtra.float("wake_threshold", default = 0.5f) { prefs.wakeThreshold = it },
        IntentExtra.float("mic_gain", default = 1f) { prefs.micGain = it },
        IntentExtra.bool("mic_muted", default = false) { prefs.micMuted = it },
    )
}
