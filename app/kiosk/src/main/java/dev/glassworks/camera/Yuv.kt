/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import java.nio.ByteBuffer

/**
 * A YUV_420_888 image's three planes as NV21 (the Y plane, then V and U interleaved), which
 * `YuvImage` compresses to JPEG. The planes keep their own strides: rows padded past [width], and
 * chroma either planar (pixel stride 1) or already interleaved (pixel stride 2).
 */
fun yuvToNv21(
    width: Int, height: Int,
    y: ByteBuffer, yRowStride: Int,
    u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
): ByteArray {
    val out = ByteArray(width * height * 3 / 2)
    var o = 0
    for (row in 0 until height) {
        val at = row * yRowStride
        for (x in 0 until width) out[o++] = y.get(at + x)
    }
    for (row in 0 until height / 2) {
        val at = row * uvRowStride
        for (x in 0 until width / 2) {
            val i = at + x * uvPixelStride
            out[o++] = v.get(i)
            out[o++] = u.get(i)
        }
    }
    return out
}

/**
 * How far to turn a still so it stands upright: the sensor's mounting [sensorDegrees], the screen's
 * [displayDegrees] (`Display.getRotation` as degrees), and a front camera turning the other way.
 */
fun stillRotation(sensorDegrees: Int, displayDegrees: Int, front: Boolean): Int =
    if (front) (sensorDegrees + displayDegrees) % 360 else (sensorDegrees - displayDegrees + 360) % 360
