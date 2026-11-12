package sh.edm.s1kiosk.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.auth.HaThreadClientTest.FakeHa
import java.util.concurrent.Executor

class ThreadJoinTest {

    /** Records /auth/revoke calls; nothing else is expected. */
    private class FakeHttp : HttpPoster {
        val revoked = ArrayList<String>()
        var reachable = true
        override fun postForm(url: String, body: String): Pair<Int, String> {
            assertTrue(url, url.endsWith("/auth/revoke"))
            if (!reachable) return -1 to ""
            revoked += OAuth.parseQuery(body)["token"]!!
            return 200 to ""
        }
        override fun get(url: String, maxBytes: Int): Pair<Int, String> = error("unexpected GET $url")
    }

    private val direct = Executor { it.run() }
    private var now = 1_000L
    private val http = FakeHttp()
    private val delivered = ArrayList<ThreadFetch.Ok>()

    private fun join(
        ha: () -> FakeHa,
        panelToken: String? = null,
        haBase: String? = "http://ha:8123",
        deliverError: String? = null,
        exec: Executor = direct,
    ) = ThreadJoin(
        client = HaThreadClient { ha() },
        tokens = HaTokenClient(http),
        haBase = { haBase },
        panelToken = { done -> done(panelToken) },
        exec = exec,
        armMs = 600_000L,
        clock = { now },
    ).apply { deliver = { r -> delivered += r; deliverError } }

    @Test
    fun adminPanelJoinsWithoutAQrCode() {
        val j = join({ FakeHa(token = "PANEL") }, panelToken = "PANEL")
        j.start()
        assertTrue(j.state.toString(), j.state is ThreadJoin.State.Joined)
        assertEquals("ha-thread-1234", (j.state as ThreadJoin.State.Joined).networkName)
        assertEquals("0e080000000000010000000300000f", delivered.single().tlv)
        assertTrue("the panel's own sign-in is not revoked", http.revoked.isEmpty())
        assertFalse(j.armed())
    }

    @Test
    fun nonAdminPanelShowsTheQrCode() {
        val j = join({ FakeHa(token = "PANEL", admin = false) }, panelToken = "PANEL")
        j.start()
        assertTrue(j.state is ThreadJoin.State.NeedsAdmin)
        assertTrue(j.armed())
        assertTrue(delivered.isEmpty())
        now += 600_000L
        assertFalse("the QR route expires", j.armed())
    }

    @Test
    fun notSignedInShowsTheQrCode() {
        val j = join({ error("no fetch without a token") }, panelToken = null)
        j.start()
        assertTrue(j.armed())
        assertTrue((j.state as ThreadJoin.State.NeedsAdmin).why.contains("isn't signed in"))
    }

    @Test
    fun adminGrantIsUsedOnceAndRevoked() {
        val j = join({ FakeHa(token = "ADMIN") })
        j.arm()
        val o = j.onGrant(StoredAuth("http://ha:8123", "http://panel:8099/", "ADMIN-RT"), TokenResult.Ok("ADMIN", 1800, "ADMIN-RT"))
        assertTrue(o.ok)
        assertTrue(o.title.contains("ha-thread-1234"))
        assertEquals(listOf("ADMIN-RT"), http.revoked)
        assertEquals(1, delivered.size)
        assertFalse(j.armed())
        assertFalse("the page never shows the dataset", o.text.contains(delivered.single().tlv))
    }

    @Test
    fun grantIsRevokedEvenWhenItFails() {
        val j = join({ FakeHa(token = "ADMIN", admin = false) })
        j.arm()
        val o = j.onGrant(StoredAuth("http://ha:8123", "http://panel:8099/", "USER-RT"), TokenResult.Ok("ADMIN", 1800, "USER-RT"))
        assertFalse(o.ok)
        assertTrue(o.title.contains("administrator"))
        assertEquals(listOf("USER-RT"), http.revoked)
        assertTrue("still waiting for an administrator", j.armed())

        val crash = join({ throw IllegalStateException("boom") })
        assertFalse(crash.onGrant(StoredAuth("http://ha:8123", "c", "RT2"), TokenResult.Ok("AT", 1800, "RT2")).ok)
        assertEquals(listOf("USER-RT", "RT2"), http.revoked)
        assertTrue(crash.state is ThreadJoin.State.Failed)
    }

    @Test
    fun deliveryAndHaProblemsAreReported() {
        val refused = join({ FakeHa(token = "PANEL") }, panelToken = "PANEL", deliverError = "Thread router needs a newer root helper")
        refused.start()
        assertTrue((refused.state as ThreadJoin.State.Failed).reason.contains("root helper"))

        val noNet = join({ FakeHa(token = "PANEL", datasets = "[]") }, panelToken = "PANEL")
        noNet.start()
        assertTrue((noNet.state as ThreadJoin.State.Failed).reason.contains("no Thread network"))

        val noHa = join({ FakeHa() }, haBase = null)
        noHa.start()
        assertTrue(noHa.state is ThreadJoin.State.Failed)
    }

    @Test
    fun aRevokeThatFailsIsReported() {
        http.reachable = false
        val j = join({ FakeHa(token = "ADMIN") })
        j.arm()
        val o = j.onGrant(StoredAuth("http://ha:8123", "c", "ADMIN-RT"), TokenResult.Ok("ADMIN", 1800, "ADMIN-RT"))
        assertTrue(o.ok)
        assertTrue(o.text, o.text.contains("Refresh tokens"))
        assertFalse((j.state as ThreadJoin.State.Joined).revoked)
    }

    @Test
    fun anOlderAttemptsResultIsDropped() {
        val queued = ArrayList<Runnable>()
        val admin = join({ FakeHa(token = "ADMIN") }, panelToken = "PANEL", exec = { queued += it })
        admin.start() // the panel's own (non-admin) fetch is still out
        admin.onGrant(StoredAuth("http://ha:8123", "c", "RT2"), TokenResult.Ok("ADMIN", 1800, "RT2"))
        assertTrue(admin.state is ThreadJoin.State.Joined)
        queued.forEach { it.run() } // the panel's fetches answer late
        assertTrue("an admin's join isn't undone by the panel's late answer", admin.state is ThreadJoin.State.Joined)
    }

    @Test
    fun disarm() {
        val j = join({ FakeHa() })
        j.arm()
        j.disarm()
        assertEquals(ThreadJoin.State.Idle, j.state)
        assertFalse(j.armed())
    }

    @Test
    fun urlAndDescriptions() {
        assertEquals("http://10.0.0.5:8099/thread", ThreadJoin.url("10.0.0.5"))
        assertTrue(ThreadJoin.describe(ThreadJoin.State.NeedsAdmin(2_000, "x"), now = 1_000).contains("Scan the QR code"))
        assertTrue(ThreadJoin.describe(ThreadJoin.State.NeedsAdmin(2_000, "x"), now = 3_000).contains("expired"))
        assertTrue(ThreadJoin.describe(ThreadJoin.State.Joined("net")).contains("net"))
    }
}
