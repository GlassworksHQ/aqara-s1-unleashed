package sh.edm.s1kiosk.esphome

import sh.edm.s1kiosk.esphome.VoiceAssistantController.State
import sh.edm.s1kiosk.voice.VoiceEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantControllerTest {

    private val sent = ArrayList<Pair<Int, ProtoMessage>>()
    private val events = ArrayList<VoiceEvent>()
    private val played = ArrayList<List<String>>()
    private var pendingDone: ((Boolean) -> Unit)? = null
    private var now = 0L

    private val controller = VoiceAssistantController(
        send = { t, p -> sent += t to ProtoMessage(p.toByteArray()); true },
        player = object : UrlPlayer {
            override fun play(urls: List<String>, onDone: (Boolean) -> Unit) { played += urls; pendingDone = onDone }
            override fun stop() {}
        },
        wakeWords = object : WakeWordCatalog {
            override fun available() = emptyList<WakeWordInfo>()
            override fun activeIds() = emptyList<String>()
            override fun setActive(ids: List<String>) {}
        },
        emit = { events += it },
        clock = { now },
    )

    private fun requests() = sent.filter { it.first == Msg.VOICE_ASSISTANT_REQUEST }.map { it.second }

    private fun startRun() {
        controller.onSubscribe(true)
        assertTrue(controller.onWakeWord("Hey Jarvis"))
        controller.onStartResponse(0, false)
        assertEquals(State.STREAMING, controller.currentState)
    }

    @Test
    fun wakeWithoutHomeAssistantIsAnError() {
        assertFalse(controller.onWakeWord("Hey Jarvis"))
        assertTrue(events.single() is VoiceEvent.Error)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun prerollIsFlushedWhenHomeAssistantStarts() {
        controller.onSubscribe(true)
        controller.onWakeWord("Hey Jarvis")
        repeat(3) { controller.onMicAudio(ByteArray(2560)) }
        assertTrue(sent.none { it.first == Msg.VOICE_ASSISTANT_AUDIO })
        controller.onStartResponse(0, false)
        assertEquals(3, sent.count { it.first == Msg.VOICE_ASSISTANT_AUDIO })
    }

    @Test
    fun runEndWithoutTtsGoesIdle() {
        startRun()
        controller.onEvent(VoiceEventType.STT_VAD_END, emptyMap())
        controller.onEvent(VoiceEventType.RUN_END, emptyMap())
        assertEquals(State.IDLE, controller.currentState)
        assertEquals(VoiceEvent.Idle, events.last())
    }

    @Test
    fun pipelineErrorStopsStreaming() {
        startRun()
        controller.onEvent(VoiceEventType.ERROR, mapOf("code" to "stt-no-text-recognized", "message" to "No text"))
        assertEquals(State.IDLE, controller.currentState)
        assertFalse("stop request sent", requests().last().bool(1))
        assertTrue(events.last() is VoiceEvent.Error)
    }

    @Test
    fun maxStreamSendsEndOfAudio() {
        startRun()
        now += 16_000
        controller.tick()
        assertEquals(State.AWAITING, controller.currentState)
        assertTrue(sent.last().first == Msg.VOICE_ASSISTANT_AUDIO && sent.last().second.bool(2))
    }

    @Test
    fun continueConversationStartsAgainWithoutWakeWord() {
        startRun()
        controller.onEvent(VoiceEventType.STT_END, mapOf("text" to "set a timer"))
        controller.onEvent(
            VoiceEventType.INTENT_END,
            mapOf("conversation_id" to "c1", "continue_conversation" to "1"),
        )
        controller.onEvent(VoiceEventType.TTS_END, mapOf("url" to "http://ha/t.mp3"))
        controller.onEvent(VoiceEventType.RUN_END, emptyMap())
        assertEquals(State.RESPONDING, controller.currentState)
        pendingDone!!(true)
        assertTrue(sent.any { it.first == Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED && it.second.bool(1) })
        assertEquals(State.STARTING, controller.currentState)
        val again = requests().last()
        assertTrue(again.bool(1))
        assertEquals("c1", again.string(2))
        assertEquals("", again.string(5)) // no wake word phrase
        assertEquals(listOf(listOf("http://ha/t.mp3")), played)
    }
}
