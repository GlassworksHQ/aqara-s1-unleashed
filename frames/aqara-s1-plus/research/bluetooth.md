# Bluetooth: can the S1 be an ESPHome Bluetooth proxy, and what do Android 10's scan rules allow?

**Status (2026-11-13): untested on the panel.** The panel's Bluetooth state was read with read-only
adb. Android 10's scan rules were read from AOSP. The proxy hasn't scanned on the panel yet: the
panel plan in #121 does that, and measures Wi-Fi and Sendspin with scanning on and off.

Device: Aqara Panel Hub S1 Plus, Android 10, S1 Kiosk 0.4.3. Tools: adb (read-only), AOSP
`packages/apps/Bluetooth` at `android10-release`, aioesphomeapi 46.25.0, ESPHome 2026.10.2,
bleak-esphome `main` (2026-09-12).

## Question

Issue #121 makes the panel an ESPHome Bluetooth proxy, so HA hears the BLE sensors near it. Before
that ships we need to know:

- what Bluetooth chip the panel has, and whether it shares the radio with Wi-Fi;
- what Android 10 does to a scan that runs for days, with the screen off at night;
- what the app needs (permissions, location);
- for GATT connections later: how many, and what Android hides.

## What we tried

1. **The chip and its coexistence** (2026-11-13, verified)
   ```
   $ adb shell 'getprop | grep -iE "bluetooth|wlan.driver" | grep -viE "addr|mac"'
   [persist.vendor.bluetooth.rtkcoex]: [true]
   [persist.vendor.bluetooth_port]: [/dev/ttyS1]
   [persist.vendor.bluetooth_vendor]: [realtek]
   [vendor.realtek.bluetooth.chip_name]: [8821CS or 8821CSH or 8821CS-VB]
   [vendor.wlan.driver.version]: [v5.14.2.1-48-g54206815b.20221004_COEX20210319-5555]
   ```
   A Realtek RTL8821CS: Wi-Fi over SDIO and Bluetooth over UART (`/dev/ttyS1`) on one chip, with
   Realtek's coexistence on. Bluetooth and 2.4 GHz Wi-Fi most likely take turns on one antenna path
   (untested: not checked against a datasheet, not measured).

2. **The Wi-Fi band** (2026-11-13, verified)
   ```
   $ adb shell 'dumpsys wifi | grep -oE "Frequency: [0-9]+|Link speed: [0-9]+Mbps" | sort | uniq -c'
         1 Frequency: 5200
         2 Link speed: 390Mbps
   ```
   The dev panel is on 5 GHz, so Bluetooth doesn't share airtime with its Wi-Fi. A panel on
   2.4 GHz would. The panel plan measures 5 GHz; 2.4 GHz is an open question.

