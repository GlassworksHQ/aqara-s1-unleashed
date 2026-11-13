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
    val title: String,
    val items: List<SettingsItem>,
)

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
 * A row in a [SettingsSection]. Values are read when the menu opens. Changes are saved together
 * ("Save & restart"): each item's `save` gets the new value and calls `done` with null, or with what
 * went wrong in words for the person at the panel. The core waits for every `done` before it restarts.
 */
sealed interface SettingsItem {

    enum class TextKind { PLAIN, ADDRESS, NUMBER, DECIMAL }

    /** A value typed in: an address, a number, a list. [narrow]: two narrow fields in a row share a line. */
    class Text(
        val title: String,
        val hint: String,
        val kind: TextKind,
        val value: () -> String,
        val narrow: Boolean = false,
        val enabled: () -> Boolean = { true },
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    class Switch(
        val title: String,
        val detail: String,
        val value: () -> Boolean,
        val save: (Boolean, done: (String?) -> Unit) -> Unit,
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
    }
}

/** Saves [changes] one after another, in order, then calls [finished] with every problem reported. */
fun saveAll(changes: List<(done: (String?) -> Unit) -> Unit>, finished: (problems: List<String>) -> Unit) {
    val problems = ArrayList<String>()
    fun next(i: Int) {
        if (i == changes.size) { finished(problems); return }
        changes[i] { p -> p?.let(problems::add); next(i + 1) }
    }
    next(0)
}
