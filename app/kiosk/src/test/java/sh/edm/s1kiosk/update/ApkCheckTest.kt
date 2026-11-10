package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pre-install checks of a downloaded APK, the root service's status contract, and check scheduling. */
class ApkCheckTest {

    private val releaseKey = "release cert DER".toByteArray()
    private val debugKey = "debug cert DER".toByteArray()
    private val installed = ApkFacts("sh.edm.s1kiosk", "1.2.0", 10200, listOf(releaseKey))
    private val v130 = AppVersion.parse("1.3.0")!!
    private fun archive(
        pkg: String = "sh.edm.s1kiosk", name: String? = "1.3.0", code: Long = 10300, signers: List<ByteArray> = listOf(releaseKey),
    ) = ApkFacts(pkg, name, code, signers)

    @Test fun acceptsTheRelease() {
        assertNull(ApkCheck.refusal(archive(), installed, v130))
        // Same certificate bytes in a fresh array, several signers in another order.
        val two = listOf(releaseKey.copyOf(), "second".toByteArray())
        assertNull(ApkCheck.refusal(archive(signers = two.reversed()), ApkFacts("sh.edm.s1kiosk", "1.2.0", 10200, two), v130))
    }

    @Test fun refusesWithAClearReason() {
        assertEquals("the download is not a valid APK", ApkCheck.refusal(null, installed, v130))
        assertTrue(ApkCheck.refusal(archive(pkg = "com.evil"), installed, v130)!!.contains("is for com.evil"))
        assertTrue(ApkCheck.refusal(archive(signers = emptyList()), installed, v130)!!.contains("not signed"))
        val wrongKey = ApkCheck.refusal(archive(signers = listOf(debugKey)), installed, v130)!!
        assertTrue(wrongKey, wrongKey.contains("different key"))
        // Release APK on a panel running a debug build: the same refusal.
        val onDebug = ApkFacts("sh.edm.s1kiosk", "0.1.0", 1, listOf(debugKey))
        assertTrue(ApkCheck.refusal(archive(), onDebug, v130)!!.contains("different key"))
        assertTrue(ApkCheck.refusal(archive(), installed.copyNoSigners(), v130)!!.contains("this app's signing certificate"))
        assertTrue(ApkCheck.refusal(archive(name = "1.3.1"), installed, v130)!!.contains("does not match release 1.3.0"))
        assertTrue(ApkCheck.refusal(archive(code = 10301), installed, v130)!!.contains("versionCode 10301"))
        val older = ApkCheck.refusal(archive(name = "1.1.0", code = 10100), installed, AppVersion.parse("1.1.0")!!)!!
        assertTrue(older, older.contains("not newer"))
    }

    private fun ApkFacts.copyNoSigners() = ApkFacts(packageName, versionName, versionCode, emptyList())

    @Test fun rootServiceStatus() {
        assertNull(UpdateHelper.parseStatus(null))
        assertNull(UpdateHelper.parseStatus(""))
        assertNull(UpdateHelper.parseStatus("garbage: x"))
        assertEquals(UpdateHelper.Status("requested", ""), UpdateHelper.parseStatus("requested"))
        assertEquals(UpdateHelper.Status("running", "installing"), UpdateHelper.parseStatus("running: installing"))
        assertEquals(UpdateHelper.Status("ok", "sh.edm.s1kiosk 10200 -> 10300"),
            UpdateHelper.parseStatus("ok: sh.edm.s1kiosk 10200 -> 10300"))
        assertEquals(UpdateHelper.Status("error", "pending.apk is not owned by the kiosk (uid 0)"),
            UpdateHelper.parseStatus(" error: pending.apk is not owned by the kiosk (uid 0) "))
        assertEquals("/data/data/sh.edm.s1kiosk/files/update/pending.apk", UpdateHelper.PENDING_PATH)
    }

    @Test fun checkScheduleBacksOff() {
        val h = CheckSchedule.HOUR
        val m = CheckSchedule.MINUTE
        val s = CheckSchedule()
        assertTrue("first check right away", s.due(0))
        s.onFailure(0)
        assertEquals(15 * m, s.nextAt)
        s.onFailure(s.nextAt)
        assertEquals(15 * m + 30 * m, s.nextAt)
        repeat(10) { s.onFailure(0) }
        assertEquals("capped", 6 * h, s.nextAt)
        s.onFailure(0, retryAfterMs = 9 * h)
        assertEquals("rate-limit reset wins", 9 * h, s.nextAt)
        s.onFailure(0, retryAfterMs = 48 * h)
        assertEquals("never past the regular interval", 24 * h, s.nextAt)
        s.onSuccess(100)
        assertEquals(0, s.failures)
        assertEquals(100 + 24 * h, s.nextAt)

        assertTrue(s.tryManual(1_000))
        assertTrue(!s.tryManual(1_000 + 59_999))
        assertTrue(s.tryManual(1_000 + 60_000))
    }

    @Test fun githubRateLimits() {
        val now = 1_700_000_000_000L
        fun h(vararg kv: Pair<String, String>): (String) -> String? = { k -> kv.toMap()[k] }
        assertNull(GithubHttp.rateLimitWaitMs(200, h(), now))
        assertNull(GithubHttp.rateLimitWaitMs(404, h("x-ratelimit-remaining" to "0"), now))
        assertNull("403 for another reason", GithubHttp.rateLimitWaitMs(403, h("x-ratelimit-remaining" to "12"), now))
        assertEquals(10 * 60_000L + 60_000L,
            GithubHttp.rateLimitWaitMs(403, h("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now / 1000 + 600}"), now))
        assertEquals(120_000L, GithubHttp.rateLimitWaitMs(429, h("retry-after" to "120"), now))
        assertEquals(CheckSchedule.HOUR, GithubHttp.rateLimitWaitMs(429, h(), now))
        assertEquals(CheckSchedule.HOUR, GithubHttp.rateLimitWaitMs(403, h("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "soon"), now))
    }
}
