/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

/**
 * What the camera pane runs now. [detect]: the motion detector, for the screen and the ONVIF events.
 * [feedScreen]: its readings go to the screen policy, because the device has no presence sensor of its
 * own. [stream]: the RTSP and ONVIF servers. Stills need nothing running: the camera entity asks for
 * one when Home Assistant does, and [CameraPolicy] decides if it may leave.
 */
data class CameraPlan(val detect: Boolean, val feedScreen: Boolean, val stream: Boolean) {
    companion object {
        val NOTHING = CameraPlan(detect = false, feedScreen = false, stream = false)

        /**
         * [permitted]: Android allows the camera. [ownSensor]: the frame's own presence sensor is on this
         * device, so the screen doesn't use the camera (the approved design). [signedIn]: the stream has
         * a username and password; there is no default.
         */
        fun of(policy: CameraPolicy, permitted: Boolean, ownSensor: Boolean, signedIn: Boolean): CameraPlan {
            if (!permitted || policy.privacy) return NOTHING
            val stream = policy.streamMayRun && signedIn
            return CameraPlan(detect = !ownSensor || stream, feedScreen = !ownSensor, stream = stream)
        }
    }
}
