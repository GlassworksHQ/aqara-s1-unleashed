/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * What the running Android can do. This is the only place in the app that reads
 * `Build.VERSION.SDK_INT` (`PlatformConfinementTest` fails the build otherwise). Every query names
 * a capability, and says why it has the boundary it has. Callers never see an API level.
 *
 * The queries live in an open class so a test can build one for any level without Robolectric:
 * [Platform] is the one for the device the app runs on.
 */
open class PlatformApi internal constructor(
    /** The API level this instance answers for. Use it for logs, not for decisions. */
    val apiLevel: Int,
) {
    /** `NotificationChannel` exists, and a notification must name one (Android 8). */
    val hasNotificationChannels: Boolean get() = apiLevel >= 26

    /**
     * `Context.startForegroundService` exists, and a background app may only start a service this
     * way (Android 8). Below it, `startService` is the call.
     */
    val hasForegroundServiceStart: Boolean get() = apiLevel >= 26

    /** `layoutInDisplayCutoutMode` exists (Android 9). */
    val hasDisplayCutoutMode: Boolean get() = apiLevel >= 28

    /** `PackageInfo.signingInfo` exists (Android 9). Below it, only `signatures` is filled. */
    val hasSigningInfo: Boolean get() = apiLevel >= 28

    /** `PackageInfo.longVersionCode` exists (Android 9). Below it, `versionCode` is an Int. */
    val hasLongVersionCode: Boolean get() = apiLevel >= 28

    /** `Settings.Panel` (the Wi-Fi sheet over the app) exists (Android 10). */
    val hasSettingsPanel: Boolean get() = apiLevel >= 29

    /** `Font.Builder` can load a variable font from memory (Android 10). */
    val hasFontBuilder: Boolean get() = apiLevel >= 29

    /** `WindowInsetsController` and `setDecorFitsSystemWindows` exist (Android 11). */
    val hasWindowInsetsController: Boolean get() = apiLevel >= 30

    /**
     * The flag to add to a `PendingIntent` that the system must be able to fill in (the package
     * installer adds the session result). From Android 12 the mutability must be stated; below it
     * the flag does not exist and every PendingIntent is mutable.
     */
    val mutablePendingIntentFlag: Int get() = if (apiLevel >= 31) PendingIntent.FLAG_MUTABLE else 0

    /**
     * Registers a receiver for a system broadcast that apps outside this one may also send. From
     * Android 13 the flag is required. Below it, a receiver registered without one is exported.
     */
    fun registerExportedReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (apiLevel >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    /** An [Intent] extra, read with the typed getter from Android 13 and the deprecated one below. */
    fun intentExtra(intent: Intent, name: String): Intent? =
        if (apiLevel >= 33) intent.getParcelableExtra(name, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(name)
}

/** The queries for the Android this app runs on. */
object Platform : PlatformApi(Build.VERSION.SDK_INT) {
    /**
     * The ABIs this device runs, preferred first (`Build.SUPPORTED_ABIS`). The S1's 32-bit userspace
     * gives `armeabi-v7a, armeabi`; an arm64 device gives `arm64-v8a` first.
     */
    val supportedAbis: List<String> = Build.SUPPORTED_ABIS.toList()
}
