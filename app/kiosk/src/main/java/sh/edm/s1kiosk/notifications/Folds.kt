package sh.edm.s1kiosk.notifications

import dev.glassworks.notifications.NotificationStore

/**
 * Which cards are in view (#118): a low or normal card folds into the list after
 * `Delivery.foldAfterS` seconds *on screen*. Its time counts only while it is shown (one of the
 * [IN_VIEW] newest cards, not under the critical alert) and the screen is on, and adds up across
 * the gaps: a card that came in the dark, or was half-read before the screen went off, is there when
 * the panel wakes. A card the person opened from the list stays until they close it.
 *
 * Pure: the overlay calls [update] on every change, when the screen turns on or off, and when
 * [View.nextFoldAt] falls. Main thread.
 */
class Folds {
    private val shownMs = HashMap<Long, Long>()
    private val since = HashMap<Long, Long>()
    private val folded = HashSet<Long>()
    private val pinned = HashSet<Long>()

    class View(val cards: List<NotificationStore.Entry>, val nextFoldAt: Long?)

    /**
     * [cards] newest first. [covered]: the critical alert covers the cards, so none of them is seen.
     * Returns the cards not folded, and when the next one folds if nothing changes.
     */
    fun update(cards: List<NotificationStore.Entry>, screenOn: Boolean, now: Long, covered: Boolean = false): View {
        val ids = cards.map { it.id }.toSet()
        for (m in listOf(shownMs.keys, since.keys, folded, pinned)) m.retainAll(ids)
        // Time up to now counts for the cards that were being seen since the last update.
        for ((id, from) in since) shownMs[id] = (shownMs[id] ?: 0L) + (now - from)
        since.clear()

        // First fold the ones whose time is up, then see which are shown now: a card behind them moves
        // into the stack and starts its own clock at once.
        for (c in cards) {
            val after = c.delivery.foldAfterS ?: continue
            if (c.id !in pinned && (shownMs[c.id] ?: 0L) >= after * 1000L) folded += c.id
        }
        val unfolded = cards.filter { it.id !in folded }
        val seen = if (covered || !screenOn) emptyList() else unfolded.take(IN_VIEW)
        var next: Long? = null
        for (c in seen) {
            val after = c.delivery.foldAfterS ?: continue
            if (c.id in pinned) continue
            since[c.id] = now
            val at = now + after * 1000L - (shownMs[c.id] ?: 0L)
            next = minOf(next ?: at, at)
        }
        return View(unfolded, next)
    }

    /** Opened from the list: back in view, and it stays. */
    fun pin(id: Long) {
        folded -= id
        pinned += id
    }

    fun isFolded(id: Long) = id in folded

    companion object {
        /** The stack shows the newest three. */
        const val IN_VIEW = 3
    }
}
