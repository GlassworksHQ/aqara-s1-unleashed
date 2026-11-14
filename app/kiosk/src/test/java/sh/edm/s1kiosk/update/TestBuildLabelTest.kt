package sh.edm.s1kiosk.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #263: a test build says so next to its version; a release shows only its version. */
class TestBuildLabelTest {
    private val repo = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, ".github/workflows").isDirectory }

    @Test fun aTestBuildSaysSo() = assertEquals("0.4.3 · test build 28c157d", AppVersion.label("0.4.3", "28c157d"))

    @Test fun aReleaseIsItsVersion() = assertEquals("0.4.3", AppVersion.label("0.4.3", ""))

    /** The label comes from the build: test-build.yml passes the commit, release.yml passes none. */
    @Test fun onlyTestBuildsPassTheirCommit() {
        assertTrue(File(repo, ".github/workflows/test-build.yml").readText().contains("S1_TEST_BUILD: \${{ env.SHORT_SHA }}"))
        assertFalse(File(repo, ".github/workflows/release.yml").readText().contains("S1_TEST_BUILD"))
        assertTrue(File(repo, "app/kiosk/build.gradle.kts").readText().contains("buildConfigField(\"String\", \"TEST_BUILD\""))
    }
}
