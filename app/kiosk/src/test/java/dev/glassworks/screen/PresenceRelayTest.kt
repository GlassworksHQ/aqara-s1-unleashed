/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.screen

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresenceRelayTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private class Sensor(val present: Boolean) : PresenceSource {
        var listener: ((Boolean, Float) -> Unit)? = null
        var range: Float? = null
        override fun start(context: Context, onReading: (Boolean, Float) -> Unit) { listener = onReading }
        override fun stop(context: Context) { listener = null }
        override fun setRange(context: Context, metres: Float): String? { range = metres; return "why" }
        override fun available(context: Context) = present
    }

    @Test fun theFramesSensorPassesThroughUnchanged() {
        val s = Sensor(present = true)
        val r = PresenceRelay(s)
        val got = mutableListOf<Boolean>()
        r.start(ctx) { near, _ -> got += near }
        s.listener!!(true, 1f)
        assertEquals("why", r.setRange(ctx, 1.5f))
        assertEquals(1.5f, s.range)
        r.stop(ctx)
        assertNull(s.listener)
        assertEquals(listOf(true), got)
        assertTrue(r.ownSensor(ctx))
    }

    @Test fun offersReachThePolicyOnlyWhileItListens() {
        val r = PresenceRelay(Sensor(present = false))
        val got = mutableListOf<Pair<Boolean, Float>>()
        r.offer(true, 0.2f) // before start: dropped
        r.start(ctx) { near, raw -> got += near to raw }
        r.offer(true, 0.2f)
        r.stop(ctx)
        r.offer(false, 0f)
        assertEquals(listOf(true to 0.2f), got)
        assertFalse(r.ownSensor(ctx))
    }

    @Test fun aFrameWithNoSensorHasNoneOfItsOwn() {
        val r = PresenceRelay(null)
        assertFalse(r.ownSensor(ctx))
        assertNull(r.setRange(ctx, 1f))
    }
}
