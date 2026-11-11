package sh.edm.improv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPeerTest {
    @Test
    fun onlyAdbShellOrRoot() {
        assertTrue(AdbPeer.allowed(0))
        assertTrue(AdbPeer.allowed(2000))
        for (uid in listOf(-1, 1000, 1010, 1999, 2001, 10000, 10123)) assertFalse("uid $uid", AdbPeer.allowed(uid))
    }
}
