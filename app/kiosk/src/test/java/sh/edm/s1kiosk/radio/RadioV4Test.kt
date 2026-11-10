package sh.edm.s1kiosk.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/** Props with a fake init; the bundle service answers [bundleAnswer] when started. */
private class V4Props(version: Int = 4) : SysProps {
    val props = HashMap<String, String>()
    val ops = ArrayList<String>()
    var bundleAnswer: String? = "ok: radio bundle 30200 installed (5 files, 1 bytes)"
    var stagedSeen: List<String> = emptyList()
    var stageDir: File? = null
    /** sleeps the fake bundle service stays "running" before it answers and stops; -1 = never starts */
    var serviceTicks = 2
    private var serviceRuns = -1
    var stagedWhileRunning: Boolean? = null
    /** what the root service writes to the state file when it answers ok */
    var onOk: (() -> Unit)? = null

    init {
        props[RadioHelper.PROP_SVC_STATE] = "running"
        props[RadioHelper.PROP_VERSION] = version.toString()
    }

    override fun get(name: String) = props[name] ?: ""
    override fun set(name: String, value: String) {
        when (name) {
            "ctl.start" -> {
                ops += "start $value"
                if (value == RadioHelper.BUNDLE_SERVICE) {
                    stagedSeen = stageDir?.walkTopDown()?.filter { it.isFile }?.map { it.relativeTo(stageDir!!).path }?.sorted()?.toList().orEmpty()
                    props["init.svc.$value"] = "running"
                    serviceRuns = serviceTicks
                } else props["init.svc.$value"] = "running"
            }
            "ctl.stop" -> { ops += "stop $value"; props["init.svc.$value"] = "stopped" }
            "ctl.restart" -> ops += "restart $value"
            else -> { ops += "set $name=$value"; props[name] = value }
        }
    }
    override fun sleep(ms: Long) {
        if (serviceRuns < 0) return
        if (serviceRuns > 0) { serviceRuns--; stagedWhileRunning = stageDir?.exists(); return }
        serviceRuns = -1
        bundleAnswer?.let { props[RadioHelper.PROP_BUNDLE_STATUS] = it; if (it.startsWith("ok:")) onOk?.invoke() }
        props["init.svc.${RadioHelper.BUNDLE_SERVICE}"] = "stopped"
    }
}

class RadioV4Test {
    @get:Rule val tmp = TemporaryFolder()

    // ------------------------------------------------------------------------------ firmware

    private var clock = 1_000_000L
    private fun fw(p: V4Props) = RadioFirmware(p, tmp.root, now = { clock })
    private val stockSha = "d7b79c6fab1e"

    @Test
    fun targetsAndStatusParse() {
        assertEquals(
            listOf(RadioFirmware.Target("stock", stockSha, "0017"), RadioFirmware.Target("rcp-2025", "0123456789ab", "0018")),
            RadioFirmware.parseTargets("stock:$stockSha:0017 rcp-2025:0123456789ab:0018 junk ::"),
        )
        assertEquals(RadioFirmware.Status("ok", "stock", "APP 0017 (VERSION:x)"), RadioFirmware.parseStatus("ok stock: APP 0017 (VERSION:x)"))
        assertEquals(RadioFirmware.Status("idle", "stock", "never flashed by the kiosk"), RadioFirmware.parseStatus("idle stock: never flashed by the kiosk"))
        assertNull(RadioFirmware.parseStatus("weird"))
        assertNull(RadioFirmware.parseStatus(""))
    }

