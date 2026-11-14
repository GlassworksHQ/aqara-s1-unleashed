/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.HomeAssistantEvent
import java.util.concurrent.Future

/** What the pane asks of the screen. The app runs these on the main thread ([dev.glassworks.screen.ScreenPolicy.hold]). */
interface NotificationScreen {
    /** Turn the screen on, as for any other reason; night dimming and the idle timeout apply. */
    fun wake()

    /** Wake the screen and keep it on at the day's brightness, past night dimming. */
    fun hold()
    fun release()
}

/**
 * The notifications pane (#118): Home Assistant calls `esphome.<panel>_notify`, the panel shows it over
 * the dashboard (the overlay reads [hub]), plays its sound, and reports a tapped button as the bus
 * event `esphome.notification_action`.
 *
 * - Its entities stay while it is off (#67), so the action answers "Notifications are off on this
 *   panel" instead of disappearing from Home Assistant.
 * - A high or critical notification holds the screen ([NotificationScreen.hold]) until no urgent one
 *   is up, and at most [HOLD_MAX_MS]: a notification nobody answers doesn't keep a bedroom lit all night.
 * - The critical alert repeats every [ALERT_REPEAT_MS] while a critical one is up. No new repeat
 *   starts after [ALERT_MAX_MS], so the last one can end up to one sound (1.45 s) past it.
 * - Sounds claim the speaker ([PanelEvent.SpeakerClaimed]), so music goes quiet meanwhile. The claim
 *   and its release are emitted under the pane's lock, in the order they happen. One claim
 *   covers overlapping sounds, and it is let go when the newest sound ends, when the alert is
 *   silenced or the pane stops, or at the latest [RELEASE_SLACK_MS] after the sound should have
 *   ended: a sound cut short (`TtsPlayer.cancel`) never calls back. [stop] lets the speaker go and
 *   silences the alert, but a chime or doorbell already playing ends by itself (at most 3 s).
 *
 * [play] plays one sound and calls back when it ended; [silence] cuts it.
 */
