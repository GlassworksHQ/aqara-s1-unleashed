package sh.edm.s1kiosk.setup

import android.app.Application
import dev.glassworks.pane.SetupStep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.GlassworksRuntime
import sh.edm.s1kiosk.KioskPrefs
import sh.edm.s1kiosk.frame.AqaraS1Plus
import sh.edm.s1kiosk.radio.RadioController
import sh.edm.s1kiosk.radio.TestInit
import java.io.File

/**
 * Setup without the screen: every pane step the S1 has (its real definitions, with a radio controller
 * on a fake init) is finished with setup channel requests alone, and the requests Kiln sends
 * (kiln/apps/kiln/src/wizard/session.ts through kiln/packages/setup-channel) still get the replies
 * channel.ts expects. The replies are pinned in kiln/packages/setup-channel/test/s1-kiosk-transcript.json,
 * which setup-channel's own tests replay through channel.ts. Regenerate it with -Ps1RegenGolden=true.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SetupChannelStepsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var runtime: GlassworksRuntime
    private lateinit var steps: List<SetupStep>

    /**
     * How each pane step is finished over the setup channel, as the installer would. A pane that adds a
     * step adds its requests here; [everyPaneStepHasAChannelPath] fails until it does.
     */
    private val viaChannel = mapOf(
        "radio" to listOf("""{"id":1,"op":"radio.set_mode","mode":"off"}"""),
    )

    /** The core's part, as the app's backend does it (KioskPrefs), without the main-thread hop. */
    private inner class Core : SetupChannel.Backend {
        val prefs = KioskPrefs(app)
        override fun state() = ChannelState(
            ip = IP,
            ha = ChannelState.Ha(
                discovered = null, url = prefs.haBase.orEmpty(), signedIn = false,
                signinUrl = ChannelState.signinUrl(IP, prefs.haBase),
            ),
        )
        override fun setHa(url: String): SetupChannel.HaChange {
            prefs.urlSetting = url
            return SetupChannel.HaChange()
        }
    }

    @Before fun s1() {
        runtime = GlassworksRuntime(AqaraS1Plus())
        val prefs = KioskPrefs(app)
        runtime.radio = RadioController(
            TestInit(), load = { prefs.radio.mode }, store = { prefs.radio.mode = it }, inline = true, log = {}, filesDir = tmp.root,
        ).also { it.start() }
        steps = runtime.paneDefinitions(app).flatMap { it.setupSteps }
    }

    private fun channel() = SetupChannel(Core(), steps)

    private fun reply(c: SetupChannel, line: String) = JSONObject(c.handle(line).first)

    @Test
    fun everyPaneStepHasAChannelPath() {
        assertEquals(listOf("radio"), steps.map { it.id })
        assertEquals(steps.map { it.id }.toSet(), viaChannel.keys)
        assertTrue(steps.all { it.ops.isNotEmpty() })
    }

    @Test
    fun everyPaneStepIsFinishedOverTheChannelAlone() {
        val c = channel()
        for (step in steps) {
            assertFalse("${step.id} starts unanswered on a fresh panel", step.done())
            for (line in viaChannel.getValue(step.id)) {
                val r = reply(c, line)
                assertTrue("$line: $r", r.getBoolean("ok"))
            }
            assertTrue("${step.id} is done after its requests", step.done())
        }
        // ... and the setup screen moves past them to Sign in, the step that is done in a browser.
        val d = SetupState.decide(
            SetupInputs(
                online = true, offlineForMs = 0, haKnown = true, haSeen = true, apiKeySet = true, haSkipped = false,
                haHelloThisProcess = true, uptimeMs = 5_000, signedIn = false, onAuthPage = true, signedOut = false,
                usePanelLogin = false, paneSteps = steps.map { PaneStepFacts(it.id, it.done(), it.reason) },
            ),
        )
        assertEquals(SetupFlow.SIGNIN, d.step)
    }

    @Test
    fun theCoreStepsGiveWhatTheInstallerNeeds() {
        val c = channel()
        val before = reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").getJSONObject("ha")
        assertFalse(before.has("signinUrl")) // no Home Assistant yet: nothing to sign in to
        val after = reply(c, """{"id":2,"op":"ha.set_url","url":"http://homeassistant.local:8123"}""").getJSONObject("state").getJSONObject("ha")
        assertEquals("http://homeassistant.local:8123", after.getString("url"))
        // Sign in is done in a browser at this link (Kiln opens it); Wi-Fi goes over Improv Serial.
        assertEquals("http://$IP:8099/signin", after.getString("signinUrl"))
    }

    /** What Kiln sends, in session.ts order: the state, the HA address, the state again, the radio role. */
    private val kiln = listOf(
        """{"id":1,"op":"state"}""",
        """{"id":2,"op":"set_ha","url":"http://homeassistant.local:8123"}""",
        """{"id":3,"op":"state"}""",
        """{"id":4,"op":"set_radio","mode":"zigbee_tcp"}""",
        """{"id":5,"op":"set_radio","mode":"off"}""",
    )

    @Test
    fun kilnsRequestsStillGetTheRepliesItExpects() {
        val c = channel()
        val transcript = JSONArray()
        for (line in kiln) transcript.put(JSONObject().put("request", JSONObject(line)).put("reply", reply(c, line)))
        val radio = steps.single { it.id == "radio" }
        assertTrue("Kiln's set_radio answers the panel's radio step", radio.done())

        val file = transcriptFile()
        if (System.getProperty("s1.regenGolden") == "true") {
            file.writeText(transcript.toString(2).replace("\\/", "/") + "\n")
            return
        }
        val golden = JSONArray(file.readText())
        assertEquals(golden.length(), transcript.length())
        for (i in 0 until golden.length()) {
            assertTrue(
                "reply ${i + 1} changed: ${transcript.getJSONObject(i)} vs pinned ${golden.getJSONObject(i)}",
                same(golden.get(i), transcript.get(i)),
            )
        }
    }

    /** JSON equality that ignores key order. */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.keys().asSequence().toSet() == b.keys().asSequence().toSet() &&
            a.keys().asSequence().all { same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    private fun transcriptFile(): File {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "THIRD-PARTY-NOTICES.md").isFile }
        return File(root, "kiln/packages/setup-channel/test/s1-kiosk-transcript.json")
    }

    private companion object {
        const val IP = "192.0.2.10"
    }
}
