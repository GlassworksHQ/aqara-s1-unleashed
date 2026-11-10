package sh.edm.s1kiosk.update

import org.json.JSONException
import org.json.JSONObject
import java.net.URI

/**
 * One asset of a GitHub release (`assets[]` in the Releases API). [digest]: GitHub's own SHA-256 of
 * the asset (`"digest": "sha256:<hex>"`, published since 2025), if present.
 */
class ReleaseAsset(val name: String, val url: String, val size: Long, val digest: ByteArray? = null)

/** The fields of a GitHub release we use (`GET /repos/{owner}/{repo}/releases/latest`). */
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

class ReleaseFormatException(message: String) : Exception(message)

/**
 * GitHub Releases as the app's update source (docs/releasing.md). The release workflow
 * (.github/workflows/release.yml) publishes `s1-kiosk-<version>.apk` and `s1-kiosk-<version>.apk.sha256`
 * on tag `v<version>`; everything else about a release is ignored.
 */
object Releases {
    /** owner/repo of the official releases. */
    const val REPO = "aguilaair/aqara-s1-unleashed"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

    /** HA caps `release_summary` at 255 characters; the full notes are behind `release_url`. */
    const val MAX_SUMMARY = 255
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val MAX_SHA_BYTES = 4 * 1024

    fun apkName(v: AppVersion) = "s1-kiosk-$v.apk"
    fun shaName(v: AppVersion) = "${apkName(v)}.sha256"

    /** Parses a release object; throws [ReleaseFormatException] on anything malformed. */
    fun parse(json: String): GithubRelease = try {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets")
        GithubRelease(
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
    } catch (e: JSONException) {
        throw ReleaseFormatException("not a release object: ${e.message?.take(80)}")
    }

    /**
     * The installable offer in [r], or a [ReleaseFormatException] saying why there is none: drafts,
     * pre-releases (GitHub flag or a SemVer pre-release tag), a tag that isn't `v<semver>`, or
     * missing/odd assets. Download URLs must be HTTPS release downloads on github.com for this tag.
     */
    fun offer(r: GithubRelease): UpdateOffer {
        if (r.draft) throw ReleaseFormatException("release ${r.tag} is a draft")
        if (r.prerelease) throw ReleaseFormatException("release ${r.tag} is a pre-release")
        val v = AppVersion.parse(r.tag) ?: throw ReleaseFormatException("tag '${r.tag.take(40)}' is not a version")
        if (v.isPreRelease) throw ReleaseFormatException("release ${r.tag} is a pre-release")
        if (v.versionCode == null) throw ReleaseFormatException("version $v is out of range")
        fun asset(name: String): ReleaseAsset {
            val a = r.assets.singleOrNull { it.name == name } ?: throw ReleaseFormatException("release ${r.tag} has no $name")
            if (!isReleaseDownload(a.url, r.tag, name)) throw ReleaseFormatException("unexpected download URL for $name")
            return a
        }
        val apk = asset(apkName(v))
        val sha = asset(shaName(v))
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
     * Release notes for HA's `release_summary`: [status] (if any) on top, then the notes without
     * GitHub's "Full Changelog" footer, whitespace tidied, cut at [max] characters with an ellipsis.
     */
    fun summary(notes: String, status: String = "", max: Int = MAX_SUMMARY): String {
        val body = notes.replace("\r\n", "\n").replace('\r', '\n').lines()
            .filterNot { it.trim().startsWith("**Full Changelog**") }
            .joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        val text = listOf(status.trim(), body).filter { it.isNotEmpty() }.joinToString("\n\n")
        if (text.length <= max) return text
        var cut = max - 1
        if (text[cut - 1].isHighSurrogate()) cut-- // don't split an emoji
        return text.take(cut).trimEnd() + "…"
    }
}
