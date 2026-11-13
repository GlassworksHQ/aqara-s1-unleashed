package sh.edm.esphome

/** `EntityCategory` (api.proto). */
enum class EntityCategory(val value: Int) { NONE(0), CONFIG(1), DIAGNOSTIC(2) }

/**
 * One entity exposed over the native API. Field numbers for each `ListEntities*Response`,
 * `*StateResponse` and `*CommandRequest` come from aioesphomeapi `api.proto`.
 *
 * Home Assistant names entities `<device name> <entity name>` (has_entity_name). It makes an entity id
 * once, when it first sees the entity: up to HA 2025.11 from the node name (`s1-panel` + "Screen" →
 * `light.s1_panel_screen`), from 2025.12 from the device's name as HA shows it ("Glassworks Panel" +
 * "Screen" → `light.glassworks_panel_screen`; 2026.8+ may put the area first). Renaming the device
 * later never changes it. The unique id is
 * `<mac>/<device_id>/<domain>/<name>` (aioesphomeapi `build_unique_id` v3); `object_id` is the
 * ESPHome-style snake_case name, and the key is FNV-1a of `object_id` so it never changes.
 *
 * Getters/setters run on API connection threads: implementations must be thread-safe.
 */
abstract class ApiEntity(
    val objectId: String,
    val name: String,
    val icon: String = "",
    val category: EntityCategory = EntityCategory.NONE,
    val disabledByDefault: Boolean = false,
) {
    val key: Int = fnv1a(objectId)

    /**
     * Commands only from an authenticated connection (Noise with the provisioned key): for actions that
     * must not come from anyone on the LAN while the panel is unprovisioned (plaintext or zero-PSK API),
     * e.g. flashing the radio.
     */
    var authOnly: Boolean = false

    abstract val listType: Int
    open val stateType: Int = 0
    open val commandType: Int = 0

    abstract fun info(): ProtoWriter
    open fun state(): ProtoWriter? = null
    open fun command(msg: ProtoMessage) {}

    protected fun base() = ProtoWriter().string(1, objectId, force = true).fixed32(2, key, force = true)
        .string(3, name, force = true)

    companion object {
        fun fnv1a(s: String): Int {
            var h = 0x811C9DC5.toInt()
            for (b in s.toByteArray(Charsets.UTF_8)) {
                h = h xor (b.toInt() and 0xFF)
                h *= 0x01000193
            }
            return h
        }
    }
}

class BinarySensorEntity(
    objectId: String, name: String, icon: String = "", private val deviceClass: String = "",
    category: EntityCategory = EntityCategory.NONE,
    private val get: () -> Boolean?,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_BINARY_SENSOR
    override val stateType = Msg.BINARY_SENSOR_STATE
    override fun info() = base().string(5, deviceClass).bool(7, disabledByDefault).string(8, icon)
        .uint32(9, category.value)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).bool(2, v == true).bool(3, v == null)
    }
}

class SensorEntity(
    objectId: String, name: String, icon: String = "",
    private val unit: String = "", private val accuracy: Int = 0, private val deviceClass: String = "",
    /** `SensorStateClass`: 0 none, 1 measurement, 2 total_increasing, 3 total. */
    private val stateClass: Int = 1,
    category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> Float?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType = Msg.LIST_ENTITIES_SENSOR
    override val stateType = Msg.SENSOR_STATE
    override fun info() = base().string(5, icon).string(6, unit).int32(7, accuracy).string(9, deviceClass)
        .uint32(10, stateClass).bool(12, disabledByDefault).uint32(13, category.value)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { !it.isNaN() }
        return ProtoWriter().fixed32(1, key, true).float(2, v ?: 0f).bool(3, v == null)
    }
}

class TextSensorEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    disabledByDefault: Boolean = false,
    private val get: () -> String?,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType = Msg.LIST_ENTITIES_TEXT_SENSOR
    override val stateType = Msg.TEXT_SENSOR_STATE
    override fun info() = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
}

class SwitchEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    private val deviceClass: String = "",
    disabledByDefault: Boolean = false,
    private val get: () -> Boolean,
    private val set: (Boolean) -> Unit,
) : ApiEntity(objectId, name, icon, category, disabledByDefault) {
    override val listType = Msg.LIST_ENTITIES_SWITCH
    override val stateType = Msg.SWITCH_STATE
    override val commandType = Msg.SWITCH_COMMAND
    override fun info() = base().string(5, icon).bool(7, disabledByDefault).uint32(8, category.value)
        .string(9, deviceClass)
    override fun state() = ProtoWriter().fixed32(1, key, true).bool(2, get())
    override fun command(msg: ProtoMessage) = set(msg.bool(2))
}

class NumberEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    private val min: Float, private val max: Float, private val step: Float,
    private val unit: String = "",
    /** `NumberMode`: 0 auto, 1 box, 2 slider. */
    private val mode: Int = 0,
    private val deviceClass: String = "",
    private val get: () -> Float?,
    private val set: (Float) -> Unit,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_NUMBER
    override val stateType = Msg.NUMBER_STATE
    override val commandType = Msg.NUMBER_COMMAND
    override fun info() = base().string(5, icon).float(6, min).float(7, max).float(8, step)
        .bool(9, disabledByDefault).uint32(10, category.value).string(11, unit).uint32(12, mode)
        .string(13, deviceClass)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { !it.isNaN() }
        return ProtoWriter().fixed32(1, key, true).float(2, v ?: 0f).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage) = set(msg.float(2).coerceIn(min, max))
}

class SelectEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    private val options: List<String>,
    private val get: () -> String?,
    private val set: (String) -> Unit,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_SELECT
    override val stateType = Msg.SELECT_STATE
    override val commandType = Msg.SELECT_COMMAND
    override fun info() = base().string(5, icon).strings(6, options).bool(7, disabledByDefault)
        .uint32(8, category.value)
    override fun state(): ProtoWriter {
        val v = get()?.takeIf { it in options }
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage) {
        val v = msg.string(2)
        if (v in options) set(v)
    }
}

/** [mode]: ESPHome TextMode, 0 = text, 1 = password (HA masks the field). */
class TextEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    private val minLength: Int = 0, private val maxLength: Int = 255, private val pattern: String = "",
    private val mode: Int = 0,
    private val get: () -> String?,
    private val set: (String) -> Unit,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_TEXT
    override val stateType = Msg.TEXT_STATE
    override val commandType = Msg.TEXT_COMMAND
    override fun info() = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .uint32(8, minLength).uint32(9, maxLength).string(10, pattern).uint32(11, mode)
    override fun state(): ProtoWriter {
        val v = get()
        return ProtoWriter().fixed32(1, key, true).string(2, v).bool(3, v == null)
    }
    override fun command(msg: ProtoMessage) {
        val v = msg.string(2)
        if (v.length in minLength..maxLength && (pattern.isEmpty() || Regex(pattern).matches(v))) set(v)
    }
}

class ButtonEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    private val deviceClass: String = "",
    private val press: () -> Unit,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_BUTTON
    override val commandType = Msg.BUTTON_COMMAND
    override fun info() = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass)
    override fun command(msg: ProtoMessage) = press()
}

/**
 * An event entity (`ListEntitiesEventResponse` 107 / `EventResponse` 108). Events have no state:
 * nothing is sent on subscribe; [ApiServer.sendEvent] pushes one `EventResponse` per occurrence and
 * Home Assistant fires `event_type` (which must be one of [eventTypes]).
 */
class EventEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.NONE,
    private val deviceClass: String = "",
    val eventTypes: List<String>,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_EVENT
    override val stateType = Msg.EVENT_RESPONSE
    override fun info() = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass).strings(9, eventTypes)
    fun event(type: String): ProtoWriter = ProtoWriter().fixed32(1, key, true).string(2, type)
}

/** `UpdateStateResponse` fields (api.proto 117); [progress] is a percentage (0..100). */
data class UpdateStateData(
    val missingState: Boolean,
    val inProgress: Boolean,
    val hasProgress: Boolean,
    val progress: Float,
    val currentVersion: String,
    val latestVersion: String,
    val title: String,
    val releaseSummary: String,
    val releaseUrl: String,
)

/**
 * An update entity (`ListEntitiesUpdateResponse` 116 / `UpdateStateResponse` 117 /
 * `UpdateCommandRequest` 118). HA's ESPHome update platform offers install, progress and the
 * release summary/URL; [command] gets the `UpdateCommand` (1 install, 2 check).
 */
class UpdateEntity(
    objectId: String, name: String, icon: String = "", category: EntityCategory = EntityCategory.CONFIG,
    private val deviceClass: String = "firmware",
    private val get: () -> UpdateStateData,
    private val onCommand: (Int) -> Unit,
) : ApiEntity(objectId, name, icon, category) {
    override val listType = Msg.LIST_ENTITIES_UPDATE
    override val stateType = Msg.UPDATE_STATE
    override val commandType = Msg.UPDATE_COMMAND
    override fun info() = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
        .string(8, deviceClass)
    override fun state(): ProtoWriter {
        val s = get()
        return ProtoWriter().fixed32(1, key, true).bool(2, s.missingState).bool(3, s.inProgress)
            .bool(4, s.hasProgress).float(5, s.progress).string(6, s.currentVersion).string(7, s.latestVersion)
            .string(8, s.title).string(9, s.releaseSummary).string(10, s.releaseUrl)
    }
    override fun command(msg: ProtoMessage) = onCommand(msg.uint32(2))
}

