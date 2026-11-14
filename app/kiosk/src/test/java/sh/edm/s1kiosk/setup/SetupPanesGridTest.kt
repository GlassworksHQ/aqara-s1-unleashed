package sh.edm.s1kiosk.setup

import android.app.Application
import android.view.ViewGroup
import dev.glassworks.pane.PaneChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import sh.edm.s1kiosk.ui.Mockup

/**
 * The Panes step at five cards and more (#121, maintainer's pick 7): three to a row, so Continue
 * stays on the screen. With two to a row, a fifth card pushed it off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) // real text sizes: the room is what the fonts leave
class SetupPanesGridTest {
    @Before fun interFromSources() = Mockup.interFromSources()

    /** The panes setup offers once #118 and #121 are in, longest names included. */
    private val names = listOf(
        "Voice" to "Assist and timers", "Music" to "A Sendspin speaker", "Notifications" to "On-screen alerts",
        "Zigbee / Thread radio" to "For your devices", "Bluetooth proxy" to "For BLE sensors", "Wall base" to "The S1's wired base",
    )

    private fun cards(n: Int) = names.take(n).map { (t, d) -> SetupPane(PaneChoice(t, d, on = { false }, apply = { null }), emptyList()) }

    private fun model() = SetupOverlay.Model(
        step = SetupFlow.PANES, done = setOf(SetupFlow.WIFI, SetupFlow.HA),
        wifi = WifiSetupStatus.Snapshot(WifiSetupStatus.State.CONNECTED, "Example-WiFi"),
        haUrl = "http://homeassistant.local:8123", haSource = "mdns", panelIp = "192.0.2.10", signedIn = true,
    )

    /** The step with [n] cards, laid out at the panel's size. */
    private fun step(n: Int): SetupOverlay {
        val a = Mockup.activity()
        val s = SetupOverlay(a, cards(n))
        a.setContentView(s, ViewGroup.LayoutParams(-1, -1))
        s.render(model())
        Mockup.idle()
        Mockup.layout(s)
        return s
    }

    /** All of [v] is on the screen: not cut by the card or pushed out of it. */
    private fun whole(v: android.view.View) = android.graphics.Rect().let { r -> v.getGlobalVisibleRect(r) && r.height() == v.height && r.width() == v.width }

    private fun ellipsized(t: android.widget.TextView) = t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } ?: true

    /** At three to a row every line under a name is read in full, and so is every name but the radio's (it ends "ra…"). */
    @Test fun atThreeToARowTheCardsStillRead() {
        for (n in listOf(5, 6)) {
            val s = step(n)
            for ((title, detail) in names.take(n)) {
                assertTrue("$n panes: \"$detail\" is cut short", !ellipsized(Mockup.label(s, detail)))
                if (title != "Zigbee / Thread radio") assertTrue("$n panes: \"$title\" is cut short", !ellipsized(Mockup.label(s, title)))
            }
        }
    }

    @Test fun twoToARowUpToFourThenThree() {
        assertEquals(listOf(2, 2, 2, 2, 3, 3, 3), (1..7).map { SetupOverlay.panesPerRow(it) })
    }

    @Test fun everyCardAndContinueAreWholeOnTheScreenUpToSixPanes() {
        for (n in 1..6) {
            val s = step(n)
            assertTrue("$n panes: Continue is cut off", whole(Mockup.label(s, "Continue")))
            for ((title, _) in names.take(n)) {
                val t = Mockup.label(s, title).let { it.parent as android.view.View }.let { generateSequence(it) { v -> v.parent as? android.view.View }.first { c -> c.isClickable } }
                assertTrue("$n panes: the $title card is cut off", whole(t))
            }
        }
    }
}
