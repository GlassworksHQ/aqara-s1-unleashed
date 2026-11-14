// The Bluetooth proxy engine for Android: BLE scanning for an ESPHome Bluetooth proxy (esphome-api).
// No Pane API: the app's bluetooth-proxy pane wraps it (#121, #152). See README.md in this directory.
plugins {
    id("com.android.library")
}

android {
    namespace = "sh.edm.bluetoothproxy"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":esphome-api"))
    // @ChecksSdkIntAtLeast for lint; class-retention, nothing at runtime.
    compileOnly("androidx.annotation:annotation:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

