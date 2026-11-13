package sh.edm.s1kiosk.setup

import android.content.Context
import dev.glassworks.pane.SetupHost
import dev.glassworks.pane.SetupOp
import dev.glassworks.pane.SetupStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.edm.s1kiosk.radio.RadioDefinition
import sh.edm.s1kiosk.radio.ui.RadioStep
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.TimeUnit

/**
 * The app side of kiln/packages/setup-channel/src/channel.ts, against the simulated app in
 * kiln/packages/setup-channel/test/setup-channel.test.ts. The radio here is a fake step with the real
 * state shape (RadioDefinition.channelJson); SetupChannelStepsTest runs the real one.
 */
class SetupChannelTest {

    private val base = ChannelState(
        ip = "192.168.1.50",
        ha = ChannelState.Ha(discovered = "http://homeassistant.local:8123", url = "", signedIn = false,
            signinUrl = "http://192.168.1.50:8099/signin"),
    )

    /** What the fake radio step reports. */
    private data class Radio(val mode: String = "off", val status: String = "Off", val helper: Boolean = false, val allowed: String? = null)

    private class Fake(var state: ChannelState, var radio: Radio = Radio()) : SetupChannel.Backend {
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

        val step = object : SetupStep {
            override val id = "radio"
            override val title = "Radio"
            override val reason = "radio mode not chosen"
            override fun done() = false
            override fun detail(done: Boolean) = ""
            override fun channelState() = RadioDefinition.channelJson(radio.mode, radio.status, radio.helper, radio.allowed)
            override val ops = mapOf(
                "set_mode" to SetupOp { r ->
                    val id = r.optString("mode", "")
                    if (id !in RADIO_MODES) return@SetupOp "Unknown radio mode '$id'."
                    calls += "set_radio $id"
                    radioError ?: run { radio = radio.copy(mode = id); null }
                },
                "set_allow" to SetupOp { r ->
                    val text = r.optString("allow", "")
                    calls += "set_radio_allow $text"
                    if (text == "bad") "Not an IP address or CIDR: “bad”."
                    else { radio = radio.copy(allowed = text.ifEmpty { "192.0.2.10" }); null }
                },
            )
            override val legacyOps = mapOf("set_radio" to "set_mode", "set_radio_allow" to "set_allow")
            override fun screen(context: Context, host: SetupHost) = throw UnsupportedOperationException()
        }

        fun channel() = SetupChannel(this, listOf(step))
    }

