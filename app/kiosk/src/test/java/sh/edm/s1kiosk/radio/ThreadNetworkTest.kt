package sh.edm.s1kiosk.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ThreadNetworkTest {

    companion object {
        /** A fake "dataset": random hex of a typical length (107 bytes). Not a real network, never logged. */
        fun fakeDataset(seed: Long, bytes: Int = 107): String {
            val r = Random(seed)
            return (1..bytes).joinToString("") { "%02x".format(r.nextInt(256)) }
        }
    }

    @Test
    fun invisibleCharactersAreDropped() {
        val d = fakeDataset(1)
        val pasted = listOf(
            " $d ", "$d\n", "\r\n$d\r\n", "\t$d", " $d ", "​$d", "$d﻿", "⁠$d ",
            d.chunked(32).joinToString("\n"), d.chunked(16).joinToString(" "), d.uppercase(),
        )
        for (p in pasted) {
            assertNull(p.map { "%04x".format(it.code) }.joinToString(), ThreadDataset.problem(p))
            assertEquals(d, ThreadDataset.clean(p))
        }
    }

    @Test
    fun anythingElseThatIsNotHexIsStillRefused() {
        val d = fakeDataset(2)
        for (bad in listOf("${d}g", "0x$d", "$d,", "${d.dropLast(1)}", "", "  \n", "${d}é", "$d-00")) {
            val why = ThreadDataset.problem(bad)
            assertTrue(bad, why != null && why.startsWith("Not a Thread dataset"))
            assertFalse("the message never contains the value", d.isNotEmpty() && why!!.contains(d.take(20)))
        }
        assertNull(ThreadDataset.problem(fakeDataset(3, bytes = ThreadDataset.MAX_BYTES)))
        assertTrue(ThreadDataset.problem(fakeDataset(3, bytes = ThreadDataset.MAX_BYTES + 1))!!.contains("longer"))
    }

    @Test
    fun theEntityPatternAcceptsWhatTheAppCleansAndNothingElse() {
        // Java's regex here (the app's TextEntity); Home Assistant checks the same pattern with Python's re.
        val p = Regex(ThreadDataset.PATTERN)
        val d = fakeDataset(4)
        for (ok in listOf("", d, " $d\n", " $d", "​$d﻿", d.chunked(8).joinToString(" "))) assertTrue(ok, p.matches(ok))
        for (bad in listOf("${d}g", "0x$d", "$d;")) assertFalse(bad, p.matches(bad))
        // everything the pattern lets through, the app cleans to plain hex
        for (c in listOf(' ', '\n', '\t', ' ', ' ', '​', '‍', '⁠', '　', '﻿')) {
            assertTrue("%04x".format(c.code), p.matches("$c"))
            assertTrue("%04x".format(c.code), ThreadDataset.invisible(c))
        }
    }

    @Test
    fun routerStateFromTheHelper() {
        assertEquals(ThreadRouterState(false, null, null), ThreadRouterState.parse("ready to join: waiting for a Thread dataset from Home Assistant"))
        assertEquals(ThreadRouterState(true, "child", "Example-Net"), ThreadRouterState.parse("joined as child, network \"Example-Net\""))
        assertEquals(ThreadRouterState(true, "router", null), ThreadRouterState.parse("joined as router"))
        assertEquals(ThreadRouterState(true, "leader", "A B"), ThreadRouterState.parse("joined as leader, network \"A B\""))
        assertEquals(ThreadRouterState(true, "detached", "X"), ThreadRouterState.parse("detached: looking for network \"X\""))
        assertEquals(ThreadRouterState(true, "disabled", null), ThreadRouterState.parse("starting Thread"))
        assertEquals(ThreadRouterState(true, "child", null), ThreadRouterState.parse("Thread router on wpan0 (child)")) // 0.4.2 helper
        assertNull(ThreadRouterState.parse("ot-ctl join steps failed (dataset rejected?)"))
        assertTrue(ThreadRouterState(true, "router", "N").joined)
        assertFalse(ThreadRouterState(true, "detached", "N").joined)
        assertTrue(ThreadRouterState(true, "child", "N").describe().contains("becomes a router"))
        assertTrue(ThreadRouterState(false, null, null).describe().startsWith("no dataset yet"))
    }
}
