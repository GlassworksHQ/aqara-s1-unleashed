/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import android.app.Application
import android.content.Context
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.esphome.ApiAction
import sh.edm.esphome.ActionCall
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.HomeAssistantEvent
import sh.edm.esphome.ProtoMessage
import sh.edm.esphome.ProtoWriter
import sh.edm.esphome.SwitchEntity
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotificationsPaneTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = NotificationsPrefs(app.getSharedPreferences("notifications-test", Context.MODE_PRIVATE))
    private val hub = NotificationsHub()
    private val fired = mutableListOf<HomeAssistantEvent>()
    private val events = mutableListOf<PanelEvent>()
    private var published = 0
    private val screen = mutableListOf<String>()
    private val played = mutableListOf<Sound>()
    private var silenced = 0
    private var t = 0L
    private val timers = mutableListOf<Triple<Long, () -> Unit, CompletableFuture<Unit>>>()

    private val pane = NotificationsPane(
        PaneContext(
            app, { error("no prefs") },
            DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
            publishStates = { published++ }, emit = { events += it }, entitiesChanged = {}, sendEvent = { _, _ -> },
            fireEvent = { fired += it },
        ),
        prefs, hub,
        screen = object : NotificationScreen {
            override fun hold() { screen += "hold" }
            override fun release() { screen += "release" }
        },
        play = { s, done -> played += s; done() },
        silence = { silenced++ },
        schedule = { ms, action -> CompletableFuture<Unit>().also { timers += Triple(ms, action, it) } as Future<*> },
        haBase = { "http://homeassistant.local:8123" },
        now = { t },
    )

    /** Calls the `notify` action as Home Assistant would, through the entity's own handler. */
    private fun notify(message: String, title: String = "", data: String = "") {
        val action = pane.entities().first { it.objectId == "notify" } as ApiAction
        val run = ApiAction::class.java.getDeclaredField("run").apply { isAccessible = true }.get(action)
        @Suppress("UNCHECKED_CAST")
        (run as (ActionCall) -> String?)(ActionCall(action, mapOf("message" to message, "title" to title, "data" to data), false))
    }

    private fun live(f: Triple<Long, () -> Unit, CompletableFuture<Unit>>) = !f.third.isCancelled

    @Test
    fun offItAnswersThatItIsOff() {
        val e = assertThrows(IllegalStateException::class.java) { notify("hi") }
        assertEquals("Notifications are off on this panel", e.message)
    }

    @Test
    fun onItShowsAndATapGoesToHomeAssistant() {
        pane.start()
        notify("Someone is at the door", "Doorbell", """{"tag":"doorbell","actions":[{"action":"open_gate","title":"Open"}]}""")
        val card = hub.state.cards.single()
        assertEquals("Doorbell", card.notification.title)
        assertEquals(listOf(Sound.CHIME), played)
        assertEquals("music goes quiet, then comes back", listOf(PanelEvent.SpeakerClaimed(true), PanelEvent.SpeakerClaimed(false)), events)
        hub.tapAction(card.id, "open_gate")
        assertEquals("esphome.notification_action", fired.single().event)
        assertEquals(mapOf("action" to "open_gate", "tag" to "doorbell"), fired.single().data)
        assertTrue(hub.state.cards.isEmpty())
    }

    @Test
    fun highHoldsTheScreenUntilItIsGone() {
        pane.start()
        notify("Garage door open", data = """{"priority":"high"}""")
        assertEquals(listOf("hold"), screen)
        assertTrue("at most 10 minutes", timers.any { it.first == NotificationsPane.HOLD_MAX_MS && live(it) })
        hub.dismiss(hub.state.cards.single().id)
        assertEquals(listOf("hold", "release"), screen)
    }

    @Test
    fun aHoldNobodyAnswersEndsAfterTenMinutes() {
        pane.start()
        notify("Garage door open", data = """{"priority":"high"}""")
        timers.first { it.first == NotificationsPane.HOLD_MAX_MS }.second()
        assertEquals(listOf("hold", "release"), screen)
        assertEquals("the card is still there", 1, hub.state.cards.size)
    }

    @Test
    fun theCriticalAlertRepeatsUntilAnswered() {
        pane.start()
        notify("Water leak", data = """{"priority":"critical"}""")
        assertEquals(listOf(Sound.ALERT), played)
        timers.last { it.first == NotificationsPane.ALERT_REPEAT_MS }.second()
        assertEquals(listOf(Sound.ALERT, Sound.ALERT), played)
        hub.dismiss(hub.state.cards.single().id)
        assertEquals(1, silenced)
        assertFalse("the next repeat is cancelled", live(timers.last { it.first == NotificationsPane.ALERT_REPEAT_MS }))
    }

    @Test
    fun theCriticalAlertStopsAfterTwoMinutes() {
        pane.start()
        notify("Water leak", data = """{"priority":"critical"}""")
        t = NotificationsPane.ALERT_MAX_MS
        timers.last { it.first == NotificationsPane.ALERT_REPEAT_MS }.second()
        assertEquals(listOf(Sound.ALERT), played)
    }

    @Test
    fun doNotDisturbFromHomeAssistant() {
        pane.start()
        val dnd = pane.entities().first { it.objectId == "do_not_disturb" } as SwitchEntity
        dnd.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertTrue(prefs.doNotDisturb)
        assertTrue(hub.state.doNotDisturb)
        assertEquals(1, published)
        notify("Washer done")
        assertTrue("held", hub.state.cards.isEmpty())
        assertTrue(played.isEmpty())
        assertEquals(1, hub.state.list.size)
        dnd.command(ProtoMessage(ProtoWriter().bool(2, false).toByteArray()))
    }

    @Test
    fun stoppedItShowsNothingAndLetsGo() {
        pane.start()
        notify("Garage door open", data = """{"priority":"high","timeout":60}""")
        pane.stop()
        assertTrue(hub.state.cards.isEmpty())
        assertFalse(hub.running)
        assertEquals(listOf("hold", "release"), screen)
        assertFalse("no timer left running", timers.any(::live))
        assertThrows(IllegalStateException::class.java) { notify("hi") }
    }

    @Test
    fun theTestNotification() {
        assertFalse("off: nothing to send to", hub.sendTest())
        pane.start()
        assertTrue(hub.sendTest())
        assertEquals(NotificationsHub.TEST_TITLE, hub.state.cards.single().notification.title)
    }
}
