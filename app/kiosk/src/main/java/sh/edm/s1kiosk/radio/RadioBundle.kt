package sh.edm.s1kiosk.radio

import java.io.File
import java.io.InputStream

/**
 * The signed radio bundle this APK ships (assets/radio-bundle/, built and signed by the release
 * workflow with tools/radio/make-bundle.py) and its installation on the panel (docs/radio.md "Radio
 * bundles"). The app is only the courier: it stages the files in its private dir and starts the root
 * service [RadioHelper.BUNDLE_SERVICE], which checks the Ed25519 signature against the key baked into
 * the boot image, refuses older versions, installs, and restarts the radio helper. The app never
 * decides what is trusted.
 *
 * What the panel has comes from the root-written, world-readable state file [pubFile]
 * (`/data/s1kiosk-pub/radio-bundle`, "<ok|bad|none> <version> <floor>", written by s1kiosk_bundle.sh and
 * the helper's loader), never from a sys.* property: any app may set those, and a fake "installed
 * 999999999" would block every update.
 *
 * Runs on its own thread ([installIfNewer] blocks while the service works, at most ~10 minutes).
 */
class RadioBundle(
    private val props: SysProps,
    private val source: Source,
    private val filesDir: File?,
    private val log: (String) -> Unit = {},
    private val pubFile: File = File(PUB_FILE),
    /** Owner uid of a file without following symlinks (android.system.Os.lstat on the panel), or null. */
    private val ownerUid: (File) -> Int? = ::lstatUid,
    private val now: () -> Long = System::currentTimeMillis,
    private val startWaitMs: Long = 30_000,
    private val maxWaitMs: Long = 600_000,
) {
    /** Where the shipped bundle comes from (the APK's assets on the panel, a directory in tests). */
    interface Source {
        /** The manifest text, or null when this build has no bundle (debug builds). */
        fun manifest(): String?
        fun open(path: String): InputStream
    }

    /** The panel's state file, parsed. */
    data class Installed(val state: String, val version: Int, val floor: Int)

    @Volatile var lastResult: String? = null
        private set
    /** When this process last tried each version: retried at most once an hour, never in a loop. */
    private val tried = HashMap<Int, Long>()

    /** The panel's bundle state, from the root-owned state file; null without a v4 boot image (or before its helper ran). */
    fun installed(): Installed? {
        if (ownerUid(pubFile) != 0 || pubFile.parentFile?.let(ownerUid) != 0) return null
        return runCatching { parsePub(pubFile.readText()) }.getOrNull()
    }

    /** The boot image has the bundle service and the loader (its helper writes the state file at every start). */
    fun loaderPresent(): Boolean = installed() != null

    /** The version of the bundle in this APK, or null. */
    fun shippedVersion(): Int? = source.manifest()?.let { parseVersion(it) }

    /**
     * Installs the shipped bundle when the panel's is missing, older, or marked bad. Returns what
     * happened, or null when nothing was needed (or a retry isn't due yet).
     */
    fun installIfNewer(): String? {
        val inst = installed() ?: return null
        val manifest = source.manifest() ?: return null
        val shipped = parseVersion(manifest) ?: return "radio bundle in this app has no version".also { lastResult = it }
        if (inst.floor > shipped) return null                        // the panel would refuse it anyway
        if (inst.state == "ok" && inst.version >= shipped) return null
        val last = tried[shipped]
        if (last != null && now() - last < RETRY_MS) return null
        tried[shipped] = now()
        val dir = filesDir?.let { File(it, RadioHelper.BUNDLE_STAGE_DIR) } ?: return null
        log("radio bundle $shipped in this app, panel has ${inst.state} ${inst.version}: installing")
        var clean = true
        return try {
            stage(manifest, dir)
            props.set(RadioHelper.PROP_BUNDLE_STATUS, "requested")
            props.set("ctl.start", RadioHelper.BUNDLE_SERVICE)
            val (result, finished) = await()
            clean = finished
            val after = installed()
            when {
                after != null && after.state == "ok" && after.version >= shipped -> "ok: radio bundle $shipped installed"
                result != null -> result
                else -> "no result from the bundle service"
            }.also { log("radio bundle $shipped: $it") }
        } catch (e: Exception) {
            "couldn't stage radio bundle $shipped: ${e.message}"
        } finally {
            // Only once the root service is done: deleting under it would make it fail half-way.
            if (clean) dir.deleteRecursively()
        }.also { lastResult = it }
    }

    /**
     * Waits for the service: it has to start within [startWaitMs] and then finish (init.svc.* back to
     * stopped). Returns its last status and whether it is known to have finished.
     */
    private fun await(): Pair<String?, Boolean> {
        val svc = "init.svc.${RadioHelper.BUNDLE_SERVICE}"
        var waited = 0L
        var started = false
        while (waited < maxWaitMs) {
            props.sleep(500); waited += 500
            val running = props.get(svc) == "running"
            val st = props.get(RadioHelper.PROP_BUNDLE_STATUS)
            val final = st.startsWith("ok:") || st.startsWith("error:")
            if (running || (st.isNotEmpty() && st != "requested")) started = true // a quick run may end between polls
            if (!running && (final || started)) return (if (final) st else null) to true
            if (!started && waited >= startWaitMs) return "the bundle service did not start" to true
        }
        return "the bundle service is still running" to false
    }

    /** Copies manifest, signature and every listed file into [dir], owner-only. */
    fun stage(manifest: String, dir: File) {
        dir.deleteRecursively()
        mkdirPrivate(dir)
        val files = listOf("manifest", "manifest.sig") + filePaths(manifest)
        for (p in files) {
            require(p == "manifest" || p == "manifest.sig" || PATH.matches(p)) { "bad path $p" }
            val f = File(dir, p)
            f.parentFile?.let { var d: File? = it; val chain = ArrayList<File>(); while (d != null && d != dir) { chain += d; d = d.parentFile }; chain.reversed().forEach(::mkdirPrivate) }
            source.open("radio-bundle/$p").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        }
    }

    companion object {
        private val PATH = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]*(/[A-Za-z0-9][A-Za-z0-9._+-]*){0,3}$""")
        private val VERSION_LINE = Regex("""(?m)^version (\d{1,9})$""")
        private val FILE_LINE = Regex("""(?m)^file 0[67][0-7][0-7] \d+ [0-9a-f]{64} (\S+)$""")

        const val PUB_FILE = "/data/s1kiosk-pub/radio-bundle"
        const val RETRY_MS = 60 * 60_000L

        /** "<ok|bad|none> <version> <floor>" -> Installed. */
        fun parsePub(text: String): Installed? {
            val p = text.trim().split(' ')
            if (p.size != 3 || p[0] !in setOf("ok", "bad", "none")) return null
            val v = p[1].toIntOrNull() ?: return null
            val f = p[2].toIntOrNull() ?: return null
            return Installed(p[0], v, f)
        }

        fun lstatUid(f: File): Int? = try { android.system.Os.lstat(f.path).st_uid } catch (t: Throwable) { null }

        fun parseVersion(manifest: String): Int? =
            if (manifest.startsWith("S1RADIO-BUNDLE 1\n")) VERSION_LINE.find(manifest)?.groupValues?.get(1)?.toIntOrNull() else null

        fun filePaths(manifest: String): List<String> = FILE_LINE.findAll(manifest).map { it.groupValues[1] }.toList()

        private fun mkdirPrivate(d: File) {
            d.mkdirs()
            d.setReadable(false, false); d.setReadable(true, true)
            d.setWritable(false, false); d.setWritable(true, true)
            d.setExecutable(false, false); d.setExecutable(true, true)
        }
    }
}

/** The bundle in the APK's assets (assets/radio-bundle/, added by the release build). */
class AssetBundleSource(private val am: android.content.res.AssetManager) : RadioBundle.Source {
    override fun manifest(): String? = try {
        am.open("radio-bundle/manifest").use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: java.io.IOException) {
        null // debug builds: no bundle
    }
    override fun open(path: String): InputStream = am.open(path)
}
