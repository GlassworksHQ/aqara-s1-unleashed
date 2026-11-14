/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files

/** The Manage page's parts on their own: sessions, the panel's code, redaction, the log, HA's address. */
class ManagePartsTest {
    private var now = 1_763_000_000_000L
    private val dir: File = Files.createTempDirectory("manage-parts").toFile()

    // ------------------------------------------------------------------ sessions

    @Test fun aRememberedBrowserSurvivesARestartAndExpiresAfter30Days() {
        val file = File(dir, "s.json")
        val (token, s) = SessionStore(file, clock = { now }).create("Alex", SignedInWith.HOME_ASSISTANT, "Safari on Mac", true, "http://192.0.2.10:8123")
        assertFalse("the file never holds the cookie", file.readText().contains(token))
        val again = SessionStore(file, clock = { now })
        assertEquals(s.id, again.lookup(token, "http://192.0.2.10:8123")?.id)
        now += 30L * 24 * 3600 * 1000
        assertNull(again.lookup(token, "http://192.0.2.10:8123"))
    }

    @Test fun anUnrememberedBrowserLastsHoursAndNotPastARestart() {
        val file = File(dir, "s.json")
        val store = SessionStore(file, clock = { now })
        val (token, _) = store.create(null, SignedInWith.PANEL_CODE, "A browser", false, null)
        assertNotNull(store.lookup(token, null))
        assertNull(SessionStore(file, clock = { now }).lookup(token, null))
        now += 12L * 3600 * 1000
        assertNull(store.lookup(token, null))
    }

    @Test fun signingOutABrowserEndsItsSession() {
        val store = SessionStore(null, clock = { now })
        val (t1, s1) = store.create("Alex", SignedInWith.HOME_ASSISTANT, "Safari on Mac", true, null)
        val (t2, _) = store.create("Alex", SignedInWith.HOME_ASSISTANT, "Chrome on Windows", true, null)
        assertEquals(SessionStore.Revoked.DONE, store.revoke(s1.id))
        assertEquals(SessionStore.Revoked.NONE, store.revoke(s1.id))
        assertNull(store.lookup(t1, null))
        assertNotNull(store.lookup(t2, null))
    }

    @Test fun browserNames() {
        assertEquals("Safari on iPhone", SessionStore.browserName("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"))
        assertEquals("Firefox on Linux", SessionStore.browserName("Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0"))
        assertEquals("A browser", SessionStore.browserName(null))
    }

    // ------------------------------------------------------------------ the panel's code

    private fun code() = PanelCode(clock = { now }).apply { canDisplay = { true } }

    private fun PanelCode.ok(purpose: String, requester: String) = (show(purpose, purpose, requester) as PanelCode.Show.Ok).shown

    @Test fun aCodeWorksOnceForItsPurposeAndBrowser() {
        val c = code()
        val s = c.ok("signin", "browser-a")
        assertEquals(PanelCode.Check.NoCode, c.check("reboot", "browser-a", s.code))
        assertEquals(PanelCode.Check.NoCode, c.check("signin", "browser-b", s.code))
        assertEquals(PanelCode.Check.Ok, c.check("signin", "browser-a", s.code))
        assertEquals(PanelCode.Check.NoCode, c.check("signin", "browser-a", s.code))
    }

    @Test fun aCodeRunsOutAfterFiveMinutes() {
        val c = code()
        val s = c.ok("signin", "a")
        now += 5 * 60_000L
        assertEquals(PanelCode.Check.NoCode, c.check("signin", "a", s.code))
    }

    @Test fun fiveWrongTriesBurnACodeAndTenInAQuarterHourLockEntry() {
        val c = code()
        var s = c.ok("signin", "a")
        fun wrong(x: PanelCode.Shown) = if (x.code == "000000") "111111" else "000000"
        for (left in 4 downTo 0) assertEquals(left, (c.check("signin", "a", wrong(s)) as PanelCode.Check.Wrong).triesLeft)
        assertEquals(PanelCode.Check.NoCode, c.check("signin", "a", s.code))
        now += 30_000 // no new code within 30 s of the last
        s = c.ok("signin", "a")
        repeat(4) { c.check("signin", "a", wrong(s)) }
        val locked = c.check("signin", "a", wrong(s))
        assertTrue(locked is PanelCode.Check.Locked)
        assertTrue("no new code while locked", c.show("signin", "Sign in", "b") is PanelCode.Show.Locked)
        now += 15 * 60_000L
        assertTrue("open again once the failures are old", c.show("signin", "Sign in", "b") is PanelCode.Show.Ok)
    }

