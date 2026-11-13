/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** How a browser signed in to the Manage page. */
enum class SignedInWith { HOME_ASSISTANT, PANEL_CODE }

/**
 * One signed-in browser. The cookie holds a random token; the panel keeps only its SHA-256 ([key]),
 * so the file never holds a usable cookie. [id] is a short public name for the Security list.
 */
data class Session(
    val key: String,
    val id: String,
    /** The HA user's name, or null for a code sign-in. */
    val user: String?,
    val with: SignedInWith,
    /** Which browser, in words ("Safari on Mac"), from the User-Agent at sign-in. */
    val browser: String,
    val createdMs: Long,
    val lastUsedMs: Long,
    val expiresMs: Long,
    /** "Remember this browser for 30 days": the cookie outlives the browser session. */
    val remember: Boolean,
    /** Sent with every form, checked on every POST (CSRF). Not secret from this browser, secret from others. */
    val csrf: String,
    /** The HA address the panel used at sign-in. A session dies when the panel's address changes. */
    val haBase: String?,
)

/**
 * The Manage page's signed-in browsers. Sessions live [rememberMs] (30 days) when remembered, else
 * [shortMs] (12 h, and the cookie dies with the browser). Every session ends when the panel's HA address
 * changes ([lookup]'s haBase) or on [revokeAll]. Persisted to [file] so a remembered browser survives an
 * app restart or a reboot. Thread-safe.
 */
class SessionStore(
    private val file: File?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val rememberMs: Long = 30L * 24 * 3600 * 1000,
    private val shortMs: Long = 12L * 3600 * 1000,
    /** The most browsers at once; the least recently used one goes first. */
    private val max: Int = 20,
) {
    private val sessions = LinkedHashMap<String, Session>()

    init { load() }

    /** A new session (never one the browser brought: no fixation). Returns the cookie's token and the session. */
    @Synchronized
    fun create(user: String?, with: SignedInWith, browser: String, remember: Boolean, haBase: String?): Pair<String, Session> {
        val token = randomToken(32)
        val now = clock()
        val s = Session(
            key = hash(token), id = randomToken(6), user = user, with = with, browser = browser, createdMs = now, lastUsedMs = now,
            expiresMs = now + if (remember) rememberMs else shortMs, remember = remember, csrf = randomToken(24), haBase = haBase,
        )
        sessions[s.key] = s
        while (sessions.size > max) sessions.remove(sessions.values.minByOrNull { it.lastUsedMs }!!.key)
        save()
        return token to s
    }

    /** The session for the cookie [token], if it is valid now and was made for [haBase]. Marks it used. */
    @Synchronized
    fun lookup(token: String?, haBase: String?): Session? {
        if (token.isNullOrEmpty() || token.length > 100) return null
        val k = hash(token)
        val s = sessions[k] ?: return null
        val now = clock()
        if (now >= s.expiresMs || s.haBase != haBase) {
            sessions.remove(k); save(); return null
        }
        val used = s.copy(lastUsedMs = now)
        sessions[k] = used
        // Writing on every request would wear the flash; once an hour is enough for "last used".
        if (now - s.lastUsedMs > 3600_000L) save()
        return used
    }

    @Synchronized
    fun list(): List<Session> {
        val now = clock()
        return sessions.values.filter { now < it.expiresMs }.sortedByDescending { it.lastUsedMs }
    }

    /** Signs out the browser with the public [id]. True if there was one. */
    @Synchronized
    fun revoke(id: String): Boolean {
        val k = sessions.values.firstOrNull { it.id == id }?.key ?: return false
        sessions.remove(k)
        save()
        return true
    }

    @Synchronized
    fun revokeAll() {
        sessions.clear()
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val arr = JSONArray(f.readText())
            val now = clock()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val s = Session(
                    key = o.getString("key"), id = o.getString("id"), user = o.optString("user").ifEmpty { null },
                    with = SignedInWith.valueOf(o.getString("with")), browser = o.optString("browser"),
                    createdMs = o.getLong("created"), lastUsedMs = o.getLong("used"), expiresMs = o.getLong("expires"),
                    remember = o.optBoolean("remember"), csrf = o.getString("csrf"), haBase = o.optString("ha").ifEmpty { null },
                )
                if (now < s.expiresMs) sessions[s.key] = s
            }
        } catch (_: Exception) {
            // A damaged file signs everyone out; that is safe.
            sessions.clear()
        }
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        // A session that dies with the browser needn't survive a restart either.
        for (s in sessions.values.filter { it.remember }) arr.put(
            JSONObject().put("key", s.key).put("id", s.id).put("user", s.user ?: "").put("with", s.with.name)
                .put("browser", s.browser).put("created", s.createdMs).put("used", s.lastUsedMs).put("expires", s.expiresMs)
                .put("remember", s.remember).put("csrf", s.csrf).put("ha", s.haBase ?: ""),
        )
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (_: Exception) {}
    }

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    companion object {
        fun hash(token: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)))

        /** "Safari on Mac", "Chrome on Windows", … from a User-Agent; "A browser" when it says nothing useful. */
        fun browserName(userAgent: String?): String {
            val ua = userAgent.orEmpty()
            val browser = when {
                "Edg/" in ua -> "Edge"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> null
            }
            val os = when {
                "iPhone" in ua -> "iPhone"
                "iPad" in ua -> "iPad"
                "Android" in ua -> "Android"
                "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
                "Windows" in ua -> "Windows"
                "Linux" in ua -> "Linux"
                else -> null
            }
            return when {
                browser != null && os != null -> "$browser on $os"
                browser != null -> browser
                os != null -> "A browser on $os"
                else -> "A browser"
            }
        }
    }
}
