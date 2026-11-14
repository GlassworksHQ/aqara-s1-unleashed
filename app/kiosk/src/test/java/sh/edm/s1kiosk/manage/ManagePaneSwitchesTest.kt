package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.pane.PaneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.auth.HaTokenClient
import sh.edm.s1kiosk.auth.HttpPoster
import sh.edm.s1kiosk.auth.SignInServer
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * #33 on #67: the S1's Manage page reads each pane's name and on/off from the sections the panel's menu
 * draws (`paneSections`), so a pane turned off on the panel, in Home Assistant or with adb reads Off, and
 * its page has its on/off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ManagePaneSwitchesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val runtime = GlassworksRuntime(AqaraS1Plus())
    private val prefs = KioskPrefs(app)
    private val tokens = HaTokenClient(object : HttpPoster {
        override fun postForm(url: String, body: String) = 0 to ""
        override fun get(url: String, maxBytes: Int) = 0 to ""
    })
    private val manage = S1Manage(app, runtime, SignInServer(0, { null }, tokens, { _, _ -> }), tokens, prefs)

    @Test fun everySwitchedPaneReadsItsSwitch() {
        for (d in runtime.paneDefinitions(app)) {
            val sw = d.switch ?: continue
            for (on in listOf(true, false)) {
                sw.on = on
                val s = manage.status()
                val row = s.panes.single { it.id == d.id }
                assertEquals(d.id, sw.title, row.title)
                assertEquals("${d.id} on=$on", if (on) PaneState.Kind.RUNNING else PaneState.Kind.OFF, row.state.kind)
                val section = s.sections.single { it.id == row.sectionId }
                assertNotNull("${d.id}'s page has its on/off", section.master)
                assertEquals(on, section.master!!.value())
            }
            sw.on = sw.default
        }
    }

    @Test fun thePanesTheManagePageLists() {
        val ids = manage.status().panes.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("assist", "sendspin", "radio", "wall-base", "time-zone", "diagnostics")))
    }
}
