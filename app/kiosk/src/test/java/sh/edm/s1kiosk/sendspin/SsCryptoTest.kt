package sh.edm.s1kiosk.sendspin

import sh.edm.s1kiosk.esphome.NoiseMacException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun publishedConstants() {
        // Spec "Pre-Shared Key": Sentinel PSK and its psk_id are published constants.
        assertEquals("1b5e24dbc1aed95fc2a5a338a90c05df44bd10f5ec1f4cd66cbf86272767b9d3", SsCrypto.SENTINEL_PSK.hex())
        assertEquals("GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo", SsCrypto.SENTINEL_PSK_ID)
        assertEquals("185b15f6d2da4909bd1dc156a4ab206103abef0153bcd52d926170b95cf7ce8a", SsCrypto.unb64url(SsCrypto.SENTINEL_PSK_ID).hex())
    }

    @Test
    fun pairingTokenReferenceVector() {
        // Spec "Pairing PSK Flow": client_key = 00..1f, pairing_psk = e0..ff.
        val key = ByteArray(32) { it.toByte() }
        val psk = ByteArray(32) { (0xe0 + it).toByte() }
        assertEquals(
            "SP:0AAAQEAYEAUDAOCAJBIFQYDIOB4IBCEQTCQKRMFYYDENBWHA5DYP6BYPC4PSOLZXH5DU6V97M5XXO74HR6LZ7J5PW674PT6X37T6757Y",
            SsCrypto.pairingToken(key, psk),
        )
    }

    @Test
    fun base32MatchesRfc4648() {
        assertEquals("MZXW6YTBOI", SsCrypto.base32("foobar".toByteArray()))
        assertEquals("MY", SsCrypto.base32("f".toByteArray()))
    }

    @Test
    fun peerIdsAreCanonical43CharBase64Url() {
        val id = SsIdentity(ByteArray(32) { 7 })
        assertEquals(43, id.peerId.length)
        assertArrayEquals(id.publicKey, SsCrypto.peerKey(id.peerId))
        assertNull(SsCrypto.peerKey(id.peerId.dropLast(1)))
        assertNull(SsCrypto.peerKey("!".repeat(43)))
    }

    /** Vector made with the Python `noiseprotocol` library aiosendspin 10.2.1 uses (fixed keys). */
    @Test
    fun kkpsk2ResponderMatchesNoiseprotocol() {
        val serverPub = hex("d89e3bad79437dbed9f843418304f460ff05c7fe81fe4a9577a804cb9367ff66")
        assertArrayEquals(serverPub, SsCrypto.x25519Public(hex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f")))
        val hs = KKpsk2Responder(
            localPriv = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"),
            remoteStatic = serverPub,
            prologue = "{\"type\":\"client/init\"}{\"type\":\"server/init\"}".toByteArray(),
            fixedEphemeral = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf"),
        )
        val msg1 = hex(
            "23b7bb8c91ae008711fb12846780bcdf1e065f821bdfec49f57e7c7dcd4c4823969844cb06c78cf0c2144187630af55d584c9090bd137b3e837a39c081ce550981e2ba4969f0a85ef3e2d3a9334b2168d5ed66c608e440fe31174f5ce31def946489093296e4b4b8f05043f7c2158d40e77e68bde9d281b6c2fb588f"
        )
        assertEquals(
            "{\"psk_id\":\"GFsV9tLaSQm9HcFWpKsgYQOr7wFTvNUtkmFwuVz3zoo\",\"psk_category\":\"sn\"}",
            String(hs.readMessage1(msg1)),
        )
        hs.setPsk(SsCrypto.SENTINEL_PSK)
        assertEquals(
            "605a725d2a4adfeeb1a29e17edd621c1b7593ee8cdbc44ac6c4ab6e2f805d23cdc97a1277a9a154cf020d3b0b73a9b5801d3",
            hs.writeMessage2("{}".toByteArray()).hex(),
        )
        assertEquals("6e7a7bd57dde1d459227fef19d185a0d1d0696bedd01aacf1b7a4a188ce0f1be", hs.handshakeHash.hex())
        val (rx, tx) = hs.split()
        assertEquals("\u0000hello", String(rx.decrypt(ByteArray(0), hex("d00ff47cd50076fd0b8869a53f5a499cc475f088fc6b"))))
        assertEquals("86ba70758459a636e8dab8be0736ee47e791edf93692", tx.encrypt(ByteArray(0), "\u0000world".toByteArray()).hex())
    }

    @Test
    fun kkpsk2WrongStaticKeyFails() {
        val hs = KKpsk2Responder(ByteArray(32) { 1 }, SsCrypto.x25519Public(ByteArray(32) { 2 }), ByteArray(0))
        val bogus = SsCrypto.x25519Public(ByteArray(32) { 3 }) + ByteArray(40)
        try {
            hs.readMessage1(bogus)
            throw AssertionError("expected a MAC failure")
        } catch (_: NoiseMacException) {}
    }

    @Test
    fun recordsEvictLeastRecentlyUsedButNeverProtected() {
        var list = emptyList<SsPairingRecord>()
        for (i in 0 until SsStore.MAX_RECORDS) list = SsStore.merge(list, SsPairingRecord("s$i", ByteArray(32) { i.toByte() }, i.toLong()), emptySet())
        list = SsStore.merge(list, SsPairingRecord("new", ByteArray(32), 100), protectedIds = setOf("s0"))
        assertEquals(SsStore.MAX_RECORDS, list.size)
        assertTrue(list.any { it.serverId == "s0" })
        assertTrue(list.none { it.serverId == "s1" })
        assertEquals(list, SsStore.decodeRecords(SsStore.encodeRecords(list)))
        // Replacing keeps one record per server.
        assertEquals(1, SsStore.merge(list, SsPairingRecord("new", ByteArray(32) { 9 }, 101), emptySet()).count { it.serverId == "new" })
    }
}
