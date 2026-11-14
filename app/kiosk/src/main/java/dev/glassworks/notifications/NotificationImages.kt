/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** What fetching a notification's picture gave. */
sealed class ImageFetch {
    class Ok(val bytes: ByteArray, val contentType: String) : ImageFetch() {
        override fun toString() = "Ok(${bytes.size} bytes, $contentType)"
    }

    data class Failed(val reason: String) : ImageFetch()
}

/** Fetches a notification's picture. Blocking: call it off the main thread. */
fun interface NotificationImages {
    fun fetch(url: String): ImageFetch
}

/** One HTTP GET, as [HaImages] uses it (a fake in tests). It must not follow redirects. */
fun interface HttpGet {
    class Response(val status: Int, val contentType: String?, val body: ByteArray, val tooLarge: Boolean)

    /** Throws [IOException] when Home Assistant can't be reached. */
    fun get(url: String, headers: Map<String, String>, maxBytes: Int): Response
}

/**
 * Pictures from the panel's own Home Assistant: a camera's `/api/camera_proxy/camera.…`, `/local/…`
 * or `/media/…`, fetched with the panel's sign-in. The sign-in only reads here (pane rule 4).
 *
 * - Only URLs on [haBase] ([HaUrls]), checked again here, so the token never goes to another host.
 * - No redirects: a redirect could carry the token elsewhere.
 * - Only `image/…`, at most [maxBytes].
 *
 * [token] gives an access token, or null when the panel isn't signed in; it may block. Never logs
 * the token.
 */
class HaImages(
    private val haBase: () -> String?,
    private val token: () -> String?,
    private val http: HttpGet = UrlConnectionGet(),
    private val maxBytes: Int = MAX_BYTES,
) : NotificationImages {

    override fun fetch(url: String): ImageFetch {
        if (HaUrls.resolve(haBase(), url) != url) return ImageFetch.Failed("not on this panel's Home Assistant")
        val t = token() ?: return ImageFetch.Failed("the panel isn't signed in to Home Assistant")
        val r = try {
            http.get(url, mapOf("Authorization" to "Bearer $t", "Accept" to "image/*"), maxBytes)
        } catch (e: IOException) {
            return ImageFetch.Failed("can't reach Home Assistant (${e.javaClass.simpleName})")
        }
        val type = r.contentType?.substringBefore(';')?.trim()?.lowercase()
        return when {
            r.status == 401 || r.status == 403 -> ImageFetch.Failed("Home Assistant refused the panel's sign-in")
            r.status in 300..399 -> ImageFetch.Failed("Home Assistant answered with a redirect")
            r.status != 200 -> ImageFetch.Failed("Home Assistant answered ${r.status}")
            r.tooLarge -> ImageFetch.Failed("the picture is larger than ${maxBytes / 1024 / 1024} MiB")
            type == null || !type.startsWith("image/") -> ImageFetch.Failed("not a picture (${type ?: "no type"})")
            else -> ImageFetch.Ok(r.body, type)
        }
    }

    companion object {
        /** A camera snapshot is well under this; the S1 has little RAM to spare. */
        const val MAX_BYTES = 4 * 1024 * 1024
    }
}

/** [HttpGet] over [HttpURLConnection]: no redirects, 5 s to connect and 10 s to read. */
class UrlConnectionGet(private val connectMs: Int = 5_000, private val readMs: Int = 10_000) : HttpGet {
    override fun get(url: String, headers: Map<String, String>, maxBytes: Int): HttpGet.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val status = c.responseCode
            if (status != 200) return HttpGet.Response(status, c.contentType, ByteArray(0), false)
            val buf = ByteArrayOutputStream()
            var tooLarge = false
            c.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buf.size() + n > maxBytes) { tooLarge = true; break }
                    buf.write(chunk, 0, n)
                }
            }
            return HttpGet.Response(status, c.contentType, if (tooLarge) ByteArray(0) else buf.toByteArray(), tooLarge)
        } finally {
            c.disconnect()
        }
    }
}
