package sh.edm.s1kiosk

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KioskForegroundTest {
    private val both = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test fun locationTypeOnlyWhenLocationIsGranted() {
        assertEquals(both, KioskForeground.types(locationGranted = true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, KioskForeground.types(locationGranted = false))
    }

    @Test fun aGrantedStartRunsOnceWithBothTypes() {
        val calls = mutableListOf<Int>()
        assertEquals(both, KioskForeground.start(true) { calls += it })
        assertEquals(listOf(both), calls)
    }

    @Test fun anAndroid14RefusalOfTheLocationTypeStartsAgainWithoutIt() {
        val calls = mutableListOf<Int>()
        val ran = KioskForeground.start(true) {
            calls += it
            if (it and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) throw SecurityException("location FGS from the background")
        }
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ran)
        assertEquals(listOf(both, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE), calls)
    }

    @Test fun aRefusalWithoutTheLocationTypeIsNotHidden() {
        assertThrows(SecurityException::class.java) { KioskForeground.start(false) { throw SecurityException("microphone") } }
    }

    /** Android 10 throws when startForeground passes a type the manifest does not declare. */
    @Test fun theManifestDeclaresEveryTypeTheServiceStartsWith() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android:name="\.KioskService"[^>]*android:foregroundServiceType="([^"]+)"""")
            .find(manifest)!!.groupValues[1].split("|").toSet()
        assertEquals(setOf("microphone", "location"), declared)
        assertTrue(manifest.contains("android.permission.FOREGROUND_SERVICE_LOCATION"))
    }
}
