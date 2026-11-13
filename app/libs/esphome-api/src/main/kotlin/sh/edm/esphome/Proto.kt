package sh.edm.esphome

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf (proto3) encoder for the ESPHome native API messages we send.
 * Default values (0, false, "", empty bytes) are omitted, like proto3 does, unless `force` is set.
 * Field numbers are taken from aioesphomeapi `api.proto` (see [Msg]).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream(64)

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun rawVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }

    private fun tag(field: Int, wire: Int) = rawVarint(((field shl 3) or wire).toLong())

    /** uint32 (also used for enums with non-negative values). */
    fun uint32(field: Int, v: Int): ProtoWriter {
        if (v != 0) { tag(field, 0); rawVarint(v.toLong() and 0xFFFFFFFFL) }
        return this
    }

    /** int32: negative values are sign-extended to 10 bytes, as protobuf does. */
    fun int32(field: Int, v: Int): ProtoWriter {
        if (v != 0) { tag(field, 0); rawVarint(v.toLong()) }
        return this
    }

    fun bool(field: Int, v: Boolean): ProtoWriter {
        if (v) { tag(field, 0); out.write(1) }
        return this
    }

    fun fixed32(field: Int, v: Int, force: Boolean = false): ProtoWriter {
        if (v != 0 || force) {
            tag(field, 5)
            out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
        }
        return this
    }

    fun float(field: Int, v: Float): ProtoWriter {
        val bits = java.lang.Float.floatToRawIntBits(v)
        if (bits != 0) fixed32(field, bits, force = true)
        return this
    }

    fun string(field: Int, v: String?, force: Boolean = false): ProtoWriter {
        if (!v.isNullOrEmpty() || force) bytes(field, (v ?: "").toByteArray(Charsets.UTF_8), force)
        return this
    }

    fun bytes(field: Int, v: ByteArray, force: Boolean = false): ProtoWriter {
        if (v.isNotEmpty() || force) {
            tag(field, 2)
            rawVarint(v.size.toLong())
            out.write(v)
        }
        return this
    }

    /** Embedded message; always written (a present sub-message differs from an absent one). */
    fun message(field: Int, w: ProtoWriter): ProtoWriter = bytes(field, w.toByteArray(), force = true)

    fun strings(field: Int, values: Iterable<String>): ProtoWriter {
        for (s in values) bytes(field, s.toByteArray(Charsets.UTF_8), force = true)
        return this
    }
}

/**
 * Minimal protobuf decoder: parses every field of one message into a map. Our messages are tiny,
 * so this is simpler than a streaming API. Varints are kept as Long, fixed32 as Int, fixed64 as
 * Long and length-delimited fields as ByteArray.
 */
class ProtoMessage(data: ByteArray) {
    private val fields = HashMap<Int, MutableList<Any>>()

    init {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size) throw ProtoException("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw ProtoException("varint too long")
            }
        }
        while (pos < data.size) {
            val key = varint()
            val field = (key ushr 3).toInt()
            val value: Any = when ((key and 7).toInt()) {
                0 -> varint()
                1 -> {
                    if (pos + 8 > data.size) throw ProtoException("truncated fixed64")
                    var v = 0L
                    for (i in 0 until 8) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
                    pos += 8
                    v
                }
                2 -> {
                    val len = varint().toInt()
                    if (len < 0 || pos + len > data.size) throw ProtoException("truncated bytes")
                    data.copyOfRange(pos, pos + len).also { pos += len }
                }
                5 -> {
                    if (pos + 4 > data.size) throw ProtoException("truncated fixed32")
                    val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8) or
                        ((data[pos + 2].toInt() and 0xFF) shl 16) or ((data[pos + 3].toInt() and 0xFF) shl 24)
                    pos += 4
                    v
                }
                else -> throw ProtoException("unsupported wire type ${key and 7}")
            }
            fields.getOrPut(field) { ArrayList(1) } += value
        }
    }

    fun has(field: Int): Boolean = fields.containsKey(field)

    private fun last(field: Int): Any? = fields[field]?.lastOrNull()

    fun long(field: Int): Long = when (val v = last(field)) {
        is Long -> v
        is Int -> v.toLong() and 0xFFFFFFFFL
        else -> 0L
    }

    fun uint32(field: Int): Int = long(field).toInt()
    fun bool(field: Int): Boolean = long(field) != 0L
    fun fixed32(field: Int): Int = (last(field) as? Int) ?: 0
    fun float(field: Int): Float = java.lang.Float.intBitsToFloat(fixed32(field))
    fun bytes(field: Int): ByteArray = (last(field) as? ByteArray) ?: ByteArray(0)
    fun string(field: Int): String = String(bytes(field), Charsets.UTF_8)
    fun strings(field: Int): List<String> =
        fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.toString(Charsets.UTF_8) }
    fun messages(field: Int): List<ProtoMessage> =
        fields[field].orEmpty().mapNotNull { (it as? ByteArray)?.let(::ProtoMessage) }

    /** Every value of a repeated varint field, packed or not (a parser must accept both). */
    fun longs(field: Int): List<Long> = fields[field].orEmpty().flatMap { v ->
        when (v) {
            is Long -> listOf(v)
            is ByteArray -> packedVarints(v)
            else -> emptyList()
        }
    }

    /** Every value of a repeated fixed32 field (float, fixed32), packed or not. */
    fun fixed32s(field: Int): List<Int> = fields[field].orEmpty().flatMap { v ->
        when (v) {
            is Int -> listOf(v)
            is ByteArray -> {
                if (v.size % 4 != 0) throw ProtoException("truncated packed fixed32")
                (v.indices step 4).map { i ->
                    (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8) or
                        ((v[i + 2].toInt() and 0xFF) shl 16) or ((v[i + 3].toInt() and 0xFF) shl 24)
                }
            }
            else -> emptyList()
        }
    }

    private fun packedVarints(data: ByteArray): List<Long> {
        val out = ArrayList<Long>()
        var pos = 0
        while (pos < data.size) {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size) throw ProtoException("truncated packed varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw ProtoException("varint too long")
            }
            out += result
        }
        return out
    }

    companion object {
        /** sint32 zigzag decoding. */
        fun zigzag(v: Long): Int = ((v ushr 1) xor -(v and 1)).toInt()
    }
}

class ProtoException(message: String) : Exception(message)
