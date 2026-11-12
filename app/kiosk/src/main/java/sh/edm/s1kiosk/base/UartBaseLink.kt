package sh.edm.s1kiosk.base

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * [BaseLink] that talks to the wall base directly: the UART protocol ([BracketProtocol] /
 * [BracketSession]) on `/dev/ttyS3`, or, for testing, on a TCP connection to
 * `tools/bracket/base_emulator.py` ([transport] = `tcp:host:port`).
 *
 * Pure JVM on purpose (Android bits are injected), so the JVM tests run it against the emulator.
 *
 * Port ownership: `ha_master` holds `/dev/ttyS3` while it runs. Before opening the serial port and
 * every 10 s while it is open, [portGuard] is asked whether the port may be used (WallBasePane
 * answers "no" while init reports ha_master running). A "no" closes the port, reports the link as
 * unavailable and calls [BaseLink.Listener.onBlocked]; the link retries every 5 s.
 *
 * Serial setup: the port is opened with File streams (and kept open, so the settings stick), then
 * configured with `busybox stty -F /dev/ttyS3 [SERIAL_STTY]`, the exact termios bracket_cli
 * (c-periphery serial_open_advanced: 8N1, no XON/XOFF, no RTS/CTS, CLOCAL|CREAD, IGNBRK, raw)
 * and ha_master use, and read back with `stty -g` ([verifyTermios]); a port whose settings don't
 * verify is not used. `/vendor/bin/busybox` comes first because the firmware's toybox 0.8.0
 * `stty` corrupts `c_iflag` when it sets flags (verified on a pty: e.g. `-cstopb` turned on
 * ixon/icrnl/istrip, which would mangle binary frames); reading with it is fine.
 * `min 0 time 1` only affects our own reads: a read returns after 100 ms without data, so the
 * reader thread can notice [stop].
 */
