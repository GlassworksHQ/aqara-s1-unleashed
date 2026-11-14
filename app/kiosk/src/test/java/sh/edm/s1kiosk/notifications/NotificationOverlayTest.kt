package sh.edm.s1kiosk.notifications

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import dev.glassworks.notifications.NotificationCenter
import dev.glassworks.notifications.NotificationOutputs
import dev.glassworks.notifications.NotificationStore
import dev.glassworks.notifications.NotificationsHub
import dev.glassworks.notifications.ActionReport
import dev.glassworks.notifications.Sound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.ui.Mockup
import java.util.concurrent.CompletableFuture

/** The overlay sends taps back to the hub, opens and closes full-screen views, and handles Back. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w960dp-h480dp-land-hdpi")
class NotificationOverlayTest {
    private val hub = NotificationsHub()
    private val reports = mutableListOf<ActionReport>()
    private val pages = mutableListOf<String?>()
    private val center = NotificationCenter(
        NotificationStore({ 0L }),
        object : NotificationOutputs {
            override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) =
                hub.publish(NotificationsHub.State(cards, page, list))
            override fun wake(overNight: Boolean) {}
            override fun play(sound: Sound) {}
        },
        { reports += it }, { false }, { "http://homeassistant.local:8123" }, { 0L }, { _, _ -> CompletableFuture<Unit>() },
    ).also { hub.attach(it) }

    private val overlay = Mockup.show(FrameLayout(Mockup.activity())).let { root ->
        NotificationOverlay(root.context, hub, images = null, now = { 0L }).also {
            root.addView(it, FrameLayout.LayoutParams(-1, -1))
            it.onPage = { url -> pages += url }
            hub.addListener { s -> it.update(s) }
        }
    }

    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }

    private fun click(text: String) {
        Mockup.layout(overlay.rootView)
        views(overlay).filterIsInstance<TextView>().first { it.text.toString() == text && it.isShown }.performClick()
        Mockup.idle()
    }

    @Test
    fun aButtonReportsAndCloses() {
        center.handle("Someone is at the door", "Doorbell", """{"tag":"doorbell","actions":[{"action":"open_gate","title":"Open the gate"}]}""")
        click("Open the gate")
        assertEquals(listOf(ActionReport("open_gate", "doorbell")), reports)
        assertTrue(hub.state.cards.isEmpty())
    }

    @Test
    fun theCrossDismisses() {
        center.handle("m", "Washer", "")
        click("×")
        assertTrue(hub.state.list.isEmpty())
        assertTrue(reports.isEmpty())
    }

    @Test
    fun aButtonWithAUriOpensItFullScreen() {
        center.handle("m", "Garage", """{"actions":[{"action":"look","title":"Look","uri":"/lovelace/garage"}]}""")
        click("Look")
        assertEquals(listOf<String?>("http://homeassistant.local:8123/lovelace/garage"), pages)
        assertEquals("Garage", hub.state.page?.notification?.title)
    }

    @Test
    fun aPageOpensAndBackNowReturns() {
        center.handle("Someone is at the door", "Doorbell", """{"url":"/dashboard-door/0","timeout":90}""")
        assertEquals(listOf<String?>("http://homeassistant.local:8123/dashboard-door/0"), pages)
        click("Back now")
        assertEquals(listOf("http://homeassistant.local:8123/dashboard-door/0", null), pages)
        assertNull(hub.state.page)
    }

    @Test
    fun backClosesTheListThenThePage() {
        center.handle("m", "Doorbell", """{"url":"/dashboard-door/0"}""")
        overlay.openList()
        assertTrue(overlay.back())
        assertFalse(overlay.listOpen)
        assertTrue(overlay.back())
        assertNull(hub.state.page)
        assertFalse("nothing left to close", overlay.back())
    }

    @Test
    fun theCriticalStageAlwaysHasDismiss() {
        center.handle("Under the sink", "Water leak", """{"priority":"critical","actions":[{"action":"close_valve","title":"Close the valve"}]}""")
        click("Dismiss")
        assertTrue(hub.state.cards.isEmpty())
        assertTrue("dismissing isn't a button press", reports.isEmpty())
    }
}
