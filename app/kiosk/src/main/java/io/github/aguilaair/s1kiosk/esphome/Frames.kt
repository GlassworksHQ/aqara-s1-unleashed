package io.github.aguilaair.s1kiosk.esphome

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** One API message: type id + protobuf payload. */
class Frame(val type: Int, val data: ByteArray)

class ProtocolException(message: String) : IOException(message)

/**
 * Framing of the ESPHome native API on one TCP connection (server side).
 * [read] is called from the connection's reader thread; [write] is serialized by the caller.
 */
interface FrameTransport {
    /** Runs the handshake (Noise only). Returns false if the connection must be closed. */
    fun handshake(): Boolean = true

    /** Next message, or null at end of stream. */
    fun read(): Frame?

    fun write(frames: List<Frame>)
}

/**
 * Plaintext framing (aioesphomeapi `_frame_helper/plain_text.py`, esphome
 * `api_frame_helper_plaintext.cpp`): `0x00`, varint payload length, varint type, payload.
 */
class PlaintextTransport(input: InputStream, private val output: OutputStream) : FrameTransport {
    private val input = DataInputStream(input)

    private fun readVarint(): Int {
        var result = 0
        var shift = 0
        repeat(4) { // the client caps varints at 4 bytes too
            val b = input.read()
            if (b < 0) throw java.io.EOFException()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw ProtocolException("varint too long")
    }

    override fun read(): Frame? {
        val indicator = input.read()
        if (indicator < 0) return null
        if (indicator != 0x00) {
            // Same reply as ESPHome: the client sees preamble 0x00 and reports a protocol error.
            // (A Noise client sends 0x01; aioesphomeapi then reports "device is using plaintext".)
            output.write("\u0000Bad indicator byte".toByteArray(Charsets.US_ASCII))
            output.flush()
            throw ProtocolException("Bad indicator byte $indicator (encrypted client?)")
        }
        val len = readVarint()
        if (len > 65535) throw ProtocolException("frame too large: $len")
        val type = readVarint()
        val data = ByteArray(len)
        input.readFully(data)
        return Frame(type, data)
    }

    override fun write(frames: List<Frame>) {
        val buf = ByteArrayOutputStream()
        for (f in frames) {
            buf.write(0)
            writeVarint(buf, f.data.size)
            writeVarint(buf, f.type)
            buf.write(f.data)
        }
        output.write(buf.toByteArray())
        output.flush()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }
}

/**
 * Noise framing (aioesphomeapi `_frame_helper/noise.py`, esphome `api_frame_helper_noise.cpp`).
 * Every frame is `0x01`, 16-bit big-endian length, data. Handshake:
 * ```
 * client -> 01 00 00                         (ClientHello, empty; goes into the prologue)
 * client -> 01 LL LL  00 <NNpsk0 message 1>
 * server -> 01 LL LL  01 <name> 00 <mac> 00  (ServerHello: chosen proto 0x01, name, 12-hex mac)
 * server -> 01 LL LL  00 <NNpsk0 message 2>   or  01 "Handshake MAC failure" on a bad key
 * ```
 * Prologue = "NoiseAPIInit" + 2-byte length of the ClientHello + ClientHello.
 * Data frames are encrypted `type(2 BE) + length(2 BE) + payload`.
 */
class NoiseTransport(
    input: InputStream,
    private val output: OutputStream,
    private val psk: ByteArray,
    private val serverName: String,
    private val macHex: String,
) : FrameTransport {
    private val input = DataInputStream(input)
    private var decrypt: NoiseCipher? = null
    private var encrypt: NoiseCipher? = null

    /** Returns the frame body, or null at EOF. [first] tolerates a plaintext client. */
    private fun readRawFrame(first: Boolean = false): ByteArray? {
        val indicator = input.read()
        if (indicator < 0) return null
        if (indicator != 0x01) {
            if (first) reject("Bad indicator byte")
            throw ProtocolException("Bad indicator byte $indicator (plaintext client?)")
        }
        val len = input.readUnsignedShort()
        val data = ByteArray(len)
        input.readFully(data)
        return data
    }

    private fun rawFrame(data: ByteArray): ByteArray {
        require(data.size <= 0xFFFF) { "noise frame too large" }
        return byteArrayOf(0x01, (data.size ushr 8).toByte(), data.size.toByte()) + data
    }

    private fun reject(reason: String) {
        // The aioesphomeapi client maps "Handshake MAC failure" to InvalidEncryptionKeyAPIError.
        // A plaintext client sees preamble 0x01 and reports RequiresEncryptionAPIError.
        try {
            output.write(rawFrame(byteArrayOf(0x01) + reason.toByteArray(Charsets.US_ASCII)))
            output.flush()
        } catch (_: IOException) {}
    }

    override fun handshake(): Boolean {
        val clientHello = readRawFrame(first = true) ?: return false
        val prologue = "NoiseAPIInit".toByteArray(Charsets.US_ASCII) +
            byteArrayOf((clientHello.size ushr 8).toByte(), clientHello.size.toByte()) + clientHello

        val hello = ByteArrayOutputStream()
        hello.write(0x01) // chosen protocol: Noise_NNpsk0_25519_ChaChaPoly_SHA256
        hello.write(serverName.toByteArray(Charsets.UTF_8)); hello.write(0)
        hello.write(macHex.toByteArray(Charsets.US_ASCII)); hello.write(0)
        output.write(rawFrame(hello.toByteArray()))
        output.flush()

        val msg = readRawFrame() ?: return false
        if (msg.isEmpty()) { reject("Empty handshake message"); return false }
        if (msg[0].toInt() != 0) { reject("Bad handshake error byte"); return false }
        val noise = NoiseResponder(psk, prologue)
        try {
            noise.readMessage1(msg.copyOfRange(1, msg.size))
        } catch (e: NoiseMacException) {
            reject("Handshake MAC failure")
            return false
        }
        val reply = noise.writeMessage2()
        output.write(rawFrame(byteArrayOf(0x00) + reply))
        output.flush()
        val (rx, tx) = noise.split()
        decrypt = rx
        encrypt = tx
        return true
    }

    override fun read(): Frame? {
        val frame = readRawFrame() ?: return null
        val msg = try {
            decrypt!!.decrypt(ByteArray(0), frame)
        } catch (e: NoiseMacException) {
            throw ProtocolException("decrypt failed")
        }
        if (msg.size < 4) throw ProtocolException("decrypted message too short")
        val type = ((msg[0].toInt() and 0xFF) shl 8) or (msg[1].toInt() and 0xFF)
        return Frame(type, msg.copyOfRange(4, msg.size))
    }

    override fun write(frames: List<Frame>) {
        val buf = ByteArrayOutputStream()
        for (f in frames) {
            val header = byteArrayOf(
                (f.type ushr 8).toByte(), f.type.toByte(),
                (f.data.size ushr 8).toByte(), f.data.size.toByte(),
            )
            buf.write(rawFrame(encrypt!!.encrypt(ByteArray(0), header + f.data)))
        }
        output.write(buf.toByteArray())
        output.flush()
    }
}
