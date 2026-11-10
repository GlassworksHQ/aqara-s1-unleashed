package sh.edm.s1kiosk.update

import java.security.MessageDigest

/** What PackageManager says about an APK (archive or installed); [signers] are DER certificates. */
class ApkFacts(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val signers: List<ByteArray>,
)

/**
 * Pre-install checks of a downloaded APK against the installed app (pure; the PackageManager calls
 * are in [UpdateManager]). Android itself refuses a different key for the same package on
 * `pm install -r` too; checking first gives a clear status instead of an install error, and covers
 * the PackageInstaller fallback before the confirmation is shown.
 */
object ApkCheck {

    /** Null if [archive] may be installed over [installed] as release [expected], else why not. */
    fun refusal(archive: ApkFacts?, installed: ApkFacts, expected: AppVersion): String? {
        if (archive == null) return "the download is not a valid APK"
        if (archive.packageName != installed.packageName) {
            return "the APK is for ${archive.packageName.take(60)}, not ${installed.packageName}"
        }
        if (installed.signers.isEmpty()) return "cannot read this app's signing certificate"
        if (archive.signers.isEmpty()) return "the APK is not signed"
        if (fingerprints(archive.signers) != fingerprints(installed.signers)) {
            return "the APK is signed with a different key than this app " +
                "(debug or self-built install? reinstall once from the installer, see docs/releasing.md)"
        }
        if (archive.versionName != expected.toString()) {
            return "the APK's version ${archive.versionName?.take(30)} does not match release $expected"
        }
        val code = expected.versionCode
        if (code != null && archive.versionCode != code.toLong()) {
            return "the APK's versionCode ${archive.versionCode} does not match release $expected ($code)"
        }
        if (archive.versionCode <= installed.versionCode) {
            return "the APK (versionCode ${archive.versionCode}) is not newer than this app (${installed.versionCode})"
        }
        return null
    }

    /** SHA-256 fingerprints of DER certificates, as a set (signer order doesn't matter). */
    fun fingerprints(certs: List<ByteArray>): Set<String> =
        certs.map { Releases.hex(MessageDigest.getInstance("SHA-256").digest(it)) }.toSet()
}

/**
 * Contract with the root install service in the boot image (tools/root/s1kiosk_update.{rc,sh},
 * docs/research/root.md). The app writes [PENDING_PATH] (private dir, only the kiosk uid can
 * write it), sets [PROP_STATUS] to `requested` and `ctl.start`s [SERVICE]; the service installs
 * that file and nothing else, then reports in [PROP_STATUS].
 */
object UpdateHelper {
    const val SERVICE = "s1kiosk_update"
    /** Set to [VERSION] by init at boot when the service is in the boot image. */
    const val PROP_VERSION = "sys.s1kiosk.update.version"
    const val VERSION = 1
    /** `<state>[: detail]`, state = requested (app) | running | ok | error (service). */
    const val PROP_STATUS = "sys.s1kiosk.update.status"
    const val PENDING_DIR = "update"
    const val PENDING_NAME = "pending.apk"
    /** Where the service looks; the app's `filesDir/update/pending.apk`. */
    const val PENDING_PATH = "/data/data/sh.edm.s1kiosk/files/$PENDING_DIR/$PENDING_NAME"

    data class Status(val state: String, val detail: String)

    private val STATES = setOf("requested", "running", "ok", "error")

    /** Parses [PROP_STATUS]; null if unset or malformed. */
    fun parseStatus(raw: String?): Status? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val state = s.substringBefore(':').trim()
        if (state !in STATES) return null
        return Status(state, if (':' in s) s.substringAfter(':').trim() else "")
    }
}
