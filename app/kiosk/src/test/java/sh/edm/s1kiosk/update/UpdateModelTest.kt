package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update entity's state machine. */
class UpdateModelTest {

    private fun offer(v: String, notes: String = "* new things") = UpdateOffer(
        version = AppVersion.parse(v)!!, tag = "v$v", notes = notes,
        releaseUrl = "https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v$v",
        apk = ReleaseAsset("s1-kiosk-$v.apk", "https://github.com/o/r/releases/download/v$v/s1-kiosk-$v.apk", 100),
        sha256 = ReleaseAsset("s1-kiosk-$v.apk.sha256", "https://github.com/o/r/releases/download/v$v/s1-kiosk-$v.apk.sha256", 85),
    )

    @Test fun missingStateUntilTheFirstSuccessfulCheck() {
        val m = UpdateModel("1.2.0")
        var v = m.view()
        assertTrue(v.missingState)
        assertEquals("1.2.0", v.currentVersion)
        assertEquals("S1 Kiosk", v.title)
        assertEquals("", v.latestVersion)
        assertEquals("not checked yet", m.statusText())

        m.onCheckFailed("network: UnknownHostException")
        assertTrue("a failed check is still no state", m.view().missingState)
        assertTrue(m.statusText().contains("UnknownHostException"))

        m.onChecked(offer("1.3.0"))
        v = m.view()
        assertFalse(v.missingState)
        assertEquals("1.3.0", v.latestVersion)
        assertEquals("* new things", v.releaseSummary) // the check error is gone
        assertEquals("https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v1.3.0", v.releaseUrl)
        assertEquals("1.3.0 available", m.statusText())
    }

    @Test fun upToDateAndNoRelease() {
        val m = UpdateModel("1.3.0")
        m.onChecked(offer("1.3.0"))
        assertEquals("1.3.0", m.view().latestVersion)
        assertFalse(m.updateAvailable)
        assertEquals("", m.view().releaseSummary)
        assertEquals("up to date (1.3.0)", m.statusText())
        assertNull("nothing to install", m.beginInstall(0))

        // A local build ahead of the latest release: HA shows "up to date" (latest < installed).
        val ahead = UpdateModel("2.0.0").apply { onChecked(offer("1.3.0")) }
        assertFalse(ahead.updateAvailable)
        assertEquals("1.3.0", ahead.view().latestVersion)

        // No usable release published: latest = installed.
        val none = UpdateModel("0.1.0").apply { onChecked(null) }
        assertFalse(none.view().missingState)
        assertEquals("0.1.0", none.view().latestVersion)
    }

    @Test fun installProgressThenFailure() {
        val m = UpdateModel("1.2.0")
        m.onChecked(offer("1.3.0"))
        val a = m.beginInstall(1_000)
        assertNotNull(a)
        assertNull("only one install at a time", m.beginInstall(1_001))
        var v = m.view()
        assertTrue(v.inProgress)
        assertTrue(v.hasProgress)
        assertEquals(0f, v.progress)

        assertTrue(m.onProgress(a!!, 0.421))
        assertFalse("same whole percent: nothing to publish", m.onProgress(a, 0.4215))
        assertEquals(42f, m.view().progress)
        assertEquals("Downloading 1.3.0: 42%", m.statusText())

        m.onVerifying(a, 2_000)
        v = m.view()
        assertTrue(v.inProgress)
        assertFalse("verifying/installing: indeterminate", v.hasProgress)
        m.onInstalling(a, 3_000, "Installing 1.3.0 (root update service)")
        assertEquals("Installing 1.3.0 (root update service)", m.statusText())

        assertTrue(m.onFailed(a, "root update service: pm install failed"))
        v = m.view()
        assertFalse("never stuck in progress", v.inProgress)
        assertFalse(v.hasProgress)
        assertTrue(v.releaseSummary.startsWith("Install of 1.3.0 failed: root update service: pm install failed"))
        assertTrue(v.releaseSummary.endsWith("* new things"))
        assertEquals(UpdateModel.Phase.IDLE, m.currentPhase)

        // Late callbacks of the dead attempt change nothing.
        assertFalse(m.onProgress(a, 0.9))
        m.onInstalling(a, 4_000, "zombie")
        assertFalse(m.view().inProgress)
        assertFalse(m.onFailed(a, "again"))

        // A later check keeps the install error visible; a retry is possible.
        m.onCheckFailed("HTTP 500")
        assertTrue(m.statusText().startsWith("Install of 1.3.0 failed"))
        val b = m.beginInstall(5_000)
        assertNotNull(b)
        assertEquals("Downloading 1.3.0: 0%", m.statusText())
    }

    @Test fun stuckPhasesExpire() {
        val m = UpdateModel("1.2.0")
        m.onChecked(offer("1.3.0"))
        val a = m.beginInstall(0)!!
        assertNull(m.expire(UpdateModel.DOWNLOAD_TIMEOUT_MS - 1))
        m.onInstalling(a, 10_000, "Installing 1.3.0: confirm on the panel's screen")
        assertNull(m.expire(10_000 + UpdateModel.INSTALL_TIMEOUT_MS - 1))
        assertEquals("timed out while installing", m.expire(10_000 + UpdateModel.INSTALL_TIMEOUT_MS))
        assertFalse(m.view().inProgress)
        assertFalse(m.isActive(a))
        assertNull("nothing left to expire", m.expire(Long.MAX_VALUE))

        val d = UpdateModel("1.2.0").apply { onChecked(offer("1.3.0")) }
        d.beginInstall(0)
        assertEquals("timed out while downloading", d.expire(UpdateModel.DOWNLOAD_TIMEOUT_MS))
    }

    @Test fun packageInstallerFailureEndsWhicheverAttemptRuns() {
        val m = UpdateModel("1.2.0")
        m.onChecked(offer("1.3.0"))
        assertFalse(m.failActive("cancelled"))
        m.beginInstall(0)
        assertTrue(m.failActive("package installer: cancelled"))
        assertFalse(m.view().inProgress)
        assertTrue(m.statusText().contains("cancelled"))
    }

    @Test fun installNoteOnlyWhileAnUpdateWaits() {
        val m = UpdateModel("1.2.0")
        m.installNote = UpdateManager.NO_HELPER_NOTE
        m.onChecked(offer("1.3.0", notes = ""))
        assertEquals(UpdateManager.NO_HELPER_NOTE, m.view().releaseSummary)
        val a = m.beginInstall(0)!!
        assertEquals("Downloading 1.3.0", m.view().releaseSummary)
        m.onFailed(a, "x")
        assertTrue(m.view().releaseSummary.startsWith("Install of 1.3.0 failed"))

        val upToDate = UpdateModel("1.3.0").apply { installNote = "note"; onChecked(offer("1.3.0")) }
        assertEquals("", upToDate.view().releaseSummary)
    }

    @Test fun previousInstallResultIsShown() {
        val m = UpdateModel("1.3.0")
        m.onInfo("Last update: 10200 -> 10300")
        m.onChecked(offer("1.3.0"))
        assertEquals("up to date (1.3.0); Last update: 10200 -> 10300", m.statusText())
    }
}
