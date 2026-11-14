/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #260: every device is its own ESPHome node, named after what it is, as `name_add_mac_suffix` does. */
class DeviceNameTest {
    private val hostname = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")

    private fun generic(model: String, mac: String = "02:00:00:a1:b2:c3") = DeviceName.node("generic-android", null, model, mac)!!

    @Test fun aKnownFrameIsItsIdAndItsName() {
        assertEquals("aqara-s1-plus-a1b2c3", DeviceName.node("aqara-s1-plus", "Aqara S1 Plus", "QUAD-CORE R818 EVB2", "02:00:00:A1:B2:C3"))
        assertEquals("Aqara S1 Plus", DeviceName.friendly("Aqara S1 Plus", "QUAD-CORE R818 EVB2"))
    }

    @Test fun aFrameForManyDevicesGoesByTheModel() {
        assertEquals("pixel-tablet-a1b2c3", generic("Pixel Tablet"))
        assertEquals("Pixel Tablet", DeviceName.friendly(null, "Pixel Tablet"))
    }

    @Test fun twoDevicesGetTwoNames() {
        assertNotEquals(generic("Pixel Tablet", "02:00:00:a1:b2:c3"), generic("Pixel Tablet", "02:00:00:a1:b2:c4"))
        assertEquals(
            "the suffix is the MAC's last three bytes, lowercase",
            listOf("aqara-s1-plus-445566", "aqara-s1-plus-ddeeff"),
            listOf("02:11:22:44:55:66", "AA:BB:CC:DD:EE:FF").map { DeviceName.node("aqara-s1-plus", "Aqara S1 Plus", "x", it) },
        )
    }

    @Test fun anOddModelBecomesAHostname() {
        assertEquals("pixel-tablet-2023-a1b2c3", generic("Pixel Tablet (2023)"))
        assertEquals("galaxy-tab-a-a1b2c3", generic("  Galaxy Tab Ä  "))
        assertEquals("sm-x710-a1b2c3", generic("SM_X710"))
        assertEquals("nothing usable is still a name", "android-a1b2c3", generic("平板电脑"))
        assertEquals("Android", DeviceName.friendly(null, "  "))
    }

    @Test fun aLongModelIsCutTo63Characters() {
        assertEquals("a".repeat(56) + "-a1b2c3", generic("a".repeat(80)))
        assertEquals("a cut that ends on a dash drops it", "a".repeat(55) + "-a1b2c3", generic("a".repeat(55) + " tail"))
    }

    @Test fun everyNameIsAValidHostname() {
        for (m in listOf("Pixel Tablet", "Pixel Tablet (2023)", "-Lead-", "a".repeat(80), "!!!", "Ünïcödé Pad", "K-") ) {
            val n = generic(m)
            assertTrue("$m -> $n", hostname.matches(n) && n.length <= 63)
        }
    }

    @Test fun noMacNoName() {
        assertNull(DeviceName.node("aqara-s1-plus", "Aqara S1 Plus", "x", ""))
        assertNull(DeviceName.node("aqara-s1-plus", "Aqara S1 Plus", "x", "02:00:00:a1:b2"))
    }
}
