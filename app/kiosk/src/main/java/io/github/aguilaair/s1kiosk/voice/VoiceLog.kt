package io.github.aguilaair.s1kiosk.voice

/** Tiny log facade: android.util.Log on the device, stdout in JVM unit tests. */
internal object VoiceLog {
    private const val TAG = "S1Voice"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true

    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

internal fun nowMs(): Long = System.nanoTime() / 1_000_000L
