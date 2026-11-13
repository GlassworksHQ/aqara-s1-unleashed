package sh.edm.esphome

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A camera entity (`ListEntitiesCameraResponse` 43). Home Assistant asks for images with
 * `CameraImageRequest` (45): `single` for one frame (a picture card, a snapshot), `stream` for
 * frames until [ApiServer.CAMERA_STREAM_MS] pass without another stream request (the more-info
 * dialog's MJPEG stream asks again after every frame). Each image goes out as `CameraImageResponse`
 * (44) chunks of [CHUNK_BYTES], the last one with `done` set.
 *
 * [frame] returns a JPEG, or null when there is none (Home Assistant then gets an empty image and
 * reports "Unable to get image" at once instead of timing out). It runs on a camera thread of the
 * connection that asked, only while a client wants frames, so a pane can use it to show that frames
 * are leaving the device. A stream calls it at most [maxFps] times a second per connection. Return
 * promptly: a connection's camera thread ends only after [frame] returns.
 * With [authOnly] set, only an authenticated connection (Noise with the provisioned key) gets images.
 */
class CameraEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    val maxFps: Float = 10f,
    private val frame: () -> ByteArray?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    init { require(maxFps > 0f) { "maxFps must be positive" } }

    override val listType = Msg.LIST_ENTITIES_CAMERA

    override fun info() = base().bool(5, disabledByDefault).string(6, icon).uint32(7, category.value)

    internal val frameIntervalMs: Long get() = (1000f / maxFps).toLong()

    /** One image as `CameraImageResponse` payloads: [CHUNK_BYTES] each, `done` on the last. */
    internal fun responses(jpeg: ByteArray?): List<ByteArray> {
        val data = jpeg ?: ByteArray(0)
        if (data.isEmpty()) return listOf(response(data, done = true))
        return (data.indices step CHUNK_BYTES).map { from ->
            val to = minOf(from + CHUNK_BYTES, data.size)
            response(data.copyOfRange(from, to), done = to == data.size)
        }
    }

    private fun response(chunk: ByteArray, done: Boolean) =
        ProtoWriter().fixed32(1, key, force = true).bytes(2, chunk).bool(3, done).toByteArray()

    internal fun capture(): ByteArray? {
        val jpeg = try { frame() } catch (t: Throwable) {
            EspHomeLog.w("camera $objectId: frame failed", t)
            return null
        }
        if (jpeg != null && jpeg.size > MAX_FRAME_BYTES) {
            EspHomeLog.w("camera $objectId: frame of ${jpeg.size} bytes is over the client's ${MAX_FRAME_BYTES}-byte limit")
            return null
        }
        return jpeg
    }

    companion object {
        /**
         * Small enough that other messages (states, pings) go out between the chunks of a big
         * image, big enough to keep the writes per frame low. ESPHome uses 1390, for its MTU.
         */
        const val CHUNK_BYTES = 8 * 1024

        /** aioesphomeapi drops a bigger image (client_base.py `MAX_CAMERA_FRAME_BYTES`). */
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}

/**
 * The camera requests of one connection, served on its own thread so that a slow client neither
 * blocks the connection's reader nor other clients. The thread asks the camera for a frame only
 * when it is ready to send one, so a slow link gets fewer frames rather than a queue.
 * [cameras] are the cameras this connection may get images from now (none until it subscribed to
 * states, as in ESPHome). [send] returns false once the connection is gone.
 */
internal class CameraFeed(
    private val threadName: String,
    private val streamMs: Long,
    private val cameras: () -> List<CameraEntity>,
    private val send: (Frame) -> Boolean,
) {
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private var single = false
    private var streamUntil = 0L
    private var closed = false
    private var thread: Thread? = null

    fun request(single: Boolean, stream: Boolean) {
        lock.withLock {
            if (closed) return
            if (single) this.single = true
            if (stream) streamUntil = now() + streamMs
            if (thread == null) thread = Thread(::loop, threadName).apply { isDaemon = true; start() }
            wake.signalAll()
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            wake.signalAll()
        }
    }

    private fun now() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    private fun loop() {
        var nextAt = 0L
        while (true) {
            lock.withLock {
                while (true) {
                    if (closed) return
                    val now = now()
                    val streaming = now < streamUntil
                    if (single || (streaming && now >= nextAt)) break
                    if (streaming) wake.await(nextAt - now, TimeUnit.MILLISECONDS) else wake.await()
                }
                single = false
            }
            val start = now()
            val cams = cameras()
            for (cam in cams) {
                for (payload in cam.responses(cam.capture())) {
                    if (!send(Frame(Msg.CAMERA_IMAGE_RESPONSE, payload))) { close(); return }
                }
            }
            nextAt = start + (cams.maxOfOrNull { it.frameIntervalMs } ?: streamMs)
        }
    }
}
