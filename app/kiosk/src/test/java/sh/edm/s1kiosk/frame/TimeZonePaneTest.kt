package sh.edm.s1kiosk.frame

import android.app.Application
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val p = hashMapOf(
            RadioHelper.PROP_VERSION to "4", TimeZoneKeeper.PROP to "Europe/Berlin",
            "persist.sys.timezone" to "Europe/Berlin", TimeZoneKeeper.PROP_STATUS to "ok Europe/Berlin",
        )
        override fun get(name: String) = p[name] ?: ""
        override fun set(name: String, value: String) { p[name] = value }
        override fun sleep(ms: Long) {}

        /** One check of the root helper v4 (tools/radio/s1kiosk_radio.sh tz_check), as it treats the property. */
        fun helper() {
            val want = p[TimeZoneKeeper.PROP].orEmpty()
            val have = p["persist.sys.timezone"].orEmpty()
            val valid = { z: String -> z.isNotEmpty() && z.all { it.isLetterOrDigit() || it in "_+/-" } }
            when {
                want.isEmpty() && valid(have) && have != "Antarctica/Casey" -> {
                    p[TimeZoneKeeper.PROP] = have // "adopted $have as the zone to keep"
                    p[TimeZoneKeeper.PROP_STATUS] = "ok $have"
                }
                want.isEmpty() -> p[TimeZoneKeeper.PROP_STATUS] = "none: no time zone chosen"
                !valid(want) -> p[TimeZoneKeeper.PROP_STATUS] = "error: '$want' is not a time zone id"
                else -> { p["persist.sys.timezone"] = want; p[TimeZoneKeeper.PROP_STATUS] = "ok $want" }
            }
        }
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

    /** #212: the settings page picks a zone, or Home Assistant's once it said, the way HA's time_zone does. */
    @Test fun thePagePicksAZoneAndKnowsHomeAssistants() {
        turn(true)
        assertEquals(null, pane.homeAssistantZone)
        pane.onEvent(PanelEvent.HomeAssistantTimeZone("Europe/Berlin"))
        assertEquals("Europe/Berlin", pane.homeAssistantZone)
        assertEquals(null, pane.keep("Europe/Berlin"))
        assertEquals("Europe/Berlin", pane.kept)
        assertEquals("Europe/Berlin", props.get(TimeZoneKeeper.PROP))
        assertEquals("'Mars/Olympus' is not a time zone this panel knows", pane.keep("Mars/Olympus"))
    }

    /**
     * The panel run on 39d668e (2026-11-14): off cleared the property, and within a second the helper
     * adopted the zone in place again. Off must stay off through the helper's checks.
     */
    @Test fun offStaysOffThroughTheHelpersChecks() {
        pane.start()
        turn(false)
        repeat(3) { props.helper() }
        assertEquals("the helper adopted no zone", TimeZoneKeeper.OFF, props.p[TimeZoneKeeper.PROP])
        assertEquals("and keeps none", "error: '(off)' is not a time zone id", props.p[TimeZoneKeeper.PROP_STATUS])
    }

    /** The status says off only once the helper has let go; before that, that it waits. */
    @Test fun theStatusSaysOffOnlyOnceTheHelperLetGo() {
        pane.start()
        turn(false)
        assertEquals("turning off: waiting for the root helper to let go of Europe/Berlin", keeper.statusText())
        props.helper()
        assertTrue(keeper.checkOff())
        assertEquals("off: the panel keeps no time zone", keeper.statusText())
    }

    @Test fun withNoRootHelperOffIsOffAtOnce() {
        props.p[RadioHelper.PROP_VERSION] = "3"
        pane.start()
        turn(false)
        assertEquals("off: the panel keeps no time zone", keeper.statusText())
    }

    @Test fun offLetsGoOfTheZoneAndOnKeepsItAgain() {
        pane.start()
        turn(false)
        assertEquals("the helper keeps no zone", TimeZoneKeeper.OFF, props.p[TimeZoneKeeper.PROP])
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
        props.helper()
        keeper.checkOff()
        assertEquals("off: the panel keeps no time zone", keeper.statusText())
        turn(true)
        assertEquals("Europe/Berlin", keeper.text())
    }

    @Test fun theAppStoppingKeepsTheZone() {
        pane.start()
        pane.stop()
        assertEquals("Europe/Berlin", props.p[TimeZoneKeeper.PROP])
    }

    @Test fun offWithNoZoneChosenAdoptsNoneAndOnLeavesTheChoiceToTheHelper() {
        props.p[TimeZoneKeeper.PROP] = ""
        pane.start()
        turn(false)
        props.helper()
        assertEquals("off adopts nothing", TimeZoneKeeper.OFF, props.p[TimeZoneKeeper.PROP])
        turn(true)
        assertEquals("on: no zone chosen, as before off", "", props.p[TimeZoneKeeper.PROP])
    }
}