class NotificationsPane(
    private val ctx: PaneContext,
    private val prefs: NotificationsPrefs,
    private val hub: NotificationsHub,
    private val screen: NotificationScreen,
    private val play: (Sound, onDone: () -> Unit) -> Unit,
    private val silence: () -> Unit,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Future<*>,
    private val haBase: () -> String?,
    private val now: () -> Long,
) : Pane {
    override val id = "notifications" // ID; tools/size reads this literal

    private val lock = Any()
    private var center: NotificationCenter? = null
    private var holding = false
    private var holdTimer: Future<*>? = null
    private var alertTimer: Future<*>? = null
    private var alertUntil = 0L
    private var criticalUp = false
    /** Bumped by [stopAlert], so a repeat already waiting for the lock knows it is stale. */
    private var alertGen = 0
    /** The speaker claim ([sound]): held now, its generation, and the fallback that lets it go. */
    private var claimed = false
    private var claimGen = 0
    private var claimTimer: Future<*>? = null

    private val entities = NotificationEntities(
        onNotify = { m, t, d ->
            val c = synchronized(lock) { center } ?: throw IllegalStateException(NotificationCenter.OFF)
            c.handle(m, t, d)
        },
        getDoNotDisturb = { prefs.doNotDisturb },
        setDoNotDisturb = { on ->
            prefs.doNotDisturb = on
            // Off, the pane shows nothing, not even Do not disturb's pill; start() publishes it again.
            synchronized(lock) { if (center != null) hub.update { it.copy(doNotDisturb = on) } }
            ctx.publishStates()
        },
    )

    override fun entities(): List<ApiEntity> = entities.all

    private val outputs = object : NotificationOutputs {
        override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) {
            val urgent = (cards + listOfNotNull(page)).any { it.delivery.overNight }
            val critical = cards.any { it.notification.priority == Priority.CRITICAL } ||
                page?.notification?.priority == Priority.CRITICAL
            val silenced = synchronized(lock) {
                // Under the pane's lock, as Do not disturb's publish: neither overwrites the other with an older state.
                hub.publish(NotificationsHub.State(cards, page, list, prefs.doNotDisturb))
                criticalUp = critical
                if (!urgent) releaseScreen()
                !critical && stopAlert()
            }
            // The alert was cut short: its end never comes, so let the music back now.
            if (silenced) releaseSpeaker()
        }

        override fun wake(overNight: Boolean) {
            // Past night dimming only for one that asks for it; otherwise a plain wake.
            if (overNight) synchronized(lock) { holdScreen() } else screen.wake()
        }

        override fun play(sound: Sound, critical: Boolean) {
            // Only a critical notification's sound repeats. "alert" on a normal one plays once.
            if (critical) synchronized(lock) { startAlert(sound) } else sound(sound)
        }
    }

    private fun sound(s: Sound) {
        // Claim, emit and arm the fallback in one step under the lock, and release the same way: the
        // emits then reach the other panes in the order the claim changed, so a release can't land
        // between a claim and its emit and leave the music ducked with nothing to let it go (#228, N1).
        val gen = synchronized(lock) {
            claimTimer?.cancel(false)
            if (!claimed) {
                claimed = true
                ctx.emit(PanelEvent.SpeakerClaimed(true))
            }
            val g = ++claimGen
            val ms = (NotificationSounds.seconds(s) * 1000).toLong() + RELEASE_SLACK_MS
            claimTimer = schedule(ms) { releaseSpeaker(g) }
            g
        }
        // Released already before it starts (dismissed meanwhile): it plays unducked, and its end is ignored.
        play(s) { releaseSpeaker(gen) }
    }

    /**
     * Lets go of the speaker: for [gen], only if no newer sound took the claim since (an older sound's
     * end, delivered while a newer one plays, must not bring the music back under it); null: now.
     */
    private fun releaseSpeaker(gen: Int? = null) = synchronized(lock) {
        if (!claimed || (gen != null && gen != claimGen)) return@synchronized
        claimed = false
        claimGen++
        claimTimer?.cancel(false)
        claimTimer = null
        ctx.emit(PanelEvent.SpeakerClaimed(false))
    }

    override fun start() {
        val c = NotificationCenter(
            NotificationStore(now), outputs,
            report = { r -> ctx.fireEvent(HomeAssistantEvent(ActionReport.EVENT, r.data)) },
            doNotDisturb = { prefs.doNotDisturb }, haBase = haBase, now = now, schedule = schedule,
        )
        synchronized(lock) { center = c }
        hub.attach(c)
        hub.publish(NotificationsHub.State(doNotDisturb = prefs.doNotDisturb))
    }

    override fun stop() {
        val c = synchronized(lock) { center.also { center = null } } ?: return
        // Close first, without the pane's lock: it waits for a notify call in flight (which may take the
        // pane's lock for its sound or screen), and nothing starts after it. Then undo what it started.
        c.close()
        synchronized(lock) {
            criticalUp = false
            stopAlert()
            releaseScreen()
        }
        releaseSpeaker()
        hub.detach(c)
    }

    override fun onEvent(event: PanelEvent) {
        if (event is PanelEvent.SettingChanged && event.key == NotificationsPrefs.KEY_DND) {
            synchronized(lock) { if (center != null) hub.update { it.copy(doNotDisturb = prefs.doNotDisturb) } }
        }
    }

    // --- under lock

    private fun holdScreen() {
        holdTimer?.cancel(false)
        holdTimer = schedule(HOLD_MAX_MS) { synchronized(lock) { releaseScreen() } }
        if (!holding) { holding = true; screen.hold() }
    }

    private fun releaseScreen() {
        holdTimer?.cancel(false)
        holdTimer = null
        if (holding) { holding = false; screen.release() }
    }

    private fun startAlert(s: Sound) {
        criticalUp = true
        if (alertTimer != null) return
        alertUntil = now() + ALERT_MAX_MS
        ring(s, alertGen)
    }

    private fun ring(s: Sound, gen: Int) {
        sound(s)
        alertTimer = schedule(ALERT_REPEAT_MS) {
            synchronized(lock) {
                // A repeat that started waiting before stopAlert() or stop() finds a newer generation and ends.
                if (gen != alertGen) return@synchronized
                alertTimer = null
                if (criticalUp && now() < alertUntil) ring(s, gen) else EspHomeLog.i("notifications: alert stopped")
            }
        }
    }

    /** Stops the alert's repeats; true when it was ringing and got cut ([silence]). */
    private fun stopAlert(): Boolean {
        alertGen++
        alertTimer?.cancel(false)
        val ringing = alertTimer != null
        if (ringing) silence()
        alertTimer = null
        return ringing
    }

    companion object {
        const val ID = "notifications"
        const val HOLD_MAX_MS = 10 * 60_000L
        const val ALERT_REPEAT_MS = 4_000L
        const val ALERT_MAX_MS = 2 * 60_000L
        /** How long after a sound should have ended the claim is let go anyway. */
        const val RELEASE_SLACK_MS = 1_500L
    }
}
