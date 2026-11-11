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
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
