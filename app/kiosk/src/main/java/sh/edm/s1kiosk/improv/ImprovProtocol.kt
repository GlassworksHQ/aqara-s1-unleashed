package sh.edm.s1kiosk.improv

/**
 * Improv Wi-Fi via BLE, wire format only (pure Kotlin, JVM-testable).
 *
 * Sources (checked 2026-11-09):
 *  - Spec: https://www.improv-wifi.com/ble/ (rev 2.4)
 *  - improv-wifi/sdk-cpp `src/improv.h` / `improv.cpp` (UUIDs, enums, parse_improv_data, build_rpc_response)
 *  - home-assistant-libs py-improv-ble-client 2.0.1 `protocol.py` (what HA's `improv_ble` accepts)
 *  - esphome `components/improv_ble/improv_ble_component.cpp` (reference gadget behaviour)
 *
 * RPC packet (command and result): `[command, dataLength, data..., checksum]`, where the checksum is
 * the sum of all previous bytes, keeping only the low byte. Result data is a list of strings, each
 * `[len, utf8 bytes...]`.
 */
object Improv {
    const val SERVICE_UUID = "00467768-6228-2272-4663-277478268000"
    const val STATE_UUID = "00467768-6228-2272-4663-277478268001"
    const val ERROR_UUID = "00467768-6228-2272-4663-277478268002"
    const val RPC_COMMAND_UUID = "00467768-6228-2272-4663-277478268003"
    const val RPC_RESULT_UUID = "00467768-6228-2272-4663-277478268004"
    const val CAPABILITIES_UUID = "00467768-6228-2272-4663-277478268005"

    /** 16-bit service data UUID 0x4677 (spec rev 2.0), as a full 128-bit UUID. */
    const val SERVICE_DATA_UUID = "00004677-0000-1000-8000-00805f9b34fb"

    // Capabilities bitmask (capabilities characteristic and service data byte 2).
    const val CAP_IDENTIFY = 0x01
    const val CAP_DEVICE_INFO = 0x02
    const val CAP_SCAN_WIFI = 0x04
    const val CAP_HOSTNAME = 0x08

    // RPC commands.
    const val CMD_WIFI_SETTINGS = 0x01
    const val CMD_IDENTIFY = 0x02
    const val CMD_DEVICE_INFO = 0x03
    const val CMD_SCAN_WIFI = 0x04
    const val CMD_HOSTNAME = 0x05
    const val CMD_DEVICE_NAME = 0x06

    /** Max encoded result: command + length + 255 data bytes + checksum (sdk-cpp RPC_RESPONSE_MAX_SIZE). */
    const val MAX_PACKET = 258

    fun checksum(data: ByteArray, end: Int = data.size): Byte {
        var sum = 0
        for (i in 0 until end) sum += data[i].toInt() and 0xFF
        return sum.toByte()
    }

    /** Encodes an RPC packet whose data is a list of length-prefixed strings (commands and results). */
    fun encode(command: Int, strings: List<String>): ByteArray = encodeRaw(command, strings.map { it.toByteArray(Charsets.UTF_8) })

    fun encodeRaw(command: Int, strings: List<ByteArray>): ByteArray {
        val dataLen = strings.sumOf { 1 + it.size }
        require(dataLen <= 255) { "RPC data too long ($dataLen > 255)" }
        strings.forEach { require(it.size <= 255) { "string too long" } }
        val out = ByteArray(3 + dataLen)
        out[0] = command.toByte()
        out[1] = dataLen.toByte()
        var p = 2
        for (s in strings) {
            out[p++] = s.size.toByte()
            s.copyInto(out, p)
            p += s.size
        }
        out[p] = checksum(out, p)
        return out
    }

    /** 6-byte service data for UUID 0x4677: state, capabilities, 4 reserved zero bytes. */
    fun serviceData(state: ImprovState, capabilities: Int): ByteArray =
        byteArrayOf(state.code.toByte(), capabilities.toByte(), 0, 0, 0, 0)

