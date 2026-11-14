/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import android.content.Context
import android.view.View

/**
 * "What may the camera do?": two cards, Presence only and Presence + snapshots (#117's approved design).
 * The frame's own toolkit draws them (the S1 app's `CameraUseCards`); the camera's page and setup's
 * Camera step use the same view. Main thread.
 */
interface CameraUsePicker {
    val view: View

    /** A card was tapped: the picker shows it lit at once. */
    var onPick: ((CameraUse) -> Unit)?

    /** Shows [chosen] lit; [enabled] false dims both and ignores taps (privacy, no permission). */
    fun bind(chosen: CameraUse, enabled: Boolean)
}

/** Makes a [CameraUsePicker]. */
fun interface CameraUsePickers {
    fun create(context: Context): CameraUsePicker
}
