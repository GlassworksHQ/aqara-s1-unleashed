# Zigbee / Thread radio modes

> **Setting a mode up?** [radio-guide.md](radio-guide.md) is the short how-to. This page is the
> technical reference: how each mode works, what was tested, the root helper and its contract.

The panel's 802.15.4 radio can be offered to Home Assistant in one single-protocol role at a time:
Zigbee coordinator (EZSP over TCP, for ZHA), Zigbee router (repeater in an existing mesh), Thread
border router, or Thread router (repeater joining an existing Thread network). The panel's own
entities always reach HA as an ESPHome device over Wi-Fi, whatever the radio does. The mode is picked in Home Assistant with the
`Radio mode` select of the panel's ESPHome device (`select.s1_panel_radio_mode`), status in the
`Radio status` text sensor.

| Mode (select option) | Needs root helper | State (2026-11-10, reference panel) |
|---|---|---|
| `Off` (default) | no | **works**: stops Aqara's radio services, radio idle |
| `Zigbee coordinator (TCP)` | yes | **works with ZHA (bellows)**: verified with Home Assistant's ZHA on the panel; form, read-back, restart verified from a computer. Forming/restoring from ZHA needs bellows#774 in a Home Assistant release ([below](#bellows-reset-bug-zha-form-and-restore)); "keep the radio's network" works today. **Zigbee2MQTT** (`ember`, experimental): with the `Zigbee stack` select on *Silicon Labs 8.2.2* (helper v4; [Zigbee stack](#zigbee-stack-aqara-or-silicon-labs-helper-v4)); form, resume and the joining window verified on the panel, not soak-tested with real devices |
| `Zigbee router (repeater)` | helper v2 | **experimental**: joined a real ZHA network as a repeater (2026-11-10, strong link); not soak-tested |
| `Thread border router` | yes | **experimental**: border-router build (`PROFILE=br`); Home Assistant's OTBR REST calls verified against a fresh test network; LAN <-> Thread routing not tested with a real device |
| `Thread router (repeater)` | helper v2 | **experimental**: joined Home Assistant's real Thread network and was promoted to router (2026-11-10); not soak-tested |

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
- **Stock radio firmware on the device:** `/data/ota-files`, `/data/dfu` and `/data/lumi/ota_subdevice`
  are empty, but the stock RCP image is on `/vendor` as `/vendor/firmware/lichun#30000000#17#20250821#8f636f.bin`
  (an unsigned, uncompressed GBL, APP `0017`; found 2026-11-09). It is the rollback target of the radio
  firmware path ([Radio firmware updates](#radio-firmware-updates-helper-v4)).

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

| Service | Off | Zigbee coordinator (TCP) | Zigbee router (repeater) | Thread border router | Thread router (repeater) |
|---|---|---|---|---|---|
| `zigbee_cpcd` | stopped | running | running | running | running |
| `zigbee_socat` | stopped | running | running | stopped | stopped |
| `zigbee_zigbeed` | stopped | running | running | stopped | stopped |
| `zigbee_host` (Z3Gateway) | stopped | stopped | stopped | stopped | stopped |
| `zigbee_agent` | stopped | stopped | stopped | stopped | stopped |
| `ha_matter` | stopped | stopped | stopped | stopped | stopped |
| ours (helper children) | none | socat TCP:6638 bridge on `/dev/ttyZigbeeNCP` (Silicon Labs stack, v4: also our zigbeed 8.2.2, and `zigbee_zigbeed` stopped) | socat bridge on 127.0.0.1:6639; the app's router host on it | `otbr-agent` on IID 2, `-B wlan0`, REST :8081 (border-router build) + forwarding/rules | `otbr-agent` (no backbone, REST on localhost) on IID 2 + dataset join |

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
- **Zigbee2MQTT `ember`: not with Aqara's zigbeed.** zigbee-herdsman 11.2.1 requires EZSP 13-19
  (EmberZNet >= 7.4) and throws "Adapter EZSP protocol version (12) is not supported by Host [13-19]".
  The deprecated `ezsp` adapter works with it (tested, [research/zigbee2mqtt.md](research/zigbee2mqtt.md)),
  but is on its way out. The fix is Silicon Labs' zigbeed 8.2.2 (EZSP 18) on the same stock radio
  firmware: the `Zigbee stack` choice ([below](#zigbee-stack-aqara-or-silicon-labs-helper-v4)).

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
creating `v4.EZSPv4(...)`. Submitted as [zigpy/bellows#774](https://github.com/zigpy/bellows/pull/774)
and merged on 2026-11-10. ZHA gets it with the first Home Assistant release that ships a bellows
containing it; until then, use "keep the radio's network settings".

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
address changes, its connection drops and the next one, from the new address, updates the list.

**Home Assistant without an encryption key.** If the panel never got an API key from Home Assistant
(with Home Assistant older than 2026.11, which doesn't hand out keys by itself), Home Assistant
connects unencrypted. That connection is never counted as Home Assistant's, because anything on the
network could make the same one. The radio ports then stay
closed to everyone: ZHA and the OTBR integration can't connect, and *Radio status* says `nobody yet
(waiting for Home Assistant to connect over ESPHome)`. To fix it, either give the panel a key
(Home Assistant 2026.11 or later sets one up by itself the next time it connects; see
[esphome.md](esphome.md#encryption-automatic)) or put Home Assistant's address in *Radio
allowed clients*.

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
the border agent are compiled out. The boot image and the radio bundle carry the border-router
profile instead (`tools/radio/out/` = `PROFILE=br` since 0.3.0):

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

## Root helper contract (v4)

The root half of the modes lives in a helper. The Magisk boot patch installs it as an init service
([research/root.md](research/root.md); working on the reference panel since 2026-11-09). Files:

- `tools/radio/s1kiosk_radio.sh`: the helper (mksh).
- `tools/radio/s1kiosk_radio.rc`: the init snippet.
- `tools/radio/build-otbr.sh`: builds `otbr-agent`/`ot-ctl`.

Contract **v4** (2026-11-10): v3 plus the loader and signed radio bundles ([Radio bundles](#radio-bundles-helper-v4)),
the Zigbee stack choice ([Zigbee stack](#zigbee-stack-aqara-or-silicon-labs-helper-v4)) and radio firmware
updates ([Radio firmware updates](#radio-firmware-updates-helper-v4)). v3 added the firewall
([Firewall](#firewall-only-home-assistant-by-default)); v1 helpers lack the router modes, v2 helpers the
firewall, v3 helpers the v4 items. The app checks the version and works with v2, v3 and v4 helpers; what
needs v4 says so ("needs the root helper v4 … update the panel's boot image once", in the `Radio helper`
sensor and the refused select). v4 needs a **new boot image** (web installer or `fel-install.sh`); it is
the last one the root side needs, since everything above the loader then updates with the app.

| Item | Value |
|---|---|
| Init service | `s1kiosk_radio`: `/system/bin/sh ${MAGISKTMP}/s1kiosk_radio.sh`, root, `u:r:su:s0`, not oneshot, **started on `sys.boot_completed=1`** |
| State dir `$DIR` | `/data/s1kiosk/radio/` (root 0700): `bundle/` (v4: `current/` = the installed radio bundle, `version` = the highest version ever installed, `bad-<v>` markers), `sisdk/` (v4: Silicon Labs' zigbeed, pinned), `zigbeed-sisdk/` (its NVM), `fw/` (v4: firmware state and logs), `radio.conf`, `thread/` (border router settings), `thread-router/`, `thread-dataset` (root 0600, secret), `zigbee-nvm/` (NVM copies), `forwarding.orig`, `dev-override` (developers only, below) |
| Detection | app: helper present iff `init.svc.s1kiosk_radio` is non-empty (it only exists once the service ran, hence the start at boot and the never-exiting loop) |
| Wanted mode | `persist.s1kiosk.radio_mode` = `off` \| `zigbee_tcp` \| `zigbee_router` \| `thread` \| `thread_router` (default `off`) |
| Apply | app: `setprop persist.s1kiosk.radio_mode <id>` then `setprop ctl.restart s1kiosk_radio` (init kills the old process group, children included) |
| Status | helper: `sys.s1kiosk.radio.status` = `<applying\|ok\|error> <id>[: detail]` (`applying <id>: waiting for zigbeed\|otbr-agent` until the stack answers), `sys.s1kiosk.radio.version` = `4`, `sys.s1kiosk.radio.otbr` = `br`\|`agent`\|`none`, `sys.s1kiosk.radio.allowed` = `<app\|conf> <any\|none\|addresses…>` (v3; empty in Off). Every status is cut to 91 bytes (`setprop` refuses longer values) |
| Allow-list (v3) | app: `persist.s1kiosk.radio_allow` = `""` (no HA address yet: loopback only) \| `any` \| space-separated IPv4/IPv6 addresses or CIDRs; re-read every 3 s, no restart. Enforced with the `S1RADIO` iptables/ip6tables chain on `ZIGBEE_PORT` and `REST_PORT` in every mode but Off |
| Overrides | `$DIR/radio.conf` (sourced): `ZIGBEE_PORT` (6638), `ZIGBEE_RANGE` (empty \| `lan` \| CIDR), `ROUTER_PORT` (6639), `BACKBONE` (wlan0 with a `br` build), `REST_LISTEN` (0.0.0.0), `REST_PORT` (8081), `OTBR_ARGS`, `RADIO_URL`, `RADIO_ALLOW` (v3: empty = the app's list \| `any` \| `none` \| addresses/CIDRs), `ZIGBEE_STACK` (v4: `aqara` \| `sisdk`, wins over the app), `ZIGBEED_ARGS` (v4: extra zigbeed 8.2.2 arguments, e.g. `-v 1 -d 3`) |
| Zigbee router | EZSP bridge on `127.0.0.1:$ROUTER_PORT` only; the app's host drives zigbeed. zigbeed's NVM is copied to `$DIR/zigbee-nvm/` (last 3, only when changed) whenever the mode starts |
| Thread dataset hand-off | the app writes hex TLVs to its own `files/radio/thread-dataset` (0600); the helper moves it to `$DIR/thread-dataset` (refuses symlinks, > 1 KiB, non-hex), deletes the app's copy, restarts the agent, joins. Never logged |
| Loader (v4) | the boot image's `s1kiosk_radio.sh` checks `$DIR/bundle/current` with `s1kiosk_bundle.sh --check` (signature, every file's SHA-256, version not below the floor) at every start and runs its `s1kiosk_radio.sh`; binaries: bundle first, then the boot image. A bundle helper that exits within a minute 3 times running is marked `bad-<v>` and the boot image's helper runs. Props: `sys.s1kiosk.radio.source` = `boot[ (why)]` \| `bundle <v>` \| `dev`, `sys.s1kiosk.radio.bundle` = `none` \| `ok <v>` \| `bad: <why>` |
| Zigbee stack (v4) | app: `persist.s1kiosk.radio_zigbee` = `aqara` (default) \| `sisdk`; helper: `sys.s1kiosk.radio.zigbee` = `aqara` \| `sisdk ready <ver>` \| `sisdk missing <ver> <sha256>` (app: download it) \| `sisdk no-runtime` \| `sisdk bad <why>`; zigbeed hand-off in the app's `files/radio/zigbeed-8.2.2` |
| Time zone (v4) | app/installer: `persist.s1kiosk.timezone` = an Olson id; helper: a background loop re-applies it with `service call alarm 3 s16 <id>` when `persist.sys.timezone` differs (Aqara's SystemManager resets it to `Antarctica/Casey` at boot), every 5 s for 10 min, then every 15 s; `sys.s1kiosk.timezone.status` = `<ok\|error\|none> [<id>][: detail]`. Runs in every mode, also after the helper gave up; part of the helper, so it updates with the radio bundle. With no zone chosen, the zone in place at the helper's first look is adopted unless it is `Antarctica/Casey`/`GMT`/`UTC` |
| Thread leave (v4) | app: `files/radio/thread-leave` (taken within 3 s in any mode, ignored after 2 min); helper: `sys.s1kiosk.radio.thread_leave` = `<ok\|error> <epoch>: <detail>` |
| Bundle state (v4) | root: `/data/s1kiosk-pub/radio-bundle` (root 0644 in a root 0755 dir) = `<ok\|bad\|none> <version> <floor>`, what the app decides from |
| Radio firmware (v4) | app: `files/radio/fw-request` = `<id> <sha256 prefix>` (taken within 3 s, ignored after 2 min); helper: `sys.s1kiosk.radio.fw` = `<idle\|flashing\|ok\|error> <id>[: detail]`, `sys.s1kiosk.radio.fw_targets` = `<id>:<sha12>:<APP> …` |
| Developer override | only with a root-created `$DIR/dev-override`: a root-owned `$DIR/s1kiosk_radio.sh` runs instead of everything, and binaries in `$DIR` win. Before v4 these were honoured without the marker; now a leftover copy is ignored (and logged), so it can't shadow updates |

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

## Radio bundles (helper v4)

Until v3 the root side lived only in the boot image: every helper fix needed a new boot image, and
flashing `boot` needs the FEL button. Since v4 the boot image holds a **small, stable loader** and the
current helper as the fallback; everything else comes with app updates as a **signed radio bundle**.

**What's in the boot image (once):** `s1kiosk_radio.sh` (loader + the v4 helper as fallback),
`s1kiosk_bundle.sh` + `.rc` (the install service), `s1kiosk_verify` (a 70 KB static ARM Ed25519
checker: [`tools/root/verify/`](../tools/root/verify/s1kiosk_verify.c), Monocypher 4.0.3, built
reproducibly by `tools/root/build-verify.sh`), `s1kiosk_bundle.pub` (the project's public key), and
otbr-agent/ot-ctl as before.

**What's in a bundle:** a `manifest` (format in `tools/root/s1kiosk_bundle.sh`), its Ed25519
signature, and the files it lists with their sizes and SHA-256: today the helper, otbr-agent, ot-ctl
and the Silicon Labs runtime (`sisdk/`). It may list radio firmware images (`firmware` lines); the
project ships none. The release workflow builds and signs it (`tools/radio/make-bundle.py`, key in the
`S1_RADIO_BUNDLE_KEY` environment secret, read only by the workflow's signing job, which signs only
commits on `main` and checks every input against committed hashes:
[releasing.md](releasing.md#3b-the-radio-bundle-key-once)); its version is the APK's versionCode
(`make-bundle.py` refuses a higher one, which would lock panels out of every later bundle). The APK carries it as assets; it is not published on its own.

**Flow:** the app compares the bundle it carries with the root-written, world-readable
`/data/s1kiosk-pub/radio-bundle` (`<ok|bad|none> <version> <floor>`, written by the service and by the
loader at every helper start; never a `sys.*` property, which any app could fake to block updates). If the
panel has none, an older one, or the same one marked bad, the app copies it into its private
`files/radio/bundle-in/`, sets `sys.s1kiosk.bundle.status=requested` and starts `s1kiosk_bundle` (at start,
then every 30 minutes; one try per version and hour). It deletes its staging copy only once the service has
finished. The service copies manifest + signature into a fresh root-only dir, checks the signature,
refuses a version below the floor (the highest version ever installed, and at least the installed one),
copies only the listed files with `s1kiosk_verify --copy` (below), checks each copy's SHA-256, records the
new floor, then swaps `bundle/current` with renames and restarts the helper. Reinstalling the version that
is installed is a no-op, unless its helper was marked bad: then it is reinstalled and the mark cleared. The loader checks the installed bundle again at every helper start. Status:
`sys.s1kiosk.bundle.status`, and the `Radio helper` diagnostic sensor in HA (which helper runs, which
bundle is installed, which one the app carries, the last error).

**Threat model.**

- *Other apps on the panel* can start init services and set properties (SELinux is permissive). They
  cannot write the kiosk's private files, `/data/s1kiosk` (root 0700), or the boot image. So the most
  they can do is make the service (re)install a bundle the kiosk staged, which still has to carry our
  signature and a version at least as high as the installed one, or spoof status properties.
- *A compromised kiosk app* can stage anything, but without the private key it can only feed back
  bundles we signed, never older than the newest installed (anti-rollback floor in root-only
  `bundle/version`). It could hold back updates; it can't run its own root code this way.
- *Network attackers* see nothing new: the bundle comes inside the signed APK, which the app update path
  already checks (SHA-256 + signing certificate), and the root side checks the bundle's own signature.
- *Key compromise* is the one that matters: the key lets someone produce root code the panels accept, if
  they also get it into the kiosk's storage. It lives only in `~/secure` and the CI secret; rotating it
  needs a new boot image on every panel (the public key is in the boot image on purpose: nothing on
  `/data` can replace it).
- *A broken bundle* can't brick the radio side: the loader falls back to the boot image's helper when the
  bundle fails its check or its helper keeps exiting, and `Radio helper` says why.
- *A compromised kiosk racing the root side* (swapping a checked path for a symlink, a FIFO or
  `/dev/zero` between a shell check and the copy): every root read of a kiosk-owned path (bundle files,
  the zigbeed download, firmware and Thread-leave requests, the Thread dataset, app updates in
  `s1kiosk_update.sh`) goes through `s1kiosk_verify --copy|--move`, which walks the path with
  `openat(O_NOFOLLOW)`, opens the file `O_NONBLOCK`, checks owner, type, link count and size on the open
  file and copies exactly that many bytes. So root never follows a link, never blocks, never fills `/data`.
- *Unauthenticated ESPHome clients* (a panel without a provisioned key accepts plaintext/zero-PSK
  connections from the LAN) cannot use `Radio firmware image`, `Flash radio firmware`, `Zigbee stack` or
  `Leave Thread network`: those commands are refused unless the connection is Noise with the
  provisioned key.

Shell tests for all of this (signature, tampering, symlinks, other owners, hard links, rollback, the
loader's fallback): `tools/radio/test/run.sh` (32-bit ARM container, mksh as Android's shell, mocks).

## Zigbee stack: Aqara or Silicon Labs (helper v4)

The coordinator mode can run either Zigbee stack on the same stock radio firmware (spinel IID 1, the
same pty pair, the same `:6638` bridge and firewall):

| `Zigbee stack` option | Stack | EZSP | Works with |
|---|---|---|---|
| `Aqara 7.3.3 (EZSP 12, ZHA)` (default) | Aqara's `/vendor/bin/zigbeed`, its init service | 12 | ZHA (bellows); Zigbee2MQTT's deprecated `ezsp` adapter |
| `Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)` | Silicon Labs' zigbeed 8.2.2 from Simplicity SDK 2025.6.3, through the bundle's glibc runtime | 18 | Zigbee2MQTT `ember` (`adapter: ember`, `port: tcp://<panel>:6638`), ZHA |

How it works ([research/zigbee2mqtt.md](research/zigbee2mqtt.md) has the panel tests):

- **zigbeed itself is never shipped by us.** It is Silicon Labs MSLA object code. When the stack is
  chosen, the helper reports `sisdk missing 8.2.2 <sha256>`; the **app** fetches the 253 KB armhf .deb
  straight from Silicon Labs' GitHub release (an HTTP range of `debian-bookworm.zip`, falling back to the
  whole 22.9 MB zip), checks the zip/.deb hashes, extracts `usr/local/bin/zigbeed` and checks its SHA-256
  (`a66d4584…e3bb`), then writes it to its private `files/radio/zigbeed-8.2.2`. The helper takes it only
  with that exact SHA-256 and size into `$DIR/sisdk/` (root 0700). Download state shows in `Radio status`.
- **What we do ship** (bundle `sisdk/`, built by `tools/radio/build-sisdk-runtime.sh`, with
  `sisdk/NOTICE.txt` and `licenses/`): `zigbeed.sh` (our wrapper), `libcpc.so.3` built from cpc-daemon
  4.3.3 (Silicon Labs MSLA source sections: distributable compiled, only inside the product for use with
  the Silicon Labs radio, not standalone, hence no separate download; the same terms as the libcpc
  already inside otbr-agent), and glibc 2.35 / libstdc++ / libgcc_s unmodified from Ubuntu 22.04's cross
  packages (LGPL-2.1+, GPL-3.0+ with the GCC Runtime Library Exception; licence texts included and the
  exact Ubuntu source packages named, which are kept permanently on Launchpad). Byte-identical to the
  libraries tested on the panel. Not legal advice.
- The helper stops `zigbee_zigbeed` and runs `zigbeed.sh -r 'spinel+cpc://cpcd_0?iid=1&iid-list=0' -p
  /tmp/ttyZigbeeNCP` with its own NVM in `$DIR/zigbeed-sisdk/host_token.nvm` (backed up to
  `zigbee-nvm/sisdk-*.nvm` at each start, last 3). Aqara's NVM is never touched; switching back to Aqara's
  stack gives back Aqara's network.
- **The pty is drained before the bridge serves anyone**, for both stacks: a fresh zigbeed writes an
  RSTACK that nobody reads, and Zigbee2MQTT's `ember` adapter would take it for the answer to its own
  reset and then see the real one as an NCP crash (found on the panel). The helper reads the pty until
  it is quiet after each new zigbeed process; a connected client keeps its session. Since 0.4.2 every
  new client is also drained on connect (`$DIR/ezsp-relay.sh` behind socat `EXEC:`: discard until 0.2 s
  quiet, at most 2 s, then relay), so the ERROR frame zigbeed writes after a client vanished never reaches
  the next one. Verified on the panel: three Zigbee2MQTT `ember` connects in a row, 0 CRC errors, ASH
  reset answered at 4.4 s as before.
- **Reset timing risk:** zigbeed 8.2.2 answers an ASH reset in 4.1-4.4 s (measured), and herdsman's
  `ember` adapter waits 5 s for it. The margin is thin: a slow moment on the panel could make Zigbee2MQTT
  fail a start (it retries on its next start). Watch it in the soak test.
- Thread modes are unaffected; the router mode always uses Aqara's stack (the app's router host speaks
  EZSP 12). It is an unsupported pairing for Silicon Labs (an 8.2.2 host on a 4.3.3 RCP): experimental
  until soak-tested with real devices.
- Needs helper v4 and a bundle with `sisdk/`; otherwise the select snaps back and `Radio helper` says why.
  `ZIGBEE_STACK=` in `radio.conf` overrides the app.

## Radio firmware updates (helper v4)

A root path for flashing the EFR32's RCP image, **never automatic**:

1. In HA, `Radio firmware image` (config): `Aqara stock image` or `Image from the radio bundle`. The
   choice is armed for 2 minutes (`Radio firmware` shows the countdown).
2. Press `Flash radio firmware` within that time. The app writes `<id> <sha256 prefix>` to its
   `files/radio/fw-request`; it does nothing else.
3. The helper takes the request (only kiosk-owned, no symlinks, at most 2 minutes old, naming a hash
   that matches the image) and:
   - resolves the image: Aqara's GBL from `/vendor/firmware/lichun#30000000#*.bin` (pinned hash
     `d7b79c6f…8fa9` = APP `0017`; another Aqara file is accepted with the version from its name), or a
     `firmware` item of the **signed** bundle (its SHA-256 checked again right before use);
   - checks it is an app-only GBL: known tags only, no bootloader or Secure Engine upgrade tag, no
     encryption, ending with the end tag exactly at EOF;
   - checks that the stock image (the rollback target) is there and valid, and records it in
     `fw/stock.ref` (the pre-flash backup reference: the file on read-only `/vendor`);
   - stops every radio process and Aqara service (and refuses if any cpcd still runs), reads the current
     version (`/vendor/bin/cpcd -c /etc/cpcd.conf -p`, "Secondary APP");
   - flashes with **Aqara's own** `/vendor/bin/cpcd -c /etc/cpcd_ota.conf -f <image>` (the only proven
     path; upstream cpcd drives this board's reset pin the wrong way), progress from cpcd's own
     `progress:N` lines (elapsed seconds until the first one; 0.4.2), 2 tries;
   - verifies: the radio must report the image's declared APP version over CPC; then the spinel version
     is read with `otbr-agent --radio-version` on IID 2 when available;
   - on failure, **rolls back to the stock image** the same way and says so; then restarts itself, which
     brings the selected mode back.
4. Status in `Radio firmware` (`sys.s1kiosk.radio.fw`): `idle`, `flashing <id>: 40%`, `ok <id>: APP …`,
   `error <id>: … rolled back to stock (APP 0017)`. Mode and stack changes wait while a flash runs; the
   bundle service won't swap files under it. Before flashing, the helper stops every radio service and
   refuses while any cpcd (or Aqara's OTA/boot services) is still running; during the flash it stops
   anything of Aqara's that starts again (its boot sequence or watchdog can start `zigbee_cpcd` any time;
   our cpcd holds the SPI lock, so that can't disturb the transfer).
5. **An interrupted flash** (helper killed, power cut) may leave the radio without an application. At its
   next start the helper does not start the mode's stack: it flashes Aqara's stock image back first (at
   most twice across restarts). If that fails too, the radio stays off (`Radio status`: "radio firmware
   missing after an interrupted flash") until the owner flashes Aqara stock from Home Assistant. The
   bootloader is never touched, and the recovery pins reach it whatever the app area holds.
6. The GBL check refuses tag lengths outside the file (the shell's 32-bit arithmetic would otherwise loop
   on a length like `0xfffffff8`).

Verified on the panel (2026-11-10): re-flashing Aqara's stock image through this path.

Limits, on purpose: only images whose CPC protocol Aqara's cpcd 4.3.3 speaks (v4) can be verified, so
an image built from a newer SDK (CPC v5/v6) would fail verification and be rolled back until a bundle
also brings a matching host cpcd. No image is shipped; see
[research/rcp-firmware.md](research/rcp-firmware.md) for what building one for this board takes.

## App side

`app/kiosk/.../radio/RadioController.kt`, `RadioMode.kt`:

- The select offers `Off`, `Zigbee coordinator (TCP)`, `Zigbee router (repeater)`, `Thread border router`,
  `Thread router (repeater)` (0.4.3; before, the repeaters were `Zigbee router` and `Thread router`).
  The stored value is the mode id, which never changed (`zigbee_router`, `thread_router`). The old
  labels are still accepted when they arrive (an automation calling `select.select_option` with them
  fails in Home Assistant, which checks the current options, but a stored or hand-sent label works).
  Retired values from older builds (`Aqara (stock)`, `Thread (future)`) parse to `Off`. The repeater
  modes need helper v2 (`sys.s1kiosk.radio.version`); with an older helper they are refused with
  "needs the root helper v2".
- More entities: `Radio allowed clients` (text, config: empty = Home Assistant only, else addresses/CIDRs
  or `any`; see [Firewall](#firewall-only-home-assistant-by-default)), `Join Zigbee network` /
  `Leave Zigbee network` buttons (router mode only), a write-only `Thread dataset` text (ESPHome password mode; state always empty) that hands the dataset
  to the helper (pattern: hex plus whitespace, the no-break and zero-width spaces a copy can carry, which
  the app drops; Home Assistant caps every text entity at 255 characters, so longer datasets go in through
  the panel's settings menu), and (helper v4) `Leave Thread network`: in any mode, Off included, the helper detaches a
  running Thread router and deletes the stored dataset (`$DIR/thread-dataset`, the network key) and the
  router's OpenThread settings (`$DIR/thread-router`); `Radio status` then says "Thread network left". The
  border-router mode's own network (`$DIR/thread`) is not touched.
- `Radio status`: in coordinator mode `listening on :6638 for <who>` and `ZHA: radio type EZSP, serial
  path socket://<ip>:6638`; the border router adds `REST open to <who>`;
  in Zigbee router mode it shows the router host's state instead of the helper's; in Thread router
  mode the role and the network's name, from the helper (`joined “Name” as a child; it becomes a
  router when the network promotes it`, `… as a router`, `dataset set, looking for the network`,
  `no dataset yet`).
- Setup screen radio step: Off, Zigbee coordinator, Thread border router (experimental) in the first
  row, the two repeater roles (experimental) in a compact second row; a one-line "what next" note.
  After a mode other than Off is applied, it shows a short version of that mode's follow-up card
  (`setup/RadioFollowUp`, sized to fit the step card without scrolling): coordinator: the ZHA serial
  path (and the Zigbee2MQTT line with Silicon Labs' stack) and a QR code to `<HA>/config/zha/add`;
  Zigbee router: the same QR code (open joining), the router status and Join; Thread border router:
  the OTBR URL, a QR code to `<HA>/config/integrations/dashboard/add?domain=otbr` and one line on
  border router vs router; Thread router: a QR code to `<HA>/config/thread`, two short copy steps and
  the Thread role. No QR code without Home Assistant's address. The full card (stack choice, Leave
  buttons, notes, the dataset field) is in the settings menu. The setup channel (web installer) offers the same five options; options carry optional
  `experimental` and `repeater` flags.
- Kiosk settings menu, Radio section (0.4.3): a mode picker (same options and `RadioController.request`
  path as the select; switching asks first, since it restarts the radio), the same follow-up card for
  the applied mode (with the Zigbee stack choice in coordinator mode and, in Thread router mode, a
  write-only `Thread dataset` field behind "Advanced"), then "who may connect". Its buttons call the
  same RadioController functions as the HA entities; at the panel, being there is the authorization
  (as for the allow-list). `Leave Thread network` shows whenever the helper reports a stored Thread
  router network (`sys.s1kiosk.radio.thread_stored` = `yes`, any mode), else in the Thread modes when
  the helper doesn't report it (bundles before 0.4.3).
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
- Helper v4 entities: `Zigbee stack` (select, config), `Radio helper` (diagnostic: which helper and
  bundle run, or what to update), `Radio firmware` (diagnostic), `Radio firmware image` (select, config)
  and `Flash radio firmware` (button, config). In coordinator mode with the Silicon Labs stack, `Radio
  status` adds the Zigbee2MQTT settings (`adapter ember, port tcp://<ip>:6638`).

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
the Basic reply.

**Joined a real network (2026-11-10):** with permit-join open in the reference home's ZHA, *Join*
found the network (channel 25) and joined; ZHA listed it as "S1 Unleashed S1 Panel Zigbee router"
with a strong link (LQI 216, RSSI −46 dBm). Leave and removing that one device from ZHA put the mesh
back as it was. Not yet run for days with traffic through it.

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
- The bootloader accepts **unsigned, uncompressed** GBLs. (The stock GBL's program tag is ERASEPROG
  `0xFD0303FD`, not PROG_LZ4 `0xFD0505FD`, re-parsed 2026-11-10; whether this bootloader also takes LZ4
  is unknown, so build custom images uncompressed.)
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
| Zigbee coordinator (TCP) | current RCP + Aqara's zigbeed 7.3.3 (or Silicon Labs' 8.2.2, the `Zigbee stack` choice) on `iid=1` only, supervised by the helper | low with Aqara's (ZHA only, EZSP 12); 8.2.2 for Z2M `ember` is experimental |
| Zigbee router (repeater) | current RCP + zigbeed + the app's EZSP host (see [Zigbee router](#zigbee-router)) | joined a real network; not soak-tested |
| Thread border router | current RCP + the `PROFILE=br` otbr-agent on `iid=2`, zigbeed stopped | low; routing to real devices untested |
| Thread router (repeater) | current RCP + otbr-agent (either build) on `iid=2`, zigbeed stopped | low |

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
- **Newer zigbeed** (EZSP >= 13 for Z2M `ember`): **works on the stock RCP, no reflash** (panel test
  2026-11-10): Silicon Labs' prebuilt armhf zigbeed 8.2.2 through a bundled glibc loader, with libcpc
  4.3.3 against Aqara's cpcd. Now the `Zigbee stack` choice ([above](#zigbee-stack-aqara-or-silicon-labs-helper-v4)).
  The "requires RCP 2024.6.1" note cited here earlier could not be found again.
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
  localhost only). With one (0.4.3 bundle), every loop: `ok thread_router: joined as
  child|router|leader, network "Name"`, `detached: looking for network "Name"`, `starting Thread, …`,
  from `ot-ctl state` and `ot-ctl networkname` (the name cut to 16 characters of `A-Za-z0-9 ._+-`).
  Never the network key, PSKc or extended PAN ID. `sys.s1kiosk.radio.thread_stored` says `yes`/`no`
  (dataset or router settings stored), in every mode.
- Never forms a network on its own initiative: it only starts Thread with a dataset the user
  supplied. Verified with the panel's own throwaway test dataset (handed over as the app uid): taken,
  app copy deleted, agent restarted, Thread started (alone on the channel it became leader of that
  test network). **Joined a real network (2026-11-10):** with the reference home's preferred Thread
  dataset from Home Assistant, the panel joined and was promoted to router within about two minutes.
  Afterwards *Off*; helper v4's *Leave Thread network* button also deletes the stored dataset.

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
- **bellows reset bug** (above): ZHA can't form or restore onto this radio until a Home Assistant
  release ships bellows with #774 (merged 2026-11-10).
- **Zigbee2MQTT on Silicon Labs' zigbeed 8.2.2** (helper v4): an unsupported host/RCP pairing (8.2.2 on
  a 4.3.3 RCP), not soak-tested with real devices yet; its 4.1-4.4 s reset answer vs herdsman's 5 s wait is
  a thin margin. Experimental; Aqara's stack stays the default.
- **Radio bundle key:** whoever holds it (and can put a file into the kiosk's storage) can run root code
  on panels. Kept offline + one CI secret; rotation needs a new boot image per panel.
- **Radio firmware flashing** (helper v4): only on an explicit, armed request, with rollback to the stock
  image; a power cut mid-flash leaves the radio without an app until the stock image is flashed again
  (the bootloader is not touched).
- **zigbeed NVM:** Aqara's network state is overwritten by any Zigbee mode use. Back it up.
- **Thread:** the border-router build's REST/BR/SRP/mDNS side works; forwarding between the LAN and
  real Thread devices (with the policy rules above) is untested, and netd could rewrite rules on a
  Wi-Fi reconnect (the helper re-adds them every 3 s). The static glibc binary is fine when run by
  root from init.
- **Aqara's watchdog** keeps restarting things. The helper re-enforces every 3 s; a Z3Gateway start
  is caught by its wrapper's 6 s sleep, but this is a race, verified once.
- **Multi-PAN (`zigbee_tcp_thread`)** is not offered and untested.
