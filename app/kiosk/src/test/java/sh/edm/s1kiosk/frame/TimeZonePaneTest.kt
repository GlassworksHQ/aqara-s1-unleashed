package sh.edm.s1kiosk.frame

import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.DeviceIdentity
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.radio.RadioHelper
import sh.edm.s1kiosk.radio.SysProps

/**
 * #67: the time zone pane turned off stops the root helper keeping the zone, and keeps the zone itself
 * for when it is turned on. The app stopping keeps the zone as it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TimeZonePaneTest {
    private val app = RuntimeEnvironment.getApplication()

    private class Props : SysProps {
        val p = hashMapOf(RadioHelper.PROP_VERSION to "4", TimeZoneKeeper.PROP to "Europe/Berlin")
        override fun get(name: String) = p[name] ?: ""
        override fun set(name: String, value: String) { p[name] = value }
        override fun sleep(ms: Long) {}
    }

    private val props = Props()
    private val switch = TimeZoneDefinition(KioskPrefs(app).paneFile("time-zone")).switch
    private val keeper = TimeZoneKeeper(props, zones = { setOf("Europe/Berlin") }, inline = true, log = {})
    private val pane = TimeZonePane(
        PaneContext(
            app, KioskPrefs(app)::paneFile,
            DeviceIdentity(
                name = "test", friendlyName = "Test", macAddress = "02:00:00:00:00:01", esphomeVersion = "x",
                compilationTime = "x", manufacturer = "x", model = "x", voiceFeatures = 0,
            ),
            publishStates = {}, emit = {}, entitiesChanged = {}, sendEvent = { _, _ -> },
        ),
        TimeZonePrefs(KioskPrefs(app).paneFile("time-zone")),
        keeper,
    )

    /** What the shell does for a switch: start or stop the pane, then tell every pane the key changed. */
    private fun turn(on: Boolean) {
        switch.on = on
        if (on) pane.start() else pane.stop()
        pane.onEvent(PanelEvent.SettingChanged(TimeZonePrefs.KEY_ENABLED))
    }

    @Test fun offLetsGoOfTheZoneAndOnKeepsItAgain() {
        pane.start()
        turn(false)
        assertEquals("the helper keeps no zone", "", props.p[TimeZoneKeeper.PROP])
        turn(true)
        assertEquals("the same zone again", "Europe/Berlin", props.p[TimeZoneKeeper.PROP])
        turn(false)
        turn(true)
        assertEquals("Europe/Berlin", props.p[TimeZoneKeeper.PROP])
    }

    /** #210 review: off, Home Assistant no longer reads the old zone as kept; on, it reads it again. */
    @Test fun offTheEntitiesSayNoZoneIsKept() {
        pane.start()
        assertEquals("Europe/Berlin", keeper.text())
        turn(false)
        assertEquals("", keeper.text())
        assertEquals("off: the panel keeps no time zone", keeper.statusText())
        turn(true)
        assertEquals("Europe/Berlin", keeper.text())
    }

    @Test fun theAppStoppingKeepsTheZone() {
        pane.start()
        pane.stop()
        assertEquals("Europe/Berlin", props.p[TimeZoneKeeper.PROP])
    }

    @Test fun offWithNoZoneChosenChangesNothing() {
        props.p[TimeZoneKeeper.PROP] = ""
        pane.start()
        turn(false)
        turn(true)
        assertEquals("", props.p[TimeZoneKeeper.PROP])
    }
}
