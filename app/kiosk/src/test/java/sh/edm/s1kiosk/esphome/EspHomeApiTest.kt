package sh.edm.s1kiosk.esphome

import sh.edm.esphome.*
import sh.edm.s1kiosk.voice.VoiceEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Runs the API server (plaintext and Noise) against the real `aioesphomeapi` client playing Home
 * Assistant (src/test/resources/esphome_client.py): device info, capabilities, list entities,
 * states, commands for every entity type, the voice assistant flow (wake word -> start -> mic audio
 * -> events -> TTS playback -> AnnounceFinished), announcements, timers, wake word configuration,
 * and Noise with right/wrong/missing keys.
 *
 *     python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
 *     ESPHOME_PYTHON=/tmp/esphome-venv/bin/python ./gradlew :kiosk:testDebugUnitTest
 *
 * Skipped when ESPHOME_PYTHON is not set.
 */
class EspHomeApiTest {

    private class FakePlayer : UrlPlayer {
        val played = Collections.synchronizedList(ArrayList<List<String>>())
        override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {
            played += urls
            Thread { Thread.sleep(100); onDone(true) }.start()
        }
        override fun stop() {}
    }

    private class FakeCatalog : WakeWordCatalog {
        @Volatile var current = listOf("hey_jarvis_v0.1")
        override fun available() = listOf(WakeWordInfo("alexa_v0.1", "Alexa"), WakeWordInfo("hey_jarvis_v0.1", "Hey Jarvis"))
        override fun activeIds() = current
        override fun setActive(ids: List<String>) { current = ids }
    }

    private val updateCommands = Collections.synchronizedList(ArrayList<Int>())

    /** In-memory stand-ins for the panel's entities (same object ids as PanelDevice). */
    private fun entities(): List<ApiEntity> {
        var update = UpdateStateData(
            missingState = true, inProgress = false, hasProgress = false, progress = 0f, currentVersion = "1.2.0",
            latestVersion = "", title = "S1 Kiosk", releaseSummary = "", releaseUrl = "",
        )
        var screenOn = true
        var brightness = 0.8f
        var auto = true
        var mode = "presence"
        var idle = 120f
        var night = "22:00-07:00"
        var muted = false
        var volume = 1f
        return listOf(
            BrightnessLightEntity("screen", "Screen", get = { screenOn to brightness },
                set = { on, b -> on?.let { screenOn = it }; b?.let { brightness = it } }),
            SwitchEntity("auto_brightness", "Auto brightness", get = { auto }, set = { auto = it }),
            BinarySensorEntity("presence", "Presence", deviceClass = "occupancy", get = { true }),
            SensorEntity("illuminance", "Illuminance", unit = "lx", deviceClass = "illuminance", get = { Float.NaN }),
            SelectEntity("screen_mode", "Screen mode", options = listOf("presence", "always"), get = { mode }, set = { mode = it }),
            dashboardSelect(listOf("Default")),
            NumberEntity("idle_timeout", "Idle timeout", min = 0f, max = 3600f, step = 5f, unit = "s",
                get = { idle }, set = { idle = it }),
            NumberEntity("presence_range", "Presence range", min = 0f, max = 2.5f, step = 0.1f, unit = "m",
                get = { 0f }, set = {}),
            TextEntity("night_window", "Night window", maxLength = 11,
                pattern = "^$|^[0-9]{1,2}:[0-9]{2}-[0-9]{1,2}:[0-9]{2}$", get = { night }, set = { night = it }),
            ButtonEntity("reload_dashboard", "Reload dashboard", press = {}),
            MediaPlayerEntity("speaker", "Speaker", formats = listOf("mp3" to 0, "mp3" to 1),
                featureFlags = MediaPlayerEntity.FEATURE_VOLUME_SET or MediaPlayerEntity.FEATURE_PLAY_MEDIA,
                get = { MediaPlayerStateData(MediaState.IDLE, volume, false) },
                handle = { c -> c.volume?.let { volume = it } }),
            SwitchEntity("mute_microphone", "Mute microphone", get = { muted }, set = { muted = it }),
            TextSensorEntity("ip_address", "IP address", category = EntityCategory.DIAGNOSTIC, get = { "192.0.2.10" }),
            EventEntity("base_button_1", "Base button 1", deviceClass = "button", eventTypes = listOf("single", "double")),
            UpdateEntity("app_update", "App update", get = { update }, onCommand = { c ->
                updateCommands += c
                update = when (c) {
                    2 -> update.copy(missingState = false, latestVersion = "1.3.0", releaseSummary = "* new things",
                        releaseUrl = "https://github.com/GlassworksHQ/aqara-s1-unleashed/releases/tag/v1.3.0")
                    1 -> update.copy(inProgress = true, hasProgress = true, progress = 42f)
                    else -> update
                }
            }),
        )
    }

