/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the notifications pane and the panel's screens meet (#118), one per process, on the app's
 * runtime. The running pane [attach]es its [NotificationCenter] and [publish]es what to show; the
 * overlay and the settings page read [state] and send taps back. While the pane is off, taps do
 * nothing and the state is empty.
 *
 * Thread-safe. Listeners run on whichever thread published: the overlay moves to the main thread.
 */
class NotificationsHub {
    /** What the panel shows now: [NotificationStore.Snapshot] plus whether Do not disturb is on. */
    data class State(
        val cards: List<NotificationStore.Entry> = emptyList(),
        val page: NotificationStore.Entry? = null,
        val list: List<NotificationStore.Entry> = emptyList(),
        val doNotDisturb: Boolean = false,
    )

    @Volatile var state = State()
        private set
    @Volatile private var center: NotificationCenter? = null
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    /** The settings page asked for the list to open. */
    @Volatile var onOpenList: (() -> Unit)? = null

    /** The settings page sent a test: close what covers the dashboard, so the card shows. */
    @Volatile var onReveal: (() -> Unit)? = null

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }

    /** The pane started. */
    fun attach(c: NotificationCenter) { center = c }

    /** The pane stopped: nothing is shown any more, Do not disturb's pill included. */
    fun detach(c: NotificationCenter) {
        if (center !== c) return
        center = null
        publish(State())
    }

    val running: Boolean get() = center != null

    /** Sets [state] and tells the listeners, in one step: the last state set is the last one they hear. */
    @Synchronized
    fun publish(s: State) {
        state = s
        for (l in listeners) runCatching { l(s) }
    }

    /** [publish] what [change] makes of the current state, read and set in one step. */
    @Synchronized
    fun update(change: (State) -> State) = publish(change(state))

    fun tapAction(id: Long, actionId: String) { center?.tapAction(id, actionId) }
    fun dismiss(id: Long) { center?.dismiss(id) }
    fun show(id: Long) { center?.show(id) }
    fun openPage(title: String, url: String) { center?.openPage(title, url) }
    fun clearAll() { center?.let { c -> state.list.forEach { c.dismiss(it.id) } } }

    /** The settings page's "Send a test notification". False while the pane is off, or stopping meanwhile. */
    fun sendTest(): Boolean {
        val c = center ?: return false
        try {
            c.handle(TEST_MESSAGE, TEST_TITLE, TEST_DATA)
        } catch (e: IllegalStateException) {
            // The pane stopped between reading the center and the call: as if it were off. Any other
            // failure (a sound or screen output) is a real one, and goes to the caller.
            if (c.isClosed) return false
            throw e
        }
        onReveal?.invoke()
        return true
    }

    fun openList() { onOpenList?.invoke() }

    companion object {
        const val TEST_TITLE = "Test notification"
        const val TEST_MESSAGE = "This is how a notification from Home Assistant looks on this panel."
        /** Its button reports `test_ok`, so an automation can be tried with it too. */
        const val TEST_DATA = """{"tag":"glassworks_test","actions":[{"action":"test_ok","title":"OK"}]}"""
    }
}
