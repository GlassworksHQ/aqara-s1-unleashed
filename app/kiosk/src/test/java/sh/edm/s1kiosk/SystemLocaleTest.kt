package sh.edm.s1kiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemLocaleTest {
    @Test
    fun parsesPlainLanguageTags() {
        assertEquals("en-US", SystemLocale.parse("en-US")!!.toLanguageTag())
        assertEquals("pt-BR", SystemLocale.parse("pt-BR")!!.toLanguageTag())
        assertEquals("de", SystemLocale.parse("de")!!.toLanguageTag())
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(SystemLocale.parse(""))
        assertNull(SystemLocale.parse("en_US"))
        assertNull(SystemLocale.parse("en-US; reboot"))
        assertNull(SystemLocale.parse("x"))
    }
}
