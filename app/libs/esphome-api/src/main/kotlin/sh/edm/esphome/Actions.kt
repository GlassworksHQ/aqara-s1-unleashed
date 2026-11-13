package sh.edm.esphome

/** `ServiceArgType` (api.proto): the type of one action argument. */
enum class ActionArgType(val value: Int) {
    BOOL(0), INT(1), FLOAT(2), STRING(3), BOOL_ARRAY(4), INT_ARRAY(5), FLOAT_ARRAY(6), STRING_ARRAY(7),
}

/** One argument of an [ApiAction]. Home Assistant makes every argument required. */
data class ActionArg(val name: String, val type: ActionArgType)

/**
 * `SupportsResponseType` (api.proto): whether Home Assistant waits for the action's result.
 * [NONE] fires and forgets. [STATUS] waits for success or the error. [OPTIONAL] also returns the
 * action's JSON when the caller asks for it (`response_variable`), and [ONLY] always does.
 */
enum class ActionResponse(val value: Int) { NONE(0), OPTIONAL(1), ONLY(2), STATUS(100) }

/**
 * One call of an [ApiAction], with the arguments by name. A value is a `Boolean`, `Int`, `Float`,
 * `String` or a `List` of one of them, as the argument's [ActionArgType] says.
 * [returnResponse]: Home Assistant wants the JSON the handler returns.
 */
class ActionCall(val action: ApiAction, val args: Map<String, Any>, val returnResponse: Boolean) {
    fun bool(name: String): Boolean = arg(name)
    fun int(name: String): Int = arg(name)
    fun float(name: String): Float = arg(name)
    fun string(name: String): String = arg(name)
    fun bools(name: String): List<Boolean> = arg(name)
    fun ints(name: String): List<Int> = arg(name)
    fun floats(name: String): List<Float> = arg(name)
    fun strings(name: String): List<String> = arg(name)

    private inline fun <reified T> arg(name: String): T {
        val v = args[name] ?: throw IllegalArgumentException("${action.name} has no argument '$name'")
        return v as? T ?: throw IllegalArgumentException("${action.name}.$name is ${v::class.simpleName}")
    }
}

/**
 * A user-defined action (an ESPHome `api: actions:` entry). Home Assistant registers it as
 * `esphome.<node name, '-' → '_'>_<name>` and calls it with every argument in [args].
 *
 * [run] gets each call on the server's action thread, one call at a time, in the order they
 * arrived. It returns a JSON object as text, which Home Assistant gets back when [supportsResponse]
 * is [ActionResponse.OPTIONAL] or [ActionResponse.ONLY], or null for none. To fail the call, throw:
 * Home Assistant shows the exception's message (for every [supportsResponse] except NONE).
 *
 * Wire format: `ListEntitiesServicesResponse` (41), `ExecuteServiceRequest` (42) and
 * `ExecuteServiceResponse` (131) in aioesphomeapi 46.25.0's api.proto. The key is FNV-1a of [name],
 * as ESPHome's user services do.
 */
class ApiAction(
    name: String,
    val args: List<ActionArg> = emptyList(),
    val supportsResponse: ActionResponse = ActionResponse.NONE,
    private val run: (ActionCall) -> String?,
) : ApiEntity(name, name) {
    init {
        require(NAME.matches(name)) { "action name '$name' must be lowercase snake_case" }
        require(args.map { it.name }.toSet().size == args.size) { "duplicate argument names in $name" }
    }

    override val listType = Msg.LIST_ENTITIES_SERVICES
    override val commandType = Msg.EXECUTE_SERVICE_REQUEST

    override fun info(): ProtoWriter {
        val w = ProtoWriter().string(1, name, force = true).fixed32(2, key, force = true)
        for (a in args) w.message(3, ProtoWriter().string(1, a.name).uint32(2, a.type.value))
        return w.uint32(4, supportsResponse.value)
    }

    /** Decodes an `ExecuteServiceRequest`. Throws [IllegalArgumentException] if the arguments don't match. */
    internal fun decode(msg: ProtoMessage): ActionCall {
        val sent = msg.messages(2)
        require(sent.size == args.size) { "$name takes ${args.size} argument(s), got ${sent.size}" }
        val values = LinkedHashMap<String, Any>()
        for ((a, m) in args.zip(sent)) values[a.name] = decodeArg(a.type, m)
        return ActionCall(this, values, msg.bool(4))
    }

    /** Runs the handler and builds the `ExecuteServiceResponse` for [callId]. */
    internal fun execute(call: ActionCall, callId: Int): ProtoWriter {
        val result = runCatching { run(call) }
        val w = ProtoWriter().uint32(1, callId)
        result.onFailure { t ->
            EspHomeLog.w("action $name failed", t)
            return w.string(3, t.message ?: t::class.simpleName ?: "failed")
        }
        w.bool(2, true)
        val data = result.getOrNull()
        val wantsData = call.returnResponse &&
            (supportsResponse == ActionResponse.OPTIONAL || supportsResponse == ActionResponse.ONLY)
        if (wantsData && data != null) w.bytes(4, data.toByteArray(Charsets.UTF_8))
        return w
    }

    companion object {
        private val NAME = Regex("[a-z0-9_]+")

        /** `ExecuteServiceArgument`: `int_` (sint32, API 1.3+) wins over `legacy_int` (int32). */
        private fun decodeArg(type: ActionArgType, m: ProtoMessage): Any = when (type) {
            ActionArgType.BOOL -> m.bool(1)
            ActionArgType.INT -> if (m.has(5)) ProtoMessage.zigzag(m.long(5)) else m.long(2).toInt()
            ActionArgType.FLOAT -> m.float(3)
            ActionArgType.STRING -> m.string(4)
            ActionArgType.BOOL_ARRAY -> m.longs(6).map { it != 0L }
            ActionArgType.INT_ARRAY -> m.longs(7).map(ProtoMessage::zigzag)
            ActionArgType.FLOAT_ARRAY -> m.fixed32s(8).map { Float.fromBits(it) }
            ActionArgType.STRING_ARRAY -> m.strings(9)
        }
    }
}
