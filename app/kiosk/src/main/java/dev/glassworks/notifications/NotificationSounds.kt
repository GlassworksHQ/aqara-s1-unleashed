/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The three built-in sounds (#118), made here from sine tones: no recordings, so nothing to license
 * beyond this file. 16 kHz, 16-bit, mono, little-endian PCM, as the panel's other chimes.
 *
 * | Sound | What it is | Length |
 * |---|---|---|
 * | chime | two soft bell notes, high then lower | 1.0 s |
 * | doorbell | "ding-dong" (E5 then C5), twice | 3.0 s |
 * | alert | three pairs of short bright beeps; the pane repeats it while a critical one is up | 1.45 s |
 */
object NotificationSounds {
    const val RATE = 16_000

    class Pcm(val rate: Int, val data: ByteArray) {
        val seconds: Double get() = data.size / 2.0 / rate
    }

    /** How long [sound] plays, without making it. */
    fun seconds(sound: Sound): Double = when (sound) {
        Sound.NONE -> 0.0
        Sound.CHIME -> 1.0
        Sound.DOORBELL -> 3.0
        Sound.ALERT -> 1.45
    }

    fun pcm(sound: Sound): Pcm? = when (sound) {
        Sound.NONE -> null
        Sound.CHIME -> render(seconds(sound), listOf(Note(0.0, 1318.5, 0.55, 0.22), Note(0.18, 1046.5, 0.7, 0.22)))
        Sound.DOORBELL -> render(seconds(sound), listOf(
            Note(0.0, 659.3, 0.9, 0.32), Note(0.55, 523.3, 1.1, 0.32),
            Note(1.35, 659.3, 0.9, 0.32), Note(1.9, 523.3, 1.1, 0.32),
        ))
        Sound.ALERT -> render(seconds(sound), (0 until 3).flatMap { k ->
            val t = k * 0.48
            listOf(Note(t, 1760.0, 0.09, 0.30, decay = 0.0), Note(t + 0.13, 1318.5, 0.09, 0.30, decay = 0.0))
        })
    }

    /** One bell-like note: [freq] with a soft octave above, starting at [at] s, [decay] s to fade (0: flat). */
    private class Note(val at: Double, val freq: Double, val length: Double, val gain: Double, val decay: Double = length / 3)

    private fun render(seconds: Double, notes: List<Note>): Pcm {
        val n = (seconds * RATE).toInt()
        val mix = DoubleArray(n)
        for (note in notes) {
            val start = (note.at * RATE).toInt()
            val len = (note.length * RATE).toInt()
            for (k in 0 until len) {
                val i = start + k
                if (i >= n) break
                val t = k.toDouble() / RATE
                // 5 ms in and 10 ms out, so no clicks.
                val edge = minOf(1.0, k / (0.005 * RATE), (len - k) / (0.010 * RATE))
                val env = if (note.decay > 0) exp(-t / note.decay) else 1.0
                val tone = sin(2 * PI * note.freq * t) + 0.25 * sin(2 * PI * 2 * note.freq * t)
                mix[i] += note.gain * edge * env * tone / 1.25
            }
        }
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (mix[i].coerceIn(-1.0, 1.0) * 32767).toInt()
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return Pcm(RATE, out)
    }
}
