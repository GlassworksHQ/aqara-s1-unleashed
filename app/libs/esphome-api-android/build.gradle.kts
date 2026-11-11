// Android helpers for esphome-api: mDNS advertising with NsdManager. See README.md in this directory.
plugins {
    id("com.android.library")
}

android {
    namespace = "sh.edm.esphome.android"
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
    api(project(":esphome-api"))

    testImplementation("junit:junit:4.13.2")
}
