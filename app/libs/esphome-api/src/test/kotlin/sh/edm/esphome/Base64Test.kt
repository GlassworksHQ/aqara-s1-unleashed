package sh.edm.esphome

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

/** Our decoder against the JDK's `Base64.getDecoder()`, which the library used before (API 26 on Android). */
class Base64Test {
    private fun jdk(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun assertSame(s: String) {
        val want = jdk(s)
        val got = decodeBase64(s)
        if (want == null) assertEquals("'$s' is invalid", null, got) else assertArrayEquals("'$s'", want, got)
    }

    @Test fun edgeCases() {
        listOf(
            "", "=", "==", "===", "Q", "QQ", "QQ=", "QQ==", "QQ===", "QUI", "QUI=", "QUI==", "QUJD", "QUJD=",
            "QR==", "QUJ=", "Q===", "QQ==QQ==", "QUJD QUJD", "QUJD\n", "-_8=", "/+8=", "QUJé", "ĀUJD",
        ).forEach(::assertSame)
    }

    @Test fun everyLengthOfRealData() {
        val r = Random(151)
        for (len in 0..80) repeat(20) {
            val bytes = r.nextBytes(len)
            val padded = Base64.getEncoder().encodeToString(bytes)
            val unpadded = Base64.getEncoder().withoutPadding().encodeToString(bytes)
            assertArrayEquals(bytes, decodeBase64(padded))
            assertArrayEquals(bytes, decodeBase64(unpadded))
        }
    }

    @Test fun randomTextDecodesOrFailsLikeTheJdk() {
        val chars = "AQZaqz09+/=" + "-_ \n.é"
        val r = Random(23)
        repeat(50_000) {
            val s = String(CharArray(r.nextInt(0, 14)) { chars[r.nextInt(if (r.nextInt(10) == 0) chars.length else 11)] })
            assertSame(s)
        }
    }
}
