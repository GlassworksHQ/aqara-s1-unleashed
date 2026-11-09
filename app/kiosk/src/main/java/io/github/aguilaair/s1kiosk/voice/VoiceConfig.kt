package io.github.aguilaair.s1kiosk.voice

import android.content.Context
import java.io.File

/**
 * Configuration of the Wyoming voice satellite.
 *
 * @property enabled       When false, [VoiceSatellite.start] does nothing.
 * @property port          TCP port of the Wyoming server (Home Assistant connects to it).
 * @property name          Satellite name shown in Home Assistant.
 * @property area          Optional area name advertised to Home Assistant.
 * @property wakeModel     Wake word model file name inside [modelsDir] (openWakeWord TFLite).
 * @property threshold     Wake word score (0..1) that counts as a detection.
 * @property refractoryMs  After a detection, ignore further detections for this long.
 * @property modelsDir     Directory holding `melspectrogram.tflite`, `embedding_model.tflite`
 *                         and the wake model. `null` means [defaultModelsDir], i.e.
 *                         `/sdcard/Android/data/io.github.aguilaair.s1kiosk/files/models/`
 *                         (writable with `adb push`, no root needed).
 * @property micGain       Linear gain applied to microphone samples (1.0 = unchanged), used both
 *                         for wake word detection and for the audio streamed to Home Assistant.
 * @property zeroconf      Advertise `_wyoming._tcp` over mDNS so Home Assistant discovers it.
 * @property maxStreamMs   Safety cap on how long one command is streamed to Home Assistant.
 */
data class VoiceConfig(
    val enabled: Boolean = true,
    val port: Int = 10700,
    val name: String = "S1 Panel",
    val area: String? = null,
    val wakeModel: String = "hey_jarvis_v0.1.tflite",
    val threshold: Float = 0.5f,
    val refractoryMs: Long = 3_000L,
    val modelsDir: File? = null,
    val micGain: Float = 1.0f,
    val zeroconf: Boolean = true,
    val maxStreamMs: Long = 15_000L,
) {
    /** [modelsDir], or the default app-specific external directory. */
    fun resolveModelsDir(context: Context): File = modelsDir ?: defaultModelsDir(context)

    companion object {
        fun defaultModelsDir(context: Context): File =
            context.getExternalFilesDir("models") ?: File(context.filesDir, "models")
    }
}

/**
 * Status updates from [VoiceSatellite], for UI. Delivered on a background thread:
 * post to the main thread before touching views.
 */
sealed class VoiceEvent {
    /** The local wake word fired. */
    data object WakeDetected : VoiceEvent()

    /** Streaming the user's speech to Home Assistant. */
    data object Listening : VoiceEvent()

    /** Speech ended; Home Assistant is transcribing / running the agent / synthesizing. */
    data object Thinking : VoiceEvent()

    /** Playing the TTS response (or an announcement). */
    data object Speaking : VoiceEvent()

    /** Waiting for the wake word. */
    data object Idle : VoiceEvent()

    /** Something went wrong (missing models, mic failure, pipeline error, ...). */
    data class Error(val message: String) : VoiceEvent()
}
