/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.pane.PaneDefinition
import dev.glassworks.platform.Platform
import dev.glassworks.screen.LightSource
import dev.glassworks.screen.PresenceSource
import java.util.concurrent.ScheduledExecutorService

/**
 * One device the core runs on. The app makes one frame at start and hands it to what needs it.
 * It holds only what the core asks a device today. The rest of the planned shape (screen off,
 * installer, panes the user can turn off) comes in later migration steps.
 */
interface Frame {
    /** The `board` TXT record in the device's mDNS advert. Home Assistant shows it. Never change it for a frame. */
    val board: String

    /** How its devices name themselves to Home Assistant ([DeviceName]). */
    val namedBy: NamedBy

    /**
     * The frame's own name, as `name` in frames/<id>/design/frame.json ("Aqara S1 Plus"). Home Assistant
     * shows it for a frame [NamedBy.FRAME]; null or blank there, the device's model stands in.
     */
    val displayName: String? get() = null

    /** Who makes the device and what they call it, for Home Assistant's device page, Music Assistant and Improv. */
    val hardware: Hardware

    /** The hardware panes can use, as frame.json's `provides`. A pane that [dev.glassworks.pane.PaneDefinition.requires] more can't run. */
    val provides: Set<Capability>

    /** Whether someone is near, for the screen policy. Null when the device has no sensor for it. */
    val presence: PresenceSource?

    /** Ambient light, for the screen policy's brightness curve. Null when the device has no light sensor. */
    val light: LightSource?

    /**
     * The `type` of the thermal zone that measures the CPU, as in `/sys/class/thermal/thermal_zoneN/type`.
     * Null when the device has none, and then the diagnostics pane has no temperature entity.
     */
    val cpuThermalZone: String? get() = null

    /** The device's own settings screens, best first. [systemSettings] picks the first that opens. */
    /**
     * The boot animation's last frame and where it sits on the screen (px), so the app's first screen
     * continues it without a cut (#63); null when the frame has none.
     */
    val bootRest: BootRest? get() = null

    fun systemSettingsIntents(): List<Intent>

    /** Where to choose a Wi-Fi network, best first (the setup screen's "Choose network"). */
    fun wifiSettingsIntents(): List<Intent> = listOfNotNull(
        if (Platform.hasSettingsPanel) Intent(Settings.Panel.ACTION_WIFI) else null,
        Intent(Settings.ACTION_WIFI_SETTINGS),
    )

    /**
     * The definitions of the panes in [panes]: their setup steps, settings sections and adb extras,
     * for the whole process. The core puts them after its own, in this order.
     */
    fun paneDefinitions(context: Context): List<PaneDefinition> = emptyList()

    /**
     * The panes only this device has, for its own hardware and settings. The shell builds them once per
     * device run, next to its own, and runs them like any other. [device] is the shell's device thread.
     */
    fun panes(ctx: PaneContext, device: ScheduledExecutorService): List<Pane>
}

/** The first of [Frame.systemSettingsIntents] that resolves on this device, ready to start from any context. Null when none does. */
fun Frame.systemSettings(context: Context): Intent? {
    val pm = context.packageManager
    return systemSettingsIntents().firstOrNull { it.resolveActivity(pm) != null }
        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Opens [systemSettings]. False when the device has no settings screen or it did not start. */
fun Frame.openSystemSettings(context: Context): Boolean {
    val i = systemSettings(context) ?: return false
    return runCatching { context.startActivity(i) }.isSuccess
}

/**
 * [FRAME]: every device on the frame is the same product, so it goes by the frame: `aqara-s1-plus-a1b2c3`,
 * "Aqara S1 Plus". [MODEL]: the frame stands for many devices (generic-android), so each goes by its own
 * model: `pixel-tablet-a1b2c3`, "Pixel Tablet". Explicit, so a display name added to such a frame later
 * doesn't rename every device on it.
 */
enum class NamedBy { FRAME, MODEL }

/** [manufacturer] ("Aqara"), [model] ("Panel Hub S1 Plus") and [chip] ("Allwinner A133", or the board's name where Android gives no chip). */
class Hardware(val manufacturer: String, val model: String, val chip: String)

/**
 * A boot animation's last frame: a drawable resource, drawn at [left], [top] screen px on flat night,
 * and the line under the wordmark it shows ([line], "On Aqara S1 Plus").
 */
class BootRest(val drawable: Int, val left: Int, val top: Int, val line: String)
