package sh.edm.s1kiosk.sendspin

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Process
import sh.edm.s1kiosk.BuildConfig
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
 * - Audio: FLAC (preferred, decoded by MediaCodec) or PCM, through [AudioPipeline] into
 *   [PlaybackScheduler] on an [AudioTrackSink]; volume/mute separate from the voice volume. If
 *   FLAC keeps failing, the player reconnects offering PCM only (see [statusText]).
 */
class SendspinPlayer(
    context: Context,
    private val prefs: SendspinPrefs,
    private val haBase: () -> String?,
    private val macAddress: String,
    private val onStatusChanged: () -> Unit = {},
    val store: SsStore = PrefsSsStore(context),
    private val connect: (url: String, hello: SsHelloInfo, hooks: SsPlayerHooks) -> SsConnection =
        { url, hello, hooks -> SendspinSession(url, store, hello, { prefs.unpairedAccess }, hooks) },
    private val newPipeline: (out: PcmOutput, onFlacFailed: (String) -> Unit) -> AudioPipeline =
        { out, onFlacFailed -> AudioPipeline(out, { MediaCodecFlacDecoder() }, onFlacFailed) },
) {
    private val ctx = context.applicationContext
    private val sink = AudioTrackSink()
    @Volatile private var session: SsConnection? = null
    private val scheduler = PlaybackScheduler(sink, { session?.timeFilter?.snapshot ?: EMPTY })
    /** The run since the last [start], or null when stopped. Written only under this player's lock. */
    @Volatile private var current: Run? = null
    /**
     * Also held while [stop] clears [current]. A run's decode thread takes it, not the player's
     * lock, because [stop] joins that thread while it holds the player's lock.
     */
    private val outputLock = Any()
    @Volatile private var ducked = false
    private val wake = Object()
    @Volatile var status: SendspinSession.Status = SendspinSession.Status(SendspinSession.Phase.CLOSED); private set
    /** True once the client has started since boot (ESPHome reports the client id only then). */
    @Volatile var started = false; private set
    @Volatile private var lastUrl: String? = null
    @Volatile private var nowPlaying: String? = null
    /** Why FLAC is no longer offered this run (decoder failures), or null. */
    @Volatile private var flacFailure: String? = null
    /** The platform's FLAC decoder, or null if there is none (then only PCM is offered). */
    private val flacDecoder: String? by lazy { MediaCodecFlacDecoder.available() }

    /** FLAC first (spec: first is preferred), PCM as the fallback; PCM only when FLAC is off or failed. */
    private fun offeredFormats(): List<SsFormat> =
        if (prefs.flac && flacFailure == null && flacDecoder != null) listOf(SsFormat.FLAC_48K_STEREO_16, SsFormat.PCM_48K_STEREO_16)
        else listOf(SsFormat.PCM_48K_STEREO_16)

    /** Built per connection: the formats can change between connections. */
    private fun hello() = SsHelloInfo(
        name = "S1 Panel",
        productName = "Panel Hub S1 Plus (S1 Kiosk)",
        manufacturer = "Aqara",
        softwareVersion = "S1 Kiosk ${BuildConfig.VERSION_NAME}",
        macAddress = macAddress.lowercase().takeIf { MAC_RE.matches(it) },
        formats = offeredFormats(),
        bufferCapacity = BUFFER_CAPACITY,
    )

    val clientId: String get() = store.identity().peerId

    /** The version-0 pairing token (a credential: never log it). */
    fun pairingToken(): String = SsCrypto.pairingToken(store.identity().publicKey, store.pairingPsk())

    /**
     * One [start] to its [stop], with what its threads own. Once it is no longer [current], its
     * threads exit and its sessions can reach only its own (closed) pipeline, even if a reader is
     * still busy: a quick stop and start can't let the old run act on the new one. The pipeline
     * reaches the shared scheduler only while its run is current.
     */
    private inner class Run {
        val pipeline = newPipeline(outputFor(this)) { reason -> onFlacFailed(this, reason) }
        val hooks = hooksFor(this)
        @Volatile var backoffMs = 1_000L
        val connThread = Thread({ connectLoop(this) }, "ss-conn").apply { isDaemon = true }
        val audioThread = Thread({ audioLoop(this) }, "ss-audio").apply { isDaemon = true }
        val statsThread = Thread({ statsLoop(this) }, "ss-stats").apply { isDaemon = true }
        var netCallback: ConnectivityManager.NetworkCallback? = null
    }

    /** Runs [block] under the lock [start] and [stop] take, and only while [r] is the current run. */
    private inline fun ifCurrent(r: Run, block: () -> Unit): Boolean =
        synchronized(this) { (current === r).also { if (it) block() } }

    /** [ifCurrent] for [r]'s decode thread, under [outputLock]. */
    private inline fun ifOutputCurrent(r: Run, block: () -> Unit): Boolean =
        synchronized(outputLock) { (current === r).also { if (it) block() } }

    /** The scheduler as [r]'s pipeline sees it: a decode thread still busy after [stop] can't end or feed the next run's stream. */
    private fun outputFor(r: Run) = object : PcmOutput {
        override fun start(f: SsFormat) { ifOutputCurrent(r) { scheduler.start(f) } }
        override fun push(c: PcmChunk) { ifOutputCurrent(r) { scheduler.push(c) } }
        override fun clear(fromUs: Long?) { ifOutputCurrent(r) { scheduler.clear(fromUs) } }
        override fun end() { ifOutputCurrent(r) { scheduler.end() } }
    }

    /** One-line state for the ESPHome diagnostic sensor. */
    fun statusText(): String {
        if (current == null) return "off"
        val s = status
        val server = s.serverName ?: "server"
        val note = flacFailure?.let { "; FLAC off, using PCM: $it" } ?: ""
        return (when (s.phase) {
            SendspinSession.Phase.CONNECTING, SendspinSession.Phase.HANDSHAKE -> "connecting"
            SendspinSession.Phase.CLOSED -> "disconnected" + (s.closeReason?.let { " ($it)" } ?: "")
            SendspinSession.Phase.HELD -> "waiting for pairing or approval by $server"
            SendspinSession.Phase.PAIRING -> "pairing with $server"
            SendspinSession.Phase.READY -> if (s.paired) "ready, paired with $server" else "ready, unpaired guest of $server"
            SendspinSession.Phase.PLAYING -> "playing from $server" + (s.codec?.let { " (${it.uppercase()})" } ?: "") + (nowPlaying?.let { ": $it" } ?: "")
        } + note).take(255)
    }

    @Synchronized fun start() {
        if (current != null) return
        started = true
        store.identity(); store.pairingPsk() // create both before HA asks for the token
        SsLog.i("Sendspin player starting (client id $clientId)")
        applyGain()
        scheduler.outputDelayUs = store.outputDelayMs * 1000L
        SsLog.i("FLAC decoder: ${flacDecoder ?: "none"}; offering ${offeredFormats().joinToString { it.codec }}")
        val r = Run()
        current = r
        r.audioThread.start(); r.connThread.start(); r.statsThread.start()
        // After a Wi-Fi drop, reconnect as soon as the network is back instead of waiting out the
        // back-off (which grows to 60 s): a wedged link left the player silent for a minute or more.
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        r.netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (session != null) return
                SsLog.i("Network is back: reconnecting now")
                r.backoffMs = 1_000L
                synchronized(wake) { wake.notifyAll() }
            }
        }.also { cb -> runCatching { cm?.registerDefaultNetworkCallback(cb) } }
    }

    /** Stops, telling the server why (`user_request` when switched off, `shutdown` when the app stops). */
    @Synchronized fun stop(reason: String = "user_request") {
        val r = current ?: return
        synchronized(outputLock) { current = null }
        r.netCallback?.let { cb -> runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        var interrupted = session?.let { closeOffThisThread(it, reason) } ?: false
        session = null
        synchronized(wake) { wake.notifyAll() }
        // The connection thread is not joined: it may sit in mDNS discovery or a socket read, and
        // once the run is no longer current it can't reach the next one.
        r.connThread.interrupt(); r.audioThread.interrupt(); r.statsThread.interrupt()
        r.pipeline.close()
        scheduler.end()
        // The audio thread owns the sink; let it close it before a restart could start another.
        try { r.audioThread.join(1_000) } catch (_: InterruptedException) { interrupted = true }
        status = SendspinSession.Status(SendspinSession.Phase.CLOSED)
        onStatusChanged()
        // Passed on only now, so neither wait above cuts the cleanup short.
        if (interrupted) Thread.currentThread().interrupt()
    }

    /**
     * Sends the goodbye and closes the socket on a thread of its own, and waits for it (at most
     * [CLOSE_WAIT_MS]). [stop] runs on the main thread when the app's service stops, and Android
     * refuses network I/O there. If the close is still running after the wait (a write that is
     * stuck), the connection is dropped so the socket doesn't outlive [stop]. Returns whether the
     * caller was interrupted while it waited.
     */
    private fun closeOffThisThread(s: SsConnection, reason: String): Boolean {
        val closer = Thread({ s.close(reason) }, "ss-close").apply { isDaemon = true; start() }
        var interrupted = false
        try {
            closer.join(CLOSE_WAIT_MS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
        if (closer.isAlive) s.abort()
        return interrupted
    }

    /** Reconnects so a new `client/hello` carries a changed setting (unpaired access, server URL). */
    fun restartSession() { session?.close("restart") }

    /** The `sendspin_flac` setting changed: switching it on also retries FLAC after a failure. */
    fun onFlacSettingChanged() {
        if (prefs.flac) { flacFailure = null; current?.pipeline?.resetFlacFailure() }
        onStatusChanged()
        restartSession()
    }

    /** FLAC kept failing in [r]: offer PCM only from the next client/hello, and reconnect [r]'s session now to get there. */
    private fun onFlacFailed(r: Run, reason: String) {
        var first = false
        ifOutputCurrent(r) { if (flacFailure == null) { flacFailure = reason.take(120); first = true } }
        if (!first) return
        SsLog.w("FLAC failed ($reason): reconnecting with PCM only")
        onStatusChanged()
        Thread({
            val s = synchronized(this@SendspinPlayer) { session.takeIf { current === r } }
            s?.close("restart")
        }, "ss-restart").apply { isDaemon = true; start() }
    }

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

    /** Exits once [r] is stopped: the stop may land anywhere, and the interrupt may not reach it. */
    private fun connectLoop(r: Run) {
        while (current === r) {
            val url = resolveUrl()
            if (url == null) {
                SsLog.w("No Sendspin server known yet (no mDNS answer, Home Assistant not found); retrying")
                if (!sleep(r, 10_000)) break
                continue
            }
            val s = connect(url, hello(), r.hooks)
            // stop() closes the session it finds; a session published after that must not run.
            if (!ifCurrent(r) { session = s }) return
            SsLog.i("Connecting to $url")
            val res = s.run()
            val stillCurrent = ifCurrent(r) {
                session = null
                status = s.status()
                if (res.established) lastUrl = url else if (url == lastUrl) lastUrl = null
            }
            if (!stillCurrent) return
            onStatusChanged()
            r.backoffMs = if (res.activated) 1_000L else minOf(r.backoffMs * 2, 60_000L)
            SsLog.i("Disconnected (${res.reason}); reconnecting in ${r.backoffMs / 1000} s")
            if (!sleep(r, r.backoffMs)) break
        }
    }

    private fun sleep(r: Run, ms: Long): Boolean {
        synchronized(wake) { if (current === r) try { wake.wait(ms) } catch (_: InterruptedException) { return false } }
        return current === r
    }

    private fun audioLoop(r: Run) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        try {
            while (current === r) {
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
    private fun statsLoop(r: Run) {
        var prev = scheduler.stats()
        var prevD = r.pipeline.stats()
        try {
            while (current === r) {
                Thread.sleep(10_000)
                val s = session ?: continue
                val st = scheduler.stats()
                val d = r.pipeline.stats(resetMax = true)
                if (scheduler.isActive) {
                    val frames = d.decodedFrames - prevD.decodedFrames
                    SsLog.i(
                        "decode: %s %.0f kbit/s on the wire | FLAC frames %d, decoder latency avg %.0f µs max %.1f ms, errors %d, dropped %d".format(
                            s.status().codec ?: "-", (d.wireBytes - prevD.wireBytes) * 8 / 10_000.0,
                            frames, if (frames > 0) (d.decodeNs - prevD.decodeNs) / 1000.0 / frames else 0.0,
                            d.maxDecodeNs / 1e6, d.decodeErrors - prevD.decodeErrors, d.droppedFrames - prevD.droppedFrames,
                        )
                    )
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
                prevD = d
            }
        } catch (_: InterruptedException) {}
    }

    // ------------------------------------------------------------------ server address

    private fun resolveUrl(): String? {
        val setting = prefs.server.trim()
        if (!setting.equals(SendspinPrefs.AUTO, ignoreCase = true) && setting.isNotEmpty()) return normalize(setting)
        lastUrl?.let { return it }
        discover()?.let { return it }
        val host = haBase()?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
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

    /**
     * The hooks of [r]'s sessions. Streams go to [r]'s own pipeline, so a stopped run's late call
     * lands in its closed pipeline. Status, metadata, volume, mute and output delay from a stopped
     * run are dropped.
     */
    private fun hooksFor(r: Run) = object : SsPlayerHooks {
        // Everything goes through the pipeline, in order: it decodes off the network and audio threads.
        override fun onStreamStart(format: SsFormat, codecHeader: ByteArray?) { r.pipeline.start(format, codecHeader) }
        override fun onStreamClear(fromTimestampUs: Long?) { r.pipeline.clear(fromTimestampUs) }
        override fun onStreamEnd() { r.pipeline.end(); ifCurrent(r) { nowPlaying = null } }
        override fun onAudio(chunk: SsAudioChunk, receivedNanos: Long) { r.pipeline.audio(chunk) }

        override fun onVolume(volume: Int) { if (ifCurrent(r) { store.volume = volume; applyGain() }) onStatusChanged() }
        override fun onMute(muted: Boolean) { if (ifCurrent(r) { store.muted = muted; applyGain() }) onStatusChanged() }
        override fun onOutputDelay(ms: Int) {
            if (ifCurrent(r) { store.outputDelayMs = ms; scheduler.outputDelayUs = ms * 1000L }) SsLog.i("Output delay set to $ms ms")
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
            var changed = false
            ifCurrent(r) { if (np != nowPlaying) { nowPlaying = np; changed = true } }
            if (changed) { SsLog.i("Now playing: ${np ?: "-"}"); onStatusChanged() }
        }

        override fun onStatus(status: SendspinSession.Status) {
            if (ifCurrent(r) { this@SendspinPlayer.status = status }) onStatusChanged()
        }
    }

    /** Local volume/mute changes (e.g. from Home Assistant) are reported to the server. */
    fun setVolume(volume: Int) { store.volume = volume.coerceIn(0, 100); applyGain(); session?.sendState() }
    fun setMuted(muted: Boolean) { store.muted = muted; applyGain(); session?.sendState() }
    val volume: Int get() = store.volume
    val muted: Boolean get() = store.muted
    /** Tests: whether the shared scheduler is playing a stream. */
    internal val outputActive: Boolean get() = scheduler.isActive

    companion object {
        /** The session waits up to 1 s for the server to hang up after the goodbye; allow for that. */
        private const val CLOSE_WAIT_MS = 1_500L
        const val DEFAULT_SERVER_PORT = 8927
        const val DEFAULT_PATH = "/sendspin"
        /**
         * Bytes of encoded chunks (+ headers) the server may have outstanding: ~7.8 s of PCM, ~15 s
         * of typical FLAC (decoded eagerly: ≤ ~3 MB of PCM in the jitter buffer).
         */
        const val BUFFER_CAPACITY = 1_500_000
        /** AudioTrack creation (~50 ms) + its 100 ms buffer + network, with margin. */
        const val REQUIRED_LEAD_TIME_MS = 300
        /** Wi-Fi jitter allowance for live streams. */
        const val MIN_BUFFER_MS = 200
        private val MAC_RE = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        private val EMPTY = TimeFilter.Snapshot(0.0, 0.0, 0, false, 0, Double.POSITIVE_INFINITY)
    }
}
