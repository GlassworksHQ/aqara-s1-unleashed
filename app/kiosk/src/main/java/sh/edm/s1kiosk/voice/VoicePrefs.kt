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

    /** Voice on or off. The shell reads it once per process: a change restarts the app. */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** Wake model file in the models dir; "" = no wake word (set from HA's wake word select). */
    var wakeModel: String
        get() = sp.getString(KEY_WAKE_MODEL, "hey_jarvis_v0.1.tflite") ?: ""
        set(v) = sp.edit().putString(KEY_WAKE_MODEL, v.trim()).apply()

    var wakeThreshold: Float
        get() = sp.getFloat(KEY_WAKE_THRESHOLD, 0.5f)
        set(v) = sp.edit().putFloat(KEY_WAKE_THRESHOLD, v).apply()

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
        const val KEY_WAKE_THRESHOLD = "wake_threshold"
        const val KEY_MIC_GAIN = "mic_gain"
        const val KEY_MIC_MUTED = "mic_muted"
    }
}
