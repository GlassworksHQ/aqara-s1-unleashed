/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.camera

import java.security.SecureRandom

/**
 * The stream's sign-in, made for each panel the first time its stream is turned on without one
 * (maintainer, 2026-11-14): never a shared default, never made again by itself. The username is the
 * same on every panel, so it is easy to type into Home Assistant or UniFi Protect; the password is
 * what's secret. Never logged, never in an entity or the diagnostics.
 */
object StreamSignIn {
    const val USERNAME = "glassworks"
    const val LENGTH = 20

    /** Letters and digits without the ones read wrong off a screen: no 0 O o, 1 I i l. About 5.8 bits each. */
    const val ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun generate(random: SecureRandom): Pair<String, String> =
        USERNAME to String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