    /**
     * Parses one complete RPC command packet. Mirrors sdk-cpp parse_improv_data: the length byte must
     * match, the checksum must match, and Wi-Fi settings must hold two strings that fit.
     */
    fun parse(packet: ByteArray): RpcCommand {
        if (packet.size < 3 || (packet[1].toInt() and 0xFF) != packet.size - 3) return RpcCommand.Invalid("bad length")
        if (checksum(packet, packet.size - 1) != packet[packet.size - 1]) return RpcCommand.Invalid("bad checksum")
        val cmd = packet[0].toInt() and 0xFF
        val strings = strings(packet) ?: return RpcCommand.Invalid("bad strings")
        return when (cmd) {
            CMD_WIFI_SETTINGS -> if (strings.size == 2) {
                RpcCommand.WifiSettings(strings[0].toString(Charsets.UTF_8), strings[1].toString(Charsets.UTF_8))
            } else RpcCommand.Invalid("wifi settings need 2 strings")
            CMD_IDENTIFY -> RpcCommand.Identify
            CMD_DEVICE_INFO -> RpcCommand.DeviceInfo
            CMD_SCAN_WIFI -> RpcCommand.ScanWifi
            else -> RpcCommand.Unknown(cmd)
        }
    }

    /** Splits the data part into length-prefixed strings; null if they don't tile the data exactly. */
    fun strings(packet: ByteArray): List<ByteArray>? {
        val end = packet.size - 1
        var p = 2
        val out = mutableListOf<ByteArray>()
        while (p < end) {
            val len = packet[p].toInt() and 0xFF
            if (p + 1 + len > end) return null
            out += packet.copyOfRange(p + 1, p + 1 + len)
            p += 1 + len
        }
        return if (p == end) out else null
    }

    /**
     * Scan result (spec rev 2.2): ONE result holding triples (SSID, RSSI, auth type), strongest first,
     * truncated to what fits in [maxPacket] bytes (255 data bytes, and the notification MTU).
     * An empty list means no networks.
     */
    fun encodeScan(networks: List<ScannedNetwork>, maxPacket: Int = MAX_PACKET): ByteArray {
        val limit = minOf(maxPacket, MAX_PACKET) - 3
        val strings = mutableListOf<ByteArray>()
        var size = 0
        val seen = HashSet<String>()
        for (n in networks.sortedByDescending { it.rssi }) {
            if (n.ssid.isEmpty() || !seen.add(n.ssid)) continue
            val triple = listOf(n.ssid, n.rssi.toString(), n.auth).map { it.toByteArray(Charsets.UTF_8) }
            if (triple[0].size > 32) continue
            val add = triple.sumOf { 1 + it.size }
            if (size + add > limit) break
            strings += triple
            size += add
        }
        return encodeRaw(CMD_SCAN_WIFI, strings)
    }
}

data class ScannedNetwork(val ssid: String, val rssi: Int, val auth: String)

enum class ImprovState(val code: Int) {
    AUTHORIZATION_REQUIRED(0x01),
    AUTHORIZED(0x02),
    PROVISIONING(0x03),
    PROVISIONED(0x04),
}

enum class ImprovError(val code: Int) {
    NONE(0x00),
    INVALID_RPC(0x01),
    UNKNOWN_RPC(0x02),
    UNABLE_TO_CONNECT(0x03),
    NOT_AUTHORIZED(0x04),
    BAD_HOSTNAME(0x05),
    UNKNOWN(0xFF),
}

sealed class RpcCommand {
    data class WifiSettings(val ssid: String, val password: String) : RpcCommand() {
        override fun toString() = "WifiSettings(ssid=$ssid, password=<${password.length} chars>)"
    }
    object Identify : RpcCommand()
    object DeviceInfo : RpcCommand()
    object ScanWifi : RpcCommand()
    data class Unknown(val command: Int) : RpcCommand()
    data class Invalid(val reason: String) : RpcCommand()
}

/**
 * Reassembles an RPC command written in several pieces (writes longer than MTU-3 arrive as
 * separate writes; see the spec's "over 20 bytes" note). Same rule as ESPHome
 * process_incoming_data_: wait until `size - 3 == length`, drop the buffer if it overshoots.
 */
class RpcAssembler {
    private var buf = ByteArray(0)

    /** Appends [chunk]; returns a complete packet when one is ready, else null. */
    fun add(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        buf += chunk
        if (buf.size < 3) return null
        val len = buf[1].toInt() and 0xFF
        return when {
            buf.size - 3 == len -> buf.also { buf = ByteArray(0) }
            buf.size - 3 > len -> { buf = ByteArray(0); ByteArray(0) } // overshoot: report as invalid
            else -> null
        }
    }

    fun clear() { buf = ByteArray(0) }
    val pending: Int get() = buf.size
}
