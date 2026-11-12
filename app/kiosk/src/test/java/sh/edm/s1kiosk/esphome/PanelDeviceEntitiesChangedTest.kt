package sh.edm.s1kiosk.esphome

import sh.edm.s1kiosk.frame.AqaraS1Plus
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.base.BaseBackend
import sh.edm.s1kiosk.base.BaseKind
import sh.edm.s1kiosk.base.BaseLayout
import sh.edm.s1kiosk.base.WallBase
import sh.edm.s1kiosk.base.WallBasePane

/** A pane's entitiesChanged makes the shell rebuild the whole list: its own entities and every pane's. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceEntitiesChangedTest {

    @Test fun aNewBaseLayoutAddsItsEntitiesAndKeepsTheRest() {
        val app = RuntimeEnvironment.getApplication()
        KioskPrefs(app).wallBase.layout = ""
        val device = PanelDevice(app, GlassworksRuntime(AqaraS1Plus()), onVoiceEvent = {})
        try {
            device.initEntities(
                DeviceIdentity(
                    name = PanelDevice.NAME, friendlyName = PanelDevice.FRIENDLY_NAME, macAddress = "02:00:00:00:00:01",
                    esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Aqara",
                    model = "test", voiceFeatures = PanelDevice.VOICE_FEATURES,
                ),
                WallBase.Config(BaseBackend.LOCALSERVICE),
            )
            val before = device.entityList().map { it.objectId }
            val pane = device.paneList().filterIsInstance<WallBasePane>().single()

            pane.baseListener.onLayout(BaseLayout(BaseKind.SWITCH, listOf(1, 2)))

            val end = System.currentTimeMillis() + 5_000
            while (device.entityList().none { it.objectId == "base_relay_2" } && System.currentTimeMillis() < end) Thread.sleep(10)
            val added = listOf(
                "base_firmware", "base_relay_1", "base_relay_2", "base_power", "base_energy", "base_protection",
                "base_button_1", "base_button_2",
            )
            assertEquals((before + added).sorted(), device.entityList().map { it.objectId }.sorted())
        } finally {
            device.stop()
        }
    }
}
