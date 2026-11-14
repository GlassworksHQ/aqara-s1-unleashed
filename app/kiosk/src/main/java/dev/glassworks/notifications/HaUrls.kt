/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import java.net.URI
import java.net.URISyntaxException

/**
 * Keeps a notification's links on the panel's own Home Assistant (#118), so an automation can't
 * send the panel, or its sign-in, to another site.
 *
 * A link is either a path on Home Assistant (`/dashboard-door/0`, `/api/camera_proxy/camera.porch`)
 * or an absolute URL with Home Assistant's own scheme, host and port. Anything else is refused:
 * another host, a scheme-relative `//host`, a backslash, user info (`http://ha@other`), control
 * characters, `javascript:` and `data:`.
 */
object HaUrls {

    /**
     * [ref] as an absolute URL on [haBase] (an origin such as `http://homeassistant.local:8123`; a
     * path on it is ignored), or null when [ref] points anywhere else or [haBase] is not http(s).
     */
    fun resolve(haBase: String?, ref: String?): String? {
        val origin = origin(haBase) ?: return null
        val r = ref?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (r.any { it.code < 0x21 || it.code == 0x7F || it == '\\' }) return null
        val candidate = when {
            r.startsWith("//") -> return null
            r.startsWith("/") -> origin.text + r
            else -> r
        }
        val u = parse(candidate) ?: return null
        return candidate.takeIf { sameOrigin(origin, u) }
    }

    /** The `camera_proxy` path of a camera entity (`camera.porch`), or null if [entityId] isn't one. */
    fun cameraProxy(entityId: String): String? =
        entityId.trim().takeIf { CAMERA.matches(it) }?.let { "/api/camera_proxy/$it" }

    private class Origin(val scheme: String, val host: String, val port: Int, val text: String)

    private fun origin(base: String?): Origin? {
        val u = base?.trim()?.let(::parse) ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.rawUserInfo != null) return null
        return Origin(scheme, host, effectivePort(scheme, u.port), "$scheme://${u.rawAuthority}")
    }

    private fun sameOrigin(o: Origin, u: URI): Boolean {
        val scheme = u.scheme?.lowercase() ?: return false
        if (u.isOpaque || u.rawUserInfo != null) return false
        val host = u.host?.lowercase() ?: return false
        return scheme == o.scheme && host == o.host && effectivePort(scheme, u.port) == o.port
    }

    private fun effectivePort(scheme: String, port: Int) = when {
        port != -1 -> port
        scheme == "https" -> 443
        else -> 80
    }

    private fun parse(s: String): URI? = try { URI(s) } catch (_: URISyntaxException) { null }

    private val CAMERA = Regex("camera\\.[a-z0-9_]+")
}
