package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    private fun v(s: String) = AppVersion.parse(s) ?: throw AssertionError("did not parse '$s'")

    @Test fun parsesTagsAndVersions() {
        assertEquals(AppVersion(1, 2, 3), v("1.2.3"))
        assertEquals(AppVersion(1, 2, 3), v("v1.2.3"))
        assertEquals(AppVersion(1, 2, 3), v(" V1.2.3 "))
        assertEquals(AppVersion(1, 0, 0, listOf("rc", "1")), v("v1.0.0-rc.1+build.5"))
        assertEquals("1.0.0-rc.1", v("1.0.0-rc.1+abc").toString())
        assertEquals("0.1.0", v("0.1.0").toString())
    }

    @Test fun rejectsNonSemver() {
        for (bad in listOf(null, "", "1", "1.2", "1.2.3.4", "01.2.3", "1.02.3", "x1.2.3", "1.2.3-", "1.2.3-01",
            "1.2.3-rc..1", "latest", "v", "1.2.3 beta", "9999999999.0.0")) {
            assertNull("'$bad' should not parse", AppVersion.parse(bad))
        }
    }

    @Test fun comparesNumericallyNotLexically() {
        assertTrue(v("1.10.0") > v("1.9.9"))
        assertTrue(v("2.0.0") > v("1.99.99"))
        assertTrue(v("1.2.10") > v("1.2.9"))
        assertEquals(0, v("v1.2.3").compareTo(v("1.2.3+other")))
    }

    @Test fun semverPrecedenceExample() {
        // SemVer 2.0.0 §11: 1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2
        //                    < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2",
            "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0").map(::v)
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sorted())
        for (i in 1 until ordered.size) assertTrue("${ordered[i - 1]} < ${ordered[i]}", ordered[i - 1] < ordered[i])
    }

    @Test fun versionCodeMatchesGradleRule() {
        assertEquals(10203, v("1.2.3").versionCode)
        assertEquals(1, v("0.0.1").versionCode)
        assertEquals(99_999_999, v("9999.99.99").versionCode)
        assertNull(v("1.100.0").versionCode)
        assertNull(v("10000.0.0").versionCode)
        // Monotonic with the version order inside the range.
        assertTrue(v("1.10.0").versionCode!! > v("1.9.99").versionCode!!)
    }
}
