/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NotificationSoundsTest {
    private fun peak(p: NotificationSounds.Pcm): Int =
        (0 until p.data.size / 2).maxOf { i -> abs(((p.data[2 * i + 1].toInt() shl 8) or (p.data[2 * i].toInt() and 0xFF)).toShort().toInt()) }

    @Test
    fun threeSoundsAndSilence() {
        assertNull(NotificationSounds.pcm(Sound.NONE))
        val lengths = mapOf(Sound.CHIME to 1.0, Sound.DOORBELL to 3.0, Sound.ALERT to 1.45)
        for ((s, secs) in lengths) {
            val p = NotificationSounds.pcm(s)!!
            assertEquals(16_000, p.rate)
            assertEquals(s.id, secs, p.seconds, 0.001)
            val pk = peak(p)
            assertTrue("$s is audible ($pk)", pk > 5_000)
            assertTrue("$s doesn't clip ($pk)", pk < 32_000)
        }
    }

    @Test
    fun noClickAtTheEnd() {
        for (s in listOf(Sound.CHIME, Sound.DOORBELL, Sound.ALERT)) {
            val d = NotificationSounds.pcm(s)!!.data
            val last = ((d[d.size - 1].toInt() shl 8) or (d[d.size - 2].toInt() and 0xFF)).toShort().toInt()
            assertTrue("$s ends near silence", abs(last) < 200)
        }
    }
}
