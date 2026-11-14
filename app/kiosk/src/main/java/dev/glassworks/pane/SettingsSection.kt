/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import android.view.View

/**
 * One section of the panel's settings. The panel's settings menu draws it, and the Manage page (#33)
 * will draw the same sections in a browser, so a section is data: typed items with their values and how
 * to save them. Only [SettingsItem.Native] is panel-only.
 */
class SettingsSection(
    /** Stable, for links and tests (`dashboard`, `screen`, a pane's id). */
    val id: String,
    /** Its name in the menu's list (`Radio`). */
    val title: String,
    val items: List<SettingsItem>,
    /** The heading of its page, when it says more than [title] (`Zigbee / Thread radio`). */
    val heading: String = title,
    /**
     * A pane's on/off, in the page's header at top right (#63 round 2). The core makes it from the
     * pane's setup card ([PaneChoice]) unless the pane gives its own (the radio asks before turning off).
     */
    val master: SettingsItem.Switch? = null,
    /** The dot by its name in the nav: how the pane is doing now, or null for none. */
    val status: () -> SettingsStatus? = { null },
    /** Why the pane can't run here, or null: the nav greys it and the page says why. */
    val unavailable: () -> String? = { null },
)

/** A section's dot in the nav. Status colours mean status only: green, amber, rose. */
enum class SettingsStatus { OK, WORKING, PROBLEM }

/**
 * The panes' sections as every settings surface shows them (#63, #33, #67): each pane's own, with its
 * on/off as the section's [SettingsSection.master] when it has none: its setup card, else its
 * [PaneDefinition.switch]. A pane with an on/off but no section gets one that says so. Definition order.
 */
fun paneSections(definitions: List<PaneDefinition>): List<SettingsSection> = definitions.flatMap { d ->
    val choice = d.setupChoice ?: d.switch?.choice()
    val master = choice?.let { c ->
        // The card's apply can wait on the device (the radio): off the main thread.
        SettingsItem.Switch(c.title, c.detail, c.on) { on, done ->
            Thread({ done(runCatching { c.apply(on) }.getOrElse { it.message ?: "It didn't work." }) }, "gw-pane-switch").apply { isDaemon = true; start() }
        }
    }
    val own = d.settings()
    when {
        own.isNotEmpty() -> own.mapIndexed { i, s ->
            if (i > 0 || s.master != null || master == null) s
            else SettingsSection(s.id, s.title, s.items, s.heading, master, s.status, s.unavailable)
        }
        choice != null -> listOf(SettingsSection(
            d.id, choice.title, listOf(SettingsItem.Note { NO_SETTINGS }), master = master,
        ))
        else -> emptyList()
    }
}

/** The page of a pane with no settings of its own (#33's words). */
const val NO_SETTINGS = "This pane has no settings of its own yet. Its entities are in Home Assistant."

/** Where a settings section sits. The core's own come first and last; panes go in between. */
enum class SettingsPlace { DASHBOARD, SCREEN, NETWORK, PANES, LOG, SECURITY, ABOUT }

/**
 * The sections in the order every settings surface uses: the core's Dashboard, Screen and Network,
 * then [panes] in definition order, then Log, Security and About. Sections without items are left out.
 */
fun settingsOrder(core: Map<SettingsPlace, SettingsSection>, panes: List<SettingsSection>): List<SettingsSection> =
    SettingsPlace.entries.flatMap { p -> if (p == SettingsPlace.PANES) panes else listOfNotNull(core[p]) }
        .filter { it.items.isNotEmpty() }

/**
 * A row in a [SettingsSection]. Values are read when the menu opens. Each change applies on its own,
 * as the person makes it (#63): `save` gets the new value and calls `done` with null, or with what went
 * wrong in words for the person at the panel. An item whose change only takes effect when the app
 * starts again says so with `restartsApp`; the menu then says so and offers to restart.
 */
sealed interface SettingsItem {

    enum class TextKind { PLAIN, ADDRESS, NUMBER, DECIMAL }

    /**
     * A value typed in: an address, a number, a list. The menu shows [shown] of the value under the
     * title ([hint] when it is empty) and edits it on its own.
     */
    class Text(
        val title: String,
        val hint: String,
        val kind: TextKind,
        val value: () -> String,
        val enabled: () -> Boolean = { true },
        /** The value as the row shows it ("After 2 minutes" for `120`). */
        val shown: (String) -> String = { it },
        val restartsApp: Boolean = false,
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    class Switch(
        val title: String,
        val detail: String,
        val value: () -> Boolean,
        val restartsApp: Boolean = false,
        /** Asked before the change to this value, or null to apply it at once. */
        val confirm: (Boolean) -> Confirm? = { null },
        val save: (Boolean, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** A question before a change: "Turn the radio off?", what it costs, the button's word. */
    class Confirm(val title: String, val detail: String, val action: String)

    /**
     * A number on a scale (#63 round 2: the presence range). [min] can mean "the default"; [shown] says
     * so ("The sensor's own"). The value is saved when the person lets go.
     */
    class Slider(
        val title: String,
        val value: () -> Float,
        val min: Float,
        val max: Float,
        val step: Float,
        val shown: (Float) -> String,
        val save: (Float, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** A length of time picked from [choices] in seconds, never typed (#63 round 2). 0 can mean never. */
    class Duration(
        val title: String,
        val value: () -> Int,
        val choices: List<Int>,
        val shown: (Int) -> String,
        val save: (Int, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /**
     * A daily window as `HH:MM-HH:MM`, or empty for none (the night window). Picked in [stepMin]
     * steps, never typed.
     */
    class TimeRange(
        val title: String,
        val value: () -> String,
        val shown: (String) -> String,
        val stepMin: Int = 30,
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** Opens something else: another app's settings, the licences. */
    class Link(val title: String, val detail: String, val open: (Context) -> Unit) : SettingsItem

    /** A line of status text. */
    class Note(val text: () -> String) : SettingsItem

    /** A name and a status line under it (the app's version). */
    class Info(val title: () -> String, val detail: () -> String) : SettingsItem

    /** Items under their own heading, in a card of their own. */
    class Group(val title: String, val items: List<SettingsItem>) : SettingsItem

    /**
     * A view only the panel draws, for what has no typed item yet. [key] names it: the menu makes it
     * once with [create] and keeps it while the activity lives. A section with a native item is drawn
     * without its own card: native views bring their surfaces, and its other items go in [Group]s.
     */
    class Native(val key: String, val create: (NativeHost) -> NativeView) : SettingsItem

    /** What the menu offers a [Native] view. Main thread. */
    interface NativeHost {
        val context: Context

        /** Adds [layer] over the whole menu (a dialog). */
        fun addLayer(layer: View)

        /** Scrolls [field] into sight above the keyboard. */
        fun reveal(field: View)

        fun hideKeyboard()
    }

    /** A native view and its hooks. Main thread. */
    interface NativeView {
        val view: View

        /** Read the state again: when the menu opens, and every couple of seconds while it is open. */
        fun refresh()

        /** The menu closes or is drawn again: close dialogs. */
        fun reset() {}

        /** Back was pressed: close this view's topmost dialog and say so, or false when none is open. */
        fun back(): Boolean = false
    }
}
