package sh.edm.s1kiosk.settings

import dev.glassworks.frame.Frame
import sh.edm.s1kiosk.frame.AqaraS1Plus

/**
 * How the kiosk settings menu ([KioskSettingsOverlay]) is asked for, and texts it shows. Pure, for tests.
 * What is in it: [CoreSettings] and the panes' definitions.
 *
 * Ways in: five taps in the top-left corner, the kiosk's notification in the shade (an intent extra,
 * [EXTRA]), or `adb shell am start -n sh.edm.s1kiosk/.MainActivity --ez kiosk_settings true` (the older
 * `--ez settings true` still works). Anyone at the panel can pull the shade down, so the menu is as
 * "hidden" as the corner gesture.
 */
object SettingsMenu {
    /** Boolean intent extra: open MainActivity with the menu shown (the notification sets it). */
    const val EXTRA = "kiosk_settings"
    /** The earlier name (`--ez settings true`), kept working. */
    const val LEGACY_EXTRA = "settings"

    /** True when [boolExtra] (name -> value, null = absent) asks for the menu. */
    fun requested(boolExtra: (String) -> Boolean?): Boolean =
        boolExtra(EXTRA) == true || boolExtra(LEGACY_EXTRA) == true

    /** The device's own settings app, last on the Screen page, quieter than the rest (#63). */
    const val AQARA_TITLE = "Aqara's own settings"
    const val AQARA_DETAIL = ""
    /** The same link on a device that isn't an S1: the system's Settings app. */
    const val ANDROID_TITLE = "Android settings"

    /** The link's name on [frame]. */
    fun deviceSettingsTitle(frame: Frame): String = if (frame is AqaraS1Plus) AQARA_TITLE else ANDROID_TITLE
}
