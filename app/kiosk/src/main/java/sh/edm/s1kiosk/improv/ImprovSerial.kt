package sh.edm.s1kiosk.improv

/**
 * Improv Serial (https://www.improv-wifi.com/serial/), carried over an adb stream instead of a UART:
 * the web installer opens `localabstract:s1kiosk_improv` (ImprovSerialServer). Wire format and
 * session, pure Kotlin (ImprovSerialTest). The client and its simulated panel are
 * web/src/improv/serial.ts and web/test/improv.test.ts; keep them in sync.
 *
 * Packet: `"IMPROV" | version 1 | type | length | data[length] | checksum`, the checksum being the
 * low byte of the sum of all previous bytes. RPC data is `[command, length, payload...]`, the
 * payload being length-prefixed UTF-8 strings (as over BLE, minus the inner checksum).
 */
object ImprovSerial {
    /** Abstract socket name (adb: `localabstract:s1kiosk_improv`). */
    const val SOCKET = "s1kiosk_improv"

    val HEADER = "IMPROV".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1

    const val TYPE_STATE = 0x01
    const val TYPE_ERROR = 0x02
    const val TYPE_RPC = 0x03
    const val TYPE_RPC_RESULT = 0x04

    const val RPC_WIFI_SETTINGS = 0x01
    const val RPC_REQUEST_STATE = 0x02
    const val RPC_REQUEST_INFO = 0x03
    const val RPC_SCAN_WIFI = 0x04

    const val STATE_READY = 0x02
    const val STATE_PROVISIONING = 0x03
    const val STATE_PROVISIONED = 0x04

    const val ERROR_INVALID_RPC = 0x01
    const val ERROR_UNKNOWN_RPC = 0x02
    const val ERROR_UNABLE_TO_CONNECT = 0x03
    const val ERROR_UNKNOWN = 0xFF

    /** Header (6) + version + type + length. */
    private const val PREFIX = 9

    fun frame(type: Int, data: ByteArray): ByteArray {
        require(data.size <= 255) { "Improv packet too large (${data.size})" }
        val out = ByteArray(PREFIX + data.size + 1)
        HEADER.copyInto(out)
        out[6] = VERSION.toByte()
        out[7] = type.toByte()
        out[8] = data.size.toByte()
        data.copyInto(out, PREFIX)
        out[out.size - 1] = Improv.checksum(out, out.size - 1)
        return out
    }

    /** `[command, length, strings...]` as RPC commands and results carry it. */
    fun rpcData(command: Int, strings: List<String>): ByteArray {
        val parts = strings.map { it.toByteArray(Charsets.UTF_8) }
        parts.forEach { require(it.size <= 255) { "Improv string longer than 255 bytes" } }
        val len = parts.sumOf { 1 + it.size }
        require(len <= 253) { "Improv RPC data too long ($len)" }
        val out = ByteArray(2 + len)
        out[0] = command.toByte()
        out[1] = len.toByte()
        var p = 2
        for (s in parts) {
            out[p++] = s.size.toByte()
            s.copyInto(out, p)
            p += s.size
        }
        return out
    }

    fun state(state: Int): ByteArray = frame(TYPE_STATE, byteArrayOf(state.toByte()))
    fun error(code: Int): ByteArray = frame(TYPE_ERROR, byteArrayOf(code.toByte()))
    fun result(command: Int, strings: List<String>): ByteArray = frame(TYPE_RPC_RESULT, rpcData(command, strings))
    /** An RPC command packet (the client side; used by tests). */
    fun rpc(command: Int, strings: List<String> = emptyList()): ByteArray = frame(TYPE_RPC, rpcData(command, strings))

    /** Data of an RPC (or RPC result) packet: command, length, then length-prefixed strings. */
    fun parseRpc(data: ByteArray): SerialRpc {
        if (data.size < 2) return SerialRpc.Invalid("short RPC")
        val cmd = data[0].toInt() and 0xFF
        val len = data[1].toInt() and 0xFF
        if (len != data.size - 2) return SerialRpc.Invalid("RPC length $len != ${data.size - 2}")
        val strings = mutableListOf<String>()
        var p = 2
        while (p < data.size) {
            val n = data[p].toInt() and 0xFF
            if (p + 1 + n > data.size) return SerialRpc.Invalid("string overruns the RPC")
            strings += String(data, p + 1, n, Charsets.UTF_8)
            p += 1 + n
        }
        return when (cmd) {
            RPC_WIFI_SETTINGS -> if (strings.size == 2) SerialRpc.WifiSettings(strings[0], strings[1])
            else SerialRpc.Invalid("Wi-Fi settings need 2 strings, got ${strings.size}")
            RPC_REQUEST_STATE -> SerialRpc.RequestState
            RPC_REQUEST_INFO -> SerialRpc.RequestInfo
            RPC_SCAN_WIFI -> SerialRpc.ScanWifi
            else -> SerialRpc.Unknown(cmd)
        }
    }

