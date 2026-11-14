package sh.edm.s1kiosk.voice

import android.content.SharedPreferences
import dev.glassworks.pane.IntentExtra
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneSwitch
import dev.glassworks.pane.PanelTimer
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import java.util.Locale

/** The voice assistant ([AssistPane]) for the whole process: its on/off and adb extras (app/README.md). [file] is its settings file. */
class AssistDefinition(file: SharedPreferences) : PaneDefinition {
    override val id = "assist"

    private val prefs = VoicePrefs(file)

    // PanelDevice starts and stops the assistant live, and gives Home Assistant its voice features again.
    override val switch = PaneSwitch(
        file, VoicePrefs.KEY_ENABLED, default = true, title = "Voice", detail = "Assist and timers",
        objectId = "voice", icon = "mdi:microphone-message", inSetup = true, voiceAssistant = true,
        offNote = "Voice is off. The microphone is closed, and its entities are unavailable in Home Assistant until you turn it on.",
    )

    /**
     * What the assistant listens for and how (#212): the wake word and its threshold, the microphone,
     * what shows on screen, and the satellite and timers Home Assistant sees. Each change applies at once,
     * and Home Assistant's entities follow, as with the adb extras.
     */
    override fun settings(running: Pane?): List<SettingsSection> {
        val p = running as? AssistPane ?: return emptyList()
        fun done(d: (String?) -> Unit) = d(null)
        return listOf(SettingsSection(id, "Voice", listOf(
            SettingsItem.Status(text = { status(prefs.micMuted, prefs.wakeWord) }, ok = { !prefs.micMuted && prefs.wakeWord.isNotEmpty() }),
            SettingsItem.Group("Listening", listOf(
                SettingsItem.Choice("Wake word", { prefs.wakeWord }, { wakeWords(prefs.wakeWord) }) { v, d -> p.useWakeWord(v); done(d) },
                SettingsItem.Slider("Threshold", { prefs.threshold }, 0.1f, 0.99f, 0.01f, { v ->
                    String.format(Locale.ROOT, "%.2f", v) + if (kotlin.math.abs(v - prefs.defaultThreshold) < 0.005f) " · the word's own" else ""
                }) { v, d -> prefs.threshold = v; done(d) },
            )),
            SettingsItem.Group("Microphone", listOf(
                SettingsItem.Switch("Mute microphone", "The wake word stops listening", { prefs.micMuted }) { v, d -> prefs.micMuted = v; done(d) },
                SettingsItem.Slider("Mic gain", { prefs.micGain }, 0.5f, 4f, 0.1f, { String.format(Locale.ROOT, "%.1f×", it) }) { v, d -> prefs.micGain = v; done(d) },
            )),
            SettingsItem.Group("On screen", listOf(
                SettingsItem.Switch("Wake sound", "A short chime when it hears the wake word", { prefs.wakeSound }) { v, d -> prefs.wakeSound = v; done(d) },
                SettingsItem.Switch("Voice overlay", "Shows what you said and the answer", { prefs.overlay }) { v, d -> prefs.overlay = v; done(d) },
            )),
            SettingsItem.States(dev.glassworks.pane.IN_HOME_ASSISTANT) {
                listOf(SettingsItem.StateRow("Assist satellite", p.satellite), SettingsItem.StateRow("Timers running", timers(p.timers)))
            },
        )))
    }

    companion object {
        /** The line under the heading. */
        fun status(muted: Boolean, wakeWord: String): String = when {
            muted -> "Microphone muted"
            wakeWord.isEmpty() -> "No wake word: start Assist from Home Assistant"
            else -> "Listening for “${WakeWords.parse(wakeWord)?.phrase}”"
        }

        /** The words the panel has, then no wake word; a word only openWakeWord has stays while it is the one in use. */
        fun wakeWords(current: String): List<Pair<String, String>> =
            WakeWords.MICRO.map { it.id to it.phrase } +
                listOfNotNull((WakeWords.parse(current) as? WakeWord.Legacy)?.let { it.id to "${it.phrase} (openWakeWord)" }) +
                ("" to "None")

        /** `None`, or `2 · Pasta, 6 min left`: how many, and the first one's name and time left. */
        fun timers(list: List<PanelTimer>): String {
            val t = list.firstOrNull() ?: return "None"
            val left = (t.secondsLeft + 59) / 60
            return "${list.size} · ${t.name.ifEmpty { "Timer" }}, $left min left" + if (t.active) "" else " (paused)"
        }
    }

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
