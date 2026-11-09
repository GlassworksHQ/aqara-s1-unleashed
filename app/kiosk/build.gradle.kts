plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.aguilaair.s1kiosk"
    // GeckoView 159 requires compileSdk 37.2 (and pulls androidx.core 1.19 -> AGP 9.1+).
    // This only affects compilation; the app still runs on the panel's Android 10 (API 29).
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.aguilaair.s1kiosk"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The Panel Hub S1 Plus runs 32-bit Android 10 (A133 with an armeabi-v7a userspace).
        ndk { abiFilters += listOf("armeabi-v7a") }

        // Default dashboard; overridable at runtime (intent extra / settings dialog).
        buildConfigField("String", "DEFAULT_URL", "\"http://192.168.1.100:8123/tablet-home\"")
    }

    buildFeatures { buildConfig = true }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No release keystore is committed; sign release builds yourself, or use the debug APK.
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

    // JVM unit tests for the ESPHome API (against aioesphomeapi) and openWakeWord buffering.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
