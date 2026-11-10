# Zigbee / Thread radio modes

The panel's 802.15.4 radio can be offered to Home Assistant in one single-protocol role at a time:
Zigbee coordinator (EZSP over TCP, for ZHA), Zigbee router (repeater in an existing mesh), Thread
border router, or Thread router (repeater joining an existing Thread network). The panel's own
entities always reach HA as an ESPHome device over Wi-Fi, whatever the radio does. The mode is picked in Home Assistant with the
`Radio mode` select of the panel's ESPHome device (`select.s1_panel_radio_mode`), status in the
`Radio status` text sensor.

| Mode (select option) | Needs root helper | State (2026-11-10, reference panel) |
|---|---|---|
| `Off` (default) | no | **works**: stops Aqara's radio services, radio idle |
| `Zigbee coordinator (TCP)` | yes | **works with ZHA (bellows)**: form, read-back, restart verified from a computer. Forming/restoring from ZHA needs a bellows fix ([below](#bellows-reset-bug-zha-form-and-restore)); "keep the radio's network" works today |
| `Zigbee router` | helper v2 | **experimental**: the app's EZSP host brings zigbeed up as a router, scans, is ready to join; joining a real network not tested |
| `Thread border router` | yes | **experimental**: border-router build (`PROFILE=br`); Home Assistant's OTBR REST calls verified against a fresh test network; LAN <-> Thread routing not tested with a real device |
| `Thread router` | helper v2 | **experimental**: dataset hand-off from the app verified (the stack starts Thread with it); joining a real network not tested |

There is no "Aqara (stock)" mode: with Aqara's launcher/apps disabled and its cloud blocked
([research/debloat.md](research/debloat.md), AqaraCloud.kt), nothing can use the hub's own Zigbee network. **Stock comes back by
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
| ours (helper children) | none | socat TCP:6638 bridge on `/dev/ttyZigbeeNCP` | socat bridge on 127.0.0.1:6639; the app's router host on it | `otbr-agent` on IID 2, `-B wlan0`, REST :8081 (border-router build) + forwarding/rules | `otbr-agent` (no backbone, REST on localhost) on IID 2 + dataset join |

`ha_master` (Aqara cloud + the old wall-base link) is not a radio service. It stays stopped by the
app's cloud block (AqaraCloud.kt), and the wall base uses the app's own UART driver, so `Off`
removes nothing the app uses.

## Proof: Zigbee coordinator over TCP (temporary root, nothing persistent)

1. `setprop ctl.stop zigbee_host` (Z3Gateway; as shell).
2. As root: `/vendor/bin/socat TCP-LISTEN:6638,reuseaddr,fork,max-children=1,range=<ha-subnet>
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

Across the VLAN (computer and panel on different subnets) and on reconnects. On a reconnect the first
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

## Zigbee coordinator, end to end (2026-11-10)

Mode switched the app's way (setup channel `set_radio`, i.e. `RadioController.request`); from a
computer, bellows 1.2.1 / zigpy 2.4.1 in a throwaway venv against `socket://<panel-ip>:6638`, doing
what ZHA does (nothing touched any other network; no permit-join):

| Step | Result |
|---|---|
| `ControllerApplication.probe` | ok |
| connect, `version` | EZSP 12, stack type 2, EmberZNet 7.3.3.0 build 0 |
| `load_network_info` on the factory NVM | Aqara's factory network (backed up first, outside the repo) |
| `form_network()` (fresh random PAN/EPID/key, energy scan) | **hangs without the bellows fix below**; with it: formed, channel 25, update id 0 |
| new connection, `load_network_info` | the formed network persists (same fingerprint), coordinator `0x0000` |
| `ControllerApplication.new(start_radio=True)` (ZHA's normal start) | ok in ~8 s |

The panel's NVM now holds that freshly formed test network (random keys that only ever existed in a
deleted temp database). ZHA can use it with "keep the radio's network settings".

### bellows reset bug (ZHA form and restore)

zigbeed answers an ASH reset in **~4.2 s** (every time; it resets its stack and the RCP's IID 1),
bellows waits 2.5 s (`RESET_TIMEOUT`). The second attempt then catches the late RSTACK through the
"zigbeed resets on startup" path, but `EZSP.startup_reset()` has replaced the protocol object with a
v4 one **without resetting `_ezsp_version`** (still 12). `version()` then sees 12 == 12, never switches
back to the v12 frame format, and the next command's reply is misparsed (`getValue` answered as
`getLibraryStatus`); the command times out. First connects are fine (`_ezsp_version` starts at 4);
everything that resets mid-session breaks: `form_network`, `write_network_info` (backup restore,
migration). One-line fix in bellows (verified here as a monkeypatch):
`startup_reset()` must call `self._switch_protocol_version(v4.EZSPv4.VERSION)` instead of only
creating `v4.EZSPv4(...)`. Not filed upstream yet (owner's call).

### Who may connect

Neither the EZSP bridge (tcp:6638) nor the border router's REST API (tcp:8081, which hands out the
Thread network key) has authentication. Since helper v3 (2026-11-10) **only Home Assistant may
connect by default**, enforced by the panel's firewall; see [Firewall](#firewall-only-home-assistant-by-default).

`ZIGBEE_RANGE` (socat `range=`) still works on top of the firewall. In `radio.conf`: empty (default) = no extra limit;
`lan` = the panel's current wlan0 subnet, recomputed every 3 s so DHCP changes are followed
(offline: `127.0.0.1/32`); or a fixed CIDR. The default stays open on purpose: Home Assistant often
sits on another VLAN than an IoT panel (as on the reference network), and a subnet guess would lock it
out. Verified: with `lan`, a client on the panel's subnet gets zigbeed's RSTACK, one from another
VLAN is reset. The bridge also sets TCP keepalive (30 s idle, 3 x 10 s) so a vanished client doesn't
hold the single slot (`max-children=1`) forever. Status: `applying zigbee_tcp: waiting for zigbeed`
until zigbeed holds its pty, then `ok zigbee_tcp: EZSP on tcp:6638` (`(clients from <cidr>)` with a
range); the app adds who may connect and the ZHA serial path.

### Firewall: only Home Assistant by default

**Who is allowed.** Home Assistant talks to the panel's ESPHome API, so the app knows its address:
whenever an **authenticated** Home Assistant connection (Noise with the provisioned API key; not
plaintext, not the zero-key provisioning handshake) subscribes to states, the app records the peer
address, one IPv4 and one IPv6 at most (`radio_ha_ipv4` / `radio_ha_ipv6` in the app's prefs, so they
survive restarts). HA on another VLAN is fine: the address is the routed peer address. If HA's
address changes, the next connection updates the list (HA reconnects at least every 90 s here).
The owner can replace the automatic list with the `Radio allowed clients` text entity (in HA: the
panel's ESPHome device, Configuration; on the panel: the hidden settings dialog, *Radio: who may
connect*, which also shows who is allowed now): addresses or
CIDRs separated by spaces or commas, `any` (no firewall, the v2 behaviour), or empty = automatic.
Loopback (the router host's bridge on 127.0.0.1, `adb forward`) is always allowed.

**Hand-off (contract v3).** The app writes the list to `persist.s1kiosk.radio_allow` (`""` = no HA
address yet, `any`, or normalized addresses/CIDRs, at most 91 bytes) before every mode change, when HA's
address changes, and every 15 s if it drifted. The helper re-reads it every loop (3 s) without a
restart, so a new address never bounces the radio. It is a persist prop so the helper has it at boot,
before the app runs. A prop fits better than a file here: it is not secret, short, and one value.
`RADIO_ALLOW` in `radio.conf` wins over it: `RADIO_ALLOW="192.0.2.10 198.51.100.0/24"`, `any`, or
`none` (loopback only); empty = use the app's list. The helper publishes what it enforces in
`sys.s1kiosk.radio.allowed` = `<app|conf> <any|none|addresses…>` (empty in Off; a `radio.conf` list
too long for a prop ends with `+<n>`).

**Rules.** In every mode except Off, for both ports whichever mode is active (Android 10 here ships
legacy `iptables`/`ip6tables` 1.6.1 in `/system/bin`; no `multiport` match, so one jump per port):

```
-A INPUT -p tcp -m tcp --dport 6638 -j S1RADIO      # inserted at the top of INPUT, before netd's
-A INPUT -p tcp -m tcp --dport 8081 -j S1RADIO      # bw_INPUT / fw_INPUT jumps
-A S1RADIO -i lo -j RETURN
-A S1RADIO -s 192.0.2.10/32 -j RETURN               # one per allowed address of this family
-A S1RADIO -j DROP
```

The same chain exists for IPv6 (only `lo` and the IPv6 entries). The chain's contents are replaced
atomically (`iptables-restore --noflush` with `:S1RADIO -`, which flushes just that chain in the same
commit), so changing the list never opens the ports in between. Every loop the helper checks the jumps
and the chain size (`iptables-save` needs no lock, ~40 ms each) and repairs them: netd may flush INPUT
when it restarts, and duplicates or stale ports are removed (exactly one jump per port, verified). With
no HA address known both ports are closed except to loopback. `any` removes the rules; **Off removes the
chain and the jumps** in both families. netd's own INPUT jumps are never touched. If iptables rejects an
entry the helper falls back to loopback only (fail closed) and logs it.

**Status.** `Radio status` names who may connect, e.g. `ok Zigbee coordinator (TCP): listening on :6638
for Home Assistant (<ha-ip>). ZHA: …`, `… for nobody yet (waiting for Home Assistant to connect over
ESPHome)`, `… for 198.51.100.0/24 (Radio allowed clients)`, `… for any client (no firewall)`; the border
router adds `; REST open to …`. With a v2 helper: `… for any client (root helper v2 has no firewall;
update it)`. The setup channel's radio state has `allowed` (`""`, `any` or the addresses) and a
`set_radio_allow` request (same as the entity).

Verified on the reference panel (2026-11-10; the computer is on another VLAN than the panel, like HA):

| Check | Result |
|---|---|
| Coordinator, HA's address unknown (HA kept off the ESPHome port by a temporary panel-side rule) | status `… for nobody yet (waiting …)`; `nc -z <panel> 6638` from the computer fails; through `adb forward` (loopback) bellows `probe` succeeds |
| `set_radio_allow <computer-ip>` | `nc -z` and a bellows `probe` from the computer succeed; chain `lo`, `<computer-ip>/32`, DROP |
| back to automatic, HA reconnects | list = HA's address within seconds, status `… for Home Assistant (<ha-ip>)`, the computer is refused |
| `radio.conf` `RADIO_ALLOW=<computer-ip>` / `any` / `none` | connects / no rules, connects / refused; status says `(radio.conf)` |
| `RADIO_ALLOW=<computer-ip>` + `ZIGBEE_RANGE=lan` | firewall passes, socat's range still resets the computer (other subnet) |
| 3 mode applies, 2 helper restarts, 6 list changes | exactly 2 jumps and 1 chain per family |
| a duplicate jump, a stray jump on 8081, a deleted IPv6 jump, a foreign chain rule | repaired within one loop |
| Thread border router (19 s) | REST `:8081` refused from the computer, `GET /node/state` 200 over loopback |
| Off | no `S1RADIO` chain or jumps in either family, `sys.s1kiosk.radio.allowed` empty |

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

The proof build is a **Thread agent, not a usable border router**: border routing, REST, mDNS and
the border agent are compiled out. It is still what `tools/radio/out/` holds and the boot image
bundles; the border router is a second profile:

## Thread border router build (`PROFILE=br`, 2026-11-10)

`PROFILE=br OTBR_RUNTIME_DIR=/data/s1kiosk/radio/run tools/radio/build-otbr.sh` writes
`tools/radio/out/br/{otbr-agent,ot-ctl}` (2.3 MB static): same pins plus cJSON and cpp-httplib (MIT,
ot-br-posix's own gitlinks), with border routing, the REST server (`:8081`), **OpenThread's own mDNS**
(`OTBR_MDNS=openthread`: no avahi/mDNSResponder daemon needed on Android) publishing `_meshcop._udp`
and SRP-registered services, SRP server auto-enable, and the border agent. Off: NAT64, TREL, backbone
router (needs kernel MRT6), DHCPv6-PD, DNS upstream (no `/etc/resolv.conf`), firewall, web, D-Bus. The
simulated-RCP test in the script now also queries the REST API. The same binary runs without `-B`
(Thread router).

The helper detects the build (`sys.s1kiosk.radio.otbr` = `br` | `agent` | `none`). With `br`,
`thread` mode runs `otbr-agent -B wlan0 --rest-listen-address 0.0.0.0 --rest-listen-port 8081` and
sets up what Android needs for routing: `net.ipv6.conf.all.forwarding=1` (old value restored in every
other mode), `accept_ra=2` on wlan0, and two policy rules, because Android puts each interface's
routes in its own table (ifindex + 1000, e.g. wpan0's OMR route) and netd's rules never consult them
for forwarded packets: `14500: iif wlan0 lookup <wpan0's table>` (follows wpan0's ifindex, which
changes on every agent restart) and `14501: iif wpan0 lookup wlan0`. The FORWARD chains are empty.

Verified from a computer on another VLAN (`python-otbr-api`, replaying the calls of Home Assistant's
`otbr` config flow and `async_setup_entry`; nothing added to HA):

| Call | Result |
|---|---|
| `GET /node`, `/node/state`, `/node/ba-id`, `/node/coprocessor/version` | 200; RCP `0017/2.3.3.0_GitHub-e6df00dd6` |
| `get_active_dataset_tlvs` before | None (204) |
| `create_active_dataset(channel, network_name, random pan)`, `set_enabled(True)` | ok: fresh test network created **on the panel**, panel became leader |
| `get_extended_address`, `get_active_dataset` (JSON + TLVs) | ok, 103-byte dataset with all 10 TLVs |
| `set_channel(24, delay)` (PUT pending dataset) | ok; the network moved channel after the delay |
| restart of the agent | auto-attached to the stored network, leader again, REST unchanged |

On the panel: `br state` running, OMR + on-link ULA prefixes, RA/RS sent and received, SRP server
running and in Network Data, `ba state` Active, `_meshcop._udp` registered by OpenThread's mDNS.
**Not tested:** traffic between a LAN host and a real Thread device through the panel, Matter
commissioning, coexistence with Android's own mDNS on the same port (both bind 5353 with
SO_REUSEPORT; nothing broke during the test). REST has no authentication either (it exposes the
dataset, i.e. the network key): since helper v3 the firewall lets only Home Assistant reach it (see
[Firewall](#firewall-only-home-assistant-by-default)); `REST_LISTEN=127.0.0.1` in `radio.conf` limits it
to the panel. Joining the user's existing Thread network means sharing the
dataset from HA: a user decision, never automatic.

## Root helper contract (v1)

The root half of the modes lives in a helper. The Magisk boot patch installs it as an init service
([research/root.md](research/root.md); working on the reference panel since 2026-11-09). Files:

- `tools/radio/s1kiosk_radio.sh`: the helper (mksh).
- `tools/radio/s1kiosk_radio.rc`: the init snippet.
- `tools/radio/build-otbr.sh`: builds `otbr-agent`/`ot-ctl`.

Contract **v3** (2026-11-10): v2 plus the firewall ([Firewall](#firewall-only-home-assistant-by-default)).
v1 helpers lack the router modes, v2 helpers the firewall; the app checks the version:

| Item | Value |
|---|---|
| Init service | `s1kiosk_radio`: `/system/bin/sh ${MAGISKTMP}/s1kiosk_radio.sh`, root, `u:r:su:s0`, not oneshot, **started on `sys.boot_completed=1`** |
| State dir `$DIR` | `/data/s1kiosk/radio/` (root 0700): optional newer `s1kiosk_radio.sh` (override, see below), optional `otbr-agent`/`ot-ctl` (win over the bundled ones), `radio.conf`, `thread/` (border router settings), `thread-router/`, `thread-dataset` (root 0600, secret), `zigbee-nvm/` (NVM copies), `forwarding.orig` |
| Detection | app: helper present iff `init.svc.s1kiosk_radio` is non-empty (it only exists once the service ran, hence the start at boot and the never-exiting loop) |
| Wanted mode | `persist.s1kiosk.radio_mode` = `off` \| `zigbee_tcp` \| `zigbee_router` \| `thread` \| `thread_router` (default `off`) |
| Apply | app: `setprop persist.s1kiosk.radio_mode <id>` then `setprop ctl.restart s1kiosk_radio` (init kills the old process group, children included) |
| Status | helper: `sys.s1kiosk.radio.status` = `<applying\|ok\|error> <id>[: detail]` (`applying <id>: waiting for zigbeed\|otbr-agent` until the stack answers), `sys.s1kiosk.radio.version` = `3`, `sys.s1kiosk.radio.otbr` = `br`\|`agent`\|`none`, `sys.s1kiosk.radio.allowed` = `<app\|conf> <any\|none\|addresses…>` (v3; empty in Off). Every status is cut to 91 bytes (`setprop` refuses longer values) |
| Allow-list (v3) | app: `persist.s1kiosk.radio_allow` = `""` (no HA address yet: loopback only) \| `any` \| space-separated IPv4/IPv6 addresses or CIDRs; re-read every 3 s, no restart. Enforced with the `S1RADIO` iptables/ip6tables chain on `ZIGBEE_PORT` and `REST_PORT` in every mode but Off |
| Overrides | `$DIR/radio.conf` (sourced): `ZIGBEE_PORT` (6638), `ZIGBEE_RANGE` (empty \| `lan` \| CIDR), `ROUTER_PORT` (6639), `BACKBONE` (wlan0 with a `br` build), `REST_LISTEN` (0.0.0.0), `REST_PORT` (8081), `OTBR_ARGS`, `RADIO_URL`, `RADIO_ALLOW` (v3: empty = the app's list \| `any` \| `none` \| addresses/CIDRs) |
| Zigbee router | EZSP bridge on `127.0.0.1:$ROUTER_PORT` only; the app's host drives zigbeed. zigbeed's NVM is copied to `$DIR/zigbee-nvm/` (last 3, only when changed) whenever the mode starts |
| Thread dataset hand-off | the app writes hex TLVs to its own `files/radio/thread-dataset` (0600); the helper moves it to `$DIR/thread-dataset` (refuses symlinks, > 1 KiB, non-hex), deletes the app's copy, restarts the agent, joins. Never logged |
| Helper override | a root-owned `$DIR/s1kiosk_radio.sh` (in the root-owned dir) is exec'd instead of the bundled one (`S1R_NO_OVERRIDE` stops the hand-off); it still uses the boot image's bundled binaries unless `$DIR` has its own |

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
  `Thread (future)`) parse to `Off`. `Zigbee router` and `Thread router` need helper v2
  (`sys.s1kiosk.radio.version`); with an older helper they are refused with "needs the root helper v2".
- More entities: `Radio allowed clients` (text, config: empty = Home Assistant only, else addresses/CIDRs
  or `any`; see [Firewall](#firewall-only-home-assistant-by-default)), `Join Zigbee network` /
  `Leave Zigbee network` buttons (router mode only), and a write-only `Thread dataset` text (ESPHome password mode; state always empty) that hands the dataset
  to the helper.
- `Radio status`: in coordinator mode `listening on :6638 for <who>` and `ZHA: radio type EZSP, serial
  path socket://<ip>:6638`; the border router adds `REST open to <who>`;
  in router mode it shows the router host's state instead of the helper's.
- Setup screen radio step: Off, Zigbee coordinator, Thread border router (experimental) in the first
  row, the two repeater roles (experimental) in a compact second row; a one-line "what next" note.
  After the coordinator is applied, it shows the ZHA serial path and a QR code that opens
  `<HA>/config/zha/add` (ZHA's "Add devices" page) on a phone. The setup channel (web installer)
  offers the same five options; options carry optional `experimental` and `repeater` flags.
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

**Implemented (2026-11-10) in the app:** `radio/ezsp/Ash.kt` (ASH codec, byte-identical to bellows),
`radio/ezsp/Ezsp.kt` (link layer with window 1 + retransmit, EZSP v12 frames/structs, blocking client),
`radio/ZigbeeRouterHost.kt` (the router). The helper runs a socat bridge on `127.0.0.1:6639` only;
RadioController runs the host while the helper reports `ok zigbee_router`.

- Each session: ASH reset (zigbeed takes ~4.2 s; the first data frame after it is only ACKed after
  ~4.8 s, so `version` gets 8 x 1.6 s), `version` 12, config (stack profile 2, security level 5),
  endpoint 1 (HA profile, range extender, Basic server), `networkInit`. The config phase is needed
  after **every** reset: without it zigbeed's `networkInit` reports NOT_JOINED although its NVM holds
  a network (seen on the panel).
- NVM with a router network: up, status `in a network on channel N`. With a **coordinator** network
  (ZHA's): it is not run (reset right after detecting it) and not erased; status `idle: the radio
  holds a Zigbee coordinator network …`. Only an explicit Join (or Leave) replaces it, and the helper
  has copied the NVM before.
- Join (`Join Zigbee network` button): leave the current network (verified with `networkState`),
  `setInitialSecurityState` (well-known TC link key, as EmberZNet's network-steering does), active
  scan on 11-26, the strongest network with permit-join open and stack profile 2, `joinNetwork(ROUTER)`,
  wait for NETWORK_UP (60 s), `updateTcLinkKey`. No joinable network: `ready to join: no Zigbee network
  allows joining right now (N seen)`.
- ZHA's interview: ZDO is answered by zigbeed; the host answers Basic-cluster reads (manufacturer
  `S1 Unleashed`, model `S1 Panel Zigbee router`, mains powered).

Verified on the panel (from a computer through `adb forward`, `ZigbeeRouterLiveTest`, dry run so
`joinNetwork` is never called; and in the app through the setup channel): session bring-up,
coordinator network detected and left alone, Leave erases it (`networkInit` NOT_JOINED afterwards),
security state + active scan (networks seen, none open), NVM backup by the helper and restoring it
(same network fingerprint afterwards). JVM tests with a fake NCP cover join, leave, scan selection and
the Basic reply. **Not verified: joining a real network** (needs the owner to open permit-join).

**Alternative: router firmware on the EFR32** (a standalone EmberZNet SoC router image). No host is
needed, but it means flashing, losing CPC/Thread, and no stock image to go back to (see below).
Not recommended.

## Flash path, verified (2026-11-09)

This was a dry run: the **identical** stock image was reflashed with Aqara's own tools, as root over
adb, with the radio helper in `off`. The plan and its sources are kept with the backups (not in
this repo, because they quote Aqara binaries).

| Step | Command (root) | Result |
|---|---|---|
| Versions | `/vendor/bin/cpcd -c /etc/cpcd.conf -p` | protocol v4, CPC v4.3.3, APP `0017` |
| Bootloader info, no write (pins off, `-a 0017`, 128-byte zero "image") | `cpcd -c <cpcd.conf with recovery pins off> -f <zeros> -a 0017` | `Secondary bootloader: Gecko SDK`, "Firmware up to date". `PROP_BOOTLOADER_INFO` = type 3 (Gecko), version `0x02030001` (**v2.3**, customer 1), capabilities 0 (always 0 over CPC) |
| Pin entry, no transfer | `cpcd -c /etc/cpcd_ota.conf -e`, then `setprop sys.zigbee.ota 1` | "Secondary is in bootloader"; the quit reset brought back APP `0017` |
| Reflash | `cpcd -c /etc/cpcd_ota.conf -f '/vendor/firmware/lichun#30000000#17#20250821#8f636f.bin'` | "Firmware upgrade successful" in 19.5 s (2 MHz SPI, progress 0→100) |
| Confirm | `cpcd -p`; Thread mode, `ot-ctl rcp version` | APP `0017`; `0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01` |

What this proves:
- The PB7 (reset) / PB8 (wake) recovery path works, so a broken app can always be reflashed.
- The bootloader accepts **unsigned, LZ4-compressed** GBLs.
- Bootloader secure boot is not enforced: the running app declares no signature.
- An app-only GBL cannot touch the bootloader. The Gecko bootloader drops writes outside the app
  area, and this image has no bootloader or SE-upgrade tag.

Rules for any future flash:
- Use **only `/vendor/bin/cpcd`** for `-e`/`-f`. Aqara's build drives the reset pin inverted
  compared with upstream (PB7=1 holds the EFR32 in reset), so an upstream or self-built cpcd would
  keep the radio in reset.
- With the recovery pins enabled, `-f` skips all version checks and always flashes.
- Don't set `persist.sys.zb_ver`; Aqara's OTA logic reads it.

## Firmware per mode

**Recommendation: keep the current multi-PAN RCP firmware and run one protocol at a time.** Every
alternative needs a custom image for this board. The stock GBL *is* on the device, and the flash
path has been proven by reflashing it (see [Flash path, verified](#flash-path-verified-2026-11-09)),
so a custom image is now possible but still not needed.

| Mode | Recommendation | Risk |
|---|---|---|
| Off | stop the services, leave the firmware alone | none |
| Zigbee coordinator (TCP) | current RCP + Aqara's zigbeed 7.3.3 on `iid=1` only, supervised by the helper | low; ZHA only (EZSP 12), no Z2M `ember` |
| Zigbee router | current RCP + zigbeed + the app's EZSP host (see [Zigbee router](#zigbee-router)) | join untested |
| Thread border router | current RCP + the `PROFILE=br` otbr-agent on `iid=2`, zigbeed stopped | low; routing to real devices untested |
| Thread router | current RCP + otbr-agent (either build) on `iid=2`, zigbeed stopped | low |

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
  - **Unsigned GBLs are accepted:** the stock GBL is unsigned and unencrypted, and it flashed
    successfully. A known-good fallback GBL is on the device. Both preconditions of the old
    "don't flash" rule are now met (see [Flash path, verified](#flash-path-verified-2026-11-09)).
  - Any custom image must still match this board's pinout and CTUNE location, and ship with a
    matching host cpcd/zigbeed.

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
- In the select (helper v2). The dataset comes from the panel's write-only `Thread dataset` entity
  in HA (password field): the app writes it to its private files, the helper takes it (see the
  contract). Own settings dir `$DIR/thread-router`, so it never auto-attaches to the network the
  border-router mode stored. Without a dataset: `ok thread_router: ready to join: waiting for a Thread
  dataset from Home Assistant` (agent up, Thread disabled, no border routing, no mDNS, REST on
  localhost only).
- Never forms a network on its own initiative: it only starts Thread with a dataset the user
  supplied. Verified with the panel's own throwaway test dataset (handed over as the app uid): taken,
  app copy deleted, agent restarted, Thread started (alone on the channel it became leader of that
  test network). Joining the user's real network not tested.

## OTA path (not run)

- `start_rcp_ota` is an init service (`gateway.rc`, oneshot). It is started by
  `on property:sys.zigbee.ota=20`, which first stops all five zigbee services, and it runs
  `cpcd -c /etc/cpcd_ota.conf -f $(getprop sys.aqara.rcp_path)`. `cpcd_ota.conf` differs from
  `cpcd.conf` only in SPI bitrate (2 MHz) and `disable_encryption: false`.
- Because SELinux is permissive, the app uid could set `sys.aqara.rcp_path` and `sys.zigbee.ota=20`,
  or `ctl.start start_rcp_ota`, i.e. flash the radio without root. **Don't do this from the app:** a
  wrong image leaves the radio without a working app until it is reflashed with the stock GBL as
  root (see [Flash path, verified](#flash-path-verified-2026-11-09)).
- `sys.zigbee.ota=0/1` run `zigbee_ota_enter.sh` / `zigbee_ota_quit.sh`, which toggle PB8 (ISP /
  bootloader select) and pulse PB7 (reset) via debugfs pinctrl. `zigbee_isp.sh` and
  `zigbee_reset.sh` set them individually. cpcd uses gpio 40 (wake) / 39 (reset) as bootloader
  recovery pins itself.

## Open risks

- **Root helper needs the Magisk boot patch** ([research/root.md](research/root.md)). Panels without it only get `Off`. Persistent `Off` across a reboot without the helper relies on the app starting and
  re-applying it (a few seconds of Aqara's stack at boot).
- **EZSP-over-TCP and the OTBR REST API have no authentication.** The helper's firewall (v3) admits
  only Home Assistant's address. That is an address check, not authentication: a host that can take
  over or intercept HA's address (same segment, the router) still gets in. `ZIGBEE_RANGE`,
  `REST_LISTEN=127.0.0.1` and the VLAN firewall add to it. HA's last IPv4 and IPv6 are both kept; a stale
  one stays allowed until HA connects from a new address of that family.
- **bellows reset bug** (above): ZHA can't form or restore onto this radio until bellows is fixed.
- **EZSP 12 only:** ZHA works; Z2M `ember` doesn't. A newer zigbeed is the non-flashing fix,
  if one can be obtained (licensing, ARM build).
- **zigbeed NVM:** Aqara's network state is overwritten by any Zigbee mode use. Back it up.
- **Thread:** the border-router build's REST/BR/SRP/mDNS side works; forwarding between the LAN and
  real Thread devices (with the policy rules above) is untested, and netd could rewrite rules on a
  Wi-Fi reconnect (the helper re-adds them every 3 s). The static glibc binary is fine when run by
  root from init.
- **Aqara's watchdog** keeps restarting things. The helper re-enforces every 3 s; a Z3Gateway start
  is caught by its wrapper's 6 s sleep, but this is a race, verified once.
- **Multi-PAN (`zigbee_tcp_thread`)** is not offered and untested.
