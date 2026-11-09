# S1 Kiosk (MIT)

A minimal, clean-room **GeckoView** kiosk browser for the Aqara Panel Hub S1 Plus
(Android 10, 32-bit `armeabi-v7a`, 1440×720). It shows a Home Assistant dashboard
full-screen and can replace the Aqara launcher as the HOME app.

Why GeckoView: the panel's system WebView is Chromium 74, which can't render the
current Home Assistant frontend. GeckoView ships its own up-to-date engine inside the APK.

## Features

- Full-screen immersive `GeckoView`, landscape, `FLAG_KEEP_SCREEN_ON`.
- Loads a configurable URL (default `http://192.168.1.100:8123/tablet-home`).
- **Microphone/camera work over plain `http://`.** The dashboard host is added to
  Gecko's `dom.securecontext.allowlist`, so `window.isSecureContext` is true and
  `navigator.mediaDevices.getUserMedia()` / AudioWorklet are available. Mic, camera and
  autoplay are granted without prompts (Gecko `PermissionDelegate` + Android `RECORD_AUDIO`).
  Verified on the panel: `getUserMedia({audio:true})` returns a live 48 kHz track.
- HOME + LAUNCHER intent filters (can be the default launcher), `BootReceiver`
  (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`).
- Back button navigates inside the page and never leaves the kiosk.
- If the page fails to load (HA restarting, Wi-Fi not up yet), it retries every 10 s.
  Crashed or killed content processes are restarted.
- Hidden settings: **tap the top-left corner 5 times within 3 s** (or `--ez settings true`) to set the
  URL, zoom, secure hosts, kiosk/screen options, open Android settings, or reload. The dialog also shows
  live sensor status.
- **Kiosk mode for HA**: a built-in Gecko web extension (`assets/extension/`) hides the HA dashboard
  header and the sidebar. Add `?kiosk=0` to the URL to show them for one page; while you're editing a
  dashboard they're left visible.
- **Screen policy from the panel's own sensors** (`ScreenController`, see
  [docs/sensors.md](../docs/sensors.md)): wake on approach (VI5300 ToF), off after idle, a night window
  with a shorter timeout and lower brightness cap, auto-brightness from the MK6212 light sensor. Runs in
  a foreground `KioskService`, so it works while the screen is off. It replaces Aqara's DND
  screen-off ([docs/screen-policy.md](../docs/screen-policy.md)).
- **Home Assistant integration as an ESPHome device** (native API, voice assistant, screen and kiosk
  controls), see below and [docs/esphome.md](../docs/esphome.md).
- **Wi-Fi setup over Bluetooth (Improv via BLE)**: a panel with no network (30 s after boot), or one
  told to (`--ez improv true` / the HA button "Start Wi-Fi setup (Bluetooth)"), advertises the Improv
  service for 10 minutes, so HA's `improv_ble` integration or improv-wifi.com can send it Wi-Fi
  details after someone taps **Allow** on the panel. See below and
  [docs/onboarding.md](../docs/onboarding.md) (step 1).
- The page sees live panel state as `window.s1kiosk` (`{presence, proximityRaw, lux, screenOn, night}`)
  plus a `s1kiosk-state` event, so dashboards can react to presence.

## Build

Requirements: JDK 21, Android SDK with **platform `android-37.2`** (`sdkmanager "platforms;android-37.2"`).

```sh
cd app
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # or set ANDROID_HOME
./gradlew :kiosk:assembleDebug
# -> kiosk/build/outputs/apk/debug/kiosk-debug.apk
```

Toolchain: Gradle 9.8.1 (wrapper), AGP 9.3.3, Kotlin 2.4.20, compileSdk 37.2, targetSdk 34, minSdk 26.
The new toolchain is forced by GeckoView 159. Its AAR metadata requires compileSdk ≥ 37.2.
Its transitive `androidx.core` 1.19 requires AGP ≥ 9.1, and it is built with Kotlin 2.4.
Older AGP 8.x / Kotlin 1.9 setups fail at `checkDebugAarMetadata`.

The APK is ~98 MB (debug, unminified, Gecko native libs for `armeabi-v7a` only).

## Install & make it the launcher

```sh
export ANDROID_SERIAL=<your panel serial>
adb install -r -g kiosk/build/outputs/apk/debug/kiosk-debug.apk
adb shell cmd package set-home-activity io.github.aguilaair.s1kiosk/.MainActivity
# optional: lets the BootReceiver start the activity from the background on Android 10
adb shell appops set io.github.aguilaair.s1kiosk SYSTEM_ALERT_WINDOW allow
```

Optional extras (recommended):

```sh
# instant screen-off (DevicePolicyManager.lockNow); undo with --ez remove_admin true
adb shell dpm set-active-admin io.github.aguilaair.s1kiosk/.AdminReceiver
# lets the app set the proximity sensor's presence range
adb shell pm grant io.github.aguilaair.s1kiosk android.permission.WRITE_SECURE_SETTINGS
# stop Aqara's 22:00-07:00 DND from forcing the screen off (undo: ... 7 i32 1 i32 0)
adb shell service call A_device_control 7 i32 0 i32 0
```

Then de-bloat with [`tools/debloat/debloat.sh`](../tools/debloat/debloat.sh)
(`disable`, then `disable-launcher`).

**Undo:** `debloat.sh enable`, then
`adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity`,
and optionally `adb shell am start -n io.github.aguilaair.s1kiosk/.MainActivity --ez remove_admin true`
followed by `adb uninstall io.github.aguilaair.s1kiosk` (an active device admin can't be uninstalled).

## Configure over ADB

```sh
A="adb shell am start -n io.github.aguilaair.s1kiosk/.MainActivity"
$A --es url http://homeassistant.local:8123/my-dashboard   # set + persist the URL
$A --es open http://192.168.1.100:8123/profile/general      # navigate once (not persisted)
$A --ef zoom 1.25          # CSS px ratio; 0 = auto (device density 1.5). Restarts the app.
$A --es secure_hosts a.lan,b.lan   # extra secure-context hosts. Restarts the app.
$A --ez remote_debug true  # Gecko remote debugging. Restarts the app.
$A --ez reload true
$A --ez settings true      # open the settings dialog
$A --ez hide_header false  # show HA header/sidebar
$A --es screen_mode always # or presence (default)
$A --ei idle_off_sec 120 --ei night_idle_off_sec 20 --es night_window 22:00-07:00
$A --ef presence_range_m 1.0   # VI5300 range (needs WRITE_SECURE_SETTINGS)
$A --es screen off         # or on
$A --ez restart true       # restart the app process
$A --es wake_model alexa_v0.1.tflite --ef wake_threshold 0.6 --ef mic_gain 2.0 --ez mic_muted false
$A --es api_key $(openssl rand -base64 32) --ez api_encryption true   # ESPHome API Noise encryption
```

Do **not** change `wm density` to scale the UI. On this panel it breaks GeckoView's surface
size until reboot. Use `zoom` instead.

## Wi-Fi setup over Bluetooth (Improv)

```sh
I="adb shell am start -n io.github.aguilaair.s1kiosk/.improv.ImprovActivity"
$I --ez improv true        # start provisioning mode (10 min); false = stop
$I --ez improv_auto false  # don't auto-start when there's no network after boot (default true)
$I --ez improv_proximity_auth true   # also authorize by approaching the panel (default false)
$I --ez release_device_owner true    # escape hatch: clearDeviceOwnerApp()
adb logcat -s S1Kiosk.Improv          # BLE support report at startup, states, RPCs
```

`ImprovActivity` only accepts callers holding `WRITE_SECURE_SETTINGS` (i.e. `adb shell`).
Joining the new network uses `WifiManager.addNetwork`, which Android 10 allows because the app holds
the SYSTEM_ALERT_WINDOW app-op (see the install step), or because it's device owner
(`adb shell dpm set-device-owner io.github.aguilaair.s1kiosk/.AdminReceiver`; release with the extra
above). Otherwise it falls back to a network suggestion the user must approve. Scanning and checking
the current SSID also need the location permission:
`adb shell pm grant io.github.aguilaair.s1kiosk android.permission.ACCESS_FINE_LOCATION` (already
granted by `adb install -g`, or self-granted when device owner). Details, sources and limits:
[docs/onboarding.md](../docs/onboarding.md).

## Debugging

Debug builds enable Gecko remote debugging. [`tools/gecko-eval`](../tools/gecko-eval/gecko_eval.py)
is a tiny Firefox Remote Debugging Protocol client that evaluates JS in the page:

```sh
adb forward tcp:6000 localabstract:io.github.aguilaair.s1kiosk/firefox-debugger-socket
python3 tools/gecko-eval/gecko_eval.py 'JSON.stringify({w: innerWidth, h: innerHeight, sc: isSecureContext})'
```

Gecko's prefs are written to `files/geckoview-config.yaml` at startup and logged under the
`S1Kiosk` tag. Page console output goes to logcat (`Isolated Web Content` / `GeckoConsole`).

> If the screen is blank/white and `innerWidth` is 0, the **display is off**. Gecko drops its
> surface while the screen sleeps. Check `adb shell dumpsys power | grep mWakefulness`.
> See [docs/screen-policy.md](../docs/screen-policy.md) for why the panel turns itself off.

## Home Assistant: ESPHome device + voice assistant

The app exposes the panel to Home Assistant as **one ESPHome device** over the ESPHome native API
(TCP **6053**, advertised as `_esphomelib._tcp`): an Assist satellite with an **on-device wake word**
(openWakeWord on TFLite, default "Hey Jarvis"), a media player, the screen as a light, presence and
illuminance sensors, and the kiosk/screen settings. It runs in `KioskService`, so it keeps listening
while the screen is off, and talking to it wakes the screen. See [docs/esphome.md](../docs/esphome.md).

```sh
tools/voice/fetch-models.sh            # downloads the openWakeWord models on the host and adb-pushes them
# HA: the panel is auto-discovered (Settings -> Devices & services -> ESPHome "S1 Panel" -> Add), or
#     Add integration -> ESPHome -> <panel IP>, port 6053
```

Entity ids look like `light.s1_panel_screen`, `select.s1_panel_screen_mode`,
`media_player.s1_panel_speaker`; pick the wake word and the Assist pipeline on the device page.
Encryption is off by default; enable it with `--es api_key <base64 32 bytes> --ez api_encryption true`
(a key is generated on first start and logged under `S1ESPHome`). Tune the mic with
`--ef mic_gain 2.0` / `--ef wake_threshold 0.6`, or turn the voice assistant off with
`--ez voice_enabled false` (the API and the other entities stay).

Not yet tested on the panel. The previous Wyoming satellite was verified there (HA connected,
`announce` TTS played, wake-word inference used about 40% of one core); remove its Wyoming entry
from HA when switching.

## License

MIT, see [LICENSE](LICENSE). Clean-room implementation. It contains no code from
`jxlarrea/kiosk-satellite` (CC BY-NC-ND), which was only used to prove that GeckoView works on this panel.
GeckoView is MPL-2.0 and is pulled as a Maven dependency.
