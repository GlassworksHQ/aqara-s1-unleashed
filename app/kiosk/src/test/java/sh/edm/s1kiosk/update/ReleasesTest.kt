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

    /** `Build.SUPPORTED_ABIS` on the S1 (32-bit userspace). */
    private val S1 = listOf("armeabi-v7a", "armeabi")

    private fun resource(name: String) = javaClass.classLoader!!.getResource("update/$name")!!.readText()
    private val latest = resource("release_latest.json")
    private fun edited(edit: JSONObject.() -> Unit) = JSONObject(latest).apply(edit).toString()
    private fun refusal(json: String): String = try {
        Releases.offer(Releases.parse(json), S1)
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
        val o = Releases.offer(Releases.parse(latest), S1)
        assertEquals(AppVersion(1, 3, 0), o.version)
        assertEquals("s1-kiosk-1.3.0.apk", o.apk.name)
        assertEquals("https://github.com/GlassworksHQ/glassworks/releases/download/v1.3.0/s1-kiosk-1.3.0.apk", o.apk.url)
        assertEquals("s1-kiosk-1.3.0.apk.sha256", o.sha256.name)
        assertEquals("https://github.com/GlassworksHQ/glassworks/releases/tag/v1.3.0", o.releaseUrl)
        assertTrue(o.notes.startsWith("## What's Changed"))
        // The fixture's .sha256 file and GitHub's own digest agree, as the manager requires.
        assertArrayEquals(o.apk.digest, Releases.parseSha256(resource("s1-kiosk-1.3.0.apk.sha256"), o.apk.name))
    }

    @Test fun extraAssetsAreIgnored() {
        val json = edited {
            getJSONArray("assets").put(JSONObject().put("name", "s1-kiosk-1.3.0-debug.apk").put("size", 9)
                .put("browser_download_url", "https://github.com/GlassworksHQ/glassworks/releases/download/v1.3.0/s1-kiosk-1.3.0-debug.apk"))
        }
        assertEquals("s1-kiosk-1.3.0.apk", Releases.offer(Releases.parse(json), S1).apk.name)
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
        val good = "https://github.com/GlassworksHQ/glassworks/releases/download/v1.3.0/s1-kiosk-1.3.0.apk"
        for (bad in listOf(
            good.replace("https://", "http://"),
            good.replace("github.com", "evil.example"),
            good.replace("github.com", "github.com.evil.example"),
            good.replace("/v1.3.0/", "/v1.2.0/"),
            "$good?x=1",
            "https://github.com/GlassworksHQ/glassworks/raw/main/s1-kiosk-1.3.0.apk",
        )) {
            assertTrue(bad, refusal(withUrl(bad)).contains("unexpected download URL"))
        }
        // A renamed/transferred repo keeps working (GitHub redirects the API; the download path shape is the same).
        // This is how 0.4.3, pinned to GlassworksHQ/aqara-s1-unleashed, takes releases after the 2026-11-13 rename.
        assertEquals(AppVersion(1, 3, 0), Releases.offer(Releases.parse(withUrl(good.replace("GlassworksHQ/", "newowner/"))), S1).version)
    }

    @Test fun malformedJsonNeverCrashes() {
        for (bad in listOf("", "null", "[]", "{", "{\"message\":\"Not Found\"}", "<html>rate limited</html>",
            "{\"tag_name\": 5, \"assets\": \"nope\"}")) {
            try {
                Releases.offer(Releases.parse(bad), S1)
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
        val o = Releases.offer(Releases.parse(latest), S1)
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

    @Test fun summaryFromTheChangelogNeverCutsInsideMarkdown() {
        // The v0.3.0 section of CHANGELOG.md (read from the repo when available).
        val notes = java.io.File("../../CHANGELOG.md").takeIf { it.isFile }?.readText()
            ?.substringAfter("## [0.3.0]")?.substringAfter("\n")?.substringBefore("\n## [")
            ?: "**Summary line.**\n\n### Added\n- **Radio modes**, chosen in the setup or in Home Assistant, with\n  a long continuation line that does not fit in the limit at all when added to the rest of it.\n  - nested\n- Another bullet."
        val s = Releases.summary(notes)
        assertTrue(s, s.length <= Releases.MAX_SUMMARY)
        assertTrue(s, s.startsWith("**"))
        assertEquals(s, 0, Regex("\\*\\*").findAll(s).count() % 2)   // bold markers balanced
        assertFalse(s, s.contains("…"))                              // whole pieces, no mid-text cut
        assertFalse(s, s.lines().any { it.startsWith("  ") })        // no nested bullets
    }

    @Test fun summaryWithStatusAndShortNotes() {
        val s = Releases.summary("**One line.**\n\n### Fixed\n- A thing.\n- Another thing.", "Install failed: x")
        assertEquals("Install failed: x\n\n**One line.**\n\n### Fixed\n- A thing.\n- Another thing.", s)
    }

    // ---------------------------------------------------------------- one repo, several frames

    /** The fixture as release [version] under [tag] (assets and their URLs follow), optionally a draft. */
    private fun release(tag: String, version: String = "1.3.0", draft: Boolean = false): JSONObject {
        val json = latest.replace("1.3.0", version).replace("/download/v$version/", "/download/$tag/")
        return JSONObject(json).put("tag_name", tag).put("draft", draft)
    }
    private fun list(vararg r: JSONObject) = JSONArray(r.toList()).toString()

    @Test fun tagsOfThisAppOnly() {
        assertEquals(AppVersion(1, 2, 3), Releases.tagVersion("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), Releases.tagVersion("aqara-s1-plus-v1.2.3"))
        assertNull(Releases.tagVersion("lululemon-mirror-v1.2.3"))
        assertNull(Releases.tagVersion("esphome-api-v1.2.3"))
        assertNull(Releases.tagVersion("aqara-s1-v1.2.3"))
        assertNull(Releases.tagVersion("installer-files-3"))
    }

    @Test fun prefixedTagIsInstallable() {
        val o = Releases.offer(Releases.parse(release("aqara-s1-plus-v1.3.0").toString()), S1)
        assertEquals(AppVersion(1, 3, 0), o.version)
        assertEquals("https://github.com/GlassworksHQ/glassworks/releases/download/aqara-s1-plus-v1.3.0/s1-kiosk-1.3.0.apk", o.apk.url)
    }

    @Test fun picksThisAppsNewestReleaseAmongOthers() {
        // Newest first, as GitHub lists them: another frame and a library released after this app.
        val other = release("lululemon-mirror-v2.0.0", "2.0.0")
        val lib = release("esphome-api-v1.1.0", "1.1.0")
        val pick = Releases.newest(Releases.parseList(list(other, lib, release("aqara-s1-plus-v1.4.0", "1.4.0"), release("v1.3.0"))), S1)
        assertEquals(AppVersion(1, 4, 0), pick.offer!!.version)
        assertNull(pick.problem)
    }

    @Test fun oldPlainTagsStillCount() {
        val pick = Releases.newest(Releases.parseList(list(release("installer-files-3"), release("v1.3.0"))), S1)
        assertEquals(AppVersion(1, 3, 0), pick.offer!!.version)
    }

    @Test fun aRefusedNewerReleaseStillOffersTheOlderOne() {
        val pick = Releases.newest(Releases.parseList(list(release("aqara-s1-plus-v1.4.0", "1.4.0", draft = true), release("v1.3.0"))), S1)
        assertEquals(AppVersion(1, 3, 0), pick.offer!!.version)
        assertTrue(pick.problem!!.contains("draft"))
    }

    @Test fun noReleaseOfThisAppIsNotAProblem() {
        val pick = Releases.newest(Releases.parseList(list(release("lululemon-mirror-v2.0.0", "2.0.0"))), S1)
        assertNull(pick.offer)
        assertNull(pick.problem)
        assertNull(Releases.newest(emptyList(), S1).offer)
    }

    /** The fixture with an arm64-v8a APK and `.sha256` beside the S1's, as the release workflow publishes them. */
    private fun withArm64(json: String = latest): String = JSONObject(json).apply {
        val assets = getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = JSONObject(assets.getJSONObject(i).toString())
            val name = a.getString("name").replace("s1-kiosk-", "s1-kiosk-arm64-v8a-")
            a.put("name", name).put("size", a.getLong("size") + 1)
                .put("browser_download_url", a.getString("browser_download_url").substringBeforeLast('/') + "/" + name)
            assets.put(a)
        }
    }.toString()

    @Test fun theS1AssetNameNeverChanges() {
        // Panels up to 0.4.3 look only for this name, and the web installer too.
        assertEquals("s1-kiosk-1.3.0.apk", ApkAbi.ARMEABI_V7A.apkName(AppVersion(1, 3, 0)))
        assertEquals("s1-kiosk-1.3.0.apk.sha256", ApkAbi.ARMEABI_V7A.shaName(AppVersion(1, 3, 0)))
        assertEquals("s1-kiosk-arm64-v8a-1.3.0.apk", ApkAbi.ARM64_V8A.apkName(AppVersion(1, 3, 0)))
    }

    @Test fun theS1TakesItsOwnApkWhenArm64IsThereToo() {
        val o = Releases.offer(Releases.parse(withArm64()), S1)
        assertEquals("s1-kiosk-1.3.0.apk", o.apk.name)
        assertEquals("s1-kiosk-1.3.0.apk.sha256", o.sha256.name)
    }

    @Test fun anArm64DeviceTakesTheArm64Apk() {
        for (abis in listOf(listOf("arm64-v8a"), listOf("arm64-v8a", "armeabi-v7a", "armeabi"))) {
            val o = Releases.offer(Releases.parse(withArm64()), abis)
            assertEquals("$abis", "s1-kiosk-arm64-v8a-1.3.0.apk", o.apk.name)
            assertEquals("https://github.com/GlassworksHQ/glassworks/releases/download/v1.3.0/s1-kiosk-arm64-v8a-1.3.0.apk", o.apk.url)
            assertEquals("s1-kiosk-arm64-v8a-1.3.0.apk.sha256", o.sha256.name)
            assertEquals(15L, o.apk.size)
        }
    }

    @Test fun anArm64DeviceThatRuns32BitFallsBackToTheS1Apk() {
        // Every release up to 0.4.3 has only the armeabi-v7a APK.
        val o = Releases.offer(Releases.parse(latest), listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("s1-kiosk-1.3.0.apk", o.apk.name)
    }

    @Test fun anArm64OnlyDeviceRefusesAReleaseWithoutArm64() {
        val e = try { Releases.offer(Releases.parse(latest), listOf("arm64-v8a")); null } catch (e: ReleaseFormatException) { e }
        assertTrue(e!!.message!!.contains("has no s1-kiosk-arm64-v8a-1.3.0.apk"))
        // newest() skips such releases for the next one that has the device's APK.
        val pick = Releases.newest(Releases.parseList(list(release("v1.3.0"), JSONObject(withArm64(release("v1.2.0", "1.2.0").toString())))), listOf("arm64-v8a"))
        assertEquals(AppVersion(1, 2, 0), pick.offer!!.version)
        assertTrue(pick.problem!!.contains("arm64-v8a"))
    }

    @Test fun anAbiWithHalfItsPairIsSkippedForTheNextOne() {
        // arm64 APK uploaded, its .sha256 not (yet): a device that also runs 32-bit takes the complete S1 pair.
        val halfArm64 = JSONObject(withArm64()).apply {
            val assets = getJSONArray("assets")
            (0 until assets.length()).first { assets.getJSONObject(it).getString("name") == "s1-kiosk-arm64-v8a-1.3.0.apk.sha256" }
                .let { assets.remove(it) }
        }.toString()
        val o = Releases.offer(Releases.parse(halfArm64), listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("s1-kiosk-1.3.0.apk", o.apk.name)
        assertEquals("s1-kiosk-1.3.0.apk.sha256", o.sha256.name)
        val e = try { Releases.offer(Releases.parse(halfArm64), listOf("arm64-v8a")); null } catch (e: ReleaseFormatException) { e }
        assertTrue(e!!.message!!.contains("has no s1-kiosk-arm64-v8a-1.3.0.apk with s1-kiosk-arm64-v8a-1.3.0.apk.sha256"))
    }

    @Test fun aRepeatedDeviceAbiCountsOnce() {
        assertEquals(listOf(ApkAbi.ARM64_V8A, ApkAbi.ARMEABI_V7A), ApkAbi.among(listOf("arm64-v8a", "armeabi-v7a", "arm64-v8a", "armeabi", "armeabi-v7a")))
        val e = try { Releases.offer(Releases.parse(latest), listOf("arm64-v8a", "arm64-v8a")); null } catch (e: ReleaseFormatException) { e }
        assertEquals("release v1.3.0 has no s1-kiosk-arm64-v8a-1.3.0.apk with s1-kiosk-arm64-v8a-1.3.0.apk.sha256", e!!.message)
    }

    @Test fun aReleaseWithOnlyTheSha256IsNotAPair() {
        val noApk = edited { getJSONArray("assets").remove(0) } // the fixture's s1-kiosk-1.3.0.apk
        assertEquals(listOf("s1-kiosk-1.3.0.apk.sha256"), Releases.parse(noApk).assets.map { it.name })
        val e = try { Releases.offer(Releases.parse(noApk), S1); null } catch (e: ReleaseFormatException) { e }
        assertEquals("release v1.3.0 has no s1-kiosk-1.3.0.apk with s1-kiosk-1.3.0.apk.sha256", e!!.message)
    }

    @Test fun theRefusalNamesEveryPairTheDeviceCouldUse() {
        val e = try { Releases.offer(Releases.parse(edited { put("assets", JSONArray()) }), listOf("arm64-v8a", "armeabi-v7a", "armeabi")); null }
            catch (e: ReleaseFormatException) { e }
        assertEquals(
            "release v1.3.0 has no s1-kiosk-arm64-v8a-1.3.0.apk with s1-kiosk-arm64-v8a-1.3.0.apk.sha256" +
                " or s1-kiosk-1.3.0.apk with s1-kiosk-1.3.0.apk.sha256",
            e!!.message,
        )
    }

    @Test fun aDeviceWithNoBuiltAbiGetsNoOffer() {
        val e = try { Releases.offer(Releases.parse(withArm64()), listOf("x86_64")); null } catch (e: ReleaseFormatException) { e }
        assertTrue(e!!.message!!.contains("no APK is built for this device's ABIs (x86_64)"))
    }

    @Test fun malformedListIsRefused() {
        for (bad in listOf("", "{}", "[1]", "[{\"assets\": []}]")) {
            try { Releases.parseList(bad); fail("accepted '$bad'") } catch (_: ReleaseFormatException) {}
        }
    }
}
