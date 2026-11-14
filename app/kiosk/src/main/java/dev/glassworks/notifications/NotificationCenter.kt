/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import java.util.concurrent.Future

/** What the notifications pane asks of the panel. The overlay and the chime player implement it. */
interface NotificationOutputs {
    /**
     * The notifications changed. [cards] go over the dashboard, newest first; [page] is the dashboard
     * view open full screen, if any; [list] is every notification, held ones included. Any thread:
     * the implementation moves to the main thread.
     */
    fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>)

    /** Turn the screen on. [overNight]: at full brightness, past night dimming. */
    fun wake(overNight: Boolean)

    /** Play [sound]. The player claims the speaker (`PanelEvent.SpeakerClaimed`) while it plays. */
    fun play(sound: Sound)
}

/**
 * A tapped button, for Home Assistant: the bus event `esphome.notification_action` with the
 * button's `action` and the notification's `tag`, the shape of the Companion app's
 * `mobile_app_notification_action`. Home Assistant adds the panel's `device_id`.
 */
data class ActionReport(val action: String, val tag: String?) {
    val data: Map<String, String> get() = buildMap {
        put("action", action)
        if (tag != null) put("tag", tag)
    }

    companion object {
        const val EVENT = "esphome.notification_action"
    }
}

/**
 * The notifications pane's engine (#118): a `notify` call goes in, the rules in [Delivery] apply,
 * and [out] shows it. Taps come back from the overlay as [tapAction], [dismiss] and [show]. Timeouts
 * run on [schedule].
 *
 * Thread-safe. [doNotDisturb] and [haBase] are read on each call.
 */
class NotificationCenter(
    private val store: NotificationStore,
    private val out: NotificationOutputs,
    private val report: (ActionReport) -> Unit,
    private val doNotDisturb: () -> Boolean,
    private val haBase: () -> String?,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, action: () -> Unit) -> Future<*>,
) {
    private var timer: Future<*>? = null

    /** One call of the `notify` action. Throws [IllegalArgumentException] for Home Assistant to show. */
    fun handle(message: String, title: String, data: String) {
        when (val c = NotifyRequest.parse(message, title, data, haBase())) {
            is NotifyCommand.Clear -> if (store.clear(c.tag) != null) changed()
            is NotifyCommand.Post -> {
                val p = store.post(c.notification, doNotDisturb())
                val d = p.entry.delivery
                if (d.wake) out.wake(d.overNight)
                if (d.sound && c.notification.sound != Sound.NONE) out.play(c.notification.sound)
                changed()
            }
        }
    }

    /** A button on a card or the page. Reports it to Home Assistant, and closes it unless it is sticky. */
    fun tapAction(id: Long, actionId: String) {
        val tag = store.list().firstOrNull { it.id == id }?.notification?.tag
        val (action, closed) = store.act(id, actionId) ?: return
        report(ActionReport(action.id, tag))
        if (closed) changed()
    }

    /** Closed by hand: the card's close, a swipe, or Back on a page. */
    fun dismiss(id: Long) {
        if (store.dismiss(id) != null) changed()
    }

    /** A held notification tapped in the list. */
    fun show(id: Long) {
        if (store.show(id) != null) changed()
    }

    private fun changed() {
        store.expire()
        out.render(store.cards(), store.page(), store.list())
        reschedule()
    }

    @Synchronized
    private fun reschedule() {
        timer?.cancel(false)
        timer = store.nextExpiry()?.let { at ->
            schedule((at - now()).coerceAtLeast(0)) { if (store.expire().isNotEmpty()) changed() else reschedule() }
        }
    }
}
