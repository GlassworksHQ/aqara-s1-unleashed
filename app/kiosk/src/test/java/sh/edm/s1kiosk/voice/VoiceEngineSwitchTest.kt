package sh.edm.s1kiosk.voice

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.Msg
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.WakeWordInfo
import sh.edm.s1kiosk.esphome.UrlPlayer
import sh.edm.s1kiosk.esphome.VoiceAssistantController
import sh.edm.s1kiosk.esphome.WakeWordCatalog
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Switching words: what Home Assistant hears while a new word loads, and when it can't. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class VoiceEngineSwitchTest {
    private class Always : WakeDetector {
        @Volatile var closed = false
        override fun process(frame: ShortArray) = 1f
        override fun close() { closed = true }
    }

    private val errors = CopyOnWriteArrayList<String>()
    private val jarvis = Always()
    private val nabu = Always()
    private val nabuGate = CountDownLatch(1)
    private var nabuFails = false
    private lateinit var engine: VoiceEngine

    private fun engine(): VoiceEngine {
        val dir = Files.createTempDirectory("models").toFile()
        return VoiceEngine(RuntimeEnvironment.getApplication(), VoiceConfig(modelsDir = dir, refractoryMs = 0), "hey_jarvis", onActiveWordChanged = {}) { word ->
            when (word.id) {
                "hey_jarvis" -> jarvis
                "okay_nabu" -> {
                    nabuGate.await(5, TimeUnit.SECONDS)
                    if (nabuFails) throw ModelsMissingException("Downloading the wake word model okay_nabu.tflite failed: offline")
                    nabu
                }
                else -> error("unexpected ${word.id}")
            }
        }.also { e ->
            e.onError = { errors += it }
            engine = e
            e.start(muted = true)
        }
    }

    @After fun stop() = engine.stop()

    /** The wake word phrase HA would get for one frame, or null when nothing was sent. */
    private fun phraseHeard(): String? {
        val sent = ArrayList<Pair<Int, ProtoMessage>>()
        val c = VoiceAssistantController(
            send = { t, p -> sent += t to ProtoMessage(p.toByteArray()); true },
            player = object : UrlPlayer {
                override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {}
                override fun stop() {}
            },
            wakeWords = object : WakeWordCatalog {
                override fun available() = emptyList<WakeWordInfo>()
                override fun activeIds() = emptyList<String>()
                override fun setActive(ids: List<String>) {}
            },
            emit = {},
        )
        c.onSubscribe(true)
        engine.controller = c
        engine.onMicFrame(ShortArray(1280))
        return sent.firstOrNull { it.first == Msg.VOICE_ASSISTANT_REQUEST }?.second?.string(5)
    }

    private fun eventually(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue(what, System.currentTimeMillis() < until)
            Thread.sleep(10)
        }
    }

    @Test
    fun whileTheNewWordLoadsTheOldOneStillAnswersAsItself() {
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" }
        e.setActive(listOf("okay_nabu"))
        assertEquals("Hey Jarvis", phraseHeard())
        nabuGate.countDown()
        eventually("Okay Nabu loaded") { phraseHeard() == "Okay Nabu" }
        eventually("the old model is closed") { jarvis.closed }
    }

    @Test
    fun aWordThatFailsToLoadStopsTheOldOneAndSaysWhy() {
        nabuFails = true
        val e = engine()
        eventually("Hey Jarvis loaded") { phraseHeard() == "Hey Jarvis" }
        e.setActive(listOf("okay_nabu"))
        nabuGate.countDown()
        eventually("the error is reported") { errors.isNotEmpty() }
        assertEquals(listOf("Downloading the wake word model okay_nabu.tflite failed: offline"), errors)
        assertEquals("no word answers for Okay Nabu", null, phraseHeard())
        assertTrue(jarvis.closed)
        assertEquals("HA's choice stays", listOf("okay_nabu"), e.activeIds())
    }
}
