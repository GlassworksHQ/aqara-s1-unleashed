package sh.edm.s1kiosk.esphome

import sh.edm.esphome.*
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
    private var stops = 0
    private var now = 0L

    private val controller = VoiceAssistantController(
        send = { t, p -> sent += t to ProtoMessage(p.toByteArray()); true },
        player = object : UrlPlayer {
            override fun play(urls: List<String>, onDone: (Boolean) -> Unit) { played += urls; pendingDone = onDone }
            override fun stop() { stops++ }
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

    /**
     * #210 review: the voice pane stopping closes its controller. The run ends with Idle, as a cancel
     * does, and a wake word still on its way starts nothing, unlike after the person's cancel.
     */
    @Test
    fun closedItEndsTheRunAndTakesNoOther() {
        startRun()
        events.clear()
        assertTrue(controller.close())
        assertEquals(VoiceEvent.Idle, events.last())
        val requests = requests().size
        assertFalse("no run after close", controller.onWakeWord("Hey Jarvis"))
        assertEquals(requests, requests().size)
        assertEquals(State.IDLE, controller.currentState)
    }

    /** #210 review: a closed controller plays nothing Home Assistant sends after it, a response or an announcement. */
    @Test
    fun closedItPlaysNoResponseAndNoAnnouncement() {
        controller.onSubscribe(true)
        controller.close()
        events.clear()
        controller.onEvent(VoiceEventType.TTS_END, mapOf("url" to "http://ha/t.mp3"))
        assertEquals("not left responding with nothing to play", State.IDLE, controller.currentState)
        controller.onAnnounce("http://ha/a.mp3", "Dinner is ready", "", startConversation = false)
        assertEquals(State.IDLE, controller.currentState)
        assertTrue("nothing played: $played", played.isEmpty())
        assertTrue("no Speaking: $events", events.none { it == VoiceEvent.Speaking })
        assertFalse("the announcement is answered as not played",
            sent.last { it.first == Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED }.second.bool(1))
    }

    /**
     * #210 review: an announcement's playback effect is collected, then the pane closes the controller
     * before the effect runs. Either the playback starts first and the close stops it, or it doesn't
     * start: the last word is Idle, so the speaker is released and paused music resumes.
     */
    @Test
    fun aCloseRacingAPlaybackStartEndsIdle() {
        val log = java.util.Collections.synchronizedList(ArrayList<String>())
        val inReply = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val c = VoiceAssistantController(
            send = { _, _ -> true },
            player = object : UrlPlayer {
                override fun play(urls: List<String>, onDone: (Boolean) -> Unit) { log += "play" }
                override fun stop() { log += "stop" }
            },
            wakeWords = object : WakeWordCatalog {
                override fun available() = emptyList<WakeWordInfo>()
                override fun activeIds() = emptyList<String>()
                override fun setActive(ids: List<String>) {}
            },
            emit = { e ->
                // The effect pauses in its first event, after the lock was released.
                if (e is VoiceEvent.Reply) { inReply.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS) }
                log += e.javaClass.simpleName
            },
            clock = { now },
        )
        c.onSubscribe(true)
        val announcer = Thread { c.onAnnounce("http://ha/a.mp3", "Dinner is ready", "", startConversation = false) }.apply { start() }
        assertTrue(inReply.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val closer = Thread { c.close() }.apply { start() }
        val end = System.currentTimeMillis() + 2_000
        while (closer.isAlive && closer.state != Thread.State.BLOCKED && System.currentTimeMillis() < end) Thread.sleep(5)
        release.countDown()
        announcer.join(5_000); closer.join(5_000)
        val last = log.lastOrNull { it == "Idle" || it == "Speaking" }
        assertEquals("the voice run ends Idle: $log", "Idle", last)
        if ("play" in log) assertTrue("a playback that started is stopped: $log", log.lastIndexOf("stop") > log.indexOf("play"))
    }

    @Test
    fun aCancelledRunLeavesTheNextOneFree() {
        startRun()
        assertTrue(controller.cancel())
        assertTrue(controller.onWakeWord("Hey Jarvis"))
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

    // ---------------------------------------------------------------- texts and cancel (voice overlay)

    @Test
    fun transcriptAndResponseTextAreEmitted() {
        startRun()
        controller.onEvent(VoiceEventType.STT_END, mapOf("text" to "turn off the lights"))
        controller.onEvent(VoiceEventType.TTS_START, mapOf("text" to "Turned off the lights"))
        assertTrue(VoiceEvent.Heard("turn off the lights") in events)
        assertTrue(VoiceEvent.Reply("Turned off the lights", announcement = false) in events)
        assertTrue(events.indexOf(VoiceEvent.Heard("turn off the lights")) < events.indexOf(VoiceEvent.Thinking))
    }

    @Test
    fun announcementTextComesBeforeSpeaking() {
        controller.onSubscribe(true)
        controller.onAnnounce("http://ha/a.mp3", "Dinner is ready", "", startConversation = false)
        assertEquals(listOf(VoiceEvent.Reply("Dinner is ready", announcement = true), VoiceEvent.Speaking), events)
    }

    @Test
    fun cancelWhileStreamingStopsTheRunAndIgnoresItsLateEvents() {
        startRun()
        controller.onEvent(VoiceEventType.RUN_START, mapOf("url" to "http://ha/stream.mp3"))
        assertTrue(controller.cancel())
        assertEquals(State.IDLE, controller.currentState)
        assertFalse("stop request sent", requests().last().bool(1))
        assertEquals(VoiceEvent.Idle, events.last())
        // No more audio goes to HA, and a late transcript/response is neither shown nor played.
        val audio = sent.count { it.first == Msg.VOICE_ASSISTANT_AUDIO }
        controller.onMicAudio(ByteArray(2560))
        assertEquals(audio, sent.count { it.first == Msg.VOICE_ASSISTANT_AUDIO })
        val n = events.size
        controller.onEvent(VoiceEventType.STT_END, mapOf("text" to "late"))
        controller.onEvent(VoiceEventType.INTENT_PROGRESS, mapOf("tts_start_streaming" to "1"))
        controller.onEvent(VoiceEventType.TTS_START, mapOf("text" to "late"))
        controller.onEvent(VoiceEventType.TTS_END, mapOf("url" to "http://ha/t.mp3"))
        controller.onEvent(VoiceEventType.RUN_END, emptyMap())
        assertEquals(n, events.size)
        assertTrue(played.isEmpty())
        assertFalse(controller.cancel()) // nothing left to cancel
        // The next run works normally again.
        assertTrue(controller.onWakeWord("Hey Jarvis"))
        controller.onStartResponse(0, false)
        controller.onEvent(VoiceEventType.TTS_END, mapOf("url" to "http://ha/t2.mp3"))
        assertEquals(listOf(listOf("http://ha/t2.mp3")), played)
    }

    @Test
    fun cancelWhileSpeakingStopsPlaybackAndSkipsTheFollowUp() {
        startRun()
        controller.onEvent(VoiceEventType.INTENT_END, mapOf("conversation_id" to "c1", "continue_conversation" to "1"))
        controller.onEvent(VoiceEventType.TTS_END, mapOf("url" to "http://ha/t.mp3"))
        assertEquals(State.RESPONDING, controller.currentState)
        val requestsBefore = requests().size
        assertTrue(controller.cancel())
        assertEquals(1, stops)
        assertEquals(State.IDLE, controller.currentState)
        assertTrue(sent.last().first == Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED && !sent.last().second.bool(1))
        // The stopped player reports done: ignored, no follow-up run.
        pendingDone!!(false)
        assertEquals(State.IDLE, controller.currentState)
        assertEquals(requestsBefore, requests().size)
        assertEquals(1, events.count { it == VoiceEvent.Idle })
    }
}
