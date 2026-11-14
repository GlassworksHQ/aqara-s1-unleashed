package sh.edm.bluetoothproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatTest {
    @Test fun scanPermissionsFollowAndroidsRules() {
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), CompatApi(23).scanPermissions)
        assertEquals(listOf("android.permission.ACCESS_COARSE_LOCATION"), CompatApi(28).scanPermissions)
        assertEquals("Android 10 wants FINE", listOf("android.permission.ACCESS_FINE_LOCATION"), CompatApi(29).scanPermissions)
        assertEquals(
            listOf("android.permission.BLUETOOTH_SCAN", "android.permission.ACCESS_FINE_LOCATION"),
            CompatApi(31).scanPermissions,
        )
    }
}
