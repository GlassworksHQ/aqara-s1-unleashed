package sh.edm.s1kiosk.setup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/** The app side of web/src/setup/channel.ts, against web/test/setup-channel.test.ts's simulated app. */
class SetupChannelTest {

    private val base = ChannelState(
        ip = "192.168.1.50",
        ha = ChannelState.Ha(discovered = "http://homeassistant.local:8123", url = "", signedIn = false,
            signinUrl = "http://192.168.1.50:8099/signin"),
        radio = ChannelState.Radio(mode = "off", status = "Off", options = ChannelState.OPTIONS, helper = false),
    )

    private class Fake(var state: ChannelState) : SetupChannel.Backend {
        val calls = mutableListOf<String>()
        var radioError: String? = null
        var haError: String? = null
        val after = mutableListOf<String>()
        override fun state() = state
        override fun setHa(url: String): SetupChannel.HaChange {
            calls += "set_ha $url"
            if (haError != null) return SetupChannel.HaChange(error = haError)
            state = state.copy(ha = state.ha.copy(url = url))
            return SetupChannel.HaChange(afterReply = { after += "reload $url" })
        }
        override fun setRadio(id: String): String? {
            calls += "set_radio $id"
            radioError?.let { return it }
            state = state.copy(radio = state.radio.copy(mode = id))
            return null
        }
    }

    @Test
    fun stateJsonHasTheSetupStateShape() {
        val o = base.toJson()
        assertEquals(setOf("ip", "ha", "radio"), o.keyNames())
        assertEquals("192.168.1.50", o.getString("ip"))
        val ha = o.getJSONObject("ha")
        assertEquals(setOf("discovered", "url", "signedIn", "signinUrl"), ha.keyNames())
        assertTrue(ha.get("signedIn") is Boolean)
        assertEquals("", ha.getString("url"))
        val radio = o.getJSONObject("radio")
        assertEquals(setOf("mode", "status", "options", "helper"), radio.keyNames())
        assertTrue(radio.get("helper") is Boolean)
        assertTrue(radio.get("status") is String)
        val opts = radio.getJSONArray("options")
        assertEquals(
            listOf("off", "zigbee_tcp", "thread", "zigbee_router", "thread_router"),
            (0 until opts.length()).map { opts.getJSONObject(it).getString("id") },
        )
        for (i in 0 until opts.length()) {
            val opt = opts.getJSONObject(i)
            val id = opt.getString("id")
            assertTrue(opt.getString("label").isNotEmpty() && opt.getString("detail").isNotEmpty())
            // `experimental` and `repeater` only where true (optional in RadioOption).
            if (id in setOf("thread", "zigbee_router", "thread_router")) assertEquals(true, opt.get("experimental"))
            else assertFalse(opt.has("experimental"))
            if (id.endsWith("_router")) assertEquals(true, opt.get("repeater")) else assertFalse(opt.has("repeater"))
        }
    }

    @Test
    fun optionalFieldsAreLeftOutWhenUnknown() {
        val bare = ChannelState(null, ChannelState.Ha(null, "", false, null), base.radio).toJson()
        assertEquals(setOf("ha", "radio"), bare.keyNames())
        assertEquals(setOf("url", "signedIn"), bare.getJSONObject("ha").keyNames())
    }

    @Test
    fun channelOffersThePanelsOptions() {
        assertEquals(RadioStep.OPTIONS.map { it.mode.id }, ChannelState.OPTIONS.map { it.id })
        assertEquals(RadioStep.OPTIONS.map { it.title }, ChannelState.OPTIONS.map { it.label })
        assertTrue(RadioStep.COMING_SOON.none { s -> ChannelState.OPTIONS.any { it.id == s.mode.id } })
    }

    @Test
    fun noSecretsInTheState() {
        val s = base.toJson().toString().lowercase()
        for (w in listOf("token", "key", "password", "psk", "secret")) assertFalse(w, w in s)
    }