    @Test
    fun flashNeedsAnArmedImageAndWritesOneRequest() {
        val p = V4Props().apply { props[RadioHelper.PROP_FW_TARGETS] = "stock:$stockSha:0017"; props[RadioHelper.PROP_FW] = "idle stock: never flashed by the kiosk" }
        val f = fw(p)
        val req = File(tmp.root, RadioHelper.FW_REQUEST_FILE)
        assertNotNull(f.flash()) // nothing armed
        assertFalse(req.exists())
        assertEquals("Image from the radio bundle: not available on this panel", f.select(RadioFirmware.BUNDLE)) // no bundle image
        assertNull(f.select(RadioFirmware.STOCK))
        assertEquals(RadioFirmware.STOCK, f.selected())
        assertTrue(f.statusText().contains("armed: Aqara stock image, press “Flash radio firmware” within 120 s"))
        assertNull(f.flash())
        assertEquals("stock $stockSha\n", req.readText())
        assertEquals(RadioFirmware.KEEP, f.selected()) // disarmed after one request
        req.delete()
        assertNotNull(f.flash()) // pressing again does nothing
        assertFalse(req.exists())
    }

    @Test
    fun armingExpires() {
        val p = V4Props().apply { props[RadioHelper.PROP_FW_TARGETS] = "stock:$stockSha:0017" }
        val f = fw(p)
        f.select(RadioFirmware.STOCK)
        clock += RadioFirmware.ARM_MS + 1
        assertEquals(RadioFirmware.KEEP, f.selected())
        assertNotNull(f.flash())
        assertFalse(File(tmp.root, RadioHelper.FW_REQUEST_FILE).exists())
    }

    @Test
    fun noFlashWhileFlashingOrWithAnOldHelper() {
        val p = V4Props().apply {
            props[RadioHelper.PROP_FW_TARGETS] = "stock:$stockSha:0017"; props[RadioHelper.PROP_FW] = "flashing stock: 40% (do not unplug the panel)"
        }
        val f = fw(p)
        f.select(RadioFirmware.STOCK)
        assertEquals("a flash is already running", f.flash())
        assertTrue(f.statusText().startsWith("FLASHING stock"))
        val old = V4Props(version = 3).apply { props[RadioHelper.PROP_FW_TARGETS] = "stock:$stockSha:0017" }
        val f3 = fw(old)
        assertTrue(f3.select(RadioFirmware.STOCK)!!.contains("root helper v4 (installed: v3)"))
        assertTrue(f3.statusText().contains("update the panel's boot image once"))
    }

    // ------------------------------------------------------------------------------ bundle

    private val manifest = """
        S1RADIO-BUNDLE 1
        version 30200
        created test
        file 0700 3 ${"a".repeat(64)} s1kiosk_radio.sh
        file 0644 3 ${"b".repeat(64)} sisdk/lib/libc.so.6
        end
    """.trimIndent() + "\n"

    private fun source(m: String? = manifest) = object : RadioBundle.Source {
        override fun manifest() = m
        override fun open(path: String): InputStream = path.substringAfterLast('/').toByteArray().inputStream()
    }

    @Test
    fun bundleManifestParsing() {
        assertEquals(30200, RadioBundle.parseVersion(manifest))
        assertNull(RadioBundle.parseVersion("version 3\n"))
        assertEquals(listOf("s1kiosk_radio.sh", "sisdk/lib/libc.so.6"), RadioBundle.filePaths(manifest))
        assertEquals(RadioBundle.Installed("ok", 302, 302), RadioBundle.parsePub("ok 302 302\n"))
        assertEquals(RadioBundle.Installed("bad", 302, 400), RadioBundle.parsePub("bad 302 400"))
        assertNull(RadioBundle.parsePub("ok 302"))
        assertNull(RadioBundle.parsePub("weird 1 1"))
    }

    private var clockMs = 1_000_000L
    /** The root-written state file, owned by root (uid 0) unless [owner] says otherwise. */
    private fun bundle(p: V4Props, state: String?, owner: Int = 0): Pair<RadioBundle, File> {
        val pub = File(tmp.root, "pub/radio-bundle")
        pub.parentFile.mkdirs()
        if (state != null) pub.writeText(state) else pub.delete()
        p.stageDir = File(tmp.root, RadioHelper.BUNDLE_STAGE_DIR)
        p.onOk = { pub.writeText("ok 30200 30200\n") }
        val b = RadioBundle(p, source(), tmp.root, pubFile = pub, ownerUid = { f -> if (f.exists()) owner else null },
            now = { clockMs }, startWaitMs = 2_000, maxWaitMs = 10_000)
        return b to pub
    }

