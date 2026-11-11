// ESPHome native API, device side: a pure Kotlin/JVM library (no Android dependency).
// See README.md in this directory.
plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
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

// EspHomeKeepaliveTest runs the real aioesphomeapi client when ESPHOME_PYTHON is set (see the test).