    /** Like PanelDevice's "Dashboard" select: same object_id (key) whatever the options. */
    private fun dashboardSelect(options: List<String>) =
        SelectEntity("dashboard", "Dashboard", options = options, get = { "Default" }, set = {})

    @Test
    fun homeAssistantClient() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val identity = DeviceIdentity(
            name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
            esphomeVersion = "2026.10.2", compilationTime = "S1 Kiosk test", manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = 61, webserverPort = 8099,
        )
        val key = ByteArray(32) { (it * 7 + 1).toByte() }
        val keyB64 = Base64.getEncoder().encodeToString(key)

        val events = Collections.synchronizedList(ArrayList<VoiceEvent>())
        val timers = Collections.synchronizedList(ArrayList<String>())
        val player = FakePlayer()
        val catalog = FakeCatalog()
        lateinit var plain: ApiServer
        val controller = VoiceAssistantController(
            send = { t, p -> plain.sendVoice(t, p) },
            player = player,
            wakeWords = catalog,
            emit = { events += it },
            resolveUrl = { if (it.startsWith("/")) "http://ha.test:8123$it" else it },
            onTimerFinished = { timers += it },
        )
        val plainEntities = entities()
        val button = plainEntities.filterIsInstance<EventEntity>().single()
        plain = ApiServer(0, { identity }, plainEntities, psk = null)
        plain.voice = controller
        val sendspin = object : SendspinTokenSource {
            override fun clientIdIfStarted() = "A".repeat(42) + "Q"
            override fun pairingToken(): Pair<Int, String?> = SendspinApi.STATUS_OK to "SP:0TESTTOKEN"
        }
        plain.sendspin = sendspin
        plain.onCommand = { plain.publishStates() }
        val subscribed = Collections.synchronizedList(ArrayList<String>())
        plain.onStatesSubscribed = { info, _, authenticated -> subscribed += "$info|$authenticated" }
        val noise = ApiServer(0, { identity }, entities(), psk = key)
        // Only a Noise connection with the provisioned key counts as authenticated (radio firewall).
        val noiseSubscribed = Collections.synchronizedList(ArrayList<String>())
        noise.onStatesSubscribed = { info, addr, authenticated -> noiseSubscribed += "$info|${addr?.hostAddress}|$authenticated" }
        noise.sendspin = sendspin
        noise.onCommand = { noise.publishStates() }
        // Unprovisioned (no key yet), with a persistent store, as on a fresh panel.
        val store = MemoryKeyStore()
        val hellos = Collections.synchronizedList(ArrayList<String>())
        var activations = 0
        fun provServer(port: Int) = ApiServer(port, { identity }, entities(), NoiseKeys(store)).apply {
            onClientHello = { info, addr -> hellos += "$info@${addr?.hostAddress}" }
            onKeyActivated = { activations++ }
        }
        var prov = provServer(0)
        plain.start()
        noise.start()
        prov.start()
        val provPort = prov.localPort
        var provisionedKey: String? = null
        var keyAtRestart: ByteArray? = null
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_client.py")!!.toURI())
            val proc = ProcessBuilder(python, "-I", script.absolutePath, plain.localPort.toString(),
                noise.localPort.toString(), keyB64, provPort.toString()).redirectErrorStream(true).start()
            val stdin = proc.outputStream.bufferedWriter()
            val log = StringBuilder()
            var result: JSONObject? = null
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    // (the provisioned test key is not echoed, like the app never logs keys)
                    if (!line.startsWith("PROVISIONED ")) synchronized(log) { log.appendLine(line) }
                    when {
                        line == "WAKE" -> {
                            assertTrue(controller.onWakeWord("Hey Jarvis"))
                            // The mic keeps delivering 80 ms chunks; the controller decides what to send.
                            Thread {
                                repeat(100) { controller.onMicAudio(ByteArray(2560)); Thread.sleep(20) }
                            }.start()
                        }
                        line == "FIRE_EVENT" -> assertTrue(plain.sendEvent(button, "double"))
                        // Wall base attached + the dashboard list changed (the select's options).
                        line == "REBUILD" -> plain.setEntities(
                            plainEntities.map { if (it.objectId == "dashboard") dashboardSelect(listOf("Default", "Tablet")) else it } +
                                SwitchEntity("base_relay_1", "Base relay 1", get = { true }, set = {}),
                        )
                        line.startsWith("PROVISIONED ") -> provisionedKey = line.removePrefix("PROVISIONED ")
                        line == "RESTART" -> {
                            keyAtRestart = store.key
                            prov.stop()
                            prov = provServer(provPort)
                            prov.start()
                            stdin.write("RESTARTED\n"); stdin.flush()
                        }
                        line.startsWith("RESULT ") -> result = JSONObject(line.removePrefix("RESULT "))
                    }
                }
            }.apply { start() }
            val exited = proc.waitFor(90, TimeUnit.SECONDS)
            reader.join(2_000)
            val out = synchronized(log) { log.toString() }
            assertTrue("client timed out:\n$out", exited)
            assertEquals("client failed:\n$out", 0, proc.exitValue())
            val r = result ?: throw AssertionError("no result:\n$out")

            val di = r.getJSONObject("device_info")
            assertEquals("s1-panel", di.getString("name"))
            assertEquals("S1 Panel", di.getString("friendly_name"))
            assertEquals("02:11:22:33:44:55", di.getString("mac"))
            assertEquals(61, di.getInt("voice_flags"))
            // HA builds the device page's "Visit" link from it (#33).
            assertEquals(8099, di.getInt("webserver_port"))
            assertEquals(61, r.getInt("caps_voice_flags"))
            assertEquals(1, r.getJSONObject("caps_sendspin").getInt("flags"))
            assertEquals("A".repeat(42) + "Q", r.getJSONObject("caps_sendspin").getString("client_id"))
            assertEquals(SendspinApi.STATUS_ENCRYPTION_REQUIRED, r.getJSONObject("sendspin_token_plain").getInt("status"))
            assertEquals("", r.getJSONObject("sendspin_token_plain").getString("token"))
            assertEquals(15, r.getJSONArray("entities").length())
            assertEquals("double", r.getString("event_type"))
            // Update entity: UpdateCommand CHECK (2) then INSTALL (1), decoded by the real client.
            assertEquals(listOf(2, 1), updateCommands.toList())
            val checked = r.getJSONObject("update_checked")
            assertEquals("1.3.0", checked.getString("latest"))
            assertEquals("1.2.0", checked.getString("current"))
            assertEquals("S1 Kiosk", checked.getString("title"))
            assertEquals("* new things", checked.getString("summary"))
            assertEquals("https://github.com/GlassworksHQ/aqara-s1-unleashed/releases/tag/v1.3.0", checked.getString("url"))
            val installing = r.getJSONObject("update_installing")
            assertTrue(installing.getBoolean("has_progress"))
            assertEquals(42.0, installing.getDouble("progress"), 0.001)
            assertEquals(16, r.getInt("entities_after_rebuild"))
            assertTrue(r.getBoolean("rebuild_relay_state"))
            val dash = r.getJSONObject("dashboard_select")
            assertEquals("[\"Default\"]", dash.getJSONArray("before").toString())
            assertEquals("[\"Default\",\"Tablet\"]", dash.getJSONArray("after").toString())
            assertTrue("same key after the rebuild", dash.getBoolean("same_key"))
            assertEquals("Default", dash.getString("state_after"))
            assertTrue("states subscription reported with client_info: $subscribed",
                subscribed.isNotEmpty() && subscribed.all { it == "Home Assistant test|false" })
            assertTrue("Noise subscription is authenticated: $noiseSubscribed",
                noiseSubscribed.isNotEmpty() && noiseSubscribed.all { it == "Home Assistant test|127.0.0.1|true" })

            assertEquals("[\"hey_jarvis_v0.1\"]", r.getJSONArray("active_before").toString())
            assertEquals("[\"alexa_v0.1\"]", r.getJSONArray("active_after").toString())
            assertEquals(listOf("alexa_v0.1"), catalog.current)

            val v = r.getJSONObject("voice")
            val start = v.getJSONObject("start")
            assertEquals("Hey Jarvis", start.getString("phrase"))
            assertEquals(VoiceRequestFlag.USE_VAD, start.getInt("flags"))
            assertEquals(2560, v.getInt("chunk_bytes"))
            assertTrue("mic audio was streamed", v.getInt("chunks") >= 5)
            assertEquals("streaming stops at STT_VAD_END", v.getInt("chunks"), v.getInt("chunks_after"))
            assertTrue(v.getBoolean("tts_finished"))
            assertTrue(r.getBoolean("announce_success"))

            // TTS started early from the RUN_START url (INTENT_PROGRESS tts_start_streaming) and was
            // not replayed at TTS_END; the announcement plays the pre-announce chime first.
            assertEquals(
                listOf(
                    listOf("http://ha.test:8123/api/tts_proxy/abc.mp3"),
                    listOf("http://ha.test:8123/local/chime.mp3", "http://ha.test:8123/media/a.mp3"),
                ),
                player.played.toList(),
            )
            assertEquals(listOf("pasta"), timers.toList())
            assertEquals(
                listOf(VoiceEvent.WakeDetected, VoiceEvent.Listening, VoiceEvent.Thinking, VoiceEvent.Speaking,
                    VoiceEvent.Idle, VoiceEvent.Speaking, VoiceEvent.Idle),
                events.filter { it !is VoiceEvent.Heard && it !is VoiceEvent.Reply }, // texts for the overlay
            )
            assertEquals(VoiceAssistantController.State.IDLE, controller.currentState)

            assertEquals("s1-panel", r.getJSONObject("noise").getString("name"))
            assertEquals(SendspinApi.STATUS_OK, r.getJSONObject("noise").getInt("sendspin_status"))
            assertEquals("SP:0TESTTOKEN", r.getJSONObject("noise").getString("sendspin_token"))
            val errs = r.getJSONObject("noise_errors")
            assertEquals("s1-panel", errs.getString("wrong_key_name"))
            assertEquals("021122334455", errs.getString("wrong_key_mac"))
            assertEquals("EncryptionPlaintextAPIError", r.getString("noise_to_plain"))

            // Dynamic key provisioning (HA 2026.11 manager.py _async_provision_key_over_noise).
            val p = r.getJSONObject("provisioning")
            assertTrue(p.getBoolean("set_key"))
            assertEquals("s1-panel", p.getString("zero_psk_name"))
            assertTrue("keyless session dropped as an expected disconnect", p.getBoolean("dropped_expected"))
            assertEquals("RequiresEncryptionAPIError", p.getString("plaintext_after"))
            assertEquals("InvalidEncryptionKeyAPIError", p.getString("zero_psk_after"))
            assertEquals("RequiresEncryptionAPIError", p.getString("plaintext_after_restart"))
            assertEquals("s1-panel", p.getString("restart_name"))
            assertTrue(p.getJSONObject("cleared").getBoolean("provisionable"))
            assertTrue("key was provisioned", provisionedKey != null)
            assertTrue("the provisioned key was persisted",
                Base64.getDecoder().decode(provisionedKey).contentEquals(keyAtRestart))
            assertEquals(2, activations) // set, then clear (after the restart: new server instance counts)
            assertEquals("cleared at the end", null, store.key)
            assertTrue("hello client_info + peer address reported: $hellos",
                hellos.any { it == "Home Assistant 2026.11.0@127.0.0.1" })
        } finally {
            plain.stop()
            noise.stop()
            prov.stop()
        }
    }
}
