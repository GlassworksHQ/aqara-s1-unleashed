/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future

class NotificationCenterTest {
    private var t = 0L
    private var dnd = false
    private val woke = mutableListOf<Boolean>()
    private val played = mutableListOf<Sound>()
    private val reports = mutableListOf<ActionReport>()
    private var cards: List<NotificationStore.Entry> = emptyList()
    private var page: NotificationStore.Entry? = null
    private var renders = 0
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val futures = mutableListOf<CompletableFuture<Unit>>()
    private var playFails = false
    private var reportFails = false

    private val out = object : NotificationOutputs {
        override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) {
            this@NotificationCenterTest.cards = cards; this@NotificationCenterTest.page = page; renders++
        }
        override fun wake(overNight: Boolean) { woke += overNight }
        override fun play(sound: Sound) { if (playFails) error("no speaker"); played += sound }
    }

    private val center = NotificationCenter(
        NotificationStore({ t }), out, { if (reportFails) error("offline"); reports += it }, { dnd }, { "http://homeassistant.local:8123" }, { t },
        { delay, action -> timers += delay to action; CompletableFuture<Unit>().also { futures += it } as Future<*> },
    )

    @Test
    fun priorityDecidesWakeAndSound() {
        center.handle("m", "low", """{"priority":"low"}""")
        center.handle("m", "normal", "")
        assertEquals(emptyList<Boolean>(), woke)
        assertEquals(listOf(Sound.CHIME), played)
        center.handle("m", "high", """{"priority":"high","sound":"doorbell"}""")
        assertEquals(listOf(true), woke)
        assertEquals(listOf(Sound.CHIME, Sound.DOORBELL), played)
        assertEquals(listOf("high", "normal", "low"), cards.map { it.notification.title })
    }

    @Test
    fun doNotDisturbHoldsAllButCritical() {
        dnd = true
        center.handle("m", "high", """{"priority":"high"}""")
        assertTrue(woke.isEmpty()); assertTrue(played.isEmpty()); assertTrue(cards.isEmpty())
        center.handle("m", "smoke", """{"priority":"critical"}""")
        assertEquals(listOf(true), woke)
        assertEquals(listOf(Sound.ALERT), played)
        assertEquals(listOf("smoke"), cards.map { it.notification.title })
    }

    @Test
    fun aTapReportsTheActionWithTheTag() {
        center.handle("Someone is at the door", "Doorbell",
            """{"tag":"doorbell","actions":[{"action":"open_gate","title":"Open the gate"}]}""")
        val id = cards.single().id
        center.tapAction(id, "open_gate")
        assertEquals(listOf(ActionReport("open_gate", "doorbell")), reports)
        assertEquals(mapOf("action" to "open_gate", "tag" to "doorbell"), reports.single().data)
        assertEquals("esphome.notification_action", ActionReport.EVENT)
        assertTrue(cards.isEmpty())
        center.tapAction(id, "open_gate")
        assertEquals("a closed notification reports nothing", 1, reports.size)
    }

    @Test
    fun withoutATagTheReportHasNone() {
        center.handle("m", "t", """{"sticky":true,"actions":[{"action":"snooze","title":"Snooze"}]}""")
        center.tapAction(cards.single().id, "snooze")
        assertEquals(mapOf("action" to "snooze"), reports.single().data)
        assertEquals("sticky stays", 1, cards.size)
    }

    @Test
    fun clearClosesTheTaggedOne() {
        center.handle("m", "door", """{"tag":"doorbell","url":"/dashboard-door/0"}""")
        assertEquals("http://homeassistant.local:8123/dashboard-door/0", page?.notification?.url)
        center.handle("clear_notification", "", """{"tag":"doorbell"}""")
        assertNull(page)
    }

    @Test
    fun aTimeoutClosesItAndGoesBackToTheDashboard() {
        center.handle("m", "door", """{"url":"/dashboard-door/0","timeout":30}""")
        val (delay, fire) = timers.last()
        assertEquals(30_000L, delay)
        t = 30_000; fire()
        assertNull(page)
    }

    @Test
    fun mistakesGoBackToHomeAssistantAndChangeNothing() {
        assertThrows(IllegalArgumentException::class.java) { center.handle("m", "t", """{"url":"https://example.com/"}""") }
        assertEquals(0, renders)
        assertTrue(played.isEmpty())
    }

    @Test
    fun aFailingSoundStillShowsTheCard() {
        playFails = true
        assertThrows(IllegalStateException::class.java) { center.handle("m", "washer", "") }
        assertEquals(listOf("washer"), cards.map { it.notification.title })
    }

    @Test
    fun aFailingReportStillClosesTheCard() {
        center.handle("m", "door", """{"actions":[{"action":"open_gate","title":"Open"}]}""")
        reportFails = true
        assertThrows(IllegalStateException::class.java) { center.tapAction(cards.single().id, "open_gate") }
        assertTrue(cards.isEmpty())
    }

    @Test
    fun closeStopsTheTimer() {
        center.handle("m", "door", """{"timeout":30}""")
        center.close()
        assertTrue(futures.last().isCancelled)
        val before = timers.size
        center.handle("m", "garage", """{"timeout":30}""")
        assertEquals("nothing is scheduled after close", before, timers.size)
    }

    @Test
    fun rendersArriveInTheOrderTheChangesHappened() {
        val sizes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val c = NotificationCenter(
            NotificationStore({ 0L }, max = 1000),
            object : NotificationOutputs {
                override fun render(cards: List<NotificationStore.Entry>, page: NotificationStore.Entry?, list: List<NotificationStore.Entry>) {
                    sizes += list.size
                    Thread.yield()
                }
                override fun wake(overNight: Boolean) {}
                override fun play(sound: Sound) {}
            },
            {}, { false }, { null }, { 0L }, { _, _ -> CompletableFuture<Unit>() },
        )
        val threads = (1..4).map { k -> Thread { repeat(150) { c.handle("m", "t$k-$it", "") } } }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(600, sizes.size)
        assertEquals("each render shows at least as many as the one before", sizes.sorted(), sizes.toList())
    }
}
