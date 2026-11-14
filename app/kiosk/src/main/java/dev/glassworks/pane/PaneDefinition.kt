/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Intent
import dev.glassworks.frame.Capability

/**
 * What the core knows about a pane for the whole process, also while the pane isn't running: the
 * steps it adds to setup, its settings sections and its adb extras. The running feature is a [Pane],
 * built per device run; a definition outlives it. The app makes the definitions once, and the frame
 * adds its own ([dev.glassworks.frame.Frame.paneDefinitions]).
 */
interface PaneDefinition {
    /** The [Pane.id] of the pane this describes. */
    val id: String

    /** The hardware it needs, as its pane.json's `requires`. On a frame without all of it, the pane is [CantRun]. */
    val requires: Set<Capability> get() = emptySet()

    /**
     * What first-boot setup waits for, in order. The person answers them on the Panes step, with the
     * pane's [setupChoice]; the installer answers them over the setup channel ([SetupStep.ops]).
     */
    val setupSteps: List<SetupStep> get() = emptyList()

    /**
     * The pane's on/off that the core keeps (#67): the shell runs the pane only while it is on. Null for a
     * pane that is always on, or that keeps its own on/off (the radio's is its mode, in [setupChoice]).
     */
    val switch: PaneSwitch? get() = null

    /** The pane's card on setup's Panes step, or null to leave it off. By default its [switch]'s, when that has one. */
    val setupChoice: PaneChoice? get() = switch?.takeIf { it.inSetup }?.choice()

    /**
     * The pane's sections in the settings menu, as they are now. Called each time the menu opens, so a
     * section can depend on whether the pane runs. The core places them after its own Dashboard, Screen
     * and Network sections, in definition order.
     */
    fun settings(): List<SettingsSection> = emptyList()

    /**
     * The same, for a page that shows the running pane (#212): [running] is the pane the shell built,
     * also while it is off, or null while the panel's device isn't running. Override this one or
     * [settings]. The core shows a pane's sections only while its [switch] is on.
     */
    fun settings(running: Pane?): List<SettingsSection> = settings()

    /** Intent extras (`adb shell am start … --ez sendspin_flac false`) that change this pane's settings. Its [switch] has its own. */
    val extras: List<IntentExtra> get() = emptyList()
}

/**
 * [definition] on a frame without the hardware it [PaneDefinition.requires] (#23, #235): one page that says
 * why ([reason]) and nothing that could start it, so no on/off, setup card, setup step or extra. The shell
 * builds no [Pane] for it.
 */
class CantRun(private val definition: PaneDefinition, private val reason: String) : PaneDefinition {
    override val id get() = definition.id
    override val requires get() = definition.requires

    override fun settings(running: Pane?): List<SettingsSection> {
        val own = definition.settings(null).firstOrNull()
        val title = own?.title ?: definition.switch?.title ?: id
        val needs = "Needs ${requires.joinToString(" and ") { it.noun }}."
        return listOf(SettingsSection(id, title, listOf(SettingsItem.Note { needs }), own?.heading ?: title, unavailable = { reason }))
    }

    companion object {
        /** [definition] as it is on a frame that [provides] what it needs, else [CantRun] with the first thing missing. */
        fun unless(definition: PaneDefinition, provides: Set<Capability>): PaneDefinition =
            (definition.requires - provides).firstOrNull()?.let { CantRun(definition, it.missing) } ?: definition
    }
}

/** Every intent extra of [this] panes: each [PaneDefinition.switch]'s (`--ez voice_enabled false`), then the pane's own. */
fun List<PaneDefinition>.intentExtras(): List<IntentExtra> = flatMap { listOfNotNull(it.switch?.extra()) + it.extras }

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