    @Test fun askingForCodesIsLimitedSoNobodyCanReplaceAnothersCode() {
        val c = code()
        val a = c.ok("signin", "a")
        assertEquals("the same browser asking again keeps its code", a.code, c.ok("signin", "a").code)
        val busy = c.show("signin", "x", "b")
        assertTrue(busy is PanelCode.Show.Busy)
        assertEquals(PanelCode.Check.Ok, c.check("signin", "a", a.code))
        val b = c.ok("signin", "b") // a's code was used: b may ask now
        now += 29_000
        assertTrue(c.show("reboot", "x", "a") is PanelCode.Show.Busy)
        now += 1_000
        assertTrue("after 30 s another browser may ask", c.show("reboot", "x", "a") is PanelCode.Show.Ok)
        assertEquals(PanelCode.Check.NoCode, c.check("signin", "b", b.code))
    }

    @Test fun atMostSixNewCodesInTenMinutesSoNobodyCanKeepTheScreenUp() {
        val c = code()
        val start = now
        repeat(6) { i -> now = start + i * 31_000L; c.ok("signin", "browser-$i") }
        now = start + 6 * 31_000L
        val busy = c.show("signin", "x", "browser-6")
        assertTrue(busy is PanelCode.Show.Busy)
        assertEquals(start + 10 * 60_000L, (busy as PanelCode.Show.Busy).untilMs)
        now = start + 10 * 60_000L
        assertTrue("open again once the oldest is 10 min old", c.show("signin", "x", "browser-6") is PanelCode.Show.Ok)
    }

    @Test fun noCodeWhenThePanelCantShowOne() {
        val c = PanelCode(clock = { now })
        assertTrue(c.show("signin", "x", "a") is PanelCode.Show.Unavailable)
    }

    @Test fun codesAreSixDigits() {
        val c = code()
        repeat(50) { now += 10 * 60_000L; assertTrue(Regex("\\d{6}").matches(c.ok("signin", "a").code)) }
    }

    // ------------------------------------------------------------------ redaction

    @Test fun redactionRemovesTokensKeysAndDeviceIds() {
        val cases = mapOf(
            "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl here" to "token <token> here",
            "Authorization: Bearer abc.DEF-123" to "Authorization: Bearer <token>",
            "refresh_token=RT-12345&x=1" to "refresh_token=<removed>&x=1",
            "{\"password\": \"hunter2\"}" to "{\"password\": \"<removed>\"}",
            "{\"password\": \"hunter two\"}" to "{\"password\": \"<removed>\"}",
            "psk=My Wifi Password" to "psk=<removed>",
            "Authorization: Basic dXNlcjpodW50ZXIy" to "Authorization: Basic <removed>",
            "Signed in to http://user:hunter2@192.0.2.10:8123" to "Signed in to http://<removed>@192.0.2.10:8123",
            "http://alex:p@ssw0rd@192.0.2.10:8123/x" to "http://<removed>@192.0.2.10:8123/x",
            // An @ in the path is no user part; the query's values go, whatever they are.
            "http://192.0.2.10:8123/u@x?a=b@c" to "http://192.0.2.10:8123/u@x?a=<removed>",
            "Home Assistant: https://alex@homeassistant.local:8123/x" to "Home Assistant: https://<removed>@homeassistant.local:8123/x",
            "password=ab,cd&x=1" to "password=<removed>&x=1",
            "psk=MyWifiPassword" to "psk=<removed>",
            "wifi passphrase: hunter22" to "wifi passphrase: <removed>",
            "noise key AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=" to "noise key <key>",
            "mac 02:11:22:33:44:55 ok" to "mac <mac> ok",
            "eui64 00:11:22:33:44:55:66:77" to "eui64 <eui64>",
            "tlv 0e080000000000010000000300000f35060004001fffe0" to "tlv <hex>",
            "serial 0123456789abcdef012" to "serial <hex>",
        )
        for ((input, out) in cases) assertEquals(input, out, Redact.text(input))
    }

    @Test fun redactionLeavesOrdinaryLinesAlone() {
        val line = "ZHA reconnected after 4 s; Sendspin stream started, FLAC 48 kHz; http://192.0.2.10:8123"
        assertEquals(line, Redact.text(line))
    }

    // ------------------------------------------------------------------ the log

    @Test fun theLogSurvivesARestartAndKeepsSevenDays() {
        val logDir = File(dir, "log")
        val log = ManageLog(logDir, clock = { now })
        log.append(LogEntry(now - 8L * 86_400_000, LogEntry.Level.INFO, "System", "old"))
        log.append(LogEntry(now - 1000, LogEntry.Level.WARNING, "Radio", "ZHA disconnected"))
        log.append(LogEntry(now, LogEntry.Level.INFO, "Voice", "Wake word heard (0.82)"))
        val again = ManageLog(logDir, clock = { now })
        assertEquals(listOf("Wake word heard (0.82)", "ZHA disconnected"), again.read().map { it.text })
        assertEquals(listOf("ZHA disconnected"), again.read(LogEntry.Level.WARNING).map { it.text })
        assertEquals(listOf("Wake word heard (0.82)"), again.read(search = "wake").map { it.text })
    }

