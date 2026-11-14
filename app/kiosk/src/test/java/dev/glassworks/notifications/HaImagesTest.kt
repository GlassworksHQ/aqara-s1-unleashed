/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class HaImagesTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    /** A stand-in Home Assistant on 127.0.0.1, and a second server as "another host". */
    private val seen = CopyOnWriteArrayList<Pair<String, String?>>()
    private val other = server("other")
    private val ha = server("ha")

    private fun server(name: String): HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { x ->
            seen += "$name ${x.requestURI}" to x.requestHeaders.getFirst("Authorization")
            when (x.requestURI.path) {
                "/api/camera_proxy/camera.porch" -> {
                    x.responseHeaders.add("Content-Type", "image/jpeg")
                    x.sendResponseHeaders(200, jpeg.size.toLong()); x.responseBody.write(jpeg)
                }
                "/redirect" -> {
                    x.responseHeaders.add("Location", "http://127.0.0.1:${other.address.port}/stolen")
                    x.sendResponseHeaders(302, -1)
                }
                "/page" -> {
                    val b = "<html></html>".toByteArray()
                    x.responseHeaders.add("Content-Type", "text/html")
                    x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.write(b)
                }
                "/logo.svg" -> {
                    val b = "<svg xmlns='http://www.w3.org/2000/svg'/>".toByteArray()
                    x.responseHeaders.add("Content-Type", "image/svg+xml")
                    x.sendResponseHeaders(200, b.size.toLong()); x.responseBody.write(b)
                }
                "/big.jpg" -> {
                    x.responseHeaders.add("Content-Type", "image/jpeg")
                    x.sendResponseHeaders(200, 0); x.responseBody.write(ByteArray(64 * 1024))
                }
                else -> x.sendResponseHeaders(401, -1)
            }
            x.close()
        }
        start()
    }

    @After fun stop() { ha.stop(0); other.stop(0) }

    private val base get() = "http://127.0.0.1:${ha.address.port}"
    private fun images(token: String? = "t0k3n", max: Int = HaImages.MAX_BYTES) = HaImages({ base }, { token }, UrlConnectionGet(), max)

    @Test
    fun aCameraSnapshotWithTheToken() {
        val r = images().fetch("$base/api/camera_proxy/camera.porch") as ImageFetch.Ok
        assertArrayEquals(jpeg, r.bytes)
        assertEquals("image/jpeg", r.contentType)
        assertEquals("Bearer t0k3n", seen.single().second)
    }

    @Test
    fun anotherHostIsNeverAskedAndGetsNoToken() {
        val r = images().fetch("http://127.0.0.1:${other.address.port}/api/camera_proxy/camera.porch")
        assertTrue(r is ImageFetch.Failed)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun aRedirectIsNotFollowed() {
        val r = images().fetch("$base/redirect") as ImageFetch.Failed
        assertTrue(r.reason, r.reason.contains("redirect"))
        assertEquals(listOf("ha /redirect"), seen.map { it.first })
    }

    @Test
    fun onlyPicturesAndNotTooLarge() {
        assertTrue((images().fetch("$base/page") as ImageFetch.Failed).reason.contains("not a picture"))
        assertTrue("no SVG", (images().fetch("$base/logo.svg") as ImageFetch.Failed).reason.contains("image/svg+xml"))
        assertTrue((images(max = 1024).fetch("$base/big.jpg") as ImageFetch.Failed).reason.contains("larger"))
    }

    @Test
    fun signInProblems() {
        assertTrue((images(token = null).fetch("$base/api/camera_proxy/camera.porch") as ImageFetch.Failed).reason.contains("signed in"))
        assertTrue(seen.isEmpty())
        assertTrue((images().fetch("$base/api/camera_proxy/camera.gone") as ImageFetch.Failed).reason.contains("refused"))
    }

    @Test
    fun theTokenIsNeverInAReason() {
        val reasons = listOf("$base/redirect", "$base/page", "$base/nope", "http://192.0.2.1/x")
            .map { (images(token = "s3cret").fetch(it) as ImageFetch.Failed).reason }
        assertTrue(reasons.none { it.contains("s3cret") })
    }
}
