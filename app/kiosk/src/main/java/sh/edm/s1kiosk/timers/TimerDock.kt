package sh.edm.s1kiosk.timers

import sh.edm.s1kiosk.TimerBoard
import kotlin.math.hypot

/**
 * Where the timer group sits: one of eight spots, a [MARGIN_DP] clear of the screen edges. [h] is −1
 * left, 0 centre, 1 right; [v] is −1 top, 0 middle, 1 bottom.
 */
enum class Anchor(val h: Int, val v: Int) {
    TOP_LEFT(-1, -1), TOP(0, -1), TOP_RIGHT(1, -1),
    LEFT(-1, 0), RIGHT(1, 0),
    BOTTOM_LEFT(-1, 1), BOTTOM(0, 1), BOTTOM_RIGHT(1, 1);

    /** The edge a hidden group tucks into: the side it's on, else the top or bottom. */
    val edge: Edge get() = when (h) {
        -1 -> Edge.LEFT
        1 -> Edge.RIGHT
        else -> if (v < 0) Edge.TOP else Edge.BOTTOM
    }

    companion object {
        val DEFAULT = BOTTOM_RIGHT
        fun parse(s: String?): Anchor = entries.firstOrNull { it.name == s } ?: DEFAULT
    }
}

enum class Edge { LEFT, RIGHT, TOP, BOTTOM }

/** Placement and snapping of the timer group, in any unit (the views use px). Pure. */
object DockMath {
    const val MARGIN_DP = 16f

    data class Pt(val x: Float, val y: Float)

    /** Top-left of a [gw]×[gh] group at [a] on a [sw]×[sh] screen. */
    fun slot(a: Anchor, sw: Float, sh: Float, gw: Float, gh: Float, margin: Float): Pt {
        fun place(k: Int, screen: Float, size: Float) = when (k) {
            -1 -> margin
            1 -> screen - margin - size
            else -> (screen - size) / 2f
        }
        return Pt(place(a.h, sw, gw), place(a.v, sh, gh))
    }

    /** The spot whose slot centre is closest to a group whose top-left is at ([x], [y]). */
    fun nearest(x: Float, y: Float, sw: Float, sh: Float, gw: Float, gh: Float, margin: Float): Anchor =
        Anchor.entries.minBy { a ->
            val s = slot(a, sw, sh, gw, gh, margin)
            hypot(s.x - x, s.y - y)
        }

    /** Keeps a dragged group's top-left on screen (the finger can go further). */
    fun clamp(x: Float, y: Float, sw: Float, sh: Float, gw: Float, gh: Float): Pt =
        Pt(x.coerceIn(0f, (sw - gw).coerceAtLeast(0f)), y.coerceIn(0f, (sh - gh).coerceAtLeast(0f)))

    /**
     * Order of the rings left to right: the soonest timer goes nearest the anchor's side (rightmost
     * for right-hand spots), so it stays in the same place as others come and go.
     */
    fun <T> order(soonestFirst: List<T>, a: Anchor): List<T> = if (a.h == 1) soonestFirst.reversed() else soonestFirst
}

/**
 * Whether the group is out, tucked away, or not there (pure). Hidden is the owner's choice and sticks
 * until they bring the group back, a new timer is set, or no timers are left. The last 10 seconds of
 * any running timer bring it out regardless; the done screen is separate and never hidden.
 */
class DockState {
    enum class Mode { NONE, EXPANDED, COLLAPSED }

    var hidden = false
        private set
    private var known: Set<String> = emptySet()

    /** Call with every new list of timers; returns the ids that weren't there before. */
    fun onTimers(timers: List<TimerBoard.Shown>): Set<String> {
        val ids = timers.map { it.id }.toSet()
        val fresh = ids - known
        known = ids
        if (fresh.isNotEmpty() || ids.isEmpty()) hidden = false
        return fresh
    }

    fun hide() { if (known.isNotEmpty()) hidden = true }

    fun show() { hidden = false }

    fun mode(timers: List<TimerBoard.Shown>, now: Long): Mode = when {
        timers.isEmpty() -> Mode.NONE
        !hidden -> Mode.EXPANDED
        timers.any { it.hot(now) } -> Mode.EXPANDED
        else -> Mode.COLLAPSED
    }
}
