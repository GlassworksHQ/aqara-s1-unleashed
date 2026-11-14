/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.pane

import android.content.Context
import android.view.View
import sh.edm.esphome.ApiEntity
import sh.edm.esphome.ButtonEntity
import sh.edm.esphome.EventEntity

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
    /**
     * How the pane is doing now, in its own words (the radio's role, Voice's wake word), or null when it
     * has none: [paneState] then says On or Off from its [master]. The chip after its name in the panel's
     * rail and on the Manage page (#243).
     */
    val status: () -> PaneState? = { null },
    /** Why the pane can't run here, or null: the nav greys it and the page says why. */
    val unavailable: () -> String? = { null },
    /**
     * What its items read, when that can be replaced while the menu is open: a pane's running [Pane],
     * which the shell builds again when the device restarts. Another one draws the page again.
     */
    val source: Any? = null,
    /**
     * The object ids of the pane's entities this page already covers: it shows them, or leaves them out
     * on purpose. Set, and the core ends the page with "In Home Assistant" for the pane's other
     * entities, or adds nothing when none is left, so no entity is listed twice (#212). Null (the
     * default): the core adds nothing to this page.
     */
    val covers: Set<String>? = null,
)


/**
 * The panes' sections as every settings surface shows them (#63, #33, #67, #212): each pane's own, with
 * its on/off as the section's [SettingsSection.master] when it has none: its setup card, else its
 * [PaneDefinition.switch]. [running] gives the pane the shell built, or null while the panel's device
 * isn't running. A pane with no section of its own gets one with its entities ([inHomeAssistant]). A
 * page of its own that says what it covers ([SettingsSection.covers]) ends with the pane's other
 * entities. A pane whose [PaneDefinition.switch] is off shows what that means ([PaneSwitch.offNote])
 * and its entities, unavailable. Definition order.
 */
fun paneSections(definitions: List<PaneDefinition>, running: (String) -> Pane? = { null }): List<SettingsSection> = definitions.flatMap { d ->
    val choice = d.setupChoice ?: d.switch?.choice()
    val master = choice?.let { c ->
        // The card's apply can wait on the device (the radio): off the main thread.
        SettingsItem.Switch(c.title, c.detail, c.on) { on, done ->
            Thread({ done(runCatching { c.apply(on) }.getOrElse { it.message ?: "It didn't work." }) }, "gw-pane-switch").apply { isDaemon = true; start() }
        }
    }
    val pane = running(d.id)
    val off = d.switch?.takeIf { !it.on }
    val own = if (off != null) emptyList() else d.settings(pane)
    when {
        own.isNotEmpty() -> {
            // The pane's entities no page covers, once, at the end of the first page that takes part.
            val covered = own.mapNotNull { it.covers }.flatten().toSet()
            val rest = inHomeAssistant(pane) { it.objectId !in covered }
            val first = own.indexOfFirst { it.covers != null }
            own.mapIndexed { i, s ->
                SettingsSection(
                    s.id, s.title, if (i == first && rest != null) s.items + rest else s.items, s.heading,
                    if (i > 0 || s.master != null) s.master else master, s.status, s.unavailable, source = pane, covers = s.covers,
                )
            }
        }
        choice != null -> listOf(SettingsSection(
            d.id, choice.title,
            listOfNotNull(off?.let { s -> SettingsItem.Note { s.offNote } }, inHomeAssistant(pane, on = off == null))
                .ifEmpty { listOf(SettingsItem.Note { NOT_RUNNING }) },
            master = master, source = pane,
        ))
        else -> emptyList()
    }
}

/**
 * The "In Home Assistant" list of [pane]'s entities (#212): each one's name and its state as Home
 * Assistant shows it, `Unavailable` while the pane is off ([on] false). Buttons and events have no
 * state and are left out. Null when the pane isn't built or has no such entity.
 */
fun inHomeAssistant(pane: Pane?, on: Boolean = true, only: (ApiEntity) -> Boolean = { true }): SettingsItem.States? {
    if (pane == null) return null
    // Read again with the rows: a pane's list can change while the page is open (a wall base mounted).
    fun shown() = pane.entities().filter { it !is ButtonEntity && it !is EventEntity && only(it) }
    if (shown().isEmpty()) return null
    return SettingsItem.States(IN_HOME_ASSISTANT, rows = {
        shown().map { e -> SettingsItem.StateRow(e.name, if (on) e.shownState() ?: UNKNOWN else UNAVAILABLE, e.disabledByDefault) }
    })
}

const val IN_HOME_ASSISTANT = "In Home Assistant"
/** A pane's page while the panel's Home Assistant device isn't running, so its entities can't be read. */
const val NOT_RUNNING = "The panel's Home Assistant device isn't running yet. Its state shows here once it is."
/** Home Assistant's words for an entity with no state now, and for one whose device can't give it. */
const val UNKNOWN = "Unknown"
const val UNAVAILABLE = "Unavailable"

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

    /**
     * A sign-in: a user name and a password, saved together (the camera stream's, #252; the panel makes one
     * per device). The password is hidden: the row says [shown] of [isSet], and a surface reads [reveal] only
     * when the person asks to see it (Show), so it can be typed into Protect, Home Assistant's ONVIF or Frigate.
     * Never in a log, an entity or the diagnostics. `save` gets the pair, or null for Clear;
     * [SettingsChange.signIn] checks it first.
     */
    class SignIn(
        val title: String,
        val isSet: () -> Boolean,
        val user: () -> String? = { null },
        val reveal: () -> String? = { null },
        val shown: (Boolean) -> String = { if (it) "Set" else "Not set" },
        val save: (Pair<String, String>?, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /** Opens something else: another app's settings, the licences. */
    class Link(val title: String, val detail: String, val open: (Context) -> Unit) : SettingsItem

    /** A line of status text. */
    class Note(val text: () -> String) : SettingsItem

    /** A name and a status line under it (the app's version, what plays now). Read again while the page is open. */
    class Info(val title: () -> String, val detail: () -> String) : SettingsItem

    /** Items under their own heading, in a card of their own. */
    class Group(val title: String, val items: List<SettingsItem>) : SettingsItem

    /**
     * One of a few [options] (id to its name), picked from a list, never typed (the wake word). The row
     * shows the picked one's name.
     */
    class Choice(
        val title: String,
        val value: () -> String,
        val options: () -> List<Pair<String, String>>,
        val save: (String, done: (String?) -> Unit) -> Unit,
    ) : SettingsItem

    /**
     * Read-only states under [title], one line each: a name on the left, its value on the right
     * ([inHomeAssistant]). [rows] is read again every couple of seconds while the page is open. A row
     * [StateRow.offByDefault] is dimmed, with a line under the list that says why.
     */
    class States(val title: String, val rows: () -> List<StateRow>) : SettingsItem

    class StateRow(val name: String, val value: String, val offByDefault: Boolean = false)

    /** A line under the page's heading that says how the pane is doing now. [ok]: it works (green), else quiet grey. Read again while open. */
    class Status(val text: () -> String, val ok: () -> Boolean) : SettingsItem

    /** Readings as tiles, [columns] to a row (the diagnostics' six, the wall base's strip). Read again while the page is open. */
    class Tiles(val columns: Int, val tiles: () -> List<Tile>) : SettingsItem

    /**
     * One reading: its [name], the [value] large with its [unit], a [detail] line, and under it either its
     * last hour ([history], oldest first: a line) or how full it is ([meter], 0 to 1).
     */
    class Tile(
        val name: String,
        val value: String,
        val unit: String = "",
        val detail: String = "",
        val history: List<Float>? = null,
        val meter: Float? = null,
    )

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
