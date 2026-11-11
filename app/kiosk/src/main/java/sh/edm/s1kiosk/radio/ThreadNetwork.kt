package sh.edm.s1kiosk.radio

/**
 * The Thread router's active dataset as it is pasted (HA's `Thread dataset` text entity, or the field
 * in the kiosk settings menu). Pure, for tests; never logs or returns the dataset in a message.
 *
 * A copy from Home Assistant's Thread page can carry invisible characters around or inside the hex:
 * spaces, line breaks, a no-break space, zero-width characters. They are dropped; anything else that
 * isn't hex is still refused.
 */
object ThreadDataset {
    /** Active datasets are at most 254 bytes (Thread spec, OT_OPERATIONAL_DATASET_MAX_LENGTH). */
    const val MAX_BYTES = 254

    /**
     * The `Thread dataset` entity's pattern: hex plus whitespace, the no-break and other Unicode
     * spaces, and the zero-width characters. Written out with `\u` escapes, not `\s` alone: Python's `re`
     * (Home Assistant checks the value with it) treats `\s` as Unicode whitespace, but Java's regex (this
     * app, [sh.edm.s1kiosk.esphome.TextEntity]) doesn't, and neither covers the zero-width characters.
     * Python, Java and the browser's `pattern` attribute (the `v` flag) read this the same way.
     */
    const val PATTERN = "^[0-9a-fA-F\\s\\u00a0\\u1680\\u2000-\\u200d\\u2028\\u2029\\u202f\\u205f\\u2060\\u3000\\ufeff]*$"

    /** Zero-width characters a copy can carry (not whitespace to Java or Kotlin). */
    private const val ZERO_WIDTH = "​‌‍⁠﻿᠎"

    fun invisible(c: Char): Boolean = c.isWhitespace() || Character.isSpaceChar(c) || c in ZERO_WIDTH

    /** [raw] without its invisible characters, lower case. */
    fun clean(raw: String): String = raw.filterNot(::invisible).lowercase()

    /** Null when [raw] is a usable dataset after [clean], else why not (never echoing the value). */
    fun problem(raw: String): String? {
        val d = clean(raw)
        return when {
            d.isEmpty() -> "Not a Thread dataset: it's empty."
            !d.all { it in '0'..'9' || it in 'a'..'f' } ->
                "Not a Thread dataset: only hex digits (0-9, a-f) are allowed. Copy “Active dataset TLVs” from Home Assistant's Thread page."
            d.length % 2 != 0 -> "Not a Thread dataset: an odd number of hex digits (part of it is missing?)."
            d.length > 2 * MAX_BYTES -> "Not a Thread dataset: longer than $MAX_BYTES bytes."
            else -> null
        }
    }
}

/**
 * What the radio helper says about the Thread router (`ok thread_router: <detail>`), parsed. The 0.4.3
 * helper reports the role and the network name ([RadioHelper] status detail):
 *  - `ready to join: waiting for a Thread dataset …` (no dataset)
 *  - `joined as router|leader|child, network "Name"`, `detached: looking for network "Name"`,
 *    `starting Thread, network "Name"`, `Thread state <x>, network "Name"`
 * Older helpers: `Thread router on wpan0 (<ot-ctl state>)`. Only the role and the name, never keys.
 */
data class ThreadRouterState(val dataset: Boolean, val role: String?, val network: String?) {

    /** The panel is part of the network (child, router or leader). */
    val joined: Boolean get() = role in setOf("child", "router", "leader")

    /** One line for the card and HA's `Radio status`. */
    fun describe(): String = when {
        !dataset -> "no dataset yet: waiting for the Thread network's active dataset"
        else -> {
            val net = network?.let { " “$it”" } ?: ""
            when (role) {
                "router" -> "joined$net as a router"
                "leader" -> "joined$net as the leader"
                "child" -> "joined$net as a child; it becomes a router when the network promotes it (a few minutes)"
                "detached" -> "dataset set, looking for the network$net (detached)"
                "disabled", null -> "dataset set, Thread starting$net"
                else -> "dataset set, Thread state $role$net"
            }
        }
    }

    /** A few words for the setup screen's card. */
    fun short(): String {
        val net = network?.let { " “$it”" } ?: ""
        return when {
            !dataset -> "no dataset yet"
            role == "router" || role == "leader" -> "joined$net as a $role"
            role == "child" -> "joined$net, a router in a few minutes"
            role == "detached" -> "looking for the network$net"
            else -> "starting$net"
        }
    }

    companion object {
        private val NAME = Regex(""""([^"]*)"""")
        private val OLD = Regex("""^Thread router on wpan0 \(([a-z]+)\)""")
        private val ROLE = Regex("""^(?:joined as (router|leader|child)|(detached)|starting Thread|Thread state ([a-z]+))""")

        /** Parses the helper's detail; null when it's something else (an error, applying, …). */
        fun parse(detail: String): ThreadRouterState? {
            val d = detail.trim()
            if (d.contains("waiting for a Thread dataset")) return ThreadRouterState(false, null, null)
            OLD.find(d)?.let { return ThreadRouterState(true, it.groupValues[1], null) }
            val m = ROLE.find(d) ?: return null
            val role = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: "disabled"
            val name = NAME.find(d)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
            return ThreadRouterState(true, role, name)
        }
    }
}
