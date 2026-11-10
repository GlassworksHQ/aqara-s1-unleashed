package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** The first check after an offline start must not wait 15 minutes once Wi-Fi is back. */
class NetworkRetryTest {
    private val m = CheckSchedule.MINUTE

    @Test fun anOfflineFailureIsRetriedWhenTheNetworkComesBack() {
        val s = CheckSchedule()
        s.onFailure(30_000, network = true) // UnknownHostException at the first check
        assertFalse(s.due(60_000))
        assertTrue(s.onNetworkBack(60_000))
        assertTrue("due right away", s.due(60_000))
        // fails again (DNS not ready yet): the next network event may retry, but not within 30 s
        s.onFailure(61_000, network = true)
        assertFalse(s.onNetworkBack(70_000))
        assertFalse(s.due(70_000))
        assertTrue(s.onNetworkBack(95_000))
        s.onSuccess(96_000)
        assertFalse("nothing to retry after a success", s.onNetworkBack(200_000))
        assertEquals(96_000 + 24 * 60 * m, s.nextAt)
    }

    @Test fun realFailuresKeepTheirBackoff() {
        val s = CheckSchedule()
        s.onFailure(0, network = false) // e.g. HTTP 502 from GitHub
        assertFalse(s.onNetworkBack(60_000))
        assertEquals(15 * m, s.nextAt)
        s.onFailure(0, retryAfterMs = 60 * m, network = true) // a rate limit is never cut short
        assertFalse(s.onNetworkBack(120_000))
        assertEquals(60 * m, s.nextAt)
    }

    @Test fun networkBackWithoutAFailureChangesNothing() {
        val s = CheckSchedule()
        s.onSuccess(0)
        val next = s.nextAt
        assertFalse(s.onNetworkBack(1_000))
        assertEquals(next, s.nextAt)
    }

    @Test fun whatCountsAsNoNetwork() {
        assertTrue(GithubHttp.isNetworkError(UnknownHostException("api.github.com")))
        assertTrue(GithubHttp.isNetworkError(ConnectException("Network is unreachable")))
        assertTrue(GithubHttp.isNetworkError(SocketTimeoutException()))
        assertTrue(GithubHttp.isNetworkError(SocketException("Software caused connection abort")))
        assertFalse(GithubHttp.isNetworkError(SSLHandshakeException("bad cert")))
        assertFalse(GithubHttp.isNetworkError(IOException("HTTP 502 for latest")))
    }
}