    @Test
    fun rules() {
        assertEquals("http://10.0.0.5:8099/signin", ChannelState.signinUrl("10.0.0.5", "http://ha:8123"))
        assertNull(ChannelState.signinUrl(null, "http://ha:8123"))
        assertNull(ChannelState.signinUrl("10.0.0.5", ""))
        assertEquals("http://ha:8123", ChannelState.discovered("http://ha:8123", "mdns"))
        assertEquals("http://ha:8123", ChannelState.discovered("http://ha:8123", "esphome"))
        assertNull(ChannelState.discovered("http://ha:8123", "manual"))
        assertNull(ChannelState.discovered("", "mdns"))
        // Same cases as channel.ts validHaUrl.
        assertTrue(ChannelState.validHaUrl("http://homeassistant.local:8123"))
        assertTrue(ChannelState.validHaUrl("https://ha.example.com/dash"))
        assertTrue(ChannelState.validHaUrl("http://my_ha:8123"))
        assertFalse(ChannelState.validHaUrl("homeassistant.local"))
        assertFalse(ChannelState.validHaUrl("http://x'; reboot"))
        assertFalse(ChannelState.validHaUrl("ftp://ha.local"))
        assertFalse(ChannelState.validHaUrl("http://"))
    }

    // ---- requests

    private fun reply(c: SetupChannel, line: String): JSONObject = JSONObject(c.handle(line).first)

    @Test
    fun stateAndSetHaRoundTripIdsEchoed() {
        val fake = Fake(base)
        val c = SetupChannel(fake)
        val r1 = reply(c, """{"id":1,"op":"state"}""")
        assertEquals(1, r1.getInt("id"))
        assertTrue(r1.getBoolean("ok"))
        assertEquals("http://homeassistant.local:8123", r1.getJSONObject("state").getJSONObject("ha").getString("discovered"))
        val (line, after) = c.handle("""{"id":2,"op":"set_ha","url":"http://ha.local:8123"}""")
        val r2 = JSONObject(line)
        assertEquals(2, r2.getInt("id"))
        assertEquals("http://ha.local:8123", r2.getJSONObject("state").getJSONObject("ha").getString("url"))
        assertEquals(listOf("set_ha http://ha.local:8123"), fake.calls)
        assertTrue(fake.after.isEmpty()) // runs only after the reply is written
        after!!()
        assertEquals(listOf("reload http://ha.local:8123"), fake.after)
        assertEquals("abc", reply(c, """{"id":"abc","op":"state"}""").getString("id"))
    }

    @Test
    fun errorsAreOkFalseWithAReason() {
        val fake = Fake(base)
        val c = SetupChannel(fake)
        reply(c, """{"id":3,"op":"set_ha","url":"homeassistant.local"}""").let {
            assertEquals(3, it.getInt("id")); assertFalse(it.getBoolean("ok")); assertTrue(it.getString("error").contains("web address"))
            assertFalse(it.has("state"))
        }
        fake.haError = "The panel didn't save the address; try again."
        assertEquals(fake.haError, reply(c, """{"id":4,"op":"set_ha","url":"http://ha:8123"}""").getString("error"))
        fake.radioError = "Zigbee coordinator (TCP) needs the root helper (init service s1kiosk_radio is not installed)."
        reply(c, """{"id":5,"op":"set_radio","mode":"zigbee_tcp"}""").let {
            assertEquals(5, it.getInt("id")); assertFalse(it.getBoolean("ok")); assertTrue(it.getString("error").contains("root helper"))
        }
        assertFalse(reply(c, """{"id":6,"op":"set_radio","mode":"zigbee_router"}""").getBoolean("ok")) // backend refuses
        assertFalse(reply(c, """{"id":7,"op":"set_radio","mode":"nonsense"}""").getBoolean("ok"))      // never reaches it
        assertEquals(listOf("set_ha http://ha:8123", "set_radio zigbee_tcp", "set_radio zigbee_router"), fake.calls)
        reply(c, """{"id":8,"op":"reboot"}""").let { assertEquals(8, it.getInt("id")); assertFalse(it.getBoolean("ok")) }
        reply(c, "not json").let { assertFalse(it.has("id")); assertFalse(it.getBoolean("ok")) }
    }

