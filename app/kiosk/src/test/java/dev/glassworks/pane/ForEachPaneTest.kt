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

    @Test fun nothingIsLoggedWhenEveryPaneSucceeds() {
        val panes = listOf(Recorder("a"), Recorder("b"))
        panes.forEachPane("onEvent") { it.onEvent(PanelEvent.SpeakerClaimed(true)) }
        assertEquals(listOf(1, 1), panes.map { it.events.size })
        assertTrue(logs.isEmpty())
    }
}
