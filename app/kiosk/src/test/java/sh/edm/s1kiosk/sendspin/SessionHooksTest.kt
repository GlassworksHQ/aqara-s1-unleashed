package sh.edm.s1kiosk.sendspin

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import sh.edm.s1kiosk.KioskPrefs

/** A session that a newer one has replaced can't drive the player any more (late reader after stop and start). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SessionHooksTest {

    private val app = RuntimeEnvironment.getApplication()
    private val player = SendspinPlayer(app, KioskPrefs(app), "02:00:00:00:00:01")

    @Test fun aReplacedSessionsLateCallsAreDropped() {
        val old = player.hooksFor(player.nextSessionGen())
        val current = player.hooksFor(player.nextSessionGen())

        old.onStatus(SendspinSession.Status(SendspinSession.Phase.PLAYING))
        assertEquals("the old session's status is ignored", SendspinSession.Phase.CLOSED, player.status.phase)

        current.onStatus(SendspinSession.Status(SendspinSession.Phase.READY))
        assertEquals(SendspinSession.Phase.READY, player.status.phase)
    }
}
