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
- Hidden settings: **tap the top-left corner 5 times within 3 s** to set the URL, zoom
  and extra secure hosts, open Android settings, or reload.

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

Then de-bloat with [`tools/debloat/debloat.sh`](../tools/debloat/debloat.sh)
(`disable`, then `disable-launcher`).

**Undo:** `debloat.sh enable`, then
`adb shell cmd package set-home-activity com.aqara.inhome.launcher/.MainActivity`,
and optionally `adb uninstall io.github.aguilaair.s1kiosk`.

## Configure over ADB

```sh
A="adb shell am start -n io.github.aguilaair.s1kiosk/.MainActivity"
$A --es url http://homeassistant.local:8123/my-dashboard   # set + persist the URL
$A --es open http://192.168.1.100:8123/profile/general      # navigate once (not persisted)
$A --ef zoom 1.25          # CSS px ratio; 0 = auto (device density 1.5). Restarts the app.
$A --es secure_hosts a.lan,b.lan   # extra secure-context hosts. Restarts the app.
$A --ez remote_debug true  # Gecko remote debugging. Restarts the app.
$A --ez reload true
```

Do **not** change `wm density` to scale the UI. On this panel it breaks GeckoView's surface
size until reboot. Use `zoom` instead.

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

## Voice

The browser side works: Home Assistant's in-dashboard Assist (mic button) can capture
audio over http. A wake-word **voice satellite** (always listening, ESPHome/Wyoming protocol)
is not implemented yet. That's the next step: a foreground service using `AudioRecord`, plus
on-device wake word or streaming to HA's Assist pipeline.

## License

MIT, see [LICENSE](LICENSE). Clean-room implementation. It contains no code from
`jxlarrea/kiosk-satellite` (CC BY-NC-ND), which was only used to prove that GeckoView works on this panel.
GeckoView is MPL-2.0 and is pulled as a Maven dependency.
