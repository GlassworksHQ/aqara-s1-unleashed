package sh.edm.s1kiosk.base

import android.app.Application
import dev.glassworks.pane.EntityRegistry
import dev.glassworks.pane.PaneContext
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
import java.util.concurrent.Executors

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
}
