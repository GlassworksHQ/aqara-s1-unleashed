/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.EspHomeLog
import sh.edm.s1kiosk.KioskPrefs

/**
 * Something on the panel that panes may react to. Delivered on any thread. Not sealed: a pane ignores
 * the events it does not know, so adding an event does not change any other pane.
 */
interface PanelEvent {
    /** A panel setting changed (the SharedPreferences key). */
    data class SettingChanged(val key: String?) : PanelEvent

    /** Voice or an announcement holds the speaker (true) or let it go (false): other audio goes quiet locally. */
    data class SpeakerClaimed(val claimed: Boolean) : PanelEvent

    /** A timer reached zero. [name] is what Home Assistant sent ("" when unnamed). */
    data class TimerFinished(val name: String) : PanelEvent

    /** The timers that are running or paused now, as the voice assistant reports them. Replaces the previous list. */
    data class TimersChanged(val timers: List<PanelTimer>) : PanelEvent
}

/** One voice timer as Home Assistant reports it. */
data class PanelTimer(val id: String, val name: String, val totalSeconds: Int, val secondsLeft: Int, val active: Boolean)

/** What the shell gives a pane when it builds it. */
class PaneContext(
    /** The application context. */
    val context: Context,
    /** Until #18 splits the settings per pane. */
    val prefs: KioskPrefs,
    val device: DeviceIdentity,
    /** Pushes every entity state to Home Assistant. Safe from any thread, also while the device stops. */
    val publishStates: () -> Unit,
    /** Raises [event] to every pane, this one included. Safe from any thread. */
    val emit: (PanelEvent) -> Unit,
)

/**
 * A feature the panel can have or leave out: its entities, its lifecycle and what it does when the
 * panel changes. The device shell ([sh.edm.s1kiosk.esphome.PanelDevice]) builds the panes, calls
 * [start] and [stop] and fans [PanelEvent]s out to them.
 *
 * Calls come from several threads: [start] on esphome-main, [stop] from the service, [onEvent] from
 * wherever the event happens. A pane guards its own state.
 */
interface Pane {
    val id: String

    /** The entities this pane adds to the Home Assistant device. Object ids must not clash with other panes'. */
    fun entities(): List<ApiEntity> = emptyList()

    /** Called once the ESPHome API server exists, before it starts. May be called again if the server restarts. */
    fun bind(server: ApiServer) {}

    fun start() {}

    fun stop() {}

    fun onEvent(event: PanelEvent) {}
}

/**
 * Runs [block] on every pane. A pane that throws is logged (with [op], the step that failed) and
 * skipped, so one broken pane never stops the others from starting, stopping or hearing an event.
 */
fun List<Pane>.forEachPane(op: String, block: (Pane) -> Unit) {
    for (pane in this) {
        try {
            block(pane)
        } catch (t: Throwable) {
            EspHomeLog.w("pane ${pane.id} failed in $op", t)
        }
    }
}

/** The Home Assistant entity list: the device's own entities plus every pane's. */
class EntityRegistry(private val panes: List<Pane>) {

    /** Throws [IllegalStateException] naming the object id if two entities share one (their keys would collide). */
    fun assemble(core: List<ApiEntity>): List<ApiEntity> {
        val all = core + panes.flatMap { it.entities() }
        val seen = HashSet<String>()
        for (e in all) check(seen.add(e.objectId)) { "Two entities share the object id '${e.objectId}'" }
        return all
    }
}