class UartBaseLink(
    private val exec: ScheduledExecutorService,
    private val listener: BaseLink.Listener,
    private val transport: String = SERIAL_PATH,
    private val portGuard: () -> String? = { null },
    private val stty: (path: String, args: List<String>) -> String? = ::configureSerial,
    initialEnergyWh: Float = 0f,
    private val saveEnergy: (Float) -> Unit = {},
    private val log: (level: Int, msg: String) -> Unit = { _, m -> println(m) },
) : BaseLink {

    companion object {
        const val SERIAL_PATH = "/dev/ttyS3"
        const val DEBUG = 3
        const val INFO = 4
        const val WARN = 5
        /** termios of ha_master / bracket_cli (see the class doc and docs/research/base-protocol.md). */
        val SERIAL_STTY = listOf(
            "115200", "raw", "cs8", "-parenb", "-cstopb", "-crtscts", "clocal", "cread", "-hupcl",
            "ignbrk", "-brkint", "-icrnl", "-ixon", "-ixoff", "-opost", "-onlcr", "-isig", "-icanon", "-iexten",
            "-echo", "-echoe", "-echok", "-echoctl", "-echoke", "min", "0", "time", "1",
        )
        private const val RETRY_MS = 5_000L
        private const val GUARD_MS = 10_000L
        private const val ENERGY_SAVE_MS = 5 * 60_000L

        /** stty implementations, best first (see the class doc for why toybox is last). */
        val STTY_TOOLS = listOf(listOf("/vendor/bin/busybox", "stty"), listOf("/system/bin/stty"), listOf("stty"))

        /** Runs a command; exit code and output, or null if it couldn't run. */
        private fun exec(cmd: List<String>): Pair<Int, String>? = try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroy(); null } else p.exitValue() to out
        } catch (_: Exception) {
            null
        }

        /**
         * Applies [args] with the first stty that works and verifies the result with `stty -g`;
         * null on success, else what went wrong with each tool.
         */
        fun configureSerial(path: String, args: List<String>): String? {
            val errors = ArrayList<String>()
            for (tool in STTY_TOOLS) {
                if (tool[0].startsWith("/") && !File(tool[0]).canExecute()) continue
                val set = exec(tool + listOf("-F", path) + args)
                if (set == null) { errors += "${tool.joinToString(" ")}: did not run"; continue }
                // busybox prints a harmless "Inappropriate ioctl" (window size) on some ttys; judge by -g.
                val g = exec(tool + listOf("-F", path, "-g"))
                val err = if (g == null || g.first != 0) "stty -g failed: ${g?.second}" else verifyTermios(g.second)
                if (err == null) return null
                errors += "${tool.joinToString(" ")}: $err (set exit ${set.first} ${set.second.take(120)})"
            }
            return errors.joinToString("; ").ifEmpty { "no stty found" }
        }

        /**
         * Checks `stty -g` output (`iflag:oflag:cflag:lflag:cc...`, hex, as GNU/busybox/toybox
         * print it) against what the protocol needs: 115200 8N1, CREAD|CLOCAL, no RTS/CTS, no
         * input/output processing, no XON/XOFF, non-canonical without echo. null if fine.
         */
        fun verifyTermios(g: String): String? {
            val f = g.trim().split(":")
            if (f.size < 4) return "unexpected stty -g output '$g'"
            val v = f.take(4).map { it.toLongOrNull(16) ?: return "unexpected stty -g output '$g'" }
            val (iflag, oflag, cflag, lflag) = v
            val problems = ArrayList<String>()
            // c_iflag: IGNPAR 4, PARMRK 8, INPCK 0x10, ISTRIP 0x20, INLCR 0x40, IGNCR 0x80, ICRNL 0x100, IUCLC 0x200, IXON 0x400, IXOFF 0x1000
            if (iflag and 0x17FC != 0L) problems += "iflag 0x%x".format(iflag)
            if (oflag and 1 != 0L) problems += "opost"
            // c_cflag: CBAUD 0x100f (B115200 = 0x1002), CSIZE 0x30 (CS8), CSTOPB 0x40, CREAD 0x80, PARENB 0x100, CLOCAL 0x800, CRTSCTS 0x80000000
            if (cflag and 0x100F != 0x1002L) problems += "speed (cflag 0x%x)".format(cflag)
            if (cflag and 0x30 != 0x30L) problems += "not cs8"
            if (cflag and 0x140 != 0L) problems += "parity/2 stop bits"
            if (cflag and 0x880 != 0x880L) problems += "cread/clocal off"
            if (cflag and 0x80000000L != 0L) problems += "crtscts"
            // c_lflag: ISIG 1, ICANON 2, ECHO 8, IEXTEN 0x8000
            if (lflag and 0x800B != 0L) problems += "lflag 0x%x".format(lflag)
            return if (problems.isEmpty()) null else "termios not raw 115200 8N1: ${problems.joinToString(", ")}"
        }

        fun parseTcp(spec: String): Pair<String, Int>? {
            if (!spec.startsWith("tcp:")) return null
            val hp = spec.removePrefix("tcp:")
            val i = hp.lastIndexOf(':')
            if (i <= 0) return null
            val port = hp.substring(i + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return hp.substring(0, i) to port
        }
    }

    private interface Port {
        val input: InputStream
        val output: OutputStream
        val serial: Boolean
        fun close()
    }

    private val session = BracketSession(object : BracketSession.Out {
        override fun send(frame: ByteArray) = sendFrame(frame)
        override fun values(values: Map<String, Any>, why: String) =
            listener.onValues(values, "uart $why", apply = true, dockTrigger = false)
        override fun log(info: Boolean, msg: String) = log(if (info) INFO else DEBUG, msg)
    }, initialEnergyWh)

    @Volatile private var port: Port? = null
    private var reader: Thread? = null
    private var fastTick: ScheduledFuture<*>? = null
    private var nextOpenAt = 0L
    private var nextGuardAt = 0L
    private var lastProblem: String? = null
    private var savedEnergy = initialEnergyWh
    private var nextEnergySaveAt = 0L
    @Volatile private var stopped = false

    override val describe: String = if (transport.startsWith("tcp:")) "uart over $transport (test)" else "uart $transport"
    override val isConnected: Boolean get() = port != null
    /** Relays are read from the base; power/current/protection arrive as reports. */
    override val pollRes: List<String> = BaseRes.RELAYS + listOf(BaseRes.ENERGY, BaseRes.PROTECTION)
    override val pollIntervalMs = 30_000L
    override val confirmWrites = false

    /** For tests and logs. */
    val online: Boolean get() = session.online
    val energyWh: Float get() = session.energyWh

    private fun now() = System.nanoTime() / 1_000_000

    override fun start() {
        stopped = false
        fastTick = exec.scheduleWithFixedDelay({ fastTick() }, 100, 100, TimeUnit.MILLISECONDS)
        open()
    }

    override fun stop() {
        stopped = true
        fastTick?.cancel(false)
        fastTick = null
        persistEnergy(force = true)
        close("stopped")
    }

    override fun tick(now: Long) {
        val t = now()
        if (port == null) {
            if (t >= nextOpenAt) open()
            return
        }
        if (port?.serial == true && t >= nextGuardAt) {
            nextGuardAt = t + GUARD_MS
            portGuard()?.let { reason ->
                log(WARN, "giving up $transport: $reason")
                close(reason)
                listener.onBlocked(reason)
                listener.onUnavailable(reason)
                return
            }
        }
        persistEnergy(force = false)
    }

    override fun read(res: List<String>) {
        if (port != null) session.read(res, now())
    }

    override fun write(res: String, value: Int) {
        if (port == null) {
            log(WARN, "write $res=$value ignored: $transport not open")
            return
        }
        if (session.write(res, value, now())) log(INFO, "write $res=$value queued")
    }

    // ------------------------------------------------------------------ internals (exec thread)

    private fun fastTick() {
        try {
            if (port != null) session.tick(now())
        } catch (t: Throwable) {
            log(WARN, "tick failed: $t")
        }
    }

    private fun problem(msg: String) {
        if (msg != lastProblem) log(WARN, msg) else log(DEBUG, msg)
        lastProblem = msg
        listener.onUnavailable(msg)
    }

    private fun open() {
        if (stopped || port != null) return
        nextOpenAt = now() + RETRY_MS
        val p = try {
            val tcp = parseTcp(transport)
            if (tcp != null) openTcp(tcp.first, tcp.second) else openSerial(transport) ?: return
        } catch (e: IOException) {
            problem("can't open $transport: ${e.message}; retrying every ${RETRY_MS / 1000} s")
            return
        }
        port = p
        lastProblem = null
        nextGuardAt = now() + GUARD_MS
        log(INFO, "opened $transport")
        val t = Thread({ readLoop(p) }, "s1-base-rx").apply { isDaemon = true }
        reader = t
        t.start()
        listener.onConnected()
    }

    private fun openTcp(host: String, portNo: Int): Port {
        val s = Socket()
        s.connect(InetSocketAddress(host, portNo), 3_000)
        s.tcpNoDelay = true
        s.soTimeout = 500
        return object : Port {
            override val input: InputStream = s.getInputStream()
            override val output: OutputStream = s.getOutputStream()
            override val serial = false
            override fun close() = try { s.close() } catch (_: IOException) {}
        }
    }

    /** null (and the link reported unavailable/blocked) when the port must not be used now. */
    private fun openSerial(path: String): Port? {
        portGuard()?.let { reason ->
            problem("not opening $path: $reason")
            listener.onBlocked(reason)
            return null
        }
        val f = File(path)
        val input = FileInputStream(f)
        val output = try {
            FileOutputStream(f)
        } catch (e: IOException) {
            input.close(); throw e
        }
        stty(path, SERIAL_STTY)?.let { err ->
            input.close(); output.close()
            throw IOException("configuring $path: $err")
        }
        return object : Port {
            override val input: InputStream = input
            override val output: OutputStream = output
            override val serial = true
            override fun close() {
                try { input.close() } catch (_: IOException) {}
                try { output.close() } catch (_: IOException) {}
            }
        }
    }

    private fun readLoop(p: Port) {
        val buf = ByteArray(1024)
        try {
            while (!stopped && port === p) {
                val n = try {
                    p.input.read(buf)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (n < 0) {
                    // A tty read that times out (VTIME) returns 0, which Java turns into -1.
                    if (p.serial) continue
                    throw IOException("connection closed")
                }
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                exec.execute { if (port === p) session.onBytes(chunk, 0, n, now()) }
            }
        } catch (e: IOException) {
            if (!stopped && port === p) exec.execute {
                if (port === p) {
                    close("read failed: ${e.message}")
                    problem("$transport: ${e.message}; reopening in ${RETRY_MS / 1000} s")
                }
            }
        }
    }

    private fun sendFrame(f: ByteArray) {
        val p = port ?: return
        try {
            p.output.write(f)
            p.output.flush()
        } catch (e: IOException) {
            close("write failed: ${e.message}")
            problem("$transport write failed: ${e.message}; reopening in ${RETRY_MS / 1000} s")
        }
    }

    private fun close(why: String) {
        val p = port ?: return
        port = null
        nextOpenAt = now() + RETRY_MS
        p.close()
        reader = null
        log(INFO, "closed $transport ($why)")
        listener.onDisconnected()
    }

    private fun persistEnergy(force: Boolean) {
        val t = now()
        if (!force && t < nextEnergySaveAt) return
        nextEnergySaveAt = t + ENERGY_SAVE_MS
        val e = session.energyWh
        if (e != savedEnergy) {
            savedEnergy = e
            saveEnergy(e)
        }
    }
}