/** A light with on/off + brightness only (`COLOR_MODE_BRIGHTNESS` = 3). */
class BrightnessLightEntity(
    objectId: String, name: String, icon: String = "",
    private val get: () -> Pair<Boolean, Float>,
    /** (state or null if unchanged, brightness 0..1 or null if unchanged). */
    private val set: (Boolean?, Float?) -> Unit,
) : ApiEntity(objectId, name, icon) {
    override val listType = Msg.LIST_ENTITIES_LIGHT
    override val stateType = Msg.LIGHT_STATE
    override val commandType = Msg.LIGHT_COMMAND
    override fun info() = base().uint32(12, COLOR_MODE_BRIGHTNESS).bool(13, disabledByDefault)
        .string(14, icon).uint32(15, category.value)
    override fun state(): ProtoWriter {
        val (on, b) = get()
        // Round so tiny auto-brightness changes don't spam Home Assistant.
        val rounded = Math.round(b.coerceIn(0f, 1f) * 100) / 100f
        return ProtoWriter().fixed32(1, key, true).bool(2, on).float(3, rounded)
            .uint32(11, COLOR_MODE_BRIGHTNESS)
    }
    override fun command(msg: ProtoMessage) {
        set(if (msg.bool(2)) msg.bool(3) else null, if (msg.bool(4)) msg.float(5).coerceIn(0f, 1f) else null)
    }

    companion object { const val COLOR_MODE_BRIGHTNESS = 3 }
}

/** `MediaPlayerState` (api.proto). */
object MediaState {
    const val IDLE = 1
    const val PLAYING = 2
    const val PAUSED = 3
    const val ANNOUNCING = 4
}

/** `MediaPlayerCommand` (api.proto). */
object MediaCommand {
    const val PLAY = 0
    const val PAUSE = 1
    const val STOP = 2
    const val MUTE = 3
    const val UNMUTE = 4
    const val TOGGLE = 5
    const val VOLUME_UP = 6
    const val VOLUME_DOWN = 7
    const val ENQUEUE = 8
    const val CLEAR_PLAYLIST = 11
}

/** Decoded `MediaPlayerCommandRequest`. */
data class MediaPlayerCommandData(
    val command: Int?, val volume: Float?, val mediaUrl: String?, val announcement: Boolean,
)

data class MediaPlayerStateData(val state: Int, val volume: Float, val muted: Boolean)

class MediaPlayerEntity(
    objectId: String, name: String, icon: String = "",
    /** `MediaPlayerSupportedFormat`s: (format, purpose: 0 default / 1 announcement). */
    private val formats: List<Pair<String, Int>>,
    private val featureFlags: Int,
    private val get: () -> MediaPlayerStateData,
    private val handle: (MediaPlayerCommandData) -> Unit,
) : ApiEntity(objectId, name, icon) {
    override val listType = Msg.LIST_ENTITIES_MEDIA_PLAYER
    override val stateType = Msg.MEDIA_PLAYER_STATE
    override val commandType = Msg.MEDIA_PLAYER_COMMAND
    override fun info(): ProtoWriter {
        val w = base().string(5, icon).bool(6, disabledByDefault).uint32(7, category.value)
            .bool(8, featureFlags and FEATURE_PAUSE != 0)
        for ((format, purpose) in formats) w.message(9, ProtoWriter().string(1, format).uint32(4, purpose))
        return w.uint32(11, featureFlags)
    }
    override fun state(): ProtoWriter {
        val s = get()
        return ProtoWriter().fixed32(1, key, true).uint32(2, s.state)
            .float(3, Math.round(s.volume * 100) / 100f).bool(4, s.muted)
    }
    override fun command(msg: ProtoMessage) = handle(
        MediaPlayerCommandData(
            command = if (msg.bool(2)) msg.uint32(3) else null,
            volume = if (msg.bool(4)) msg.float(5) else null,
            mediaUrl = if (msg.bool(6)) msg.string(7) else null,
            announcement = msg.bool(8) && msg.bool(9),
        )
    )

    companion object {
        // MediaPlayerEntityFeature (aioesphomeapi model.py)
        const val FEATURE_PAUSE = 1 shl 0
        const val FEATURE_VOLUME_SET = 1 shl 2
        const val FEATURE_VOLUME_MUTE = 1 shl 3
        const val FEATURE_PLAY_MEDIA = 1 shl 9
        const val FEATURE_VOLUME_STEP = 1 shl 10
        const val FEATURE_STOP = 1 shl 12
        const val FEATURE_PLAY = 1 shl 14
        const val FEATURE_BROWSE_MEDIA = 1 shl 17
        const val FEATURE_MEDIA_ANNOUNCE = 1 shl 20
    }
}
