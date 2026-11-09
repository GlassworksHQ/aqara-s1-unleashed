package io.github.aguilaair.s1kiosk.esphome

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Tiny log facade: android.util.Log on the device, stdout in JVM unit tests. */
internal object ApiLog {
    private const val TAG = "S1ESPHome"
    private val onAndroid = System.getProperty("java.vendor")?.contains("Android") == true

    fun d(msg: String) = if (onAndroid) { android.util.Log.d(TAG, msg); Unit } else println("D/$TAG: $msg")
    fun i(msg: String) = if (onAndroid) { android.util.Log.i(TAG, msg); Unit } else println("I/$TAG: $msg")
    fun w(msg: String, t: Throwable? = null) =
        if (onAndroid) { android.util.Log.w(TAG, msg, t); Unit } else println("W/$TAG: $msg ${t ?: ""}")
}

/** What `DeviceInfoResponse` / `HelloResponse` / mDNS report. */
data class DeviceIdentity(
    /** ESPHome node name (hostname-style), e.g. `s1-panel`. */
    val name: String,
    val friendlyName: String,
    /** `AA:BB:CC:DD:EE:FF`; Home Assistant's config entry unique id is derived from it. */
    val macAddress: String,
    val esphomeVersion: String,
    val compilationTime: String,
    val manufacturer: String,
    val model: String,
    val voiceFeatures: Int,
) {
    /** Lowercase hex without separators, as in ESPHome's mDNS `mac` TXT and Noise ServerHello. */
    val macHex: String get() = macAddress.replace(":", "").lowercase()
}

data class WakeWordInfo(val id: String, val wakeWord: String, val trainedLanguages: List<String> = listOf("en"))

data class VoiceConfiguration(val available: List<WakeWordInfo>, val active: List<String>, val maxActive: Int)

/** Voice assistant messages from Home Assistant (called on the connection's reader thread). */
interface VoiceAssistantHandler {
    fun onSubscribe(subscribed: Boolean)
    fun onStartResponse(port: Int, error: Boolean)
    fun onEvent(type: Int, data: Map<String, String>)
    fun onTimer(type: Int, id: String, name: String, totalSeconds: Int, secondsLeft: Int, active: Boolean)
    fun onAnnounce(mediaId: String, text: String, preannounceMediaId: String, startConversation: Boolean)
    fun configuration(): VoiceConfiguration
    fun setActiveWakeWords(ids: List<String>)
}

/**
 * ESPHome native API server (TCP, default port 6053). One reader thread per connection; writes are
 * serialized per connection. Message handling follows esphome `api_connection.cpp`: Hello,
 * DeviceInfo, DeviceCapabilities, ListEntities, SubscribeStates, commands, Ping, Disconnect and the
 * voice assistant messages. Unknown messages are ignored, as ESPHome does.
 *
 * @param psk 32-byte Noise PSK, or null for plaintext.
 */
