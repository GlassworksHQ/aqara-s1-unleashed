# Wall bases ("brackets" / "docks")

The panel mounts on a mains-powered wall base. Aqara's firmware calls it the **bracket** (native code)
or **dock** (Java). Findings below come from the stock firmware (`com.lumi.systemmanager`,
`com.lumi.settings`, `/vendor/bin/bracket_cli`, `/vendor/lib/libha_bracket.so`). Everything was
read-only, and no base was attached during these tests.

## Which bases exist

From public sources (manuals, retailer listings, reviews). All bases are relay bases with power
metering. None dim or drive curtains.

| Panel SKU | Region | Base | Terminals | Rating | Cloud model |
|---|---|---|---|---|---|
| Panel Hub S1 Plus **MP-K03D** | EU / UK / AU / KR / RU | 2-gang relay, neutral required | `N L L2 L1` | 200–240 VAC, 8 A resistive; 300 W LED (200 + 100 W with both channels on) | `lumi.switch.agl003` |
| 集悦妙控屏 S1 Plus **JYMKP01LM** | China (86-box) | 3-gang relay, neutral required | `N L L3 L2 L1` | 220 V; ≤2200 W incandescent / ≤400 W LED in total | `lumi.switch.acn034` |
| S1 Plus Desktop **MP-K05D** | – | none (stand + USB-C) | – | – | – |

- The base connector sits in a different place on CN and international panels, so bases aren't
  interchangeable across regions.
- Stock features: relay per channel, power/energy metering, over-temp/over-load protection, and
  decoupled mode ("MARS") with Normal / Intelligent (bind to a smart bulb) / Pulse load modes.
- The firmware also knows an **Ethernet dock** (dock class `0x10`). No public listing for it was
  found.

Sources: EU manual (gzhls.at PDF), CN upgraded-edition manual (static-resource.aqara.com), aqara.ru,
The Ambient and HomeKitNews reviews, absent42/Aqara-Firmware-Archive.

## Physical link

- **UART `/dev/ttyS3` @ 115200 8N1.** The device node is world-read/writable
  (`crwxrwxrwx system system`).
- At runtime the port is held open by Aqara's native gateway daemon:
  `ha_master -H -M /dev/i2c-3 -C -a /vendor/lib/libha_auto.so -k /vendor/lib/libha_bracket.so -g /vendor/lib/libha_energy.so`
  (started by init; it keeps running after the de-bloat because it isn't an APK).
- `libha_bracket.so` implements the protocol: framed messages (`pack_msg_head` / `msg_head_parse`)
  with **CRC16**, a periodic `bracket_tick` / `bracket_sync` keepalive, `bracket offline` /
  `bracket status %d`, writes cached while the serial port is busy, and base firmware OTA
  (`bracket_ota_launch`).
- `ha_master` also opens **`/dev/i2c-3`** (`twi3`, root-only, no kernel driver bound). Its purpose
  is unknown. It might be a second link to the base.
- **Base firmware tools** ship in `/vendor/bin`: `bracket_ota`, `bracket_isp.sh`. Don't run them.

## Access without root: `bracket_cli`

Aqara's own Java code (`com.lumi.common.utils.DockExecUtils`) controls the base by executing:

```
/vendor/bin/bracket_cli /dev/ttyS3 115200 <resource-id>            # read  -> prints value
/vendor/bin/bracket_cli /dev/ttyS3 115200 <resource-id> <value>    # write
# failure prints "###opration failed. key=<res>"
```

The binary is `root:shell 0755`, so `adb shell` and ordinary apps can run it.

## Resource IDs

| Resource | Meaning |
|---|---|
| `14.160.85` | **Dock class** (detection). `value >> 24`: `1` = switch/relay base, `0x10` = Ethernet base, `0` = none/unknown. Mirrored into `persist.aqara.dock_info`. |
| `200.1.10003` | Write `1` = re-detect the dock (Settings → Advanced → "re-detect"). |
| `13.8.85` | Number of switch channels. |
| `14.21.85` | Relay mask (bit per channel; `7` = 3 channels). |
| `4.1.85` / `4.2.85` / `4.3.85` | Relay 1/2/3 state (0/1), read/write. |
| `13.1.85` / `13.2.85` / `13.3.85` | Physical button click events, channels 1–3. |
| `4.1.28` … `4.3.28` | Channel names. `4.x.600` = channel icons. |
| `0.12.85` | Power (W). |
| `0.13.85` | Energy consumed. |
| `8.0.2014` | Protection state: 0 normal, 1 over-temp, 2 over-load, 3/4 errors, 5/6 removed. |
| `8.0.2022` | Base firmware version. |
| `8.0.2030` | Save. |
| `3.7.85` | Connect/report state. |

Property names used internally (`DockConstant`) show what a base can report: `SW1–SW3`, `VOLTAGE`,
`CURRENT`, `V_POWER`, `PW_ACC`, `FACTOR` (power factor), `AC_FREQ`, `TEMP`/`TH_TEMP`,
`POWER`/`TH_POW` (over-load), `PROX`/`V_PROX`/`TH_PROX` (a proximity reading on the base),
`WAN` (removal protection), `MODEL`, `BID` (base id; `000000000000000000` = none), `SN`, `MAC`,
`HW_INFO`, `SW_INFO`.

The default name in the code is "三键开关底座 Pro" ("3-switch base Pro").

## Plan for S1 Kiosk

1. **Detect.** Poll `14.160.85` through `bracket_cli` every few seconds and on boot. When it changes,
   read `13.8.85` / `14.21.85` for the channel count, plus model, firmware and the metering
   resources.
2. **Expose through ESPHome.** The entity list is built from the detected base:
   - a `switch` per relay;
   - `sensor`s for power, energy, voltage and current when present;
   - a protection `text_sensor`;
   - `event` entities for button clicks (needs the event feed, see below).

   When the base changes, the app updates its entity list and drops the API connection. HA
   reconnects and picks up the new entities, just as it does after an ESPHome firmware update.
3. **Button events.** Unsolicited click frames are read by `ha_master`, not `bracket_cli`.
   - Option A: subscribe to the stock event path (`ha_master` → `com.lumi.systemmanager`;
     transport not yet identified, D-Bus `gateway_dbus` is the likely candidate).
   - Option B: stop `ha_master` (root) and speak the framed protocol ourselves. That needs the
     frame format reversed from `libha_bracket.so`.

   With the base's default coupled mode, a press toggles the relay locally, and polling sees it.
4. **Ethernet base.** Exposes Ethernet only; Android sees it as a network interface. Report it as
   detected; no extra entities.

**Status:** research only, nothing implemented yet. Validating this needs a panel mounted on a
powered base.
