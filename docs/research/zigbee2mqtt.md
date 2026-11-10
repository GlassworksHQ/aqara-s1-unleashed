# Zigbee2MQTT on the panel's radio (research, 2026-11-10)

Goal: make the panel's Zigbee coordinator usable by **Zigbee2MQTT** (zigbee-herdsman), not only ZHA.
Starting point is [../radio.md](../radio.md): EFR32 with Aqara's multi-PAN RCP over CPC, Aqara's
zigbeed 7.3.3 (**EZSP 12**) on spinel IID 1, EZSP over TCP :6638 from the root helper.

Status: offline research and a prototype build. **Nothing here has run on the panel yet.** No radio
firmware was flashed, and none needs to be for the recommended path.

## Summary

| Path | Z2M adapter | Flash? | Thread kept? | Verdict |
|---|---|---|---|---|
| A. Today's zigbeed 7.3.3 (EZSP 12) | `ezsp` (deprecated) | no | yes | works on paper; panel test prepared. Stopgap only: the adapter is on its way out and has no backup restore |
| **B. SiLabs zigbeed 8.2.2 (EZSP 18) on the stock RCP** | `ember` | **no** | yes | **recommended**: prebuilt armhf binary from Silicon Labs + our libcpc 4.3.3 + bundled glibc. Bundle built and smoke-tested; the RCP compatibility test on the panel is still open |
| C. New multi-PAN RCP + matching host stack | `ember` | yes (custom GBL) | yes | only if B fails on the RCP; heavy: custom MG21 SPI image, new cpcd/libcpc/otbr |
| D. Zigbee-only NCP on the EFR32 | `ember` | yes, per mode switch | **no** | not recommended: custom SPI image + our own SPI-or-CPC-to-ASH bridge |

## 1. Today's EZSP 12 with Z2M's deprecated `ezsp` adapter

**Shipped and accepts v12.** zigbee-herdsman 11.2.1 (latest on npm, 2026-11-03; Zigbee2MQTT 2.14.3
pins 11.2.0) still contains it:

- `dist/adapter/adapter.js:99-103`: `// @deprecated case "ezsp"` loads `EZSPAdapter`.
- `dist/adapter/ezsp/adapter/ezspAdapter.js:163`: warns "'ezsp' driver is deprecated and will only
  remain to provide support for older firmware (pre 7.4.x)".
- `dist/adapter/ezsp/driver/ezsp.js:402-413`: `version()` throws only for `protocolVersion >= 14`. 12 is
  negotiated (`ezspV` starts at 4 and switches to what the NCP reports). There is v<13 handling all
  through `driver.js` (`getKey` vs `exportKey`, `addTransientLinkKey`, lines 743-803).
- TCP: `dist/adapter/transport.js:61,193-205` opens `tcp://host:port` for every adapter.
- Z2M 2.14.3 still lists `ezsp` in `dist/util/settings.schema.json:253`.
- ASH reset: the legacy driver waits **10 s** for RSTACK (`ezsp/driver/uart.js:291`), so zigbeed's
  ~4.2 s reset is fine. The bellows reset bug in radio.md does not apply here.

