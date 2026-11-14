/* SPDX-License-Identifier: MIT
 *
 * Glassworks test for the ported OnvifDiscovery (aguilaair/android-ip-camera, 7b5e6f28). The licence
 * text is in THIRD-PARTY-NOTICES.md and LICENSES/MIT.txt. */

package dev.glassworks.camera.onvif

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.MulticastSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OnvifDiscoveryTest {

    @Test fun aFailedGroupJoinClosesItsSocket() {
        val made = CopyOnWriteArrayList<MulticastSocket>()
        val tried = CountDownLatch(1)
        val d = OnvifDiscovery(
            endpointUuid = { "urn:uuid:00000000-0000-0000-0000-000000000000" },
            xaddr = { "http://192.0.2.41:8000/onvif/device_service" },
            scopes = { emptyList() },
            newSocket = { MulticastSocket(0).also { made += it } },
            join = { _, _ -> tried.countDown(); throw SocketException("no multicast here") },
        )
        d.start()
        assertTrue(tried.await(5, TimeUnit.SECONDS))
        Thread.sleep(200) // past the failed attempt, into the retry wait
        d.stop()
        assertTrue(made.isNotEmpty())
        assertTrue("every socket from a failed join is closed", made.all { it.isClosed })
    }
}
