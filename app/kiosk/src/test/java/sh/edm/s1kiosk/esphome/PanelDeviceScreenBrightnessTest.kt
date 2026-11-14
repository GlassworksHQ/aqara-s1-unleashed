package sh.edm.s1kiosk.esphome

import android.app.Application
import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.frame.AqaraS1Plus

/** What the Screen light reports to Home Assistant, driven through its entities as HA does (#129). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceScreenBrightnessTest {

    @Test fun autoBrightnessBackOnBeforeALightReadingDoesNotReportTheSavedManualValue() {
        val app = RuntimeEnvironment.getApplication()
        val runtime = GlassworksRuntime(AqaraS1Plus())
        val device = PanelDevice(app, runtime, onVoiceEvent = {})
        try {
            device.initEntities(
                DeviceIdentity(
                    name = "aqara-s1-plus-000001", friendlyName = "Aqara S1 Plus", macAddress = "02:00:00:00:00:01",
                    esphomeVersion = PanelDevice.ESPHOME_VERSION, compilationTime = "test", manufacturer = "Aqara",
                    model = "test", voiceFeatures = PanelDevice.VOICE_FEATURES,
                ),
            )
            runtime.screen.start(app)
            fun entity(id: String): ApiEntity = device.entityList().single { it.objectId == id }
            fun reported() = ProtoMessage(entity("screen").state()!!.toByteArray()).float(3)
            fun command(id: String, w: ProtoWriter) {
                entity(id).command(ProtoMessage(w.toByteArray()))
                shadowOf(Looper.getMainLooper()).idle()
            }
            // No light reading arrives in this test, as before the sensor's first event.
            command("screen", ProtoWriter().bool(4, true).float(5, 0.4f))
            assertEquals("HA's brightness", 0.4f, reported(), 0f)
            assertEquals(0.4f, runtime.screen.brightness, 0f)

            command("auto_brightness", ProtoWriter().bool(2, true))
            assertEquals("the window follows the system brightness", -1f, runtime.screen.brightness, 0f)
            assertEquals("reported while the window follows the system", 1f, reported(), 0f)
        } finally {
            runtime.screen.stop()
            device.stop()
        }
    }
}
