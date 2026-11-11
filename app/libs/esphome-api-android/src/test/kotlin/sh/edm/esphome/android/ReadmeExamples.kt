package sh.edm.esphome.android

import android.content.Context
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import org.junit.Assert.assertNotNull
import org.junit.Test

/** The README's example, compiled (never run). Keep in sync with README.md. */
@Suppress("unused")
internal object ReadmeExamples {
    fun advertise(context: Context, server: ApiServer, identity: DeviceIdentity) {
        val mdns = EspHomeMdns(context, board = "my-tablet") // board: optional `board` TXT record
        server.start()
        mdns.start(identity, server)
        // Encryption state changed (HA set a key): re-advertise so HA knows a key is now required.
        server.onKeyActivated = { mdns.stop(); mdns.start(identity, server) }
    }
}

class ReadmeExamplesTest {
    /** Compiling ReadmeExamples is the check; this just keeps it referenced from a test. */
    @Test
    fun readmeExampleCompiles() {
        assertNotNull(ReadmeExamples::class)
    }
}
