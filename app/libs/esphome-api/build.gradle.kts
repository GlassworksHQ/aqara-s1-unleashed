// ESPHome native API, device side: a pure Kotlin/JVM library (no Android dependency).
// See README.md in this directory.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("com.toasttab.expediter") version "0.2.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Noise: X25519 + ChaCha20-Poly1305 (lightweight API, no JCA provider). Part of the public API
    // surface only through NoiseCipher/NoiseResponder, but consumers need it at runtime.
    api("org.bouncycastle:bcprov-jdk18on:1.80")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}

// The library runs on Android 6 (API 23), and lint can't check a JVM module. `expedite` checks our
// classes, and the dependency classes they reach, against API 23's (Gummy Bears, with D8's
// backports). kotlin-stdlib's jdk8 classes are skipped: the stdlib loads them only on a JDK, never on
// Android. `test` runs it, so CI does.
expediter {
    platform { android { sdk = 23 } }
    ignore { callerStartsWith("kotlin/internal/jdk8/", "kotlin/time/jdk8/") }
    failOnIssues = true
}
tasks.named("test") { dependsOn("expedite") }

// EspHomeKeepaliveTest runs the real aioesphomeapi client when ESPHOME_PYTHON is set (see the test).
