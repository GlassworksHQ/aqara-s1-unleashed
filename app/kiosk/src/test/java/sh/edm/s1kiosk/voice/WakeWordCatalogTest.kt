package sh.edm.s1kiosk.voice

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files

/** What Home Assistant's wake word select offers, and what it can pick ([VoiceEngine] as the catalog). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WakeWordCatalogTest {
    private val dir: File = Files.createTempDirectory("models").toFile()
    private val stored = ArrayList<String>()
    private fun engine(word: String = "hey_jarvis") =
        VoiceEngine(RuntimeEnvironment.getApplication(), VoiceConfig(modelsDir = dir), word) { stored += it }

    @Test
    fun theFourMicroWakeWordWordsAreAlwaysOffered() {
        assertEquals(
            listOf("okay_nabu" to "Okay Nabu", "hey_jarvis" to "Hey Jarvis", "alexa" to "Alexa", "hey_mycroft" to "Hey Mycroft"),
            engine().available().map { it.id to it.wakeWord },
        )
    }

    @Test
    fun openWakeWordModelsAlreadyOnThePanelAreOfferedAsLegacy() {
        for (f in listOf("melspectrogram.tflite", "embedding_model.tflite", "hey_jarvis_v0.1.tflite", "alexa_v0.1.tflite")) File(dir, f).writeText("x")
        File(dir, "micro").mkdirs()
        val legacy = engine().available().drop(4).map { it.id to it.wakeWord }
        assertEquals(listOf("alexa_v0.1" to "Alexa (openWakeWord)", "hey_jarvis_v0.1" to "Hey Jarvis (openWakeWord)"), legacy)
    }

    @Test
    fun pickingAWordStoresItsIdAndAnUnknownOneIsIgnored() {
        File(dir, "hey_mycroft_v0.1.tflite").writeText("x")
        val e = engine()
        e.setActive(listOf("okay_nabu"))
        e.setActive(listOf("hey_mycroft_v0.1"))
        e.setActive(listOf("alexa_v0.1")) // not on the panel
        e.setActive(emptyList())
        assertEquals(listOf("okay_nabu", "hey_mycroft_v0.1", ""), stored)
        assertTrue(e.activeIds().isEmpty())
    }

    @Test
    fun theActiveWordIsWhatTheCallerStored() {
        assertEquals(listOf("alexa"), engine("alexa").activeIds())
        assertEquals(emptyList<String>(), engine("").activeIds())
    }
}

class WakeWordsTest {
    @Test
    fun the04ModelFilesMoveToMicroWakeWord() {
        assertEquals("hey_jarvis", WakeWords.migrate("hey_jarvis_v0.1.tflite"))
        assertEquals("alexa", WakeWords.migrate("alexa_v0.1.tflite"))
        assertEquals("hey_mycroft", WakeWords.migrate("hey_mycroft_v0.1.tflite"))
        assertEquals("", WakeWords.migrate(""))
    }

    @Test
    fun otherModelFilesStayOnOpenWakeWordAndIdsAreKept() {
        assertEquals("my_word_v2", WakeWords.migrate("my_word_v2.tflite"))
        assertEquals("hey_jarvis_v0.1", WakeWords.migrate("hey_jarvis_v0.1"))
        assertEquals("okay_nabu", WakeWords.migrate("okay_nabu"))
        assertTrue(WakeWords.parse("hey_jarvis_v0.1") is WakeWord.Legacy)
        assertEquals("Hey Jarvis", WakeWords.parse("hey_jarvis_v0.1")!!.phrase)
        assertTrue(WakeWords.parse("hey_jarvis") is WakeWord.Micro)
    }

    @Test
    fun theHostScriptPinsTheSameCommitAndHashes() {
        val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/voice").isDirectory && File(it, "app/kiosk").isDirectory }
        val script = File(repo, "tools/voice/fetch-models.sh").readText()
        assertTrue("MWW_COMMIT", script.contains("MWW_COMMIT=\"${WakeWords.MODELS_COMMIT}\""))
        for (m in WakeWords.MICRO) assertTrue(m.id, script.contains("\"${m.id}.tflite ${m.sha256}\""))
    }

    @Test
    fun theMicroFetcherTakesThePinnedCommitAndChecksEachHash() {
        val urls = ArrayList<String>()
        val dir = Files.createTempDirectory("micro").toFile()
        val e = runCatching { ModelFetcher.micro { url: URL -> urls += url.toString(); ByteArrayInputStream("<html>".toByteArray()) }.ensure(dir) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("SHA-256"))
        assertEquals(
            "https://raw.githubusercontent.com/esphome/micro-wake-word-models/${WakeWords.MODELS_COMMIT}/models/v2/okay_nabu.tflite",
            urls.single(),
        )
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
