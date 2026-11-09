# Aqara / Lumi de-bloat

Removing Aqara's launcher, cloud/telemetry, and extra apps so the panel is a
clean Home Assistant kiosk. **Everything here is reversible** — that is a hard
requirement (we may want to undo a change, or inspect how Aqara does something,
e.g. how its voice assistant uses the microphone).

## Reversibility policy

- Prefer **`pm disable-user --user 0 <pkg>`** (disable, not remove) → undo with
  `pm enable <pkg>`.
- If a package is removed with **`pm uninstall --user 0 <pkg>`**, the APK stays
  in the read-only `/vendor` or `/system` partition → restore with
  `pm install-existing <pkg>`.
- **Back up every APK first** (`adb pull`) so we have the exact binaries to
  re-install and to reverse-engineer. Backups live outside the repo
  (`~/src/aqara-panel-backups/apks/`, not committed — they're Aqara's binaries).
- These are all **shell-level `pm` operations** (no root needed), applied over
  ADB, and are **version-agnostic transforms** — they work on any firmware
  version, nothing is flashed.

## The packages (stock Panel Hub S1 Plus, firmware ~2025.12)

All ship in read-only `/vendor/app` (one in `/system/app`), so all are restorable.

| Package | App | Role | Plan |
|---|---|---|---|
| `com.aqara.inhome.launcher` | Launcher | The Aqara home/kiosk launcher (default HOME) | Disable **after** setting our kiosk as HOME |
| `com.aqara.inhome.manager` | Manager | Main Aqara app + cloud link (telemetry: `datasink.aqara.cn`, `rpc-ger.aqara.com`) | Disable |
| `com.lumi.ota` | OTA | Firmware updater (phone-home to `cdn.aqara.com`) | Disable (prevents vendor re-flash) |
| `com.aqara.inhome.vassistant` | VoiceAssistant | Aqara's voice assistant (**uses the mic** — keep APK for RE) | Disable (we use HA Assist) |
| `com.aqara.inhome.embedded_chat` | Chat | Embedded chat | Disable |
| `com.aqara.music` | AqaraMusic | Music app | Disable |
| `com.lumi.siri` | Siri | Siri/HomeKit bridge | Disable unless HomeKit wanted |
| `com.lumi.dream` | Dream | Daydream/screensaver | Disable |
| `com.lumi.aging` | Aging | Factory burn-in test | Disable |
| `com.lumi.factory` | Factory | Factory test (`/system/app`) | Disable |
| `com.android.gallery3d` | AqaraGallery | Aqara's gallery (note: masquerades as `com.android.gallery3d`) | Optional |
| `com.lumi.settings` | LumiSettings | Stripped Settings replacement | Keep for now; goal = restore AOSP Settings |
| `com.lumi.systemmanager` | SystemManager | System manager / device policy | **Keep** (may be system-critical) — test carefully before touching |

> Order matters: set a replacement HOME **before** disabling `com.aqara.inhome.launcher`, or the device has no launcher.

> Note: this panel's Wi-Fi ("Sol IoT") is an isolated VLAN with no internet, so
> the phone-home apps can't currently reach Aqara's servers anyway — disabling
> them is hygiene + portability, not an urgent block.

## Apply / undo

A reversible script lives at [`tools/debloat/debloat.sh`](../tools/debloat/debloat.sh)
(`disable` / `enable` / `status`). It disables the safe set and leaves
system-critical packages alone.

## Applied on the reference unit (2026-11-09)

- S1 Kiosk ([app/](../app/README.md)) installed and set as HOME with
  `cmd package set-home-activity io.github.aguilaair.s1kiosk/.MainActivity`.
- `debloat.sh disable` + `disable-launcher` applied: all 9 packages in the safe set and the Aqara launcher
  are disabled. `com.lumi.settings` and `com.lumi.systemmanager` are still enabled.
- Survives reboot: the panel boots straight into the kiosk, USB ADB comes back, and Wi-Fi rejoins.
- Package/HOME state was snapshotted before any change (kept outside the repo with the APK backups).

### Locale / time zone

The unit shipped with Android's locale on `es-ES` (`settings system system_locales`), even though
`persist.sys.locale=en`. Its time zone was `Africa/Algiers`. Both are fixable without root:

```sh
adb shell settings put system system_locales en-US          # applies after reboot
adb shell settings put global auto_time_zone 0
adb shell service call alarm 3 s16 America/Los_Angeles      # IAlarmManager.setTimeZone, immediate
```

Home Assistant's UI language is a **per-user HA profile setting** (Profile → Localization), not the
Android locale.

### Keyboard

The stock IME was `com.baidu.input_auto` (Baidu, branded "Aqara"). Replaced with
[Simple Keyboard](https://f-droid.org/packages/rkr.simplekeyboard.inputmethod/) (Apache-2.0, F-Droid
build, only permission is VIBRATE):

```sh
adb install rkr.simplekeyboard.inputmethod_148.apk
adb shell ime enable rkr.simplekeyboard.inputmethod/.latin.LatinIME
adb shell ime set rkr.simplekeyboard.inputmethod/.latin.LatinIME
adb shell pm disable-user --user 0 com.baidu.input_auto      # undo: pm enable + ime set back
```

### Do Not Disturb / night screen-off

Aqara's DND schedule (22:00–07:00) forces the screen off 15 s after every wake. It's disabled through
Lumi's own service (`service call A_device_control 7 i32 0 i32 0`; undo with `i32 1`), and the kiosk's
`ScreenController` handles night behaviour instead. See [screen-policy.md](screen-policy.md).

### Still on the list

- `com.lumi.dream` is disabled, so Lumi's screensaver toggle `secure screensave_enable` was set to `0`.
- Night behaviour: see [screen-policy.md](screen-policy.md) (DND 22:00–07:00 forces the screen off).
