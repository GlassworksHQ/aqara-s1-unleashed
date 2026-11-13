package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** [AudioTrackSink] over fake tracks: one track at a time, released when replaced or closed. */
class AudioTrackSinkTest {
    private class Track(val rate: Int, val channels: Int, var gain: Float) : PcmTrack {
        var released = false
        var samples = 0
        override fun write(buf: ShortArray, samples: Int): Int { this.samples += samples; return samples }
        override fun timestamp(out: LongArray) = false
        override fun setVolume(gain: Float) { this.gain = gain }
        override fun release() { released = true }
    }

    private val made = mutableListOf<Track>()
    /** Runs inside the next track's creation, as another thread's call would while it blocks. */
    private var duringCreation: () -> Unit = {}
    private val sink: AudioTrackSink = AudioTrackSink { rate, ch, gain ->
        duringCreation().also { duringCreation = {} }
        Track(rate, ch, gain).also { made += it }
    }

    @Test
    fun openReplacesAndReleasesTheTrackAndKeepsTheGain() {
        assertEquals("no track yet", 0, sink.write(ShortArray(4), 2))
        sink.setGain(0.25f)
        sink.open(48_000, 2)
        assertEquals("frames, not samples", 2, sink.write(ShortArray(4), 2))
        assertEquals(4, made[0].samples)
        sink.setGain(0.5f)
        sink.open(44_100, 1)
        assertTrue("the first track was released", made[0].released)
        assertEquals(0.5f, made[1].gain)
        assertEquals(44_100 to 1, made[1].rate to made[1].channels)
        assertEquals("mono", 2, sink.write(ShortArray(2), 2))
        sink.close()
        sink.close()
        assertTrue(made[1].released)
        assertFalse("no write after close", sink.write(ShortArray(2), 1) > 0)
    }

    @Test
    fun aGainChangeWhileTheTrackIsBeingCreatedReachesIt() {
        sink.setGain(0.6f)
        duringCreation = { sink.setGain(0f) } // muted or ducked while AudioTrack.Builder.build() runs
        sink.open(48_000, 2)
        assertEquals(0f, made.single().gain)
    }

    @Test
    fun aGainChangeFromAnotherThreadDoesNotWaitForTheTrackCreation() {
        val creating = CountDownLatch(1)
        val gate = CountDownLatch(1)
        sink.setGain(0.6f)
        duringCreation = { creating.countDown(); gate.await(5, TimeUnit.SECONDS) } // a slow AudioTrack.Builder.build()
        val opener = thread(name = "audio") { sink.open(48_000, 2) }
        try {
            assertTrue("creation started", creating.await(5, TimeUnit.SECONDS))
            val duck = thread(name = "voice") { sink.setGain(0f) }
            duck.join(2_000)
            assertFalse("setGain returned while the track was still being created", duck.isAlive)
        } finally {
            gate.countDown()
        }
        opener.join(5_000)
        assertFalse(opener.isAlive)
        assertEquals("the change made during creation reached the track", 0f, made.single().gain)
    }
}
