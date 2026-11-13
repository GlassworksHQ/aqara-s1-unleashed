/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.web

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The server takes only so many connections at once; the rest get 503 straight away instead of piling up. */
class HttpServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")

    private fun open(port: Int): Socket = Socket(loopback, port).apply {
        soTimeout = 3_000
        getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
    }

    @Test fun aFullServerSaysBusy() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val server = HttpServer(0, { started.countDown(); release.await(5, TimeUnit.SECONDS); HttpResponse.text(200, "ok") },
            bindAddress = loopback, workers = 1, queued = 1)
        server.start()
        try {
            val busy = open(server.localPort)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val waiting = open(server.localPort)
            Thread.sleep(200)
            val third = open(server.localPort)
            val answer = third.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(answer, answer.startsWith("HTTP/1.1 503"))
            release.countDown()
            assertTrue(busy.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            assertTrue(waiting.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
            listOf(busy, waiting, third).forEach { it.close() }
        } finally {
            release.countDown()
            server.stop()
        }
    }
}
