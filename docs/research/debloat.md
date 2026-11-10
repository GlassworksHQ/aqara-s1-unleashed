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

> Correction (2026-11-09): the panel's Wi-Fi **does** have internet, and disabling the APKs does
> **not** stop the phone-home: see "Aqara cloud: still connected" below.

## Apply / undo

A reversible script lives at [`tools/debloat/debloat.sh`](../../tools/debloat/debloat.sh)
(`disable` / `enable` / `status`). It disables the safe set and leaves
system-critical packages alone.

## Applied on the reference unit (2026-11-09)

- S1 Kiosk ([app/](../../app/README.md)) installed and set as HOME with
  `cmd package set-home-activity sh.edm.s1kiosk/.MainActivity`.
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

**It doesn't stick (found 2026-11-10):** Aqara's SystemManager (`/vendor/app/SystemManager`,
`com.lumi.systemmanager.service.gateway.PropReceiver`) runs `syncTimeZone()` at boot and whenever its own
gateway property `persist.app.time_zone` changes. That property lives in Aqara's native gateway store, not
in Android's properties, and is empty without Aqara's cloud, so it defaults to `GMT+08:00`; `setTimeZone()`
then calls `AlarmManager.setTimeZone` with the first `TimeZone.getAvailableIDs()` entry whose current
offset matches: `Antarctica/Casey` (logcat tag `PropReceiver`, "syncTimeZone … / setTimeZoneOffset id …").
The app can't set the zone (`SET_TIME_ZONE` is signature|privileged), so the root helper (v4) keeps the
owner's zone (`persist.s1kiosk.timezone`) with the same `service call alarm 3` and re-applies it whenever
`persist.sys.timezone` drifts ([../radio.md](../radio.md#root-helper-contract-v4), [../install.md](../install.md#time-zone)).

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

### Aqara cloud: still connected, and its Wi-Fi watchdog

The native gateway daemon **`ha_master`** (started by init, not an APK, so `pm disable-user` can't touch it)
keeps a connection to Aqara's cloud (`43.131.7.8:15050`, `cloud_aiot.cpp`), mirrored in the Android
property `sys.cloud_connected`.

`com.lumi.systemmanager` (`PropReceiver.onPropCloudConnect`) runs a **watchdog**. When the cloud
connection drops while the internet is reachable, it waits 6 s and then **forces a Wi-Fi reassociate**
(`NetUtils.reassociateWiFi`), or toggles Ethernet. On this panel the reassociate fails once, which causes
a 6–10 s network outage. The watchdog only runs when the gateway property
`persist.app.cloud_provisioned` is true, meaning the panel was set up with an Aqara account. **A panel
never registered with Aqara skips it.**

One trigger is restarting the S1 Kiosk app, through this chain:
1. Our process was Android `NsdService`'s only client, so NsdService stops `mdnsd`.
2. `ha_master` loses mdnsd, errors out and is restarted by init.
3. Its cloud connection drops, the watchdog fires, and Wi-Fi reassociates.

Any Aqara cloud hiccup triggers it the same way.

**Built in (S1 Kiosk, default on): `switch.<panel>_block_aqara_cloud`.** As soon as it starts, the app
runs `setprop ctl.stop ha_master` (with the LocalService base backend this used to happen ~20 s after
start; that delay is now only used with that backend). This firmware is SELinux-permissive, so the app's own uid may control
init services; no root needed. It re-applies after every boot, because init starts the daemon again.
Verified on device:
- the panel holds zero internet connections afterwards;
- the cloud watchdog doesn't fire, since the dead daemon never reports a cloud-state change;
- after a reboot it is blocked again within ~30 s;
- HA, voice and Music Assistant are unaffected.

**It is blocked always**, wall base or not. `ha_master` also holds the wall-base UART `/dev/ttyS3`, but the
app now speaks that protocol itself (UART base backend, the default; see [bases.md](bases.md) and
[base-protocol.md](base-protocol.md)). With the UART backend, the app stops `ha_master` right at start, before
it opens the port, instead of after 20 s. Only the LocalService base backend
(`--es base_backend localservice`, or `auto` with blocking off) still keeps `ha_master` running while a
base is attached. With `base_backend uart`, ha_master is stopped even if blocking is off, because the two
can't share the port.

The status shows in `sensor.<panel>_aqara_cloud_daemon`: `blocked`,
`stopped (UART wall base backend owns /dev/ttyS3)`, `running`,
`running (needed by the wall base, LocalService backend)` or `error (…)`. Known gap: the daemon is online
from boot until the app starts and stops it (a few seconds after the app's process starts).

**For zero phone-home, also** block the panel's internet access at the router and allow only the LAN (HA, Music
Assistant). That stops the phone-home **and** the watchdog: it checks `isInternetConnected()` first and does
nothing without internet. Then point time sync at a LAN NTP server, e.g.
`adb shell settings put global ntp_server 192.168.1.1`. Keeping `ha_master` from starting at all at boot would need
persistent root. The app stops it shortly after boot; the wall base no longer needs it (see bases.md).
