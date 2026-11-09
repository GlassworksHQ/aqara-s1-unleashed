package io.github.aguilaair.s1kiosk.sendspin

import io.github.aguilaair.s1kiosk.esphome.NoiseCipher
import io.github.aguilaair.s1kiosk.esphome.NoiseMacException
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sendspin crypto helpers (spec "Encryption", aiosendspin 10.2.1 `noise/keys.py`, `noise/constants.py`). */
object SsCrypto {
    const val KEY_SIZE = 32
    const val PEER_ID_LEN = 43
    const val SUITE = "25519_ChaChaPoly_SHA256"

    private val B64E = Base64.getUrlEncoder().withoutPadding()
    private val B64D = Base64.getUrlDecoder()

    fun b64url(b: ByteArray): String = B64E.encodeToString(b)

    /** Lenient on padding like aiosendspin `b64url_decode`; throws IllegalArgumentException. */
    fun unb64url(s: String): ByteArray = B64D.decode(s.trimEnd('='))

    fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

    /** `SHA-256("sendspin-sentinel-psk-v1")`, the published PSK used when no other applies. */
    val SENTINEL_PSK: ByteArray = sha256("sendspin-sentinel-psk-v1".toByteArray())

    /** `base64url(SHA-256("sendspin-psk-id-v1" || PSK))`. */
    fun pskId(psk: ByteArray): String {
        require(psk.size == KEY_SIZE) { "PSK must be 32 bytes" }
        return b64url(sha256("sendspin-psk-id-v1".toByteArray(), psk))
    }

    val SENTINEL_PSK_ID: String = pskId(SENTINEL_PSK)

    fun randomBytes(n: Int, rng: SecureRandom = SecureRandom()) = ByteArray(n).also { rng.nextBytes(it) }

    fun x25519Public(priv: ByteArray): ByteArray =
        ByteArray(X25519.POINT_SIZE).also { X25519.generatePublicKey(priv, 0, it, 0) }

    fun x25519PrivateKey(rng: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(X25519.SCALAR_SIZE).also { X25519.generatePrivateKey(rng, it) }

    /** DH; throws [NoiseMacException] for a low-order point (all-zero shared secret). */
    fun dh(priv: ByteArray, pub: ByteArray): ByteArray {
        val out = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(priv, 0, pub, 0, out, 0)) throw NoiseMacException()
        return out
    }

    /** Decodes a 43-char base64url peer id to its 32-byte public key, or null if malformed. */
    fun peerKey(id: String): ByteArray? {
        if (id.length != PEER_ID_LEN) return null
        val b = try { unb64url(id) } catch (_: IllegalArgumentException) { return null }
        return b.takeIf { it.size == KEY_SIZE && b64url(it) == id }
    }

