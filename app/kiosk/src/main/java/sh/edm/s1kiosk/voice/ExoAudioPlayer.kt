package sh.edm.s1kiosk.voice

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import sh.edm.s1kiosk.esphome.UrlPlayer

/**
 * Streams HTTP audio (mp3/flac/aac/ogg/wav, finite or endless) with Media3 ExoPlayer.
 *
 * Why not android.media.MediaPlayer: on this panel it is backed by Allwinner's CedarX player in
 * mediaserver, whose HTTP stack aborts (`FORTIFY: FD_SET: file descriptor >= FD_SETSIZE` in
 * libcdx_base). ExoPlayer fetches over HTTP in-process (Java) and decodes with MediaCodec, so it
 * streams endless sources too (Music Assistant flow mode, radio) and starts TTS sooner.
 *
 * All ExoPlayer calls run on this player's own looper thread.
 */
class ExoAudioPlayer(context: Context, name: String, private val usage: Int) : UrlPlayer {

    private val thread = HandlerThread("exo-$name").apply { start() }
    private val handler = Handler(thread.looper)
    private val app = context.applicationContext
    private lateinit var player: ExoPlayer

    // Player thread only.
    private var onDone: ((Boolean) -> Unit)? = null

    @Volatile var isPlaying = false; private set
    @Volatile var isPaused = false; private set
    @Volatile private var volume = 1f

    /** Fired (on the player thread) whenever playing/paused changes. */
    @Volatile var onStateChanged: (() -> Unit)? = null

    init {
        handler.post {
            player = ExoPlayer.Builder(app)
                .setLooper(thread.looper)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(
                            if (usage == C.USAGE_MEDIA) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_SPEECH
                        )
                        .build(),
                    /* handleAudioFocus = */ false,
                )
                .build()
            player.volume = volume
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    val paused = !playing && player.playbackState == Player.STATE_READY && !player.playWhenReady
                    setState(playing, paused)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) finish(true)
                }

                override fun onPlayerError(error: PlaybackException) {
                    VoiceLog.w("Playback failed (${error.errorCodeName}): ${player.currentMediaItem?.localConfiguration?.uri}", error)
                    finish(false)
                }
            })
        }
    }

    override fun play(urls: List<String>, onDone: (Boolean) -> Unit) {
        handler.post {
            finish(false)
            if (urls.isEmpty()) { onDone(true); return@post }
            this.onDone = onDone
            urls.forEach { VoiceLog.i("Playing $it") }
            player.setMediaItems(urls.map { MediaItem.fromUri(it) })
            player.prepare()
            player.playWhenReady = true
        }
    }

    /** Appends to the queue (starts playing if idle). */
    fun enqueue(url: String) {
        handler.post {
            player.addMediaItem(MediaItem.fromUri(url))
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                player.prepare()
                player.playWhenReady = true
            }
        }
    }

    override fun stop() {
        handler.post { finish(false) }
    }

    fun pause() {
        handler.post { if (player.isPlaying) { player.playWhenReady = false; setState(playing = false, paused = true) } }
    }

    fun resume() {
        handler.post { if (isPaused) { player.playWhenReady = true } }
    }

    /** 0..1, applied immediately. */
    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        handler.post { player.volume = volume }
    }

    fun release() {
        handler.post {
            finish(false)
            player.release()
            thread.quitSafely()
        }
    }

    private fun setState(playing: Boolean, paused: Boolean) {
        if (playing == isPlaying && paused == isPaused) return
        isPlaying = playing
        isPaused = paused
        try { onStateChanged?.invoke() } catch (_: Exception) {}
    }

    private fun finish(ok: Boolean) {
        if (player.mediaItemCount > 0 || player.playbackState != Player.STATE_IDLE) {
            player.stop()
            player.clearMediaItems()
        }
        setState(playing = false, paused = false)
        val cb = onDone
        onDone = null
        try { cb?.invoke(ok) } catch (e: Exception) { VoiceLog.w("onDone failed", e) }
    }
}
