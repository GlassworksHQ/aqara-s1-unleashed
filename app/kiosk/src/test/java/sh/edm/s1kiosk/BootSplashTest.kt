package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.BootSplash.Home
import sh.edm.s1kiosk.BootSplash.Step
import java.io.File

class BootSplashTest {
    private val pkg = "sh.edm.s1kiosk"
    private val main = "sh.edm.s1kiosk.MainActivity"
    private val splash = "sh.edm.s1kiosk.BootSplashActivity"
    private val kiosk = Home(pkg, main, 0)
    private val ourSplash = Home(pkg, splash, BootSplash.PRIORITY)
    private val fallback = Home("com.lumi.systemmanager", "com.lumi.systemmanager.activity.FallbackHome", -1000)
    private val launcher = Home("com.aqara.inhome.launcher", "com.aqara.inhome.launcher.MainActivity", 0)
    private val factory = Home("com.lumi.factory", "com.lumi.factory.activity.MainActivity", 0)
    private val chooser = Home("android", "com.android.internal.app.ResolverActivity", 0)

    @Test
    fun waitsWhileLocked() {
        assertEquals(Step.WAIT, BootSplash.step(false, kiosk, pkg, main))
        assertEquals(Step.WAIT, BootSplash.step(false, ourSplash, pkg, main))
        assertEquals(Step.WAIT, BootSplash.step(false, null, pkg, main))
    }

    @Test
    fun opensTheKioskOnceUnlockedAndItIsHome() =
        assertEquals(Step.OPEN_KIOSK, BootSplash.step(true, kiosk, pkg, main))

    @Test
    fun retriesWhenAndroidCantResolveHomeYet() =
        assertEquals(Step.WAIT, BootSplash.step(true, null, pkg, main))

    @Test
    fun neverStaysHomeAfterUnlock() {
        // someone else's HOME, the chooser, or (MainActivity gone) the splash itself: leave, no loop
        listOf(launcher, chooser, fallback, ourSplash).forEach {
            assertEquals(it.cls, Step.LEAVE, BootSplash.step(true, it, pkg, main))
        }
    }

    @Test
    fun enabledOnADebloatedPanel() {
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, fallback), pkg, main))
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, ourSplash, fallback), pkg, main)) // already on
    }

    @Test
    fun notWithAnotherPriorityZeroHome() {
        // Aqara's launcher or factory app still enabled: they tie with MainActivity, the preferred HOME
        // decides, and adding a HOME component would make Android drop it.
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, launcher, fallback), pkg, main))
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, factory, fallback), pkg, main))
        assertFalse(BootSplash.safeToEnable(listOf(kiosk, Home("x", "x.Home", BootSplash.PRIORITY), fallback), pkg, main))
        assertTrue(BootSplash.safeToEnable(listOf(kiosk, Home("x", "x.Home", BootSplash.PRIORITY - 1), fallback), pkg, main))
    }

    @Test
    fun notWithoutTheKioskAsHome() =
        assertFalse(BootSplash.safeToEnable(listOf(ourSplash, fallback, launcher), pkg, main))

    @Test
    fun priorityBetweenFallbackHomeAndMainActivity() {
        assertTrue(BootSplash.PRIORITY > fallback.priority)
        assertTrue(BootSplash.PRIORITY < 0) // a non-system app's priority is capped at 0: it would tie with MainActivity
    }

    @Test
    fun manifestMatches() {
        val m = File("src/main/AndroidManifest.xml").readText()
        val block = m.substringAfter("android:name=\".BootSplashActivity\"").substringBefore("</activity>")
        assertTrue("splash ships disabled", "android:enabled=\"false\"" in block)
        assertTrue("direct-boot aware", "android:directBootAware=\"true\"" in block)
        assertTrue("own process", "android:process=\":boot\"" in block)
        assertTrue("HOME filter priority", "android:priority=\"${BootSplash.PRIORITY}\"" in block)
        assertTrue("HOME filter", "android.intent.category.HOME" in block && "android.intent.category.DEFAULT" in block)
        val main = m.substringAfter("android:name=\".MainActivity\"").substringBefore("</activity>")
        assertFalse("MainActivity stays credential-encrypted only", "directBootAware" in main)
        assertFalse("MainActivity HOME keeps the default priority 0", "android:priority" in main)
    }
}
