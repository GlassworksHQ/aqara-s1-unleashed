package io.github.aguilaair.s1kiosk.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import io.github.aguilaair.s1kiosk.esphome.UrlPlayer
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * HTTP URLs are downloaded by the app to a temp file first and MediaPlayer plays the local file.
 * Reason: the panel's vendor player (Allwinner CedarX in mediaserver) aborts in its own HTTP
 * stream code (`FORTIFY: FD_SET: file descriptor >= FD_SETSIZE` in libcdx_base CdxSockAsynConnect),
 * killing mediaserver. Local-file playback never touches that code. Endless streams (radio) are
 * therefore not supported; downloads are capped at [MAX_DOWNLOAD] bytes.
 *
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
    private var gen = 0
    private var tmp: File? = null
    private val downloader = Executors.newSingleThreadExecutor()

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
        val myGen = ++gen
        if (url.startsWith("http://") || url.startsWith("https://")) {
            VoiceLog.i("Downloading $url")
            downloader.execute {
                val f = try { download(url) } catch (e: Exception) { VoiceLog.w("Download failed: $url", e); null }
                handler.post {
                    if (myGen != gen) { f?.delete(); return@post }
                    if (f == null) { queue.clear(); finish(false); return@post }
                    tmp = f
                    startPlayer(url, f)
                }
            }
        } else {
            startPlayer(url, null)
        }
    }

    private fun download(url: String): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 30_000
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val f = File.createTempFile("s1audio", ".bin") // java.io.tmpdir = app cache dir on Android
            var total = 0L
            conn.inputStream.use { input ->
                f.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD) { f.delete(); throw java.io.IOException("too large / endless stream") }
                        out.write(buf, 0, n)
                    }
                }
            }
            VoiceLog.d("Downloaded $total bytes")
            return f
        } finally {
            conn.disconnect()
        }
    }

    private fun startPlayer(url: String, file: File?) {
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
            if (file != null) FileInputStream(file).use { p.setDataSource(it.fd) } else p.setDataSource(url)
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
        tmp?.delete()
        tmp = null
        mp?.let {
            try { it.reset() } catch (_: Exception) {}
            it.release()
        }
        mp = null
        prepared = false
    }

    private fun finish(ok: Boolean) {
        gen++ // cancels any in-flight download
        releasePlayer()
        queue.clear()
        setState(playing = false, paused = false)
        val cb = onDone
        onDone = null
        try { cb?.invoke(ok) } catch (e: Exception) { VoiceLog.w("onDone failed", e) }
    }

    companion object {
        private const val MAX_DOWNLOAD = 32L * 1024 * 1024
    }
}