    /**
     * Scan results as the client reads them: one RPC result `[ssid, rssi, "YES"|"NO"]` per network
     * (hidden SSIDs skipped, one per SSID keeping the strongest, strongest first, SSIDs over 32
     * bytes skipped), then an empty result that ends the list.
     */
    fun scanResults(networks: List<SerialNetwork>): List<ByteArray> {
        val best = LinkedHashMap<String, SerialNetwork>()
        for (n in networks) {
            if (n.ssid.isEmpty() || n.ssid.toByteArray(Charsets.UTF_8).size > 32) continue
            val prev = best[n.ssid]
            if (prev == null || n.rssi > prev.rssi) best[n.ssid] = n
        }
        return best.values.sortedByDescending { it.rssi }
            .map { result(RPC_SCAN_WIFI, listOf(it.ssid, it.rssi.toString(), if (it.secure) "YES" else "NO")) } +
            result(RPC_SCAN_WIFI, emptyList())
    }

    /** Device info strings, each clipped so the four fit one result (253 bytes of strings). */
    fun info(firmware: String, version: String, chip: String, name: String): ByteArray =
        result(RPC_REQUEST_INFO, listOf(firmware, version, chip, name).map { clip(it, 60) })

    private fun clip(s: String, maxBytes: Int): String {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > maxBytes) out = out.dropLast(1)
        return out
    }
}

/** A network as the serial scan reports it: [secure] = a password is needed. */
data class SerialNetwork(val ssid: String, val rssi: Int, val secure: Boolean)

sealed class SerialRpc {
    data class WifiSettings(val ssid: String, val password: String) : SerialRpc() {
        override fun toString() = "WifiSettings(ssid=$ssid, password=<${password.length} chars>)"
    }
    object RequestState : SerialRpc()
    object RequestInfo : SerialRpc()
    object ScanWifi : SerialRpc()
    data class Unknown(val command: Int) : SerialRpc()
    data class Invalid(val reason: String) : SerialRpc()
}

/**
 * Accumulates bytes and yields complete packets. Garbage before a header is skipped silently; a
 * complete packet with a bad checksum or version is reported as [Item.Bad] and the reader resyncs on
 * the next header (as the client's PacketReader does).
 */
class SerialPacketReader(private val maxBuffer: Int = 4096) {
    sealed class Item {
        data class Packet(val type: Int, val data: ByteArray) : Item() {
            override fun equals(other: Any?) = other is Packet && other.type == type && other.data.contentEquals(data)
            override fun hashCode() = type * 31 + data.contentHashCode()
        }
        data class Bad(val reason: String) : Item()
    }

    private var buf = ByteArray(0)

    fun push(chunk: ByteArray, len: Int = chunk.size): List<Item> {
        buf += chunk.copyOf(len)
        val out = mutableListOf<Item>()
        while (true) {
            val start = findHeader()
            if (start < 0) {
                // Keep a possible partial header at the end.
                buf = buf.copyOfRange(maxOf(0, buf.size - (ImprovSerial.HEADER.size - 1)), buf.size)
                break
            }
            if (start > 0) buf = buf.copyOfRange(start, buf.size)
            if (buf.size < 9) break
            val total = 9 + (buf[8].toInt() and 0xFF) + 1
            if (buf.size < total) break
            val okSum = Improv.checksum(buf, total - 1) == buf[total - 1]
            val okVersion = (buf[6].toInt() and 0xFF) == ImprovSerial.VERSION
            if (okSum && okVersion) {
                out += Item.Packet(buf[7].toInt() and 0xFF, buf.copyOfRange(9, total - 1))
                buf = buf.copyOfRange(total, buf.size)
            } else {
                out += Item.Bad(if (!okSum) "bad checksum" else "version ${buf[6].toInt() and 0xFF}")
                buf = buf.copyOfRange(1, buf.size) // resync on the next header
            }
        }
        if (buf.size > maxBuffer) buf = ByteArray(0)
        return out
    }

    private fun findHeader(): Int {
        val h = ImprovSerial.HEADER
        outer@ for (i in 0..buf.size - h.size) {
            for (j in h.indices) if (buf[i + j] != h[j]) continue@outer
            return i
        }
        return -1
    }
}

