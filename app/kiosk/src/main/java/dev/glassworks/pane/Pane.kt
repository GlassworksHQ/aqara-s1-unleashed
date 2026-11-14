/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import android.content.SharedPreferences
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ApiServer
import sh.edm.esphome.DeviceIdentity
import sh.edm.esphome.EspHomeLog
import sh.edm.esphome.EventEntity
import sh.edm.esphome.HomeAssistantEvent
import java.net.InetAddress

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

    /**
     * Home Assistant subscribed to the entity states from [address]. [authenticated]: the connection uses
     * the API key Home Assistant provisioned (Noise). Only an authenticated one proves it is that Home
     * Assistant, so a pane that trusts the address checks it.
     */
    data class HomeAssistantSubscribed(val address: InetAddress?, val authenticated: Boolean) : PanelEvent

    /**
     * Home Assistant's own time zone, from its answer to the panel's time request on an authenticated
     * connection: a POSIX `TZ` rule like `CET-1CEST,M3.5.0,M10.5.0/3`, not an IANA id.
     */
    data class HomeAssistantTimeZone(val posix: String) : PanelEvent
}

/** One voice timer as Home Assistant reports it. */
data class PanelTimer(val id: String, val name: String, val totalSeconds: Int, val secondsLeft: Int, val active: Boolean)

/** What the shell gives a pane when it builds it. */
class PaneContext(
    /** The application context. */
    val context: Context,
    /**
     * The settings of [namespace], which is the pane's [Pane.id]. A pane wraps the file in its own
     * typed class and reads and writes only its own keys there. The frame decides which file holds a
     * namespace, so a frame can keep the files and keys its panels already have. The frame raises
     * [PanelEvent.SettingChanged] when a key in the file changes. Throws [IllegalArgumentException]
     * for a namespace the frame does not know.
     */
    val prefs: (namespace: String) -> SharedPreferences,
    val device: DeviceIdentity,
    /** Pushes every entity state to Home Assistant. Safe from any thread, also while the device stops. */
    val publishStates: () -> Unit,
    /** Raises [event] to every pane, this one included. Safe from any thread. */
    val emit: (PanelEvent) -> Unit,
    /**
     * Tells the shell that [Pane.entities] now returns a different list. The shell builds the device's
     * list again from its own entities and every pane's, and gives it to Home Assistant, which then
     * reconnects and lists the entities again. Safe from any thread; the rebuild runs on the device
     * thread, after the work already queued there.
     */
    val entitiesChanged: () -> Unit,
    /**
     * Fires [type] on one of this pane's event entities. Pass the instance from the pane's current
     * [Pane.entities] list; the server drops an event for an entity it no longer has. Safe from any
     * thread; a no-op while the server is not running.
     */
    val sendEvent: (EventEntity, String) -> Unit,
    /**
     * Fires an event on Home Assistant's bus (`esphome.<name>`), for a report an event entity can't
     * carry: HA refuses event types the entity didn't list up front. The notifications pane's taps go
     * this way. Safe from any thread; dropped while no Home Assistant is connected.
     */
    val fireEvent: (HomeAssistantEvent) -> Unit = {},
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
 * An interruption is not a failure: it goes back to the caller, with the thread's interrupt flag set.
 */
fun List<Pane>.forEachPane(op: String, block: (Pane) -> Unit) {
    for (pane in this) {
        try {
            block(pane)
        } catch (e: InterruptedException) {
            // The caller is being cancelled (the device is stopping); that is not this pane failing.
            Thread.currentThread().interrupt()
            throw e
        } catch (t: Throwable) {
            EspHomeLog.w("pane ${pane.id} failed in $op", t)
        }
    }
}

/**
 * Stops every pane, even when one of them is interrupted, so shutdown always runs to the end. A pane
 * that throws is logged and skipped, as in [forEachPane]. The thread's interrupt flag is cleared
 * before each pane, so no pane's own waits are cut short, and the result says whether there was an
 * interruption (from a pane, or already pending): the caller sets the flag again once its own
 * shutdown is done.
 */
fun List<Pane>.stopAll(): Boolean {
    var interrupted = false
    for (pane in this) {
        if (Thread.interrupted()) interrupted = true
        try {
            pane.stop()
        } catch (e: InterruptedException) {
            interrupted = true
        } catch (t: Throwable) {
            EspHomeLog.w("pane ${pane.id} failed in stop", t)
        }
    }
    if (Thread.interrupted()) interrupted = true
    return interrupted
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
