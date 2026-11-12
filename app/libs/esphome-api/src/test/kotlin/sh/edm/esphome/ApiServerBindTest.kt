package sh.edm.esphome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * #113: other processes on the machine listen on 127.0.0.1 ports too (Gradle test servers, dev
 * servers). On macOS a wildcard listener on port 0 can get one of their ports, and a connection to
 * 127.0.0.1 then reaches them: the server seems to listen before it starts and after it stops.
 * Holding the next ports on 127.0.0.1 makes that happen there. Linux refuses such a bind, so on CI
 * this test passes with or without [ApiServer]'s bindAddress.
 */
class ApiServerBindTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val identity = DeviceIdentity("t", "T", "02:00:00:00:00:01", "2026.10.2", "", "Me", "Test", 0)

    @Test
    fun aLoopbackListenerOnTheNextPortsDoesNotAnswerForTheServer() {
        val next = ServerSocket(0, 0, loopback).use { it.localPort }.coerceAtMost(65535 - 21)
        val squatters = (next + 1..next + 20).mapNotNull { p ->
            try { ServerSocket(p, 50, loopback) } catch (_: IOException) { null }
        }
        assertTrue("held ${squatters.size} of 20 loopback ports", squatters.size >= 15)
        val server = ApiServer(0, { identity }, emptyList(), keys = null, bindAddress = loopback)
        try {
            server.start()
            val port = server.localPort
            Socket().use { s ->
                s.connect(InetSocketAddress(loopback, port), 1_000)
                s.soTimeout = 2_000
                s.getOutputStream().write(byteArrayOf(0, 4, Msg.HELLO_REQUEST.toByte(), 0x0a, 2, 't'.code.toByte(), 't'.code.toByte()))
                assertEquals("the API server on port $port answers Hello with a plaintext frame", 0, s.getInputStream().read())
            }
            server.stop()
            assertTrue("nothing listens on 127.0.0.1:$port after stop()", stopsListening(port))
        } finally {
            server.stop()
            squatters.forEach { it.close() }
        }
    }

    private fun stopsListening(port: Int): Boolean {
        val end = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress(loopback, port), 500) }
            } catch (_: IOException) {
                return true
            }
            Thread.sleep(25)
        }
        return false
    }
}
