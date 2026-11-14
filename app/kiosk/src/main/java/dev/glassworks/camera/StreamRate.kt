/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

/**
 * The target-rate range ([ranges]: the camera's `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`) to ask for
 * while streaming: one whose top is the encoder's [want], with the highest floor, so the rate stays
 * steady. Null when the camera offers none: it then runs at its own rate.
 */
fun fpsRangeFor(ranges: List<IntRange>?, want: Int): IntRange? =
    ranges?.filter { it.last == want }?.maxByOrNull { it.first }

/** The rate the stream's SDP announces (`a=framerate`): the top of the range asked for, or 0 for none. */
fun announcedFps(range: IntRange?): Int = range?.last ?: 0
