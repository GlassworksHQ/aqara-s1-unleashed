/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import java.util.concurrent.Future

/**
 * What the notifications pane asks of the panel. The overlay and the chime player implement it.
 *
 * Who does what: [NotificationCenter] and [NotificationStore] decide *what* is on the panel (which
 * entries exist, which are held, which page is open, when they time out) and whether to wake the
 * screen and play a sound. The overlay decides *how long a card stays in view*, from each entry's
 * [Delivery]: it shows cards only while the screen is on (so low and normal ones wait for the screen
 * to wake), folds a card into the list [Delivery.foldAfterS] seconds after it was first on screen, and
 * keeps one with no fold time until it is answered. Folding only changes the view; the entry stays.
 */
interface NotificationOutputs {
    /**
     * The notifications changed. [cards] go over the dashboard, newest first; [page] is the dashboard
     * view open full screen, if any; [list] is every notification, held ones included.
     *
     * Called in the order the changes happened, one call at a time, on whichever thread changed them.
     * It must not block or call back into the center: hand the lists to the main thread and return.
     */
    fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>)

    /** Turn the screen on. [overNight]: at full brightness, past night dimming. */
    fun wake(overNight: Boolean)

    /**
     * Play [sound]. The player claims the speaker (`PanelEvent.SpeakerClaimed`) while it plays.
     * [critical]: it belongs to a critical notification, so it repeats while one is up; otherwise once.
     */
    fun play(sound: Sound, critical: Boolean)
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
 * run on [schedule]. [close] when the pane stops.
 *
 * Thread-safe. [doNotDisturb] and [haBase] are read on each call. If [report] or an output throws,
 * the store has still changed, so the panel is rendered anyway and the exception goes to the caller.
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
    private val lock = Any()
    private var timer: Future<*>? = null
    private var closed = false

    companion object {
        /** What a call gets while the pane is off or stopping. */
        const val OFF = "Notifications are off on this panel"
    }

    /**
     * One call of the `notify` action. Throws [IllegalArgumentException] for Home Assistant to show, and
     * [IllegalStateException] once [close]d. Runs under the center's lock, so [close] waits for a call
     * in flight, and no screen or sound effect starts after it.
     */
    fun handle(message: String, title: String, data: String): Unit = synchronized(lock) {
        check(!closed) { OFF }
        when (val c = NotifyRequest.parse(message, title, data, haBase())) {
            is NotifyCommand.Clear -> if (store.clear(c.tag) != null) changed()
            is NotifyCommand.Post -> {
                val p = store.post(c.notification, doNotDisturb())
                val d = p.entry.delivery
                // Dropped at once to stay under the store's limit: it isn't on the panel, so it makes no noise.
                val kept = p.entry !in p.dropped
                try {
                    if (kept && d.wake) out.wake(d.overNight)
                    if (kept && d.sound && c.notification.sound != Sound.NONE) {
                        out.play(c.notification.sound, c.notification.priority == Priority.CRITICAL)
                    }
                } finally {
                    changed()
                }
            }
        }
    }

    /** A button on a card or the page. Reports it to Home Assistant, and closes it unless it is sticky. */
    fun tapAction(id: Long, actionId: String) {
        val acted = store.act(id, actionId) ?: return
        try {
            report(ActionReport(acted.action.id, acted.tag))
        } finally {
            if (acted.closed) changed()
        }
    }

    /**
     * A button's `uri` was tapped: [url] (already checked by [HaUrls]) opens full screen, with the same
     * way back as a `url` notification. Silent; the person asked for it, so Do not disturb doesn't hold it.
     */
    fun openPage(title: String, url: String) {
        store.post(Notification(title, "", url = url, sound = Sound.NONE), doNotDisturb = false)
        changed()
    }

    /** Closed by hand: the card's close, a swipe, or Back on a page. */
    fun dismiss(id: Long) {
        if (store.dismiss(id) != null) changed()
    }

    /** A held notification tapped in the list. */
    fun show(id: Long) {
        if (store.show(id) != null) changed()
    }

    /** [close] was called: the pane stopped. */
    val isClosed: Boolean get() = synchronized(lock) { closed }

    /**
     * Stops the timeout timer. Nothing is scheduled after this, and a timeout already firing does
     * nothing; the store keeps its entries.
     */
    fun close(): Unit = synchronized(lock) {
        closed = true
        timer?.cancel(false)
        timer = null
    }

    /** Takes the snapshot and renders it under one lock, so an older snapshot never lands after a newer one. */
    private fun changed(): Unit = synchronized(lock) {
        // Closed: the pane stopped and cleared the screen. A call that got the center just before
        // must not put its cards back.
        if (closed) return
        store.expire()
        val s = store.snapshot()
        try {
            out.render(s.cards, s.page, s.list)
        } finally {
            reschedule()
        }
    }

    /** A timeout fell. Under the lock, so it can't run past [close], and does nothing after it. */
    private fun timedOut(): Unit = synchronized(lock) {
        if (closed) return
        if (store.expire().isNotEmpty()) changed() else reschedule()
    }

    private fun reschedule(): Unit = synchronized(lock) {
        timer?.cancel(false)
        timer = if (closed) null else store.nextExpiry()?.let { at ->
            schedule((at - now()).coerceAtLeast(0)) { timedOut() }
        }
    }
}
