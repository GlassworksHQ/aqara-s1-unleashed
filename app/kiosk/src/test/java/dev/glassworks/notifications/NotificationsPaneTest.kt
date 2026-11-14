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
    private val events = java.util.Collections.synchronizedList(mutableListOf<PanelEvent>())
    /** Runs before an event is recorded, on the emitting thread: a test forces an interleaving with it. */
    private var onEmit: ((PanelEvent) -> Unit)? = null
    private var published = 0
    private val screen = mutableListOf<String>()
    private val played = mutableListOf<Sound>()
    private var silenced = 0
    private var playFails = false
    /** Hold the play callbacks instead of calling them, as a sound still playing (or cut, never calling back). */
    private var holdDone = false
    private val pending = mutableListOf<() -> Unit>()
    private var t = 0L
    private val timers = mutableListOf<Triple<Long, () -> Unit, CompletableFuture<Unit>>>()

    private val pane = NotificationsPane(
        PaneContext(
            app, { error("no prefs") },
            DeviceIdentity("test", "Test", "02:00:00:00:00:01", "x", "x", "x", "x", 0),
            publishStates = { published++ }, emit = { e -> onEmit?.invoke(e); events += e }, entitiesChanged = {}, sendEvent = { _, _ -> },
            fireEvent = { fired += it },
        ),
        prefs, hub,
        screen = object : NotificationScreen {
            override fun wake() { screen += "wake" }
            override fun hold() { screen += "hold" }
            override fun release() { screen += "release" }
        },
        play = { s, done -> if (playFails) error("speaker failed"); played += s; if (holdDone) pending += done else done() },
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

    /** CodeRabbit on #228: "alert" on a normal one plays once and isn't cut off. */
    @Test
    fun theAlertSoundOnANormalOnePlaysOnce() {
        pane.start()
        notify("Oven", data = """{"sound":"alert"}""")
        assertEquals(listOf(Sound.ALERT), played)
        assertEquals("not silenced by the next render", 0, silenced)
        assertFalse("no repeat", timers.any { it.first == NotificationsPane.ALERT_REPEAT_MS })
    }

    /** CodeRabbit on #228: a repeat already due when the pane stops doesn't ring again. */
    @Test
    fun aRepeatDueAtStopDoesNotRing() {
        pane.start()
        notify("Water leak", data = """{"priority":"critical"}""")
        val repeat = timers.last { it.first == NotificationsPane.ALERT_REPEAT_MS }.second
        pane.stop()
        repeat()
        assertEquals(listOf(Sound.ALERT), played)
    }

    /** CodeRabbit on #228: a call that got the center just before stop() doesn't put cards back. */
    @Test
    fun aLateCallAfterStopShowsNothing() {
        pane.start()
        val center = NotificationsPane::class.java.getDeclaredField("center").apply { isAccessible = true }.get(pane) as NotificationCenter
        pane.stop()
        val e = assertThrows(IllegalStateException::class.java) { center.handle("late", "Late", """{"priority":"critical"}""") }
        assertEquals("Notifications are off on this panel", e.message)
        assertTrue(hub.state.cards.isEmpty())
        assertTrue("no sound started after stop", played.isEmpty())
        assertEquals("no screen hold after stop", emptyList<String>(), screen)
    }

    /** CodeRabbit on #228: a test sent while the pane stops answers false instead of throwing on the UI thread. */
    @Test
    fun aTestWhileThePaneStopsIsFalse() {
        pane.start()
        val center = NotificationsPane::class.java.getDeclaredField("center").apply { isAccessible = true }.get(pane) as NotificationCenter
        center.close() // the switch's thread got there first
        assertFalse(hub.sendTest())
        assertTrue(hub.state.cards.isEmpty())
    }

    /** CodeRabbit on #228: only the center being closed means "off"; a failing output still gets through. */
    @Test
    fun aFailingOutputDuringATestIsNotOff() {
        var revealed = false
        hub.onReveal = { revealed = true }
        pane.start()
        playFails = true
        val e = assertThrows(IllegalStateException::class.java) { hub.sendTest() }
        assertEquals("speaker failed", e.message)
        assertFalse("nothing revealed for a test that failed", revealed)
    }

    private val claims get() = events.filterIsInstance<PanelEvent.SpeakerClaimed>().map { it.claimed }

    /** Verifier L1: dismissing a ringing critical alert lets the music back, once; the cut sound's late end changes nothing. */
    @Test
    fun dismissingTheAlertReleasesTheSpeaker() {
        holdDone = true
        pane.start()
        notify("Water leak", data = """{"priority":"critical"}""")
        assertEquals(listOf(true), claims)
        hub.dismiss(hub.state.cards.single().id)
        assertEquals(1, silenced)
        assertEquals(listOf(true, false), claims)
        pending.forEach { it() }
        assertEquals("a late end of the cut sound doesn't release twice", listOf(true, false), claims)
    }

    /** Verifier L1: stopping the pane mid-sound lets the music back. */
    @Test
    fun stoppingMidSoundReleasesTheSpeaker() {
        holdDone = true
        pane.start()
        notify("Washer done")
        pane.stop()
        assertEquals(listOf(true, false), claims)
    }

    /** Verifier L1: with two sounds overlapping, the older one's end doesn't bring the music back under the newer one. */
    @Test
    fun anOlderSoundsEndDoesNotReleaseANewerOne() {
        holdDone = true
        pane.start()
        notify("Washer done", data = """{"tag":"washer"}""")
        notify("Dryer done", data = """{"tag":"dryer"}""")
        assertEquals("one claim for both", listOf(true), claims)
        pending[0]()
        assertEquals(listOf(true), claims)
        pending[1]()
        assertEquals(listOf(true, false), claims)
    }

    /** Verifier L1: a sound cut short somewhere else (the shared player's cancel) never calls back; the fallback lets go. */
    @Test
    fun aSoundThatNeverEndsIsReleasedAfterItsLength() {
        holdDone = true
        pane.start()
        notify("Washer done")
        val fallback = timers.single { it.first == 1_000L + NotificationsPane.RELEASE_SLACK_MS }
        fallback.second()
        assertEquals(listOf(true, false), claims)
    }

    /** Verifier: with Do not disturb on, a pane turned off shows no pill; and DND changed while off doesn't bring it back. */
    @Test
    fun offShowsNoDoNotDisturbPill() {
        pane.start()
        val dnd = pane.entities().first { it.objectId == "do_not_disturb" } as SwitchEntity
        dnd.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertTrue(hub.state.doNotDisturb)
        pane.stop()
        assertFalse(hub.state.doNotDisturb)
        dnd.command(ProtoMessage(ProtoWriter().bool(2, false).toByteArray()))
        dnd.command(ProtoMessage(ProtoWriter().bool(2, true).toByteArray()))
        assertFalse("still nothing while off", hub.state.doNotDisturb)
        pane.start()
        assertTrue("back on: the pill comes back", hub.state.doNotDisturb)
    }

    /**
     * Verifier N1: a release on another thread that lands while a sound claims the speaker (render's
     * silenced path, via a dismiss or a timeout) must come after the claim's emit, never before it: the
     * music must not end up ducked with nothing left to let it go.
     */
    @Test
    fun aReleaseDuringTheClaimComesAfterIt() {
        holdDone = true
        pane.start()
        val release = NotificationsPane::class.java.getDeclaredMethod("releaseSpeaker", Integer::class.java).apply { isAccessible = true }
        var other: Thread? = null
        onEmit = { e ->
            if (e == PanelEvent.SpeakerClaimed(true) && other == null) {
                other = Thread { release.invoke(pane, null) }.apply { start(); join(300) }
            }
        }
        notify("Washer done")
        other!!.join(5_000)
        assertEquals("claimed, then let go: the music comes back", listOf(true, false), claims)
        pending.forEach { it() }
        assertEquals("the sound's own end changes nothing after", listOf(true, false), claims)
    }

    /** Verifier N3: a wake that doesn't ask to pass night dimming is a plain wake, not a hold. */
    @Test
    fun aPlainWakeIsNotAHold() {
        pane.start()
        val outputs = NotificationsPane::class.java.getDeclaredField("outputs").apply { isAccessible = true }.get(pane) as NotificationOutputs
        outputs.wake(overNight = false)
        assertEquals(listOf("wake"), screen)
        outputs.wake(overNight = true)
        assertEquals(listOf("wake", "hold"), screen)
    }
}
