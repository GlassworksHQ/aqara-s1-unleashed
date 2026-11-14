plugins {
    id("com.android.application")
}

// Release version: -Ps1Version=1.2.3 or S1_VERSION=1.2.3 (the release workflow sets it from the tag).
// versionCode = major*10000 + minor*100 + patch, the same rule the app uses to compare releases
// (update/AppVersion.kt). Unset: the development defaults below, so debug builds are unchanged.
val s1Version: String? = ((findProperty("s1Version") as String?) ?: System.getenv("S1_VERSION"))
    ?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
val s1VersionCode: Int? = s1Version?.let { v ->
    val m = Regex("""^(0|[1-9]\d{0,3})\.(0|[1-9]\d?)\.(0|[1-9]\d?)$""").matchEntire(v)
        ?: throw GradleException("S1_VERSION '$v' must be MAJOR.MINOR.PATCH with minor and patch below 100")
    val (major, minor, patch) = m.destructured
    major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

// The ABI this APK is built for: -Ps1Abi=arm64-v8a or S1_ABI. Unset: the S1's. One APK per ABI, because
// each per-ABI GeckoView AAR carries its own classes.jar and omni.ja, so two cannot share a classpath
// (docs/releasing.md "ABIs"). Every ABI's APK has the same versionCode. Each frame declares the ABIs its
// device runs (`abis` in frames/<id>/design/frame.json), and the release builds them all (#98).
val frameAbis: Map<String, List<String>> = layout.projectDirectory.dir("../../frames").asFile.listFiles().orEmpty()
    .map { File(it, "design/frame.json") }.filter { it.isFile }.sortedBy { it.parentFile.parentFile.name }
    .associate { f ->
        val abis = ((groovy.json.JsonSlurper().parse(f) as? Map<*, *>)?.get("abis") as? List<*>)
            ?.takeIf { it.isNotEmpty() && it.all { a -> a is String } }
            ?: throw GradleException("$f: abis must be a non-empty list of ABI names, e.g. [\"armeabi-v7a\"]")
        f.parentFile.parentFile.name to abis.map { it as String }
    }
val releaseAbis = frameAbis.values.flatten().distinct()
    .ifEmpty { throw GradleException("no frame declares abis (frames/*/design/frame.json)") }
val s1Abi: String = ((findProperty("s1Abi") as String?) ?: System.getenv("S1_ABI"))
    ?.trim()?.takeIf { it.isNotEmpty() }
    ?: frameAbis["aqara-s1-plus"]?.first() ?: throw GradleException("frames/aqara-s1-plus/design/frame.json is missing")
if (s1Abi !in releaseAbis) throw GradleException("s1Abi '$s1Abi' must be one of ${releaseAbis.joinToString()}")

// Release signing comes only from the environment (CI secrets or a local shell; docs/releasing.md).
// Nothing set: the release APK is unsigned (never signed with the debug key). Partly set, or
// S1_REQUIRE_RELEASE_SIGNING=true without a key: the release build fails (checkReleaseSigning).
val signingEnv = listOf("S1_KEYSTORE_FILE", "S1_KEYSTORE_PASSWORD", "S1_KEY_ALIAS", "S1_KEY_PASSWORD")
    .associateWith { System.getenv(it)?.takeIf(String::isNotEmpty) }
val haveReleaseKey = signingEnv.values.all { it != null }

android {
    namespace = "sh.edm.s1kiosk"
    // GeckoView 159 requires compileSdk 37.2 (and pulls androidx.core 1.19 -> AGP 9.1+).
    // This only affects compilation; the app still runs on the panel's Android 10 (API 29).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "sh.edm.s1kiosk"
        minSdk = 26
        targetSdk = 34
        versionCode = s1VersionCode ?: 1
        versionName = s1Version ?: "0.1.0"

        // The Panel Hub S1 Plus runs 32-bit Android 10 (A133 with an armeabi-v7a userspace): the default.
        ndk { abiFilters += listOf(s1Abi) }

        // No hard-coded dashboard: the default URL setting is "auto" (Home Assistant is discovered,
        // see docs/onboarding.md). A URL saved in the prefs is kept.
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (haveReleaseKey) {
            create("release") {
                storeFile = file(signingEnv.getValue("S1_KEYSTORE_FILE")!!)
                storePassword = signingEnv.getValue("S1_KEYSTORE_PASSWORD")
                keyAlias = signingEnv.getValue("S1_KEY_ALIAS")
                keyPassword = signingEnv.getValue("S1_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No keystore is committed: signed only with the S1_* environment above, else unsigned.
            signingConfig = if (haveReleaseKey) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    // The signed radio bundle (docs/radio.md "Radio bundles"): S1_RADIO_BUNDLE_ASSETS is a directory
    // holding radio-bundle/ (tools/radio/make-bundle.py --out). The release workflow sets it; other
    // builds ship no bundle and the app leaves the panel's root side as it is.
    System.getenv("S1_RADIO_BUNDLE_ASSETS")?.takeIf { it.isNotEmpty() }?.let { dir ->
        if (!file("$dir/radio-bundle/manifest").isFile || !file("$dir/radio-bundle/manifest.sig").isFile) {
            throw GradleException("S1_RADIO_BUNDLE_ASSETS=$dir has no radio-bundle/manifest(.sig)")
        }
        sourceSets.getByName("main").assets.srcDir(dir)
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-$s1Abi:159.0.20261102144711") {
        // Proprietary Google Play services (play-services-fido/-base/-basement/-tasks), used only for
        // WebAuthn/passkeys. The panel has no Play services, so they could never run; excluding them
        // keeps the APK free software (as Firefox's F-Droid builds do). HA's login doesn't use WebAuthn.
        exclude(group = "com.google.android.gms")
    }
    // Voice assistant: wake word inference, microWakeWord and the legacy openWakeWord (ships every ABI;
    // abiFilters keeps the one built, minSdk 19).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // Libraries extracted from this app (app/libs/). esphome-api brings BouncyCastle (Noise), which
    // Sendspin's encryption also uses.
    implementation(project(":esphome-api-android")) // brings :esphome-api
    implementation(project(":improv"))
    implementation(project(":bluetooth-proxy")) // the Bluetooth proxy pane's engine (#121)
    // Audio playback (TTS, announcements, media player): streams over HTTP in-process, avoiding the
    // vendor CedarX HTTP stack. Same version GeckoView already ships.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // QR code on the sign-in screen (encoder only, pure Java, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")
    // XZ decoder (pure Java, 0BSD) for Silicon Labs' zigbeed .deb (radio/SisdkZigbeed.kt).
    implementation("org.tukaani:xz:1.10")

    // JVM unit tests for the ESPHome API (against aioesphomeapi) and the wake word engines.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    // Mock-up screenshots of the real settings/setup views (RadioMockups, only with -Ps1MockupDir=<dir>).
    testImplementation("org.robolectric:robolectric:4.17")
}

// RadioMockups writes PNGs of the radio views when asked: ./gradlew :kiosk:testDebugUnitTest
// --tests '*RadioMockups*' -Ps1MockupDir=<dir>. Without the property it is skipped.
tasks.withType<Test>().configureEach {
    (project.findProperty("s1MockupDir") as String?)?.let { systemProperty("s1.mockupDir", it) }
    // PanelEntitiesPinTest rewrites its golden file with -Ps1RegenGolden=true.
    (project.findProperty("s1RegenGolden") as String?)?.let { systemProperty("s1.regenGolden", it) }
    // ApkAbiListsTest reads the workflows, this file, every frame's frame.json and frame-abis.sh. Declared
    // as inputs, so a change to any of them re-runs the tests instead of taking them UP-TO-DATE or FROM-CACHE.
    inputs.dir(rootProject.file("../.github/workflows")).withPropertyName("workflows")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("build.gradle.kts").withPropertyName("kioskBuildScript").withPathSensitivity(PathSensitivity.NONE)
    inputs.files(fileTree(rootProject.file("../frames")) { include("*/design/frame.json") }, rootProject.file("../tools/release/frame-abis.sh"))
        .withPropertyName("frameAbis").withPathSensitivity(PathSensitivity.RELATIVE)
    // WakeWordsTest checks that the host fetch script pins the same models as the app.
    inputs.file(rootProject.file("../tools/voice/fetch-models.sh")).withPropertyName("fetchModels")
        .withPathSensitivity(PathSensitivity.NONE)
    // ContentScriptReadyTest runs the web extension's content.js under node: a change to it re-runs the tests.
    inputs.dir("src/main/assets/extension").withPropertyName("webExtension").withPathSensitivity(PathSensitivity.RELATIVE)
    // PaneCatalogTest reads each pane's pane.json and icon.svg, which no compile task takes in (#231).
    inputs.files(fileTree("src/main/java") { include("**/pane.json", "**/icon.svg") }).withPropertyName("paneCatalog")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// Open source licences screen (hidden settings → "Open source licences"): the repo's
// THIRD-PARTY-NOTICES.md, LICENSE and LICENSES/*.txt are copied into the APK as assets/licenses/ at
// build time, so the app always shows the committed texts. Local files only: no network, no plugin.
abstract class LicenceAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val notices: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val projectLicence: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val texts: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:javax.inject.Inject abstract val fs: FileSystemOperations

    @TaskAction fun copy() {
        fs.sync {
            into(outputDir.dir("licenses"))
            from(notices)
            from(projectLicence)
            from(texts) { include("*.txt"); into("texts") }
        }
    }
}
val repoRoot = layout.projectDirectory.dir("../..")
val thirdPartyNotices = repoRoot.file("THIRD-PARTY-NOTICES.md")
val licenceAssets = tasks.register<LicenceAssets>("licenceAssets") {
    notices.set(thirdPartyNotices)
    projectLicence.set(repoRoot.file("LICENSE"))
    texts.set(repoRoot.dir("LICENSES"))
    outputDir.set(layout.buildDirectory.dir("generated/licence-assets"))
}
androidComponents {
    onVariants { v -> v.sources.assets?.addGeneratedSourceDirectory(licenceAssets, LicenceAssets::outputDir) }
}

// Every library in the release APK must be listed, with its version, in THIRD-PARTY-NOTICES.md
// (`group:name:version`). Runs before release builds, so a dependency bump can't ship without its notice.
// Reads only the resolved dependency graph Gradle already has for the build.
val checkThirdPartyNotices = tasks.register("checkThirdPartyNotices") {
    val modules = configurations.named("releaseRuntimeClasspath").flatMap { c ->
        c.incoming.resolutionResult.rootComponent.map { root ->
            val seen = sortedSetOf<String>()
            val todo = ArrayDeque(listOf(root))
            val visited = mutableSetOf<org.gradle.api.artifacts.result.ResolvedComponentResult>()
            while (todo.isNotEmpty()) {
                val comp = todo.removeFirst()
                if (!visited.add(comp)) continue
                (comp.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)
                    ?.let { seen += "${it.group}:${it.module}:${it.version}" }
                comp.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                    .forEach { todo += it.selected }
            }
            seen.toList()
        }
    }
    val noticesFile = thirdPartyNotices
    inputs.file(noticesFile)
    inputs.property("modules", modules)
    doLast {
        val text = noticesFile.asFile.readText()
        val missing = modules.get().filter { "`$it`" !in text }
        if (missing.isNotEmpty()) throw GradleException(
            "THIRD-PARTY-NOTICES.md does not list these APK libraries (add them, with licence and source):\n  " +
                missing.joinToString("\n  "))
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkThirdPartyNotices) }

// Fails a release build early and clearly instead of producing an APK nobody can install as an update.
val checkReleaseSigning by tasks.registering {
    val partial = signingEnv.filterValues { it == null }.keys.takeIf { it.size in 1..3 }
    val required = System.getenv("S1_REQUIRE_RELEASE_SIGNING") == "true"
    val keyFile = signingEnv["S1_KEYSTORE_FILE"]
    val keyFileMissing = keyFile != null && !file(keyFile).isFile
    doLast {
        if (partial != null) throw GradleException("release signing: missing ${partial.joinToString()} (see docs/releasing.md)")
        if (keyFileMissing) throw GradleException("release signing: S1_KEYSTORE_FILE does not exist")
        if (required && keyFile == null) throw GradleException("release signing required (S1_REQUIRE_RELEASE_SIGNING) but no S1_KEYSTORE_* set")
        if (keyFile == null) logger.warn("release APK is UNSIGNED: set S1_KEYSTORE_FILE, S1_KEYSTORE_PASSWORD, S1_KEY_ALIAS, S1_KEY_PASSWORD to sign it")
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseSigning) }
