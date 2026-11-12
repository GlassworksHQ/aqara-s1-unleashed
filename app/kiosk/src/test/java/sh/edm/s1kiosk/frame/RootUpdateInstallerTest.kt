package sh.edm.s1kiosk.frame

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.radio.SysProps

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RootUpdateInstallerTest {
    /** The boot image's side: `ctl.start s1kiosk_update` moves the status to [afterStart]. */
    private class Props(val afterStart: String? = null) : SysProps {
        val p = HashMap<String, String>()
        val sets = ArrayList<Pair<String, String>>()
        var clock = 0L
        override fun get(name: String) = p[name] ?: ""
        override fun set(name: String, value: String) {
            sets += name to value
            p[name] = value
            if (name == "ctl.start" && afterStart != null) p["sys.s1kiosk.update.status"] = afterStart
        }
        override fun sleep(ms: Long) { clock += ms }
    }

    private fun installer(props: Props) = RootUpdateInstaller(props, now = { props.clock })

    @Test fun presentOnlyWithServiceVersionOneOrMore() {
        val props = Props()
        assertFalse(installer(props).present())
        props.p["sys.s1kiosk.update.version"] = "0"
        assertFalse(installer(props).present())
        props.p["sys.s1kiosk.update.version"] = "1"
        assertTrue(installer(props).present())
    }

    @Test fun onlyAFailedLastInstallIsReported() {
        val props = Props()
        assertNull(installer(props).lastFailure())
        props.p["sys.s1kiosk.update.status"] = "ok: sh.edm.s1kiosk 10200 -> 10300"
        assertNull(installer(props).lastFailure())
        props.p["sys.s1kiosk.update.status"] = "error: pending.apk is not owned by the kiosk"
        assertEquals("pending.apk is not owned by the kiosk", installer(props).lastFailure())
    }

    @Test fun installStartsTheServiceAndReportsItsError() {
        val props = Props(afterStart = "error: signature mismatch")
        val lines = ArrayList<String>()
        val failure = installer(props).install("1.2.3", installing = { lines += it }, over = { false })
        assertEquals("root update service: signature mismatch", failure)
        assertEquals(
            listOf("sys.s1kiosk.update.status" to "requested", "ctl.start" to "s1kiosk_update"),
            props.sets,
        )
        assertEquals(listOf("Installing 1.2.3 (root update service)"), lines)
    }

    @Test fun anOkStatusSaysRestarting() {
        val props = Props(afterStart = "ok: sh.edm.s1kiosk 10200 -> 10300")
        val lines = ArrayList<String>()
        assertNull(installer(props).install("1.2.3", installing = { lines += it }, over = { false }))
        assertEquals(listOf("Installing 1.2.3 (root update service)", "Installed 1.2.3; restarting"), lines)
    }

    @Test fun aServiceThatNeverStartsFailsAfterThirtySeconds() {
        val props = Props()
        val failure = installer(props).install("1.2.3", installing = {}, over = { false })
        assertEquals("the root update service did not start (s1kiosk_update)", failure)
        assertTrue(props.clock in 30_001L..32_000L)
    }

    @Test fun anAttemptThatEndsStopsFollowingWithoutAFailure() {
        val props = Props(afterStart = "running: installing")
        assertNull(installer(props).install("1.2.3", installing = {}, over = { props.clock >= 5_000 }))
    }
}
