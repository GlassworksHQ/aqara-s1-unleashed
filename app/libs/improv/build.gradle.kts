// Improv Wi-Fi for Android: the protocol and state machines (plain Kotlin) plus a BLE GATT server.
// See README.md in this directory.
plugins {
    id("com.android.library")
}

android {
    namespace = "sh.edm.improv"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 23
        // The androidTest checks run on an API 23 emulator in CI (pr.yml, libs-api23).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // @ChecksSdkIntAtLeast and @RequiresApi for lint; class-retention annotations, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
