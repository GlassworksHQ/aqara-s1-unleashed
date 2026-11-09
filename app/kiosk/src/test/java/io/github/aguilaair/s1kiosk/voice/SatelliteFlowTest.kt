package io.github.aguilaair.s1kiosk.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end Wyoming session against the real `wyoming` Python package playing Home Assistant
 * (src/test/resources/ha_sim.py). Needs a Python with `wyoming` installed:
 *
 *     python3 -m venv /tmp/wyo-venv && /tmp/wyo-venv/bin/pip install wyoming
 *     WYOMING_PYTHON=/tmp/wyo-venv/bin/python ./gradlew :kiosk:testDebugUnitTest
 *
 * Skipped when WYOMING_PYTHON is not set.
 */
class SatelliteFlowTest {

    private class FakeOutput : AudioOutput {
        val bytes = AtomicInteger()
        @Volatile var format: Triple<Int, Int, Int>? = null
        override fun begin(rate: Int, width: Int, channels: Int) { format = Triple(rate, width, channels) }
        override fun write(pcm: ByteArray) { bytes.addAndGet(pcm.size) }
        override fun end(onDone: () -> Unit) { Thread { Thread.sleep(50); onDone() }.start() }
        override fun cancel() {}
    }

    @Test
    fun homeAssistantSession() {
        val python = System.getenv("WYOMING_PYTHON")
        assumeTrue("set WYOMING_PYTHON to a python with `wyoming` installed", !python.isNullOrBlank())

        val events = Collections.synchronizedList(ArrayList<VoiceEvent>())
        val output = FakeOutput()
        val controller = SatelliteController(
            infoProvider = {
                WyomingInfo.build("Test Panel", null, "test", "hey_jarvis_v0.1", "hey jarvis", wakeInstalled = true)
            },
            output = output,
            emit = { events += it },
            refractoryMs = 1_000,
            maxStreamMs = 15_000,
        )
        val server = WyomingServer(0, controller)
        server.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("ha_sim.py")!!.toURI())
            val proc = ProcessBuilder(python, "-I", script.absolutePath, server.localPort.toString())
                .redirectErrorStream(true)
                .start()
            val log = StringBuilder()
            val ready = CountDownLatch(1)
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.appendLine(line) }
                    println("ha_sim: $line")
                    if (line == "READY") ready.countDown()
                }
            }.apply { start() }

            assertTrue("ha_sim never got ready:\n$log", ready.await(20, TimeUnit.SECONDS))
            // The mic thread: wake word fires, then 80 ms frames flow while streaming.
            controller.onWakeWord("hey_jarvis_v0.1")
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                controller.currentState == SatelliteController.State.STREAMING
            ) {
                controller.onMicAudio(ByteArray(2560))
                Thread.sleep(20)
            }

            assertTrue("ha_sim timed out", proc.waitFor(60, TimeUnit.SECONDS))
            reader.join(2_000)
            assertEquals("ha_sim failed:\n$log", 0, proc.exitValue())
            assertTrue(log.contains("OK"))

            assertEquals(Triple(22050, 2, 1), output.format)
            assertEquals(20 * 1024, output.bytes.get())
            val seq = synchronized(events) { events.toList() }
            val wanted = listOf(
                VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking,
                VoiceEvent.Speaking, VoiceEvent.Idle,
            )
            var idx = 0
            for (e in seq) if (idx < wanted.size && e == wanted[idx]) idx++
            assertEquals("event order: $seq", wanted.size, idx)
        } finally {
            controller.shutdown()
            server.stop()
        }
    }
}