    @Test
    fun bundleIsStagedInstalledAndCleanedUpOnlyWhenTheServiceIsDone() {
        val p = V4Props()
        val (b, _) = bundle(p, "ok 30100 30100\n")
        assertEquals("ok: radio bundle 30200 installed", b.installIfNewer())
        assertEquals(listOf("manifest", "manifest.sig", "s1kiosk_radio.sh", "sisdk/lib/libc.so.6"), p.stagedSeen)
        assertTrue(p.ops.containsAll(listOf("set ${RadioHelper.PROP_BUNDLE_STATUS}=requested", "start ${RadioHelper.BUNDLE_SERVICE}")))
        assertEquals(true, p.stagedWhileRunning) // not deleted under the running service
        assertFalse(p.stageDir!!.exists())
        assertNull(b.installIfNewer()) // the state file now says 30200
    }

    @Test
    fun aSpoofedPropertyCannotBlockAnUpdate() {
        val p = V4Props().apply { props[RadioHelper.PROP_BUNDLE] = "ok 999999999"; props[RadioHelper.PROP_BUNDLE_LOADER] = "" }
        val (b, _) = bundle(p, "ok 30100 30100\n")
        assertEquals("ok: radio bundle 30200 installed", b.installIfNewer())
    }

    @Test
    fun aStateFileNotOwnedByRootIsIgnored() {
        val p = V4Props()
        val (b, _) = bundle(p, "ok 999999999 999999999\n", owner = 10077)
        assertNull(b.installed())
        assertNull(b.installIfNewer()) // no trustworthy state: not a v4 panel as far as we can tell
        assertTrue(p.ops.none { it.contains(RadioHelper.BUNDLE_SERVICE) })
    }

    @Test
    fun bundleNotInstalledWhenNotNeeded() {
        for (st in listOf(null, "ok 30200 30200\n", "ok 40000 40000\n", "none 0 40000\n")) {
            val p = V4Props()
            val (b, _) = bundle(p, st)
            assertNull(st, b.installIfNewer())
            assertTrue(st, p.ops.none { it.contains(RadioHelper.BUNDLE_SERVICE) })
        }
        val debug = V4Props()
        val pub = File(tmp.root, "pub2/radio-bundle").apply { parentFile.mkdirs(); writeText("none 0 0") }
        assertNull(RadioBundle(debug, source(null), tmp.root, pubFile = pub, ownerUid = { 0 }).installIfNewer())
    }

    @Test
    fun aBadBundleOfTheSameVersionIsReinstalledAndFailuresAreRetriedHourly() {
        val p = V4Props().apply { bundleAnswer = "error: bad signature (not signed with the project's key)" }
        val (b, _) = bundle(p, "bad 30200 30200\n")
        assertEquals("error: bad signature (not signed with the project's key)", b.installIfNewer())
        assertNull(b.installIfNewer()) // not again right away
        clockMs += RadioBundle.RETRY_MS + 1
        p.bundleAnswer = "ok: radio bundle 30200 installed (2 files, 6 bytes)"
        assertEquals("ok: radio bundle 30200 installed", b.installIfNewer())
    }

    @Test
    fun aServiceThatNeverStartsLeavesNothingBehindAndOneStillRunningKeepsItsFiles() {
        val p = V4Props().apply { serviceTicks = 0 }
        val (b, _) = bundle(p, "none 0 0\n")
        p.bundleAnswer = null // never says anything (a boot image whose service doesn't run)
        assertEquals("the bundle service did not start", b.installIfNewer())
        assertFalse(p.stageDir!!.exists())
        val slow = V4Props().apply { serviceTicks = 1_000_000 }
        val (b2, _) = bundle(slow, "none 0 0\n")
        assertEquals("the bundle service is still running", b2.installIfNewer())
        assertTrue(slow.stageDir!!.exists()) // the root service may still be copying from it
    }

    // ------------------------------------------------------------------------------ zigbeed fetch

