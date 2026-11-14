/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The device's camera, shared by everything the camera pane runs: presence frames ([open], a
 * [FrameSource]), stills for Home Assistant ([jpeg], a [StillCamera]) and the stream's encoder
 * ([setEncoder]). One Camera2 device, opened while anyone needs it and closed when nobody does. The
 * front camera when there is one: a panel faces the room.
 *
 * Everything happens on the hub's own thread, so the camera's state has one writer. Frames reach
 * presence at about the rate it asked for; the rest are dropped without a copy.
 */
class CameraHub(
    context: Context,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) : FrameSource, StillCamera, EncoderInput {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("glassworks-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private class Sink(val fps: Float, val sink: FrameSink) { var lastMs = 0L }
    private class Waiter(val latch: CountDownLatch) { @Volatile var jpeg: ByteArray? = null }

    // Owned by the hub's thread.
    private val sinks = ArrayList<Sink>()
    private val waiters = ArrayList<Waiter>()
    private var encoder: Surface? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var opening = false
    /** Set by [release]: a camera that opens after it is closed at once. */
    private var released = false
    /** Bumped whenever a session is dropped, so a late callback from an older one is ignored. */
    private var generation = 0
    private var configuring = false
    private var lingerUntil = 0L
    /** Bumped by [idle]: a still asked for before it doesn't keep the camera open after it. */
    private var stillEpoch = 0
    private var luma = ByteArray(0)
    private var facingFront = true
    private var sensorDegrees = 0
    private var fpsRange: android.util.Range<Int>? = null

    private val wanted get() = sinks.isNotEmpty() || waiters.isNotEmpty() || encoder != null ||
        SystemClock.elapsedRealtime() < lingerUntil

    override fun open(fps: Float, sink: FrameSink): AutoCloseable {
        val s = Sink(fps, sink)
        handler.post { sinks += s; settle() }
        return AutoCloseable { handler.post { sinks -= s; settle() } }
    }

    /**
     * The next frame as a JPEG, upright, or null after [STILL_WAIT_MS]. Blocks the caller (a camera
     * entity's thread), never the hub's. The camera stays open [LINGER_MS] after, so a card that asks
     * every few seconds doesn't reopen it each time.
     */
    override fun jpeg(): ByteArray? {
        val w = Waiter(CountDownLatch(1))
        var epoch = 0
        handler.post { epoch = stillEpoch; waiters += w; settle() }
        w.latch.await(STILL_WAIT_MS, TimeUnit.MILLISECONDS)
        handler.post {
            waiters -= w
            if (epoch == stillEpoch) lingerUntil = SystemClock.elapsedRealtime() + LINGER_MS
            handler.postDelayed(::settle, LINGER_MS + 50)
        }
        return w.jpeg
    }

    /**
     * The stream encoder whose input the camera draws into, or null to take it out. The capture session
     * is rebuilt either way. Taking it out returns once the session no longer draws into it, so the
     * caller can release the encoder's surface (at most [DETACH_WAIT_MS]). Never call from the hub's thread.
     */
    override fun setEncoder(encoder: StreamEncoder?) {
        val surface = encoder?.surface
        val done = CountDownLatch(1)
        handler.post {
            if (this.encoder !== surface) {
                this.encoder = surface
                closeSession()
                settle()
            }
            done.countDown()
        }
        if (surface == null) done.await(DETACH_WAIT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Nothing may use the camera now (privacy, the pane stopping): drops the still linger and any waiting
     * still, so the camera closes at once instead of [LINGER_MS] later. Presence closes its own frames.
     */
    fun idle() {
        handler.post {
            stillEpoch++
            lingerUntil = 0L
            waiters.forEach { it.latch.countDown() }; waiters.clear()
            settle()
        }
    }

    /** Closes the camera and ends the hub's thread. Nothing may use the hub after. */
    fun release() {
        handler.post {
            released = true
            sinks.clear(); waiters.forEach { it.latch.countDown() }; waiters.clear(); encoder = null
            closeCamera()
            // An open in flight still calls back: quit only once it has, so it can close that camera.
            if (!opening) thread.quitSafely()
        }
    }

    // ------------------------------------------------------------------ the hub's thread

    private fun settle() {
        if (released) return
        if (!wanted) { closeCamera(); return }
        if (device == null) { if (!opening) openCamera(); return }
        if (session == null && !configuring) startSession()
    }

    @SuppressLint("MissingPermission") // the pane opens the hub only with the permission; a refusal is caught
    private fun openCamera() {
        val id = try { pickCamera() } catch (e: Exception) { fail("no camera: ${e.javaClass.simpleName}"); return }
            ?: run { fail("this device has no camera"); return }
        opening = true
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    opening = false
                    if (released) { camera.close(); thread.quitSafely(); return }
                    device = camera
                    log("camera $id open (${if (facingFront) "front" else "back"}, ${width}x$height)")
                    settle()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    opening = false
                    camera.close(); forget(camera)
                    if (released) { thread.quitSafely(); return }
                    fail("another app took the camera")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    opening = false
                    camera.close(); forget(camera)
                    if (released) { thread.quitSafely(); return }
                    fail("camera error $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            opening = false
            fail("Android hasn't allowed the camera")
        } catch (e: CameraAccessException) {
            opening = false
            fail("camera unavailable (${e.reason})")
        }
    }

    private fun pickCamera(): String? {
        val ids = manager.cameraIdList
        val front = ids.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
        val id = front ?: ids.firstOrNull() ?: return null
        val c = manager.getCameraCharacteristics(id)
        facingFront = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        sensorDegrees = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        fpsRange = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == H264Encoder.FPS }?.maxByOrNull { it.lower }
        return id
    }

    @Suppress("DEPRECATION") // createCaptureSession(List): SessionConfiguration needs API 28, the S1 app's floor is 26
    private fun startSession() {
        val camera = device ?: return
        val r = reader ?: ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3).also { r ->
            r.setOnImageAvailableListener({ onImage(it) }, handler)
            reader = r
        }
        val targets = listOfNotNull(r.surface, encoder)
        val gen = ++generation
        configuring = true
        try {
            camera.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (gen != generation || device !== camera) { s.close(); return }
                    configuring = false
                    session = s
                    try {
                        val template = if (encoder != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
                        val req = camera.createCaptureRequest(template).apply {
                            targets.forEach(::addTarget)
                            // While streaming, the encoder's rate when the camera offers it (less CPU, a true a=framerate).
                            if (encoder != null) fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        }
                        s.setRepeatingRequest(req.build(), null, handler)
                    } catch (e: Exception) {
                        fail("camera session failed: ${e.javaClass.simpleName}")
                    }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (gen != generation) return
                    configuring = false
                    fail("the camera refused the session (${targets.size} outputs)")
                }
            }, handler)
        } catch (e: Exception) {
            configuring = false
            fail("camera session failed: ${e.javaClass.simpleName}")
        }
    }

    private fun onImage(r: ImageReader) {
        // The reader can be closed between the callback being posted and now: then there is no image.
        val image = try { r.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            val due = sinks.filter { now - it.lastMs >= (1000f / it.fps).toLong() }
            if (due.isNotEmpty()) {
                val frame = try { lumaOf(image) } catch (e: IllegalStateException) { null }
                if (frame != null) for (s in due) { s.lastMs = now; s.sink.frame(frame) }
            }
            if (waiters.isNotEmpty()) {
                val jpeg = try { jpegOf(image) } catch (e: Exception) { log("still failed: ${e.javaClass.simpleName}"); null }
                waiters.forEach { it.jpeg = jpeg; it.latch.countDown() }
                waiters.clear()
            }
        } finally {
            image.close()
        }
    }

    /** The Y plane, copied once into a buffer the hub reuses: presence reads it before [FrameSink.frame] returns. */
    private fun lumaOf(image: Image): LumaFrame {
        val plane = image.planes[0]
        val buf = plane.buffer
        val size = plane.rowStride * (image.height - 1) + image.width
        if (luma.size < size) luma = ByteArray(size)
        buf.position(0)
        buf.get(luma, 0, minOf(size, buf.remaining()))
        return LumaFrame(image.width, image.height, plane.rowStride, luma)
    }

    private fun jpegOf(image: Image): ByteArray {
        val (y, u, v) = image.planes
        val nv21 = yuvToNv21(image.width, image.height, y.buffer, y.rowStride, u.buffer, v.buffer, u.rowStride, u.pixelStride)
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null).compressToJpeg(Rect(0, 0, image.width, image.height), JPEG_QUALITY, out)
        val turn = stillRotation(sensorDegrees, displayDegrees(), facingFront)
        if (turn == 0) return out.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
        return ByteArrayOutputStream().also { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it); bmp.recycle(); rotated.recycle() }.toByteArray()
    }

    private fun displayDegrees(): Int {
        val d = (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        return when (d?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    }

    /** Tells presence the camera is gone, answers waiting stills, and tries again later while it is still wanted. */
    private fun fail(why: String) {
        log("camera: $why")
        closeCamera()
        sinks.forEach { it.sink.lost(why) }
        waiters.forEach { it.latch.countDown() }
        waiters.clear()
        handler.postDelayed(::settle, RETRY_MS)
    }

    private fun forget(camera: CameraDevice) {
        if (device === camera) { device = null; session = null }
    }

    private fun closeSession() {
        generation++
        configuring = false
        session?.let { s ->
            // Stop drawing now, not when close() finishes in the background: the encoder's surface may go next.
            try { s.stopRepeating(); s.abortCaptures() } catch (_: Exception) {}
            try { s.close() } catch (_: Exception) {}
        }
        session = null
    }

    private fun closeCamera() {
        closeSession()
        try { device?.close() } catch (_: Exception) {}
        if (device != null) log("camera closed")
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
    }

    companion object {
        private const val TAG = "S1Kiosk.Camera"

        /** Enough for a still people recognise and an H.264 stream; presence reads a sparse 48x27 grid of it. */
        const val WIDTH = 1280
        const val HEIGHT = 720
        const val JPEG_QUALITY = 85
        const val STILL_WAIT_MS = 2_000L
        const val LINGER_MS = 10_000L
        const val RETRY_MS = 5_000L
        const val DETACH_WAIT_MS = 1_000L
    }
}
