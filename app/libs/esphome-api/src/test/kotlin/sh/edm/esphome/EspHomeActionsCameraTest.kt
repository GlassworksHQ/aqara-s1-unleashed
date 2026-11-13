package sh.edm.esphome

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.random.Random

/**
 * Actions and the camera against the real client Home Assistant 2026.11 pins (aioesphomeapi 46.25.0),
 * via ESPHOME_PYTHON as in [EspHomeKeepaliveTest]: HA lists the actions, calls them with every
 * argument type and gets their results, then fetches a JPEG of several chunks, alone and as a stream.
 */
class EspHomeActionsCameraTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )

    private fun jpeg(): ByteArray {
        System.setProperty("java.awt.headless", "true")
        val random = Random(120)
        val img = BufferedImage(640, 480, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 480) for (x in 0 until 640) img.setRGB(x, y, random.nextInt(0xFFFFFF))
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun homeAssistantsClientCallsActionsAndFetchesImages() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val image = jpeg()
        assertTrue("the image spans several chunks", image.size > 3 * CameraEntity.CHUNK_BYTES)
        val all = listOf(
            "b" to ActionArgType.BOOL, "i" to ActionArgType.INT, "f" to ActionArgType.FLOAT,
            "s" to ActionArgType.STRING, "bs" to ActionArgType.BOOL_ARRAY, "is" to ActionArgType.INT_ARRAY,
            "fs" to ActionArgType.FLOAT_ARRAY, "ss" to ActionArgType.STRING_ARRAY,
        ).map { (n, t) -> ActionArg(n, t) }
        val echo = ApiAction("echo", all, ActionResponse.OPTIONAL) { JSONObject(it.args).toString() }
        val fail = ApiAction("fail", supportsResponse = ActionResponse.STATUS) { throw IllegalStateException("speaker busy") }
        val rang = CountDownLatch(1)
        val ring = ApiAction("ring", listOf(ActionArg("times", ActionArgType.INT))) {
            if (it.int("times") == 2) rang.countDown()
            null
        }
        val cam = CameraEntity("porch", "Porch") { image }
        val s = ApiServer(0, { identity }, listOf(echo, fail, ring, cam), keys = null, bindAddress = InetAddress.getLoopbackAddress())
        s.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_actions_camera.py")!!.toURI())
            val log = File.createTempFile("esphome_actions_camera", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = proc.waitFor(60, TimeUnit.SECONDS)
            if (!finished) proc.destroyForcibly()
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))

            val services = r.getJSONObject("services")
            assertEquals(
                JSONArray(all.map { JSONArray(listOf(it.name, it.type.value)) }).toString(),
                services.getJSONObject("echo").getJSONArray("args").toString(),
            )
            assertEquals(ActionResponse.OPTIONAL.value, services.getJSONObject("echo").getInt("supports_response"))
            assertEquals(ActionResponse.STATUS.value, services.getJSONObject("fail").getInt("supports_response"))
            assertEquals(listOf("porch"), r.getJSONArray("cameras").toList())

            val echoed = r.getJSONObject("echo")
            assertTrue(echoed.getBoolean("success"))
            val data = echoed.getJSONObject("data")
            assertEquals(true, data.getBoolean("b"))
            assertEquals(-42, data.getInt("i"))
            assertEquals(1.5, data.getDouble("f"), 0.0)
            assertEquals("héllo", data.getString("s"))
            assertEquals("[true,false,true]", data.getJSONArray("bs").toString())
            assertEquals("[1,-2,300]", data.getJSONArray("is").toString())
            assertEquals("[0.5,-2]", data.getJSONArray("fs").toString())
            assertEquals("""["a","","c"]""", data.getJSONArray("ss").toString())
            val statusOnly = r.getJSONObject("echo_status_only")
            assertTrue(statusOnly.getBoolean("success"))
            assertEquals("no JSON unless asked for", "", statusOnly.getString("data"))
            val failed = r.getJSONObject("fail")
            assertEquals(false, failed.getBoolean("success"))
            assertEquals("speaker busy", failed.getString("error"))
            assertTrue("ring ran with its argument", rang.await(5, TimeUnit.SECONDS))

            val single = r.getJSONObject("single")
            assertEquals(image.size, single.getInt("len"))
            assertEquals(sha256(image), single.getString("sha256"))
            assertEquals(List(5) { sha256(image) }, r.getJSONArray("stream").toList())
        } finally { s.stop() }
    }
}
