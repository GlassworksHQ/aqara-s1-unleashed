package sh.edm.s1kiosk.voice

import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.PaneDefinition

/** The voice assistant ([AssistPane]) for the whole process: its Panes card and adb extras (app/README.md). */
class AssistDefinition(prefs: VoicePrefs) : PaneDefinition {
    override val id = "assist"

    // PanelDevice reads `enabled` once per start, so a change restarts the app (as `voice_enabled` does).
    override val setupChoice = PaneChoice(
        title = "Voice", detail = "Assist and timers", on = { prefs.enabled }, restartsApp = true,
        apply = { on -> prefs.enabled = on; null },
    )

    override val extras = listOf(
        // PanelDevice reads it once per start: voice adds or drops the microphone and its entities.
        IntentExtra.bool("voice_enabled", default = true, restartsApp = true) { prefs.enabled = it },
        // The rest is applied live by PanelDevice (it listens to the prefs).
        // A wake word id (okay_nabu, hey_jarvis_v0.1, ...); an old model file name is migrated.
        IntentExtra.string("wake_model") { prefs.wakeWord = WakeWords.migrate(it) },
        // The active word's threshold; another type than float resets it to the word's default.
        IntentExtra.float("wake_threshold", default = Float.NaN) { prefs.threshold = if (it.isNaN()) prefs.defaultThreshold else it },
        IntentExtra.float("mic_gain", default = 1f) { prefs.micGain = it },
        IntentExtra.bool("mic_muted", default = false) { prefs.micMuted = it },
    )
}
