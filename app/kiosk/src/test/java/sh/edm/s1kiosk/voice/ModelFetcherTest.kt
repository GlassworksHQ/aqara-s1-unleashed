package sh.edm.s1kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.file.Files

/**
 * The real models come from the host cache tools/voice/fetch-models.sh fills
 * (~/.cache/s1kiosk/openwakeword-v0.5.1, or OWW_MODELS_DIR); without it the test is skipped.
 */
class ModelFetcherTest {
    private val cache = File(System.getenv("OWW_MODELS_DIR")
        ?: "${System.getProperty("user.home")}/.cache/s1kiosk/openwakeword-${ModelFetcher.VERSION}")

    private fun haveCache() = ModelFetcher.FILES.keys.all { File(cache, it).isFile }

    @Test
    fun fetchesMissingModelsAndVerifiesThem() {
        assumeTrue("no model cache at $cache", haveCache())
        val dir = Files.createTempDirectory("models").toFile()
        val urls = mutableListOf<String>()
        val f = ModelFetcher { url: URL -> urls += url.toString(); File(cache, url.path.substringAfterLast('/')).inputStream() }
        // one already there and correct: not downloaded again
        File(cache, "alexa_v0.1.tflite").copyTo(File(dir, "alexa_v0.1.tflite"))
        val got = f.ensure(dir)
        assertEquals(ModelFetcher.FILES.keys.filter { it != "alexa_v0.1.tflite" }, got)
        assertTrue(urls.all { it.startsWith("https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/") })
        assertTrue(f.missing(dir).isEmpty())
        assertTrue(f.ensure(dir).isEmpty())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test
    fun rejectsAFileThatFailsItsHash() {
        val dir = Files.createTempDirectory("models").toFile()
        val f = ModelFetcher { ByteArrayInputStream("<html>not a model</html>".toByteArray()) }
        val e = runCatching { f.ensure(dir) }.exceptionOrNull()
        assertTrue(e is IOException && e.message!!.contains("SHA-256"))
        assertTrue(dir.listFiles()!!.isEmpty()) // nothing left behind, not even .part
    }

    @Test
    fun replacesACorruptModel() {
        assumeTrue("no model cache at $cache", haveCache())
        val dir = Files.createTempDirectory("models").toFile()
        ModelFetcher.FILES.keys.forEach { File(cache, it).copyTo(File(dir, it)) }
        File(dir, TfliteWakeWord.MELSPEC_FILE).writeText("truncated")
        val f = ModelFetcher { url: URL -> File(cache, url.path.substringAfterLast('/')).inputStream() }
        assertEquals(listOf(TfliteWakeWord.MELSPEC_FILE), f.ensure(dir))
    }
}
