/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CameraStreamTest {

    private val app = RuntimeEnvironment.getApplication()
    private val wifi = shadowOf(app.getSystemService(Context.WIFI_SERVICE) as WifiManager)

    private fun freePort() = ServerSocket(0).use { it.localPort }

    /** A just-closed port can take a moment to bind again. */
    private fun bindsWithin(port: Int, deadlineMs: Long = 5_000): Boolean {
        val end = System.currentTimeMillis() + deadlineMs
        while (System.currentTimeMillis() < end) {
            val ok = runCatching { ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) } }.isSuccess
            if (ok) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test fun aThrowingEncoderReleaseStillClosesThePortsAndTheMulticastLock() {
        val rtsp = freePort()
        val onvif = generateSequence { freePort() }.first { it != rtsp }
        // The encoder is made before the viewer's hold on the indicator is taken: wait for the hold.
        val held = CountDownLatch(1)
        val encoder = object : StreamEncoder {
            override val surface: Surface? = null
            override fun requestKeyFrame() {}
            override fun release() = throw IllegalStateException("codec")
        }
        val egress = FrameEgress({ 0L }, { _, _ -> }) { leaving -> if (leaving) held.countDown() }
        val stream = CameraStream(
            app, hub = {}, egress = egress,
            identity = { CameraIdentity("Test panel", "1.0", "02:00:00:00:00:01") },
            host = { "127.0.0.1" }, snapshot = { null }, log = {},
            encoders = { _, _ -> encoder },
            rtspPort = rtsp, onvifPort = onvif, discovery = false,
        )
        assertTrue(stream.start("viewer" to "made-up-pass"))
        assertEquals("the multicast lock is held while the stream runs", 1, wifi.activeLockCount)

        // A viewer: DESCRIBE waits for the first keyframe, and counts, so the encoder starts.
        Socket("127.0.0.1", rtsp).use { viewer ->
            val auth = Base64.getEncoder().encodeToString("viewer:made-up-pass".toByteArray())
            viewer.getOutputStream().write("DESCRIBE rtsp://127.0.0.1:$rtsp/live RTSP/1.0\r\nCSeq: 1\r\nAuthorization: Basic $auth\r\n\r\n".toByteArray())
            assertTrue("the encoder started and holds the indicator on", held.await(5, TimeUnit.SECONDS))
            assertTrue(egress.leaving)

            stream.stop()

            assertFalse("the indicator goes off", egress.leaving)
            assertTrue("RTSP's port closed", bindsWithin(rtsp))
            assertTrue("ONVIF's port closed", bindsWithin(onvif))
            assertEquals("the multicast lock is let go", 0, wifi.activeLockCount)
        }
    }
}
