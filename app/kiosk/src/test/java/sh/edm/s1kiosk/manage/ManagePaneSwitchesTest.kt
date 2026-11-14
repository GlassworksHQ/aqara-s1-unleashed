package sh.edm.s1kiosk.manage

import android.app.Application
import dev.glassworks.pane.PaneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
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
@org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
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
        val cantRun = HashSet<String>()
        for (d in runtime.paneDefinitions(app)) {
            val sw = d.switch ?: continue
            for (on in listOf(true, false)) {
                sw.on = on
                val s = manage.status()
                val row = s.panes.single { it.id == d.id }
                assertEquals(d.id, sw.title, row.title)
                val section = s.sections.single { it.id == row.sectionId }
                // A pane that can't run on this device (the Bluetooth proxy under Robolectric: no adapter) reads
                // Can't run while on; off still wins (paneRow).
                val expected = when {
                    !on -> PaneState.Kind.OFF
                    section.unavailable() != null -> PaneState.Kind.CANT_RUN.also { cantRun += d.id }
                    else -> PaneState.Kind.RUNNING
                }
                assertEquals("${d.id} on=$on", expected, row.state.kind)
                assertNotNull("${d.id}'s page has its on/off", section.master)
                assertEquals(on, section.master!!.value())
            }
            sw.on = sw.default
        }
        // Only the Bluetooth proxy lacks its hardware here; any other pane reading Can't run is a regression.
        assertEquals(setOf("bluetooth-proxy", "camera"), cantRun)
    }

    @Test fun thePanesTheManagePageLists() {
        val ids = manage.status().panes.map { it.id }
        assertTrue(ids.toString(), ids.containsAll(listOf("assist", "sendspin", "radio", "wall-base", "time-zone", "diagnostics")))
    }

    @Test fun aPanelSetUpWithoutHaCredentialsStillCountsAsSetUp() {
        assertFalse("a new panel: / is the QR sign-in", manage.setUp())
        // Home Assistant added the panel; it signed in on its own screen, so it holds no HA credentials.
        prefs.haSeen = true
        assertTrue(manage.setUp())
        prefs.haSeen = false
        prefs.haSetupSkipped = true
        assertTrue(manage.setUp())
    }

    @Test fun restartWaitsSoThePageReachesTheBrowserFirst() {
        val sent = ArrayList<android.content.Intent>()
        runtime.commandSink = { sent += it }
        assertNull(manage.actions.restartApp())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(S1Manage.ACTION_DELAY_MS - 100))
        assertTrue("not yet: the answer is still on its way", sent.isEmpty())
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200))
        assertTrue(sent.single().getBooleanExtra("restart", false))
    }
}
