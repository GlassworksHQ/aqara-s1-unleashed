package sh.edm.esphome

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * A bus event against the real client Home Assistant 2026.11 pins (aioesphomeapi 46.25.0), via
 * ESPHOME_PYTHON as in [EspHomeKeepaliveTest]: HA subscribes to events, calls an action, and the
 * device answers with `esphome.notification_action`, which the client decodes as an event.
 */
class EspHomeHaEventTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )

    @Test
    fun homeAssistantsClientGetsTheEvent() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        lateinit var s: ApiServer
        val tap = ApiAction("tap", supportsResponse = ActionResponse.STATUS) {
            check(s.sendHomeAssistantEvent(HomeAssistantEvent("esphome.notification_action", linkedMapOf("action" to "open_gate", "tag" to "doorbell"))))
            null
        }
        s = ApiServer(0, { identity }, listOf(tap), keys = null, bindAddress = InetAddress.getLoopbackAddress())
        s.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_ha_event.py")!!.toURI())
            val log = File.createTempFile("esphome_ha_event", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, s.localPort.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = try { proc.waitFor(60, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))
            assertEquals("esphome.notification_action", r.getString("service"))
            assertTrue(r.getBoolean("is_event"))
            assertEquals("open_gate", r.getJSONObject("data").getString("action"))
            assertEquals("doorbell", r.getJSONObject("data").getString("tag"))
        } finally {
            s.stop()
        }
    }
}
