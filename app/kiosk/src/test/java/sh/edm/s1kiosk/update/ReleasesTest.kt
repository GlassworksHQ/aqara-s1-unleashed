package sh.edm.s1kiosk.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

/** Release JSON parsing and asset selection, against a fixture shaped like GitHub's real response. */
class ReleasesTest {

    private fun resource(name: String) = javaClass.classLoader!!.getResource("update/$name")!!.readText()
    private val latest = resource("release_latest.json")
    private fun edited(edit: JSONObject.() -> Unit) = JSONObject(latest).apply(edit).toString()
    private fun refusal(json: String): String = try {
        Releases.offer(Releases.parse(json))
        fail("expected a refusal"); ""
    } catch (e: ReleaseFormatException) {
        e.message!!
    }

    @Test fun parsesGithubRelease() {
        val r = Releases.parse(latest)
        assertEquals("v1.3.0", r.tag)
        assertEquals("S1 Kiosk 1.3.0", r.name)
        assertFalse(r.draft)
        assertFalse(r.prerelease)
        assertEquals(listOf("s1-kiosk-1.3.0.apk", "s1-kiosk-1.3.0.apk.sha256"), r.assets.map { it.name })
        assertEquals(14L, r.assets[0].size)
        assertNotNull(r.assets[0].digest)
    }

    @Test fun selectsTheTwoAssets() {
        val o = Releases.offer(Releases.parse(latest))
        assertEquals(AppVersion(1, 3, 0), o.version)
        assertEquals("s1-kiosk-1.3.0.apk", o.apk.name)
        assertEquals("https://github.com/aguilaair/aqara-s1-unleashed/releases/download/v1.3.0/s1-kiosk-1.3.0.apk", o.apk.url)
        assertEquals("s1-kiosk-1.3.0.apk.sha256", o.sha256.name)
        assertEquals("https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v1.3.0", o.releaseUrl)
        assertTrue(o.notes.startsWith("## What's Changed"))
        // The fixture's .sha256 file and GitHub's own digest agree, as the manager requires.
        assertArrayEquals(o.apk.digest, Releases.parseSha256(resource("s1-kiosk-1.3.0.apk.sha256"), o.apk.name))
    }

    @Test fun extraAssetsAreIgnored() {
        val json = edited {
            getJSONArray("assets").put(JSONObject().put("name", "s1-kiosk-1.3.0-debug.apk").put("size", 9)
                .put("browser_download_url", "https://github.com/aguilaair/aqara-s1-unleashed/releases/download/v1.3.0/s1-kiosk-1.3.0-debug.apk"))
        }
        assertEquals("s1-kiosk-1.3.0.apk", Releases.offer(Releases.parse(json)).apk.name)
    }

    @Test fun refusesDraftsAndPrereleases() {
        assertTrue(refusal(edited { put("draft", true) }).contains("draft"))
        assertTrue(refusal(edited { put("prerelease", true) }).contains("pre-release"))
        assertTrue(refusal(edited { put("tag_name", "v1.3.0-rc.1") }).contains("pre-release"))
    }

    @Test fun refusesOddTagsAndAssets() {
        assertTrue(refusal(edited { put("tag_name", "nightly") }).contains("not a version"))
        // Assets named for another version than the tag.
        assertTrue(refusal(edited { put("tag_name", "v1.3.1") }).contains("has no s1-kiosk-1.3.1.apk"))
        assertTrue(refusal(edited { put("assets", JSONArray()) }).contains("has no"))
        val noSha = edited { getJSONArray("assets").remove(1) }
        assertTrue(refusal(noSha).contains("s1-kiosk-1.3.0.apk.sha256"))
        val twoApks = edited { getJSONArray("assets").put(JSONObject(getJSONArray("assets").getJSONObject(0).toString())) }
        assertTrue(refusal(twoApks).contains("has no"))
        assertTrue(refusal(edited { getJSONArray("assets").getJSONObject(0).put("size", 0) }).contains("empty"))
        assertTrue(refusal(edited { getJSONArray("assets").getJSONObject(0).put("size", Releases.MAX_APK_BYTES + 1) }).contains("too large"))
    }

