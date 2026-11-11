package sh.edm.improv

import android.util.Log
import java.io.InputStream
import java.io.OutputStream

/**
 * Improv Serial over adb: Wi-Fi provisioning from a computer or a web page over one USB cable, with
 * no Bluetooth and no UART. Listens on the abstract socket `@<socketName>` ([AdbSocketServer], which
 * only accepts adb), and runs one [ImprovSerialSession] per connection.
 *
 * Clients: the `improv-serial` TypeScript package over WebUSB adb (`localabstract:<socketName>`),
 * or anything after `adb forward tcp:7000 localabstract:<socketName>`.
 *
 * USB adb access (an authorized adb key) is the physical authorization, so there is no separate
 * authorization step: the session starts Ready (or Provisioned when already online).
 *
 * @param effects Called once per connection with that connection's output; [ImprovSerialSession.Effects.send]
 *   should write to it (synchronized, flushed).
 */
class ImprovAdbServer(
    socketName: String,
    private val logTag: String = "ImprovAdb",
    private val effects: (output: OutputStream) -> ImprovSerialSession.Effects,
) {
    private val server = AdbSocketServer(socketName, logTag, ::serve)

    fun start() = server.start()

    fun stop() = server.stop()

    private fun serve(input: InputStream, output: OutputStream) {
        val session = ImprovSerialSession(effects(output)) { Log.i(logTag, it) }
        val buf = ByteArray(512)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) session.onBytes(buf, n)
        }
    }
}
