package sh.edm.s1kiosk.update

import sh.edm.s1kiosk.BuildConfig

/**
 * A release version, `MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]` (SemVer 2.0.0), with an optional
 * leading `v` as in git tags. Ordering follows SemVer precedence: numeric core, then a pre-release
 * sorts before the release, identifiers compared numerically or lexically; build metadata is ignored.
 *
 * The same rule derives the Android versionCode (app/kiosk/build.gradle.kts):
 * `major * 10000 + minor * 100 + patch`, so minor and patch must stay below 100.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String> = emptyList(),
) : Comparable<AppVersion> {

    val isPreRelease: Boolean get() = preRelease.isNotEmpty()

    /** Deterministic versionCode (see class doc); null if out of range. */
    val versionCode: Int?
        get() = if (major in 0..9_999 && minor in 0..99 && patch in 0..99) major * 10_000 + minor * 100 + patch else null

    override fun compareTo(other: AppVersion): Int {
        compareValues(major, other.major).let { if (it != 0) return it }
        compareValues(minor, other.minor).let { if (it != 0) return it }
        compareValues(patch, other.patch).let { if (it != 0) return it }
        // A version without pre-release identifiers has higher precedence.
        if (preRelease.isEmpty()) return if (other.preRelease.isEmpty()) 0 else 1
        if (other.preRelease.isEmpty()) return -1
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val c = compareIdentifier(preRelease[i], other.preRelease[i])
            if (c != 0) return c
        }
        return compareValues(preRelease.size, other.preRelease.size)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + if (preRelease.isEmpty()) "" else "-" + preRelease.joinToString(".")

    companion object {
        /**
         * The version people see (#263): "0.4.3", or "0.4.3 · test build 28c157d" for a test build, which
         * reuses the release's versionName so it installs over it. [testBuild] is `BuildConfig.TEST_BUILD`.
         */
        fun label(version: String, testBuild: String): String = if (testBuild.isEmpty()) version else "$version · test build $testBuild"

        /** [label] of this build. */
        val shown: String get() = label(BuildConfig.VERSION_NAME, BuildConfig.TEST_BUILD)

        private val RE = Regex(
            """^[vV]?(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})""" +
                """(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?$""",
        )

        /** Parses a version or tag (`1.2.3`, `v1.2.3-rc.1+abc`); null if it is not SemVer. */
        fun parse(s: String?): AppVersion? {
            val m = RE.matchEntire(s?.trim() ?: return null) ?: return null
            val pre = m.groupValues[4].takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            // SemVer: numeric pre-release identifiers must not have leading zeros.
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return AppVersion(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), pre)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val an = a.all(Char::isDigit)
            val bn = b.all(Char::isDigit)
            return when {
                an && bn -> compareValues(a.length, b.length).takeIf { it != 0 } ?: a.compareTo(b).coerceIn(-1, 1)
                an -> -1 // numeric identifiers have lower precedence than alphanumeric ones
                bn -> 1
                else -> a.compareTo(b).coerceIn(-1, 1)
            }
        }
    }
}
