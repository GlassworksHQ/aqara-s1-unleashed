# Agent guide: the kiosk app (`app/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). The feature list, every adb extra and the
logcat tags are in [`README.md`](README.md); read it before you add a new one.
A new library in `libs/` or a new pane needs a branded README header: see
[Branding](../AGENTS.md#branding).

## The target

- **Android 10, 32-bit `armeabi-v7a`**, 1440×720 landscape, density 1.5 (960×480 dp). The CPU is
  slow and RAM is shared with Gecko, so keep work off the main thread and don't add heavy deps.
  An `arm64-v8a` APK is built too (`-Ps1Abi=arm64-v8a`, [`../docs/releasing.md`](../docs/releasing.md#abis)).
- **GeckoView, not WebView.** The system WebView is Chromium 74 and can't render the current HA
  frontend. Page-side changes go in the Gecko web extension (`kiosk/src/main/assets/extension/`).
- **Never change `wm density`** on the panel to scale the UI. It breaks GeckoView's surface until a
  reboot. Use the app's `zoom`.
- The app has **no root**. It reaches the root side only through properties and init services
  (`setprop persist.s1kiosk.radio_mode …`, `setprop ctl.start s1kiosk_update`), and must keep working
  on a panel without the patched boot image. Check the `sys.s1kiosk.*` capability properties before
  you use a service. The contracts are in [`../docs/radio.md`](../docs/radio.md#root-helper-contract-v4)
  and [`../frames/aqara-s1-plus/research/root.md`](../frames/aqara-s1-plus/research/root.md).
- Aqara's own services (`ha_master`, the system manager's DND rule) fight the kiosk. See
  [`../frames/aqara-s1-plus/research/debloat.md`](../frames/aqara-s1-plus/research/debloat.md) and
  [`screen-policy.md`](../frames/aqara-s1-plus/research/screen-policy.md) before you debug a screen that turns off,
  Wi-Fi drops or radio conflicts.

## Build and test

- JDK 21 and the Android SDK platform `android-37.2`. GeckoView forces the toolchain (AGP 9, Kotlin
  2.4); don't downgrade it.
- `./gradlew test` runs the JVM tests for every module. Some tests talk to Python reference
  implementations in `src/test/resources/` (`aioesphomeapi`, a Sendspin server, openWakeWord, microWakeWord on LiteRT). Keep
  those passing rather than skipping them.
- Put protocol logic (ESPHome, Improv, EZSP/ASH, the wall base, Sendspin, FLAC) in plain Kotlin
  that a JVM test can drive, and keep the Android glue thin. `app/libs/esphome-api` must stay free of
  Android imports.
- The wall base driver has an emulator: `tools/bracket/base_emulator.py`, reached with
  `--es base_transport tcp:127.0.0.1:9999` and `adb reverse`. It has never met a real base.

## Code conventions

- Components get what they need from `GlassworksRuntime` (owned by `KioskApp`, reached with
  `glassworks` from Android entry points). Don't add new static singletons; this is groundwork for
  the device-neutral core (#16, #30).
- **Never read `Build.VERSION.SDK_INT` outside `dev.glassworks.platform`.** Add a named query to
  `Platform` instead (libraries keep theirs in their own `Compat.kt`). `PlatformConfinementTest`
  fails the build otherwise.
- **Never use GeckoView outside `dev.glassworks.engine.gecko`.** Everything else goes through
  `Engine` and `Page` (`glassworks.engine`). `EngineConfinementTest` fails the build otherwise.
- Device-specific code (sensors, `/dev/ttyS3`, the radio, Aqara binder calls) belongs behind a seam
  that another frame could replace. Don't spread S1 paths through generic code. The S1's own parts
  live in `sh.edm.s1kiosk.frame`, reached through `glassworks.frame` (`AqaraS1Plus`).
- Logs use the `S1…` tags listed in the README. **Never log** tokens, API keys, the Noise key, Wi-Fi
  passwords or Thread datasets, not even at debug level.
- New user-visible strings follow the existing tone: short, plain, no exclamation marks.

## UI changes and screenshots

Every UI change needs screenshots in the PR, before and after (see [`../AGENTS.md`](../AGENTS.md#pull-requests)).

- **On the panel:** `adb shell input keyevent KEYCODE_WAKEUP`, put the screen in the state, then
  `adb exec-out screencap -p > after.png`. The intent extras help you get there:
  `--ez show_setup true --es setup_step wifi|ha|signin|panes|done` (a pane's step id, e.g. `radio`,
  opens that pane's step), `--ez show_signin true`, `--ez settings true`.
- **Without a panel:** the Robolectric mockups render the real views at the panel's size with the
  Inter font: `./gradlew :kiosk:testDebugUnitTest --tests '*Mockups*' -Ps1MockupDir=/tmp/shots`.
  When you add a view or a state, add it to `RadioMockups`/`TimerMockups` or a new `*Mockups` class,
  with illustrative values only.
- Take *before* from `main` with the same command or the same panel state.

## Trying a branch on the panel

- Android only updates an app with an APK signed by the same key. A debug build means an uninstall,
  which wipes HA's API key, the dashboard sign-in and the Sendspin pairing. Avoid that on the
  shared dev panel.
- Instead, push to `test-build/<name>`. CI builds a release-signed APK of the latest release's version;
  use the workflow's `version` input if the panel runs a newer, unreleased version (Android refuses a
  lower `versionCode`). Then `gh run download <run-id> -n s1-kiosk-test-<sha>` and
  `adb install -r s1-kiosk-test-*.apk`. That artifact is the S1's `armeabi-v7a` APK; the
  `arm64-v8a` one is `s1-kiosk-test-arm64-v8a-<sha>`. Creating these branches needs admin rights.
- Test builds carry no radio bundle, so the root side stays as it was.
- `ANDROID_SERIAL` selects the panel for adb. Don't export it before running `fastboot`; see
  [`../tools/AGENTS.md`](../tools/AGENTS.md).
