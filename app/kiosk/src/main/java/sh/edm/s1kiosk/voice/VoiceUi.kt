package sh.edm.s1kiosk.voice

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What the voice overlay shows, kept by KioskService (main thread). [state] is the same string as
 * `KioskService.voiceState`; [seq] counts state events so a repeated, identical error still shows.
 */
data class VoiceUi(
    val state: String = "off",
    /** Final transcript of the current run ("" until STT_END; cleared when a run starts). */
    val heard: String = "",
    /** Text being spoken (TTS_START or an announcement); "" if none. */
    val reply: String = "",
    val announcement: Boolean = false,
    /** The current error comes from the panel (mic, models), not from Home Assistant. */
    val errorLocal: Boolean = false,
    val seq: Int = 0,
) {
    /** Applies [e]; returns the new snapshot. */
    fun on(e: VoiceEvent): VoiceUi = when (e) {
        is VoiceEvent.Heard -> copy(heard = e.text.trim())
        is VoiceEvent.Reply -> copy(
            reply = e.text.trim(), announcement = e.announcement,
            heard = if (e.announcement) "" else heard,
        )
        // A new run (wake word, follow-up question): forget the last exchange.
        is VoiceEvent.WakeDetected -> VoiceUi("wake", seq = seq + 1)
        is VoiceEvent.Listening -> VoiceUi("listening", seq = seq + 1)
        is VoiceEvent.Thinking -> copy(state = "thinking", seq = seq + 1)
        is VoiceEvent.Speaking -> copy(state = "speaking", seq = seq + 1)
        is VoiceEvent.Idle -> copy(state = "idle", seq = seq + 1)
        is VoiceEvent.Error -> copy(state = "error: ${e.message}", errorLocal = e.local, seq = seq + 1)
    }
}

/** Live audio levels for the voice overlay: written by audio threads, read by its frame loop. */
object VoiceLevels {
    /** Mic level 0..1 of the latest frame streamed to Home Assistant ([AudioLevel.fromRms]); 0 when not streaming. */
    @Volatile var mic: Float = 0f

    /** The voice player (TTS response, announcement) is audibly playing. */
    @Volatile var ttsPlaying: Boolean = false
}

/** Audio level → motion (SPEC §5). Pure. */
object AudioLevel {
    /** RMS of s16 samples, on the 0..32768 scale. */
    fun rms(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var acc = 0.0
        for (v in frame) acc += v.toDouble() * v
        return sqrt(acc / frame.size)
    }

    /** `db = 20·log10(rms/32768)`, then −55 dBFS → 0 and −15 dBFS → 1, clamped. */
    fun fromRms(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms / 32768.0)
        return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
    }

    /** Synthetic speech-like level for URL playback, where samples aren't available (t in ms). */
    fun ttsPulse(tMs: Double): Float = (0.35 + 0.25 * sin(tMs * 0.027 + 2 * sin(tMs * 0.0041))).toFloat()

    /** Floor while listening, so the orb never looks dead. */
    const val LISTENING_FLOOR = 0.06f
}

/** One-pole smoothing with separate attack and release time constants. Pure. */
class LevelSmoother(private val attackMs: Float = 60f, private val releaseMs: Float = 220f) {
    var value = 0f
        private set

    fun step(target: Float, dtMs: Float): Float {
        if (dtMs <= 0f) return value
        val tau = if (target > value) attackMs else releaseMs
        value += (target - value) * (1f - exp(-dtMs / tau))
        return value
    }

    fun reset() { value = 0f }
}

/** Text rules of the voice overlay (SPEC §6, §2). Pure. */
object VoiceTexts {
    const val PROMPT = "How can I help?"
    const val ANNOUNCEMENT = "Announcement"

    /** “Heard text”, first letter capitalised, no full stop added; "" stays "". */
    fun quote(heard: String): String {
        val t = heard.trim()
        if (t.isEmpty()) return ""
        return "“" + t.replaceFirstChar { it.uppercaseChar() } + "”"
    }

    /** Maps a raw error (VoiceEvent.Error message, or `voiceState` without "error: ") to plain English. */
    fun friendlyError(raw: String, local: Boolean): String {
        if (local) return "The microphone isn't working"
        val msg = raw.removePrefix("error:").trim()
        val code = msg.substringBefore(':').trim()
        return when {
            msg.startsWith("Home Assistant is not connected") ||
                msg.startsWith("Home Assistant did not start the pipeline") ||
                msg.startsWith("Home Assistant could not start the pipeline") ||
                msg.startsWith("Could not request a pipeline run") -> "Can't reach Home Assistant"
            code == "stt-no-text-recognized" || code == "stt-stream-failed" -> "I didn't catch that"
            code == "intent-failed" || code == "tts-failed" -> "Home Assistant didn't answer"
            else -> "Something went wrong"
        }
    }
}

/**
 * The main text's size steps (SPEC §2): 36 sp on at most 2 lines, else 30 sp on 2, else 26 sp on 3
 * (then ellipsised). [lineCount] measures the text at a size, unconstrained. Pure.
 */
object MainTextFit {
    data class Fit(val sizeSp: Float, val maxLines: Int)

    fun choose(lineCount: (Float) -> Int): Fit = when {
        lineCount(36f) <= 2 -> Fit(36f, 2)
        lineCount(30f) <= 2 -> Fit(30f, 2)
        else -> Fit(26f, 3)
    }
}

/** Two-view text cross-fade (SPEC §4), with k the eased progress 0..1 as in the prototype. Pure. */
object TextFade {
    fun outgoingAlpha(k: Float, ease: (Float) -> Float): Float = 1f - ease(minOf(1f, 1.8f * k))
    fun incomingAlpha(k: Float, ease: (Float) -> Float): Float = ease(((k - 0.35f) / 0.65f).coerceIn(0f, 1f))
}

/**
 * A 0..1 presence that eases towards a target, in [inMs] when rising and [outMs] when falling; a
 * new target starts from wherever it is (no jumps). Pure; times in ms.
 */
class Fader(private val inMs: Float, private val outMs: Float) {
    private var from = 0f
    private var to = 0f
    private var start = 0L
    var value = 0f
        private set
    val target: Float get() = to
    val settled: Boolean get() = value == to

    fun set(target: Float, now: Long) {
        if (target == to) return
        from = value
        to = target
        start = now
    }

    fun snap(v: Float) { from = v; to = v; value = v }

    fun update(now: Long, ease: (Float) -> Float): Float {
        if (value == to) return value
        val dur = if (to > from) inMs else outMs
        val k = if (dur <= 0f) 1f else ((now - start) / dur).coerceIn(0f, 1f)
        value = if (k >= 1f) to else from + (to - from) * ease(k)
        return value
    }
}
