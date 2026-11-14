<h1 align="center">S1 Kiosk</h1>

<p align="center">
  <b>The Home Assistant kiosk app for the Aqara Panel Hub S1 Plus.</b><br>
  A GeckoView browser, the first-boot setup, and the panel's entities in HA.
</p>

<p align="center">
  <a href="https://www.glassworks.dev">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-on-dark.svg">
      <img alt="Built on Glassworks" src="https://www.glassworks.dev/brand/badge-on-light.svg" height="30">
    </picture>
  </a>
  <a href="LICENSE">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-license-mit-on-dark.svg">
      <img alt="License: MIT" src="https://www.glassworks.dev/brand/badge-license-mit-on-light.svg" height="30">
    </picture>
  </a>
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-status-beta-on-dark.svg">
    <img alt="Status: beta" src="https://www.glassworks.dev/brand/badge-status-beta-on-light.svg" height="30">
  </picture>
  <a href="https://github.com/orgs/GlassworksHQ/projects/1">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://www.glassworks.dev/brand/badge-roadmap-on-dark.svg">
      <img alt="Roadmap" src="https://www.glassworks.dev/brand/badge-roadmap-on-light.svg" height="30">
    </picture>
  </a>
</p>

---

A minimal, clean-room **GeckoView** kiosk browser for the Aqara Panel Hub S1 Plus
(Android 10, 32-bit `armeabi-v7a`, 1440×720). It shows a Home Assistant dashboard
full-screen and can replace the Aqara launcher as the HOME app.

Why GeckoView: the panel's system WebView is Chromium 74, which can't render the
current Home Assistant frontend. GeckoView ships its own up-to-date engine inside the APK.

## Features

