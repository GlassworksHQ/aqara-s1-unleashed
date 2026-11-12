/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import sh.edm.esphome.EspHomeLog

class ForEachPaneTest {

    private val logs = mutableListOf<Pair<String, Throwable?>>()
    private lateinit var oldSink: EspHomeLog.Sink

    @Before fun captureLogs() {
        oldSink = EspHomeLog.sink
        EspHomeLog.sink = EspHomeLog.Sink { level, _, msg, t -> if (level == EspHomeLog.Level.WARN) logs += msg to t }
    }

    @After fun restoreLogs() { EspHomeLog.sink = oldSink }

    private class Recorder(override val id: String, private val fail: Boolean = false) : Pane {
        val events = mutableListOf<PanelEvent>()
        override fun onEvent(event: PanelEvent) {
            if (fail) throw IllegalStateException("boom")
            events += event
        }
    }

    @Test fun aThrowingPaneDoesNotStopTheOthers() {
        val first = Recorder("first")
        val broken = Recorder("broken", fail = true)
        val last = Recorder("last")
        val event = PanelEvent.SettingChanged("x")

        listOf(first, broken, last).forEachPane("onEvent") { it.onEvent(event) }

        assertEquals(listOf(event), first.events)
        assertEquals(listOf(event), last.events)
        assertEquals(1, logs.size)
        assertEquals("pane broken failed in onEvent", logs[0].first)
        assertTrue(logs[0].second is IllegalStateException)
    }

    @Test fun anInterruptionStopsTheLoopAndKeepsTheInterruptFlag() {
        val first = Recorder("first")
        val last = Recorder("last")
        val interrupted = object : Pane {
            override val id = "interrupted"
            override fun onEvent(event: PanelEvent) = throw InterruptedException()
        }
        try {
            listOf(first, interrupted, last).forEachPane("start") { it.onEvent(PanelEvent.SettingChanged("x")) }
            fail("the interruption must reach the caller")
        } catch (_: InterruptedException) {
        }
        assertTrue(Thread.interrupted())
        assertEquals(1, first.events.size)
        assertTrue(last.events.isEmpty())
        assertTrue(logs.isEmpty())
    }

    private class Stopper(override val id: String, private val throwOnStop: Throwable? = null) : Pane {
        var stopped = false
        var interruptedWhenStopped = false
        override fun stop() {
            interruptedWhenStopped = Thread.currentThread().isInterrupted
            stopped = true
            if (throwOnStop is InterruptedException) Thread.currentThread().interrupt()
            throwOnStop?.let { throw it }
        }
    }

    @Test fun stopAllStopsEveryPaneWhenOneIsInterrupted() {
        val first = Stopper("first")
        val interrupted = Stopper("interrupted", InterruptedException())
        val broken = Stopper("broken", IllegalStateException("boom"))
        val last = Stopper("last")

        val wasInterrupted = listOf(first, interrupted, broken, last).stopAll()

        assertTrue(listOf(first, interrupted, broken, last).all { it.stopped })
        assertTrue("the panes after the interrupted one stop without the flag set", !broken.interruptedWhenStopped && !last.interruptedWhenStopped)
        assertTrue("the caller learns about the interruption", wasInterrupted)
        assertTrue("the flag is left for the caller to set when its shutdown is done", !Thread.interrupted())
        assertEquals(listOf("pane broken failed in stop"), logs.map { it.first })
    }

    @Test fun anInterruptAlreadyPendingDoesNotCutTheFirstStopShort() {
        val first = Stopper("first")
        Thread.currentThread().interrupt()

        val wasInterrupted = listOf(first).stopAll()

        assertTrue(!first.interruptedWhenStopped)
        assertTrue(wasInterrupted)
        assertTrue(!Thread.interrupted())
    }

    @Test fun stopAllReportsNoInterruptionWhenThereWasNone() {
        assertTrue(!listOf(Stopper("a"), Stopper("b")).stopAll())
        assertTrue(!Thread.interrupted())
    }

    @Test fun nothingIsLoggedWhenEveryPaneSucceeds() {
        val panes = listOf(Recorder("a"), Recorder("b"))
        panes.forEachPane("onEvent") { it.onEvent(PanelEvent.SpeakerClaimed(true)) }
        assertEquals(listOf(1, 1), panes.map { it.events.size })
        assertTrue(logs.isEmpty())
    }
}
