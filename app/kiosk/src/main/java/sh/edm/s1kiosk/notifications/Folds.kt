package sh.edm.s1kiosk.notifications

import dev.glassworks.notifications.NotificationStore

/**
 * Which cards are in view (#118): a low or normal card folds into the list [Delivery.foldAfterS] seconds
 * after it was first on screen. Time counts only while the screen is on, so one that came in the dark
 * is there when the panel wakes. A card the person opened from the list stays until they close it.
 * Pure: the overlay calls it on every change and when the next fold is due. Main thread.
 *
 * [Delivery.foldAfterS]: dev.glassworks.notifications.Delivery.foldAfterS
 */
class Folds {
    private val firstShown = HashMap<Long, Long>()
    private val folded = HashSet<Long>()
    private val pinned = HashSet<Long>()

    class View(val cards: List<NotificationStore.Entry>, val nextFoldAt: Long?)

    fun update(cards: List<NotificationStore.Entry>, screenOn: Boolean, now: Long): View {
        val ids = cards.map { it.id }.toSet()
        firstShown.keys.retainAll(ids); folded.retainAll(ids); pinned.retainAll(ids)
        var next: Long? = null
        for (c in cards) {
            val after = c.delivery.foldAfterS ?: continue
            if (c.id in folded || c.id in pinned) continue
            if (screenOn) firstShown.putIfAbsent(c.id, now)
            val due = (firstShown[c.id] ?: continue) + after * 1000L
            if (now >= due) folded += c.id else next = minOf(next ?: due, due)
        }
        return View(cards.filter { it.id !in folded }, next)
    }

    /** Opened from the list: back in view, and it stays. */
    fun pin(id: Long) {
        folded -= id
        pinned += id
    }

    fun isFolded(id: Long) = id in folded
}
