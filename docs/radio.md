# Zigbee / Thread radio modes

The panel's 802.15.4 radio can be offered to Home Assistant in one single-protocol role at a time:
Zigbee coordinator (EZSP over TCP, for ZHA), Zigbee router (repeater in an existing mesh), Thread
border router, or Thread router (repeater joining an existing Thread network). The panel's own
entities always reach HA as an ESPHome device over Wi-Fi, whatever the radio does. The mode is picked in Home Assistant with the
`Radio mode` select of the panel's ESPHome device (`select.s1_panel_radio_mode`), status in the
`Radio status` text sensor.

| Mode (select option) | Needs root helper | State |
|---|---|---|
| `Off` (default) | no | **works today**: stops Aqara's radio services, radio idle |
| `Zigbee coordinator (TCP)` | yes | proven on device with temporary root (bellows over TCP) |
| `Zigbee router` | yes | plan + contract hook only (see [Zigbee router](#zigbee-router)) |
| `Thread border router` | yes | otbr-agent built and proven on the radio (spinel IID 2); a full border-router build is still to do |
| `Thread router` | yes | helper mode implemented (joins with a dataset from HA); runs on the proof otbr build; join not tested |

There is no "Aqara (stock)" mode: with Aqara's launcher/apps disabled and its cloud blocked
(debloat.md, AqaraCloud.kt), nothing can use the hub's own Zigbee network. **Stock comes back by
uninstalling the app (or `tools/install.sh --undo`) and rebooting**: all of Aqara's radio services
are init services started on every boot. `--undo` also force-stops the kiosk and `ctl.start`s them
right away.

Concurrent Zigbee + Thread on the one radio (multi-PAN) is deliberately **not** offered: it shares
one channel between both networks and Home Assistant deprecated the same design (SiLabs
multiprotocol on SkyConnect/Yellow). The helper still accepts an experimental `zigbee_tcp_thread`
id when set by hand; it is untested and unsupported.

All device-unique values (EUI64, PAN IDs, network keys) are redacted here; never commit them.

## Hardware and firmware (measured 2026-11-09)

- **Radio:** Silicon Labs EFR32 (MG21 per a teardown) on **SPI** (`/dev/spidev2.0`, root-only, 4 MHz,
  max bus bitrate reported by the secondary 4 MHz, RX capability 256 bytes). IRQ gpio 243,
  bootloader wake gpio 40, reset gpio 39 (`/etc/cpcd.conf`).
- **Host daemon:** `/vendor/bin/cpcd` = **CPCd 4.3.3.0** (git 823a260a, not in the public repo:
  built from a Gecko SDK 4.3.3 tree), Library API v3, RCP protocol v4. `disable_encryption: true`,
  no binding key (`/data/.cpcd` doesn't exist). stdout/stderr go to /dev/null, so its startup log is
  only visible by running `cpcd -c /etc/cpcd.conf -p` with the stack stopped (done once, read-only).
- **Secondary (radio firmware), from `cpcd -p`:** Secondary Protocol v4, **Secondary CPC v4.3.3**,
  **Secondary APP v0017**.
- **Firmware type:** **multi-PAN 802.15.4 RCP over CPC.** Its spinel version (from `otbr-agent
  --radio-version` on IID 2): `0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01`
  (OpenThread-based RCP, Gecko SDK 4.3.3 era).
- **CPC endpoints open:** only the system endpoint (`ctrl.cpcd.sock`) and **endpoint 12**
  (`ep12.cpcd.sock`, the 802.15.4 endpoint that carries spinel for every IID). Nothing else (no
  Bluetooth, no Zigbee-NCP endpoint).
- **Spinel IIDs:** valid 0-3 (zigbeed's help: "Upto three IIDs ... Valid values are 0-3"; 0 is
  broadcast). zigbeed uses `iid=1&iid-list=0`; **IIDs 2 and 3 are free**. Thread was proven on IID 2.
- **zigbeed** (`/vendor/bin/zigbeed`, bionic build from `gecko_sdk_4.3.3`): **EmberZNet 7.3.3.0
  build 0, EZSP protocol v12**, stack type 2. Config `/etc/zigbeed.conf`:
  `radio-url=spinel+cpc://cpcd_0?iid=1&iid-list=0`, `ezsp-interface=/tmp/ttyZigbeeNCP`. Its
  network/token storage is **hardcoded** to `/data/zigbee_host/zigbeed_host_token.nvm`. Mfg tokens
  (manufacturer/board name) are empty; no XNCP features.
- **No stock radio firmware on the device:** `/data/ota-files`, `/data/dfu`,
  `/data/lumi/ota_subdevice` are empty and no `*.gbl` exists anywhere readable. Aqara fetches RCP
  updates from its cloud (now blocked). This matters for recovery, see [Firmware](#firmware-per-mode).

## Aqara's radio stack

```
EFR32 --SPI--> cpcd (zigbee_cpcd) --CPC ep12, spinel iid=1--> zigbeed (zigbee_zigbeed)
   zigbeed <--EZSP/ASH--> /tmp/ttyZigbeeNCP (pts/1) ==socat (zigbee_socat)== /dev/ttyZigbeeNCP (pts/0)
   /dev/ttyZigbeeNCP <--EZSP/ASH--> Z3Gateway (zigbee_host) --MQTT 127.0.0.1:1883--> zigbee_agent
```

Note the direction of the pty pair: **zigbeed holds `/tmp/ttyZigbeeNCP`, the EZSP host (Z3Gateway)
holds `/dev/ttyZigbeeNCP`**. An EZSP-over-TCP bridge must therefore attach to `/dev/ttyZigbeeNCP`,
with Aqara's `zigbee_socat` left running. Both ptys are root-only.

Services (`/vendor/etc/init/gateway.rc`, all root, `u:r:su:s0`, `disabled`):

| Service | Process | Role |
|---|---|---|
| `zigbee_cpcd` | `cpcd -c /etc/cpcd.conf` | CPC daemon, owns the SPI bus |
| `zigbee_socat` | `socat -x -v pty,link=/dev/ttyZigbeeNCP pty,link=/tmp/ttyZigbeeNCP` | the pty pair |
| `zigbee_zigbeed` | `zigbeed` | Zigbee stack on the host, emulates an EZSP NCP |
| `zigbee_host` | `Z3Gateway -p ttyZigbeeNCP -d /data` | Aqara's coordinator app (EZSP host) |
| `zigbee_agent` | `zigbee_agent -f /vendor/etc/zigbeeAgent.conf` | Aqara's MQTT glue (+ `/data/ota-files`) |
| `ha_matter` | `ha_matter` | Aqara's Matter bridge (Wi-Fi, TCP/UDP 5540, mDNS) for its Zigbee devices; no radio fds |
| `zigbee_cpcd_factory` | oneshot | boot: sets `sys.gateway.config=10` (= start the 5 zigbee services) |
| `start_rcp_ota` | oneshot | `cpcd -c /etc/cpcd_ota.conf -f $(getprop sys.aqara.rcp_path)` |

Gotchas:

- **Watchdog:** the wrappers of cpcd, socat, zigbeed and Z3Gateway run
  `setprop sys.gateway.config 11` when their binary exits *by itself*; 11 = stop and restart all
  five zigbee services. `ctl.stop` kills the whole process group, so it doesn't trigger this, but
  stopping a provider before its consumer would (the consumer dies, its wrapper fires). Always stop
  consumers first: `ha_matter, zigbee_agent, zigbee_host, zigbee_zigbeed, zigbee_socat, zigbee_cpcd`.
- `ha_matter` is started by `sys.svc.gateway=5` during Aqara's boot sequence, the zigbee services
  by `sys.gateway.config=10` from `zigbee_cpcd_factory`.
- A killed cpcd leaves a stale `ctrl.cpcd.sock`; a fresh cpcd needs ~4 s to reach the secondary
  (first attempt "secondary seems unresponsive") and recreates the folder. Clients that connect
  too early get `CPC init failed -2`. CPC endpoint close is asynchronous: re-opening endpoint 12
  immediately after a client closed it fails ("CPC endpoint open failed"); retry after a few seconds.

**What Aqara stores:**

- `/data/zigbee/` (root 0600): `coordinator.info`, `device.info`, `deviceObject.info`,
  `fileCacheCfg.info`, `packetConfig.info`, `sum.log`. This is Z3Gateway's view: coordinator
  identity/network and paired-device cache. Contents not dumped (keys/IDs).
- `/data/zigbee_host/zigbeed_host_token.nvm`: zigbeed's NVM (network parameters, keys, frame
  counters): the authoritative network state. **Back it up (root) before the first non-stock Zigbee
  use** and keep the backup out of git: forming a network from ZHA, or joining as a router,
  overwrites it, and Aqara's stack would come back with a different network after an undo.
- Aqara's network on this panel is unused (the user's Zigbee runs on a separate coordinator).

## Services per mode

| Service | Off | Zigbee coordinator (TCP) | Zigbee router | Thread border router | Thread router |
|---|---|---|---|---|---|
| `zigbee_cpcd` | stopped | running | running | running | running |
| `zigbee_socat` | stopped | running | running | stopped | stopped |
| `zigbee_zigbeed` | stopped | running | running | stopped | stopped |
| `zigbee_host` (Z3Gateway) | stopped | stopped | stopped | stopped | stopped |
| `zigbee_agent` | stopped | stopped | stopped | stopped | stopped |
| `ha_matter` | stopped | stopped | stopped | stopped | stopped |
| ours (helper children) | none | socat TCP:6638 bridge on `/dev/ttyZigbeeNCP` | router host (see below) | `otbr-agent` on IID 2, `-B $BACKBONE` (wlan0) with the border-routing build | `otbr-agent` (no backbone) on IID 2 + dataset join |

`ha_master` (Aqara cloud + the old wall-base link) is not a radio service. It stays stopped by the
app's cloud block (AqaraCloud.kt), and the wall base uses the app's own UART driver, so `Off`
removes nothing the app uses.

## Proof: Zigbee coordinator over TCP (temporary root, nothing persistent)

1. `setprop ctl.stop zigbee_host` (Z3Gateway; as shell).
2. As root: `/vendor/bin/socat TCP-LISTEN:6638,reuseaddr,fork,max-children=1,range=192.168.1.0/24
   FILE:/dev/ttyZigbeeNCP,raw,echo=0`.
3. From the Mac, a read-only bellows probe (no `write_config`, no `networkInit`, no form/join/permit):

```
EZSP protocol version: 12
version(): protocol=12 stackType=2 stackVersion=0x7330
Manufacturer token: None | Board name: None | EmberZNet: 7.3.3.0 build 0
XNCP features: 0
EUI64: (redacted)
NodeId (after reset, before networkInit): 0xfffe
networkState: NO_NETWORK            # right after the ASH reset; zigbeed brings the stored network up on networkInit
CONFIG_MAX_END_DEVICE_CHILDREN: 64, CONFIG_SOURCE_ROUTE_TABLE_SIZE: 254, CONFIG_SUPPORTED_NETWORKS: 1
```

Across the VLAN (Mac 192.168.1.x -> panel 192.168.3.39) and on reconnects. On a reconnect the first
ASH reset times out once and bellows' retry succeeds; bellows knows zigbeed-over-TCP and waits for
its startup RSTACK. Z3Gateway was restarted afterwards and re-attached normally.

**Who accepts it:**

- **ZHA (bellows): yes.** Radio type EZSP, path `socket://<panel-ip>:6638`. bellows supports EZSP
  v4-v19, and HA's old multiprotocol add-on shipped exactly this topology (zigbeed + socat TCP).
  On first use ZHA forms its own network (or restores a backup) into zigbeed's NVM.
- **Zigbee2MQTT `ember`: no.** zigbee-herdsman 11.2.1 requires EZSP 13-19 (EmberZNet >= 7.4) and
  throws "Adapter EZSP protocol version (12) is not supported by Host [13-19]". The deprecated
  `ezsp` adapter (pre-7.4 firmware, still shipped in herdsman 11.2.1) and both adapters' shared
  transport accept `tcp://host:port`, but that is untested and on its way out. A newer zigbeed (see
  [Firmware](#firmware-per-mode)) would fix this.

## Proof: Thread on the same radio (spinel IID 2)

`tools/radio/build-otbr.sh` builds static ARM `otbr-agent` + `ot-ctl` (ot-br-posix v2026.11.0,
multi-PAN, SiLabs CPC vendor interface + libcpc 4.3.3; nothing vendored, see the script header for
pins and licenses). Device test, only `zigbee_cpcd` running (Zigbee stopped), root:

```
$ otbr-agent -s -v --radio-version 'spinel+cpc://cpcd_0?iid=2&iid-list=0'
VERSION:0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01
$ otbr-agent -s -d 5 -I wpan0 --auto-attach=0 'spinel+cpc://cpcd_0?iid=2&iid-list=0' &   # no -B
$ ot-ctl version        -> OPENTHREAD/ecd308b; POSIX; Nov 10 2026
$ ot-ctl rcp version    -> VERSION:0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01
$ ot-ctl state          -> disabled        channel -> 11    panid -> 0xffff
$ ot-ctl ifconfig       -> down            dataset active -> Error 23: NotFound
```

`wpan0` existed (DOWN, no routes) only while the agent ran; no network was formed or joined and
nothing was published. Coexistence with zigbeed (multi-PAN) was not tested: low priority since
concurrent mode is not offered.

The proof build is a **Thread agent, not yet a usable border router**: border routing, REST
(needed by HA's OTBR integration), mDNS/DNS-SD and the border agent are compiled out. The
`Thread border router` mode needs a second build profile with `OTBR_BORDER_ROUTING`, `OTBR_REST`,
an mDNS publisher and the border agent enabled, `OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run`, and
`BACKBONE=wlan0` in the helper's `radio.conf`. Joining it to the user's existing Thread network
(HA's OTBR app) means sharing the dataset from HA: a user decision, never automatic.

## Root helper contract (v1)

The root half of the modes lives in a helper. The Magisk boot patch installs it as an init service
([root.md](root.md); working on the reference panel since 2026-11-09). Files:

- `tools/radio/s1kiosk_radio.sh`: the helper (mksh).
- `tools/radio/s1kiosk_radio.rc`: the init snippet.
- `tools/radio/build-otbr.sh`: builds `otbr-agent`/`ot-ctl`.

| Item | Value |
|---|---|
| Init service | `s1kiosk_radio`: `/system/bin/sh /data/s1kiosk/radio/s1kiosk_radio.sh`, root, `u:r:su:s0`, not oneshot, **started on `sys.boot_completed=1`** |
| Install dir | `/data/s1kiosk/radio/` (root 0700): script, `otbr-agent`, `ot-ctl`, optional `zigbee-router`, optional `radio.conf`, `thread-dataset` (Thread router only; root 0600, secret) |
| Detection | app: helper present iff `init.svc.s1kiosk_radio` is non-empty (it only exists once the service ran, hence the start at boot and the never-exiting loop) |
| Wanted mode | `persist.s1kiosk.radio_mode` = `off` \| `zigbee_tcp` \| `zigbee_router` \| `thread` \| `thread_router` (default `off`) |
| Apply | app: `setprop persist.s1kiosk.radio_mode <id>` then `setprop ctl.restart s1kiosk_radio` (init kills the old process group, children included) |
| Status | helper: `sys.s1kiosk.radio.status` = `<applying\|ok\|error> <id>[: detail]`, `sys.s1kiosk.radio.version` = `1` |
| Overrides | `$DIR/radio.conf` (sourced): `ZIGBEE_PORT` (6638), `ZIGBEE_RANGE` (socat `range=`, e.g. the HA subnet), `BACKBONE`, `OTBR_ARGS`, `RADIO_URL` |

Behaviour: the helper brings Aqara's services to the table above (consumers stopped first),
starts its children and then **re-enforces every 3 s in every mode**, because Aqara's boot sequence
and its `sys.gateway.config=11` watchdog start the stock stack again. Dead children are restarted;
after 20 consecutive failures it reports `error … giving up` and idles.

Verified on the device with temporary root (run by hand with `S1R_MODE=<id>`, which skips the
persist prop):

- **`zigbee_tcp`:** Z3Gateway, zigbee_agent and ha_matter stopped; cpcd, socat and zigbeed running.
  Status `ok zigbee_tcp: EZSP on tcp:6638`, and bellows reads EZSP through the helper's bridge.
- **Watchdog:** firing Aqara's own watchdog (`setprop sys.gateway.config 11`) restarted cpcd and
  zigbeed, but Z3Gateway never ran: its wrapper sleeps 6 s and the helper stopped it first. The
  unchanged bridge served a new client on the recreated pty, because `fork` reopens the pty per
  connection.
- **`thread`:** only cpcd running, otbr-agent on IID 2, `ot-ctl state` = disabled, status
  `ok thread: otbr-agent on wpan0`.
- **`off`:** all six stopped, status `ok off: …`.
- **Off without the helper is stable:** after `ctl.stop` of all six, nothing restarted them during a
  15-minute watch (17:01-17:18). `sys.gateway.config=11` only fires when a wrapped binary exits by
  itself, and `ctl.stop` kills the wrapper too. Only a reboot brings the stack back, and the app
  stops it again on start.

## App side

`app/kiosk/.../radio/RadioController.kt`, `RadioMode.kt`:

- The select offers `Off`, `Zigbee coordinator (TCP)`, `Zigbee router`, `Thread border router`,
  `Thread router`. The
  stored value is the mode id. Labels and retired values from older builds (`Aqara (stock)`,
  `Thread (future)`) parse to `Off`.
- **No helper:** `Off` is applied directly with `ctl.stop` (the app uid may control init services
  because SELinux is permissive; same mechanism as AqaraCloud.kt). It is re-applied after every app
  start, since init restarts Aqara's stack on boot. Choosing a root mode is refused: the select snaps
  back and `Radio status` says "… needs the root helper (init service s1kiosk_radio is not
  installed)". A stored root mode without a helper keeps the choice and applies Off meanwhile.
- **Helper present:** every mode, Off included, is delegated (prop + `ctl.restart`, see the
  contract). The app never touches Aqara's services itself then. On app start it restarts the helper
  only if its mode or status doesn't match. `Radio status` shows the helper's status.
- `Radio status` is refreshed on the radio thread every 15 s and after every change, so no `getprop`
  runs on the ESPHome thread.

## Zigbee router

Goal: the panel as a mains-powered **repeater** in the user's existing ZHA mesh (separate
coordinator). Router only: no application endpoints.

**Recommended approach: drive zigbeed as an EMBER_ROUTER from a small EZSP host.** zigbeed is a full
EmberZNet stack; routing, link status, many-to-one route handling and child tables all run inside
it. A host only has to:

1. Speak ASH and a small EZSP v12 subset over `/dev/ttyZigbeeNCP` (the path the TCP bridge uses).
2. Configure the stack for a router. ZDO is handled by the stack itself; no HA-profile endpoint is
   registered. ZHA lists it as a router node with no entities, and if its interview complains it is
   still a working repeater.
3. Set the initial security state for joining (well-known TC link key `ZigBeeAlliance09`, so the
   trust center sends the network key).
4. Scan for joinable networks and `joinNetwork(EMBER_ROUTER, …)`, **only after the user opens
   permit-join in ZHA**.
5. Stay attached. zigbeed persists the joined network in its NVM, so later starts are just
   `networkInit`, and the host only has to keep the EZSP session (and its callbacks) alive.

Where the host runs: preferably **in the app (Kotlin)** against an EZSP bridge the helper binds to
127.0.0.1. That needs no extra native binary, and the ASH/EZSP code can be unit-tested on the JVM.
A native `zigbee-router` binary is the alternative. The helper already has the hook: in
`zigbee_router` mode it runs `$DIR/zigbee-router /dev/ttyZigbeeNCP` if present, otherwise it reports
`error zigbee_router: zigbee router host not installed`. The localhost-bridge variant is a small
helper change once the in-app host exists.

Blockers / not done:

- No EZSP host exists yet: ASH framing plus about 10 EZSP v12 commands and their callbacks.
- Nothing can be verified without joining a network, which was out of scope here.
- Joining overwrites zigbeed's NVM (Aqara's coordinator state), so back it up first.
- EmberZNet 7.3.3 is old but adequate for a router.
- bellows/zigpy can't do this: zigpy only supports coordinators, and `bellows join` joins as an end
  device.

**Alternative: router firmware on the EFR32** (a standalone EmberZNet SoC router image). No host is
needed, but it means flashing, losing CPC/Thread, and no stock image to go back to (see below).
Not recommended.

## Firmware per mode

**Recommendation: keep the current multi-PAN RCP firmware and run one protocol at a time.** Every
alternative needs a custom image for this board, and there is no stock GBL on the device to roll
back to (see [OTA path](#ota-path-not-run)). Nothing was flashed.

| Mode | Recommendation | Risk |
|---|---|---|
| Off | stop the services, leave the firmware alone | none |
| Zigbee coordinator (TCP) | current RCP + Aqara's zigbeed 7.3.3 on `iid=1` only, supervised by the helper | low; ZHA only (EZSP 12), no Z2M `ember` |
| Zigbee router | current RCP + zigbeed + a custom EZSP host (see [Zigbee router](#zigbee-router)); defer | host app not written |
| Thread border router | current RCP + otbr-agent on `iid=2`, zigbeed stopped | low; needs the full BR build |
| Thread router | current RCP + the proof otbr build on `iid=2`, zigbeed stopped | low |

Why one protocol at a time on a multi-PAN RCP is fine, while concurrent use is not:

- Home Assistant / Nabu Casa deprecated the SiLabs multiprotocol setup:
  - The Nabu Casa ZBT-1 docs recommend disabling multiprotocol unless Thread is actively used.
  - Both networks are forced onto one channel (home-assistant/core#92904).
  - The add-on was deprecated and then removed (home-assistant/addons#3833, #4850). Core 2026.6 ships
    a repair that migrates users off it.
  - The documented problems are concurrency and zigbeed crashes (zigpy#894), plus GSDK 4.3/4.4
    stability and ABI breaks between zigbeed and RCP builds (darkxst/silabs-firmware-builder
    discussion #41).
  - Nothing official says zigbeed with a single protocol is broken. Inference: the risk left in
    single-protocol use is zigbeed's own maturity, which is mitigated by the helper restarting it.
- **Dedicated Zigbee NCP** (EmberZNet on the EFR32):
  - Transports: EZSP-SPI is supported in EmberZNet 7.x-9.x (SiLabs EZSP-SPI host guide), and
    EZSP-over-CPC has existed since 7.1 (AN1333).
  - Host bridge: bellows only speaks ASH over serial or `socket://`, and no existing SPI/CPC-to-ASH
    bridge was found. Using either transport means writing that bridge **and** building a custom
    MG21 SPI image.
  - Not worth it for ZHA, which already works.
- **Newer zigbeed** (EZSP >= 13 for Z2M `ember`):
  - Binaries: SiLabs ships zigbeed as glibc armhf .debs, so Android would need a chroot or static
    rebuild.
  - Daemon: cpcd 4.9.x accepts secondaries speaking CPC protocol v4-v6.
  - Blocker: zigbeed must match the RCP's spinel/SDK version. The Nerivec firmware builder notes
    "requires RCP 2024.6.1". **A newer zigbeed therefore means reflashing**, which is the same blocker
    as below.
- **Dedicated OpenThread RCP over SPI:**
  - Possible in principle: NCP SPIDRV firmware, host `OT_POSIX_CONFIG_RCP_BUS=SPI` /
    `spinel+spi://` with the IRQ and reset GPIOs (AN1256).
  - The image's pins must match the board and the bootloader.
  - It gains nothing over Thread on the multi-PAN RCP via CPC, which is proven here.
- **No community MG21 SPI targets:** the NabuCasa and darkxst silabs-firmware-builder targets are all
  UART (SkyConnect/ZBT-1, Yellow, Sonoff ZBDongle-E…). An image for this board would need its exact
  pinout (SPI peripheral, IRQ, wake) and a matching bootloader.
- **Recovery and signing:**
  - `cpcd -f` enters the Gecko bootloader with the reset and wake pins (gpio 39/40) and uploads the
    GBL over SPI. The bootloader survives an incompatible app image, so the pins can still reach it.
  - The Gecko bootloader can be configured to **require signed (and encrypted) GBLs** (UG489), and
    whether Aqara's does can't be determined from the host without trying. If unsigned images are
    rejected, only Aqara-signed images can be installed, and there is none on the device.
  - Rule: **don't flash without proof that unsigned images are accepted and a known-good fallback
    GBL in hand.**

Sources: Nabu Casa ZBT-1 multiprotocol docs; home-assistant/core#92904; home-assistant/addons#3833,
#4850; darkxst/silabs-firmware-builder discussion #41; zigpy#894; SiLabs EZSP-SPI host guide
(EmberZNet 9.1), AN1333 (Zigbee/EZSP over CPC), AN1256 (OpenThread RCP / host bus), UG489 (Gecko
Bootloader, secure boot / signed GBL); Nerivec silabs-firmware-builder; zigbee-herdsman 11.2.1
`EZSP_MIN_PROTOCOL_VERSION = 13` (checked in its source).

## Thread router

An FTD that **joins** the user's existing Thread network (the one formed by HA's OTBR app) and routes
for it, without border routing, REST, mDNS or a border agent. It is simpler than a border router,
and the **proof build from `build-otbr.sh` already is that profile** (all of those compiled out).

- Join: the user copies the active dataset TLVs from HA (OTBR integration / Thread panel) into the
  panel. The panel then runs `ot-ctl dataset set active <tlvs>; ot-ctl ifconfig up; ot-ctl thread
  start`. The dataset is a secret: it belongs in the helper's root-only `radio.conf` or a root-only
  file, not in app prefs or logs.
- Contract: mode id `thread_router`. Services: cpcd only. Child: otbr-agent on `iid=2` without
  `-B`. Once its socket answers, the helper runs `dataset set active $(cat $DIR/thread-dataset)`,
  `ifconfig up`, `thread start`. Without the file it reports `error thread_router: no Thread
  dataset …` and joins nothing.
- In the select. The dataset is not yet transferable from the app: for now it is placed in
  `$DIR/thread-dataset` as root. A later step is an app-side path, e.g. a write-only ESPHome text
  entity whose value the helper picks up once and never echoes back.
- Never forms a network. Joining happens only with a dataset the user supplied. Not tested on the
  device (that would mean joining the user's network).

## OTA path (not run)

- `start_rcp_ota` is an init service (`gateway.rc`, oneshot). It is started by
  `on property:sys.zigbee.ota=20`, which first stops all five zigbee services, and it runs
  `cpcd -c /etc/cpcd_ota.conf -f $(getprop sys.aqara.rcp_path)`. `cpcd_ota.conf` differs from
  `cpcd.conf` only in SPI bitrate (2 MHz) and `disable_encryption: false`.
- Because SELinux is permissive, the app uid could set `sys.aqara.rcp_path` and `sys.zigbee.ota=20`,
  or `ctl.start start_rcp_ota`, i.e. flash the radio without root. **Don't**: there is no stock GBL
  to recover with, and a wrong image bricks the radio until reflashed.
- `sys.zigbee.ota=0/1` run `zigbee_ota_enter.sh` / `zigbee_ota_quit.sh`, which toggle PB8 (ISP /
  bootloader select) and pulse PB7 (reset) via debugfs pinctrl. `zigbee_isp.sh` and
  `zigbee_reset.sh` set them individually. cpcd uses gpio 40 (wake) / 39 (reset) as bootloader
  recovery pins itself.

## Open risks

- **Root helper needs the Magisk boot patch** ([root.md](root.md)). Panels without it only get `Off`. Persistent `Off` across a reboot without the helper relies on the app starting and
  re-applying it (a few seconds of Aqara's stack at boot).
- **EZSP-over-TCP has no authentication.** Restrict with `ZIGBEE_RANGE` (the HA subnet) and the
  VLAN firewall.
- **EZSP 12 only:** ZHA works; Z2M `ember` doesn't. A newer zigbeed is the non-flashing fix,
  if one can be obtained (licensing, ARM build).
- **zigbeed NVM:** Aqara's network state is overwritten by any Zigbee mode use. Back it up.
- **Thread:** the full border-router build (border routing, REST, mDNS) is untested, and so are
  routes and RA on `wlan0` under Android's netd. Android's own routing rules may need ip-rule work.
  The static glibc binary is fine when run by root from init.
- **Aqara's watchdog** keeps restarting things. The helper re-enforces every 3 s; a Z3Gateway start
  is caught by its wrapper's 6 s sleep, but this is a race, verified once.
- **Multi-PAN (`zigbee_tcp_thread`)** is not offered and untested.