    @Test
    fun setRadioOk() {
        val fake = Fake(base.copy(radio = base.radio.copy(helper = true)))
        val r = reply(SetupChannel(fake), """{"id":9,"op":"set_radio","mode":"zigbee_tcp"}""")
        assertTrue(r.getBoolean("ok"))
        assertEquals("zigbee_tcp", r.getJSONObject("state").getJSONObject("radio").getString("mode"))
    }

    @Test
    fun pushesOnlyChangesAfterTheClientSawTheState() {
        val fake = Fake(base)
        val c = SetupChannel(fake)
        assertNull(c.pushIfChanged()) // nothing before the first state
        reply(c, """{"id":1,"op":"state"}""")
        assertNull(c.pushIfChanged())
        fake.state = base.copy(ha = base.ha.copy(signedIn = true))
        val ev = JSONObject(c.pushIfChanged()!!)
        assertEquals("state", ev.getString("event"))
        assertFalse(ev.has("id"))
        assertTrue(ev.getJSONObject("state").getJSONObject("ha").getBoolean("signedIn"))
        assertNull(c.pushIfChanged())
    }

    // ---- over streams

    /** Delivers [data] in small pieces, like a socket. */
    private class Chunked(private val data: ByteArray, private val size: Int = 7) : InputStream() {
        private var p = 0
        override fun read(): Int = if (p < data.size) data[p++].toInt() and 0xff else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (p >= data.size) return -1
            val n = minOf(len, size, data.size - p)
            System.arraycopy(data, p, b, off, n)
            p += n
            return n
        }
    }

    private fun lines(out: ByteArrayOutputStream) = out.toString("UTF-8").split("\n").filter { it.isNotEmpty() }.map(::JSONObject)

    @Test
    fun servesRequestsInOrderFromSplitChunks() {
        val fake = Fake(base)
        val input = ("""{"id":1,"op":"state"}""" + "\r\n\n" + """{"id":2,"op":"set_ha","url":"http://ha.local:8123"}""" + "\n" +
            """{"id":3,"op":"set_radio","mode":"thread"}""" + "\n").toByteArray()
        val out = ByteArrayOutputStream()
        SetupChannel(fake).serve(Chunked(input), out, pollMs = 10)
        val got = lines(out)
        assertEquals(listOf(1, 2, 3), got.map { it.getInt("id") })
        assertTrue(got.all { it.getBoolean("ok") })
        assertEquals("thread", got[2].getJSONObject("state").getJSONObject("radio").getString("mode"))
        assertEquals(listOf("reload http://ha.local:8123"), fake.after)
    }

    @Test
    fun pushesStateEventsWhileConnected() {
        val fake = Fake(base)
        val toApp = PipedOutputStream()
        val input = PipedInputStream(toApp)
        val out = ByteArrayOutputStream() // synchronized: written by serve, read here
        val t = Thread { SetupChannel(fake).serve(input, out, pollMs = 10) }.apply { start() }
        toApp.write(("""{"id":1,"op":"state"}""" + "\n").toByteArray()); toApp.flush()
        waitFor { lines(out).size == 1 }
        fake.state = base.copy(radio = base.radio.copy(status = "ok Zigbee coordinator (TCP)"))
        waitFor { lines(out).size == 2 }
        toApp.close()
        t.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(t.isAlive)
        val got = lines(out)
        assertEquals(2, got.size)
        assertEquals("state", got[1].getString("event"))
        assertEquals("ok Zigbee coordinator (TCP)", got[1].getJSONObject("state").getJSONObject("radio").getString("status"))
    }

    @Test
    fun dropsOverlongLines() {
        val got = mutableListOf<String>()
        val big = "x".repeat(SetupChannel.MAX_LINE + 10)
        SetupChannel.splitLines(Chunked(("$big\n{\"id\":1}\n").toByteArray(), 1000)) { got += it }
        assertEquals(listOf("{\"id\":1}"), got)
    }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }
}
