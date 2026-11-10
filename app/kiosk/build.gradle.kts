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

        // The Panel Hub S1 Plus runs 32-bit Android 10 (A133 with an armeabi-v7a userspace).
        ndk { abiFilters += listOf("armeabi-v7a") }

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
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview-armeabi-v7a:159.0.20261102144711")
    // Voice assistant: openWakeWord inference (ships armeabi-v7a, minSdk 19).
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    // ESPHome API Noise encryption: X25519 + ChaCha20-Poly1305 (lightweight API, no JCA provider).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80")
    // Audio playback (TTS, announcements, media player): streams over HTTP in-process, avoiding the
    // vendor CedarX HTTP stack. Same version GeckoView already ships.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    // QR code on the sign-in screen (encoder only, pure Java, Apache-2.0).
    implementation("com.google.zxing:core:3.5.3")

    // JVM unit tests for the ESPHome API (against aioesphomeapi) and openWakeWord buffering.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

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
