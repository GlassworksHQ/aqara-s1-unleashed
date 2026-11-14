package sh.edm.s1kiosk.sendspin

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.edm.s1kiosk.sendspin.SendspinSession.Phase
import sh.edm.s1kiosk.sendspin.SendspinSession.Status

/** The Music page's words (#212): the connection, and the stream's format. */
class MusicPageTest {
    @Test fun theStatusLine() {
        assertEquals("Connected to Music Assistant · 192.0.2.30",
            SendspinDefinition.status(Status(Phase.PLAYING, serverName = "Music Assistant"), "ws://192.0.2.30:8927/sendspin"))
        assertEquals("Connected to Music Assistant", SendspinDefinition.status(Status(Phase.READY), null))
        assertEquals("Waiting for Living room MA to let the panel in", SendspinDefinition.status(Status(Phase.HELD, serverName = "Living room MA"), null))
        assertEquals("Not connected to Music Assistant", SendspinDefinition.status(Status(Phase.CLOSED), "ws://192.0.2.30:8927/sendspin"))
    }

    @Test fun theAudioLine() {
        assertEquals("FLAC · 48 kHz · stereo", SendspinDefinition.audio(Status(Phase.PLAYING, codec = "flac")))
        assertEquals("Not playing", SendspinDefinition.audio(Status(Phase.READY, codec = "flac")))
    }
}
