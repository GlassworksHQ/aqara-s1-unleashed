package sh.edm.s1kiosk.camera

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.glassworks.camera.CameraDefinition
import dev.glassworks.camera.CameraSetupStep
import dev.glassworks.camera.CameraUse
import dev.glassworks.camera.CameraUsePickers
import dev.glassworks.pane.PaneChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.setup.ImprovFacts
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import sh.edm.s1kiosk.setup.SetupPane
import sh.edm.s1kiosk.setup.WifiSetupStatus
import sh.edm.s1kiosk.setup.WifiStep
import sh.edm.s1kiosk.ui.Mockup

/**
 * The tablet run on 28c157d: setup's Sign in step showed "Android allows the camera." The camera was
 * allowed at first launch, so its step was done while Sign in was current; opened from the rail (Panes,
 * then Camera), its Continue went back to Sign in without a step change, and the shared status line
 * kept its words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class CameraSetupLeakTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val file get() = KioskPrefs(app).paneFile("camera")

    @After fun tearDown() { file.edit().clear().commit() }

    private val cameraLines = setOf(CameraSetupStep.ALLOWED, CameraSetupStep.ASKS_ONCE, CameraSetupStep.REFUSED)

    private fun all(v: View): List<View> = listOf(v) + ((v as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())

    private fun visible(v: View): Boolean = generateSequence(v) { it.parent as? View }.all { it.visibility == View.VISIBLE }

    /** The camera's words anywhere on screen. */
    private fun cameraLineShown(s: SetupOverlay) = all(s).filterIsInstance<TextView>().any { it.text.toString() in cameraLines && visible(it) }

    internal fun tap(s: SetupOverlay, text: String) {
        val t = all(s).filterIsInstance<TextView>().firstOrNull { it.text.toString() == text && visible(it) }
            ?: error("no \"$text\" on screen: " + all(s).filterIsInstance<TextView>().filter { visible(it) && it.text.isNotBlank() }.map { it.text.toString() })
        generateSequence(t as View) { it.parent as? View }.first { it.isClickable }.performClick()
        Mockup.idle()
    }

    /** Opens [step] from the rail, unfolding its group first (the rail shows one group's steps at a time). */
    private fun open(s: SetupOverlay, step: String) {
        val group = when (step) { "Add it", "Sign in" -> "Home Assistant"; "Choose", "Camera" -> "Panes"; else -> null }
        if (group != null && all(s).filterIsInstance<TextView>().none { it.text.toString() == step && visible(it) }) tap(s, group)
        tap(s, step)
    }

    /** Setup with the camera's card on and its step done (use picked, Android allows it), Sign in current. */
    internal fun setUp(onStepDone: (SetupOverlay, SetupOverlay.Model) -> Unit = { s, m -> s.render(m) }): Pair<SetupOverlay, SetupOverlay.Model> {
        val a = Mockup.activity()
        val def = CameraDefinition(file, hasCamera = true, granted = { true }, pickers = CameraUsePickers { CameraUseCards(it) })
        def.switch.on = true
        def.prefs.use = CameraUse.PRESENCE
        val panes = listOf(
            SetupPane(PaneChoice("Voice", "Assist and timers", on = { true }, apply = { null }), emptyList()),
            SetupPane(def.setupChoice!!, def.setupSteps),
        )
        val s = SetupOverlay(a, panes)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        val m = SetupOverlay.Model(
            step = SetupFlow.SIGNIN, done = setOf(SetupFlow.WIFI, SetupFlow.HA, SetupFlow.PANES, "camera"),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"), haUrl = "http://homeassistant.local:8123",
            haSource = "mdns", panelIp = "192.0.2.41", signedIn = false, improv = WifiStep.improv(ImprovFacts()), installer = null, preview = false,
        )
        s.onStepDone = { onStepDone(s, m) } // the live screen draws the same state again: Sign in is still current
        s.render(m)
        Mockup.idle()
        return s to m
    }

    @Test fun theCameraLineStaysOnTheCameraStep() {
        val (s, _) = setUp()
        assertTrue("Sign in is current", all(s).filterIsInstance<TextView>().any { it.text.toString() == "Sign in to Home Assistant on your phone. Nothing to type here." && visible(it) })
        assertEquals(false, cameraLineShown(s))
        tap(s, "Panes") // the rail: steps that are done can be opened, Panes and then its Camera step
        tap(s, "Camera")
        assertTrue("the camera's step says Android allows it", cameraLineShown(s))
        tap(s, "Continue") // its Continue: the step is answered, back to the current step
        assertEquals("Sign in shows no camera line", false, cameraLineShown(s))
        // And no other step shows it either, opened from the rail after the camera's.
        for (step in listOf("Wi-Fi", "Add it", "Choose", "Sign in")) {
            open(s, "Camera")
            assertTrue(cameraLineShown(s))
            open(s, step)
            assertEquals("$step shows no camera line", false, cameraLineShown(s))
        }
    }
}
