package io.github.aguilaair.s1kiosk.base

/**
 * Last known values of the wall base and the decision of which entity set to expose. Pure JVM
 * (fed parsed callback values and a clock), thread-safe: callbacks, polling and the API server's
 * entity getters all touch it.
 *
 * Layout changes are debounced: a new entity set is only reported by [due] once the detected
 * layout has been stable for [debounceMs] *and* its entity set differs from the applied one, so a
 * flapping or half-read base never makes the API server drop connections in a loop.
 */
class BaseTracker(initial: BaseLayout, private val debounceMs: Long = 5_000, private val clickDedupMs: Long = 250) {

    data class Click(val channel: Int, val raw: Any, val type: String)

    data class Update(
        /** Resource ids whose value changed. */
        val changed: Set<String>,
        /** Button events found in the payload (after de-duplication). */
        val clicks: List<Click>,
        /** The payload mentions a dock-detection resource (re-read the dock class). */
        val dockMentioned: Boolean,
    )

    private var serviceAvailable: Boolean? = null
    private var dockClass: Int? = null
    private var dockNoReply = false
    private var channelCount: Int? = null
    private var relayMask: Int? = null
    private val relays = arrayOfNulls<Boolean>(BaseRes.MAX_CHANNELS)
    private var power: Float? = null
    private var energy: Float? = null
    private var protection: Int? = null
    private var firmware: String? = null
    private val names = arrayOfNulls<String>(BaseRes.MAX_CHANNELS)

    private var applied: BaseLayout = initial
    private var candidate: BaseLayout? = null
    private var candidateSince = 0L
    private val lastClick = HashMap<Int, Pair<String, Long>>()

    @Synchronized fun setServiceAvailable(v: Boolean) { serviceAvailable = v }

    /** No reply to a dock class read: treat as "none" until a reply arrives. */
    @Synchronized fun markDockNoReply() { if (dockClass == null) dockNoReply = true }

    @Synchronized fun hasDockClass(): Boolean = dockClass != null

    @Synchronized
    fun apply(values: Map<String, Any>, now: Long): Update {
        val changed = LinkedHashSet<String>()
        val clicks = ArrayList<Click>()
        var dock = false
        for ((res, raw) in values) {
            if (res in BaseRes.DOCK_TRIGGERS) dock = true
            when (res) {
                BaseRes.DOCK_CLASS -> {
                    // A dock class reply only counts when it is a number; -1 is stock's "missing".
                    val v = BaseProtocol.asInt(raw)
                    if (v != null && v != dockClass) { dockClass = v; changed += res }
                    if (v != null) dockNoReply = false
                }
                BaseRes.CHANNEL_COUNT -> BaseProtocol.asInt(raw)?.let { if (it != channelCount) { channelCount = it; changed += res } }
                BaseRes.RELAY_MASK -> BaseProtocol.asInt(raw)?.let { if (it != relayMask) { relayMask = it; changed += res } }
                BaseRes.POWER -> BaseProtocol.asFloat(raw)?.let { if (it != power) { power = it; changed += res } }
                BaseRes.ENERGY -> BaseProtocol.asFloat(raw)?.let { if (it != energy) { energy = it; changed += res } }
                BaseRes.PROTECTION -> BaseProtocol.asInt(raw)?.let { if (it != protection) { protection = it; changed += res } }
                BaseRes.FIRMWARE -> BaseProtocol.asText(raw)?.let { if (it != firmware) { firmware = it; changed += res } }
                else -> {
                    val r = BaseRes.RELAYS.indexOf(res)
                    val c = BaseRes.CLICKS.indexOf(res)
                    val n = BaseRes.NAMES.indexOf(res)
                    when {
                        r >= 0 -> BaseProtocol.asInt(raw)?.let { v ->
                            val on = v != 0
                            if (relays[r] != on) { relays[r] = on; changed += res }
                        }
                        c >= 0 -> {
                            val ch = c + 1
                            val key = raw.toString()
                            val prev = lastClick[ch]
                            if (prev == null || prev.first != key || now - prev.second >= clickDedupMs) {
                                clicks += Click(ch, raw, BaseProtocol.clickType(raw))
                            }
                            lastClick[ch] = key to now
                        }
                        n >= 0 -> BaseProtocol.asText(raw).let { if (it != names[n]) { names[n] = it; changed += res } }
                    }
                }
            }
        }
        return Update(changed, clicks, dock)
    }

    /** Currently detected layout, or null while nothing is known yet. */
    @Synchronized
    fun current(): BaseLayout? {
        if (serviceAvailable == false) return BaseLayout.UNAVAILABLE
        val cls = dockClass
        if (cls == null) return if (dockNoReply) BaseLayout.NONE else null
        val (kind, raw) = BaseProtocol.decodeDockClass(cls)
        return when (kind) {
            BaseKind.SWITCH -> BaseLayout(kind, BaseProtocol.channels(relayMask, channelCount))
            BaseKind.UNKNOWN -> BaseLayout(kind, rawClass = raw)
            else -> BaseLayout(kind)
        }
    }

    @Synchronized fun applied(): BaseLayout = applied

    /**
     * The layout whose entity set should now replace the applied one, or null. Call periodically;
     * after acting on a non-null result call [markApplied].
     */
    @Synchronized
    fun due(now: Long): BaseLayout? {
        val cur = current() ?: return null
        if (BaseEntities.objectIds(cur) == BaseEntities.objectIds(applied)) {
            applied = cur // same entities (e.g. none <-> unavailable): nothing to rebuild
            candidate = null
            return null
        }
        if (cur != candidate) {
            candidate = cur
            candidateSince = now
            return null
        }
        return if (now - candidateSince >= debounceMs) cur else null
    }

    @Synchronized
    fun markApplied(layout: BaseLayout) {
        applied = layout
        candidate = null
    }

    @Synchronized fun relay(ch: Int): Boolean? = relays.getOrNull(ch - 1)
    @Synchronized fun power(): Float? = power
    @Synchronized fun energy(): Float? = energy
    @Synchronized fun protection(): Int? = protection
    @Synchronized fun firmware(): String? = firmware
    @Synchronized fun channelName(ch: Int): String? = names.getOrNull(ch - 1)

    /** Forget the per-base values (on detach), so a different base doesn't show stale data. */
    @Synchronized
    fun clearBaseValues() {
        channelCount = null
        relayMask = null
        relays.fill(null)
        power = null
        energy = null
        protection = null
        firmware = null
        names.fill(null)
    }
}
