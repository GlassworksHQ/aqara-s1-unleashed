# Zigbee2MQTT on the panel's radio (research, 2026-11-10)

Goal: make the panel's Zigbee coordinator usable by **Zigbee2MQTT** (zigbee-herdsman), not only ZHA.
Starting point is [../radio.md](../../../docs/radio.md): EFR32 with Aqara's multi-PAN RCP over CPC, Aqara's
zigbeed 7.3.3 (**EZSP 12**) on spinel IID 1, EZSP over TCP :6638 from the root helper.

Status: research plus **panel tests of steps 1 and 2 (2026-11-10, results in [section 6](#6-panel-results-2026-11-10))**.
Both work: Z2M's `ezsp` adapter on today's zigbeed, and Z2M's `ember` adapter on SiLabs' zigbeed 8.2.2
running against the **stock RCP**. No radio firmware was flashed, and none is needed for path B.
**Step 3 (product) is implemented (2026-11-10) but not yet run on the panel:** helper v4's `Zigbee stack`
choice ([../radio.md](../../../docs/radio.md#zigbee-stack-aqara-or-silicon-labs-helper-v4)), the app downloading
zigbeed from Silicon Labs, and the runtime in the signed radio bundle. Off-device tests pass.

## Summary

| Path | Z2M adapter | Flash? | Thread kept? | Verdict |
|---|---|---|---|---|
| A. Today's zigbeed 7.3.3 (EZSP 12) | `ezsp` (deprecated) | no | yes | **works on the panel** (start, form, resume, permit join). Stopgap only: the adapter is on its way out and has no backup restore |
| **B. SiLabs zigbeed 8.2.2 (EZSP 18) on the stock RCP** | `ember` | **no** | yes | **recommended, works on the panel** (start, form, resume after a zigbeed restart, permit join; Thread on IID 2 unaffected). Prebuilt armhf binary from Silicon Labs + our libcpc 4.3.3 + bundled glibc. Both host-side fixes are in helper v4: the wrapper's `--argv0` and the pty drain before the bridge |
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

**Panel test (run 2026-11-10, results in [section 6](#6-panel-results-2026-11-10)).** Throwaway
harness outside the repo: `/tmp/z2m-ezsp/` (herdsman 11.2.1 via a local `npm install`, nothing
global) and `probe.cjs`:

```
node probe.cjs <host:port> version            # read-only: ASH reset, EZSP version, EmberZNet version, networkState
node probe.cjs <host:port> form               # Controller.start() with fresh random PAN/EPID/key on ch 25 -> forms a test network
PERMIT=1 node probe.cjs <host:port> resume    # second start with the same params -> expect "resumed"; PERMIT=1 adds permitJoin(10), then permitJoin(0)
ADAPTER=ember ...                             # the current adapter instead of the deprecated `ezsp` one
```

Joining is refused (`acceptJoiningDeviceHandler` returns false); `permitJoin` is only called with
`PERMIT=1`, for 10 s at most, on the fresh test network. Params, the database and the debug log (it
contains keys) stay in `/tmp/z2m-ezsp/state-<adapter>/` (0700). Since helper v3 only Home Assistant
may reach :6638, so the computer goes through `adb forward tcp:16638 tcp:6638` (loopback is always
allowed) and uses `127.0.0.1:16638`; the allow-list stays untouched. Precondition: helper mode
`zigbee_tcp`, and a root copy of zigbeed's NVM taken first, because `form` replaces the network in it.

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
  without a login. (Correction, 2026-11-10: Silicon Labs' Studio update site serves it without a login,
  `updates.silabs.com/studio/v6/updates/update_site/archives/slc-cli/<ver>/…`, as NabuCasa's builder
  uses; see [rcp-firmware.md](rcp-firmware.md).)
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
   - **Self-restart needs `--argv0` (found on the panel).** On every ASH reset, i.e. every EZSP host
     connect, zigbeed 8.2.2 `longjmp`s back into `main()`, prints `Restarting` and calls
     `execvp(argv[0], argv)` (disassembly: `_setjmp`, then `fwrite "Restarting"`, then `execvp`).
     Through the loader, `argv[0]` is `bin/zigbeed`, whose ELF interpreter `/lib/ld-linux-armhf.so.3`
     doesn't exist on Android. `execvp` fails, zigbeed falls through into half-initialised code and
     dies with `FATAL ERROR: Caught signal: 11 (Segmentation fault)`. The wrapper now passes
     `--argv0 <abs path of zigbeed.sh>`, so the re-exec comes back through the wrapper and the loader.
     The wrapper adds `-c /dev/null` only once, so the args don't grow with each restart. Verified: same
     PID, `Restarting`, `Zigbeed started` again ~4.2 s later.
3. **Its own NVM:** zigbeed 8.x keeps `host_token.nvm` in its **current directory**. The wrapper cd's
   to `$ZIGBEED_STATE` (default `/data/s1kiosk/radio/zigbeed-sisdk`), so Aqara's
   `/data/zigbee_host/zigbeed_host_token.nvm` is never touched. 8.x can't read 7.3.3's v1 token format
   anyway ("Cannot read v1 token file, please upgrade file to v2 using app built off of `sisdk-2024.6'").
   Z2M forms its own network, so nothing is lost.
4. Smoke test (in the script; arm32v7 container under qemu):
   - `-h` prints zigbeed's usage.
   - A start with no cpcd fails cleanly: `CPC init failed Error: -2` at `cpc_interface.cpp:99`.

   That proves loading, linking and argument parsing; it calls the loader directly, so it doesn't
   cover the wrapper or the re-exec. The RCP side is proven on the panel ([section 6](#6-panel-results-2026-11-10)).

Output: `/tmp/zigbeed-sisdk/` (`$ZIGBEED_OUT`; ~4 MB with the runtime) plus `BUILD-INFO.txt` with all
hashes. Downloads and intermediates go to `/tmp/zigbeed-sisdk-build/` (`$ZIGBEED_BUILD`). Both are
**outside the repo tree** on purpose, because the bundle holds the MSLA binary, and the script refuses
paths inside the checkout. (An earlier version wrote to the gitignored `tools/radio/out/`.)

### Licensing (checked again 2026-11-10 for what the radio bundle ships)

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
- **What the product ships** (radio bundle `sisdk/`, `tools/radio/build-sisdk-runtime.sh`):
  - `libcpc.so.3` from cpc-daemon v4.3.3: every file says "governed by the sections of the MSLA
    applicable to Source Code". MSLA 4.2.2/4.2.5 allow compiling it and distributing the result
    ("Licensed Programs") to end users "only for use with Authorized Applications and not on a standalone
    basis"; 5.4 requires passing on the no-redistribution terms. So: only inside the signed bundle inside
    the kiosk APK (used with the panel's EFR32, an Authorized Application), never as its own download or
    release asset, with those terms in `NOTICE.txt`. This is the position the libcpc linked into
    otbr-agent was already in.
  - glibc 2.35 (`ld-linux-armhf.so.3`, `libc.so.6`, `libm.so.6`) and `libstdc++.so.6`/`libgcc_s.so.1`
    (GCC 12): unmodified Ubuntu 22.04 cross-runtime packages (Architecture: all), byte-identical to the
    ones tested on the panel. Licence texts and the Debian copyright files ship in `licenses/`;
    `NOTICE.txt` names the exact Ubuntu source packages (`cross-toolchain-base 59ubuntu3`,
    `gcc-12-cross 5ubuntu2.5`) as Corresponding Source, which Launchpad keeps permanently. If that is
    ever not enough (GPLv3 6(d) makes us responsible for the source staying available), mirror those
    two source packages next to the release.
  - zigbeed: **not shipped**. `make-bundle.py` refuses it, the release workflow fails if an APK contains
    one, and the panel downloads it from Silicon Labs' release with pinned hashes.
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

**Does a newer zigbeed work with the existing RCP? Yes, on the panel (2026-11-10, [section 6](#6-panel-results-2026-11-10)).**
zigbeed 8.2.2 accepts RCP `0017/2.3.3.0` (spinel protocol 4.3, RCP API 9, min host API 4, radio
caps 255), forms and resumes networks, and opens and closes permit-join. The reasoning below was the
prediction; the soak test (hours or days, real devices joining) is still open.

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

**Correction to radio.md (applied there and in the backups' FLASH-PLAN.md):** the stock GBL's program tag is `0xFD0303FD` = **ERASEPROG, uncompressed**
(`platform/bootloader/parser/gbl/btl_gbl_format.h:63`), not PROG_LZ4 (`0xFD0505FD`).

- Re-parsed here: HEADER_V3, APPLICATION, ERASEPROG of 120308 B at `0x4000`, END.
- So the dry run proved that **unsigned, uncompressed** GBLs are accepted; LZ4 support in this
  bootloader (v2.3) is unknown.
- radio.md ("Flash path, verified") and the backups' FLASH-PLAN.md are fixed. Any custom GBL
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

### Step 1 (needs the panel, no flashing): the `ezsp` adapter against today's zigbeed — **done, passed**

1. Mode `zigbee_tcp` via the app/helper.
2. Back up the NVM (root):
   `cp -p /data/zigbee_host/zigbeed_host_token.nvm /data/s1kiosk/radio/zigbee-nvm/pre-z2m-<date>.nvm`.
3. From the Mac: `adb forward tcp:16638 tcp:6638`, then
   `node /tmp/z2m-ezsp/probe.cjs 127.0.0.1:16638 version`, then `form`, then `PERMIT=1 … resume`.
4. Done means: EZSP 12 / 7.3.3 read; `start()` → `reset`, then `resumed`; `backup()` written;
   `permitJoin` false. Restore: copy the NVM back with zigbeed stopped (or just leave the throwaway
   network).

### Step 2 (needs the panel, no flashing): zigbeed 8.2.2 against the stock RCP — **done, passed**

1. Build: `tools/radio/build-zigbeed.sh` (output in `/tmp/zigbeed-sisdk/`). Push `bin/ lib/
   zigbeed.sh` to `/data/local/tmp/zigbeed-sisdk/` and make it root 0700. The MSLA binary only ever
   lives in `/tmp` / `/data/local/tmp` during a test and is deleted afterwards.
2. Hold Aqara's stack the way the test needs it:
   1. Mode Off via the app (the helper stops all six and removes the firewall chain), then
      `setprop ctl.stop s1kiosk_radio`. Without the helper nothing restarts Aqara's services
      (radio.md, 15-minute watch), and the app only re-applies the mode at startup or on a request.
   2. `setprop ctl.start zigbee_cpcd`, wait ~6 s, then `setprop ctl.start zigbee_socat` (the pty
      pair). **Not** `zigbee_zigbeed`.
3. Root:
   `ZIGBEED_STATE=/data/local/tmp/zigbeed-sisdk-state setsid nohup /data/local/tmp/zigbeed-sisdk/zigbeed.sh -r 'spinel+cpc://cpcd_0?iid=1&iid-list=0' -p /tmp/ttyZigbeeNCP -v 1 -d 4 > /data/local/tmp/zigbeed8.log 2>&1 &`
   1. Pass: RCP version logged, no spinel errors or asserts within 60 s.
   2. Then the bridge, **loopback only**, because the firewall is gone while the helper is stopped:
      `/vendor/bin/socat TCP-LISTEN:6638,bind=127.0.0.1,reuseaddr,fork,max-children=1 FILE:/dev/ttyZigbeeNCP,raw,echo=0`.
   3. After every fresh zigbeed start, drain the pty once before the first client:
      `timeout 1 /vendor/bin/socat -u FILE:/dev/ttyZigbeeNCP,raw,echo=0 -` (see section 6).
4. From the Mac (through `adb forward tcp:16638 tcp:6638`):
   `ADAPTER=ember node /tmp/z2m-ezsp/probe.cjs 127.0.0.1:16638 form`, then `PERMIT=1 … resume`.
   1. Expect EZSP 18 / 8.2.2.
   2. The network is formed on a fresh `host_token.nvm` and resumes after a zigbeed restart.
   3. Watch that the ember adapter's RSTACK wait (5 s, `ember/uart/ash.js:23`) covers zigbeed's reset
      time.
5. Also check bellows/ZHA against it (read-only probe; the energy scan was not run). Then kill
   zigbeed, run otbr `--radio-version` on IID 2, and confirm Thread is unaffected.
6. Rollback: kill our zigbeed and socat, then set a mode through the app (`ctl.restart`s the helper).
   - The helper restores whatever mode it holds, with Aqara's zigbeed 7.3.3 and its untouched NVM.
   - **Nothing persistent changes on the radio:** the RCP keeps no Zigbee state, and a cpcd restart
     resets it.

### Step 3 (only after step 2 passes): productize — **implemented in helper v4 (2026-11-10), panel test pending**

What was built (details in [../radio.md](../../../docs/radio.md#zigbee-stack-aqara-or-silicon-labs-helper-v4)):
a `Zigbee stack` select (`persist.s1kiosk.radio_zigbee`), the helper running our zigbeed instead of
`zigbee_zigbeed` in coordinator mode, the pty drain before the bridge (for both stacks), NVM copies
(`zigbee-nvm/sisdk-*.nvm`), the runtime in the signed radio bundle, and the app fetching zigbeed from
Silicon Labs (an HTTP range of the zip for just the .deb, hash-checked at zip/.deb/ELF level) and handing
it to the helper, which accepts only the pinned SHA-256. The mode label stays "Zigbee coordinator (TCP)";
`Radio status` shows the Zigbee2MQTT settings instead. The original plan, for reference:

- Helper: an opt-in `ZIGBEED=sisdk` in `radio.conf`, or a new mode id. In that mode it starts
  `zigbee_cpcd` and `zigbee_socat`, but runs our wrapper instead of `zigbee_zigbeed`. It is supervised
  and restarted like the other children, with NVM copies in `$DIR/zigbee-nvm/`.
  - **Drain the pty after each zigbeed (re)start, before the bridge serves anyone.** zigbeed writes
    an RSTACK at boot. Nobody reads it, so it waits in `/dev/ttyZigbeeNCP` until the first client
    connects. herdsman's `ember` adapter takes it as the answer to its own RST, then treats zigbeed's
    real RSTACK ~4.3 s later as an NCP crash (`ASH_NCP_FATAL_ERROR`), and `start()` fails. Z2M would
    exit and only succeed on its next start. Draining first fixes it (verified). This applies equally
    to the helper's existing `zigbee_tcp` bridge; `ezsp` and bellows only tolerate it (CRC errors in
    the log).
  - Keep the wrapper's `--argv0`: without it zigbeed segfaults on the first host connect.
  - RSTACK timing: zigbeed answers an RST in 4.1-4.4 s (measured), and herdsman's `ember` adapter waits
    5 s. That margin is thin; watch it in the soak test.
- App: label the mode "Zigbee coordinator (Zigbee2MQTT / ZHA)" and show the Z2M config snippet
  (`adapter: ember`, `port: tcp://<ip>:6638`).
- The zigbeed binary is fetched from Silicon Labs' release at install time and checked by hash, never
  bundled in our APK or boot image.

### If step 2 fails at the RCP level: path C, which needs a flash and the owner's approval

(Updated findings, including that `slc-cli` is downloadable without a login after all and that every
newer SDK's RCP speaks a CPC protocol Aqara's cpcd rejects: [rcp-firmware.md](rcp-firmware.md).)

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
| 1 (ezsp adapter test) | done | none (NVM backed up and restored byte-identical) |
| 2 (zigbeed 8.2.2 test) | done | low: no flash; the crash found was host-side (wrapper), fixed |
| 3 (helper/app mode, docs) | 1-2 days | medium-low: unsupported SiLabs pairing (8.2.2 host on a 4.3.3 RCP), so soak-test before offering it; zigbeed watchdog via the helper |
| C (new RCP) | 3-6 days | medium: custom pin config; recoverable via the stock GBL as long as the bootloader is untouched |
| D (NCP) | weeks | medium-high, and loses Thread; not recommended |

## 6. Panel results (2026-11-10)

Reference panel: helper v3 (root override, sha256 equal to `tools/radio/s1kiosk_radio.sh`), stock RCP
`0017/2.3.3.0_GitHub-e6df00dd6`, Aqara's cpcd 4.3.3. Host: the Mac, zigbee-herdsman 11.2.1 (one patch above
the 11.2.0 that Zigbee2MQTT 2.14.3 pins), Node 24, through `adb forward` to the panel's loopback, so the
firewall allow-list was never changed. Only fresh random test networks on channel 25; nothing joined.
No firmware, bootloader or token writes.

### Step 1: `ezsp` adapter, Aqara's zigbeed 7.3.3, helper mode `zigbee_tcp`

| Run | Result |
|---|---|
| `version` | ASH reset ok; first DATA frame times out once (`{"sequence":1} after 4000ms`, zigbeed's slow first ACK) and the retry works; **EZSP 12, EmberZNet 7.3.3.0 build 0**, `networkState` 0 before `networkInit` |
| `form` | `Leaving current network and forming new network`, `Form network`; `start() -> reset` in 10.6 s; coordinator `EZSP v12` / `7.3.3.0 build 0`; channel 25, PAN and ext PAN as configured; `backup()` written |
| `PERMIT=1 resume` | `start() -> resumed` in 14.9 s; `permitJoin(10)` → `getPermitJoin` true, `permitJoin(0)` → false; `backup()` written |

Noise, harmless: `setValue(VALUE_CCA_THRESHOLD)` returns status 55 (`0x37`, EZSP_ERROR_INVALID_ID:
zigbeed has no CCA threshold value) on every start. A second connect logs a few `CRC error` frames
from stale pty bytes before the reset.

### Step 2: `ember` adapter, SiLabs zigbeed 8.2.2 on the stock RCP

zigbeed start on IID 1 (`-v 1 -d 4`, redacted):

```
P-SpinelDrive-: Received ... key:PROTOCOL_VERSION, major:4, minor:3
P-SpinelDrive-: Received ... key:NCP_VERSION, version:VERSION:0017/2.3.3.0_GitHub-e6df00dd6;
P-SpinelDrive-: Received ... key:CAPS, caps:COUNTERS UNSOL_UPDATE_FILTER 802_15_4_2450MHZ_OQP
P-SpinelDrive-: Received ... key:HWADDR, eui64:<eui64>
P-SpinelDrive-: Received ... key:RCP_API_VERSION, version:9
P-SpinelDrive-: Received ... key:RCP_MIN_HOST_API_VERSION, min-host-version:4
P-SpinelDrive-: Received ... key:RADIO_CAPS, caps:255
Zigbeed started
RCP version: VERSION:0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01
Zigbeed Version: GSDK 8.2.2 - Feb 16 2026 - 21:27:51
```

No spinel errors or asserts in the following 60 s.

| Run | Result |
|---|---|
| `form` #1 (original wrapper) | **zigbeed crashed** on the host's ASH RST: `Restarting`, then `*** FATAL ERROR: Caught signal: 11 (Segmentation fault)` (`zigbeed(+0x3cfa2)`). Cause: the failed `execvp` (section 2); fixed with `--argv0` |
| `form` #2 (fixed wrapper) | zigbeed re-execs fine (same PID, `Restarting`, `Zigbeed started`), but herdsman fails: `Received unexpected reset from adapter, with reason=RESET_SOFTWARE` → `ASH_NCP_FATAL_ERROR` → `VERSION` `ASH_ERROR_TIMEOUTS`. Cause: zigbeed's boot RSTACK still in the pty (step 3, drain) |
| `form` #3 | RSTACK after 4.1 s; `Adapter EZSP protocol version (18) lower than Host. Switched.`; **EZSP 18, `8.2.2 [GA]` build 532**; `[INIT FORM] New network formed!`, `Network up`; `start() -> reset` in 6.2 s; `backup()` written; ASH counters: 0 CRC/comm/retry errors |
| `PERMIT=1 resume` ×2 | `Adapter network matches config`, `start() -> resumed` in 5.6-5.8 s; `Network opened` / `Network closed` for `permitJoin(10)` / `permitJoin(0)`; `backup()` written |
| zigbeed process killed and restarted, then `resume` | without a drain: the stale-RSTACK failure again (reproducible on every fresh start). Drained first (bytes `c1 02 0b 0a 52 7e` = RSTACK with reset code `0x0b`, plus one more frame): `start() -> resumed`, so the network persists in `./host_token.nvm` across zigbeed restarts |
| bellows 1.2.1 `ControllerApplication.probe` (ZHA's probe, read-only) | ok |

### Thread and rollback

- With our zigbeed stopped and only cpcd running: `otbr-agent --radio-version` on IID 2 →
  `VERSION:0017/2.3.3.0_GitHub-e6df00dd6; EFR32; Dec 12 2024 17:59:01`.
- Then helper mode `thread` via the app: `ok thread: border router on wpan0, backbone wlan0, REST :8081 (disabled)`.
  `ot-ctl state` → `disabled` (no dataset brought up), `rcp version` as above, `version`
  `OPENTHREAD/ecd308b`.
- Then `zigbee_tcp` via the app: Aqara's `zigbeed` (7.3.3) is back under `zigbee_zigbeed.sh`, and the
  probe reads EZSP 12 / 7.3.3.0.
- Then Off.
- Aqara's NVM was restored from `pre-z2m-20261110.nvm` (kept in `/data/s1kiosk/radio/zigbee-nvm/`)
  and is byte-identical to the pre-test file. The bundle, zigbeed 8.2.2's state dir and logs were
  deleted from `/data/local/tmp`.
- No `S1RADIO` rules are left, `persist.s1kiosk.radio_allow` is unchanged, and the kiosk stays in
  front.

### Not tested yet

- Real devices joining and traffic.
- A soak test (hours or days) of the mismatched 8.2.2 host / 2.3.3.0 RCP pair.
- An energy scan, ZHA `form`/restore on 8.2.2, and Z2M's `ember` backup restore into a fresh
  `host_token.nvm`.
- The helper integration (step 3): implemented and covered by off-device tests
  (`tools/radio/test/run.sh`, `RadioV4Test`), not run on the panel yet. Panel steps are in the v4 test
  plan (the final report of 2026-11-10 / [../radio.md](../../../docs/radio.md#zigbee-stack-aqara-or-silicon-labs-helper-v4)).
- The RSTACK margin: zigbeed 8.2.2 answers in 4.1-4.4 s, herdsman's `ember` waits 5 s. Thin; watch
  Zigbee2MQTT restarts in the soak test.

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