    private fun tarOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        for ((name, data) in files) {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "0000644".toByteArray().copyInto(h, 100)
            String.format("%011o", data.size).toByteArray().copyInto(h, 124)
            h[156] = '0'.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            o.write(h); o.write(data); o.write(ByteArray((512 - data.size % 512) % 512))
        }
        o.write(ByteArray(1024))
        return o.toByteArray()
    }

    private fun arOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("!<arch>\n".toByteArray())
        for ((name, data) in files) {
            o.write(String.format("%-16s%-12s%-6s%-6s%-8s%-10s`\n", "$name/", "0", "0", "0", "100644", data.size).toByteArray())
            o.write(data); if (data.size % 2 == 1) o.write('\n'.code)
        }
        return o.toByteArray()
    }

    @Test
    fun debAndTarParsing() {
        val elf = "fake zigbeed".toByteArray()
        val tar = tarOf("./usr/local/etc/zigbeed.conf" to "conf".toByteArray(), SisdkZigbeed.ELF_PATH to elf)
        val xz = ByteArrayOutputStream().also { b -> XZOutputStream(b, LZMA2Options()).use { it.write(tar) } }.toByteArray()
        val deb = arOf("debian-binary" to "2.0\n".toByteArray(), "control.tar.xz" to ByteArray(3), "data.tar.xz" to xz)
        val (name, data) = SisdkZigbeed.arMember(deb) { it.startsWith("data.tar") }!!
        assertEquals("data.tar.xz", name)
        val got = SisdkZigbeed.tarMember(org.tukaani.xz.XZInputStream(data.inputStream()), SisdkZigbeed.ELF_PATH)
        assertEquals("fake zigbeed", String(got!!))
        assertNull(SisdkZigbeed.tarMember(tar.inputStream(), "./usr/bin/other"))
        // the pins are checked: a deb that isn't Silicon Labs' is refused before parsing
        val e = runCatching { SisdkZigbeed.elfFromDeb(deb) }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("wrong SHA-256"))
    }

    /** Against Silicon Labs' real zip when this computer has it (tools/radio/build-zigbeed.sh's cache); never committed. */
    @Test
    fun realZipMatchesEveryPin() {
        val zip = File(System.getenv("SISDK_ZIP") ?: "/tmp/zigbeed-sisdk-build/debian-bookworm.zip")
        assumeTrue("no local copy of Silicon Labs' debian-bookworm.zip", zip.isFile)
        val deb = SisdkZigbeed.debFromZip(zip)
        // the range the panel fetches is exactly that .deb
        val ranged = ByteArray(SisdkZigbeed.DEB_SIZE)
        RandomAccessFile(zip, "r").use { it.seek(SisdkZigbeed.DEB_OFFSET); it.readFully(ranged) }
        assertTrue(ranged.contentEquals(deb))
        val elf = SisdkZigbeed.elfFromDeb(deb)
        assertEquals(SisdkZigbeed.ELF_SIZE, elf.size)
        assertEquals(SisdkZigbeed.ELF_SHA256, SisdkZigbeed.sha256(elf))
    }

    // ------------------------------------------------------------------------------ controller

    private fun controller(p: V4Props, mode: String, stack: StackStore = MemoryStackStore(), fetcher: ((File, (String) -> Unit) -> Unit)? = null): RadioController {
        var stored: String? = mode
        p.props[RadioHelper.PROP_MODE] = mode
        return RadioController(
            p, load = { stored }, store = { stored = it }, inline = true, log = {}, panelIp = { "198.51.100.7" },
            filesDir = tmp.root, stackStore = stack, zigbeedFetcher = fetcher,
        )
    }

    @Test
    fun silabsStackNeedsHelperV4() {
        val p = V4Props(version = 3).apply { props[RadioHelper.PROP_STATUS] = "ok zigbee_tcp: EZSP on tcp:6638"; props[RadioHelper.PROP_ALLOWED] = "app none" }
        val c = controller(p, "zigbee_tcp")
        var ok: Boolean? = null
        c.setZigbeeStack(ZigbeeStack.SISDK.label) { ok = it }
        assertEquals(false, ok)
        assertEquals(ZigbeeStack.AQARA, c.zigbeeStack)
        assertTrue(p.ops.none { it.contains(RadioHelper.PROP_ZIGBEE_STACK) || it.startsWith("restart") })
        assertTrue(c.helperStatusText(), c.helperStatusText().startsWith(
            "Zigbee stack “Silicon Labs 8.2.2” refused: needs the root helper v4 (installed: v3): update the panel's boot image once"))
        assertTrue(c.helperStatusText(), c.helperStatusText().contains("v3 from the boot image. Update the boot image once"))
    }

    @Test
    fun silabsStackOnV4RestartsTheCoordinatorAndShowsZ2mSettings() {
        val p = V4Props().apply {
            props[RadioHelper.PROP_STATUS] = "ok zigbee_tcp: EZSP on tcp:6638"; props[RadioHelper.PROP_ALLOWED] = "app none"
            props[RadioHelper.PROP_SOURCE] = "bundle 30200"; props[RadioHelper.PROP_BUNDLE] = "ok 30200"
        }
        val store = MemoryStackStore()
        val c = controller(p, "zigbee_tcp", store)
        c.setZigbeeStack(ZigbeeStack.SISDK.label)
        assertEquals("sisdk", store.stack)
        assertEquals("sisdk", p.get(RadioHelper.PROP_ZIGBEE_STACK))
        assertTrue(p.ops.contains("restart ${RadioHelper.SERVICE}"))
        p.props[RadioHelper.PROP_STATUS] = "ok zigbee_tcp: EZSP 18 on tcp:6638, Silicon Labs zigbeed 8.2.2"
        c.refresh()
        assertTrue(c.statusText(), c.statusText().contains("Zigbee2MQTT: adapter ember, port tcp://198.51.100.7:6638"))
        assertTrue(c.helperStatusText(), c.helperStatusText().startsWith("v4, running the bundle 30200 helper; radio bundle on the panel: ok 30200"))
    }

    @Test
    fun stackChoiceIsHandedToTheHelperAtStartAndNotRestartedOutsideCoordinatorMode() {
        val p = V4Props().apply { props[RadioHelper.PROP_STATUS] = "ok thread: border router"; props["init.svc.${RadioHelper.SERVICE}"] = "running" }
        val c = controller(p, "thread", MemoryStackStore("sisdk"))
        c.start()
        assertEquals("sisdk", p.get(RadioHelper.PROP_ZIGBEE_STACK))
        p.ops.clear()
        c.setZigbeeStack(ZigbeeStack.AQARA.label)
        assertEquals("aqara", p.get(RadioHelper.PROP_ZIGBEE_STACK))
        assertTrue(p.ops.none { it.startsWith("restart") })
    }

    @Test
    fun zigbeedIsDownloadedWhenTheHelperAsksAndRemovedOnceTaken() {
        val p = V4Props().apply {
            props[RadioHelper.PROP_STATUS] = "applying zigbee_tcp: waiting for zigbeed 8.2.2 (the app downloads it from Silicon Labs)"
            props[RadioHelper.PROP_ZIGBEE] = "sisdk missing 8.2.2 ${SisdkZigbeed.ELF_SHA256}"
        }
        var calls = 0
        val c = controller(p, "zigbee_tcp", MemoryStackStore("sisdk")) { dest, progress ->
            calls++; progress("downloading zigbeed 8.2.2 from Silicon Labs"); dest.parentFile?.mkdirs(); dest.writeText("elf")
        }
        c.refresh()
        val f = File(tmp.root, RadioHelper.ZIGBEED_FILE)
        assertEquals(1, calls)
        assertTrue(f.exists())
        c.refresh()
        assertEquals(1, calls) // waits for the helper to take it
        p.props[RadioHelper.PROP_ZIGBEE] = "sisdk ready 8.2.2"
        c.refresh()
        assertFalse(f.exists())
    }

    @Test
    fun failedDownloadIsShownAndNotRetriedAtOnce() {
        val p = V4Props().apply {
            props[RadioHelper.PROP_STATUS] = "applying zigbee_tcp: waiting for zigbeed 8.2.2 (the app downloads it from Silicon Labs)"
            props[RadioHelper.PROP_ZIGBEE] = "sisdk missing 8.2.2 ${SisdkZigbeed.ELF_SHA256}"
        }
        var calls = 0
        val c = controller(p, "zigbee_tcp", MemoryStackStore("sisdk")) { _, _ -> calls++; throw java.io.IOException("HTTP 503") }
        c.refresh(); c.refresh()
        assertEquals(1, calls)
        assertTrue(c.statusText(), c.statusText().contains("download failed (HTTP 503); retrying in 30 minutes"))
    }

    @Test
    fun leaveThreadNetworkWritesARequestAndReportsTheHelpersAnswer() {
        val p = V4Props().apply { props[RadioHelper.PROP_STATUS] = "ok off: Aqara radio services stopped, radio idle" }
        val c = controller(p, "off")
        val pending = File(tmp.root, RadioHelper.DATASET_FILE).apply { parentFile.mkdirs(); writeText("0e08") }
        c.threadLeave()
        assertEquals("leave\n", File(tmp.root, RadioHelper.THREAD_LEAVE_FILE).readText())
        assertFalse(pending.exists()) // a dataset not yet handed over is dropped too
        assertTrue(c.statusText(), c.statusText().endsWith("(leaving the Thread network…)"))
        p.props[RadioHelper.PROP_THREAD_LEAVE] = "ok ${System.currentTimeMillis() / 1000}: Thread dataset and router settings deleted"
        c.refresh()
        assertTrue(c.statusText(), c.statusText().endsWith("(Thread network left: Thread dataset and router settings deleted)"))
    }

    @Test
    fun leaveThreadNetworkNeedsHelperV4() {
        val p = V4Props(version = 3).apply { props[RadioHelper.PROP_STATUS] = "ok off: Aqara radio services stopped, radio idle" }
        val c = controller(p, "off")
        c.threadLeave()
        assertFalse(File(tmp.root, RadioHelper.THREAD_LEAVE_FILE).exists())
        assertTrue(c.helperStatusText(), c.helperStatusText().startsWith("Leave Thread network refused: needs the root helper v4"))
    }

    @Test
    fun aRefusedZigbeedDownloadIsDroppedAndRetriedLater() {
        val p = V4Props().apply {
            props[RadioHelper.PROP_STATUS] = "applying zigbee_tcp: waiting for zigbeed 8.2.2 (the app downloads it from Silicon Labs)"
            props[RadioHelper.PROP_ZIGBEE] = "sisdk bad 0123456789ab"
        }
        var calls = 0
        val c = controller(p, "zigbee_tcp", MemoryStackStore("sisdk")) { _, _ -> calls++ }
        val f = File(tmp.root, RadioHelper.ZIGBEED_FILE).apply { parentFile.mkdirs(); writeText("elf") }
        c.refresh()
        assertFalse(f.exists())
        p.props[RadioHelper.PROP_ZIGBEE] = "sisdk missing 8.2.2 ${SisdkZigbeed.ELF_SHA256}"
        c.refresh()
        assertEquals(0, calls) // backs off 30 minutes
    }

    @Test
    fun modeChangesWaitForAFirmwareFlash() {
        val p = V4Props().apply { props[RadioHelper.PROP_STATUS] = "applying off: radio firmware update (stock)"; props[RadioHelper.PROP_FW] = "flashing stock: 40% (do not unplug the panel)" }
        val c = controller(p, "off")
        var ok: Boolean? = null
        c.request(RadioMode.ZIGBEE_TCP.label) { ok = it }
        assertEquals(false, ok)
        assertEquals(RadioMode.OFF, c.mode)
        assertTrue(p.ops.none { it.startsWith("restart") })
        assertTrue(c.firmwareText(), c.firmwareText().startsWith("FLASHING stock"))
    }
}
