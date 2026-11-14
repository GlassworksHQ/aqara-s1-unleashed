package sh.edm.s1kiosk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.glassworks.frame.Frame
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.pane.PaneChoice
import dev.glassworks.pane.SettingsItem
import dev.glassworks.pane.SettingsSection
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupStep
import dev.glassworks.pane.settingsOrder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import sh.edm.s1kiosk.setup.SetupChannelServer
import sh.edm.s1kiosk.setup.SetupFlow
import sh.edm.s1kiosk.setup.SetupOverlay
import java.io.File
import java.util.concurrent.ScheduledExecutorService

/**
 * What a frame's panes add to setup and settings, through the same runtime as the S1's: the real
 * definitions fit the setup flow, a fake frame's step and section land after the core's panes, and a
 * step with a clashing id is left out with an error in the log instead of stopping the app. The setup
 * screen and the setup channel take their steps from GlassworksRuntime.setupSteps alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FramePanesTest {

    private val app get() = RuntimeEnvironment.getApplication()
    private val runtime = GlassworksRuntime(AqaraS1Plus())

    /** The S1's runtime with [frame]'s panes in place of the S1's own. */
    private fun runtimeWith(frame: Frame) = GlassworksRuntime(AqaraS1Plus(), paneFrame = frame)

    /**
     * A frame with one pane, "camera": one settings section, and a setup step for each of [steps]
     * (id to its one request's name, which answers ok).
     */
    private class FakeFrame(private vararg val steps: Pair<String, String>, private val card: Boolean = true) : Frame {
        override val board = "fake-frame"
        override val presence = null
        override val light = null
        override fun systemSettingsIntents() = emptyList<Intent>()
        override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()
        override fun paneDefinitions(context: Context) = listOf(object : PaneDefinition {
            override val id = "camera"
            override val setupSteps = steps.map { (id, op) -> step(id, op) }
            override val setupChoice = if (card) PaneChoice("Camera", "A doorbell view", on = { true }, apply = { null }) else null
            override fun settings() = listOf(SettingsSection("camera", "Camera", listOf(SettingsItem.Note { "Camera on" })))
        })

        private fun step(id: String, op: String) = object : SetupStep {
            override val id = id
            override val title = "Camera"
            override val ops = mapOf(op to SetupOp { null })
            override val reason = "camera not set up"
            override fun done() = false
            override fun screen(context: Context, host: SetupHost) = throw UnsupportedOperationException()
        }
    }

    @Test
    fun theS1sStepsAllFitTheSetupFlow() {
        val steps = runtime.paneDefinitions(app).flatMap { it.setupSteps }
        val skipped = mutableListOf<String>()
        assertEquals(steps, SetupFlow.usable(steps, skipped::add))
        assertEquals(emptyList<String>(), skipped)
        assertEquals(listOf("radio"), steps.map { it.id }) // a sub-step under Panes
        assertEquals(listOf("wifi", "ha", "signin", "panes", "radio", "done"), SetupFlow.ids(true, steps.map { it.id }))
        assertEquals(steps, runtime.setupSteps(app))
    }

    @Test
    fun aFramesStepAndSectionComeAfterTheCorePanes() {
        val r = runtimeWith(FakeFrame("camera" to "look"))
        val definitions = r.paneDefinitions(app)
        val steps = r.setupSteps(app)
        assertEquals(listOf("radio", "camera"), steps.map { it.id })

        val core = CoreSettings(
            KioskPrefs(app), "0.0.0", status = { "screen on" }, deviceSettings = null,
            licences = SettingsItem.Link(CoreSettings.LICENCES_TITLE, CoreSettings.LICENCES_DETAIL) {},
        )
        val sections = settingsOrder(core.sections(), definitions.flatMap { it.settings() }).map { it.id }
        val radio = sections.indexOf("radio")
        assertTrue(sections.toString(), radio > sections.indexOf("screen"))
        assertEquals("the frame's right after the core's last pane: $sections", sections.indexOf("bluetooth-proxy") + 1, sections.indexOf("camera"))
        assertTrue(sections.toString(), sections.indexOf("camera") < sections.indexOf("about"))
    }

    @Test
    fun aClashingStepIsLeftOutAndSetupStillStarts() {
        ShadowLog.clear()
        val r = runtimeWith(FakeFrame("wifi" to "go", "camera" to "look", "camera" to "pan", "radio" to "tune"))
        val steps = r.setupSteps(app)
        assertEquals(listOf("radio", "camera"), steps.map { it.id })
        val errors = ShadowLog.getLogsForTag("S1Kiosk").filter { it.type == Log.ERROR }.map { it.msg }
        assertEquals(errors.toString(), 3, errors.size)
        assertTrue(errors.toString(), errors[0].contains("'wifi'"))

        // The setup screen, as MainActivity.onCreate builds it, still starts.
        SetupOverlay(app, r.setupPanes(app))

        // Each setup channel connection, as SetupChannelServer makes it: the kept step answers, a
        // dropped step's requests don't exist.
        val channel = SetupChannelServer.channels(app, r, object : SetupChannel.Backend {
            override fun state() = ChannelState(ip = "192.0.2.10", ha = ChannelState.Ha(null, "", false, null))
            override fun setHa(url: String) = SetupChannel.HaChange()
        })()
        fun reply(op: String) = JSONObject(channel.handle("""{"id":1,"op":"$op"}""").first)
        assertTrue(reply("state").getBoolean("ok"))
        assertTrue(reply("camera.look").getBoolean("ok"))
        for (dropped in listOf("wifi.go", "camera.pan", "radio.tune")) {
            val rp = reply(dropped)
            assertFalse(dropped, rp.getBoolean("ok"))
            assertTrue("$dropped: $rp", rp.toString().contains("Unknown request"))
        }
    }

    /**
     * Only GlassworksRuntime reads PaneDefinition.setupSteps. Everything else (MainActivity's setup
     * screen and `--es setup_step`, SetupChannelServer) calls GlassworksRuntime.setupSteps(...), so a
     * clashing step can't reach them. A source scan: it matches `.setupSteps` or `::setupSteps` that
     * isn't a call, outside comments.
     */
    @Test
    fun onlyTheRuntimeReadsAPanesSetupSteps() {
        val appRoot = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "kiosk/src/main").isDirectory }
        val main = File(appRoot, "kiosk/src/main/java")
        val read = Regex("""(\.|::)setupSteps\b(?!\s*\()""")
        val sources = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("scan found no sources", sources.size > 100)
        val found = sources.filter { it.name != "GlassworksRuntime.kt" }.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, line ->
                val code = line.trim()
                val comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//")
                if (!comment && read.containsMatchIn(code)) "${f.relativeTo(appRoot).path}:${i + 1}: $code" else null
            }
        }
        assertTrue("PaneDefinition.setupSteps read outside GlassworksRuntime; use GlassworksRuntime.setupSteps:\n${found.joinToString("\n")}", found.isEmpty())
        assertTrue(read.containsMatchIn(File(main, "sh/edm/s1kiosk/GlassworksRuntime.kt").readText()))
    }

    @Test
    fun aPanesStepsWithoutACardAreLeftOut() {
        // F6: a pane's steps sit under Panes only while its card is on; with no card they could never be
        // answered on the panel, so setup leaves them out (and says so) instead of waiting for ever.
        ShadowLog.clear()
        val r = runtimeWith(FakeFrame("camera" to "look", card = false))
        assertEquals(listOf("radio"), r.setupSteps(app).map { it.id })
        val errors = ShadowLog.getLogsForTag("S1Kiosk").filter { it.type == Log.ERROR }.map { it.msg }
        assertTrue(errors.toString(), errors.any { "'camera'" in it && "no card" in it })
    }
}