    // ---- pairing token (spec "Pairing Token"; aiosendspin noise/pairing_token.py)

    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** RFC 4648 base32 without padding. */
    fun base32(data: ByteArray): String {
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buf = 0
        var bits = 0
        for (b in data) {
            buf = (buf shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(B32[(buf shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(B32[(buf shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Version-0 pairing token: `"SP:0" + base32(client_key || pairing_psk)` with `2` → `9`. It is a credential. */
    fun pairingToken(clientKey: ByteArray, pairingPsk: ByteArray): String {
        require(clientKey.size == KEY_SIZE && pairingPsk.size == KEY_SIZE)
        return "SP:0" + base32(clientKey + pairingPsk).replace('2', '9')
    }

    internal fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Noise HKDF (§4.3) with HMAC-SHA256: 2 or 3 outputs. */
    internal fun hkdf(ck: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(ck, ikm)
        val o1 = hmac(temp, byteArrayOf(1))
        val o2 = hmac(temp, o1 + byteArrayOf(2))
        if (outputs == 2) return listOf(o1, o2)
        return listOf(o1, o2, hmac(temp, o2 + byteArrayOf(3)))
    }
}

/** A Sendspin static identity: X25519 keypair; `client_id` = base64url(public key). */
class SsIdentity(val privateKey: ByteArray) {
    init { require(privateKey.size == SsCrypto.KEY_SIZE) }
    val publicKey: ByteArray = SsCrypto.x25519Public(privateKey)
    val peerId: String = SsCrypto.b64url(publicKey)

    companion object {
        fun generate(rng: SecureRandom = SecureRandom()) = SsIdentity(SsCrypto.x25519PrivateKey(rng))
    }
}

/**
 * Responder (client) side of `Noise_KKpsk2_25519_ChaChaPoly_SHA256`, written from the Noise
 * Protocol Framework (rev. 34: §5 processing rules, §7.5 KK, §9 psk modifiers):
 *
 * ```
 * KKpsk2:
 *   -> s
 *   <- s
 *   ...
 *   -> e, es, ss            (server, Noise message 1)
 *   <- e, ee, se, psk       (client, Noise message 2)
 * ```
 * The server is the initiator. The PSK is chosen after message 1 (its payload names a `psk_id`),
 * so [setPsk] must be called between [readMessage1] and [writeMessage2] (spec "Pre-Shared Key").
 */
class KKpsk2Responder(
    private val localPriv: ByteArray,
    private val remoteStatic: ByteArray,
    prologue: ByteArray,
    private val rng: SecureRandom = SecureRandom(),
    /** Test hook: a fixed ephemeral private key. */
    private val fixedEphemeral: ByteArray? = null,
) {
    private var ck: ByteArray
    private var h: ByteArray
    private var k: NoiseCipher? = null
    private var re: ByteArray? = null
    private var psk: ByteArray? = null
    private var done = false

    init {
        val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= 32) name.copyOf(32) else SsCrypto.sha256(name)
        ck = h.copyOf()
        mixHash(prologue)
        mixHash(remoteStatic)                        // -> s (initiator's static)
        mixHash(SsCrypto.x25519Public(localPriv))    // <- s (responder's static)
    }

    /** Reads `e, es, ss` and returns the decrypted payload. Throws [NoiseMacException] on failure. */
    fun readMessage1(msg: ByteArray): ByteArray {
        if (msg.size < 32 + 16) throw NoiseMacException()
        val e = msg.copyOfRange(0, 32)
        re = e
        mixHash(e)
        mixKey(e) // psk handshakes: MixKey(e.public_key) after each "e" token
        mixKey(SsCrypto.dh(localPriv, e))            // es = DH(s_responder, e_initiator)
        mixKey(SsCrypto.dh(localPriv, remoteStatic)) // ss
        return decryptAndHash(msg.copyOfRange(32, msg.size))
    }

    fun setPsk(p: ByteArray) {
        require(p.size == SsCrypto.KEY_SIZE)
        psk = p.copyOf()
    }

    /** Writes `e, ee, se, psk` with [payload]. */
    fun writeMessage2(payload: ByteArray): ByteArray {
        val p = checkNotNull(psk) { "PSK not set" }
        val ePriv = fixedEphemeral ?: SsCrypto.x25519PrivateKey(rng)
        val ePub = SsCrypto.x25519Public(ePriv)
        mixHash(ePub)
        mixKey(ePub)
        mixKey(SsCrypto.dh(ePriv, re!!))             // ee
        mixKey(SsCrypto.dh(ePriv, remoteStatic))     // se = DH(s_initiator, e_responder)
        mixKeyAndHash(p)                             // psk
        val out = ePub + encryptAndHash(payload)
        done = true
        return out
    }

    /** The handshake hash `h` (available after [writeMessage2]). */
    val handshakeHash: ByteArray get() { check(done); return h.copyOf() }

    /** (receive cipher: initiator→responder, send cipher: responder→initiator). */
    fun split(): Pair<NoiseCipher, NoiseCipher> {
        check(done)
        val (k1, k2) = SsCrypto.hkdf(ck, ByteArray(0), 2)
        return NoiseCipher(k1) to NoiseCipher(k2)
    }

    private fun mixHash(d: ByteArray) { h = SsCrypto.sha256(h, d) }

    private fun mixKey(ikm: ByteArray) {
        val (c, key) = SsCrypto.hkdf(ck, ikm, 2)
        ck = c
        k = NoiseCipher(key)
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (c, th, key) = SsCrypto.hkdf(ck, ikm, 3)
        ck = c
        mixHash(th)
        k = NoiseCipher(key)
    }

    private fun encryptAndHash(p: ByteArray): ByteArray {
        val c = k!!.encrypt(h, p)
        mixHash(c)
        return c
    }

    private fun decryptAndHash(c: ByteArray): ByteArray {
        val p = k!!.decrypt(h, c)
        mixHash(c)
        return p
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_KKpsk2_25519_ChaChaPoly_SHA256"
    }
}
