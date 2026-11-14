/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The 6-digit code the panel shows on its screen, which proves someone is standing at it: the way in
 * when Home Assistant is down, and the second step of a dangerous action. One code at a time, for one
 * purpose and one browser, for [ttlMs] (5 min), used once.
 *
 * Guessing is limited twice: a code dies after [perCode] wrong tries (5), and [windowFailures] wrong
 * tries in [windowMs] (10 in 15 min, from any browser) lock code entry until the oldest of them is
 * [windowMs] old. At most 40 guesses an hour against 10^6 codes, each new code shown on the screen.
 *
 * Asking is limited too: no new code goes up within [minGapMs] (30 s) of the last one, also when that
 * one was used up by wrong tries or closed, unless it was typed right. The same browser asking again for
 * the same thing gets the same code; anyone else waits, so nobody can keep replacing another person's
 * code. And at most [maxShows] new codes go up in [showWindowMs] (6 in 10 min, all browsers together), so
 * nobody can keep the screen lit and waking with codes. Thread-safe.
 *
 * [display] is called under the lock, in the order the code changes, so a late "hide" never hides a newer
 * code. It must not block (the panel posts to its main thread).
 */
class PanelCode(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = 5 * 60_000L,
    private val perCode: Int = 5,
    private val windowMs: Long = 15 * 60_000L,
    private val windowFailures: Int = 10,
    private val minGapMs: Long = 30_000L,
    private val maxShows: Int = 6,
    /** The window [maxShows] counts in; public so the page can say it. */
    val showWindowMs: Long = 10 * 60_000L,
) {
    /** What the panel shows: [code], and in words what it is for ([label], "Sign in to manage this panel"). */
    data class Shown(val code: String, val purpose: String, val label: String, val untilMs: Long) {
        override fun toString() = "Shown(purpose=$purpose, until=$untilMs)"
    }

    sealed class Check {
        object Ok : Check()
        class Wrong(val triesLeft: Int) : Check()
        /** No code for this browser and purpose: never asked, expired, used up, or replaced by a newer one. */
        object NoCode : Check()
        class Locked(val untilMs: Long) : Check()
    }

    /** Why [Show.Busy]: another browser's code is up (under 30 s), or too many codes went up lately. */
    enum class Why { SHOWING, TOO_MANY }

    /** What [show] did. */
    sealed class Show {
        class Ok(val shown: Shown) : Show()
        /** Too many wrong codes: no new code until [untilMs]. */
        class Locked(val untilMs: Long) : Show()
        /** Another browser's code went up less than 30 s ago, or too many codes went up lately: try again at [untilMs]. */
        class Busy(val untilMs: Long, val why: Why) : Show()
        /** The panel can't draw a code now ([canDisplay] is false). */
        object Unavailable : Show()
    }

    private class Pending(val shown: Shown, val requester: String, val shownAtMs: Long, var wrong: Int = 0)

    private var pending: Pending? = null
    /** When the last code went up; 0 once one was typed right. Kept apart from [pending], which a burn clears. */
    private var lastShownAtMs = 0L
    private val failures = ArrayDeque<Long>()
    private val shows = ArrayDeque<Long>()

    /** Called with the code to show, or null to hide it. The panel's screen sets it. Any thread. */
    @Volatile var display: (Shown?) -> Unit = {}

    /** Whether the panel can draw a code now. False until the panel's screen says it can. */
    @Volatile var canDisplay: () -> Boolean = { false }

    /** Shows a code for [purpose], for the browser [requester]: a new one, or the one already up for it. */
    fun show(purpose: String, label: String, requester: String): Show = synchronized(this) {
        if (!canDisplay()) return Show.Unavailable
        lockedUntil()?.let { return Show.Locked(it) }
        val now = clock()
        val p = pending
        if (p != null && now < p.shown.untilMs && now - p.shownAtMs < minGapMs && p.requester == requester && p.shown.purpose == purpose) {
            return Show.Ok(p.shown)
        }
        if (lastShownAtMs != 0L && now - lastShownAtMs < minGapMs) return Show.Busy(lastShownAtMs + minGapMs, Why.SHOWING)
        while (shows.isNotEmpty() && now - shows.first() >= showWindowMs) shows.removeFirst()
        if (shows.size >= maxShows) return Show.Busy(shows.first() + showWindowMs, Why.TOO_MANY)
        shows.addLast(now)
        lastShownAtMs = now
        val code = (random.nextInt(1_000_000)).toString().padStart(6, '0')
        val shown = Shown(code, purpose, label, now + ttlMs)
        pending = Pending(shown, requester, now)
        display(shown)
        Show.Ok(shown)
    }

    /** Checks [input] against the code shown for [purpose] and [requester]. A right code is used up. */
    fun check(purpose: String, requester: String, input: String): Check = synchronized(this) {
        var hide = false
        val result = run {
            lockedUntil()?.let { return@run Check.Locked(it) }
            val p = pending
            if (p == null || p.shown.purpose != purpose || p.requester != requester) return@run Check.NoCode
            if (clock() >= p.shown.untilMs) { pending = null; hide = true; return@run Check.NoCode }
            val digits = input.filter { it.isDigit() }
            if (digits.length == 6 && MessageDigest.isEqual(digits.toByteArray(), p.shown.code.toByteArray())) {
                // Someone at the panel typed it: the next code (a reboot's, say) needn't wait.
                pending = null; hide = true; lastShownAtMs = 0L
                return@run Check.Ok
            }
            p.wrong++
            failures.addLast(clock())
            if (p.wrong >= perCode) { pending = null; hide = true }
            lockedUntil()?.let { pending = null; hide = true; return@run Check.Locked(it) }
            Check.Wrong(if (pending == null) 0 else perCode - p.wrong)
        }
        if (hide) display(null)
        result
    }

    /** The code on the screen now, if any. */
    @Synchronized
    fun current(): Shown? = pending?.shown?.takeIf { clock() < it.untilMs }

    /** Hides the code if it has run out (the panel calls this when [Shown.untilMs] passes). A newer code stays. */
    @Synchronized
    fun expire() {
        val p = pending
        if (p != null && clock() >= p.shown.untilMs) {
            pending = null
            display(null)
        }
    }

    /** Hides the code (the person at the panel closed it, or the browser cancelled). */
    @Synchronized
    fun dismiss() {
        pending = null
        display(null)
    }

    /** When code entry opens again, or null when it is open now. */
    @Synchronized
    fun lockedUntil(): Long? {
        val now = clock()
        while (failures.isNotEmpty() && now - failures.first() >= windowMs) failures.removeFirst()
        return if (failures.size >= windowFailures) failures.first() + windowMs else null
    }
}
