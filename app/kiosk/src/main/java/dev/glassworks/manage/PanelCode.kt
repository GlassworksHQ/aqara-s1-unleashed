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
 * Thread-safe.
 */
class PanelCode(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMs: Long = 5 * 60_000L,
    private val perCode: Int = 5,
    private val windowMs: Long = 15 * 60_000L,
    private val windowFailures: Int = 10,
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

    private class Pending(val shown: Shown, val requester: String, var wrong: Int = 0)

    private var pending: Pending? = null
    private val failures = ArrayDeque<Long>()

    /** Called with the code to show, or null to hide it. The panel's screen sets it. Any thread. */
    @Volatile var display: (Shown?) -> Unit = {}

    /** Shows a new code for [purpose], for the browser [requester]. Null when code entry is locked. */
    fun show(purpose: String, label: String, requester: String): Shown? {
        val shown = synchronized(this) {
            if (lockedUntil() != null) return null
            val code = (random.nextInt(1_000_000)).toString().padStart(6, '0')
            Shown(code, purpose, label, clock() + ttlMs).also { pending = Pending(it, requester) }
        }
        display(shown)
        return shown
    }

    /** Checks [input] against the code shown for [purpose] and [requester]. A right code is used up. */
    fun check(purpose: String, requester: String, input: String): Check {
        var hide = false
        val result = synchronized(this) {
            lockedUntil()?.let { return@synchronized Check.Locked(it) }
            val p = pending
            if (p == null || p.shown.purpose != purpose || p.requester != requester) return@synchronized Check.NoCode
            if (clock() >= p.shown.untilMs) { pending = null; hide = true; return@synchronized Check.NoCode }
            val digits = input.filter { it.isDigit() }
            if (digits.length == 6 && MessageDigest.isEqual(digits.toByteArray(), p.shown.code.toByteArray())) {
                pending = null; hide = true
                return@synchronized Check.Ok
            }
            p.wrong++
            failures.addLast(clock())
            if (p.wrong >= perCode) { pending = null; hide = true }
            lockedUntil()?.let { pending = null; hide = true; return@synchronized Check.Locked(it) }
            Check.Wrong(if (pending == null) 0 else perCode - p.wrong)
        }
        if (hide) display(null)
        return result
    }

    /** The code on the screen now, if any. */
    @Synchronized
    fun current(): Shown? = pending?.shown?.takeIf { clock() < it.untilMs }

    /** Hides the code (the person at the panel closed it, or the browser cancelled). */
    fun dismiss() {
        synchronized(this) { pending = null }
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