    @Test fun expiredLinesLeaveTheDiskEvenWhenNothingNewIsWritten() {
        val logDir = File(dir, "log-old")
        val log = ManageLog(logDir, clock = { now })
        log.append(LogEntry(now, LogEntry.Level.INFO, "Radio", "joined"))
        now += 8L * 86_400_000
        assertTrue(log.read().isEmpty())
        assertFalse("the file with only old lines is gone", File(logDir, "log.0").exists())
    }

    @Test fun anExpiredCodeLeavesTheScreenButANewerOneStays() {
        val c = code()
        val shown = ArrayList<PanelCode.Shown?>()
        c.display = { shown += it }
        c.ok("signin", "a")
        now += 4 * 60_000L
        c.expire()
        assertEquals("not run out yet", 1, shown.size)
        now += 60_000L
        c.expire()
        assertNull(shown.last())
        c.ok("signin", "b")
        c.expire()
        assertNotNull("a newer code stays up", shown.last())
    }

    @Test fun theLogRedactsWhatItWrites() {
        val log = ManageLog(File(dir, "log2"), clock = { now })
        log.append(LogEntry(now, LogEntry.Level.INFO, "Sign-in", "access_token=SECRETSECRET"))
        assertFalse(File(dir, "log2/log.0").readText().contains("SECRETSECRET"))
    }

    @Test fun theLogStaysSmall() {
        val log = ManageLog(File(dir, "log3"), clock = { now }, fileBytes = 1000)
        repeat(200) { log.append(LogEntry(now, LogEntry.Level.INFO, "App", "line $it with some text to fill the file")) }
        val total = File(dir, "log3").listFiles()!!.sumOf { it.length() }
        assertTrue("$total bytes", total < 2200)
        assertEquals("line 199 with some text to fill the file", log.read().first().text)
    }

    @Test fun logcatLines() {
        val src: (String) -> String? = { if (it.startsWith("S1")) "Radio" else null }
        val e = ManageLog.fromLogcat("1731456000.123  1234  1250 W S1Kiosk.Radio: ZHA disconnected", src)!!
        assertEquals(1731456000123L, e.timeMs)
        assertEquals(LogEntry.Level.WARNING, e.level)
        assertEquals("ZHA disconnected", e.text)
        assertNull(ManageLog.fromLogcat("1731456000.123  1234  1250 D S1Kiosk.Radio: debug", src))
        assertNull(ManageLog.fromLogcat("1731456000.123  1234  1250 I GeckoConsole: page", src))
        assertNull(ManageLog.fromLogcat("--------- beginning of main", src))
    }

    // ------------------------------------------------------------------ HA's address

    @Test fun onlyAHaOnTheLocalNetworkIsSignedInWith() {
        val s = HaSignIn(object : HaGateway {
            override fun exchangeCode(haBase: String, clientId: String, code: String, codeVerifier: String) = HaGateway.Tokens.Failed("x")
            override fun currentUser(haBase: String, accessToken: String): HaUser? = null
            override fun revoke(haBase: String, clientId: String, refreshToken: String) = true
        }, resolve = { host -> if (host == "ha.home.arpa") listOf(InetAddress.getByName("192.168.100.20")) else listOf(InetAddress.getByName(host)) })
        assertTrue(s.isLocal("http://192.168.100.20:8123"))
        assertTrue(s.isLocal("http://10.0.0.5:8123"))
        assertTrue(s.isLocal("http://homeassistant.local:8123"))
        assertTrue(s.isLocal("http://ha.home.arpa:8123"))
        assertTrue(s.isLocal("http://[fd00::10]:8123"))
        assertFalse(s.isLocal("https://abcdef.ui.nabu.casa"))
        assertFalse(s.isLocal("http://8.8.8.8:8123"))
        assertFalse(s.isLocal("not a url"))
    }

    @Test fun pkceChallengeIsS256() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", HaSignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    // ------------------------------------------------------------------ CodeRabbit round on f5b117c

    @Test fun aSignOutThatCantBeSavedDeletesTheFileOrSaysSo() {
        val file = File(dir, "s.json")
        val store = SessionStore(file, clock = { now })
        val (_, s) = store.create("Alex", SignedInWith.HOME_ASSISTANT, "Safari on Mac", true, null)
        // Writing fails (a directory where the temp file goes), deleting works: the file goes, everyone is out after a restart.
        File(dir, "s.json.tmp").mkdirs()
        File(File(dir, "s.json.tmp"), "x").writeText("x")
        assertEquals(SessionStore.Revoked.DONE, store.revoke(s.id))
        assertFalse(file.exists())
    }