3. **Bluetooth, the scan filter slots, location, permissions** (2026-11-13, verified)
   ```
   $ adb shell 'dumpsys bluetooth_manager | grep -iE "^  state|enabled:|mMaxScanFilters"'
     enabled: true
     state: ON
     mMaxScanFilters: 0
   $ adb shell 'dumpsys location | grep "Location mode"'
     Location mode: true
   $ adb shell 'dumpsys package sh.edm.s1kiosk | grep -E "LOCATION|BLUETOOTH"'
         android.permission.BLUETOOTH: granted=true
         android.permission.BLUETOOTH_ADMIN: granted=true
           android.permission.ACCESS_FINE_LOCATION: granted=true, ...
   ```
   Bluetooth is on, location is on, and S1 Kiosk already holds the permissions a scan needs (from
   the Matter work, #47). The controller has **no offloaded scan filter slots**.

4. **Android 10's scan rules** (2026-11-13, read: AOSP `packages/apps/Bluetooth`, `android10-release`)

   | Rule | Source |
   |---|---|
   | A 6th `registerScanner` within 30 s fails with `SCAN_FAILED_SCANNING_TOO_FREQUENTLY` (6) | `GattService.registerScanner`; `AppScanStats.NUM_SCAN_DURATIONS_KEPT = 5`, `EXCESSIVE_SCANNING_PERIOD_MS = 30 s` |
   | A scan with no filters is suspended while every display is off, and while location is off. "Filtered" only means a non-empty filter list | `ScanManager.handleStartScan` (`isFiltered = filters != null && !filters.isEmpty()`) |
   | Results reach an app only with location on and FINE location (Android 10) | `GattService.hasScanResultPermission`, `Utils.blockedByLocationOff` |
   | After 30 min a scan becomes opportunistic, unless it is exempt. Its filters must fit in the controller to be exempt | `ScanManager.regularScanTimeout`, `isExemptFromScanDowngrade`, `shouldUseAllPassFilter` (`filters.size() > mFilterIndexStack.size()`) |
   | A scan from an app below foreground-service importance drops to `BLE_SCAN_BACKGROUND_MODE` (low power by default) | `ScanManager.handleImportanceChange` |
   | Duty cycles: low power 512/5120 ms, balanced 1024/4096 ms, low latency 4096/4096 ms | `ScanManager.SCAN_MODE_*_WINDOW_MS`, `_INTERVAL_MS` |

   With `mMaxScanFilters: 0`, one empty filter is already more than the controller holds. The scan
   then uses the all-pass filter, isn't exempt, and becomes opportunistic after 30 min. The empty
   filter still counts as "filtered", so it keeps running with the screen off.

5. **What Home Assistant needs from the device** (2026-11-13, read)

   | What | Source |
   |---|---|
   | `DeviceInfoResponse.bluetooth_proxy_feature_flags` = field 15; `bluetooth_mac_address` = 18 | aioesphomeapi 46.25.0 `api.proto` |
   | HA adds a scanner for `PASSIVE_SCAN` (bit 0), subscribes to raw advertisements for `RAW_ADVERTISEMENTS` (bit 5), makes it connectable for `ACTIVE_CONNECTIONS` (bit 1). The scanner's source is `bluetooth_mac_address`, else `mac_address` | bleak-esphome `connect.py` `connect_scanner` |
   | One advertisement subscriber at a time; a new one replaces the old | ESPHome 2026.10.2 `bluetooth_proxy.cpp` `subscribe_api_connection` |
   | Batches of 16 (`BLUETOOTH_PROXY_ADVERTISEMENT_BATCH_SIZE`), partial batches every other 100 ms tick on Wi-Fi | ESPHome 2026.10.2 `bluetooth_proxy/__init__.py`, `bluetooth_proxy.cpp` `loop` |
   | A raw advertisement holds at most 62 bytes of AD data | `api.proto` `BluetoothLERawAdvertisement.data` `fixed_array_size = 62` |
   | `FEATURE_STATE_AND_MODE` only where the scanner can switch between passive and active scans | ESPHome `bluetooth_proxy.h` `get_feature_flags` |

## Result

| Question | Answer |
|---|---|
| Chip | RTL8821CS, Wi-Fi and Bluetooth on one chip, Realtek coexistence on (verified) |
| Shares airtime with Wi-Fi? | Likely only with 2.4 GHz Wi-Fi; the dev panel is on 5 GHz (band verified; the sharing untested) |
| Permissions and location | Already in place on the dev panel (verified) |
| Scan with the screen off | Runs, with one empty `ScanFilter` (read) |
| A scan for days | Restart it every 25 min, at most 4 starts per 30 s (read) |
| Passive vs active scan type | Android doesn't let an app choose, so no `FEATURE_STATE_AND_MODE` (read) |

The `bluetooth-proxy` library (`app/libs/bluetooth-proxy`) follows all of these.

## How to reproduce

Every command above is read-only (`getprop`, `dumpsys`). `dumpsys bluetooth_manager` prints the
addresses of bonded and nearby devices: grep for the lines you need, or redact before you share it.

## Open questions

- **Wi-Fi and Sendspin with scanning on.** The panel plan in #121 measures throughput (`iperf3`)
  and Sendspin dropouts with the proxy off, at balanced and at low latency. Untested.
- **2.4 GHz.** A panel on 2.4 GHz Wi-Fi shares the antenna path with Bluetooth. Not measured.
- **Does Android pad the scan record?** The library strips trailing zero padding and keeps whole AD
  structures. If Android 10 pads the advertisement *before* the scan response, the scan response
  would be lost. Compare a known sensor's data on the panel with an ESP32 proxy's. Untested.
- **GATT connections:** how many the RTL8821CS holds at once, and whether Android 10's hidden
  attribute handles match ESPHome's. See the active-connections design in #121. Untested.
- **Next to the Matter pane's BLE advertising (#47).** Android allows a scan and an advertiser at
  once; the chip doing both was not tried.
