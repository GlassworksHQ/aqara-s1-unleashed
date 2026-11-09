package sh.edm.s1kiosk.esphome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The key-provisioning state machine (esphome api_server.cpp / api_connection.cpp semantics). */
class NoiseKeysTest {
    private val k1 = ByteArray(32) { (it + 1).toByte() }
    private val k2 = ByteArray(32) { (it * 3 + 7).toByte() }
    private fun field(k: ByteArray) = Base64.getEncoder().encode(k)

    @Test
    fun unprovisionedAcceptsAKeyAndActivatesItLater() {
        val store = MemoryKeyStore()
        val keys = NoiseKeys(store)
        assertTrue(keys.provisionable)
        assertFalse(keys.hasKey)

        val r = keys.handleSetKey(field(k1))
        assertTrue(r.success)
        assertTrue(r.activate)
        assertFalse("message must not contain the key", r.message.contains(Base64.getEncoder().encodeToString(k1)))
        // Persisted at once, active only after activatePending (ESPHome: 100 ms later).
        assertArrayEquals(k1, store.key)
        assertTrue(keys.provisionable)
        assertTrue(keys.activatePending())
        assertArrayEquals(k1, keys.active)
        assertFalse(keys.provisionable)
        assertFalse("second activation is a no-op", keys.activatePending())
    }

    @Test
    fun keySurvivesARestart() {
        val store = MemoryKeyStore()
        NoiseKeys(store).apply { handleSetKey(field(k1)); activatePending() }
        val again = NoiseKeys(store)
        assertArrayEquals(k1, again.active)
        assertFalse(again.provisionable)
    }

    @Test
    fun existingKeyKeepsWorking() {
        val keys = NoiseKeys(MemoryKeyStore(k1))
        assertTrue(keys.hasKey)
        assertFalse(keys.provisionable)
        assertArrayEquals(k1, keys.active)
    }

    @Test
    fun rejectsBadKeys() {
        val store = MemoryKeyStore(k1)
        val keys = NoiseKeys(store)
        for (bad in listOf("short".toByteArray(), Base64.getEncoder().encode(ByteArray(31) { 1 }),
            "not base64!!".toByteArray(), Base64.getEncoder().encode(ByteArray(32)))) {
            val r = keys.handleSetKey(bad)
            assertFalse(r.message, r.success)
            assertFalse(r.activate)
        }
        assertArrayEquals(k1, store.key)
        assertArrayEquals(k1, keys.active)
    }

    @Test
    fun sameKeyIsANoOpSuccess() {
        val keys = NoiseKeys(MemoryKeyStore(k1))
        val r = keys.handleSetKey(field(k1))
        assertTrue(r.success)
        assertFalse(r.activate)
    }

    @Test
    fun replaceAndClear() {
        val store = MemoryKeyStore(k1)
        val keys = NoiseKeys(store)
        assertTrue(keys.handleSetKey(field(k2)).activate)
        keys.activatePending()
        assertArrayEquals(k2, keys.active)

        val clear = keys.handleSetKey(ByteArray(0))
        assertTrue(clear.success)
        assertTrue(clear.activate)
        assertNull(store.key)
        keys.activatePending()
        assertTrue(keys.provisionable)
        assertNull(keys.active)
    }

    @Test
    fun failedSaveReportsFailure() {
        val keys = NoiseKeys(object : NoiseKeyStore {
            override fun load(): ByteArray? = null
            override fun save(key: ByteArray?) = false
        })
        val r = keys.handleSetKey(field(k1))
        assertFalse(r.success)
        assertTrue(keys.provisionable)
    }

    @Test
    fun localReplaceAndFingerprint() {
        val keys = NoiseKeys(MemoryKeyStore())
        assertTrue(keys.replace(k1))
        assertFalse(keys.replace(k1.copyOf()))
        assertTrue(keys.replace(null))
        assertEquals(4, NoiseKeys.fingerprint(k1).length)
        assertTrue(NoiseKeys.fingerprint(k1) != NoiseKeys.fingerprint(k2))
        // The key field is base64 text (HA: base64.b64encode(secrets.token_bytes(32))).
        assertArrayEquals(k1, NoiseKeys.decodeKeyField(field(k1)))
        assertArrayEquals(k1, NoiseKeys.decodeKeyField((Base64.getEncoder().encodeToString(k1) + "\n").toByteArray()))
    }
}