    @Test
    fun stateJsonHasTheSetupStateShape() {
        val o = JSONObject(Fake(base).channel().handle("""{"id":1,"op":"state"}""").first).getJSONObject("state")
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
            if (id in setOf("zigbee_router", "thread_router")) assertEquals(true, opt.get("experimental"))
            else assertFalse(opt.has("experimental"))
            if (id.endsWith("_router")) assertEquals(true, opt.get("repeater")) else assertFalse(opt.has("repeater"))
        }
    }

    @Test
    fun optionalFieldsAreLeftOutWhenUnknown() {
        val bare = ChannelState(null, ChannelState.Ha(null, "", false, null)).toJson()
        assertEquals(setOf("ha"), bare.keyNames())
        assertEquals(setOf("url", "signedIn"), bare.getJSONObject("ha").keyNames())
    }

    @Test
    fun channelOffersThePanelsOptions() {
        val opts = RadioDefinition.channelOptions()
        val ids = (0 until opts.length()).map { opts.getJSONObject(it).getString("id") }
        assertEquals(RadioStep.OPTIONS.map { it.mode.id }, ids)
        assertEquals(RadioStep.OPTIONS.map { it.title }, (0 until opts.length()).map { opts.getJSONObject(it).getString("label") })
        assertTrue(RadioStep.COMING_SOON.none { s -> s.mode.id in ids })
    }

    @Test
    fun noSecretsInTheState() {
        val s = Fake(base).channel().handle("""{"op":"state"}""").first.lowercase()
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
    fun setRadioAllowAndTheAllowedField() {
        val fake = Fake(base)
        val c = fake.channel()
        assertFalse(reply(c, """{"id":1,"op":"state"}""").getJSONObject("state").getJSONObject("radio").has("allowed"))
        val r = reply(c, """{"id":2,"op":"set_radio_allow","allow":"198.51.100.23"}""")
        assertTrue(r.getBoolean("ok"))
        assertEquals("198.51.100.23", r.getJSONObject("state").getJSONObject("radio").getString("allowed"))
        val bad = reply(c, """{"id":3,"op":"set_radio_allow","allow":"bad"}""")
        assertFalse(bad.getBoolean("ok"))
        assertTrue(bad.getString("error").startsWith("Not an IP address"))
        // missing "allow" = "" = back to automatic (Home Assistant only)
        assertTrue(reply(c, """{"id":4,"op":"set_radio_allow"}""").getBoolean("ok"))
        assertEquals(listOf("set_radio_allow 198.51.100.23", "set_radio_allow bad", "set_radio_allow "), fake.calls)
    }

    @Test
    fun stateAndSetHaRoundTripIdsEchoed() {
        val fake = Fake(base)
        val c = fake.channel()
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
        val c = fake.channel()
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
        val fake = Fake(base, Radio(helper = true))
        val r = reply(fake.channel(), """{"id":9,"op":"set_radio","mode":"zigbee_tcp"}""")
        assertTrue(r.getBoolean("ok"))
        assertEquals("zigbee_tcp", r.getJSONObject("state").getJSONObject("radio").getString("mode"))
    }

    @Test
    fun pushesOnlyChangesAfterTheClientSawTheState() {
        val fake = Fake(base)
        val c = fake.channel()
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
        fake.channel().serve(Chunked(input), out, pollMs = 10)
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
        val t = Thread { fake.channel().serve(input, out, pollMs = 10) }.apply { start() }
        toApp.write(("""{"id":1,"op":"state"}""" + "\n").toByteArray()); toApp.flush()
        waitFor { lines(out).size == 1 }
        fake.radio = fake.radio.copy(status = "ok Zigbee coordinator (TCP)")
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

    @Test
    fun namespacedOpsAndTheOldNamesDoTheSame() {
        val fake = Fake(base)
        val c = fake.channel()
        assertTrue(reply(c, """{"id":1,"op":"ha.set_url","url":"http://ha.local:8123"}""").getBoolean("ok"))
        assertTrue(reply(c, """{"id":2,"op":"radio.set_mode","mode":"thread"}""").getBoolean("ok"))
        assertTrue(reply(c, """{"id":3,"op":"set_radio","mode":"off"}""").getBoolean("ok"))
        assertTrue(reply(c, """{"id":4,"op":"radio.set_allow","allow":"192.0.2.30"}""").getBoolean("ok"))
        assertEquals(listOf("set_ha http://ha.local:8123", "set_radio thread", "set_radio off", "set_radio_allow 192.0.2.30"), fake.calls)
        // Only `<step>.<op>` and the declared old names: a bare op name is not one.
        assertFalse(reply(c, """{"id":5,"op":"set_mode","mode":"off"}""").getBoolean("ok"))
    }

    /** [steps] through SetupFlow.usable, as the app gathers them: the ids kept, and what was logged. */
    private fun usable(vararg steps: SetupStep): Pair<List<String>, List<String>> {
        val log = mutableListOf<String>()
        return SetupFlow.usable(steps.toList(), log::add).map { it.id } to log
    }

    @Test
    fun aStepCannotTakeACoreKey() {
        val fake = Fake(base)
        // A core step's id, a key of the core's state, or an earlier step's: left out and logged, never a crash.
        for (id in listOf("ha", "ip", "wifi", "signin", "done")) {
            val (kept, log) = usable(object : SetupStep by fake.step { override val id = id })
            assertEquals(id, emptyList<String>(), kept)
            assertTrue(log.toString(), log.single().contains("'$id'"))
        }
        assertEquals(listOf("radio"), usable(fake.step, fake.step).first)
    }

    @Test
    fun anOldNameCannotTakeACoreRequest() {
        val fake = Fake(base)
        for (core in listOf("set_ha", "ha.set_url", "state")) {
            val greedy = object : SetupStep by fake.step { override val legacyOps = mapOf(core to "set_mode") }
            val (kept, log) = usable(greedy)
            assertEquals(core, emptyList<String>(), kept)
            assertTrue(log.toString(), log.single().contains(core))
        }
        val other = object : SetupStep by fake.step {
            override val id = "camera"
            override val legacyOps = mapOf("set_radio" to "set_mode") // the radio's old name, taken twice
        }
        val (kept, log) = usable(fake.step, other)
        assertEquals(listOf("radio"), kept)
        assertTrue(log.toString(), log.single().contains("set_radio"))
        // The radio's own old names are fine, and set_ha still reaches the core.
        val c = fake.channel()
        assertTrue(reply(c, """{"id":1,"op":"set_ha","url":"http://ha.local:8123"}""").getBoolean("ok"))
        assertEquals(listOf("set_ha http://ha.local:8123"), fake.calls)
    }

    private fun JSONObject.keyNames(): Set<String> = keys().asSequence().toSet()

    private companion object {
        val RADIO_MODES = RadioStep.OPTIONS.map { it.mode.id }
    }

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!cond()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }
}
