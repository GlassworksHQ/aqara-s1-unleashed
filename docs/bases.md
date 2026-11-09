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

## Implementation (S1 Kiosk)

Code: `app/kiosk/src/main/java/io/github/aguilaair/s1kiosk/base/`. Logs: `adb logcat -s S1Base`.

### How it talks to the base

Not through `bracket_cli` or the UART: the kiosk binds to the stock **LocalService** in
`com.lumi.systemmanager` (`systemmanager.intent.action.LOCAL_SERVER`,
`com.lumi.systemmanager.service.gateway.LocalService`; exported, no permission), the same path the
stock Settings app uses (`com.lumi.common.Local.LocalServer`). `ha_master` stays in charge of the
serial link, so stock behaviour (cloud, scenes, base OTA) is untouched.

- `LocalServiceClient` is a hand-written AIDL proxy for `com.lumi.systemmanager.service.ILocalService`
  (read=1, write=2, register=5, unregister=6; two-way, `readException` + `readInt`) and a `Binder`
  implementing the `ILocal` callback (onReadDone=1, onWriteDone=2, onStartSceneDone=3, onEvent=4,
  onReportDone=5, onKeepAlive=6, onSubdeviceJoinDone=7, onSubdeviceRemoved=8, write=9, read=10).
  The callbacks are **not oneway** in the decompiled stub, so ours writes `writeNoException` into
  the reply and hands the payload to a worker thread.
- Requests: read `{"did":<DID>,"res":"14.160.85"}` (or `"res":[...]` for a batch), write
  `{"did":<DID>,"res":"4.1.85","src":"3,,<epoch ms>,,,","value":1}`. `<DID>` is
  `persist.sys.did` (readable by an ordinary app through `SystemProperties.get` reflection). It is
  never logged: payloads are logged with the DID replaced by `<did>`.
- The service broadcasts **every** reply/event to every registered callback, including Zigbee
  sub-devices that reuse the same resource ids (`4.1.85`, `13.1.85`). Payloads whose `did` isn't
  the panel's are ignored.
- `WallBase` reads the dock class on connect, every 60 s, and when an event/report mentions
  `14.160.85` / `200.1.10003` / `13.8.85` / `14.21.85` / `3.7.85`. No reply within 15 s counts as
  "none". With a switch base it reads `13.8.85`, `14.21.85`, `8.0.2022`, `4.x.85`, `0.12.85`,
  `0.13.85`, `8.0.2014` and `4.x.28` on attach, then relays/power/energy/protection every 10 s;
  events and reports update values immediately. Channels come from the relay mask bits, else the
  channel count, capped at 3.
- The entity set (see [esphome.md](esphome.md#wall-base-entities)) changes only when the detected
  layout has been stable for 5 s and its entity set differs from the applied one. The API server
  then sends `DisconnectRequest` to every client; Home Assistant reconnects and re-lists. The last
  applied layout is persisted (`base_layout` pref), so a restart with the same base doesn't change
  the entity set.
- If the service can't be bound (package missing/disabled on another firmware), or `persist.sys.did`
  can't be read, it logs once, reports "Base: unavailable" and retries every 60 s. It rebinds on
  binding death and after 60 s without a connection.
- The only write is a relay command from Home Assistant (`4.x.85` = 0/1), followed by reads after
  0.5 s and 2 s to confirm. Re-detect (`200.1.10003`) is not used.
- The pure logic (dock class decode, mask to channels, reply parsing, debounce, entity ids) is in
  `BaseProtocol` / `BaseTracker` / `BaseEntities`, covered by `BaseProtocolTest` /
  `BaseTrackerTest`. `EspHomeApiTest` covers event entities and the entity set change against the
  real aioesphomeapi client.

### Verified on the panel (2026-11-09, no base mounted)

- The service binds, `registerILocalCallback` succeeds, and `read` returns `0`.
- The first `read 14.160.85` was answered by `onReadDone` about 2.3 s later (later reads take about 5 ms).
  Raw payload (DID redacted):

  ```json
  {"did":"<did>","result":{"14.160.85":0}}
  ```

  0 decodes to **none**. Over the API, `text_sensor` *Base* = `none` and no other base entities are
  listed (22 entities in total). It stays like that across the 60 s re-reads, with no rebuilds.
- Unsolicited events also arrive through `onEvent`, all for the panel's own DID, in the shape
  `LocalServer.parseEvent` expects:

  ```json
  {"action":"report","did":"<did>","res":"8.0.2006","src":"10,,<epoch ms>,0.trg=0,,","value":"30"}
  ```

  Values are strings. Resources seen with no base mounted: `14.6.113`, `8.0.2006`, `8.0.2024`,
  `8.0.2157`, `8.0.2333`, `13.201.85`, `13.201.700` (Matter fabric list).
- Home Assistant dropped its connection once, about 2 s after the first connect following the
  install that added the *Base* entity, then reconnected about 11 s later. It didn't happen on the
  next restart, so it looks like HA's one-time reaction to the new entity, not a loop.

### Still needs a mounted base

- The dock class value of a real base (expected `0x01xxxxxx`) and whether `13.8.85` / `14.21.85`
  answer through the LocalService at all (on the S1 Plus they are served by `ha_master` /
  `libha_bracket.so`, not by the Java `dock` package, which only runs on the S1M "multi-dock"
  firmware).
- The **click event format** for `13.x.85` (source of the `single`/`double`/`long` mapping is
  Aqara's usual multistate convention, not an observation). The raw value is logged at info
  (`button N: raw ...`).
- Units of `0.13.85` (energy; exposed raw, no unit) and the value type of `0.12.85` (power) and
  `8.0.2022` (firmware).
- Whether relay writes through the LocalService reach the base, and whether a write reports back
  (`onWriteDone` / `onEvent`) or only the confirmation reads update the state.
- Whether the channel names (`4.x.28`) are readable (they are read and kept, not used for entity
  names; see esphome.md).
- Ethernet base: detected as `ethernet` with a firmware sensor only.
