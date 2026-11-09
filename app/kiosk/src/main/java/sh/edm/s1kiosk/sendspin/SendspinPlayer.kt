package sh.edm.s1kiosk.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Process
import sh.edm.s1kiosk.BuildConfig
import sh.edm.s1kiosk.KioskPrefs
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [SsStore] in the app's private SharedPreferences (`sendspin.xml`). Secrets are never logged. */
class PrefsSsStore(context: Context) : SsStore {
    private val sp = context.applicationContext.getSharedPreferences("sendspin", Context.MODE_PRIVATE)
    @Volatile private var cachedIdentity: SsIdentity? = null

    @Synchronized override fun identity(): SsIdentity {
        cachedIdentity?.let { return it }
        val saved = sp.getString("identity", null)?.let { runCatching { SsIdentity(SsCrypto.unb64url(it)) }.getOrNull() }
        val id = saved ?: SsIdentity.generate().also { sp.edit().putString("identity", SsCrypto.b64url(it.privateKey)).commit() }
        cachedIdentity = id
        return id
    }

    @Synchronized override fun pairingPsk(): ByteArray {
        sp.getString("pairing_psk", null)?.let { s -> runCatching { SsCrypto.unb64url(s) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it } }
        val psk = SsCrypto.randomBytes(32)
        sp.edit().putString("pairing_psk", SsCrypto.b64url(psk)).commit()
        return psk
    }

    @Synchronized override fun records() = SsStore.decodeRecords(sp.getString("records", null))

    @Synchronized override fun putRecord(r: SsPairingRecord, protectedServerIds: Set<String>) {
        sp.edit().putString("records", SsStore.encodeRecords(SsStore.merge(records(), r, protectedServerIds))).commit()
    }

    @Synchronized override fun removeRecord(serverId: String) {
        sp.edit().putString("records", SsStore.encodeRecords(records().filter { it.serverId != serverId })).commit()
    }

    @Synchronized override fun touchRecord(serverId: String, nowMs: Long) {
        sp.edit().putString("records", SsStore.encodeRecords(records().map { if (it.serverId == serverId) it.copy(lastUsedMs = nowMs) else it })).apply()
    }

    override var outputDelayMs: Int
        get() = sp.getInt("output_delay_ms", 0)
        set(v) = sp.edit().putInt("output_delay_ms", v.coerceIn(0, 5000)).apply()
    override var volume: Int
        get() = sp.getInt("volume", 60)
        set(v) = sp.edit().putInt("volume", v.coerceIn(0, 100)).apply()
    override var muted: Boolean
        get() = sp.getBoolean("muted", false)
        set(v) = sp.edit().putBoolean("muted", v).apply()
    override var lastPlaybackServerId: String?
        get() = sp.getString("last_playback_server", null)
        set(v) = sp.edit().putString("last_playback_server", v).apply()
}

/** [AudioSink] on an [AudioTrack] (USAGE_MEDIA, PCM 16-bit, streaming, ~100 ms buffer). */
class AudioTrackSink : AudioSink {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var gain = 1f
    private var channels = 2
    private val at = AudioTimestamp()

    override fun open(sampleRate: Int, channels: Int) {
        close()
        this.channels = channels
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val enc = AudioFormat.ENCODING_PCM_16BIT
        val frame = 2 * channels
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, mask, enc)
        val bytes = (maxOf(minBuf, sampleRate * frame / 10) + frame - 1) / frame * frame
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(enc).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        t.setVolume(gain)
        t.play()
        track = t
        SsLog.i("AudioTrack open: $sampleRate Hz, $channels ch, buffer ${bytes / frame} frames (min ${minBuf / frame}), latency hint ${bytes * 1000 / (frame * sampleRate)} ms")
    }

    override fun write(buf: ShortArray, frames: Int): Int {
        val t = track ?: return 0
        val n = t.write(buf, 0, frames * channels, AudioTrack.WRITE_BLOCKING)
        return if (n <= 0) 0 else n / channels
    }

    override fun timestamp(out: LongArray): Boolean {
        val t = track ?: return false
        if (!t.getTimestamp(at)) return false
        out[0] = at.framePosition
        out[1] = at.nanoTime
        return true
    }

    override fun setGain(gain: Float) {
        this.gain = gain
        track?.setVolume(gain)
    }

    override fun close() {
        val t = track ?: return
        track = null
        try { t.pause(); t.flush() } catch (_: IllegalStateException) {}
        t.release()
    }
}

