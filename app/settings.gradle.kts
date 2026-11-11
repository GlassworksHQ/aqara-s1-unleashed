pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2/") {
            content { includeGroup("org.mozilla.geckoview") }
        }
    }
}

rootProject.name = "s1-kiosk"
include(":kiosk")

// Reusable libraries extracted from the app (each has its own README).
include(":esphome-api")
project(":esphome-api").projectDir = file("libs/esphome-api")
include(":esphome-api-android")
project(":esphome-api-android").projectDir = file("libs/esphome-api-android")
include(":improv")
project(":improv").projectDir = file("libs/improv")

// rs-matter-android (git submodule, the rs-matter fork's `android` branch): the Matter device
// library that lets the panel be commissioned over BLE and receive a Thread dataset (docs/matter.md).
include(":matter")
project(":matter").projectDir = file("third_party/rs-matter/rs-matter-android/android/matter")