    @Test fun refusesUnexpectedDownloadUrls() {
        fun withUrl(u: String) = edited { getJSONArray("assets").getJSONObject(0).put("browser_download_url", u) }
        val good = "https://github.com/aguilaair/aqara-s1-unleashed/releases/download/v1.3.0/s1-kiosk-1.3.0.apk"
        for (bad in listOf(
            good.replace("https://", "http://"),
            good.replace("github.com", "evil.example"),
            good.replace("github.com", "github.com.evil.example"),
            good.replace("/v1.3.0/", "/v1.2.0/"),
            "$good?x=1",
            "https://github.com/aguilaair/aqara-s1-unleashed/raw/main/s1-kiosk-1.3.0.apk",
        )) {
            assertTrue(bad, refusal(withUrl(bad)).contains("unexpected download URL"))
        }
        // A renamed/transferred repo keeps working (GitHub redirects the API; the download path shape is the same).
        assertEquals(AppVersion(1, 3, 0), Releases.offer(Releases.parse(withUrl(good.replace("aguilaair/", "newowner/")))).version)
    }

    @Test fun malformedJsonNeverCrashes() {
        for (bad in listOf("", "null", "[]", "{", "{\"message\":\"Not Found\"}", "<html>rate limited</html>",
            "{\"tag_name\": 5, \"assets\": \"nope\"}")) {
            try {
                Releases.offer(Releases.parse(bad))
                fail("'$bad' should be refused")
            } catch (_: ReleaseFormatException) {
            }
        }
        // Nulls where GitHub allows them.
        val r = Releases.parse(edited { put("body", JSONObject.NULL); put("name", JSONObject.NULL) })
        assertEquals("", r.body)
        assertEquals("", r.name)
    }

    @Test fun parsesSha256Files() {
        val hex = "cb4870807289f0ebb14bbfc941421b08f5766fa0346c1828bd5f09a955ccd560"
        val want = MessageDigest.getInstance("SHA-256").digest("fake apk bytes".toByteArray())
        val apk = "s1-kiosk-1.3.0.apk"
        assertArrayEquals(want, Releases.parseSha256("$hex  $apk\n", apk))
        assertArrayEquals(want, Releases.parseSha256("$hex *$apk", apk)) // binary-mode sha256sum
        assertArrayEquals(want, Releases.parseSha256("\n${hex.uppercase()}\n", apk)) // bare digest
        assertNull(Releases.parseSha256("$hex  s1-kiosk-1.2.0.apk", apk)) // for another file
        assertNull(Releases.parseSha256(hex.drop(1), apk))
        assertNull(Releases.parseSha256("${hex.dropLast(1)}g  $apk", apk))
        assertNull(Releases.parseSha256("", apk))
        assertEquals(hex, Releases.hex(want))
    }

    @Test fun parsesGithubDigests() {
        val hex = "cb4870807289f0ebb14bbfc941421b08f5766fa0346c1828bd5f09a955ccd560"
        assertEquals(hex, Releases.hex(Releases.parseDigest("sha256:$hex")!!))
        assertNull(Releases.parseDigest("sha512:$hex"))
        assertNull(Releases.parseDigest(hex))
        assertNull(Releases.parseDigest(""))
        assertNull(Releases.parseDigest(null))
    }

    @Test fun summaryIsTidyAndShort() {
        val o = Releases.offer(Releases.parse(latest))
        val s = Releases.summary(o.notes)
        assertFalse(s.contains("Full Changelog"))
        assertFalse(s.contains("\r"))
        assertFalse(s.contains("\n\n\n"))
        assertTrue(s.length <= Releases.MAX_SUMMARY)
        assertEquals("Status\n\nnotes", Releases.summary("notes\n", "Status"))
        val long = Releases.summary("x".repeat(1000), "Install failed")
        assertEquals(Releases.MAX_SUMMARY, long.length)
        assertTrue(long.startsWith("Install failed\n\n") && long.endsWith("…"))
        // Never splits a surrogate pair.
        val emoji = Releases.summary("a".repeat(253) + "🚀" + "b")
        assertFalse(emoji.dropLast(1).last().isHighSurrogate())
    }
}