/**
 * The panel as a native Sendspin player for Music Assistant (docs/sendspin.md). Owned by
 * PanelDevice; runs in KioskService.
 *
 * - Client-initiated connection to the Sendspin server: `_sendspin-server._tcp` via mDNS, else
 *   Music Assistant's default port on the Home Assistant host (`ws://<ha host>:8927/sendspin`),
 *   or the `sendspin_server` setting. Reconnects with exponential backoff (1 s … 60 s).
 * - Pairing: Home Assistant reads the pairing token over the ESPHome API and Music Assistant
 *   pairs with it ([pairingToken]); or unpaired ("guest") access if enabled in the settings.
 * - Audio: [PlaybackScheduler] on an [AudioTrackSink], volume/mute separate from the voice volume.
 */
class SendspinPlayer(
    context: Context,
    private val prefs: KioskPrefs,
    private val macAddress: String,
    private val onStatusChanged: () -> Unit = {},
) {
    private val ctx = context.applicationContext
    val store: SsStore = PrefsSsStore(ctx)
    private val sink = AudioTrackSink()
    @Volatile private var session: SendspinSession? = null
    private val scheduler = PlaybackScheduler(sink, { session?.timeFilter?.snapshot ?: EMPTY })
    @Volatile private var running = false
    @Volatile private var ducked = false
    @Volatile private var connThread: Thread? = null
    @Volatile private var audioThread: Thread? = null
    @Volatile private var statsThread: Thread? = null
    private val wake = Object()
    @Volatile var status: SendspinSession.Status = SendspinSession.Status(SendspinSession.Phase.CLOSED); private set
    /** True once the client has started since boot (ESPHome reports the client id only then). */
    @Volatile var started = false; private set
    @Volatile private var lastUrl: String? = null
    @Volatile private var nowPlaying: String? = null

    private val hello = SsHelloInfo(
        name = "S1 Panel",
        productName = "Panel Hub S1 Plus (S1 Kiosk)",
        manufacturer = "Aqara",
        softwareVersion = "S1 Kiosk ${BuildConfig.VERSION_NAME}",
        macAddress = macAddress.lowercase().takeIf { MAC_RE.matches(it) },
        formats = listOf(SsFormat.PCM_48K_STEREO_16),
        bufferCapacity = BUFFER_CAPACITY,
    )

    val clientId: String get() = store.identity().peerId

    /** The version-0 pairing token (a credential: never log it). */
    fun pairingToken(): String = SsCrypto.pairingToken(store.identity().publicKey, store.pairingPsk())

    /** One-line state for the ESPHome diagnostic sensor. */
    fun statusText(): String {
        if (!running) return "off"
        val s = status
        val server = s.serverName ?: "server"
        return when (s.phase) {
            SendspinSession.Phase.CONNECTING, SendspinSession.Phase.HANDSHAKE -> "connecting"
            SendspinSession.Phase.CLOSED -> "disconnected" + (s.closeReason?.let { " ($it)" } ?: "")
            SendspinSession.Phase.HELD -> "waiting for pairing or approval by $server"
            SendspinSession.Phase.PAIRING -> "pairing with $server"
            SendspinSession.Phase.READY -> if (s.paired) "ready, paired with $server" else "ready, unpaired guest of $server"
            SendspinSession.Phase.PLAYING -> "playing from $server" + (nowPlaying?.let { ": $it" } ?: "")
        }.take(255)
    }

    @Synchronized fun start() {
        if (running) return
        running = true
        started = true
        store.identity(); store.pairingPsk() // create both before HA asks for the token
        SsLog.i("Sendspin player starting (client id $clientId)")
        applyGain()
        scheduler.outputDelayUs = store.outputDelayMs * 1000L
        audioThread = Thread({ audioLoop() }, "ss-audio").apply { isDaemon = true; start() }
        connThread = Thread({ connectLoop() }, "ss-conn").apply { isDaemon = true; start() }
        statsThread = Thread({ statsLoop() }, "ss-stats").apply { isDaemon = true; start() }
    }

    /** Stops, telling the server why (`user_request` when switched off, `shutdown` when the app stops). */
    @Synchronized fun stop(reason: String = "user_request") {
        if (!running) return
        running = false
        session?.close(reason)
        synchronized(wake) { wake.notifyAll() }
        connThread?.interrupt(); audioThread?.interrupt(); statsThread?.interrupt()
        scheduler.end()
        // The audio thread owns the sink; let it close it before a restart could start another.
        try { audioThread?.join(1_000) } catch (_: InterruptedException) {}
        connThread = null; audioThread = null; statsThread = null
        status = SendspinSession.Status(SendspinSession.Phase.CLOSED)
        onStatusChanged()
    }

    /** Reconnects so a new `client/hello` carries a changed setting (unpaired access, server URL). */
    fun restartSession() { session?.close("restart") }

    /** Voice assistant active: mute locally while staying in sync (spec allows suspending output locally). */
    fun setDucked(d: Boolean) {
        if (ducked == d) return
        ducked = d
        applyGain()
    }

    private fun applyGain() {
        val g = if (ducked || store.muted) 0f else PlaybackScheduler.amplitude(store.volume)
        sink.setGain(g)
    }

    // ------------------------------------------------------------------ threads

    private fun connectLoop() {
        var backoffMs = 1_000L
        while (running) {
            val url = resolveUrl()
            if (url == null) {
                SsLog.w("No Sendspin server known yet (no mDNS answer, Home Assistant not found); retrying")
                if (!sleep(10_000)) break
                continue
            }
            val s = SendspinSession(url, store, hello, { prefs.sendspinUnpairedAccess }, hooks)
            session = s
            SsLog.i("Connecting to $url")
            val r = s.run()
            session = null
            status = s.status()
            onStatusChanged()
            if (!running) break
            if (r.established) lastUrl = url else if (url == lastUrl) lastUrl = null
            backoffMs = if (r.activated) 1_000L else minOf(backoffMs * 2, 60_000L)
            SsLog.i("Disconnected (${r.reason}); reconnecting in ${backoffMs / 1000} s")
            if (!sleep(backoffMs)) break
        }
    }

    private fun sleep(ms: Long): Boolean {
        synchronized(wake) { if (running) try { wake.wait(ms) } catch (_: InterruptedException) { return false } }
        return running
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (running) {
                if (!scheduler.step()) scheduler.awaitWork(200)
            }
        } catch (_: InterruptedException) {
        } catch (t: Throwable) {
            SsLog.w("audio thread failed", t)
        } finally {
            scheduler.end()
            scheduler.step() // closes the sink
        }
    }

    /** Every 10 s while streaming: the sync figures (docs/sendspin.md "Measuring"). */
    private fun statsLoop() {
        var prev = scheduler.stats()
        try {
            while (running) {
                Thread.sleep(10_000)
                val s = session ?: continue
                val st = scheduler.stats()
                if (scheduler.isActive) {
                    val f = s.timeFilter.snapshot
                    SsLog.i(
                        "sync: offset %.3f ms ±%.0f µs drift %.2f ppm%s | err %.0f µs (smoothed %.0f) buffer %.0f ms | per 10 s: +%d/-%d frames, snaps %d/%d, underrun blocks %d, late chunks %d, chunks %d".format(
                            f.offset / 1000, f.offsetStdDevUs, f.drift * 1e6, if (f.useDrift) "" else " (unused)",
                            st.lastErrUs, st.smoothErrUs, st.bufferedMs,
                            st.duplicated - prev.duplicated, st.dropped - prev.dropped,
                            st.snapsEarly - prev.snapsEarly, st.snapsLate - prev.snapsLate,
                            st.underrunBlocks - prev.underrunBlocks, st.lateChunks - prev.lateChunks, st.chunks - prev.chunks,
                        )
                    )
                }
                prev = st
            }
        } catch (_: InterruptedException) {}
    }

    // ------------------------------------------------------------------ server address

    private fun resolveUrl(): String? {
        val setting = prefs.sendspinServer.trim()
        if (!setting.equals(KioskPrefs.AUTO, ignoreCase = true) && setting.isNotEmpty()) return normalize(setting)
        lastUrl?.let { return it }
        discover()?.let { return it }
        val host = prefs.haBase?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
        return "ws://${if (host.contains(':')) "[$host]" else host}:$DEFAULT_SERVER_PORT$DEFAULT_PATH"
    }

    private fun normalize(s: String): String = when {
        s.startsWith("ws://") -> s
        s.contains("://") -> s // other schemes fail in WsClient with a clear message
        else -> {
            val hasPort = Regex(":[0-9]+(/|$)").containsMatchIn(s.substringAfterLast(']'))
            val hostPort = if (hasPort) s.substringBefore('/') else "${s.substringBefore('/')}:$DEFAULT_SERVER_PORT"
            "ws://$hostPort" + (s.substringAfter('/', "").let { if (it.isEmpty()) DEFAULT_PATH else "/$it" })
        }
    }

    /** `_sendspin-server._tcp` with its `path` TXT (spec "Client Initiated Connections"); 3 s budget. */
    private fun discover(): String? {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val found = CountDownLatch(1)
        var result: String? = null
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) { found.countDown() }
            override fun onServiceResolved(info: NsdServiceInfo) {
                val addr = info.host
                val path = info.attributes["path"]?.toString(Charsets.UTF_8)?.takeIf { it.startsWith("/") } ?: DEFAULT_PATH
                if (addr != null && !addr.isLoopbackAddress && !addr.isAnyLocalAddress) {
                    val h = addr.hostAddress?.let { if (it.contains(':')) "[${it.substringBefore('%')}]" else it }
                    result = "ws://$h:${info.port}$path"
                }
                found.countDown()
            }
        }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            @Volatile var resolving = false
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { found.countDown() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (resolving) return
                resolving = true
                try { nsd.resolveService(info, resolveListener) } catch (e: Exception) { found.countDown() }
            }
        }
        return try {
            nsd.discoverServices("_sendspin-server._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            found.await(3, TimeUnit.SECONDS)
            result?.also { SsLog.i("Found Sendspin server via mDNS: $it") }
        } catch (e: Exception) {
            null
        } finally {
            try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ session hooks

    private val hooks = object : SsPlayerHooks {
        override fun onStreamStart(format: SsFormat) { scheduler.start(format) }
        override fun onStreamClear(fromTimestampUs: Long?) { scheduler.clear(fromTimestampUs) }
        override fun onStreamEnd() { scheduler.end(); nowPlaying = null }

        override fun onAudio(chunk: SsAudioChunk, receivedNanos: Long) {
            val f = SsFormat.PCM_48K_STEREO_16
            val len = chunk.dataLength - chunk.dataLength % f.frameBytes
            val pcm = PlaybackScheduler.decodePcm(chunk.data, chunk.dataOffset, len, f.bitDepth)
            scheduler.push(PcmChunk(chunk.timestampUs, pcm, pcm.size / f.channels, f.sampleRate, f.channels, chunk.data.size))
        }

        override fun onVolume(volume: Int) { store.volume = volume; applyGain(); onStatusChanged() }
        override fun onMute(muted: Boolean) { store.muted = muted; applyGain(); onStatusChanged() }
        override fun onOutputDelay(ms: Int) {
            store.outputDelayMs = ms
            scheduler.outputDelayUs = ms * 1000L
            SsLog.i("Output delay set to $ms ms")
        }

        override fun playerState() = SsPlayerState(
            volume = store.volume,
            muted = store.muted,
            outputDelayMs = store.outputDelayMs,
            requiredLeadTimeMs = REQUIRED_LEAD_TIME_MS,
            minBufferMs = MIN_BUFFER_MS,
        )

        override fun onMetadata(title: String?, artist: String?) {
            val np = listOfNotNull(artist, title).joinToString(" – ").takeIf { it.isNotEmpty() }
            if (np != nowPlaying) { nowPlaying = np; SsLog.i("Now playing: ${np ?: "-"}"); onStatusChanged() }
        }

        override fun onStatus(status: SendspinSession.Status) {
            this@SendspinPlayer.status = status
            onStatusChanged()
        }
    }

    /** Local volume/mute changes (e.g. from Home Assistant) are reported to the server. */
    fun setVolume(volume: Int) { store.volume = volume.coerceIn(0, 100); applyGain(); session?.sendState() }
    fun setMuted(muted: Boolean) { store.muted = muted; applyGain(); session?.sendState() }
    val volume: Int get() = store.volume
    val muted: Boolean get() = store.muted

    companion object {
        const val DEFAULT_SERVER_PORT = 8927
        const val DEFAULT_PATH = "/sendspin"
        /** ~7.8 s of 48 kHz stereo 16-bit (+ headers): what the server may have outstanding. */
        const val BUFFER_CAPACITY = 1_500_000
        /** AudioTrack creation (~50 ms) + its 100 ms buffer + network, with margin. */
        const val REQUIRED_LEAD_TIME_MS = 300
        /** Wi-Fi jitter allowance for live streams. */
        const val MIN_BUFFER_MS = 200
        private val MAC_RE = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        private val EMPTY = TimeFilter.Snapshot(0.0, 0.0, 0, false, 0, Double.POSITIVE_INFINITY)
    }
}
