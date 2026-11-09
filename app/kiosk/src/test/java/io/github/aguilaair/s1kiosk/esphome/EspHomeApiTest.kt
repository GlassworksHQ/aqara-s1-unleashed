package io.github.aguilaair.s1kiosk.esphome

import io.github.aguilaair.s1kiosk.voice.VoiceEvent
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

    /** In-memory stand-ins for the panel's entities (same object ids as PanelDevice). */
    private fun entities(): List<ApiEntity> {
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
        )
    }

    @Test
    fun homeAssistantClient() {
        val python = System.getenv("ESPHOME_PYTHON")
        assumeTrue("set ESPHOME_PYTHON to a python with aioesphomeapi installed", !python.isNullOrBlank())

        val identity = DeviceIdentity(
            name = "s1-panel", friendlyName = "S1 Panel", macAddress = "02:11:22:33:44:55",
            esphomeVersion = "2026.10.2", compilationTime = "S1 Kiosk test", manufacturer = "Aqara",
            model = "Panel Hub S1 Plus (S1 Kiosk)", voiceFeatures = 61,
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
        plain.onCommand = { plain.publishStates() }
        val noise = ApiServer(0, { identity }, entities(), psk = key)
        noise.onCommand = { noise.publishStates() }
        plain.start()
        noise.start()
        try {
            val script = File(javaClass.classLoader!!.getResource("esphome_client.py")!!.toURI())
            val proc = ProcessBuilder(python, "-I", script.absolutePath, plain.localPort.toString(),
                noise.localPort.toString(), keyB64).redirectErrorStream(true).start()
            val log = StringBuilder()
            var result: JSONObject? = null
            val reader = Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(log) { log.appendLine(line) }
                    when {
                        line == "WAKE" -> {
                            assertTrue(controller.onWakeWord("Hey Jarvis"))
                            // The mic keeps delivering 80 ms chunks; the controller decides what to send.
                            Thread {
                                repeat(100) { controller.onMicAudio(ByteArray(2560)); Thread.sleep(20) }
                            }.start()
                        }
                        line == "FIRE_EVENT" -> assertTrue(plain.sendEvent(button, "double"))
                        line == "REBUILD" -> plain.setEntities(
                            plainEntities + SwitchEntity("base_relay_1", "Base relay 1", get = { true }, set = {}),
                        )
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
            assertEquals(61, r.getInt("caps_voice_flags"))
            assertEquals(13, r.getJSONArray("entities").length())
            assertEquals("double", r.getString("event_type"))
            assertEquals(14, r.getInt("entities_after_rebuild"))
            assertTrue(r.getBoolean("rebuild_relay_state"))

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
                events.toList(),
            )
            assertEquals(VoiceAssistantController.State.IDLE, controller.currentState)

            assertEquals("s1-panel", r.getJSONObject("noise").getString("name"))
            val errs = r.getJSONObject("noise_errors")
            assertEquals("s1-panel", errs.getString("wrong_key_name"))
            assertEquals("021122334455", errs.getString("wrong_key_mac"))
            assertEquals("EncryptionPlaintextAPIError", r.getString("noise_to_plain"))
        } finally {
            plain.stop()
            noise.stop()
        }
    }
}
