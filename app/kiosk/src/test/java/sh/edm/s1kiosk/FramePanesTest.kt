package sh.edm.s1kiosk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.frame.Frame
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.SetupCard
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupScreen
import dev.glassworks.pane.SetupStep
import dev.glassworks.pane.settingsOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.settings.CoreSettings
import sh.edm.s1kiosk.setup.ChannelState
import sh.edm.s1kiosk.setup.SetupChannel
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import java.util.concurrent.ScheduledExecutorService

/**
 * What a frame's panes add to setup and settings, through the same runtime as the S1's: the real
 * definitions fit the setup flow, a fake frame's step and section land after the core's panes, and a
 * step with a clashing id is left out with an error in the log instead of stopping the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FramePanesTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val runtime = GlassworksRuntime(AqaraS1Plus())

    /** A frame with one pane, "camera": a setup step for each of [stepIds] and one settings section. */
    private class FakeFrame(private vararg val stepIds: String) : Frame {
        override val board = "fake-frame"
        override val presence = null
        override val light = null
        override fun systemSettingsIntents() = emptyList<Intent>()
        override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()
        override fun paneDefinitions(context: Context) = listOf(object : PaneDefinition {
            override val id = "camera"
            override val setupSteps = stepIds.map(::step)
            override fun settings() = listOf(SettingsSection("camera", "Camera", listOf(SettingsItem.Note { "Camera on" })))
        })

        private fun step(id: String) = object : SetupStep {
            override val id = id
            override val title = "Camera"
            override val reason = "camera not set up"
            override fun done() = false
            override fun detail(done: Boolean) = ""
            override fun screen(context: Context, host: SetupHost) = object : SetupScreen {
                override fun bind(card: SetupCard, entered: Boolean) = card.title("Camera")
            }
        }
    }

    @Test
    fun theS1sStepsAllFitTheSetupFlow() {
        val steps = runtime.paneDefinitions(app).flatMap { it.setupSteps }
        val skipped = mutableListOf<String>()
        assertEquals(steps, SetupFlow.usable(steps, skipped::add))
        assertEquals(emptyList<String>(), skipped)
        assertEquals(listOf("wifi", "ha", "radio", "signin", "done"), SetupFlow.ids(steps.map { it.id }))
        assertEquals(steps, runtime.setupSteps(app))
    }

    @Test
    fun aFramesStepAndSectionComeAfterTheCorePanes() {
        val definitions = runtime.paneDefinitions(app, FakeFrame("camera"))
        val steps = runtime.setupSteps(app, definitions)
        assertEquals(listOf("wifi", "ha", "radio", "camera", "signin", "done"), SetupFlow.ids(steps.map { it.id }))

        val core = CoreSettings(
            KioskPrefs(app), "0.0.0", status = { "screen on" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        )
        val sections = settingsOrder(core.sections(), definitions.flatMap { it.settings() }).map { it.id }
        val radio = sections.indexOf("radio")
        assertTrue(sections.toString(), radio > sections.indexOf("screen"))
        assertEquals(sections.toString(), radio + 1, sections.indexOf("camera"))
        assertTrue(sections.toString(), sections.indexOf("camera") < sections.indexOf("about"))
    }

    @Test
    fun aClashingStepIsLeftOutAndSetupStillStarts() {
        ShadowLog.clear()
        val steps = runtime.setupSteps(app, runtime.paneDefinitions(app, FakeFrame("wifi", "camera", "camera", "radio")))
        assertEquals(listOf("radio", "camera"), steps.map { it.id })
        val errors = ShadowLog.getLogsForTag("S1Kiosk").filter { it.type == Log.ERROR }.map { it.msg }
        assertEquals(errors.toString(), 3, errors.size)
        assertTrue(errors.toString(), errors[0].contains("'wifi'"))

        // What MainActivity.onCreate and every setup channel connection build from them.
        SetupOverlay(app, steps)
        val channel = SetupChannel(object : SetupChannel.Backend {
            override fun state() = ChannelState(ip = "192.0.2.10", ha = ChannelState.Ha(null, "", false, null))
            override fun setHa(url: String) = SetupChannel.HaChange()
        }, steps)
        assertTrue(JSONObject(channel.handle("""{"id":1,"op":"state"}""").first).getBoolean("ok"))
    }
}
