/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import dev.glassworks.platform.Platform
import java.text.Normalizer
import java.util.Locale

/**
 * What a device calls itself to Home Assistant (#260). The node name follows ESPHome's
 * `name_add_mac_suffix`: what the device is, then the last three bytes of the MAC it reports, so two
 * devices never share one. HA's config entry and the mDNS service take the node name. The device
 * page shows the friendly name, until the user renames the device there.
 */
object DeviceName {
    /** An mDNS label, and so a host name, holds at most 63 characters. */
    private const val MAX_LENGTH = 63
    private val MAC = Regex("^(?:[0-9A-Fa-f]{2}:){3}([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2}):([0-9A-Fa-f]{2})$")
    private val MARKS = Regex("\\p{M}+")
    private val NOT_HOSTNAME = Regex("[^a-z0-9]+")

    /** [frameName] ([Frame.displayName]) on a frame [NamedBy.FRAME], unless it is blank; else [model]. */
    fun friendly(namedBy: NamedBy, frameName: String?, model: String): String =
        frameName?.trim()?.takeIf { namedBy == NamedBy.FRAME && it.isNotEmpty() } ?: model.trim().ifEmpty { "Android" }

    /**
     * `aqara-s1-plus-a1b2c3`, `pixel-tablet-a1b2c3`: [frameId] on a frame [NamedBy.FRAME], else [model] as a
     * host name, then the MAC's last three bytes. Null when [mac] isn't `AA:BB:CC:DD:EE:FF`.
     */
    fun node(namedBy: NamedBy, frameId: String, model: String, mac: String): String? {
        val suffix = MAC.matchEntire(mac)?.groupValues?.drop(1)?.joinToString("")?.lowercase(Locale.ROOT) ?: return null
        val what = hostname(if (namedBy == NamedBy.FRAME) frameId else model).ifEmpty { "android" }
        return what.take(MAX_LENGTH - suffix.length - 1).trimEnd('-') + "-" + suffix
    }

    /** Lowercase `a-z0-9-`, accents dropped ("Galaxy Tab Ä" is `galaxy-tab-a`), no dash at either end. */
    private fun hostname(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)
            .replace(NOT_HOSTNAME, "-").trim('-')
}

/** What Home Assistant shows for this device. */
fun Frame.friendlyName(): String = DeviceName.friendly(namedBy, displayName, Platform.model)

/** This device's ESPHome node name, for the [mac] it reports. Null when [mac] isn't `AA:BB:CC:DD:EE:FF`. */
fun Frame.nodeName(mac: String): String? = DeviceName.node(namedBy, board, Platform.model, mac)