Caveats: deprecated, no new features. Its backup/restore is the old `EZSPAdapterBackup`; migrating a
network later from `ezsp` to `ember` is a Z2M-documented path (discussion Koenkk/zigbee2mqtt#21462).
Good enough for a test, not as the product answer.

**Panel test (prepared, not run: the panel was busy).** Throwaway harness outside the repo:
`/tmp/z2m-ezsp/` (herdsman 11.2.1 via a local `npm install`, nothing global) and `probe.cjs`:

```
node probe.cjs <panel-ip>:6638 version   # read-only: ASH reset, EZSP version, EmberZNet version, networkState
node probe.cjs <panel-ip>:6638 form      # Controller.start() with fresh random PAN/EPID/key on ch 25 -> forms a test network
node probe.cjs <panel-ip>:6638 resume    # second start with the same params -> expect "resumed"
```

Joining is refused (`acceptJoiningDeviceHandler` returns false), and `permitJoin` is never called.
Params, the database and the debug log (it contains keys) stay in `/tmp/z2m-ezsp/state-ezsp/` (0700).
Precondition: helper mode `zigbee_tcp`, and a root copy of zigbeed's NVM taken first, because `form`
replaces the throwaway ZHA test network now in it.

## 2. Newer zigbeed

### Which SDK gives which EZSP (read from each tag's `protocol/zigbee/app/util/ezsp/ezsp-protocol.h` and `stack/config/config.h`)

| SDK | EmberZNet | EZSP | herdsman `ember` (13-19) | herdsman `ezsp` (<14) |
|---|---|---|---|---|
| Gecko SDK 4.3.3 (Aqara's) | 7.3.3 | 12 | no | yes |
| Gecko SDK 4.4.0 / 4.4.6 / 4.5.0 | 7.4.0 / 7.5.0 / 7.5.1 | 13 | yes | yes |
| Simplicity SDK 2024.6.3 | 8.0.3 | 16 | yes | no |
| Simplicity SDK 2024.12.3 | 8.1.3 | 16 | yes | no |
| Simplicity SDK 2025.6.3 | 8.2.2 | 18 | yes | no |

herdsman's limits: `dist/adapter/ember/ezsp/consts.js:7,9` (`EZSP_MIN_PROTOCOL_VERSION = 0x0d`,
`EZSP_PROTOCOL_VERSION = 0x13`).

### Getting a binary: Silicon Labs ships one

Since Simplicity SDK 2024.6, every release carries **`debian-bookworm.zip`**: prebuilt cpcd, libcpc,
ot-br-posix and **zigbeed for i386/amd64/armhf/arm64**. For v2025.6.3 that is
`https://github.com/SiliconLabs/simplicity_sdk/releases/download/v2025.6.3/debian-bookworm.zip`,
sha256 `7ecbd91c…3c0e`, which matches GitHub's asset digest. Inside it:

- `zigbeed_8.2.2_armhf.deb`: a 663 KB ELF, armhf, PIE, dynamically linked. It needs `GLIBC_2.34`,
  `libstdc++.so.6`, `libgcc_s.so.1` and `libcpc.so.3`.
- `libcpc3_4.6.0_armhf.deb` (`libcpc.so.4.7.1.0`, soname `libcpc.so.3`) and `cpcd_4.6.0`.

Building zigbeed from source is the other route, as Home Assistant's add-on did: `slc generate` on
`protocol/zigbee/app/zigbeed/zigbeed.slcp` with `--with=linux_arch_32` and `provides:
zigbee_use_release_libraries, arm32v7`, then `make -f zigbeed.Makefile` with a cross gcc
(home-assistant/addons `silabs-multiprotocol/Dockerfile` @ac8e556c3d). That route has two blockers
here:

- The SLC CLI download (`silabs.com/documents/login/software/slc_cli_linux.zip`) now returns 403
  without a login.
- The Zigbee stack itself is closed: it comes as prebuilt `.a` files in Git LFS under
  `protocol/zigbee/build/gcc/arm32v7/`, present in GSDK 4.4.x and every Simplicity SDK.

Only the OpenThread spinel side is source. In `platform/radio/mac/component/lower_mac_spinel.slcc` and
`protocol/openthread/component/ot_lower_mac_spinel.slcc`, RadioSpinel, `cpc_interface.cpp` and
`posix_vendor_spinel_interface.cpp` are compiled from Zlib/BSD source. So a source build gains nothing
over SiLabs' own binary, unless EZSP 13 (GSDK 4.4/4.5) is wanted. No prebuilt debs exist for 4.4/4.5.
darkxst/silabs-firmware-builder and NabuCasa's builder only build **firmware** (NCP/RCP/bootloader
GBLs), not zigbeed.

### Making it run on Android: `tools/radio/build-zigbeed.sh` (prototype, built here)

1. **libcpc must match Aqara's cpcd 4.3.3.**
   - SiLabs' libcpc 4.7.1.0 refuses a daemon whose version string differs (strings: "libcpc version
     (v%s) does not match the daemon version (v%s)").
   - zigbeed 8.2.2 imports only `cpc_init`, `cpc_open_endpoint`, `cpc_read_endpoint`,
     `cpc_write_endpoint`, `cpc_close_endpoint`, `cpc_restart` and `cpc_set_endpoint_option`.
   - The script builds `libcpc.so.3` from cpc-daemon v4.3.3 (API v3, protocol 4, same soname) and
     checks that every import resolves.
   - This is the same libcpc that build-otbr.sh links statically, and that is proven against Aqara's
     cpcd.
