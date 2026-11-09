package sh.edm.s1kiosk.base

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseTrackerTest {

    private fun v(vararg p: Pair<String, Any>) = mapOf(*p)

    @Test
    fun unknownUntilFirstReply() {
        val t = BaseTracker(BaseLayout.NONE)
        assertNull(t.current())
        assertNull(t.due(0))
        t.markDockNoReply()
        assertEquals(BaseLayout.NONE, t.current())
    }

    @Test
    fun noBaseNeverRebuilds() {
        val t = BaseTracker(BaseLayout.NONE)
        t.setServiceAvailable(true)
        t.apply(v(BaseRes.DOCK_CLASS to 0), 0)
        assertEquals("none", t.current()!!.describe())
        for (now in 0L..60_000L step 1_000) assertNull(t.due(now))
        // none <-> unavailable has the same entity set: no rebuild either.
        t.setServiceAvailable(false)
        assertEquals("unavailable", t.current()!!.describe())
        for (now in 60_000L..120_000L step 1_000) assertNull(t.due(now))
    }

    @Test
    fun switchBaseRebuildsOnceAfterDebounce() {
        val t = BaseTracker(BaseLayout.NONE, debounceMs = 5_000)
        t.setServiceAvailable(true)
        t.apply(v(BaseRes.DOCK_CLASS to 16838928), 0)
        assertNull(t.due(0)) // switch, channels unknown yet
        t.apply(v(BaseRes.CHANNEL_COUNT to 2, BaseRes.RELAY_MASK to 3), 300)
        assertNull(t.due(300)) // candidate changed -> debounce restarts
        assertNull(t.due(5_000))
        val l = t.due(5_300)
        assertEquals(BaseLayout(BaseKind.SWITCH, listOf(1, 2)), l)
        t.markApplied(l!!)
        for (now in 5_300L..60_000L step 1_000) assertNull(t.due(now))
        assertEquals("switch (2ch)", t.current()!!.describe())
    }

    @Test
    fun flappingNeverRebuilds() {
        val t = BaseTracker(BaseLayout.NONE, debounceMs = 5_000)
        t.setServiceAvailable(true)
        var now = 0L
        repeat(20) { i ->
            t.apply(v(BaseRes.DOCK_CLASS to if (i % 2 == 0) 16838928 else 0, BaseRes.CHANNEL_COUNT to 2), now)
            assertNull(t.due(now))
            now += 3_000
            assertNull(t.due(now))
        }
    }

    @Test
    fun persistedLayoutStaysWhenConfirmed() {
        val applied = BaseLayout(BaseKind.SWITCH, listOf(1, 2))
        val t = BaseTracker(applied)
        t.setServiceAvailable(true)
        t.apply(v(BaseRes.DOCK_CLASS to 16838928, BaseRes.RELAY_MASK to 3), 0)
        for (now in 0L..20_000L step 1_000) assertNull(t.due(now))
        // ... and is removed (after the debounce) when the base is gone.
        t.apply(v(BaseRes.DOCK_CLASS to 0), 20_000)
        assertNull(t.due(20_000))
        assertEquals(BaseLayout.NONE, t.due(25_000))
    }

    @Test
    fun valuesAndChanges() {
        val t = BaseTracker(BaseLayout.NONE)
        val u = t.apply(v("4.1.85" to 1, "4.2.85" to "0", BaseRes.POWER to 12.5, BaseRes.ENERGY to "3",
            BaseRes.PROTECTION to 2, BaseRes.FIRMWARE to 25, "4.1.28" to "Kitchen"), 0)
        assertEquals(setOf("4.1.85", "4.2.85", BaseRes.POWER, BaseRes.ENERGY, BaseRes.PROTECTION, BaseRes.FIRMWARE, "4.1.28"), u.changed)
        assertEquals(true, t.relay(1))
        assertEquals(false, t.relay(2))
        assertNull(t.relay(3))
        assertEquals(12.5f, t.power())
        assertEquals(3f, t.energy())
        assertEquals("over_load", BaseProtocol.protectionText(t.protection()))
        assertEquals("25", t.firmware())
        assertEquals("Kitchen", t.channelName(1))
        assertTrue(t.apply(v("4.1.85" to 1), 10).changed.isEmpty())
        assertFalse(u.dockMentioned)
        assertTrue(t.apply(v(BaseRes.DOCK_DETECT to 1), 20).dockMentioned)
        t.clearBaseValues()
        assertNull(t.relay(1))
        assertNull(t.power())
    }

    @Test
    fun clicksAreDeduplicated() {
        val t = BaseTracker(BaseLayout.NONE, clickDedupMs = 250)
        assertEquals(listOf(BaseTracker.Click(1, 1, "single")), t.apply(v("13.1.85" to 1), 0).clicks)
        assertTrue(t.apply(v("13.1.85" to 1), 100).clicks.isEmpty()) // same click via a second callback
        assertEquals("double", t.apply(v("13.1.85" to 2), 150).clicks.single().type)
        assertEquals(1, t.apply(v("13.1.85" to 1), 1_000).clicks.size)
        assertEquals(3, t.apply(v("13.3.85" to 0), 1_000).clicks.single().channel)
    }

    @Test
    fun entityIdsPerLayout() {
        assertEquals(listOf("base"), BaseEntities.objectIds(BaseLayout.NONE))
        assertEquals(listOf("base"), BaseEntities.objectIds(BaseLayout.UNAVAILABLE))
        assertEquals(listOf("base", "base_firmware"), BaseEntities.objectIds(BaseLayout(BaseKind.ETHERNET)))
        assertEquals(
            listOf("base", "base_firmware", "base_relay_1", "base_relay_2", "base_power", "base_energy",
                "base_protection", "base_button_1", "base_button_2"),
            BaseEntities.objectIds(BaseLayout(BaseKind.SWITCH, listOf(1, 2))),
        )
        val t = BaseTracker(BaseLayout.NONE)
        val built = BaseEntities.build(BaseLayout(BaseKind.SWITCH, listOf(1, 2, 3)), t) { _, _ -> }
        assertEquals(BaseEntities.objectIds(BaseLayout(BaseKind.SWITCH, listOf(1, 2, 3))), built.map { it.objectId })
        assertEquals(built.size, built.map { it.key }.toSet().size)
    }
}
