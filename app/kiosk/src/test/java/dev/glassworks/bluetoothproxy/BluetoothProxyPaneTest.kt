package dev.glassworks.bluetoothproxy

import android.app.Application
import dev.glassworks.pane.PaneContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.bluetoothproxy.BleScanner
import sh.edm.bluetoothproxy.BluetoothProxyEngine
import sh.edm.bluetoothproxy.ScanFailure
import sh.edm.bluetoothproxy.ScanPower
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import java.util.concurrent.Executors

/** The pane around the engine: it offers the proxy to Home Assistant only while it is on, on the server it was given. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BluetoothProxyPaneTest {
    private val identity = DeviceIdentity(
        name = "test-panel", friendlyName = "Test Panel", macAddress = "02:00:00:00:00:01",
        esphomeVersion = "2026.10.2", compilationTime = "test", manufacturer = "Test", model = "JVM", voiceFeatures = 0,
    )
    private val device = Executors.newSingleThreadScheduledExecutor()
    private var rebuilds = 0
    private val ctx = PaneContext(
        RuntimeEnvironment.getApplication(), prefs = { throw IllegalArgumentException(it) }, identity,
        publishStates = {}, emit = {}, entitiesChanged = { rebuilds++ }, sendEvent = { _, _ -> },
    )

    private class Radio(var why: ScanFailure? = null) : ProxyRadio {
        override val scanner = object : BleScanner {
            override fun start(power: ScanPower, listener: BleScanner.Listener) {}
            override fun stop() {}
        }
        override fun unavailable() = why
        override fun turnBluetoothOn() = false
        override fun turnLocationOn() = false
    }

    private fun server() = ApiServer(0, { identity }, emptyList(), keys = null)

    @After fun tearDown() { device.shutdownNow() }

    @Test fun onOffersTheProxyAndOffTakesItBack() {
        val pane = BluetoothProxyPane(ctx, Radio(), device)
        val s = server()
        pane.bind(s)
        assertNull("off: no proxy flags", s.bluetooth)
        assertNull("and its status entity is unavailable", pane.phase().entityText)

        pane.start()
        assertTrue(s.bluetooth is BluetoothProxyEngine)
        assertEquals("HA reconnects to read DeviceInfo", 1, rebuilds)
        assertEquals(ProxyPhase.WAITING, pane.phase())

        pane.stop()
        assertNull(s.bluetooth)
        assertEquals(2, rebuilds)
        assertEquals(ProxyPhase.OFF, pane.phase())
    }

    @Test fun aNewServerGetsTheProxyAndTheOldOneLosesIt() {
        val pane = BluetoothProxyPane(ctx, Radio(), device)
        val first = server()
        pane.bind(first)
        pane.start()
        val engine = first.bluetooth
        val second = server()
        pane.bind(second)
        assertNull(first.bluetooth)
        assertTrue(second.bluetooth is BluetoothProxyEngine)
        assertTrue("a new engine for the new server", second.bluetooth !== engine)
        pane.stop()
    }

    @Test fun withoutBluetoothLeHomeAssistantIsNeverOfferedAProxy() {
        val pane = BluetoothProxyPane(ctx, Radio(ScanFailure.NO_BLE), device)
        val s = server()
        pane.bind(s)
        pane.start()
        assertNull(s.bluetooth)
        assertEquals(ProxyPhase.NO_BLE, pane.phase())
        pane.stop()
    }

    @Test fun startedBeforeTheServerExistsItAttachesOnBind() {
        val pane = BluetoothProxyPane(ctx, Radio(), device)
        pane.start()
        val s = server()
        pane.bind(s)
        val engine = s.bluetooth
        assertTrue(engine is BluetoothProxyEngine)
        pane.bind(s)
        assertSame("binding the same server again keeps the engine", engine, s.bluetooth)
        pane.stop()
    }
}
