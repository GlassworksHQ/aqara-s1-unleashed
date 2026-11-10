package sh.edm.s1kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.ui.Soft
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceUiTest {

    private val ease: (Float) -> Float = Soft::easeInOutCubic

    // ---------------------------------------------------------------- §5 audio level

    private fun rmsAt(db: Double) = 32768.0 * 10.0.pow(db / 20)

    @Test
    fun levelMapsMinus55To0AndMinus15To1() {
        assertEquals(0f, AudioLevel.fromRms(0.0), 0f)
        assertEquals(0f, AudioLevel.fromRms(rmsAt(-70.0)), 0f)
        assertEquals(0f, AudioLevel.fromRms(rmsAt(-55.0)), 1e-4f)
        assertEquals(0.5f, AudioLevel.fromRms(rmsAt(-35.0)), 1e-4f)
        assertEquals(1f, AudioLevel.fromRms(rmsAt(-15.0)), 1e-4f)
        assertEquals(1f, AudioLevel.fromRms(32768.0), 0f)
    }

    @Test
    fun rmsOfASine() {
        val amp = 10_000.0
        val frame = ShortArray(1280) { (amp * sin(2 * PI * 440 * it / 16_000.0)).toInt().toShort() }
        assertEquals(amp / sqrt(2.0), AudioLevel.rms(frame), amp * 0.01)
        assertEquals(0.0, AudioLevel.rms(ShortArray(0)), 0.0)
    }

    @Test
    fun smootherAttacksFastAndReleasesSlowly() {
        val s = LevelSmoother(attackMs = 60f, releaseMs = 220f)
        repeat(6) { s.step(1f, 10f) } // 60 ms of attack: one time constant
        assertEquals(1 - 1 / Math.E, s.value.toDouble(), 0.01)
        repeat(100) { s.step(1f, 16f) }
        assertEquals(1f, s.value, 1e-3f)
        repeat(22) { s.step(0f, 10f) } // 220 ms of release: one time constant
        assertEquals(1 / Math.E, s.value.toDouble(), 0.01)
        // Frame-rate independent: one 100 ms step == ten 10 ms steps.
        val a = LevelSmoother(); a.step(1f, 100f)
        val b = LevelSmoother(); repeat(10) { b.step(1f, 10f) }
        assertEquals(a.value, b.value, 1e-5f)
        assertEquals(b.value, b.step(0.3f, 0f), 0f)
    }

    @Test
    fun ttsPulseLooksLikeSpeechWithinRange() {
        var lo = 1f; var hi = 0f
        for (t in 0 until 10_000 step 5) {
            val v = AudioLevel.ttsPulse(t.toDouble())
            lo = minOf(lo, v); hi = maxOf(hi, v)
        }
        assertTrue(lo >= 0.0999f && hi <= 0.6001f)
        assertTrue("it moves", hi - lo > 0.4f)
    }

    // ---------------------------------------------------------------- §6 texts

    @Test
    fun errorsBecomePlainEnglish() {
        val e = VoiceTexts::friendlyError
        assertEquals("Can't reach Home Assistant", e("error: Home Assistant is not connected", false))
        assertEquals("Can't reach Home Assistant", e("Home Assistant did not start the pipeline", false))
        assertEquals("Can't reach Home Assistant", e("error: Home Assistant could not start the pipeline", false))
        assertEquals("Can't reach Home Assistant", e("Could not request a pipeline run", false))
        assertEquals("I didn't catch that", e("error: stt-no-text-recognized: No text recognized", false))
        assertEquals("I didn't catch that", e("stt-stream-failed: boom", false))
        assertEquals("Home Assistant didn't answer", e("error: intent-failed: Unexpected error", false))
        assertEquals("Home Assistant didn't answer", e("tts-failed: nope", false))
        assertEquals("The microphone isn't working", e("error: Cannot open the microphone (RECORD_AUDIO granted? mic busy?)", true))
        assertEquals("The microphone isn't working", e("Wake word init failed: x", true))
        assertEquals("Something went wrong", e("error: pipeline-not-found: Pipeline not found", false))
        assertEquals("Something went wrong", e("", false))
    }

    @Test
    fun heardTextIsQuotedAndCapitalised() {
        assertEquals("“Turn off the bedroom lights”", VoiceTexts.quote(" turn off the bedroom lights "))
        assertEquals("“What's the weather?”", VoiceTexts.quote("what's the weather?"))
        assertEquals("“Done”", VoiceTexts.quote("Done")) // no full stop added
        assertEquals("", VoiceTexts.quote("  "))
    }

    @Test
    fun mainTextStepsDownThenAllowsThreeLines() {
        assertEquals(MainTextFit.Fit(36f, 2), MainTextFit.choose { 1 })
        assertEquals(MainTextFit.Fit(36f, 2), MainTextFit.choose { 2 })
        assertEquals(MainTextFit.Fit(30f, 2), MainTextFit.choose { sp -> if (sp >= 36f) 3 else 2 })
        assertEquals(MainTextFit.Fit(26f, 3), MainTextFit.choose { 3 })
        assertEquals(MainTextFit.Fit(26f, 3), MainTextFit.choose { 7 }) // ellipsised at 3 lines
        val asked = ArrayList<Float>()
        MainTextFit.choose { asked += it; 1 }
        assertEquals(listOf(36f), asked) // stops at the first size that fits
    }

    // ---------------------------------------------------------------- §4 motion

    @Test
    fun textCrossFadeFollowsThePrototype() {
        assertEquals(1f, TextFade.outgoingAlpha(0f, ease), 0f)
        assertEquals(0f, TextFade.outgoingAlpha(1f / 1.8f, ease), 1e-6f) // gone at k = 0.56
        assertEquals(0f, TextFade.incomingAlpha(0.35f, ease), 0f)       // starts at k = 0.35
        assertEquals(1f, TextFade.incomingAlpha(1f, ease), 0f)
        var prev = 0f
        for (i in 0..100) {
            val v = TextFade.incomingAlpha(i / 100f, ease)
            assertTrue(v >= prev && v <= 1f)
            prev = v
        }
    }

    @Test
    fun faderEasesInAndOutWithItsOwnDurations() {
        val f = Fader(350f, 550f)
        f.set(1f, 1000)
        assertEquals(0f, f.update(1000, ease), 0f)
        assertEquals(0.5f, f.update(1175, ease), 1e-5f)
        assertEquals(1f, f.update(1350, ease), 0f)
        assertTrue(f.settled)
        f.set(0f, 2000)
        assertEquals(0.5f, f.update(2275, ease), 1e-5f) // 550 ms out
        // Reversing mid-way starts from where it is: no jump.
        f.set(1f, 2275)
        assertEquals(0.5f, f.update(2275, ease), 1e-5f)
        assertEquals(1f, f.update(2275 + 350, ease), 0f)
    }

    // ---------------------------------------------------------------- snapshot

    @Test
    fun snapshotKeepsTextsForTheRunAndClearsThemOnTheNext() {
        var ui = VoiceUi("idle")
        ui = ui.on(VoiceEvent.WakeDetected)
        assertEquals("wake", ui.state)
        val seq = ui.seq
        ui = ui.on(VoiceEvent.Listening).on(VoiceEvent.Thinking)
        ui = ui.on(VoiceEvent.Heard(" turn off the lights "))
        assertEquals("thinking", ui.state)
        assertEquals(seq + 2, ui.seq) // texts don't count as state events
        assertEquals("turn off the lights", ui.heard)
        ui = ui.on(VoiceEvent.Reply("Done.", announcement = false)).on(VoiceEvent.Speaking).on(VoiceEvent.Idle)
        assertEquals("idle", ui.state)
        assertEquals("turn off the lights", ui.heard) // the done step still shows the reply
        assertEquals("Done.", ui.reply)
        // Follow-up question / next run starts clean.
        ui = ui.on(VoiceEvent.Listening)
        assertEquals("", ui.heard)
        assertEquals("", ui.reply)
        // An announcement has no heard text.
        ui = ui.on(VoiceEvent.Heard("x")).on(VoiceEvent.Reply("Dinner is ready", announcement = true))
        assertEquals("", ui.heard)
        assertTrue(ui.announcement)
        // Errors keep their origin; the same error twice is still two events.
        val e1 = ui.on(VoiceEvent.Error("Cannot open the microphone", local = true))
        assertTrue(e1.errorLocal)
        assertEquals("error: Cannot open the microphone", e1.state)
        val e2 = e1.on(VoiceEvent.Error("Cannot open the microphone", local = true))
        assertEquals(e1.state, e2.state)
        assertFalse(e1.seq == e2.seq)
    }
}
