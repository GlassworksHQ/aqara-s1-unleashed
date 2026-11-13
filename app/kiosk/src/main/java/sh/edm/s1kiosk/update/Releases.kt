package sh.edm.s1kiosk.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * One asset of a GitHub release (`assets[]` in the Releases API). [digest]: GitHub's own SHA-256 of
 * the asset (`"digest": "sha256:<hex>"`, published since 2025), if present.
 */
class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: ByteArray? = null)

/** The fields of a GitHub release we use (one entry of `GET /repos/{owner}/{repo}/releases`). */
data class GithubRelease(
    val tag: String,
    val name: String,
    val body: String,
    val htmlUrl: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

/** A published release this app can install: version, notes and the two assets. */
data class UpdateOffer(
    val version: AppVersion,
    val tag: String,
    val notes: String,
    val releaseUrl: String,
    val apk: ReleaseAsset,
    val sha256: ReleaseAsset,
)

/**
 * The ABIs this app is built for, each with its release APK's name stem: every ABI a frame runs
 * (`abis` in frames/<id>/design/frame.json; ApkAbiListsTest checks the two agree). Every ABI's APK has the same version and versionCode (docs/releasing.md "ABIs").
 */
enum class ApkAbi(val id: String, private val stem: String) {
    /** The S1's ABI. Its name has been `s1-kiosk-<version>.apk` since the first release, and panels up to 0.4.3 look only for that name. */
    ARMEABI_V7A("armeabi-v7a", "s1-kiosk"),
    ARM64_V8A("arm64-v8a", "s1-kiosk-arm64-v8a");

    fun apkName(v: AppVersion) = "$stem-$v.apk"
    fun shaName(v: AppVersion) = "${apkName(v)}.sha256"

    companion object {
        /** This app's ABIs among [deviceAbis] (`Build.SUPPORTED_ABIS`), in the device's order of preference. */
        fun among(deviceAbis: List<String>): List<ApkAbi> = deviceAbis.mapNotNull { id -> entries.firstOrNull { it.id == id } }.distinct()
    }
}

class ReleaseFormatException(message: String) : Exception(message)

/** The newest installable release of this app ([offer], or null), and why a newer one was refused. */
class ReleasePick(val offer: UpdateOffer?, val problem: String?)

/**
 * GitHub Releases as the app's update source (docs/releasing.md). The release workflow
 * (.github/workflows/release.yml) publishes one APK and its `.sha256` per [ApkAbi] on tag
 * `v<version>` or `aqara-s1-plus-v<version>`; everything else about a release is ignored. The app
 * takes the APK of the first of the device's ABIs that the release has, as Android does for native
 * libraries.
 *
 * The repo will also hold other frames and libraries (Glassworks), each releasing under its own tag
 * prefix, so "the latest release" may not be this app's. The app lists recent releases and picks
 * the newest of its own ([newest]) instead of reading `releases/latest`.
 */
object Releases {
    /** owner/repo of the official releases. */
    const val REPO = "GlassworksHQ/aqara-s1-unleashed"
    /** Recent releases, newest first. 30 leaves room for other frames' and libraries' releases. */
    const val LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"

    /** This app's tag prefix in the shared repo. Plain `v1.2.3` tags (all releases so far) stay valid. */
    const val TAG_PREFIX = "aqara-s1-plus-"

    /** The version in one of this app's release tags (`v1.2.3`, `aqara-s1-plus-v1.2.3`); null for any other tag. */
    fun tagVersion(tag: String): AppVersion? = AppVersion.parse(tag.removePrefix(TAG_PREFIX))

    /** HA caps `release_summary` at 255 characters; the full notes are behind `release_url`. */
    const val MAX_SUMMARY = 255
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_SHA_BYTES = 4 * 1024

    /** Parses a release object; throws [ReleaseFormatException] on anything malformed. */
    fun parse(json: String): GithubRelease = try {
        parse(JSONObject(json))
    } catch (e: JSONException) {
        throw ReleaseFormatException("not a release object: ${e.message?.take(80)}")
    }

    /** Parses the Releases API's list; throws [ReleaseFormatException] if it isn't a list of releases. */
    fun parseList(json: String): List<GithubRelease> = try {
        val a = JSONArray(json)
        (0 until a.length()).map { parse(a.getJSONObject(it)) }
    } catch (e: JSONException) {
        throw ReleaseFormatException("not a list of releases: ${e.message?.take(80)}")
    }

    private fun parse(o: JSONObject): GithubRelease {
        val assets = o.optJSONArray("assets")
        return GithubRelease(
            tag = o.getString("tag_name"),
            name = o.optString("name").takeUnless { o.isNull("name") }.orEmpty(),
            body = o.optString("body").takeUnless { o.isNull("body") }.orEmpty(),
            htmlUrl = o.optString("html_url"),
            draft = o.optBoolean("draft", false),
            prerelease = o.optBoolean("prerelease", false),
            assets = (0 until (assets?.length() ?: 0)).mapNotNull { i ->
                val a = assets!!.optJSONObject(i) ?: return@mapNotNull null
                val name = a.optString("name")
                val url = a.optString("browser_download_url")
                if (name.isEmpty() || url.isEmpty()) null
                else ReleaseAsset(name, url, a.optLong("size", -1), parseDigest(a.optString("digest")))
            },
        )
    }

    /**
     * The newest installable release of this app in [releases]. Other frames' and libraries' releases
     * (another tag prefix) are skipped silently. If a newer release of this app was refused (draft,
     * pre-release, odd assets), the newest acceptable one is still offered, and [ReleasePick.problem]
     * says what was wrong with the newer one. No release of this app at all: no offer, no problem.
     */
    fun newest(releases: List<GithubRelease>, deviceAbis: List<String>): ReleasePick {
        var best: UpdateOffer? = null
        var refused: Pair<AppVersion, String>? = null
        for (r in releases) {
            val v = tagVersion(r.tag) ?: continue
            try {
                val o = offer(r, deviceAbis)
                if (best == null || o.version > best.version) best = o
            } catch (e: ReleaseFormatException) {
                if (refused == null || v > refused.first) refused = v to e.message.orEmpty()
            }
        }
        val offer = best
        val problem = refused?.takeIf { (v, _) -> offer == null || v > offer.version }?.second
        return ReleasePick(offer, problem)
    }

    /**
     * The installable offer in [r], or a [ReleaseFormatException] saying why there is none: drafts,
     * pre-releases (GitHub flag or a SemVer pre-release tag), a tag that isn't one of this app's
     * ([tagVersion]), no APK and `.sha256` pair for any of [deviceAbis] (`Build.SUPPORTED_ABIS`,
     * preferred first; an ABI with half its pair is skipped for the next one), or
     * missing/odd assets. Download URLs must be HTTPS release downloads on github.com for this tag.
     */
    fun offer(r: GithubRelease, deviceAbis: List<String>): UpdateOffer {
        if (r.draft) throw ReleaseFormatException("release ${r.tag} is a draft")
        if (r.prerelease) throw ReleaseFormatException("release ${r.tag} is a pre-release")
        val v = tagVersion(r.tag) ?: throw ReleaseFormatException("tag '${r.tag.take(40)}' is not a version of this app")
        if (v.isPreRelease) throw ReleaseFormatException("release ${r.tag} is a pre-release")
        if (v.versionCode == null) throw ReleaseFormatException("version $v is out of range")
        fun asset(name: String): ReleaseAsset {
            val a = r.assets.singleOrNull { it.name == name } ?: throw ReleaseFormatException("release ${r.tag} has no $name")
            if (!isReleaseDownload(a.url, r.tag, name)) throw ReleaseFormatException("unexpected download URL for $name")
            return a
        }
        val abis = ApkAbi.among(deviceAbis)
        if (abis.isEmpty()) throw ReleaseFormatException("no APK is built for this device's ABIs (${deviceAbis.joinToString()})")
        fun one(name: String) = r.assets.count { it.name == name } == 1
        val abi = abis.firstOrNull { one(it.apkName(v)) && one(it.shaName(v)) }
            ?: throw ReleaseFormatException("release ${r.tag} has no ${abis.joinToString(" or ") { "${it.apkName(v)} with ${it.shaName(v)}" }}")
        val apk = asset(abi.apkName(v))
        val sha = asset(abi.shaName(v))
        if (apk.size > MAX_APK_BYTES) throw ReleaseFormatException("${apk.name} is too large (${apk.size} bytes)")
        if (apk.size == 0L) throw ReleaseFormatException("${apk.name} is empty")
        return UpdateOffer(v, r.tag, r.body, r.htmlUrl.takeIf(::isHttps).orEmpty(), apk, sha)
    }

    /** `https://github.com/<owner>/<repo>/releases/download/<tag>/<name>` (owner/repo may differ after a rename). */
    fun isReleaseDownload(url: String, tag: String, name: String): Boolean {
        val u = try { URI(url) } catch (_: Exception) { return false }
        if (u.scheme != "https" || u.host != "github.com" || u.rawQuery != null || u.rawFragment != null) return false
        val p = u.path.split('/')
        // ["", owner, repo, "releases", "download", tag, name]
        return p.size == 7 && p[0].isEmpty() && p[1].isNotEmpty() && p[2].isNotEmpty() &&
            p[3] == "releases" && p[4] == "download" && p[5] == tag && p[6] == name
    }

    private fun isHttps(url: String) = url.startsWith("https://")

    /**
     * The digest in a `.sha256` file: `sha256sum` output (`<hex>  <name>` or `<hex> *<name>`) or a
     * bare hex digest. A file name, when present, must be [apkName]. Null if malformed.
     */
    fun parseSha256(text: String, apkName: String): ByteArray? {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val parts = line.split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].trim().removePrefix("*") != apkName) return null
        return parseSha256Hex(parts[0].lowercase())
    }

    /** GitHub's `sha256:<hex>` asset digest; null if absent or another algorithm. */
    fun parseDigest(s: String?): ByteArray? {
        val t = s?.trim()?.lowercase() ?: return null
        return if (t.startsWith("sha256:")) parseSha256Hex(t.removePrefix("sha256:")) else null
    }

    private fun parseSha256Hex(hex: String): ByteArray? {
        if (!Regex("^[0-9a-f]{64}$").matches(hex)) return null
        return ByteArray(32) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /**
     * Release notes for HA's `release_summary` (at most [max] characters; the full notes are behind
     * `release_url`). Built from whole pieces, never cutting inside markdown: [status] (if any), the
     * notes' first paragraph (the one-line summary in CHANGELOG.md), then `###` headings and top-level
     * `- ` bullets in order. A bullet goes in whole if it fits, else as its bold lead (`- **X**`), and
     * the summary stops at the first piece that doesn't fit. Only a first paragraph that alone is too
     * long gets cut, at a word, with its markdown markers removed. GitHub's "Full Changelog" footer is
     * dropped.
     */
    fun summary(notes: String, status: String = "", max: Int = MAX_SUMMARY): String {
        val lines = notes.replace("\r\n", "\n").replace('\r', '\n').lines()
            .map { it.trimEnd() }
            .filterNot { it.trim().startsWith("**Full Changelog**") }
        val out = ArrayList<String>()
        fun text(extra: List<String> = emptyList()) = (out + extra).joinToString("\n").trim()
        fun fits(vararg add: String) = text(add.toList()).length <= max
        if (status.isNotBlank()) { out += status.trim(); out += "" }

        // first paragraph
        var i = 0
        while (i < lines.size && lines[i].isBlank()) i++
        val para = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank()) para += lines[i++].trim()
        val first = para.joinToString(" ")
        if (first.isEmpty()) return text().let { if (it.length <= max) it else cutPlain(it, max) }
        if (!fits(first)) return cutPlain(text(listOf(plain(first))), max)
        out += first

        // headings and top-level bullets (a bullet's continuation lines are indented)
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("### ") -> {
                    if (!fits("", line)) break
                    out += ""; out += line; i++
                }
                line.startsWith("- ") -> {
                    val item = StringBuilder(line)
                    i++
                    while (i < lines.size && lines[i].startsWith("  ") && !lines[i].trimStart().startsWith("- ")) {
                        item.append(' ').append(lines[i].trim()); i++
                    }
                    while (i < lines.size && lines[i].startsWith("  ")) i++ // nested bullets: left out
                    val full = item.toString()
                    val lead = Regex("^- \\*\\*[^*]+\\*\\*").find(full)?.value
                    when {
                        fits(full) -> out += full
                        lead != null && fits(lead) -> out += lead
                        else -> return text()
                    }
                }
                else -> i++
            }
        }
        return text()
    }

    /** Markdown emphasis and link syntax removed, for when plain text must be cut. */
    private fun plain(s: String) = s.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1").replace(Regex("[*_`]"), "")

    /** Cut at a word boundary with an ellipsis, without splitting a surrogate pair. */
    private fun cutPlain(s: String, max: Int): String {
        if (s.length <= max) return s
        var cut = max - 1
        if (s[cut - 1].isHighSurrogate()) cut--
        val space = s.lastIndexOf(' ', cut)
        if (space > max / 2) cut = space
        return s.take(cut).trimEnd() + "…"
    }
}
