package sh.edm.s1kiosk.setup

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import dev.glassworks.pane.PaneChoice
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Glass
import sh.edm.s1kiosk.ui.Mockup
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The Panes step: what Continue applies, and when it asks for a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class SetupPanesTest {

    private val applied = Collections.synchronizedList(ArrayList<String>())
    private var voice = true
    private var radio = "off"

    private val choices = listOf(
        PaneChoice("Voice", "Assist and timers", on = { voice }, restartsApp = true, apply = { on, _ -> applied += "voice $on"; null }),
        PaneChoice(
            "Zigbee / Thread radio", "For your devices", on = { radio != "off" },
            options = listOf(PaneChoice.Option("zigbee_tcp", "Zigbee coordinator"), PaneChoice.Option("thread", "Thread border router")),
            option = { if (radio == "off") "zigbee_tcp" else radio },
            apply = { on, o -> applied += "radio ${if (on) o else "off"}"; null },
        ),
    )

    private fun panes(): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, choices)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = setOf(SetupFlow.WIFI, SetupFlow.HA),
            wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = false,
        ))
        Mockup.idle()
        return s
    }

    private fun <T : View> all(root: View, type: Class<T>): List<T> = buildList {
        fun walk(v: View) {
            if (type.isInstance(v) && v.isShown) add(type.cast(v)!!)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
    }

    /** Taps Continue and waits for the cards to be applied; the answer onPanesDone got. */
    private fun continueAndWait(s: SetupOverlay): Boolean? {
        var restart: Boolean? = null
        val done = CountDownLatch(1)
        s.onPanesDone = { restart = it; done.countDown() }
        Mockup.label(s, "Continue").performClick()
        val until = System.currentTimeMillis() + 5_000
        while (done.count > 0 && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            done.await(20, TimeUnit.MILLISECONDS)
        }
        return restart
    }

    @Test
    fun continueAppliesEveryCardAsSet() {
        val s = panes()
        all(s, Glass.Toggle::class.java)[1].performClick() // the radio on: the coordinator is offered first
        Mockup.label(s, "Thread border router").performClick()
        assertEquals(false, continueAndWait(s))
        assertEquals(listOf("voice true", "radio thread"), applied)
    }

    @Test
    fun turningVoiceOffAsksForARestart() {
        val s = panes()
        all(s, Glass.Toggle::class.java)[0].performClick()
        assertEquals(true, continueAndWait(s))
        assertEquals(listOf("voice false", "radio off"), applied)
    }

    @Test
    fun aPreviewNeverAppliesAnything() {
        val a = Mockup.activity()
        val s = SetupOverlay(a, choices)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = emptySet(), wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = null, haSource = "", panelIp = null, signedIn = false, preview = true,
        ))
        Mockup.idle()
        assertEquals(false, continueAndWait(s))
        assertEquals(emptyList<String>(), applied)
    }

    @Test
    fun aProblemStaysOnTheStep() {
        val failing = listOf(PaneChoice("Music", "A Sendspin speaker", on = { true }, apply = { _, _ -> "the player didn't start" }))
        val a = Mockup.activity()
        val s = SetupOverlay(a, failing)
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(SetupOverlay.Model(
            step = SetupFlow.PANES, done = emptySet(), wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
            haUrl = null, haSource = "", panelIp = null, signedIn = false,
        ))
        Mockup.idle()
        var called = false
        s.onPanesDone = { called = true }
        Mockup.label(s, "Continue").performClick()
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until && runCatching { Mockup.label(s, "Music: the player didn't start") }.isFailure) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        Mockup.label(s, "Music: the player didn't start")
        assertEquals(false, called)
    }
}
