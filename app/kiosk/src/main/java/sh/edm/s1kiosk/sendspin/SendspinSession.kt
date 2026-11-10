package sh.edm.s1kiosk.sendspin

import sh.edm.s1kiosk.esphome.NoiseCipher
import sh.edm.s1kiosk.esphome.NoiseMacException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal object SsLog {
    private const val TAG = "S1Sendspin"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true
    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

/** Which PSK keyed the Noise session (spec "Pre-Shared Key"). */
enum class PskCategory(val code: String) {
    LONG_TERM("lt"), PAIRING("pr"), SENTINEL("sn");

    companion object { fun of(code: String) = entries.firstOrNull { it.code == code } }
}

/** The player side of a session: what the session hands to the audio pipeline and asks of it. */
interface SsPlayerHooks {
    /** `stream/start` (new stream or format update); [codecHeader] is the decoded `codec_header`, if any. */
    fun onStreamStart(format: SsFormat, codecHeader: ByteArray?)
    fun onStreamClear(fromTimestampUs: Long?)
    fun onStreamEnd()
    /** Called on the network thread for every audio chunk of the active stream. */
    fun onAudio(chunk: SsAudioChunk, receivedNanos: Long)
    fun onVolume(volume: Int)
    fun onMute(muted: Boolean)
    fun onOutputDelay(ms: Int)
    fun playerState(): SsPlayerState
    fun onMetadata(title: String?, artist: String?) {}
    fun onStatus(status: SendspinSession.Status) {}
}

/**
 * One client-initiated Sendspin connection (spec "Client Initiated Connections" → "Encryption" →
 * "Communication"), as implemented by aiosendspin 10.2.1 (the version Music Assistant 2.10.9 pins):
 *
 * 1. `client/init` → `server/init` → Noise `KKpsk2` (server = initiator, client = responder;
 *    prologue = the exact `client/init` + `server/init` bytes; PSK chosen by the `psk_id` /
 *    `psk_category` in message 1, Sentinel fallback on a miss).
 * 2. Encrypted `server/hello` → `client/hello` → `server/activate` (admission per the matched PSK
 *    and the `unpaired_access` setting).
 * 3. Clock sync bursts, `client/state` once converged, streams, commands, the Pairing PSK flow and
 *    in-band re-handshakes.
 *
 * [run] blocks until the connection ends. [close] may be called from any thread.
 */
class SendspinSession(
    private val url: String,
    private val store: SsStore,
    private val hello: SsHelloInfo,
    private val unpairedAccess: () -> Boolean,
    private val hooks: SsPlayerHooks,
    val timeFilter: TimeFilter = TimeFilter(),
    private val nowUs: () -> Long = { System.nanoTime() / 1000 },
) {
    enum class Phase { CONNECTING, HANDSHAKE, HELD, PAIRING, READY, PLAYING, CLOSED }

    data class Status(
        val phase: Phase,
        val serverName: String? = null,
        val serverId: String? = null,
        val paired: Boolean = false,
        val psk: PskCategory? = null,
        val activities: List<String> = emptyList(),
        val roles: List<String> = emptyList(),
        val groupName: String? = null,
        val closeReason: String? = null,
        /** Codec of the active stream. */
        val codec: String? = null,
    )

    class Result(val established: Boolean, val activated: Boolean, val reason: String)

    private val identity = store.identity()
    private val sendLock = Any()
    private val burstLock = Any()
    private var ws: WsClient? = null
    private var tx: NoiseCipher? = null
    private var rx: NoiseCipher? = null
    private val reasm = SsReassembler()
    @Volatile private var closing = false
    @Volatile private var goodbyeSent = false
    @Volatile private var readerThread: Thread? = null

    // Session state (network thread, read elsewhere via @Volatile).
    @Volatile var serverId: String? = null; private set
    @Volatile var serverName: String? = null; private set
    @Volatile var matched: PskCategory? = null; private set
    private var serverStatic: ByteArray? = null
    private var handshakeHash: ByteArray? = null
    @Volatile private var activated = false
    @Volatile private var quiet = false // re-handshake: no new application messages
    @Volatile var activities: List<String> = emptyList(); private set
    @Volatile var activeRoles: List<String> = emptyList(); private set
    @Volatile var streamActive = false; private set
    /** The latest `stream/start` format (kept after the stream ends, for the status). */
    @Volatile var streamFormat: SsFormat? = null; private set
    @Volatile private var stateSent = false
    @Volatile private var groupName: String? = null
    private var pairingIndex = 0
    private var pendingFinalize: ByteArray? = null
    private var closeReason = "closed"

    // Clock sync.
    private val burst = TimeSyncBurst(timeFilter)
    private var lastBurstStartUs = 0L

    val playerActive: Boolean get() = activeRoles.any { it.startsWith("player@") }
    private val playback: Boolean get() = "playback" in activities

    fun status(): Status {
        val phase = when {
            closing || (ws?.closed == true) -> Phase.CLOSED
            !activated -> if (ws == null) Phase.CONNECTING else Phase.HANDSHAKE
            "pairing" in activities -> Phase.PAIRING
            streamActive -> Phase.PLAYING
            playerActive -> Phase.READY
            else -> Phase.HELD
        }
        return Status(phase, serverName, serverId, matched == PskCategory.LONG_TERM, matched, activities, activeRoles, groupName, closeReason, streamFormat?.codec?.takeIf { streamActive })
    }

    private fun publish() = try { hooks.onStatus(status()) } catch (t: Throwable) { SsLog.w("status listener failed", t) }

    // ------------------------------------------------------------------ public API

    /** Connects, handshakes and processes messages until the connection ends. */
    fun run(): Result {
        var established = false
        readerThread = Thread.currentThread()
        val timer = Thread({ timerLoop() }, "ss-timer").apply { isDaemon = true }
        try {
            publish()
            val w = WsClient.connect(url)
            ws = w
            if (closing) { w.close(); return Result(false, false, "closed") }
            handshake(w)
            established = true
            exchangeHellos(w)
            timer.start()
            readLoop(w)
        } catch (e: SsProtocolException) {
            closeReason = "protocol error: ${e.message}"
            SsLog.w("Sendspin $closeReason")
        } catch (e: NoiseMacException) {
            closeReason = "Noise authentication failed"
            SsLog.w("Sendspin $closeReason")
        } catch (e: IOException) {
            if (!closing) closeReason = e.message ?: e.javaClass.simpleName
            if (!closing) SsLog.i("Sendspin connection to $url ended: $closeReason")
        } finally {
            closing = true
            timer.interrupt()
            if (streamActive) { streamActive = false; safe { hooks.onStreamEnd() } }
            ws?.close()
            publish()
        }
        return Result(established, activated, closeReason)
    }

    /** Ends the connection, sending `client/goodbye` with [reason] when the session is up. */
    fun close(reason: String?) {
        if (closing) return
        closing = true
        if (reason != null) closeReason = "goodbye: $reason"
        val w = ws
        if (reason != null && tx != null && !goodbyeSent) {
            goodbyeSent = true
            try { sendJson(SsMessages.goodbye(reason), force = true) } catch (_: Exception) {}
            // The server SHOULD initiate the disconnect after a goodbye: give it a moment (not on
            // the reader thread, which is what notices the close).
            if (w != null && Thread.currentThread() !== readerThread) {
                val deadline = System.nanoTime() + 1_000_000_000L
                while (!w.closed && System.nanoTime() < deadline) try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
        }
        w?.close()
    }

    /** Reports a local state change (volume/mute changed on the panel). */
    fun sendState() {
        if (activated && stateSent) sendPlayerState()
    }

    // ------------------------------------------------------------------ handshake

    private fun handshake(w: WsClient) {
        val initText = SsMessages.clientInit(identity.peerId)
        w.sendText(initText)
        val serverInit = w.receive() ?: throw IOException("closed before server/init")
        if (!serverInit.text) throw SsProtocolException("server/init not a text message")
        val serverInitText = String(serverInit.data, Charsets.UTF_8)
        val si = SsMessages.parse(serverInitText) ?: throw SsProtocolException("malformed server/init")
        if (si.type == "server/error") throw IOException("server rejected client/init: ${si.payload.optString("reason")}")
        if (si.type != "server/init") throw SsProtocolException("expected server/init, got ${si.type}")
        val version = si.payload.opt("version")
        if (version !is Int || version != 1) throw SsProtocolException("unsupported server version $version")
        val sid = si.payload.optString("server_id")
        val sKey = SsCrypto.peerKey(sid) ?: throw SsProtocolException("malformed server_id")
        serverId = sid
        serverStatic = sKey

        val prologue = initText.toByteArray(Charsets.UTF_8) + serverInit.data
        val hs = KKpsk2Responder(identity.privateKey, sKey, prologue)
        val m1 = w.receive() ?: throw IOException("closed before Noise message 1")
        if (!m1.text) throw SsProtocolException("Noise message 1 not a text message")
        val (psk, cat, mismatch) = readMessage1(hs, String(m1.data, Charsets.UTF_8), allowSentinelFallback = true)
        hs.setPsk(psk)
        w.sendText(SsMessages.noiseHandshake(hs.writeMessage2(SsMessages.MSG2_PAYLOAD)))
        val (r, t) = hs.split()
        rx = r
        tx = t
        handshakeHash = hs.handshakeHash
        matched = cat
        pairingIndex = 0
        if (cat == PskCategory.LONG_TERM) store.touchRecord(sid, System.currentTimeMillis())
        SsLog.i("Noise handshake with $sid done (${cat.name.lowercase()} PSK${if (mismatch) ", credential mismatch: unpaired until re-paired" else ""})")
    }

    private data class Resolved(val psk: ByteArray, val cat: PskCategory, val mismatch: Boolean)

    /** Decrypts message 1, resolves its `psk_id`; a miss falls back to the Sentinel only when allowed. */
    private fun readMessage1(hs: KKpsk2Responder, text: String, allowSentinelFallback: Boolean): Resolved {
        val msg = SsMessages.parse(text)
        if (msg == null || msg.type != "noise/handshake") throw SsProtocolException("expected noise/handshake")
        val data = try { SsCrypto.unb64url(msg.payload.getString("data")) } catch (e: Exception) { throw SsProtocolException("malformed handshake data") }
        val payload = hs.readMessage1(data)
        val p = SsMessages.parse("{\"type\":\"x\",\"payload\":" + String(payload, Charsets.UTF_8) + "}")?.payload
            ?: throw SsProtocolException("malformed Noise message 1 payload")
        val pskId = p.optString("psk_id")
        val cat = PskCategory.of(p.optString("psk_category")) ?: throw SsProtocolException("unknown psk_category")
        val found: ByteArray? = when (cat) {
            PskCategory.SENTINEL -> SsCrypto.SENTINEL_PSK.takeIf { pskId == SsCrypto.SENTINEL_PSK_ID }
            PskCategory.PAIRING -> store.pairingPsk().takeIf { SsCrypto.pskId(it) == pskId }
            PskCategory.LONG_TERM -> store.records().firstOrNull { it.pskId == pskId }?.let { rec ->
                // Stored-pubkey post-match check: a misbinding fails, it is not a miss.
                if (rec.serverId != serverId) throw SsProtocolException("PSK is bound to another server")
                rec.psk
            }
        }
        if (found != null) return Resolved(found, cat, false)
        if (!allowSentinelFallback) throw SsProtocolException("no PSK matches the re-handshake")
        return Resolved(SsCrypto.SENTINEL_PSK, PskCategory.SENTINEL, true)
    }

    private fun exchangeHellos(w: WsClient) {
        val sh = readJson(w) ?: throw IOException("closed before server/hello")
        if (sh.type != "server/hello") throw SsProtocolException("expected server/hello, got ${sh.type}")
        serverName = sh.payload.optString("name").take(64)
        SsLog.i("Server '$serverName' (${serverId})")
        sendJson(SsMessages.clientHello(hello, unpairedAccess()), force = true)
        val act = readJson(w) ?: throw IOException("closed before server/activate")
        if (act.type != "server/activate") throw SsProtocolException("expected server/activate, got ${act.type}")
        activated = true
        applyActivation(act.payload)
        publish()
    }

    /** Reads the next JSON message during the hello exchange (nothing else may arrive yet). */
    private fun readJson(w: WsClient): SsJson? {
        while (true) {
            val m = w.receive() ?: return null
            val plain = decrypt(m) ?: continue
            if (plain[0].toInt() != SsMessages.BIN_JSON) continue
            return SsMessages.parse(String(plain, 1, plain.size - 1, Charsets.UTF_8))
                ?: throw SsProtocolException("malformed JSON message")
        }
    }

    /** Decrypts one transport message and reassembles fragments; null while a fragment is incomplete. */
    private fun decrypt(m: WsClient.Message): ByteArray? {
        if (m.text) throw SsProtocolException("cleartext message in transport mode")
        val plain = rx!!.decrypt(ByteArray(0), m.data)
        return reasm.accept(plain)
    }

    // ------------------------------------------------------------------ steady state

    private fun readLoop(w: WsClient) {
        while (true) {
            val m = w.receive() ?: break
            val plain = decrypt(m) ?: continue
            if (closing) continue // goodbye sent: wait for the server to close
            when (plain[0].toInt() and 0xFF) {
                SsMessages.BIN_JSON -> {
                    val msg = SsMessages.parse(String(plain, 1, plain.size - 1, Charsets.UTF_8))
                        ?: throw SsProtocolException("malformed JSON message")
                    onJson(msg, m.receivedNanos)
                }
                SsMessages.BIN_AUDIO -> {
                    val c = SsMessages.parseAudio(plain) ?: throw SsProtocolException("audio message shorter than 13 bytes")
                    if (streamActive && playerActive && playback) hooks.onAudio(c, m.receivedNanos)
                }
                // 5-7 player (unused in v1), 8-23 artwork/source/visualizer (roles we don't have): discard.
                else -> Unit
            }
        }
        if (closing) return
        closeReason = "server closed the connection"
    }

    private fun onJson(msg: SsJson, receivedNanos: Long) {
        val p = msg.payload
        when (msg.type) {
            "server/time" -> onServerTime(p, receivedNanos / 1000)
            "server/activate" -> { applyActivation(p); publish() }
            "noise/handshake" -> rehandshake(msg)
            "stream/start" -> onStreamStart(p)
            "stream/clear" -> if (targets(p, "player") && streamActive) {
                val from = if (p.has("from_timestamp")) p.getLong("from_timestamp") else null
                hooks.onStreamClear(from)
            }
            "stream/end" -> if (targets(p, "player") && streamActive) {
                streamActive = false
                hooks.onStreamEnd()
                SsLog.i("Stream ended")
                publish()
            }
            "server/command" -> onServerCommand(p)
            "group/update" -> {
                val name = p.optString("group_name").takeIf { it.isNotEmpty() }
                if (name != groupName) { groupName = name; publish() }
                SsLog.d("group/update ${p.optString("playback_state")} '${p.optString("group_name")}'")
            }
            "server/state" -> if (activeRoles.any { it.startsWith("metadata@") }) {
                p.optJSONObject("metadata")?.let { md -> hooks.onMetadata(md.optStringOrNull("title"), md.optStringOrNull("artist")) }
            }
            "server/unpair" -> if (matched == PskCategory.LONG_TERM) {
                serverId?.let { store.removeRecord(it) }
                SsLog.i("Server unpaired this panel; record removed")
                close("unpaired")
            }
            "server/pair-finalize" -> pendingFinalize?.let { psk ->
                pendingFinalize = null
                val sid = serverId ?: return
                store.putRecord(SsPairingRecord(sid, psk, System.currentTimeMillis()), protectedServerIds = setOf(sid))
                SsLog.i("Paired with '$serverName' ($sid); waiting for the re-handshake")
            }
            "pair/abort" -> { pendingFinalize = null; SsLog.i("Pairing aborted by the server: ${p.optString("reason")}") }
            else -> SsLog.d("ignoring ${msg.type}")
        }
    }

    private fun targets(p: JSONObject, role: String): Boolean {
        val roles = p.optJSONArray("roles") ?: return true
        return (0 until roles.length()).any { roles.optString(it) == role }
    }

    private fun onStreamStart(p: JSONObject) {
        val pl = p.optJSONObject("player") ?: return
        if (!playerActive || !playback) return // inactive role / no playback activity: ignore unvalidated
        val f = SsFormat.fromJson(pl) ?: throw SsProtocolException("malformed stream/start player object")
        if (hello.formats.none { it == f }) throw SsProtocolException("stream/start format $f was not offered")
        val header = SsMessages.codecHeader(pl)
        val wasActive = streamActive
        val changed = f != streamFormat
        streamActive = true
        streamFormat = f
        hooks.onStreamStart(f, header)
        val what = "${f.codec} ${f.sampleRate} Hz ${f.channels} ch ${f.bitDepth}-bit" + (header?.let { " (codec_header ${it.size} bytes)" } ?: "")
        if (!wasActive) { SsLog.i("Stream started: $what"); publish() }
        else if (changed) { SsLog.i("Stream format changed: $what"); publish() }
    }

    private fun onServerCommand(p: JSONObject) {
        val c = p.optJSONObject("player") ?: return
        if (!playerActive) return
        val supported = hooks.playerState().supportedCommands
        when (val cmd = c.optString("command")) {
            "volume" -> if ("volume" in supported) {
                val v = c.optInt("volume", -1)
                if (v !in 0..100) throw SsProtocolException("volume out of range: $v")
                hooks.onVolume(v)
            }
            "mute" -> if ("mute" in supported) {
                if (!c.has("mute")) throw SsProtocolException("mute command without mute")
                hooks.onMute(c.getBoolean("mute"))
            }
            "set_output_delay" -> if ("set_output_delay" in supported) {
                val d = c.optInt("output_delay_ms", -1)
                if (d !in 0..5000) throw SsProtocolException("output_delay_ms out of range: $d")
                hooks.onOutputDelay(d)
            }
            else -> { SsLog.d("ignoring player command '$cmd'"); return }
        }
        sendPlayerState()
    }

    // ------------------------------------------------------------------ activation (spec "server/activate")

    private fun allowed(cat: PskCategory, acts: Set<String>, unpaired: Boolean): Boolean = when {
        cat == PskCategory.LONG_TERM -> acts.all { it == "playback" }
        unpaired -> acts.all { it == "playback" || it == "pairing" }
        else -> acts.all { it == "pairing" }
    }

    private fun admissible(cat: PskCategory, acts: Set<String>, hasRoles: Boolean, unpaired: Boolean) =
        allowed(cat, acts, unpaired) && (!hasRoles || allowed(cat, acts + "playback", unpaired))

    private fun applyActivation(p: JSONObject) {
        val cat = matched!!
        val actsArr = p.optJSONArray("activities") ?: JSONArray()
        val acts = (0 until actsArr.length()).map { actsArr.optString(it) }
        if (acts.any { it != "playback" && it != "pairing" }) {
            SsLog.w("server/activate with unknown activities $acts")
            close("unauthorized"); return
        }
        val actSet = acts.toSet()
        val unpaired = unpairedAccess()
        val rolesArr = p.optJSONArray("active_roles")
        // active_roles persists when omitted, unless the connection is no longer playback-capable.
        val effective = when {
            rolesArr != null -> (0 until rolesArr.length()).map { rolesArr.optString(it) }
            allowed(cat, actSet + "playback", unpaired) -> activeRoles
            else -> emptyList()
        }.filter { it == "player@v1" || (it == "metadata@v1" && hello.metadataRole) } // never use a role we didn't list
        val pairing = p.optJSONObject("pairing")
        if (!admissible(cat, actSet, effective.isNotEmpty(), unpaired)) {
            val reason = if (cat != PskCategory.LONG_TERM && !unpaired && admissible(cat, actSet, effective.isNotEmpty(), true) &&
                ("pairing" !in actSet || pairingAcceptable(cat, pairing))
            ) "pairing_required" else "unauthorized"
            SsLog.w("Rejecting server/activate $acts roles=$effective on a ${cat.name.lowercase()} session: $reason")
            close(reason)
            return
        }
        val hadPlayer = playerActive
        val hadPlayback = playback
        // A cancelling activation during pairing persists nothing.
        pendingFinalize = null
        activities = acts
        activeRoles = effective
        quiet = false // the re-handshake quiet period ends with this activation
        if ((hadPlayer && !playerActive) || (hadPlayback && !playback)) {
            if (streamActive) { streamActive = false; hooks.onStreamEnd() }
        }
        if (playback) serverId?.let { store.lastPlaybackServerId = it }
        SsLog.i("Activated: activities=$acts roles=$effective (${cat.name.lowercase()} PSK)")
        if ("pairing" in actSet) {
            pairingIndex++
            startPairing(cat, pairing)
        }
        if (playerActive) {
            // The player's state object is required after every activation that (re)adds it; resending is harmless.
            stateSent = false
            maybeSendInitialState()
        } else if (!hadPlayer) {
            stateSent = false
        }
        if (!hadPlayer && playerActive) lastBurstStartUs = 0 // make sure the clock is fresh
    }

    private fun pairingAcceptable(cat: PskCategory, pairing: JSONObject?): Boolean =
        pairing?.optString("method") == "pairing_psk" && cat == PskCategory.PAIRING

    /** Pairing PSK flow (spec "Pairing PSK Flow"): `client/pair-init` then `client/pair-finalize` back to back. */
    private fun startPairing(cat: PskCategory, pairing: JSONObject?) {
        val method = pairing?.optString("method")
        if (!pairingAcceptable(cat, pairing)) {
            SsLog.w("Pairing method '$method' on a ${cat.name.lowercase()} session is not supported")
            sendJson(SsMessages.pairAbort("method_not_supported"), force = true)
            return
        }
        var psk: ByteArray
        val pairingPsk = store.pairingPsk()
        do { psk = SsCrypto.randomBytes(32) } while (psk.contentEquals(SsCrypto.SENTINEL_PSK) || psk.contentEquals(pairingPsk))
        pendingFinalize = psk
        SsLog.i("Pairing with '$serverName' (pairing PSK flow, index $pairingIndex)")
        sendJson(SsMessages.pairInit(pairingIndex), force = true)
        sendJson(SsMessages.pairFinalize(psk), force = true)
    }

    /** In-band re-handshake (spec "Re-handshake"): prologue = previous `h`, msg 2 under the old keys. */
    private fun rehandshake(msg: SsJson) {
        quiet = true
        synchronized(burstLock) { burst.abort() }
        val hs = KKpsk2Responder(identity.privateKey, serverStatic!!, handshakeHash!!)
        val wire = SsMessages.envelope("noise/handshake", msg.payload)
        val (psk, cat, _) = readMessage1(hs, wire, allowSentinelFallback = false)
        hs.setPsk(psk)
        val m2 = SsMessages.noiseHandshake(hs.writeMessage2(SsMessages.MSG2_PAYLOAD))
        synchronized(sendLock) {
            sendJsonLocked(m2)
            val (r, t) = hs.split()
            tx = t
            rx = r
        }
        handshakeHash = hs.handshakeHash
        matched = cat
        pairingIndex = 0
        if (cat == PskCategory.LONG_TERM) serverId?.let { store.touchRecord(it, System.currentTimeMillis()) }
        SsLog.i("Re-handshake done (${cat.name.lowercase()} PSK)")
    }

    // ------------------------------------------------------------------ clock sync + state

    private fun onServerTime(p: JSONObject, t4: Long) {
        val t1 = p.optLong("client_transmitted")
        val t2 = p.optLong("server_received")
        val t3 = p.optLong("server_transmitted")
        val next = synchronized(burstLock) { burst.onReply(t1, t2, t3, t4, nowUs()) }
        if (next != null) sendTime(next)
        else maybeSendInitialState()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun sendTime(t1Hint: Long) {
        // T1 is taken right before encryption; the burst tracks the value actually sent.
        synchronized(sendLock) {
            if (quiet || closing) { synchronized(burstLock) { burst.abort() }; return }
            val t1 = nowUs()
            synchronized(burstLock) { burst.markSent(t1) }
            sendJsonLocked(SsMessages.clientTime(t1))
        }
    }

    /** Burst scheduler: every 1 s for the first 10 bursts, then every 5 s (spec: ≤ 10 s apart). */
    private fun timerLoop() {
        try {
            while (!closing) {
                Thread.sleep(100)
                if (!activated || quiet) continue
                val now = nowUs()
                val t1 = synchronized(burstLock) {
                    burst.checkTimeout(now)
                    val interval = if (burst.bursts < 10) 1_000_000L else 5_000_000L
                    if (!burst.isActive && now - lastBurstStartUs >= interval) { lastBurstStartUs = now; burst.start(now) } else null
                }
                if (t1 != null) sendTime(t1)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (!closing) SsLog.w("clock sync timer failed", e)
        }
    }

    /** `client/state` with the player object, but only once the time filter has converged. */
    private fun maybeSendInitialState() {
        if (stateSent || !playerActive || !timeFilter.snapshot.converged || quiet) return
        sendPlayerState()
    }

    private fun sendPlayerState() {
        if (!playerActive || quiet || closing) return
        try {
            sendJson(SsMessages.clientState(true, hooks.playerState()))
            if (!stateSent) SsLog.i("client/state sent (clock offset ${"%.1f".format(timeFilter.snapshot.offset / 1000)} ms)")
            stateSent = true
        } catch (e: IOException) {
            SsLog.w("client/state send failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ transport

    private fun sendJson(json: String, force: Boolean = false) {
        synchronized(sendLock) {
            if (quiet && !force) return
            sendJsonLocked(json)
        }
    }

    private fun sendJsonLocked(json: String) {
        val w = ws ?: throw IOException("not connected")
        val body = json.toByteArray(Charsets.UTF_8)
        val plain = ByteArray(1 + body.size)
        System.arraycopy(body, 0, plain, 1, body.size)
        if (plain.size > 65535 - 16) throw IOException("message too large to send unfragmented")
        w.sendBinary(tx!!.encrypt(ByteArray(0), plain))
    }

    private inline fun safe(block: () -> Unit) = try { block() } catch (t: Throwable) { SsLog.w("hook failed", t) }

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
}
