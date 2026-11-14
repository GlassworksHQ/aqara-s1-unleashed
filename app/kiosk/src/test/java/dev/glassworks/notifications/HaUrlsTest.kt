/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaUrlsTest {
    private val ha = "http://homeassistant.local:8123"

    @Test
    fun aPathIsOnHomeAssistant() {
        assertEquals("$ha/dashboard-door/0", HaUrls.resolve(ha, "/dashboard-door/0"))
        assertEquals("$ha/api/camera_proxy/camera.porch", HaUrls.resolve(ha, " /api/camera_proxy/camera.porch "))
        assertEquals("$ha/lovelace/0?kiosk#top", HaUrls.resolve(ha, "/lovelace/0?kiosk#top"))
    }

    @Test
    fun theBasePathIsIgnoredOnlyItsOriginCounts() {
        assertEquals("$ha/dashboard-door/0", HaUrls.resolve("$ha/lovelace/panel", "/dashboard-door/0"))
    }

    @Test
    fun anAbsoluteUrlOnTheSameOriginIsKept() {
        assertEquals("$ha/lovelace/0", HaUrls.resolve(ha, "$ha/lovelace/0"))
        assertEquals("HTTP://HomeAssistant.local:8123/x", HaUrls.resolve(ha, "HTTP://HomeAssistant.local:8123/x"))
        assertEquals("https://192.0.2.10/x", HaUrls.resolve("https://192.0.2.10:443", "https://192.0.2.10/x"))
        assertEquals("http://192.0.2.10:80/x", HaUrls.resolve("http://192.0.2.10", "http://192.0.2.10:80/x"))
    }

    @Test
    fun offHostIsRefused() {
        val refused = listOf(
            "https://example.com/",
            "http://example.com:8123/lovelace/0",
            "http://homeassistant.local.example.com:8123/",
            "http://homeassistant.local:8124/lovelace/0", // another port
            "https://homeassistant.local:8123/lovelace/0", // another scheme
            "//example.com/x", // scheme-relative
            "/\\example.com/x", // backslash: browsers read it as //
            "\\\\example.com/x",
            "http://homeassistant.local:8123@example.com/", // user info
            "http://user@homeassistant.local:8123/",
            "javascript:alert(1)",
            "data:text/html,<p>hi</p>",
            "file:///sdcard/x.png",
            "ftp://homeassistant.local:8123/x",
            "lovelace/0", // relative without a slash
            "/love\tlace",
            "/love lace",
            "",
            "   ",
        )
        for (r in refused) assertNull("should refuse '$r'", HaUrls.resolve(ha, r))
        assertNull(HaUrls.resolve(ha, null))
    }

    @Test
    fun withoutAnHttpBaseNothingResolves() {
        assertNull(HaUrls.resolve(null, "/lovelace/0"))
        assertNull(HaUrls.resolve("", "/lovelace/0"))
        assertNull(HaUrls.resolve("homeassistant.local:8123", "/lovelace/0"))
        assertNull(HaUrls.resolve("file:///sdcard", "/lovelace/0"))
        assertNull(HaUrls.resolve("http://u@homeassistant.local:8123", "/lovelace/0"))
    }

    @Test
    fun cameraProxyOnlyForCameras() {
        assertEquals("/api/camera_proxy/camera.porch", HaUrls.cameraProxy("camera.porch"))
        assertNull(HaUrls.cameraProxy("light.porch"))
        assertNull(HaUrls.cameraProxy("camera.../../x"))
        assertNull(HaUrls.cameraProxy("camera."))
    }
}