/**
 * One Improv Serial connection (pure; the Android side is behind [Effects]). Thread-safe: bytes come
 * in on the connection's thread, Wi-Fi outcomes on the main thread.
 *
 * - Starts in Ready (authorized): the USB connection, authorized with the installer's adb key, is
 *   the physical authorization. If the panel is already on Wi-Fi, the state is Provisioned instead
 *   (as ESPHome's improv_serial does), so the installer can say so; Wi-Fi settings are still taken.
 * - Wi-Fi settings: state Provisioning, then Provisioned + an RPC result with the redirect URL, or
 *   error 0x03 (unable to connect) and back to Ready.
 * - Request state: the state (+ the redirect URL as an RPC result when provisioned).
 * - Request info: firmware, version, chip, device name.
 * - Scan: one result per network, then an empty result ([ImprovSerial.scanResults]).
 * - A malformed packet gets error 0x01, an unknown command 0x02. Passwords are never logged.
 */
class ImprovSerialSession(private val fx: Effects, private val log: (String) -> Unit = {}) {
    interface Effects {
        /** Writes one packet to the client (may throw if the client is gone). */
        fun send(packet: ByteArray)
        /** The panel is on Wi-Fi now (for the initial state). */
        fun online(): Boolean
        /** Where to continue setup once on Wi-Fi ("" = none). */
        fun redirectUrl(): String
        fun info(): ByteArray
        /** Fresh Wi-Fi scan; may block for a few seconds. */
        fun scan(): List<SerialNetwork>
        /** Starts joining; [done] (any thread) with the redirect URL on success, null on failure. */
        fun connect(ssid: String, password: String, done: (redirect: String?) -> Unit)
    }

    private val reader = SerialPacketReader()
    private var seq = 0
    private var redirect = ""

    @Volatile var state: Int = -1
        private set

    private fun ensureState() {
        if (state == -1) state = if (fx.online()) ImprovSerial.STATE_PROVISIONED else ImprovSerial.STATE_READY
    }

    /** Bytes from the client. */
    fun onBytes(chunk: ByteArray, len: Int = chunk.size) {
        for (item in reader.push(chunk, len)) {
            when (item) {
                is SerialPacketReader.Item.Bad -> { log("bad packet: ${item.reason}"); sendError(ImprovSerial.ERROR_INVALID_RPC) }
                is SerialPacketReader.Item.Packet ->
                    if (item.type != ImprovSerial.TYPE_RPC) {
                        log("unexpected packet type ${item.type}")
                        sendError(ImprovSerial.ERROR_INVALID_RPC)
                    } else handle(ImprovSerial.parseRpc(item.data))
            }
        }
    }

    private fun handle(rpc: SerialRpc) {
        log("rpc $rpc")
        when (rpc) {
            is SerialRpc.Invalid -> sendError(ImprovSerial.ERROR_INVALID_RPC)
            is SerialRpc.Unknown -> sendError(ImprovSerial.ERROR_UNKNOWN_RPC)
            SerialRpc.RequestState -> synchronized(this) {
                ensureState()
                send(ImprovSerial.state(state))
                if (state == ImprovSerial.STATE_PROVISIONED) {
                    send(ImprovSerial.result(ImprovSerial.RPC_REQUEST_STATE, urls(redirect.ifEmpty { fx.redirectUrl() })))
                }
            }
            SerialRpc.RequestInfo -> send(fx.info())
            SerialRpc.ScanWifi -> {
                val packets = ImprovSerial.scanResults(fx.scan())
                synchronized(this) { packets.forEach { send(it) } }
            }
            is SerialRpc.WifiSettings -> wifi(rpc)
        }
    }

    private fun wifi(rpc: SerialRpc.WifiSettings) {
        val bytes = rpc.ssid.toByteArray(Charsets.UTF_8).size
        if (bytes == 0 || bytes > 32) return sendError(ImprovSerial.ERROR_INVALID_RPC)
        val mine = synchronized(this) {
            ensureState()
            state = ImprovSerial.STATE_PROVISIONING
            send(ImprovSerial.state(state))
            ++seq
        }
        fx.connect(rpc.ssid, rpc.password) { url -> onWifiResult(mine, url) }
    }

    private fun onWifiResult(attempt: Int, url: String?) = synchronized(this) {
        if (attempt != seq) return@synchronized // a newer request on this connection owns the state
        if (url != null) {
            redirect = url
            state = ImprovSerial.STATE_PROVISIONED
            send(ImprovSerial.state(state))
            send(ImprovSerial.result(ImprovSerial.RPC_WIFI_SETTINGS, urls(url)))
        } else {
            state = ImprovSerial.STATE_READY
            send(ImprovSerial.error(ImprovSerial.ERROR_UNABLE_TO_CONNECT))
            send(ImprovSerial.state(state))
        }
    }

    private fun urls(url: String) = listOf(url).filter { it.isNotEmpty() }

    private fun sendError(code: Int) = synchronized(this) { send(ImprovSerial.error(code)) }

    private fun send(p: ByteArray) {
        try { fx.send(p) } catch (e: Exception) { log("send failed: ${e.message}") }
    }
}
