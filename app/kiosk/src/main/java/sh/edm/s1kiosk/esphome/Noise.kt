package sh.edm.s1kiosk.esphome

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Thrown when a Noise message fails AEAD authentication (wrong PSK or tampering). */
class NoiseMacException : Exception("Handshake MAC failure")

/**
 * Noise `CipherState` with ChaChaPoly (Noise spec §5.1): 96-bit nonce = 32 zero bits followed by
 * the 64-bit little-endian counter.
 */
class NoiseCipher(private val key: ByteArray) {
    private var n = 0L

    private fun nonce(): ByteArray {
        val iv = ByteArray(12)
        var v = n
        for (i in 4 until 12) { iv[i] = v.toByte(); v = v ushr 8 }
        return iv
    }

    fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce(), ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val len = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, len)
        n++
        return out
    }

    fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce(), ad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val len = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, len)
        } catch (e: InvalidCipherTextException) {
            throw NoiseMacException()
        }
        n++
        return out
    }
}

/**
 * Responder side of `Noise_NNpsk0_25519_ChaChaPoly_SHA256`, the handshake used by ESPHome's API
 * (esphome `api_frame_helper_noise.cpp`; client in aioesphomeapi `_frame_helper/noise.py`):
 *
 * ```
 * NNpsk0:
 *   -> psk, e        (client, 32-byte ephemeral + 16-byte tag of the empty payload)
 *   <- e, ee         (server, same layout)
 * ```
 * Written from the Noise Protocol Framework spec, revision 34 (§5.2 SymmetricState, §9 PSK).
 */
class NoiseResponder(psk: ByteArray, prologue: ByteArray, private val random: SecureRandom = SecureRandom()) {
    private var ck: ByteArray
    private var h: ByteArray
    private var cipher: NoiseCipher? = null
    private var re: ByteArray? = null
    private val psk = psk.copyOf()

    init {
        require(psk.size == 32) { "PSK must be 32 bytes" }
        val name = PROTOCOL_NAME.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= 32) name.copyOf(32) else sha256(name)
        ck = h.copyOf()
        mixHash(prologue)
    }

    /** Reads the initiator's first message (`psk, e`). Throws [NoiseMacException] on a bad PSK. */
    fun readMessage1(msg: ByteArray) {
        if (msg.size < 32 + 16) throw NoiseMacException()
        mixKeyAndHash(psk)
        val e = msg.copyOfRange(0, 32)
        re = e
        mixHash(e)
        mixKey(e) // PSK handshakes also MixKey(e.public_key)
        decryptAndHash(msg.copyOfRange(32, msg.size)) // empty payload, but authenticated
    }

    /** Writes the responder's message (`e, ee`) with an empty payload. */
    fun writeMessage2(): ByteArray {
        val priv = ByteArray(X25519.SCALAR_SIZE)
        X25519.generatePrivateKey(random, priv)
        val pub = ByteArray(X25519.POINT_SIZE)
        X25519.generatePublicKey(priv, 0, pub, 0)
        mixHash(pub)
        mixKey(pub)
        val shared = ByteArray(X25519.POINT_SIZE)
        if (!X25519.calculateAgreement(priv, 0, re!!, 0, shared, 0)) throw NoiseMacException()
        mixKey(shared)
        return pub + encryptAndHash(ByteArray(0))
    }

    /** Returns (decrypt cipher for initiator->responder, encrypt cipher for responder->initiator). */
    fun split(): Pair<NoiseCipher, NoiseCipher> {
        val (k1, k2) = hkdf(ck, ByteArray(0), 2)
        return NoiseCipher(k1) to NoiseCipher(k2)
    }

    private fun mixHash(data: ByteArray) { h = sha256(h + data) }

    private fun mixKey(ikm: ByteArray) {
        val (newCk, k) = hkdf(ck, ikm, 2)
        ck = newCk
        cipher = NoiseCipher(k)
    }

    private fun mixKeyAndHash(ikm: ByteArray) {
        val (newCk, th, k) = hkdf(ck, ikm, 3)
        ck = newCk
        mixHash(th)
        cipher = NoiseCipher(k)
    }

    private fun encryptAndHash(p: ByteArray): ByteArray {
        val c = cipher!!.encrypt(h, p)
        mixHash(c)
        return c
    }

    private fun decryptAndHash(c: ByteArray): ByteArray {
        val p = cipher!!.decrypt(h, c)
        mixHash(c)
        return p
    }

    companion object {
        const val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256"

        private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

        private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

        /** Noise HKDF (§4.3) with HMAC-SHA256; returns 2 or 3 outputs of 32 bytes. */
        internal fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val temp = hmac(chainingKey, ikm)
            val o1 = hmac(temp, byteArrayOf(1))
            val o2 = hmac(temp, o1 + byteArrayOf(2))
            if (outputs == 2) return listOf(o1, o2)
            val o3 = hmac(temp, o2 + byteArrayOf(3))
            return listOf(o1, o2, o3)
        }
    }
}
