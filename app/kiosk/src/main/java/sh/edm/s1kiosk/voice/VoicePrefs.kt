/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.voice

import android.content.SharedPreferences

/**
 * The voice assistant's settings, in the file `PaneContext.prefs("assist")` gives it (`kiosk.xml` on
 * the S1, where they were before panes).
 */
class VoicePrefs(private val sp: SharedPreferences) {

    /** Voice on or off ([AssistDefinition.switch] writes it). */
    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, true)

    /**
     * The openWakeWord model file S1 Kiosk 0.4 and earlier saved, e.g. `hey_jarvis_v0.1.tflite`.
     * Only read, to migrate it to [wakeWord]; never written now, so a downgrade keeps its word.
     */
    val legacyWakeModel: String
        get() = sp.getString(KEY_WAKE_MODEL, "hey_jarvis_v0.1.tflite") ?: ""

    /**
     * The wake word id Home Assistant picked ([WakeWords]); "" = no wake word. Until the first pick
     * it is [legacyWakeModel] migrated ([WakeWords.migrate]). Picking another word drops the
     * threshold set for the old one ([wakeCutoff]).
     */
    var wakeWord: String
        get() = sp.getString(KEY_WAKE_WORD, null) ?: WakeWords.migrate(legacyWakeModel)
        set(v) {
            val id = v.trim()
            val e = sp.edit().putString(KEY_WAKE_WORD, id)
            if (id != wakeWord) e.remove(KEY_WAKE_CUTOFF)
            e.apply()
        }

    /** openWakeWord's score threshold, for a legacy word. */
    var wakeThreshold: Float
        get() = sp.getFloat(KEY_WAKE_THRESHOLD, 0.5f)
        set(v) = sp.edit().putFloat(KEY_WAKE_THRESHOLD, v).apply()

    /** microWakeWord's probability cutoff set for the active word; until then the model's own. */
    var wakeCutoff: Float
        get() = sp.getFloat(KEY_WAKE_CUTOFF, defaultCutoff())
        set(v) = sp.edit().putFloat(KEY_WAKE_CUTOFF, v).apply()

    /**
     * The `wake_word_threshold` entity: the active word's threshold. For a microWakeWord word it is
     * the probability cutoff for the mean of the model's last outputs ([wakeCutoff]); for a legacy
     * openWakeWord word, openWakeWord's score threshold ([wakeThreshold]).
     */
    var threshold: Float
        get() = if (WakeWords.parse(wakeWord) is WakeWord.Legacy) wakeThreshold else wakeCutoff
        set(v) = if (WakeWords.parse(wakeWord) is WakeWord.Legacy) wakeThreshold = v else wakeCutoff = v

    /** The active word's own threshold: its model's cutoff, or 0.5 for openWakeWord. */
    val defaultThreshold: Float
        get() = if (WakeWords.parse(wakeWord) is WakeWord.Legacy) 0.5f else defaultCutoff()

    private fun defaultCutoff(): Float {
        val model = (WakeWords.parse(wakeWord) as? WakeWord.Micro)?.model ?: WakeWords.MICRO.first { it.id == WakeWords.DEFAULT }
        return model.cutoff
    }

    /** Play a short chime when the wake word is detected. */
    var wakeSound: Boolean
        get() = sp.getBoolean("wake_sound", true)
        set(v) = sp.edit().putBoolean("wake_sound", v).apply()

    /** Show the "Listening… / Thinking…" overlay on screen during voice runs. */
    var overlay: Boolean
        get() = sp.getBoolean("voice_overlay", true)
        set(v) = sp.edit().putBoolean("voice_overlay", v).apply()

    var micGain: Float
        get() = sp.getFloat(KEY_MIC_GAIN, 1.0f)
        set(v) = sp.edit().putFloat(KEY_MIC_GAIN, v).apply()

    /** Mute: the microphone is released entirely (no wake word, no streaming). */
    var micMuted: Boolean
        get() = sp.getBoolean(KEY_MIC_MUTED, false)
        set(v) = sp.edit().putBoolean(KEY_MIC_MUTED, v).apply()

    companion object {
        const val KEY_ENABLED = "voice_enabled"
        const val KEY_WAKE_MODEL = "wake_model"
        const val KEY_WAKE_WORD = "wake_word"
        const val KEY_WAKE_CUTOFF = "wake_cutoff"
        const val KEY_WAKE_THRESHOLD = "wake_threshold"
        const val KEY_MIC_GAIN = "mic_gain"
        const val KEY_MIC_MUTED = "mic_muted"
    }
}
