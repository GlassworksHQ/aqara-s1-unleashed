# Screen on/off policy (why the panel turns itself off)

The stock firmware's `com.lumi.systemmanager` (kept enabled, it's system-critical) runs
several "screen policy" monitors (`com.lumi.systemmanager.service.screenpolicy.*`):

| Monitor | What it does |
|---|---|
| `ZenModeMonitor` | **While Do Not Disturb is on, it forces the screen off 15 s after every wake** (`PowerUtils.screenOff()` → `goToSleep(reason=power_button)`). This ignores app wake locks such as `FLAG_KEEP_SCREEN_ON`. |
| `VI5300Monitor` / `RD1VI5300Monitor` | VI5300 ToF proximity sensor: wakes the screen when someone approaches (`secure proximity_wakeup_enable=1`, `proximity_threshold`). |
| `LightMonitor` | Ambient light / `secure dark_screen_off`. |
| `AutoHomeMonitor` | On `SCREEN_OFF`, sends the user back to HOME (`goHome`). With our kiosk as HOME, that's just the kiosk again. |
| `DockMonitor` | Dock state. |

The only escape from the ZenMode idle-off is `DeviceControlService.isRequiredScreenKeepOn()`.
It is true while some client has registered via the `IDeviceControlService` binder
(`mZenKeepScreenOnSet`). That's a possible future hook for the kiosk.

## The DND schedule

The firmware installs an automatic Zen rule named **`lumi_zen`**: every day **22:00 → 07:00**
(`condition://android/schedule?days=1.2.3.4.5.6.7&start=22.0&end=7.0`). So at night the panel
behaves as a night clock: the screen sleeps 15 s after a wake, and approaching it wakes it again.
At 07:00 DND ends, and `ZenModeMonitor` wakes the screen (and would start the Lumi
screensaver if `secure screensave_enable=1`).

Inspect it:

```sh
adb shell settings get global zen_mode          # 0 = off, 1 = important interruptions
adb shell dumpsys notification | grep lumi_zen
```

Gotcha: the panel shipped with time zone `Africa/Algiers`. After fixing it to the real local
zone, the 22:00–07:00 window lines up with real night-time, which can look like a "white
screen / kiosk broken" bug when you're testing at night. It isn't. The display is just off,
and Gecko reports `innerWidth = 0` while its surface is gone.

## Related settings (stock values)

```
system  screen_off_timeout   15000
secure  screensave_enable    1        # Lumi screensaver (com.lumi.dream); we set 0 since dream is disabled
secure  dark_screen_off      1
secure  proximity_wakeup_enable 1
```

## Decision (2026-11-09)

The `lumi_zen` rule is disabled through Lumi's own API (`IDeviceControlService.setDisturbMode`,
transaction 7 on service `A_device_control`):

```sh
adb shell service call A_device_control 7 i32 0 i32 0   # disable DND (2nd arg: don't sync to cloud)
adb shell service call A_device_control 7 i32 1 i32 0   # restore
```

Night behaviour now lives in the kiosk's screen policy (`dev.glassworks.screen.ScreenPolicy`): a configurable night window, a shorter
idle timeout and a lower brightness cap, with wake on approach. See [sensors.md](../sensors.md).
