/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.frame

import android.content.Context
import android.content.Intent
import android.provider.Settings
import dev.glassworks.pane.Pane
import dev.glassworks.pane.PaneContext
import dev.glassworks.platform.Platform
import java.util.concurrent.ScheduledExecutorService

/**
 * Any Android device that has no frame of its own (frames/generic-android): a tablet, an old phone.
 * It goes by its own maker and model, has what its features say, and has no panes of its own.
 */
class GenericAndroid(private val context: Context) : Frame {
    override val board = "generic-android"

    override val namedBy = NamedBy.MODEL

    override val hardware = Hardware(Platform.manufacturer, Platform.model, Platform.hardware)

    override val provides: Set<Capability> by lazy { Capability.ofDevice(context.packageManager::hasSystemFeature) }

    override val presence = null

    override val light = null

    override fun systemSettingsIntents() = listOf(Intent(Settings.ACTION_SETTINGS))

    override fun panes(ctx: PaneContext, device: ScheduledExecutorService) = emptyList<Pane>()
}
