package sh.edm.esphome

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The messages waiting for one connection's writer thread, so that a client that stops reading
 * holds up only itself (#213). A state replaces the one still waiting for the same entity, in its
 * place in the queue, so states never fill it. Other messages count against [maxBytes]: [offer]
 * refuses them once that much is waiting, and the caller drops the connection.
 */
internal class Outbox(private val maxBytes: Int) {
    private class Entry(val stateKey: Int?, var frame: Frame, val seq: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = ArrayDeque<Entry>()
    private val waitingStates = HashMap<Int, Entry>()
    private var bytes = 0
    private var lastSeq = 0L
    private var takenSeq = 0L
    private var writtenSeq = 0L
    private var closed = false

    /** Queues [frames], all or none. False if closed or if they don't fit. */
    fun offer(frames: List<Frame>): Boolean = enqueue(frames) >= 0

    /** Like [offer], then waits until the writer has written them. False if the connection closed first. */
    fun offerAndWait(frames: List<Frame>): Boolean {
        val seq = enqueue(frames)
        if (seq < 0) return false
        lock.withLock {
            while (writtenSeq < seq) {
                if (closed) return false
                changed.await()
            }
        }
        return true
    }

    /** Queues the newest state of entity [key]. False only if closed. */
    fun offerState(key: Int, frame: Frame): Boolean = lock.withLock {
        if (closed) return false
        val waiting = waitingStates[key]
        if (waiting != null) {
            waiting.frame = frame
        } else {
            val e = Entry(key, frame, ++lastSeq)
            queue.addLast(e)
            waitingStates[key] = e
            changed.signalAll()
        }
        true
    }

    private fun enqueue(frames: List<Frame>): Long = lock.withLock {
        if (closed) return -1
        val size = frames.sumOf { it.data.size + FRAME_OVERHEAD }
        if (bytes + size > maxBytes) return -1
        bytes += size
        for (f in frames) queue.addLast(Entry(null, f, ++lastSeq))
        changed.signalAll()
        lastSeq
    }

    /** Writer thread: everything waiting, in order; blocks while there is nothing. Null once closed. */
    fun take(): List<Frame>? = lock.withLock {
        while (queue.isEmpty() && !closed) changed.await()
        if (closed) return null
        val out = ArrayList<Frame>(queue.size)
        while (true) {
            val e = queue.pollFirst() ?: break
            if (e.stateKey != null) waitingStates.remove(e.stateKey) else bytes -= e.frame.data.size + FRAME_OVERHEAD
            out += e.frame
            takenSeq = e.seq
        }
        out
    }

    /** Writer thread: the frames from the last [take] are on the wire. */
    fun written() = lock.withLock {
        writtenSeq = takenSeq
        changed.signalAll()
    }

    fun close() = lock.withLock {
        closed = true
        queue.clear()
        waitingStates.clear()
        changed.signalAll()
    }

    private companion object {
        /** Framing and the queue entry, roughly, so many tiny messages still fill the queue. */
        const val FRAME_OVERHEAD = 16
    }
}
