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
