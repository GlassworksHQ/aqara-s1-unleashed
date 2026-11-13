/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Intent

/**
 * What the core knows about a pane for the whole process, also while the pane isn't running: the
 * steps it adds to setup, its settings sections and its adb extras. The running feature is a [Pane],
 * built per device run; a definition outlives it. The app makes the definitions once, and the frame
 * adds its own ([dev.glassworks.frame.Frame.paneDefinitions]).
 */
interface PaneDefinition {
    /** The [Pane.id] of the pane this describes. */
    val id: String

    /** Steps for first-boot setup, in order. They come after Home Assistant and before Sign in. */
    val setupSteps: List<SetupStep> get() = emptyList()

    /**
     * The pane's sections in the settings menu, as they are now. Called each time the menu opens, so a
     * section can depend on whether the pane runs. The core places them after its own Dashboard, Screen
     * and Network sections, in definition order.
     */
    fun settings(): List<SettingsSection> = emptyList()

    /** Intent extras (`adb shell am start … --ez voice_enabled false`) that change this pane's settings. */
    val extras: List<IntentExtra> get() = emptyList()
}

/**
 * One adb intent extra that changes a setting. [apply] reads it from an intent if it is there and
 * returns whether it was. [restartsApp]: the setting is only read at start, so the app restarts after it.
 */
class IntentExtra private constructor(
    val name: String,
    val restartsApp: Boolean,
    private val read: (Intent) -> Unit,
    private val present: (Intent) -> Boolean,
) {
    fun apply(intent: Intent): Boolean {
        if (!present(intent)) return false
        read(intent)
        return true
    }

    companion object {
        /** `--ez name true|false`; [default] is used when the extra has another type. */
        fun bool(name: String, default: Boolean, restartsApp: Boolean = false, set: (Boolean) -> Unit) =
            IntentExtra(name, restartsApp, { set(it.getBooleanExtra(name, default)) }, { it.hasExtra(name) })

        /** `--ef name 0.5`; [default] is used when the extra has another type. */
        fun float(name: String, default: Float, restartsApp: Boolean = false, set: (Float) -> Unit) =
            IntentExtra(name, restartsApp, { set(it.getFloatExtra(name, default)) }, { it.hasExtra(name) })

        /** `--es name value`; only a string counts. */
        fun string(name: String, restartsApp: Boolean = false, set: (String) -> Unit) =
            IntentExtra(name, restartsApp, { i -> i.getStringExtra(name)?.let(set) }, { it.getStringExtra(name) != null })
    }
}

/** Applies every extra in [intent] that one of [extras] knows. True when one of them needs an app restart. */
fun List<IntentExtra>.applyFrom(intent: Intent): Boolean {
    var restart = false
    for (e in this) if (e.apply(intent) && e.restartsApp) restart = true
    return restart
}
