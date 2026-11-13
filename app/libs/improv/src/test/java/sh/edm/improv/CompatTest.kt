package sh.edm.improv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every capability switches at exactly its API level, so a wrong boundary fails here. */
class CompatTest {

    private fun row(level: Int) = CompatApi(level).run {
        listOf(hasAdvertisingSets, hasIsLocationEnabled, hasNetworkSuggestions, isAndroid10, hasSecurityParams, hasBluetoothRuntimePermissions, appsCanEnableBluetooth, hasValueNotify)
    }

    @Test fun eachCapabilitySwitchesAtItsLevel() {
        val expected = mapOf(
            23 to listOf(false, false, false, false, false, false, true, false),
            25 to listOf(false, false, false, false, false, false, true, false),
            26 to listOf(true, false, false, false, false, false, true, false),
            27 to listOf(true, false, false, false, false, false, true, false),
            28 to listOf(true, true, false, false, false, false, true, false),
            29 to listOf(true, true, true, true, false, false, true, false),
            30 to listOf(true, true, true, false, true, false, true, false),
            31 to listOf(true, true, true, false, true, true, true, false),
            32 to listOf(true, true, true, false, true, true, true, false),
            33 to listOf(true, true, true, false, true, true, false, true),
            34 to listOf(true, true, true, false, true, true, false, true),
        )
        for ((level, want) in expected) assertEquals("API $level", want, row(level))
    }
}