class ApiServer(
    private val port: Int,
    private val identity: () -> DeviceIdentity,
    entities: List<ApiEntity>,
    private val psk: ByteArray?,
) {
    companion object {
        const val DEFAULT_PORT = 6053
        const val API_MAJOR = 1
        /** ESPHome 2026.10/11 reports 1.20 (api_connection.cpp); we implement DeviceCapabilities (1.15+). */
        const val API_MINOR = 20
        private const val READ_TIMEOUT_MS = 90_000 // aioesphomeapi pings every 20 s
    }

    @Volatile var voice: VoiceAssistantHandler? = null

    /** Called after a command message was handled (state may have changed). */
    @Volatile var onCommand: (() -> Unit)? = null

    // Replaced as a whole by setEntities (the wall base adds/removes entities at runtime).
    @Volatile private var entities: List<ApiEntity> = entities.toList()
    @Volatile private var byKey: Map<Int, ApiEntity> = entities.associateBy { it.key }
    private val connections = CopyOnWriteArrayList<Connection>()
    private val ids = AtomicInteger()
    private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var voiceConn: Connection? = null

    private val stateLock = Any()
    private val lastStates = HashMap<Int, ByteArray>()

    val localPort: Int get() = serverSocket?.localPort ?: -1
    val hasVoiceClient: Boolean get() = voiceConn?.isOpen == true
    val connectionCount: Int get() = connections.size

    init {
        require(byKey.size == entities.size) { "duplicate entity keys" }
    }

    /**
     * Replaces the entity set and asks every connected client to disconnect (`DisconnectRequest`,
     * socket closed after the ack or 2 s). Home Assistant reconnects and lists the entities again,
     * which is how it picks up added ones, like after an ESPHome firmware update.
     */
    fun setEntities(newEntities: List<ApiEntity>) {
        val list = newEntities.toList()
        val map = list.associateBy { it.key }
        require(map.size == list.size) { "duplicate entity keys" }
        synchronized(stateLock) {
            entities = list
            byKey = map
            lastStates.keys.retainAll(map.keys)
        }
        ApiLog.i("Entity set changed (${list.size} entities); asking ${connections.size} client(s) to reconnect")
        for (c in connections) c.requestDisconnect()
    }

    /** Fires an event entity on every subscribed connection (no-op if it isn't in the current set). */
    fun sendEvent(entity: EventEntity, type: String): Boolean {
        if (byKey[entity.key] !== entity) return false
        if (type !in entity.eventTypes) {
            ApiLog.w("event type '$type' not declared by ${entity.objectId}")
            return false
        }
        val frame = Frame(Msg.EVENT_RESPONSE, entity.event(type).toByteArray())
        var sent = false
        synchronized(stateLock) {
            for (c in connections) if (c.subscribedStates) sent = c.send(listOf(frame)) || sent
        }
        return sent
    }

    @Throws(IOException::class)
    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        running = true
        Thread({ acceptLoop(ss) }, "esphome-accept").apply { isDaemon = true; start() }
        ApiLog.i("API server listening on ${ss.localPort} (${if (psk != null) "Noise" else "plaintext"})")
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        connections.forEach { it.close() }
        connections.clear()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val s = try { ss.accept() } catch (e: IOException) {
                if (running) ApiLog.w("accept failed", e)
                break
            }
            val c = Connection(ids.incrementAndGet(), s)
            connections += c
            Thread({ c.run() }, "esphome-conn-${c.id}").apply { isDaemon = true; start() }
        }
    }

    /** Sends the states that changed since the last call to every subscribed connection. */
    fun publishStates() {
        // Sent under the lock so a connection that is subscribing can't get updates out of order.
        synchronized(stateLock) {
            val changed = ArrayList<Frame>()
            for (e in entities) {
                if (e is EventEntity) continue
                val st = safeState(e) ?: continue
                val prev = lastStates[e.key]
                if (prev == null || !prev.contentEquals(st)) {
                    lastStates[e.key] = st
                    changed += Frame(e.stateType, st)
                }
            }
            if (changed.isEmpty()) return
            for (c in connections) if (c.subscribedStates) c.send(changed)
        }
    }

    private fun safeState(e: ApiEntity): ByteArray? = try {
        e.state()?.toByteArray()
    } catch (t: Throwable) {
        ApiLog.w("state of ${e.objectId} failed", t)
        null
    }

    /** Sends a voice assistant message to the subscribed Home Assistant connection. */
    fun sendVoice(type: Int, payload: ProtoWriter): Boolean {
        val c = voiceConn ?: return false
        return c.send(listOf(Frame(type, payload.toByteArray())))
    }

    private inner class Connection(val id: Int, private val socket: Socket) {
        private val writeLock = Any()
        private lateinit var transport: FrameTransport
        @Volatile var subscribedStates = false
        @Volatile var isOpen = true
        private val remote = socket.remoteSocketAddress?.toString() ?: "?"

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
                val output = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
                val ident = identity()
                transport = if (psk != null) NoiseTransport(input, output, psk, ident.name, ident.macHex)
                else PlaintextTransport(input, output)
                if (!transport.handshake()) {
                    ApiLog.w("[$id] handshake with $remote failed")
                    return
                }
                while (isOpen) {
                    val f = transport.read() ?: break
                    handle(f)
                }
            } catch (e: SocketTimeoutException) {
                ApiLog.w("[$id] $remote idle for ${READ_TIMEOUT_MS / 1000} s; closing")
            } catch (e: SocketException) {
                if (isOpen) ApiLog.i("[$id] $remote: ${e.message}")
            } catch (e: IOException) {
                if (isOpen) ApiLog.w("[$id] $remote: ${e.message}")
            } catch (e: Exception) {
                ApiLog.w("[$id] $remote: handler error", e)
            } finally {
                close()
            }
        }

        fun send(frames: List<Frame>): Boolean {
            if (!isOpen || !::transport.isInitialized) return false
            return try {
                synchronized(writeLock) { transport.write(frames) }
                true
            } catch (e: IOException) {
                ApiLog.w("[$id] write failed: ${e.message}")
                close()
                false
            }
        }

        private fun reply(type: Int, w: ProtoWriter = ProtoWriter()) = send(listOf(Frame(type, w.toByteArray())))

        /** Server-initiated disconnect (entity set changed): ask, then close after the ack or 2 s. */
        fun requestDisconnect() {
            subscribedStates = false
            if (!reply(Msg.DISCONNECT_REQUEST)) { close(); return }
            Thread({
                try { Thread.sleep(2_000) } catch (_: InterruptedException) {}
                close()
            }, "esphome-disc-$id").apply { isDaemon = true; start() }
        }

        fun close() {
            if (!isOpen) return
            isOpen = false
            try { socket.close() } catch (_: IOException) {}
            connections -= this
            if (voiceConn === this) {
                voiceConn = null
                voice?.onSubscribe(false)
            }
            ApiLog.i("[$id] $remote disconnected")
        }

        private fun handle(f: Frame) {
            val m = ProtoMessage(f.data)
            when (f.type) {
                Msg.HELLO_REQUEST -> {
                    ApiLog.i("[$id] hello from '${m.string(1)}' API ${m.uint32(2)}.${m.uint32(3)} ($remote)")
                    val ident = identity()
                    reply(
                        Msg.HELLO_RESPONSE,
                        ProtoWriter().uint32(1, API_MAJOR).uint32(2, API_MINOR)
                            .string(3, ident.esphomeVersion, force = true).string(4, ident.name, force = true),
                    )
                }
                Msg.AUTHENTICATION_REQUEST -> Unit // password auth was removed in ESPHome 2026.1; no reply
                Msg.DISCONNECT_REQUEST -> { reply(Msg.DISCONNECT_RESPONSE); close() }
                Msg.DISCONNECT_RESPONSE -> close()
                Msg.PING_REQUEST -> reply(Msg.PING_RESPONSE)
                Msg.PING_RESPONSE, Msg.GET_TIME_RESPONSE -> Unit
                Msg.DEVICE_INFO_REQUEST -> reply(Msg.DEVICE_INFO_RESPONSE, deviceInfo())
                Msg.DEVICE_CAPABILITIES_REQUEST -> reply(
                    Msg.DEVICE_CAPABILITIES_RESPONSE,
                    // voice_assistant = 2 { feature_flags = 1 }
                    ProtoWriter().message(2, ProtoWriter().uint32(1, identity().voiceFeatures)),
                )
                Msg.LIST_ENTITIES_REQUEST -> {
                    val frames = entities.map { Frame(it.listType, it.info().toByteArray()) } +
                        Frame(Msg.LIST_ENTITIES_DONE, ByteArray(0))
                    send(frames)
                }
                Msg.SUBSCRIBE_STATES_REQUEST -> synchronized(stateLock) {
                    // Current state of everything (and refresh the cache, so other connections get
                    // whatever changed on the next publish).
                    val frames = entities.mapNotNull { e ->
                        if (e is EventEntity) return@mapNotNull null
                        val st = safeState(e) ?: return@mapNotNull null
                        val prev = lastStates[e.key]
                        lastStates[e.key] = st
                        if (prev != null && !prev.contentEquals(st)) {
                            connections.filter { it !== this && it.subscribedStates }
                                .forEach { it.send(listOf(Frame(e.stateType, st))) }
                        }
                        Frame(e.stateType, st)
                    }
                    send(frames)
                    subscribedStates = true
                }
                Msg.LIGHT_COMMAND, Msg.SWITCH_COMMAND, Msg.NUMBER_COMMAND, Msg.SELECT_COMMAND,
                Msg.BUTTON_COMMAND, Msg.TEXT_COMMAND, Msg.MEDIA_PLAYER_COMMAND -> {
                    val e = byKey[m.fixed32(1)]
                    if (e == null || e.commandType != f.type) {
                        ApiLog.w("[$id] command type ${f.type} for unknown key ${m.fixed32(1)}")
                    } else {
                        ApiLog.i("[$id] command for ${e.objectId}")
                        try { e.command(m) } catch (t: Throwable) { ApiLog.w("command ${e.objectId} failed", t) }
                        onCommand?.invoke()
                    }
                }
                else -> handleVoice(f.type, m)
            }
        }

        private fun handleVoice(type: Int, m: ProtoMessage) {
            val va = voice
            when (type) {
                Msg.SUBSCRIBE_VOICE_ASSISTANT_REQUEST -> {
                    // The flags aren't interpreted: like esphome voice_assistant.cpp, the audio
                    // transport follows VoiceAssistantResponse.port (0 = API audio). (aioesphomeapi
                    // sends 4 for API audio while api.proto's enum says 1.)
                    val subscribe = m.bool(1)
                    ApiLog.i("[$id] voice assistant subscribe=$subscribe flags=${m.uint32(2)}")
                    if (subscribe) {
                        voiceConn = this
                        va?.onSubscribe(true)
                    } else if (voiceConn === this) {
                        voiceConn = null
                        va?.onSubscribe(false)
                    }
                }
                Msg.VOICE_ASSISTANT_RESPONSE -> va?.onStartResponse(m.uint32(1), m.bool(2))
                Msg.VOICE_ASSISTANT_EVENT_RESPONSE -> {
                    val data = LinkedHashMap<String, String>()
                    for (d in m.messages(2)) data[d.string(1)] = d.string(2)
                    va?.onEvent(m.uint32(1), data)
                }
                Msg.VOICE_ASSISTANT_AUDIO -> Unit // TTS audio is only sent to SPEAKER devices; we play URLs
                Msg.VOICE_ASSISTANT_TIMER_EVENT -> va?.onTimer(
                    m.uint32(1), m.string(2), m.string(3), m.uint32(4), m.uint32(5), m.bool(6),
                )
                Msg.VOICE_ASSISTANT_ANNOUNCE_REQUEST -> {
                    if (va == null) {
                        reply(Msg.VOICE_ASSISTANT_ANNOUNCE_FINISHED, ProtoWriter().bool(1, false))
                    } else {
                        va.onAnnounce(m.string(1), m.string(2), m.string(3), m.bool(4))
                    }
                }
                Msg.VOICE_ASSISTANT_CONFIGURATION_REQUEST -> {
                    val cfg = va?.configuration() ?: VoiceConfiguration(emptyList(), emptyList(), 0)
                    val w = ProtoWriter()
                    for (ww in cfg.available) {
                        w.message(1, ProtoWriter().string(1, ww.id).string(2, ww.wakeWord).strings(3, ww.trainedLanguages))
                    }
                    w.strings(2, cfg.active).uint32(3, cfg.maxActive)
                    reply(Msg.VOICE_ASSISTANT_CONFIGURATION_RESPONSE, w)
                }
                Msg.VOICE_ASSISTANT_SET_CONFIGURATION -> va?.setActiveWakeWords(m.strings(1))
                else -> ApiLog.d("[$id] ignoring message type $type")
            }
        }

        private fun deviceInfo(): ProtoWriter {
            val d = identity()
            return ProtoWriter()
                .string(2, d.name, force = true)
                .string(3, d.macAddress, force = true)
                .string(4, d.esphomeVersion, force = true)
                .string(5, d.compilationTime, force = true)
                .string(6, d.model, force = true)
                .string(12, d.manufacturer, force = true)
                .string(13, d.friendlyName, force = true)
                .uint32(17, d.voiceFeatures)
        }
    }
}
