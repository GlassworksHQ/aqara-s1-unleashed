package sh.edm.s1kiosk.screen

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins how the panel's screen behaves: the VI5300 range table, presence, wake on approach, idle off,
 * the night window and its timeout, and the brightness curve. Issue #19 moves this code into a generic
 * policy with the S1's sensors in its frame; these numbers must not move with it. Only
 * [ScreenPinHarness] follows the code's API.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScreenBehaviourPinTest {
    private val h = ScreenPinHarness()
    private val prefs = h.prefs

    @After fun tearDown() = h.close()

    // ---- presence range (Lumi's proximity_threshold table)

    @Test fun presenceRangeMapsToLumiThresholdIndex() {
        val expected = linkedMapOf(
            0.05f to 0, 0.1f to 1, 0.2f to 2, 0.3f to 2, 0.4f to 3, 0.5f to 3, 0.6f to 4, 0.7f to 4,
            0.8f to 5, 0.9f to 5, 1.0f to 6, 1.1f to 6, 1.2f to 7, 1.3f to 7, 1.4f to 8, 1.5f to 8,
            1.6f to 8, 1.7f to 8, 1.8f to 9, 1.9f to 9, 2.0f to 9, 2.1f to 9, 2.2f to 9, 2.3f to 10,
            2.4f to 10, 2.5f to 10, 3.0f to 10,
        )
        h.start()
        val actual = expected.keys.associateWith { m ->
            prefs.presenceRangeM = m
            h.applyPresenceRange()
            h.proximityThreshold
        }
        assertEquals(expected, actual)
    }

    @Test fun presenceRangeZeroLeavesTheFirmwareSettingAlone() {
        prefs.presenceRangeM = 0f
        h.start()
        h.applyPresenceRange()
        assertNull(h.proximityThreshold)
    }

    @Test fun startAppliesTheSavedPresenceRange() {
        prefs.presenceRangeM = 1.5f
        h.start()
        assertEquals(8, h.proximityThreshold)
    }

    // ---- presence

    @Test fun onlyRawOneIsNear() {
        h.start()
        h.proximity(1f)
        assertTrue(h.near)
        assertEquals(1f, h.proximityRaw)
        h.proximity(5f)
        assertFalse(h.near)
        assertEquals(5f, h.proximityRaw)
        h.proximity(0f)
        assertFalse(h.near)
        assertEquals(0f, h.proximityRaw)
        h.proximity(0.99f)
        assertFalse(h.near)
    }

    @Test fun presenceNotifiesOnlyWhenItChanges() {
        h.start()
        h.proximity(5f)
        assertEquals(0, h.changes)
        h.proximity(1f)
        h.proximity(1f)
        assertEquals(1, h.changes)
        h.proximity(8f)
        h.proximity(9f)
        assertEquals(2, h.changes)
    }

    @Test fun approachWakesTheScreenWhenItIsOff() {
        h.start(screenOn = false)
        h.proximity(1f)
        assertTrue(h.wokeScreen)
    }

    @Test fun approachDoesNotWakeWhenWakeOnApproachIsOff() {
        prefs.wakeOnApproach = false
        h.start(screenOn = false)
        h.proximity(1f)
        assertTrue(h.near)
        assertFalse(h.wokeScreen)
    }

    @Test fun approachWithTheScreenOnTakesNoWakeLock() {
        h.start()
        h.proximity(1f)
        assertFalse(h.wokeScreen)
    }

    // ---- idle off

    @Test fun dayIdleTimeoutIs120sByDefault() {
        h.setClock(12, 0)
        h.start()
        h.userActivity()
        h.advance(119_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false), h.keepOnCalls)
    }

    @Test fun nightIdleTimeoutIs20sByDefault() {
        h.setClock(23, 0)
        h.start()
        h.userActivity()
        h.advance(19_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false), h.keepOnCalls)
    }

    @Test fun configuredTimeoutsAreUsed() {
        prefs.idleOffSec = 30
        prefs.nightIdleOffSec = 5
        h.setClock(12, 0)
        h.start()
        h.userActivity()
        h.advance(29_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false), h.keepOnCalls)
    }

    @Test fun zeroTimeoutNeverTurnsTheScreenOff() {
        prefs.idleOffSec = 0
        h.setClock(12, 0)
        h.start()
        h.advance(3_600_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
    }

    @Test fun alwaysModeNeverTurnsTheScreenOff() {
        prefs.screenMode = "always"
        h.setClock(12, 0)
        h.start()
        h.advance(3_600_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
    }

    @Test fun someoneNearKeepsTheScreenOnThenTheTimeoutStartsWhenTheyLeave() {
        h.setClock(12, 0)
        h.start()
        h.proximity(1f)
        h.advance(600_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.proximity(5f)
        h.advance(119_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false), h.keepOnCalls)
    }

    @Test fun touchAfterIdleOffKeepsTheScreenOnAgain() {
        prefs.idleOffSec = 10
        h.setClock(12, 0)
        h.start()
        h.userActivity()
        h.advance(10_000)
        h.userActivity()
        assertEquals(listOf(false, true), h.keepOnCalls)
        h.advance(9_000)
        assertEquals(listOf(false, true), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false, true, false), h.keepOnCalls)
    }

    @Test fun noIdleCountdownWhileTheScreenIsOffAndAFreshOneWhenItComesOn() {
        prefs.idleOffSec = 30
        h.setClock(12, 0)
        h.start()
        h.screenOff()
        h.advance(200_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        assertFalse(h.screenIsOn)
        h.screenOn()
        assertTrue(h.screenIsOn)
        h.advance(29_000)
        assertEquals(emptyList<Boolean>(), h.keepOnCalls)
        h.advance(1_000)
        assertEquals(listOf(false), h.keepOnCalls)
    }

    @Test fun screenBroadcastsNotify() {
        h.start()
        h.screenOff()
        h.screenOn()
        assertEquals(2, h.changes)
    }

    @Test fun sleepWithoutDeviceAdminReleasesKeepScreenOn() {
        h.start()
        h.sleep("home assistant")
        assertEquals(listOf(false), h.keepOnCalls)
        h.wake("home assistant")
        assertEquals(listOf(false, true), h.keepOnCalls)
    }

    @Test fun wakeWithTheScreenOffTakesAWakeLock() {
        h.start(screenOn = false)
        h.wake("home assistant")
        assertTrue(h.wokeScreen)
    }

    // ---- night window

    @Test fun defaultNightWindowIs22To07() {
        assertEquals("22:00-07:00", prefs.nightWindow)
        h.start()
        assertEquals(
            mapOf("21:59" to false, "22:00" to true, "23:30" to true, "00:00" to true, "06:59" to true, "07:00" to false, "12:00" to false),
            nightAt("21:59", "22:00", "23:30", "00:00", "06:59", "07:00", "12:00"),
        )
    }

    @Test fun nightWindowWithinOneDay() {
        prefs.nightWindow = "08:00-17:00"
        h.start()
        assertEquals(
            mapOf("07:59" to false, "08:00" to true, "16:59" to true, "17:00" to false),
            nightAt("07:59", "08:00", "16:59", "17:00"),
        )
    }

    @Test fun nightWindowEdgeCases() {
        h.start()
        h.setClock(8, 0)
        for ((window, night) in listOf("" to false, "night" to false, "7:00-9:00" to true, "08:00-08:00" to false, "8:0-9:00" to false)) {
            prefs.nightWindow = window
            assertEquals(window, night, h.night)
        }
    }

    private fun nightAt(vararg times: String): Map<String, Boolean> = times.associateWith {
        val (hh, mm) = it.split(":").map(String::toInt)
        h.setClock(hh, mm)
        h.night
    }

    // ---- brightness

    private fun firstBrightnessAt(lux: Float, hour: Int = 12): Float {
        h.setClock(hour, 0)
        h.start()
        h.light(lux)
        return h.brightness
    }

    @Test fun brightnessCurvePoints() {
        val expected = linkedMapOf(-3f to 0.1f, 0f to 0.1f, 9f to 0.4f, 30f to 0.54744f, 99f to 0.7f, 999f to 1.0f, 5000f to 1.0f)
        val actual = expected.keys.associateWith { firstBrightnessAt(it) }
        for ((lux, b) in expected) assertEquals("lux $lux", b, actual.getValue(lux), 1e-4f)
    }

    @Test fun brightnessCurveUsesTheConfiguredRange() {
        prefs.minBrightness = 0.3f
        prefs.maxBrightness = 0.6f
        assertEquals(0.3f, firstBrightnessAt(0f), 1e-4f)
        assertEquals(0.4f, firstBrightnessAt(9f), 1e-4f)
        assertEquals(0.6f, firstBrightnessAt(999f), 1e-4f)
    }

    @Test fun nightCapsBrightness() {
        assertEquals(0.2f, firstBrightnessAt(999f, hour = 23), 1e-4f)
        assertEquals(0.1f, firstBrightnessAt(0f, hour = 23), 1e-4f)
        assertEquals(0.13333f, firstBrightnessAt(9f, hour = 23), 1e-4f)
    }

    @Test fun nightCapAlsoLimitsTheMinimum() {
        prefs.minBrightness = 0.3f
        assertEquals(0.2f, firstBrightnessAt(0f, hour = 23), 1e-4f)
    }

    @Test fun brightnessMovesThirtyPercentTowardTheTarget() {
        h.setClock(12, 0)
        h.start()
        h.light(0f)
        h.light(999f)
        h.light(999f)
        assertEquals(listOf(0.1f, 0.37f, 0.559f), h.brightnessCalls.map { Math.round(it * 1000) / 1000f })
    }

    @Test fun brightnessIgnoresSteps() {
        h.setClock(12, 0)
        h.start()
        h.light(0f)
        h.light(0.2f)
        assertEquals(1, h.brightnessCalls.size)
        assertEquals(0.1f, h.brightness, 1e-6f)
    }

    @Test fun manualBrightnessWhenAutoIsOff() {
        prefs.autoBrightness = false
        prefs.manualBrightness = 0.4f
        h.start()
        assertEquals("applied at start, before any light reading (#100)", 0.4f, h.brightness)
        h.light(500f)
        assertEquals(emptyList<Float>(), h.brightnessCalls)
        prefs.manualBrightness = 0f
        h.refreshBrightness()
        assertEquals(listOf(-1f), h.brightnessCalls)
    }

    @Test fun autoWithoutALightReadingKeepsTheSystemBrightness() {
        h.start()
        h.refreshBrightness()
        assertEquals(emptyList<Float>(), h.brightnessCalls)
        assertEquals(-1f, h.brightness)
    }

    @Test fun manualBrightnessFromHomeAssistantTurnsAutoOffAndIsClamped() {
        h.start()
        h.setManualBrightness(1.5f)
        assertFalse(prefs.autoBrightness)
        assertEquals(1f, prefs.manualBrightness)
        assertEquals(1f, h.brightness)
        h.setManualBrightness(0f)
        assertEquals(0.01f, prefs.manualBrightness)
        assertEquals(0.01f, h.brightness)
    }

    @Test fun luxNotifiesOnFirstReadingAndOnChangesAboveFiveLuxOrTwentyPercent() {
        h.setClock(12, 0)
        h.start()
        h.light(100f)
        assertEquals(100f, h.lux)
        assertEquals(1, h.changes)
        h.light(104f)
        assertEquals(1, h.changes)
        h.light(126f)
        assertEquals(2, h.changes)
        h.light(2f)
        assertEquals(3, h.changes)
        h.light(7.5f)
        assertEquals(4, h.changes)
        h.light(12f)
        assertEquals(4, h.changes)
    }

    // ---- a wake after an idle sleep lasts the idle timeout (#257)

    private fun admin() = shadowOf(h.context.getSystemService(android.app.admin.DevicePolicyManager::class.java))
        .setActiveAdmin(android.content.ComponentName(h.context, sh.edm.s1kiosk.AdminReceiver::class.java))

    private val awake: android.os.PowerManager.WakeLock? get() = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()

    /**
     * #257: the kiosk sleeps on idle with lockNow(), then a wake that isn't a touch (Home Assistant, a
     * notification). Android's own timeout (15 s on the S1) would turn the screen off again; the wake
     * lock holds it until the kiosk's idle timeout, 120 s after the wake.
     */
    @Test fun aWakeAfterALockNowSleepLastsTheIdleTimeout() {
        admin()
        h.setClock(12, 0)
        h.start()
        h.advance(121_000)                       // idle: sleep (lockNow)
        h.screenOff()
        h.wake("home assistant")
        h.screenOn()
        h.advance(30_000)
        assertTrue("still on 30 s after the wake, past Android's own timeout", awake!!.isHeld)
        h.advance(88_000)
        assertTrue("still on at 118 s", awake!!.isHeld)
        h.advance(3_000)
        assertFalse("the kiosk's idle timeout let it go", awake!!.isHeld)
    }

    /** #257: a touch while woken counts as activity: the idle timeout starts again from it. */
    @Test fun aTouchAfterAWakeMovesTheTimeout() {
        h.setClock(12, 0)
        h.start()
        h.wake("home assistant")
        h.advance(100_000)
        h.userActivity()
        h.advance(100_000)
        assertTrue(awake!!.isHeld)
        h.advance(21_000)
        assertFalse(awake!!.isHeld)
    }

    /** #257: the power key (the screen going off without the kiosk) lets go: no fighting it. */
    @Test fun theScreenGoingOffOtherwiseLetsGo() {
        h.setClock(12, 0)
        h.start()
        h.wake("home assistant")
        assertTrue(awake!!.isHeld)
        h.screenOff()
        assertFalse(awake!!.isHeld)
    }

    /** #257: with the idle timeout off ("never"), a wake keeps the screen on past the lock's own safety timeout. */
    @Test fun withNoIdleTimeoutAWakeLastsPastTheLocksOwnTimeout() {
        prefs.idleOffSec = 0
        h.setClock(12, 0)
        h.start()
        h.wake("home assistant")
        h.advance(30 * 60_000L)
        assertTrue(awake!!.isHeld)
    }

    /**
     * #257 with #118's hold (a high or critical notification): after a lockNow sleep, a hold keeps the
     * screen on past Android's timeout and past the idle timeout, for as long as it is held. Released,
     * the idle timeout counts from the release.
     */
    @Test fun aHoldLastsUntilReleasedThenTheIdleTimeoutCountsFromThere() {
        admin()
        h.setClock(12, 0)
        h.start()
        h.advance(121_000)                       // idle: sleep (lockNow)
        h.screenOff()
        h.hold("notification")
        h.screenOn()
        h.advance(30_000)
        assertTrue("on past Android's own timeout", awake!!.isHeld)
        h.advance(300_000)
        assertTrue("and past the idle timeout, while held", awake!!.isHeld)
        h.release("notification")
        h.advance(118_000)
        assertTrue("released: the idle timeout counts from the release", awake!!.isHeld)
        h.advance(3_000)
        assertFalse(awake!!.isHeld)
    }

    /** #257, CodeRabbit on #258: the power key before its broadcast arrives: the renewal doesn't wake the screen again. */
    @Test fun noRenewalWhileTheScreenIsGoingOff() {
        h.setClock(12, 0)
        h.start()
        h.wake("home assistant")
        val held = shadowOf(awake!!).timesHeld
        h.advance(59_000)
        // The power key: not interactive any more, the screen-off broadcast not delivered yet.
        shadowOf(h.context.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(false)
        h.advance(3_000)
        assertEquals("not taken again while going off", held, shadowOf(awake!!).timesHeld)
    }
}
