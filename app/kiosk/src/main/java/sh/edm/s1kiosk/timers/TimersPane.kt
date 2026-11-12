/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package sh.edm.s1kiosk.timers

import android.os.Handler
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PanelEvent
import sh.edm.esphome.EspHomeLog
import sh.edm.s1kiosk.ScreenController
import sh.edm.s1kiosk.TimerBoard
import java.util.concurrent.Future

/**
 * Voice timers as a pane: the countdown board, the screen waking when one finishes and the finished
 * chimes. It has no Home Assistant entities. The voice assistant reports timers as
 * [PanelEvent.TimersChanged] and [PanelEvent.TimerFinished].
 *
 * [ui] runs a block on the main thread. [wakeScreen] and [screenChanged] run there.
 */
class TimersPane(
    private val chimes: TimerChimes,
    private val ui: (() -> Unit) -> Unit,
    private val wakeScreen: () -> Unit = { ScreenController.wake("timer") },
    private val screenChanged: () -> Unit = { ScreenController.onStateChanged?.invoke() },
) : Pane {
    override val id = "timers"

    /**
     * [schedule] runs an action after a delay, [play] starts one chime and [silence] cuts the sound.
     * The shell owns the chime player: the wake sound shares it.
     */
    constructor(
        ctx: PaneContext,
        schedule: (delayMs: Long, action: () -> Unit) -> Future<*>,
        play: () -> Unit,
        silence: () -> Unit,
    ) : this(TimerChimes(schedule, play, silence), mainThread(ctx))

    override fun start() {
        TimerBoard.onDismissed = { chimes.stop() }
    }

    override fun stop() {
        TimerBoard.onDismissed = null
        chimes.stop()
    }

    override fun onEvent(event: PanelEvent) {
        when (event) {
            is PanelEvent.TimerFinished -> {
                EspHomeLog.i("Timer '${event.name}' finished")
                TimerBoard.finished(event.name)
                ui { wakeScreen(); screenChanged() }
                // Three chimes ~3 s apart so it's noticeable across the room; a tap on the done screen stops them.
                chimes.ring()
            }
            is PanelEvent.TimersChanged -> {
                TimerBoard.update(event.timers.map { TimerBoard.Entry(it.id, it.name, it.totalSeconds, it.secondsLeft, it.active) })
                ui { screenChanged() }
            }
            else -> Unit
        }
    }

    private companion object {
        fun mainThread(ctx: PaneContext): (() -> Unit) -> Unit {
            val handler = Handler(ctx.context.mainLooper)
            return { handler.post(it) }
        }
    }
}
