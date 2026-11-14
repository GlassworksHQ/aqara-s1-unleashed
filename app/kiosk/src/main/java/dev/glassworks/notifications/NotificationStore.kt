/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

/**
 * What the panel does with a notification when it arrives (#118).
 *
 * | Priority | Shows | Sound | Wakes the screen | Over night dimming | Card folds into the list |
 * |---|---|---|---|---|---|
 * | low | when the screen is next on | no | no | no | after 10 s on screen |
 * | normal | when the screen is next on | yes | no | no | after 15 s on screen |
 * | high | at once | yes | yes | yes | no: stays until answered |
 * | critical | at once | yes | yes | yes | no: stays until answered |
 *
 * Do not disturb holds everything but critical: it goes to the list, silent, and the screen stays
 * as it is.
 */
data class Delivery(
    val held: Boolean,
    val sound: Boolean,
    val wake: Boolean,
    val overNight: Boolean,
    /**
     * Seconds the card stays over the dashboard while the screen is on, before it folds into the list.
     * Null: it stays until it is answered, dismissed, cleared or times out. A held one (Do not
     * disturb) has none: it has no card until the person opens it from the list, and then it stays
     * until they close it.
     */
    val foldAfterS: Int? = null,
) {
    companion object {
        const val FOLD_LOW_S = 10
        const val FOLD_NORMAL_S = 15

        fun of(priority: Priority, doNotDisturb: Boolean): Delivery = when {
            priority == Priority.CRITICAL -> Delivery(held = false, sound = true, wake = true, overNight = true)
            doNotDisturb -> Delivery(held = true, sound = false, wake = false, overNight = false)
            priority == Priority.HIGH -> Delivery(held = false, sound = true, wake = true, overNight = true)
            priority == Priority.NORMAL -> Delivery(held = false, sound = true, wake = false, overNight = false, foldAfterS = FOLD_NORMAL_S)
            else -> Delivery(held = false, sound = false, wake = false, overNight = false, foldAfterS = FOLD_LOW_S)
        }
    }
}

/**
 * The notifications on the panel, newest first. In memory only: a restart starts empty, because Home
 * Assistant holds the facts and can send again (#118's open question).
 *
 * - A notification with a tag replaces the one with the same tag.
 * - [clear] closes by tag, [dismiss] by id, [expire] the ones whose timeout has passed.
 * - A *held* entry is in the list but has no card: Do not disturb held it, or a newer full-screen
 *   page took the screen from it. Tapping it in the list shows it.
 * - At most [max] entries; past that the oldest goes, critical ones last.
 *
 * Thread-safe. [now] is in milliseconds (`SystemClock.elapsedRealtime` on the panel).
 */
class NotificationStore(private val now: () -> Long, val max: Int = 20) {
    /**
     * Tests only: runs after each of [cards], [page] and [list] returns, never inside [snapshot]. A test
     * changes the store from it, to prove a render reads one snapshot and not three separate lists.
     */
    internal var afterRead: ((String) -> Unit)? = null


    data class Entry(
        val id: Long,
        val notification: Notification,
        val postedAt: Long,
        val delivery: Delivery,
        val held: Boolean = delivery.held,
    ) {
        val expiresAt: Long? get() = notification.timeoutS?.let { postedAt + it * 1000L }
    }

    /** What [post] did: the new entry, the one it replaced (same tag), and any dropped to stay under [max]. */
    data class Posted(val entry: Entry, val replaced: Entry?, val dropped: List<Entry>)

    private val entries = ArrayList<Entry>()
    private var nextId = 1L

    @Synchronized
    fun post(n: Notification, doNotDisturb: Boolean): Posted {
        val replaced = n.tag?.let { t -> entries.firstOrNull { it.notification.tag == t } }
        if (replaced != null) entries.remove(replaced)
        // Overdue entries are gone already: they must not take a place from the new one.
        val t = now()
        entries.removeAll { e -> e.expiresAt?.let { it <= t } == true }
        val e = Entry(nextId++, n, t, Delivery.of(n.priority, doNotDisturb))
        entries.add(0, e)
        val dropped = ArrayList<Entry>()
        while (entries.size > max) {
            val victim = entries.lastOrNull { it.notification.priority != Priority.CRITICAL } ?: entries.last()
            entries.remove(victim)
            dropped += victim
        }
        // A full-screen page takes the screen, if it stayed: an older page stays in the list, without the screen.
        if (n.url != null && !e.held && e !in dropped) {
            for (i in entries.indices) {
                val o = entries[i]
                if (o !== e && o.notification.url != null && !o.held) entries[i] = o.copy(held = true)
            }
        }
        return Posted(e, replaced, dropped)
    }

    @Synchronized
    fun clear(tag: String): Entry? = entries.firstOrNull { it.notification.tag == tag }?.also { entries.remove(it) }

    @Synchronized
    fun dismiss(id: Long): Entry? = entries.firstOrNull { it.id == id }?.also { entries.remove(it) }

    /** Shows a held entry (tapped in the list). A page takes the screen from any other page. */
    @Synchronized
    fun show(id: Long): Entry? {
        val i = entries.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return null
        val e = entries[i].copy(held = false)
        if (e.notification.url != null) {
            for (j in entries.indices) {
                val o = entries[j]
                if (j != i && o.notification.url != null && !o.held) entries[j] = o.copy(held = true)
            }
        }
        entries[i] = e
        return e
    }

    /** What [act] did: the action tapped, the notification's tag, and whether it closed. */
    data class Acted(val action: NotificationAction, val tag: String?, val closed: Boolean)

    /**
     * A tap on [actionId] of entry [id], in one step: the entry can't be replaced or cleared between
     * finding it and reading its tag. It closes unless [Notification.sticky]. Null when there is no
     * such entry or action.
     */
    @Synchronized
    fun act(id: Long, actionId: String): Acted? {
        val e = entries.firstOrNull { it.id == id } ?: return null
        val a = e.notification.actions.firstOrNull { it.id == actionId } ?: return null
        val closes = !e.notification.sticky
        if (closes) entries.remove(e)
        return Acted(a, e.notification.tag, closes)
    }

    /** Removes and returns every entry whose timeout has passed. */
    @Synchronized
    fun expire(): List<Entry> {
        val t = now()
        val gone = entries.filter { e -> e.expiresAt?.let { it <= t } == true }
        entries.removeAll(gone.toSet())
        return gone
    }

    /** When the next timeout falls, or null for none. */
    @Synchronized
    fun nextExpiry(): Long? = entries.mapNotNull { it.expiresAt }.minOrNull()

    /** What the panel shows, read in one step: the cards, the open page and the whole list. */
    data class Snapshot(val cards: List<Entry>, val page: Entry?, val list: List<Entry>)

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(cardsNow(), pageNow(), entries.toList())

    /** Every entry, newest first. */
    fun list(): List<Entry> = synchronized(this) { entries.toList() }.also { afterRead?.invoke("list") }

    /** The full-screen page that is open, if any: the newest page that isn't held. */
    fun page(): Entry? = synchronized(this) { pageNow() }.also { afterRead?.invoke("page") }

    /** The cards over the dashboard: entries that aren't held and aren't a page, newest first. */
    fun cards(): List<Entry> = synchronized(this) { cardsNow() }.also { afterRead?.invoke("cards") }

    private fun pageNow() = entries.firstOrNull { it.notification.url != null && !it.held }
    private fun cardsNow() = entries.filter { !it.held && it.notification.url == null }
}
