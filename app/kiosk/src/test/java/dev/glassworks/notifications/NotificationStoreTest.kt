/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationStoreTest {
    private var t = 1_000L
    private val store = NotificationStore({ t }, max = 5)

    private fun n(title: String, tag: String? = null, p: Priority = Priority.NORMAL, timeout: Int? = null, url: String? = null, sticky: Boolean = false) =
        Notification(title, "", tag = tag, priority = p, timeoutS = timeout, url = url, sticky = sticky,
            actions = listOf(NotificationAction("ok", "OK")))

    @Test
    fun deliveryRules() {
        fun d(p: Priority, dnd: Boolean) = Delivery.of(p, dnd)
        assertEquals(Delivery(held = false, sound = false, wake = false, overNight = false, foldAfterS = 10), d(Priority.LOW, false))
        assertEquals(Delivery(held = false, sound = true, wake = false, overNight = false, foldAfterS = 15), d(Priority.NORMAL, false))
        assertEquals(Delivery(held = false, sound = true, wake = true, overNight = true), d(Priority.HIGH, false))
        assertEquals(Delivery(held = false, sound = true, wake = true, overNight = true), d(Priority.CRITICAL, false))
        for (p in listOf(Priority.LOW, Priority.NORMAL, Priority.HIGH)) {
            assertEquals(Delivery(held = true, sound = false, wake = false, overNight = false), d(p, true))
        }
        assertEquals(d(Priority.CRITICAL, false), d(Priority.CRITICAL, true))
    }

    @Test
    fun newestFirst() {
        store.post(n("a"), false); store.post(n("b"), false)
        assertEquals(listOf("b", "a"), store.list().map { it.notification.title })
    }

    @Test
    fun aTagReplaces() {
        val first = store.post(n("Washer: 10 min left", tag = "washer"), false).entry
        store.post(n("Door open", tag = "door"), false)
        val p = store.post(n("Washer done", tag = "washer"), false)
        assertEquals(first, p.replaced)
        assertEquals(listOf("Washer done", "Door open"), store.list().map { it.notification.title })
        assertTrue(p.entry.id != first.id)
    }

    @Test
    fun clearByTagAndDismissById() {
        val a = store.post(n("a", tag = "door"), false).entry
        val b = store.post(n("b"), false).entry
        assertEquals(a, store.clear("door"))
        assertNull(store.clear("door"))
        assertEquals(b, store.dismiss(b.id))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun timeoutsExpire() {
        store.post(n("short", timeout = 10), false)
        store.post(n("long", timeout = 60), false)
        store.post(n("forever"), false)
        assertEquals(11_000L, store.nextExpiry())
        t = 10_999; assertTrue(store.expire().isEmpty())
        t = 11_000; assertEquals(listOf("short"), store.expire().map { it.notification.title })
        assertEquals(61_000L, store.nextExpiry())
        t = 100_000; store.expire()
        assertEquals(listOf("forever"), store.list().map { it.notification.title })
        assertNull(store.nextExpiry())
    }

    @Test
    fun doNotDisturbHoldsInTheList() {
        store.post(n("quiet"), doNotDisturb = true)
        store.post(n("fire", p = Priority.CRITICAL), doNotDisturb = true)
        assertEquals(listOf("fire"), store.cards().map { it.notification.title })
        assertEquals(2, store.list().size)
        val held = store.list().first { it.held }
        store.show(held.id)
        assertEquals(listOf("fire", "quiet"), store.cards().map { it.notification.title })
    }

    @Test
    fun oneFullScreenPageAtATime() {
        val door = store.post(n("door", url = "http://ha/door"), false).entry
        assertEquals(door, store.page())
        assertTrue(store.cards().isEmpty())
        val garage = store.post(n("garage", url = "http://ha/garage"), false).entry
        assertEquals(garage, store.page())
        assertTrue(store.list().first { it.id == door.id }.held)
        store.dismiss(garage.id)
        assertNull("an older page doesn't come back by itself", store.page())
        store.show(door.id)
        assertEquals(door.id, store.page()?.id)
        // A page held by Do not disturb doesn't take the screen.
        store.post(n("quiet page", url = "http://ha/q"), doNotDisturb = true)
        assertEquals(door.id, store.page()?.id)
    }

    @Test
    fun anActionClosesUnlessSticky() {
        val a = store.post(n("a", tag = "door"), false).entry
        val s = store.post(n("s", sticky = true), false).entry
        assertEquals(NotificationStore.Acted(NotificationAction("ok", "OK"), "door", closed = true), store.act(a.id, "ok"))
        assertEquals(NotificationStore.Acted(NotificationAction("ok", "OK"), null, closed = false), store.act(s.id, "ok"))
        assertNull(store.act(s.id, "nope"))
        assertNull(store.act(a.id, "ok"))
        assertEquals(listOf(s.id), store.list().map { it.id })
    }

    @Test
    fun theOldestGoesPastTheCapCriticalLast() {
        store.post(n("c0", p = Priority.CRITICAL), false)
        for (i in 1..5) store.post(n("n$i"), false)
        assertEquals(listOf("n5", "n4", "n3", "n2", "c0"), store.list().map { it.notification.title })
        val p = store.post(n("n6"), false)
        assertEquals(listOf("n2"), p.dropped.map { it.notification.title })
        assertFalse(store.list().any { it.notification.title == "n2" })
    }

    @Test
    fun aDroppedPageDoesNotTakeTheScreen() {
        val one = NotificationStore({ t }, max = 1)
        val fire = one.post(n("fire", p = Priority.CRITICAL, url = "http://ha/fire"), false).entry
        val p = one.post(n("door", url = "http://ha/door"), false)
        assertEquals(listOf("door"), p.dropped.map { it.notification.title })
        assertEquals(fire, one.page())
    }

    @Test
    fun anOverdueEntryDoesNotTakeAPlace() {
        val one = NotificationStore({ t }, max = 1)
        one.post(n("fire", p = Priority.CRITICAL, timeout = 1), false)
        t += 1_000
        val p = one.post(n("washer"), false)
        assertTrue(p.dropped.isEmpty())
        assertEquals(listOf("washer"), one.list().map { it.notification.title })
    }

    /** Verifier carry-over, decided: a held one has no fold time, so opened from the list it stays until closed. */
    @Test
    fun aHeldOneStaysOnceOpened() {
        for (p in listOf(Priority.LOW, Priority.NORMAL, Priority.HIGH)) assertNull(Delivery.of(p, doNotDisturb = true).foldAfterS)
        val e = store.post(n("quiet"), doNotDisturb = true).entry
        assertNull(store.show(e.id)!!.delivery.foldAfterS)
    }
}
