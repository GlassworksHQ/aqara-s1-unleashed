plugins {
    id("com.android.application") version "9.3.3" apply false
    // AGP 9 has built-in Kotlin (it bundles KGP 2.2.x). GeckoView 159 is built
    // against kotlin-stdlib 2.4.x, so pin a newer KGP on the classpath; AGP's
    // built-in Kotlin support then uses it.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}
