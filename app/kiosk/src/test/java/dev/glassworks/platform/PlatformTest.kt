/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The first API level each query is true on. These numbers are written out here on purpose: they
 * are the boundaries the app had before the checks moved, so a changed boundary in [PlatformApi]
 * fails this table.
 */
class PlatformTest {
    private val queries: List<Triple<String, Int, (PlatformApi) -> Boolean>> = listOf(
        Triple("hasNotificationChannels", 26) { it.hasNotificationChannels },
        Triple("hasForegroundServiceStart", 26) { it.hasForegroundServiceStart },
        Triple("hasDisplayCutoutMode", 28) { it.hasDisplayCutoutMode },
        Triple("hasSigningInfo", 28) { it.hasSigningInfo },
        Triple("hasLongVersionCode", 28) { it.hasLongVersionCode },
        Triple("hasForegroundServiceTypes", 29) { it.hasForegroundServiceTypes },
        Triple("hasSettingsPanel", 29) { it.hasSettingsPanel },
        Triple("hasFontBuilder", 29) { it.hasFontBuilder },
        Triple("hasWindowInsetsController", 30) { it.hasWindowInsetsController },
        Triple("hasInsetsIgnoringVisibility", 30) { it.hasInsetsIgnoringVisibility },
        Triple("hasPrivacyIndicatorBounds", 31) { it.hasPrivacyIndicatorBounds },
        Triple("hasApplicationExitInfo", 30) { it.hasApplicationExitInfo },
        Triple("foregroundServiceTypesNeedTheirPermission", 34) { it.foregroundServiceTypesNeedTheirPermission },
    )

    @Test
    fun `each query flips at its boundary and nowhere else`() {
        for ((name, firstLevel, query) in queries) {
            for (level in 21..40) {
                assertEquals("$name at API $level", level >= firstLevel, query(PlatformApi(level)))
            }
        }
    }

    @Test
    fun `a PendingIntent is mutable from Android 12 only`() {
        assertEquals(0, PlatformApi(30).mutablePendingIntentFlag)
        assertEquals(PendingIntent.FLAG_MUTABLE, PlatformApi(31).mutablePendingIntentFlag)
        assertEquals(PendingIntent.FLAG_MUTABLE, PlatformApi(33).mutablePendingIntentFlag)
    }

    @Test
    fun `the apiLevel is the one given`() {
        assertEquals(29, PlatformApi(29).apiLevel)
    }
}

/** [Platform] reads the real `Build.VERSION`, and the Intent shim returns the extra. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlatformOnDeviceTest {
    @Test
    fun `Platform answers for the running Android`() {
        assertEquals(Build.VERSION.SDK_INT, Platform.apiLevel)
        assertEquals(34, Platform.apiLevel)
        assertEquals(PendingIntent.FLAG_MUTABLE, Platform.mutablePendingIntentFlag)
        assertEquals(Build.SUPPORTED_ABIS.toList(), Platform.supportedAbis)
    }

    @Test
    fun `intentExtra returns the nested Intent`() {
        val inner = Intent("test.INNER")
        val outer = Intent("test.OUTER").putExtra(Intent.EXTRA_INTENT, inner)
        assertEquals("test.INNER", Platform.intentExtra(outer, Intent.EXTRA_INTENT)?.action)
        assertEquals(null, Platform.intentExtra(Intent("test.EMPTY"), Intent.EXTRA_INTENT))
    }
}
