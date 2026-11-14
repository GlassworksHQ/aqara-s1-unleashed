package sh.edm.s1kiosk.base

import android.app.Application
import dev.glassworks.pane.EntityRegistry
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.EventEntity
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraCloud
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WallBasePaneTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = KioskPrefs(app).wallBase.apply { layout = "" }
    private val device = Executors.newSingleThreadScheduledExecutor()
    private var changes = 0
    private val sent = ArrayList<Pair<EventEntity, String>>()

    private fun newPane() = WallBasePane(
        PaneContext(
            app, KioskPrefs(app)::paneFile,
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = {}, emit = {}, entitiesChanged = { changes++ }, sendEvent = { e, t -> sent += e to t },
        ),
        WallBase.Config(BaseBackend.LOCALSERVICE),
        device,
        AqaraCloud(),
    )

    private val pane = newPane()

    /** What the shell gives Home Assistant after the pane said its entities changed. */
    private fun shellList(): List<ApiEntity> = EntityRegistry(listOf(pane)).assemble(emptyList())

    @After fun tearDown() {
        pane.stop()
        device.shutdownNow()
    }

    private val twoChannels = BaseLayout(BaseKind.SWITCH, listOf(1, 2))

    @Test fun withNoBaseOnlyTheBaseSensorAndTheCloudEntitiesExist() {
        assertEquals(listOf("block_aqara_cloud", "aqara_cloud", "base"), shellList().map { it.objectId })
    }

    @Test fun aNewLayoutAsksTheShellToRebuildWithItsEntities() {
        pane.baseListener.onLayout(twoChannels)

        assertEquals(1, changes)
        assertEquals(
            listOf(
                "block_aqara_cloud", "aqara_cloud", "base", "base_firmware", "base_relay_1", "base_relay_2",
                "base_power", "base_energy", "base_protection", "base_button_1", "base_button_2",
            ),
            shellList().map { it.objectId },
        )
        assertEquals(twoChannels, BaseLayout.decode(prefs.layout))
    }

    @Test fun aButtonPressFiresTheEventEntityHomeAssistantHas() {
        pane.baseListener.onLayout(twoChannels)
        val listed = shellList()

        pane.baseListener.onButton(2, "double")

        assertEquals(1, sent.size)
        assertSame(listed.first { it.objectId == "base_button_2" }, sent[0].first)
        assertEquals("double", sent[0].second)
    }

    @Test fun aButtonWithoutAnEventEntityFiresNothing() {
        pane.baseListener.onButton(1, "single")
        pane.baseListener.onLayout(BaseLayout(BaseKind.SWITCH, listOf(1)))
        pane.baseListener.onButton(3, "single")
        assertTrue(sent.isEmpty())
    }

    @Test fun theSavedLayoutIsWhatAFreshPaneStartsWith() {
        prefs.layout = twoChannels.encode()
        val restarted = newPane()
        try {
            assertTrue(restarted.entities().any { it.objectId == "base_relay_2" })
        } finally {
            restarted.stop()
        }
    }

    /** init's ha_master as the pane sees it: `getprop init.svc.ha_master` and `setprop ctl.start|ctl.stop`. */
    private class FakeInit(var state: String = "running") {
        val cloud = AqaraCloud { cmd ->
            when {
                cmd[0] == "getprop" -> state
                cmd[1] == "ctl.start" -> { state = "running"; "" }
                else -> { state = "stopped"; "" }
            }
        }
    }

    private fun paneWith(config: WallBase.Config, init: FakeInit) = WallBasePane(
        PaneContext(
            app, KioskPrefs(app)::paneFile,
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        ),
        config, device, init.cloud,
    )

    /** What the shell does when the switch changes: the pane started or stopped, then it hears its own key. */
    private fun turn(p: WallBasePane, on: Boolean) {
        switch.on = on
        if (on) p.start() else p.stop()
        p.onEvent(PanelEvent.SettingChanged(WallBasePrefs.KEY_ENABLED))
        device.submit {}.get(5, TimeUnit.SECONDS)
    }

    /** #210 review: a backend and transport chosen while the base is off are the ones it starts with. */
    @Test fun aBackendChosenWhileOffIsTheOneItStartsWith() {
        val p = paneWith(WallBase.Config(BaseBackend.LOCALSERVICE), FakeInit())
        try {
            p.start()
            turn(p, false)
            prefs.backend = "uart"
            prefs.transport = "tcp:127.0.0.1:1" // the emulator's, nothing listening: the link only retries
            p.onEvent(PanelEvent.SettingChanged(WallBasePrefs.KEY_BACKEND))
            p.onEvent(PanelEvent.SettingChanged(WallBasePrefs.KEY_TRANSPORT))
            device.submit {}.get(5, TimeUnit.SECONDS)
            turn(p, true)
            assertEquals(BaseBackend.UART to "tcp:127.0.0.1:1", p.config.backend to p.config.transport)
        } finally {
            p.stop()
            switch.on = true
            prefs.backend = "auto"
            prefs.transport = ""
        }
    }

    private fun turnedOffAfterUart(blocked: Boolean): String {
        val init = FakeInit()
        prefs.blockAqaraCloud = blocked
        // The emulator's transport, nothing listening: the link only retries.
        val p = paneWith(WallBase.Config(BaseBackend.UART, "tcp:127.0.0.1:1"), init)
        try {
            p.start()
            assertEquals("the UART link owns the port: ha_master stopped", "stopped", init.state)
            // What the shell does when the switch goes off: the pane stopped, then it hears its own key.
            switch.on = false
            p.stop()
            p.onEvent(PanelEvent.SettingChanged(WallBasePrefs.KEY_ENABLED))
            device.submit {}.get(5, TimeUnit.SECONDS)
            return init.state
        } finally {
            p.stop()
            switch.on = true
        }
    }

    private val switch get() = WallBaseDefinition(KioskPrefs(app).paneFile("wall-base")).switch

    @Test fun turnedOffItLetsAqarasDaemonHaveThePortAgain() = assertEquals("running", turnedOffAfterUart(blocked = false))

    @Test fun turnedOffItKeepsAqarasDaemonBlocked() = assertEquals("stopped", turnedOffAfterUart(blocked = true))
}
