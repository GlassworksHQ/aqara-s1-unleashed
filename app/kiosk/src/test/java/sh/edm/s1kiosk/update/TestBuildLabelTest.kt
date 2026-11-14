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

    /**
     * Every place that shows the app's version shows [AppVersion.shown], so a test build says so there too
     * (the Manage page, the settings' About). The raw versionName stays only where a version is compared or
     * sent as a protocol field: updates, HA's DeviceInfo and version entity, Sendspin, Improv, user agents.
     * A merge that brings `BuildConfig.VERSION_NAME` back into a page fails here.
     */
    @Test fun pagesShowTheLabelNotTheRawVersion() {
        val main = File(repo, "app/kiosk/src/main/java/sh/edm/s1kiosk")
        val raw = setOf("update/AppVersion.kt", "update/UpdateManager.kt", "esphome/PanelDevice.kt", "sendspin/SendspinPlayer.kt",
            "improv/ImprovController.kt", "improv/ImprovSerialServer.kt", "improv/ImprovIdentity.kt", "radio/RadioPane.kt")
        val found = main.walkTopDown().filter { it.isFile && it.extension == "kt" && "BuildConfig.VERSION_NAME" in it.readText() }
            .map { it.relativeTo(main).path }.filter { it !in raw }.toList()
        assertTrue("show AppVersion.shown, not BuildConfig.VERSION_NAME, in: $found", found.isEmpty())
    }

    /** The label comes from the build: test-build.yml passes the commit, release.yml passes none. */
    @Test fun onlyTestBuildsPassTheirCommit() {
        assertTrue(File(repo, ".github/workflows/test-build.yml").readText().contains("S1_TEST_BUILD: \${{ env.SHORT_SHA }}"))
        assertFalse(File(repo, ".github/workflows/release.yml").readText().contains("S1_TEST_BUILD"))
        assertTrue(File(repo, "app/kiosk/build.gradle.kts").readText().contains("buildConfigField(\"String\", \"TEST_BUILD\""))
    }
}