- Full-screen immersive `GeckoView`, landscape, `FLAG_KEEP_SCREEN_ON`.
- Loads a configurable URL. The default `auto` finds Home Assistant (mDNS `_home-assistant._tcp`,
  or the address of HA's ESPHome connection) and loads its default dashboard. A saved URL is kept.
- **First-boot setup screen** (native, in the Glassworks look): Wi-Fi → Home Assistant (add it, sign
  in) → Panes (choose which, then each pane's own steps, such as the radio's role) → Done; it moves on
  by itself, and a step that is done says so with Continue. It only appears when the panel is really not
  set up (offline for 30 s, HA unknown, HA never added the panel within 2 min of startup, or HA's
  login page with no refresh token), never on a working panel. See
  [docs/onboarding.md](../docs/onboarding.md#setup-screen-).
- **Dashboard select** in HA (`select.s1_panel_dashboard`): Default + your Lovelace dashboards,
  listed by the HA frontend on the panel.
- **Sign in with your phone**: if HA shows its login page, the panel covers it with a QR code for
  `http://<panel-ip>:8099/signin`. Signing in on the phone gives the panel a refresh token (HA OAuth /
  IndieAuth), and the dashboard then runs with HA's external auth (`external_auth=1`, like the
  Companion app). "Sign in on this panel instead" keeps HA's normal login page. See
  [docs/onboarding.md](../docs/onboarding.md).
- **Microphone/camera work over plain `http://`.** The dashboard host is added to
  Gecko's `dom.securecontext.allowlist`, so `window.isSecureContext` is true and
  `navigator.mediaDevices.getUserMedia()` / AudioWorklet are available. Mic, camera and
  autoplay are granted without prompts (Gecko `PermissionDelegate` + Android `RECORD_AUDIO`).
  Verified on the panel: `getUserMedia({audio:true})` returns a live 48 kHz track.
- HOME + LAUNCHER intent filters (can be the default launcher), `BootReceiver`
  (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`).
- Back closes the topmost layer (a settings picker, the settings menu, a setup preview), else navigates
  inside the page, and never leaves the kiosk.
- If the page fails to load (HA restarting, Wi-Fi not up yet), it retries every 10 s.
  Crashed or killed content processes are restarted.
- Hidden settings: **tap the top-left corner 5 times within 3 s** (or `--ez settings true`) for the
  settings menu (#63): Dashboard (address, full screen, zoom, secure hosts), Screen (presence, idle and
  night times, brightness, Aqara's own settings), each pane under Panes with its on/off, Log (the live
  screen and sensor line) and About. Changes apply as you make them; the few that need a restart offer
  one.
- **Kiosk mode for HA**: a built-in Gecko web extension (`assets/extension/`) hides the HA dashboard
  header and the sidebar. Add `?kiosk=0` to the URL to show them for one page; while you're editing a
  dashboard they're left visible.
- **Screen policy from the panel's own sensors** (`dev.glassworks.screen.ScreenPolicy` with the S1 frame's sensors, see
  [docs/sensors.md](../docs/sensors.md)): wake on approach (VI5300 ToF), off after idle, a night window
  with a shorter timeout and lower brightness cap, auto-brightness from the MK6212 light sensor. Runs in
  a foreground `KioskService`, so it works while the screen is off. It replaces Aqara's DND
  screen-off ([frames/aqara-s1-plus/research/screen-policy.md](../frames/aqara-s1-plus/research/screen-policy.md)).
- **Home Assistant integration as an ESPHome device** (native API, voice assistant, screen and kiosk
  controls), see below and [docs/esphome.md](../docs/esphome.md).
- **Wall base (relay base) without Aqara's daemon**: the app speaks the base's UART protocol on
  `/dev/ttyS3` itself (relays, power, energy, protection), so `ha_master` and Aqara's cloud stay stopped.
  The LocalService path through `ha_master` remains as a fallback (`base_backend`). See
  [frames/aqara-s1-plus/research/bases.md](../frames/aqara-s1-plus/research/bases.md) and [frames/aqara-s1-plus/research/base-protocol.md](../frames/aqara-s1-plus/research/base-protocol.md).
- **Native Music Assistant player (Sendspin)**: MA sees the panel as its own player "Glassworks Panel" and plays
  to it in sync with your other Sendspin speakers (groupable). It pairs automatically: HA hands MA the
  panel's pairing token over the ESPHome API. See below and [docs/sendspin.md](../docs/sendspin.md).
- **Notifications from Home Assistant**: `esphome.s1_panel_notify` shows a card over the dashboard
  (picture, up to three buttons, a sound), or opens a dashboard view full screen; a button press fires
  `esphome.notification_action` in HA. Off by default. See [docs/notifications.md](../docs/notifications.md).
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
adb shell cmd package set-home-activity sh.edm.s1kiosk/.MainActivity
# optional: lets the BootReceiver start the activity from the background on Android 10
adb shell appops set sh.edm.s1kiosk SYSTEM_ALERT_WINDOW allow
```

Optional extras (recommended):

```sh
# instant screen-off (DevicePolicyManager.lockNow); undo with --ez remove_admin true
adb shell dpm set-active-admin sh.edm.s1kiosk/.AdminReceiver
# lets the app set the proximity sensor's presence range
adb shell pm grant sh.edm.s1kiosk android.permission.WRITE_SECURE_SETTINGS
# stop Aqara's 22:00-07:00 DND from forcing the screen off (undo: ... 7 i32 1 i32 0)
adb shell service call A_device_control 7 i32 0 i32 0
```

Then de-bloat with [`tools/debloat/debloat.sh`](../tools/debloat/debloat.sh)
(`disable`, then `disable-launcher`).

**Undo:** `debloat.sh enable`, then
`adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity`,
and optionally `adb shell am start -n sh.edm.s1kiosk/.MainActivity --ez remove_admin true`
followed by `adb uninstall sh.edm.s1kiosk` (an active device admin can't be uninstalled).

## Configure over ADB

```sh
A="adb shell am start -n sh.edm.s1kiosk/.MainActivity"
$A --es url http://homeassistant.local:8123/my-dashboard   # set + persist the URL ("auto" = discover HA)
$A --ez show_setup true    # preview the setup screen at its live step (changes nothing)
$A --ez show_setup true --es setup_step wifi   # ... or a given step: wifi|ha|signin|panes|radio|done
$A --ez show_setup false   # close the preview
$A --ez show_signin true   # preview the QR sign-in step (doesn't touch the session)
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
$A --es wake_model okay_nabu --ef wake_threshold 0.9 --ef mic_gain 2.0 --ez mic_muted false  # wake word id (an old *.tflite name maps to its microWakeWord model); the threshold of that word
$A --es api_key $(openssl rand -base64 32) --ez api_encryption true   # manual ESPHome API key (HA normally provisions one)
$A --ez voice_enabled false           # each pane on/off, applied live (default on): voice_enabled, sendspin_enabled,
                                      #   wall_base_enabled, time_zone_enabled, diagnostics_enabled
$A --ez notifications_enabled true    # notifications on/off, applied live (default off; docs/notifications.md)
$A --ez bluetooth_proxy_enabled true  # the Bluetooth proxy on/off (default off)
$A --ez sendspin_enabled false        # Music Assistant player (Sendspin) off/on (default on)
$A --es sendspin_server 192.168.1.100 # Sendspin server: auto (default: mDNS, else <HA host>:8927) or host[:port][/path] / ws://
$A --ez sendspin_unpaired_access true # let a server play without pairing (guest mode; default off)
$A --ez sendspin_flac false           # offer only PCM to Music Assistant (default: FLAC first, PCM fallback)
$A --es base_backend auto      # wall base: auto (UART unless Aqara cloud blocking is off) | uart | localservice
$A --es base_transport tcp:127.0.0.1:9999   # UART backend on tools/bracket/base_emulator.py (adb reverse); "" = /dev/ttyS3
```

Do **not** change `wm density` to scale the UI. On this panel it breaks GeckoView's surface
size until reboot. Use `zoom` instead.

## Wi-Fi setup over Bluetooth (Improv)

```sh
I="adb shell am start -n sh.edm.s1kiosk/.improv.ImprovActivity"
$I --ez improv true        # start provisioning mode (10 min); false = stop
$I --ez improv_auto false  # don't auto-start when there's no network after boot (default true)
$I --ez improv_proximity_auth true   # also authorize by approaching the panel (default false)
$I --ez release_device_owner true    # escape hatch: clearDeviceOwnerApp()
adb logcat -s S1Kiosk.Improv          # BLE support report at startup, states, RPCs
```

`ImprovActivity` only accepts callers holding `WRITE_SECURE_SETTINGS` (i.e. `adb shell`).
Joining the new network uses `WifiManager.addNetwork`, which Android 10 allows because the app holds
the SYSTEM_ALERT_WINDOW app-op (see the install step), or because it's device owner
(`adb shell dpm set-device-owner sh.edm.s1kiosk/.AdminReceiver`; release with the extra
above). Otherwise it falls back to a network suggestion the user must approve. Scanning and checking
the current SSID also need the location permission:
`adb shell pm grant sh.edm.s1kiosk android.permission.ACCESS_FINE_LOCATION` (already
granted by `adb install -g`, or self-granted when device owner). Details, sources and limits:
[docs/onboarding.md](../docs/onboarding.md). With the permission granted, the kiosk's service runs
with the `location` foreground type as well as `microphone`, so the Wi-Fi scans it starts with the
screen off are allowed (#3, see [docs/radio.md](../docs/radio.md)).

## Debugging

Debug builds enable Gecko remote debugging. [`tools/gecko-eval`](../tools/gecko-eval/gecko_eval.py)
is a tiny Firefox Remote Debugging Protocol client that evaluates JS in the page:

```sh
adb forward tcp:6000 localabstract:sh.edm.s1kiosk/firefox-debugger-socket
python3 tools/gecko-eval/gecko_eval.py 'JSON.stringify({w: innerWidth, h: innerHeight, sc: isSecureContext})'
```

Gecko's prefs are written to `files/geckoview-config.yaml` at startup and logged under the
`S1Kiosk` tag. Sign-in and HA discovery log under `S1Auth` / `S1Discovery`, the Manage page under `S1Manage` ([docs/manage.md](../docs/manage.md); its own log keeps the `S1…` lines for 7 days in `files/manage/log/`), the Music Assistant
player under `S1Sendspin` (with a `sync:` line every 10 s while playing). The Wi-Fi scans the app
starts while the screen is off log under `S1Kiosk.Wifi`. None of them log tokens or keys. Page
console output goes to logcat (`Isolated Web Content` / `GeckoConsole`).

The connecting screen stays up until the page says it has drawn. `S1Kiosk` logs one line when it
goes, with the reason: `Connecting screen hidden: ready, frontend/loaded` (Home Assistant's frontend
removed its launch screen and a frame was painted after it), `ready, removed` (the same, for a
frontend that doesn't send frontend/loaded), `ready, page` (a page without a launch screen), or the
fallbacks `fallback 15 s, no word from the page` and `fallback 45 s, the frontend still loading`.
`Home Assistant's launch screen is up` marks the page reporting that it's still loading. Whatever the
timing, HA's launch screen never shows white: content.js styles it, and the page under it, with the
night background from `document_start`, hides its logo and attribution, and keeps its info box readable
(HA writes why a start failed there). The style is scoped with `:has` and removed once the launch screen
goes; not with `?kiosk=0` or `?edit=1`.

> If the screen is blank/white and `innerWidth` is 0, the **display is off**. Gecko drops its
> surface while the screen sleeps. Check `adb shell dumpsys power | grep mWakefulness`.
> See [frames/aqara-s1-plus/research/screen-policy.md](../frames/aqara-s1-plus/research/screen-policy.md) for why the panel turns itself off.

## Home Assistant: ESPHome device + voice assistant

The app exposes the panel to Home Assistant as **one ESPHome device** over the ESPHome native API
(TCP **6053**, advertised as `_esphomelib._tcp`): an Assist satellite with an **on-device wake word**
(microWakeWord on TFLite, default "Hey Jarvis"; the openWakeWord models stay selectable for one release), a media player, the screen as a light, presence and
illuminance sensors, and the kiosk/screen settings. It runs in `KioskService`, so it keeps listening
while the screen is off, and talking to it wakes the screen. See [docs/esphome.md](../docs/esphome.md).

```sh
tools/voice/fetch-models.sh            # optional: downloads the microWakeWord models on the host and adb-pushes them
# HA: the panel is auto-discovered (Settings -> Devices & services -> ESPHome "Glassworks Panel" -> Add), or
#     Add integration -> ESPHome -> <panel IP>, port 6053
```

Entity ids look like `light.s1_panel_screen`, `select.s1_panel_screen_mode`,
`media_player.s1_panel_speaker` on a panel added before #63; newer ones start with `glassworks_panel_`
([Names](../docs/esphome.md#names)). Pick the wake word and the Assist pipeline on the device page.
Encryption is automatic: a panel without a key lets HA 2026.11+ provision one when you add it
(ESPHome dynamic encryption key). You can also set a key by hand with
`--es api_key <base64 32 bytes> --ez api_encryption true`. Keys are never logged. Tune the mic with
`--ef mic_gain 2.0` / `--ef wake_threshold 0.9`, or turn the voice assistant off with
`--ez voice_enabled false`, the Voice switch in the settings, or the `voice` switch in Home Assistant
(the API and the other entities stay). It turns off and on without a restart: Home Assistant
reconnects to read the panel's voice features again.

Not yet tested on the panel. The previous Wyoming satellite was verified there (HA connected,
`announce` TTS played, wake-word inference used about 40% of one core); remove its Wyoming entry
from HA when switching.

## Music Assistant: native Sendspin player

The panel is a Sendspin client (the protocol behind Music Assistant's own players): Noise-encrypted
WebSocket to MA's server (`_sendspin-server._tcp`, else `ws://<HA host>:8927/sendspin`), Kalman clock
sync, PCM 48 kHz stereo into an `AudioTrack` scheduled from `getTimestamp()` with single-frame drift
correction. MA pairs with it automatically through HA (ESPHome pairing-token hand-off). Its entity in HA
is `media_player.s1_panel` (from the Music Assistant integration). The ESPHome switch
**Music Assistant player (Sendspin)** turns it off. While the voice assistant listens or talks, the
Sendspin output is muted locally and stays in sync. Verified on the panel against MA 2.10.9 (clock
±0.43 ms, ~0.9 corrected frames/s). Details, sources and tests: [docs/sendspin.md](../docs/sendspin.md).

## Libraries

Parts of the app are standalone modules other projects can use:

- [`libs/esphome-api`](libs/esphome-api/README.md): the ESPHome native API, device side, in plain
  Kotlin/JVM (Noise encryption with HA key provisioning, entities, voice assistant messages).
- [`libs/esphome-api-android`](libs/esphome-api-android/README.md): mDNS discovery for it on Android.
- [`libs/improv`](libs/improv/README.md): Improv Wi-Fi for Android: a BLE GATT server, Improv Serial
  (including over adb, from a web page over USB), Wi-Fi joining that works on Android 10+, and the
  pure-Kotlin protocol and state machines. Its browser client is
  [`kiln/packages/improv-serial`](../kiln/packages/improv-serial/README.md).
- [`libs/bluetooth-proxy`](libs/bluetooth-proxy/README.md): an ESPHome Bluetooth proxy on Android:
  BLE scanning on Android's terms, batched into `esphome-api`. Listening only so far; the app's
  Bluetooth proxy pane runs it (#121).

They build with the app (`./gradlew :esphome-api:test :esphome-api-android:assembleDebug
:improv:testDebugUnitTest :bluetooth-proxy:testDebugUnitTest`) and aren't published to a Maven repository or npm yet.

## License

MIT, see [LICENSE](LICENSE). Clean-room implementation. It contains no code from
`jxlarrea/kiosk-satellite` (CC BY-NC-ND), which was only used to prove that GeckoView works on this panel.
GeckoView is MPL-2.0 and is pulled as a Maven dependency. The QR code uses ZXing core (Apache-2.0).
The Sendspin client is a clean-room implementation from the Sendspin specification (Community
Specification License 1.0), cross-checked against aiosendspin (Apache-2.0). It contains no code from
either.
