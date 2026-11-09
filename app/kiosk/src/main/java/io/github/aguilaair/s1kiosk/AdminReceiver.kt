package io.github.aguilaair.s1kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Device admin with only the "force-lock" policy, used to switch the screen off immediately
 * (DevicePolicyManager.lockNow()). Optional; activate with
 *   adb shell dpm set-active-admin io.github.aguilaair.s1kiosk/.AdminReceiver
 * and deactivate with the `--ez remove_admin true` intent extra (required before uninstalling).
 */
class AdminReceiver : DeviceAdminReceiver()
