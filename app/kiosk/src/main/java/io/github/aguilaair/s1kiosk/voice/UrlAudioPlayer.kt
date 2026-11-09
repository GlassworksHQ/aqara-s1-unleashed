package io.github.aguilaair.s1kiosk.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import io.github.aguilaair.s1kiosk.esphome.UrlPlayer

/**
 * Plays HTTP audio URLs (mp3/flac/wav/ogg, whatever Android's MediaPlayer can stream) one after
 * another on its own looper thread. Used for TTS responses and announcements from Home Assistant
 * ([usage] = USAGE_ASSISTANT) and for the media player entity ([usage] = USAGE_MEDIA).
 */
class UrlAudioPlayer(name: String, private val usage: Int) : UrlPlayer {

    private val thread = HandlerThread("audio-$name").apply { start() }
    private val handler = Handler(thread.looper)

    // Player thread only.
    private var mp: MediaPlayer? = null
    private val queue = ArrayDeque<String>()
    private var onDone: ((Boolean) -> Unit)? = null
    private var prepared = false

    @Volatile var isPlaying = false; private set
    @Volatile var isPaused = false; private set
    @Volatile private var volume = 1f

    /** Fired (on the player thread) whenever playing/paused changes. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {
        handler.post {
            finish(false)
            queue.addAll(urls)
            this.onDone = onDone
            next()
        }
    }

    /** Appends to the queue (starts playing if idle). */
    fun enqueue(url: String) {
        handler.post {
            if (mp == null) { queue.add(url); next() } else queue.add(url)
        }
    }

    override fun stop() {
        handler.post { finish(false) }
    }

    fun pause() {
        handler.post {
            val p = mp ?: return@post
            if (prepared && p.isPlaying) { p.pause(); setState(playing = false, paused = true) }
        }
    }

    fun resume() {
        handler.post {
            val p = mp ?: return@post
            if (prepared && !p.isPlaying) { p.start(); setState(playing = true, paused = false) }
        }
    }

    /** 0..1, applied to the current and following tracks. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        handler.post { try { mp?.setVolume(volume, volume) } catch (_: IllegalStateException) {} }
    }

    fun release() {
        handler.post { finish(false) }
        thread.quitSafely()
    }

    private fun setState(playing: Boolean, paused: Boolean) {
        if (playing == isPlaying && paused == isPaused) return
        isPlaying = playing
        isPaused = paused
        try { onStateChanged?.invoke() } catch (_: Exception) {}
    }

    private fun next() {
        releasePlayer()
        val url = queue.removeFirstOrNull()
        if (url == null) { finish(true); return }
        val p = MediaPlayer()
        mp = p
        prepared = false
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(
                        if (usage == AudioAttributes.USAGE_MEDIA) AudioAttributes.CONTENT_TYPE_MUSIC
                        else AudioAttributes.CONTENT_TYPE_SPEECH
                    )
                    .build()
            )
            p.setDataSource(url)
            p.setOnPreparedListener {
                if (mp !== it) return@setOnPreparedListener
                prepared = true
                it.setVolume(volume, volume)
                it.start()
                setState(playing = true, paused = false)
            }
            p.setOnCompletionListener { if (mp === it) next() }
            p.setOnErrorListener { mp2, what, extra ->
                if (mp === mp2) {
                    VoiceLog.w("Playback failed ($what/$extra): $url")
                    queue.clear()
                    finish(false)
                }
                true
            }
            VoiceLog.i("Playing $url")
            p.prepareAsync()
        } catch (e: Exception) {
            VoiceLog.w("Cannot play $url", e)
            queue.clear()
            finish(false)
        }
    }

    private fun releasePlayer() {
        mp?.let {
            try { it.reset() } catch (_: Exception) {}
            it.release()
        }
        mp = null
        prepared = false
    }

    private fun finish(ok: Boolean) {
        releasePlayer()
        queue.clear()
        setState(playing = false, paused = false)
        val cb = onDone
        onDone = null
        try { cb?.invoke(ok) } catch (e: Exception) { VoiceLog.w("onDone failed", e) }
    }
}