2. **glibc on bionic:** the script bundles `ld-linux-armhf.so.3`, `libc.so.6`, `libm.so.6`,
   `libstdc++.so.6` and `libgcc_s.so.1` (Ubuntu 22.04 armhf cross runtime, glibc 2.35, GCC 12).
   `zigbeed.sh` runs the unmodified binary through the bundled loader (`--library-path`).
3. **Its own NVM:** zigbeed 8.x keeps `host_token.nvm` in its **current directory**. The wrapper cd's
   to `$ZIGBEED_STATE` (default `/data/s1kiosk/radio/zigbeed-sisdk`), so Aqara's
   `/data/zigbee_host/zigbeed_host_token.nvm` is never touched. 8.x can't read 7.3.3's v1 token format
   anyway ("Cannot read v1 token file, please upgrade file to v2 using app built off of `sisdk-2024.6'").
   Z2M forms its own network, so nothing is lost.
4. Smoke test (in the script; arm32v7 container under qemu):
   - `-h` prints zigbeed's usage.
   - A start with no cpcd fails cleanly: `CPC init failed Error: -2` at `cpc_interface.cpp:99`.

   That proves loading, linking and argument parsing. It does **not** prove the RCP talks to it.

Output: `tools/radio/out/zigbeed-sisdk/` (gitignored; ~4 MB with the runtime), plus `BUILD-INFO.txt`
with all hashes.

### Licensing

- zigbeed is Silicon Labs **MSLA** object code (`usr/share/doc/zigbeed/copyright` in the deb).
- Object code may be distributed to end users "only for use with Authorized Applications and not on a
  standalone basis" (MSLA 4.1.6, 4.2.5), with end-user terms that forbid further redistribution (5.4).
- An "Authorized Application" includes software on "a host processor that operates in conjunction
  with a Silicon Labs Device". The panel's EFR32 qualifies, but a public GitHub release of the bare
  binary is at least a grey area.
- **Recommendation:** never commit or attach the binary. Fetch it from Silicon Labs' own release at
  build or install time, hash-pinned, as build-zigbeed.sh does. Home Assistant did redistribute
  SiLabs-built zigbeed inside its add-on images, but that is not a licence opinion either.
- The bundled glibc (LGPL-2.1+) and libstdc++ (GPL-3.0+ with the GCC Runtime Library Exception) are
  unproblematic if their sources are pointed to.
- Not legal advice.

## 3. Compatibility with the RCP on the chip

**Device-side versions** (`cpcd -p`, 2026-11-09, logs kept with the backups, `r1-versions.log:1,44-50`):

- CPCd v4.3.3.0, Library API v3, RCP protocol v4.
- Secondary protocol v4, Secondary CPC v4.3.3, Secondary APP `0017`.
- `Secondary bootloader: Gecko SDK` (`r2-btlinfo.log:180`).
- RCP spinel version `0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024`.

**The part:** EFR32**MG21** (Series 2, config 1), confirmed from the stock image, not only the
teardown:

- The decompressed app references the USART0/1/2 bases at `0x50058000`, `0x5005C000` and `0x50060000`
  (xG21 layout) and no EUSART base (xG24 has EUSART).
- It uses `sl_se_*` (HSE) for its CTUNE user-data commands.
- One flash literal is `0xF4000`, which suggests the 1024 kB variant (NVM3 below `0x100000`). That is
  an inference; A010 vs A020 (TX power) is unknown.
- App range `0x4000-0x215F0`.

**Does a newer zigbeed work with the existing RCP? Probably, but untested.**

- **Spinel:** zigbeed's lower MAC is OpenThread's own RadioSpinel plus SiLabs' vendor interface (both
  open source, see above). Our otbr-agent build is OpenThread `ecd308b` (2026-11) with the Simplicity
  SDK 2025.6.3 `cpc_interface.cpp`. It already runs against this RCP on IID 2, including
  `rcp version` and border routing (radio.md). The RCP API version check is therefore passed by
  current OpenThread.
- **SiLabs vendor properties:** unchanged from GSDK 4.3.3 through Simplicity SDK 2025.6.3
  (`protocol/openthread/platform-abstraction/include/vendor_spinel.hpp`: `ANTENNA`, `COEX`, `TEST`,
  `EFR32` = `VENDOR__BEGIN + 0..3` in all three).
