package sh.edm.s1kiosk.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The real [UartBaseLink] (TCP transport) against `tools/bracket/base_emulator.py`, the emulator
 * Aqara's bracket_cli accepted on the panel (frames/aqara-s1-plus/research/base-protocol.md). Needs only a plain python3;
 * uses BRACKET_PYTHON, else ESPHOME_PYTHON, else `python3`. Skipped if none runs.
 */
class UartBaseLinkEmulatorTest {

    private fun python(): String? {
        val candidates = listOfNotNull(System.getenv("BRACKET_PYTHON"), System.getenv("ESPHOME_PYTHON"), "python3")
        return candidates.firstOrNull { p ->
            try {
                ProcessBuilder(p, "-c", "pass").start().waitFor(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun waitFor(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun driverAgainstEmulator() {
        val py = python()
        assumeTrue("no python3 to run base_emulator.py", py != null)
        val script = listOf("../../tools/bracket/base_emulator.py", "../tools/bracket/base_emulator.py", "tools/bracket/base_emulator.py")
            .map(::File).firstOrNull { it.isFile }
        assumeTrue("tools/bracket/base_emulator.py not found from ${File(".").absolutePath}", script != null)
        // Picked on 127.0.0.1, where the emulator listens: on macOS a wildcard pick can be a port
        // another process holds there (#113).
        val port = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val emu = ProcessBuilder(py, "-I", "-u", script!!.absolutePath, "--port", port.toString(), "--report-interval", "1", "--no-hello")
            .redirectErrorStream(true).start()
        val emuLog = Collections.synchronizedList(ArrayList<String>())
        Thread { emu.inputStream.bufferedReader().forEachLine { emuLog += it } }.apply { isDaemon = true; start() }
        val exec = Executors.newSingleThreadScheduledExecutor()
        val got = ConcurrentHashMap<String, Any>()
        val values: Map<String, Any> = got
        val events = Collections.synchronizedList(ArrayList<String>())
        val logs = Collections.synchronizedList(ArrayList<String>())
        lateinit var link: UartBaseLink
        val listener = object : BaseLink.Listener {
            override fun onConnected() { events += "connected"; link.read(listOf(BaseRes.DOCK_CLASS)) }
            override fun onDisconnected() { events += "disconnected" }
            override fun onUnavailable(reason: String) { events += "unavailable" }
            override fun onValues(values: Map<String, Any>, via: String, apply: Boolean, dockTrigger: Boolean) {
                got.putAll(values)
            }
        }
        try {
            waitFor("emulator listening") { emuLog.any { "base emulator on" in it } }
            var saved = -1f
            link = UartBaseLink(exec, listener, "tcp:127.0.0.1:$port", saveEnergy = { saved = it }, log = { _, m -> logs += m })
            exec.execute { link.start() }

            // Handshake -> dock class / channels / mask, config writes, firmware + relay reads.
            waitFor("handshake") { values[BaseRes.DOCK_CLASS] == 0x0100F110L && values[BaseRes.FIRMWARE] == 23L && "4.2.85" in values }
            assertEquals(2, values[BaseRes.CHANNEL_COUNT])
            assertEquals(3, values[BaseRes.RELAY_MASK])
            assertEquals(BaseKind.SWITCH, BaseProtocol.decodeDockClass((values[BaseRes.DOCK_CLASS] as Long).toInt()).first)
            waitFor("config writes acked by the emulator") {
                listOf("200.4.1", "4.103.85", "200.4.2", "200.4.3", "200.4.4").all { r -> emuLog.any { "write ack $r=" in it } }
            }
            assertTrue("emulator saw a bad CRC", emuLog.none { "BAD" in it })

            // Relay write from "Home Assistant".
            exec.execute { link.write("4.1.85", 1) }
            waitFor("relay 1 on") { values["4.1.85"] == 1 }
            assertTrue(emuLog.any { "write ack 4.1.85=1" in it })
            // Power arrives by report (emulator: 60 W per relay that is on).
            waitFor("power report") { (values[BaseRes.POWER] as? Float ?: 0f) > 50f }

            // Simulated physical toggle and an over-load alarm on the base.
            emu.outputStream.write("t2\no1\n".toByteArray()); emu.outputStream.flush()
            waitFor("relay 2 report") { values["4.2.85"] == 1 }
            waitFor("protection") { values[BaseRes.PROTECTION] == 2 }

            // Energy is integrated host-side and persisted on stop.
            waitFor("energy grows", 5_000) { (values[BaseRes.ENERGY] as? Float ?: 0f) > 0f }
            exec.submit { link.stop() }.get(5, TimeUnit.SECONDS)
            assertTrue(saved > 0f)
            assertTrue(events.first() == "connected")
        } finally {
            exec.shutdownNow()
            emu.destroy()
            if (emu.isAlive) emu.destroyForcibly()
            System.err.println(logs.joinToString("\n"))
        }
    }
}
