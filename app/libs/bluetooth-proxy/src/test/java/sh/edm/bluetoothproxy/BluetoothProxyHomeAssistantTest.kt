package sh.edm.bluetoothproxy

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The proxy as Home Assistant meets it: the real client HA 2026.11 pins (aioesphomeapi 46.25.0), via
 * ESPHOME_PYTHON as in esphome-api's EspHomeKeepaliveTest. It takes the panel as a passive,
 * not connectable Bluetooth scanner with raw advertisements (bleak-esphome connect.py), and gets
 * what a fake radio hears. The engine scans only while HA is subscribed.
 */
class BluetoothProxyHomeAssistantTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM",
        voiceFeatures = 0,
    )

    /** Hears [COUNT] made-up advertisements, 5 ms apart, each time a scan starts. */
    private class RadioWithNeighbours : BleScanner {
        val started = CountDownLatch(1)
        val stopped = CountDownLatch(1)

        override fun start(power: ScanPower, listener: BleScanner.Listener) {
            started.countDown()
            Thread {
                for (i in 0 until COUNT) {
                    listener.onAdvertisement(0xC0FFEE000000L + i, -40 - i, advertising(i) + ByteArray(50))
                    Thread.sleep(5)
                }
            }.apply { isDaemon = true }.start()
        }

        override fun stop() = stopped.countDown()
    }

    companion object {
        const val COUNT = 40
        /** Flags, then manufacturer data under 0xFFFF (the id the Bluetooth SIG keeps for tests). */
        fun advertising(i: Int) = byteArrayOf(2, 1, 6, 4, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), i.toByte())
    }

    @Test
    fun homeAssistantSeesAPassiveProxyAndItsAdvertisements() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = InetAddress.getLoopbackAddress())
        val radio = RadioWithNeighbours()
        val engine = BluetoothProxyEngine(
            radio, server::sendBluetoothAdvertisements, ScanPower.LOW_LATENCY,
            BluetoothProxyEngine.ExecutorLoop(), ScanSchedule.Limits(lingerMs = 300),
        )
        server.bluetooth = engine
        server.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_bluetooth_proxy.py")!!.toURI())
            val log = File.createTempFile("esphome_bluetooth_proxy", ".log").apply { deleteOnExit() }
            val proc = ProcessBuilder(python, "-I", script.absolutePath, server.localPort.toString(), COUNT.toString())
                .redirectErrorStream(true).redirectOutput(log).start()
            val finished = try { proc.waitFor(60, TimeUnit.SECONDS) } finally { if (proc.isAlive) proc.destroyForcibly() }
            val out = log.readText()
            assertTrue("client timed out:\n$out", finished)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = JSONObject(out.lines().first { it.startsWith("RESULT ") }.removePrefix("RESULT "))

            assertTrue("HA adds a Bluetooth scanner", r.getBoolean("scanner"))
            assertTrue("and subscribes to raw advertisements", r.getBoolean("raw"))
            assertFalse("passive: not connectable", r.getBoolean("connectable"))
            assertFalse("no scanner state or mode (Android can't switch to a passive scan)", r.getBoolean("state_and_mode"))
            assertEquals("the scanner's source is the device's MAC", identity.macAddress, r.getString("source"))

            val advs = r.getJSONArray("advertisements")
            assertEquals(COUNT, advs.length())
            for (i in 0 until COUNT) {
                val a = advs.getJSONObject(i)
                assertEquals("C0:FF:EE:00:00:%02X".format(i), a.getString("address"))
                assertEquals(-40 - i, a.getInt("rssi"))
                assertEquals("Android's zero padding is gone", advertising(i).joinToString("") { "%02x".format(it) }, a.getString("data"))
            }
            val batches = r.getJSONArray("batches").let { b -> (0 until b.length()).map { b.getInt(it) } }
            assertTrue("batched, at most 16 each: $batches", batches.all { it in 1..16 } && batches.size < COUNT)

            assertTrue("scanning started for HA", radio.started.await(0, TimeUnit.SECONDS))
            assertTrue("and stopped once HA left", radio.stopped.await(5, TimeUnit.SECONDS))
            assertFalse(engine.status.subscribed)
        } finally {
            engine.close()
            server.stop()
        }
    }
}
