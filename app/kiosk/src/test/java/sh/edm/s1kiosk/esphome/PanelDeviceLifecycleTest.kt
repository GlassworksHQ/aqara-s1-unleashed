package sh.edm.s1kiosk.esphome

import sh.edm.s1kiosk.frame.AqaraS1Plus
import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime

/** start() queues the startup on esphome-main, so it can still run after the service has stopped the device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PanelDeviceLifecycleTest {

    @Test fun aStartupThatRunsAfterStopStartsNothing() {
        val runtime = GlassworksRuntime(AqaraS1Plus())
        val device = PanelDevice(RuntimeEnvironment.getApplication(), runtime, onVoiceEvent = {})
        device.stop()

        device.startInternal()

        assertTrue("no panes were built", device.paneList().isEmpty())
        assertTrue("the radio pane never published its controller", runtime.radio == null)
    }
}
