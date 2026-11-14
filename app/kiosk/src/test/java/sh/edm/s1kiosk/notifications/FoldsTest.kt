package sh.edm.s1kiosk.notifications

import dev.glassworks.notifications.Delivery
import dev.glassworks.notifications.Notification
import dev.glassworks.notifications.NotificationStore.Entry
import dev.glassworks.notifications.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldsTest {
    private val folds = Folds()
    private fun e(id: Long, p: Priority, dnd: Boolean = false) = Entry(id, Notification("n$id", ""), 0, Delivery.of(p, dnd))

    @Test
    fun lowAndNormalFoldAfterTheirTimeOnScreen() {
        val low = e(1, Priority.LOW); val normal = e(2, Priority.NORMAL); val high = e(3, Priority.HIGH)
        val all = listOf(high, normal, low)
        assertEquals(10_000L, folds.update(all, screenOn = true, now = 0).nextFoldAt)
        assertEquals(listOf(high, normal), folds.update(all, true, 10_000).cards)
        assertEquals(listOf(high), folds.update(all, true, 15_000).cards)
        assertNull("high stays until answered", folds.update(all, true, 1_000_000).nextFoldAt)
    }

    @Test
    fun timeCountsOnlyWhileTheScreenIsOn() {
        val n = e(1, Priority.NORMAL)
        folds.update(listOf(n), screenOn = false, now = 0)
        assertEquals("came in the dark: still there an hour later", listOf(n), folds.update(listOf(n), false, 3_600_000).cards)
        folds.update(listOf(n), true, 3_600_000)
        assertEquals(listOf(n), folds.update(listOf(n), true, 3_614_000).cards)
        assertEquals(emptyList<Entry>(), folds.update(listOf(n), true, 3_615_000).cards)
    }

    @Test
    fun openedFromTheListItStays() {
        val n = e(1, Priority.NORMAL)
        folds.update(listOf(n), true, 0)
        folds.update(listOf(n), true, 15_000)
        folds.pin(1)
        assertEquals(listOf(n), folds.update(listOf(n), true, 1_000_000).cards)
    }

    /** CodeRabbit on #228: time counts only on screen, and adds up across a screen-off gap. */
    @Test
    fun theScreenGoingOffPausesTheClock() {
        val n = e(1, Priority.NORMAL)
        folds.update(listOf(n), screenOn = true, now = 0)
        folds.update(listOf(n), screenOn = false, now = 5_000)      // read for 5 s, then the screen went off
        assertEquals(listOf(n), folds.update(listOf(n), false, 60_000).cards)
        assertEquals("10 s left once it's back on", 40_000L, folds.update(listOf(n), true, 30_000).nextFoldAt)
        assertEquals(listOf(n), folds.update(listOf(n), true, 39_999).cards)
        assertEquals(emptyList<Entry>(), folds.update(listOf(n), true, 40_000).cards)
    }

    /** CodeRabbit on #228: a fourth card, out of the stack, doesn't fold before anyone saw it. */
    @Test
    fun onlyTheShownCardsCount() {
        val four = (1L..4L).map { e(it, Priority.NORMAL) }
        folds.update(four, true, 0)
        val v = folds.update(four, true, 15_000)
        assertEquals("the three shown folded; the fourth is now shown, its clock just started", listOf(four[3]), v.cards)
        assertEquals(30_000L, v.nextFoldAt)
    }

    /** CodeRabbit on #228: under the critical alert, nothing is seen, so nothing folds. */
    @Test
    fun theCriticalAlertCoversTheCards() {
        val n = e(1, Priority.NORMAL)
        folds.update(listOf(n), true, 0, covered = true)
        assertEquals(listOf(n), folds.update(listOf(n), true, 60_000, covered = true).cards)
        assertNull(folds.update(listOf(n), true, 60_000, covered = true).nextFoldAt)
    }
}