- **CPC:** handled by keeping Aqara's cpcd and our libcpc 4.3.3 (the same pairing otbr uses).
- **Open risk:** the closed Zigbee upper MAC in 8.2.2 may use RCP features that 2.3.3.0 lacks.
  2025.6 adds `radio_multi_channel.h`, `radio_power_manager.h` and `radio_coex.h`, and multi-PAN gained
  "fast channel switching" in 4.4. A missing property would show up as a spinel error or a zigbeed
  assert at start, i.e. right away in the panel test.
- **Track record:** SiLabs and Home Assistant always paired zigbeed with an exact-version RCP.
  - The HA add-on flashed `NabuCasa_*_RCP_v<GSDK>` with `--ensure-exact-version` on every GSDK bump
    (home-assistant/addons#3370).
  - HA reverted GSDK 4.4.0 → 4.3.1 within weeks (#3388, #3429: Green Power crashes).
  - So a mismatched pair is unsupported by SiLabs even if it works.
- The "requires RCP 2024.6.1" note cited in radio.md (Nerivec firmware builder) could not be found
  again. Treat it as unverified.

**If a new RCP turns out to be needed (path C):**

- **Image:** Simplicity SDK 2025.6.3 `protocol/openthread/sample-apps/ot-ncp/rcp-spi-802154.slcp`
  (multi-PAN 802.15.4 RCP over CPC/SPI, the same family as Aqara's), built for `EFR32MG21A0x0F1024IM32`.
  - There is **no community MG21-SPI target**: darkxst/NabuCasa manifests are all UART (radio.md).
  - The board config must reproduce Aqara's:
    - CPC SPI secondary pins: the USART instance and its SCLK/MOSI/MISO/CS routes, plus the
      nHOST_INT line to the host's PH19 / gpio 243;
    - the bootloader wake pin;
    - CTUNE, read from the SE user-data token Aqara writes;
    - LFXO/HFXO settings.

    All of these have to be reverse-engineered from the stock image (the `GPIO->USARTROUTE[]` writes,
    the CPC SPI driver config). That has not been done yet.
  - Build with darkxst/NabuCasa's builder (slc + commander) as a **non-compressed** GBL (see the
    correction below).
- **Host side:**
  - A 2025.6 RCP speaks CPC protocol v5/v6, which Aqara's cpcd 4.3.3 rejects. So the daemon would
    become upstream cpcd 4.6+ for normal operation.
  - libcpc and otbr-agent would be rebuilt to match (build-otbr.sh pins change).
  - **Flashing stays on Aqara's cpcd 4.3.3** (inverted reset pin, radio.md "Flash path, verified").
- **Thread:** keeps working in principle. It is the same multi-PAN RCP family with spinel on IID 2,
  and otbr would need the new libcpc.

**Correction to radio.md:** the stock GBL's program tag is `0xFD0303FD` = **ERASEPROG, uncompressed**
(`platform/bootloader/parser/gbl/btl_gbl_format.h:63`), not PROG_LZ4 (`0xFD0505FD`).

- Re-parsed here: HEADER_V3, APPLICATION, ERASEPROG of 120308 B at `0x4000`, END.
- So the dry run proved that **unsigned, uncompressed** GBLs are accepted; LZ4 support in this
  bootloader (v2.3) is unknown.
- radio.md ("Flash path, verified") and the backups' FLASH-PLAN.md should be fixed. Any custom GBL
  should be built uncompressed.

## 4. Zigbee-only NCP on the EFR32 (path D)

What it takes:

1. **An NCP image for this board.** Candidates in Simplicity SDK 2025.6.3:
   `protocol/zigbee/app/projects/ncp/ncp-spi/ncp-spi.slcp` (EZSP-SPI),
   `zigbee_ncp_cpc_spi/zigbee_ncp_cpc_spi.slcp` (EZSP over CPC), or
   `ncp-cmp/zigbee_ncp-ot_rcp-spi.slcp` (Zigbee NCP + OpenThread RCP concurrently, over CPC). All need
   the same reverse-engineered pinout as path C.
2. **A host bridge.** Z2M/herdsman and bellows speak only ASH (serial or TCP). Nothing turns EZSP-SPI
   or EZSP-over-CPC into ASH. We would have to write one (SPI frames or a CPC endpoint ↔ ASH ↔
   TCP :6638).
3. **Mode switching = reflash.**
   - One flash is ~20 s at 2 MHz (measured in the dry run), plus cpcd restarts.
   - Each switch carries the same small but real risk as any flash: a power cut mid-transfer means
     re-running it, and the bootloader recovers.
   - Flash endurance (~10k cycles) is not the issue. The switch would be a deliberate "Zigbee2MQTT
     mode" install, not a select in HA.
   - Each app's NVM3 survives the other's flash only if both use the same layout. The NCP's network
     state would live on the chip, so Z2M's coordinator backup becomes the safety net.

Effort: weeks, mostly pinout reverse-engineering and the bridge. Thread is lost while in that mode. Not
recommended while path B is open.

## 5. Recommendation and plan

**Path B: SiLabs zigbeed 8.2.2 on the existing RCP, no flashing.** Thread mode is unaffected (otbr on
IID 2 never meets zigbeed, one protocol at a time). ZHA works too: bellows supports EZSP 4-19.

### Step 1 (needs the panel, no flashing): the `ezsp` adapter against today's zigbeed

1. Mode `zigbee_tcp` via the app/helper.
2. Back up the NVM (root):
   `cp /data/zigbee_host/zigbeed_host_token.nvm /data/s1kiosk/radio/zigbee-nvm/pre-z2m.nvm`.
3. From the Mac: `node /tmp/z2m-ezsp/probe.cjs <panel-ip>:6638 version`, then `form`, then `resume`.
4. Done means: EZSP 12 / 7.3.3 read; `start()` → `reset`, then `resumed`; `backup()` written;
   `permitJoin` false. Restore: copy the NVM back with zigbeed stopped (or just leave the throwaway
   network).

### Step 2 (needs the panel, no flashing): zigbeed 8.2.2 against the stock RCP

1. Build: `tools/radio/build-zigbeed.sh` (done). Push `out/zigbeed-sisdk/` to
   `/data/s1kiosk/radio/zigbeed-sisdk-bin/` (root 0700).
2. Hold Aqara's stack the way the test needs it:
   1. `setprop ctl.stop s1kiosk_radio`. Without the helper nothing restarts Aqara's services
      (radio.md, 15-minute watch).
   2. Stop all six in consumer order.
   3. `setprop ctl.start zigbee_cpcd`, then `setprop ctl.start zigbee_socat` (the pty pair). **Not**
      `zigbee_zigbeed`.
3. Root:
   `ZIGBEED_STATE=/data/s1kiosk/radio/zigbeed-sisdk …/zigbeed.sh -r 'spinel+cpc://cpcd_0?iid=1&iid-list=0' -p /tmp/ttyZigbeeNCP -v 1 -d 4 2>&1 | tee /data/local/tmp/zigbeed8.log`
   1. Pass: RCP version logged, no spinel errors or asserts within 60 s.
   2. Then the bridge:
      `/vendor/bin/socat TCP-LISTEN:6638,reuseaddr,fork,max-children=1 FILE:/dev/ttyZigbeeNCP,raw,echo=0`.
4. From the Mac: `ADAPTER=ember node /tmp/z2m-ezsp/probe.cjs <panel-ip>:6638 form`, then `resume`.
   1. Expect EZSP 18 / 8.2.2.
   2. The network is formed on a fresh `host_token.nvm` and resumes after a zigbeed restart.
   3. Watch that the ember adapter's RSTACK wait (5 s, `ember/uart/ash.js:23`) covers zigbeed's reset
      time.
5. Also check bellows/ZHA against it (read-only probe) and an energy scan. Then kill zigbeed,
   run otbr `--radio-version` on IID 2, and confirm Thread is unaffected.
6. Rollback: kill our zigbeed and socat, then `setprop ctl.start s1kiosk_radio`.
   - The helper restores whatever mode it holds, with Aqara's zigbeed 7.3.3 and its untouched NVM.
   - **Nothing persistent changes on the radio:** the RCP keeps no Zigbee state, and a cpcd restart
     resets it.

### Step 3 (only after step 2 passes): productize

- Helper: an opt-in `ZIGBEED=sisdk` in `radio.conf`, or a new mode id. In that mode it starts
  `zigbee_cpcd` and `zigbee_socat`, but runs our wrapper instead of `zigbee_zigbeed`. It is supervised
  and restarted like the other children, with NVM copies in `$DIR/zigbee-nvm/`.
- App: label the mode "Zigbee coordinator (Zigbee2MQTT / ZHA)" and show the Z2M config snippet
  (`adapter: ember`, `port: tcp://<ip>:6638`).
- The zigbeed binary is fetched from Silicon Labs' release at install time and checked by hash, never
  bundled in our APK or boot image.

### If step 2 fails at the RCP level: path C, which needs a flash and the owner's approval

1. Reverse-engineer the CPC SPI pinout from the stock image.
2. Build `rcp-spi-802154` (Simplicity SDK 2025.6.3, MG21, uncompressed GBL).
3. Rebuild otbr and libcpc against cpcd 4.6, and run upstream cpcd 4.6 for normal use.
4. Flash **only** with Aqara's `/vendor/bin/cpcd -c /etc/cpcd_ota.conf -f <gbl>` (radio.md flash
   path).
5. **Rollback:** reflash the stock GBL the same way:
   - `~/src/aqara-panel-backups/radio-fw/aqara-s1plus-rcp-0017_2.3.3.0-e6df00dd6.gbl`, sha256
     `d7b79c6f…8fa9`, identical to `/vendor/firmware/lichun#30000000#17#20250821#8f636f.bin`;
   - recovery pins, ~20 s;
   - Aqara's cpcd 4.3.3 again for normal use.

   The bootloader is untouchable by an app GBL (FLASH-PLAN (d)). Effort: 3-6 days including the
   pinout work, with real risk in the pin config.

### Effort and risk

| Step | Effort | Risk |
|---|---|---|
| 1 (ezsp adapter test) | 30 min on the panel | none beyond replacing the throwaway test network (NVM backed up) |
| 2 (zigbeed 8.2.2 test) | 1-2 h on the panel | low: no flash; the worst case is zigbeed failing to start or misbehaving, fixed by killing it |
| 3 (helper/app mode, docs) | 1-2 days | medium-low: unsupported SiLabs pairing (8.2.2 host on a 4.3.3 RCP), so soak-test before offering it; zigbeed watchdog via the helper |
| C (new RCP) | 3-6 days | medium: custom pin config; recoverable via the stock GBL as long as the bootloader is untouched |
| D (NCP) | weeks | medium-high, and loses Thread; not recommended |

## Sources

- zigbee-herdsman 11.2.1 (npm tarball), files cited inline under `dist/adapter/`. Zigbee2MQTT 2.14.3
  `dist/util/settings.schema.json:253`.
- SiliconLabs/gecko_sdk v4.3.3, v4.4.0, v4.4.6, v4.5.0 and SiliconLabs/simplicity_sdk v2024.6.3,
  v2024.12.3, v2025.6.3 (git trees via the GitHub API): `protocol/zigbee/app/util/ezsp/ezsp-protocol.h`,
  `protocol/zigbee/stack/config/config.h`, `protocol/zigbee/app/projects/zigbeed/zigbeed.slcp`,
  `platform/radio/mac/component/*.slcc`, `protocol/openthread/component/ot_lower_mac_spinel.slcc`,
  `protocol/openthread/platform-abstraction/include/vendor_spinel.hpp`,
  `platform/bootloader/parser/gbl/btl_gbl_format.h`, and `protocol/zigbee/build/gcc/arm32v7/` (Git LFS
  libraries).
- simplicity_sdk v2025.6.3 release asset `debian-bookworm.zip` (zigbeed 8.2.2, cpcd/libcpc 4.6.0):
  control file, copyright (MSLA 20221107), ELF dynamic sections and symbols.
- SiliconLabs/cpc-daemon v4.3.3 `CMakeLists.txt:4-7,80-97`.
- home-assistant/addons: `silabs-multiprotocol/Dockerfile` @ac8e556c3d (zigbeed via slc); PRs #3370,
  #3388, #3390, #3429 (GSDK 4.4.0 up and back down, exact RCP pairing); #3833 (deprecation).
- darkxst/silabs-firmware-builder README, manifests (UART-only targets, e.g.
  `manifests/xiaomi/lumi-gateway-mgl001_zigbee_ncp.yaml`), discussion #41.
- Device data: radio.md, and the backups' `radio-fw/dryrun-2026-11-09/r1-versions.log` and
  `r2-btlinfo.log`, `radio-fw/FINDINGS.md`, `FLASH-PLAN.md`. Stock GBL re-parsed here.
