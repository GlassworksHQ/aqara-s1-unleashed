package io.github.aguilaair.s1kiosk.sendspin

import org.json.JSONArray
import org.json.JSONObject

/** A pairing record: the long-term PSK shared with one server (spec "Pairing Records"). */
data class SsPairingRecord(val serverId: String, val psk: ByteArray, val lastUsedMs: Long) {
    val pskId: String get() = SsCrypto.pskId(psk)
    override fun equals(other: Any?) = other is SsPairingRecord && other.serverId == serverId && other.psk.contentEquals(psk)
    override fun hashCode() = serverId.hashCode()
}

/**
 * Persistent client secrets and settings. Values are credentials: never log them.
 * Implementations must be thread-safe.
 */
interface SsStore {
    /** The X25519 identity, created (from a CSPRNG) and persisted on first use. */
    fun identity(): SsIdentity

    /** The per-device pairing PSK (CSPRNG, persistent, never consumed by a pairing). */
    fun pairingPsk(): ByteArray

    fun records(): List<SsPairingRecord>

    /** Stores [r], replacing any record for the same server; evicts the least recently used beyond [MAX_RECORDS]. */
    fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String> = emptySet())

    fun removeRecord(serverId: String)

    fun touchRecord(serverId: String, nowMs: Long)

    var outputDelayMs: Int
    var volume: Int
    var muted: Boolean
    var lastPlaybackServerId: String?

    companion object {
        /** Spec: a client MUST be able to store at least 5 pairing records. */
        const val MAX_RECORDS = 8

        fun encodeRecords(list: List<SsPairingRecord>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("server_id", it.serverId).put("psk", SsCrypto.b64url(it.psk)).put("used", it.lastUsedMs)) }
        }.toString()

        fun decodeRecords(s: String?): List<SsPairingRecord> = try {
            if (s.isNullOrEmpty()) emptyList() else JSONArray(s).let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val o = a.getJSONObject(i)
                    val psk = SsCrypto.unb64url(o.getString("psk"))
                    if (psk.size != 32) null else SsPairingRecord(o.getString("server_id"), psk, o.optLong("used"))
                }
            }
        } catch (_: Exception) { emptyList() }

        /** Replace-or-insert, then evict LRU (never a protected one) down to [MAX_RECORDS]. */
        fun merge(list: List<SsPairingRecord>, r: SsPairingRecord, protectedIds: Set<String>): List<SsPairingRecord> {
            val out = list.filter { it.serverId != r.serverId }.toMutableList()
            out += r
            while (out.size > MAX_RECORDS) {
                val victim = out.filter { it.serverId != r.serverId && it.serverId !in protectedIds }.minByOrNull { it.lastUsedMs } ?: break
                out.remove(victim)
            }
            return out
        }
    }
}

/** In-memory store (tests). */
class MemoryStore(private val id: SsIdentity = SsIdentity.generate(), private val pairing: ByteArray = SsCrypto.randomBytes(32)) : SsStore {
    private var recs = listOf<SsPairingRecord>()
    override fun identity() = id
    override fun pairingPsk() = pairing.copyOf()
    @Synchronized override fun records() = recs
    @Synchronized override fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String>) { recs = SsStore.merge(recs, r, protectedServerIds) }
    @Synchronized override fun removeRecord(serverId: String) { recs = recs.filter { it.serverId != serverId } }
    @Synchronized override fun touchRecord(serverId: String, nowMs: Long) { recs = recs.map { if (it.serverId == serverId) it.copy(lastUsedMs = nowMs) else it } }
    @Volatile override var outputDelayMs = 0
    @Volatile override var volume = 50
    @Volatile override var muted = false
    @Volatile override var lastPlaybackServerId: String? = null
}
