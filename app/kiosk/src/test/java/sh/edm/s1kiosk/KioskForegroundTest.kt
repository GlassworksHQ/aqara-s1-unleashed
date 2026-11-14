package sh.edm.s1kiosk

import android.content.pm.ServiceInfo
import dev.glassworks.platform.PlatformApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class KioskForegroundTest {
    private val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
    private val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    private val android10 = PlatformApi(29)
    private val android14 = PlatformApi(34)

    /** A fake `startForeground` that refuses, as Android 14 does, any type in [refuse]. */
    private class Starts(var refuse: Int = 0) {
        val calls = mutableListOf<Int>()
        val refused = mutableListOf<Pair<Int, RuntimeException>>()
        fun foreground(platform: PlatformApi) = KioskForeground(platform, { t, e -> refused += t to e }) {
            calls += it
            if (it and refuse != 0) throw SecurityException("refused $it")
        }
    }

    @Test fun theS1StartsOnceWithMicrophoneAndLocation() {
        val s = Starts()
        assertEquals(136, s.foreground(android10).promote(micGranted = true, locationGranted = true))
        assertEquals(listOf(136), s.calls)
    }

    @Test fun belowAndroid14TheMicrophoneTypeDoesNotWaitForItsGrant() {
        assertEquals(mic or location, Starts().foreground(android10).promote(micGranted = false, locationGranted = true))
        assertEquals(mic, Starts().foreground(PlatformApi(33)).promote(micGranted = false, locationGranted = false))
    }

    @Test fun android14TakesEachTypeOnlyWithItsGrant() {
        assertEquals(mic or location, Starts().foreground(android14).promote(micGranted = true, locationGranted = true))
        assertEquals(mic, Starts().foreground(android14).promote(micGranted = true, locationGranted = false))
        assertEquals(location, Starts().foreground(android14).promote(micGranted = false, locationGranted = true))
    }

    /** Android 14 refuses a service with no type at all (#93: a fresh install crash-looped). */
    @Test fun android14WithNoGrantRunsAsAConnectedDeviceOnce() {
        val s = Starts()
        assertEquals(device, s.foreground(android14).promote(micGranted = false, locationGranted = false))
        assertEquals(listOf(device), s.calls)
    }

    @Test fun aGrantAtRuntimeStartsAgainWithTheNewType() {
        val s = Starts()
        val fg = s.foreground(android14)
        fg.promote(micGranted = false, locationGranted = false)
        assertEquals(mic, fg.promote(micGranted = true, locationGranted = false))
        assertEquals(mic, fg.types)
        assertEquals(listOf(device, mic), s.calls)
    }

    @Test fun anUnchangedGrantDoesNotStartAgain() {
        val s = Starts()
        val fg = s.foreground(android10)
        fg.promote(micGranted = true, locationGranted = true)
        fg.promote(micGranted = true, locationGranted = true)
        assertEquals(listOf(136), s.calls)
    }

    @Test fun aRefusedLocationStartsAgainWithoutIt() {
        val s = Starts(refuse = location)
        assertEquals(mic, s.foreground(android14).promote(micGranted = true, locationGranted = true))
        assertEquals(listOf(mic or location, mic), s.calls)
    }

    /** #102 item 3: AOSP checks the microphone first, so its refusal is the one to log. */
    @Test fun aRefusedMicrophoneIsLoggedAndLocationStillRuns() {
        val s = Starts(refuse = mic)
        assertEquals(location, s.foreground(android14).promote(micGranted = true, locationGranted = true))
        assertEquals(listOf(mic or location, mic, location), s.calls)
        assertEquals(listOf(mic or location, mic), s.refused.map { it.first })
        assertEquals("refused ${mic or location}", s.refused.first().second.message)
    }

    @Test fun whenEveryTypeIsRefusedTheLastRefusalIsThrownAndEveryOneLogged() {
        val s = Starts(refuse = mic or location or device)
        val e = assertThrows(SecurityException::class.java) {
            s.foreground(android14).promote(micGranted = true, locationGranted = true)
        }
        assertEquals(listOf(mic or location, mic, location, device), s.calls)
        assertEquals(s.calls, s.refused.map { it.first })
        assertSame(s.refused.last().second, e)
    }

    @Test fun aRefusedRepromoteKeepsTheRunningTypesAndTriesAgainNextTime() {
        val s = Starts()
        val fg = s.foreground(android14)
        fg.promote(micGranted = true, locationGranted = false)
        s.refuse = location
        assertEquals(mic, fg.promote(micGranted = true, locationGranted = true))
        s.refuse = 0
        assertEquals(mic or location, fg.promote(micGranted = true, locationGranted = true))
    }

    @Test fun aRepromoteRefusedForEveryTypeKeepsTheServiceRunning() {
        val s = Starts()
        val fg = s.foreground(android14)
        fg.promote(micGranted = false, locationGranted = false)
        s.refuse = mic or location or device
        assertEquals(device, fg.promote(micGranted = true, locationGranted = true))
        assertEquals(device, fg.types)
        assertEquals(listOf(mic or location, mic, location, device), s.refused.map { it.first })
    }

    /** Only refusals fall back. Another exception is a bug, and it still throws on the first try. */
    @Test fun anUnrelatedExceptionIsNotTakenForARefusal() {
        val s = Starts()
        val fg = KioskForeground(android14, { t, e -> s.refused += t to e }) { s.calls += it; throw IllegalArgumentException("not a subset of the manifest") }
        assertThrows(IllegalArgumentException::class.java) { fg.promote(micGranted = true, locationGranted = true) }
        assertEquals(listOf(mic or location), s.calls)
        assertTrue(s.refused.isEmpty())
    }

    @Test fun theLogNamesEachType() {
        assertEquals("microphone, location", KioskForeground.names(136))
        assertEquals("connectedDevice", KioskForeground.names(device))
        assertEquals("microphone", KioskForeground.names(mic))
    }

    @Test fun theCameraTypeComesOnlyWithTheCameraAllowed() {
        val camera = KioskForeground.CAMERA
        assertEquals(mic or location, Starts().foreground(android10).promote(micGranted = true, locationGranted = true))
        assertEquals(mic or location or camera, Starts().foreground(android10).promote(micGranted = true, locationGranted = true, cameraGranted = true))
        assertEquals("microphone, location, camera", KioskForeground.names(mic or location or camera))
    }

    @Test fun aRefusedCameraTypeIsDroppedFirst() {
        val camera = KioskForeground.CAMERA
        val s = Starts(refuse = camera)
        assertEquals(mic or location, s.foreground(android10).promote(micGranted = true, locationGranted = true, cameraGranted = true))
        assertEquals(listOf(mic or location or camera, mic or location), s.calls)
    }

    @Test fun twoRefusedTypesStillLeaveTheThird() {
        val camera = KioskForeground.CAMERA
        val s = Starts(refuse = mic or camera)
        assertEquals(location, s.foreground(android14).promote(micGranted = true, locationGranted = true, cameraGranted = true))
        assertEquals(listOf(mic or location or camera, mic or location, mic or camera, location or camera, mic, location), s.calls)
    }

    /** Android 10 throws when startForeground passes a type the manifest does not declare. */
    @Test fun theManifestDeclaresEveryTypeTheServiceStartsWith() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val declared = Regex("""android:name="\.KioskService"[^>]*android:foregroundServiceType="([^"]+)"""")
            .find(manifest)!!.groupValues[1].split("|").toSet()
        assertEquals(setOf("microphone", "location", "connectedDevice", "camera"), declared)
        for (p in listOf("FOREGROUND_SERVICE_MICROPHONE", "FOREGROUND_SERVICE_LOCATION", "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_CAMERA", "CHANGE_WIFI_STATE")) {
            assertTrue(p, manifest.contains("android.permission.$p\""))
        }
    }
}