    @Test fun redactionTakesEscapedQuotesAndQueryValues() {
        val out = Redact.text("""{"password":"alpha\"bravo"} http://homeassistant.local:8123/lovelace/0?auth=secret123&kiosk&x=1#top""")
        assertFalse(out, out.contains("bravo"))
        assertFalse(out, out.contains("secret123"))
        assertTrue(out, out.contains("/lovelace/0?auth=<removed>&kiosk&x=<removed>#top"))
    }

    @Test fun pruningFindsAnOldLineAfterANewerOne() {
        val log = ManageLog(File(dir, "log"), clock = { now })
        log.append(LogEntry(now, LogEntry.Level.INFO, "App", "new"))
        // The clock jumped back at boot: an older line after a newer one.
        log.append(LogEntry(now - 8L * 24 * 3600 * 1000, LogEntry.Level.INFO, "App", "old"))
        log.prune()
        val disk = File(dir, "log/log.0").readText()
        assertFalse(disk, disk.contains("old"))
        assertTrue(disk.contains("new"))
    }

    @Test fun theTailPrunesWhenItStartsWithNothingWrittenOrRead() {
        val log = ManageLog(File(dir, "log"), clock = { now })
        log.append(LogEntry(now, LogEntry.Level.INFO, "App", "a week ago"))
        now += 8L * 24 * 3600 * 1000
        val tail = LogcatTail(log, { null }, logcat = { ProcessBuilder("sleep", "30").start() })
        tail.start()
        try {
            val f = File(dir, "log/log.0")
            val until = System.currentTimeMillis() + 5_000
            while (f.exists() && System.currentTimeMillis() < until) Thread.sleep(20)
            assertFalse("the old line left the disk", f.exists())
        } finally {
            tail.stop()
        }
    }

    @Test fun aWorkerFromAnEarlierStartNeverKeepsItsLogcat() {
        val log = ManageLog(File(dir, "log"), clock = { now })
        val first = java.util.concurrent.CountDownLatch(1)
        val made = java.util.concurrent.CopyOnWriteArrayList<Process>()
        var calls = 0
        val tail = LogcatTail(log, { null }, logcat = {
            val mine = synchronized(made) { ++calls }
            if (mine == 1) first.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ProcessBuilder("sleep", "30").start().also { made += it }
        })
        tail.start() // worker A waits inside logcat
        tail.stop()
        tail.start() // worker B
        val until = System.currentTimeMillis() + 5_000
        while (made.size < 1 && System.currentTimeMillis() < until) Thread.sleep(10)
        first.countDown() // A's logcat starts now, after the restart
        while (made.size < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        Thread.sleep(200)
        tail.stop()
        val deadline = System.currentTimeMillis() + 5_000
        while (made.any { it.isAlive } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals("both logcats are gone after stop()", 0, made.count { it.isAlive })
    }

    @Test fun noNewCodeWithin30SecondsOfABurnedOneUnlessItWasTypedRight() {
        val c = PanelCode(clock = { now }).apply { canDisplay = { true } }
        val a = (c.show("signin", "Sign in", "a") as PanelCode.Show.Ok).shown
        repeat(5) { c.check("signin", "a", if (a.code == "000000") "111111" else "000000") }
        assertTrue("burned, but the 30 s still hold", c.show("signin", "Sign in", "b") is PanelCode.Show.Busy)
        c.dismiss()
        assertTrue("closed, and the 30 s still hold", c.show("signin", "Sign in", "b") is PanelCode.Show.Busy)
        now += 30_000
        val b = (c.show("signin", "Sign in", "b") as PanelCode.Show.Ok).shown
        assertEquals(PanelCode.Check.Ok, c.check("signin", "b", b.code))
        assertTrue("typed right: the next code goes up at once", c.show("reboot", "Reboot", "b") is PanelCode.Show.Ok)
    }

    @Test fun aLateHideNeverHidesANewerCode() {
        val c = PanelCode(clock = { now })
        c.canDisplay = { true }
        val screen = java.util.concurrent.atomic.AtomicReference<PanelCode.Shown?>()
        var other: Thread? = null
        c.display = { s ->
            // The hide is on its way to the screen when another browser asks for a code. Under the lock the new
            // code waits for the hide; without it, the new code is drawn first and the hide then clears it.
            if (s == null && other == null) {
                other = Thread { c.show("signin", "Sign in", "b") }.apply { start(); join(300) }
            }
            screen.set(s)
        }
        c.show("signin", "Sign in", "a")
        now += 5 * 60_000L
        c.expire()
        other!!.join(5_000)
        assertNotNull(c.current())
        assertEquals("the screen shows the code that is current", c.current(), screen.get())
    }
}
