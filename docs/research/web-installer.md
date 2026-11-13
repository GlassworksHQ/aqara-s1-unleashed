# Browser installer (WebUSB) — design

> **Historical note.** This is the spec written *before* the installer was built. The installer now
> exists in [`kiln/`](../../kiln/src/README.md); the user guide is [docs/install.md](../install.md).
> Where the shipped code and this spec differ, the code wins.

**Status: design only. Nothing here is implemented yet, and nothing in it touches a panel.**
This is the implementation spec for a browser-based installer that turns a factory **Aqara Panel
Hub S1 Plus** (A133, `sun50iw10`) into a Home Assistant kiosk with no UART, no terminal and no
local downloads. It mirrors, step for step, the CLI flow that already passed a factory simulation on
a real panel: `tools/install/fel-install.sh` → `tools/install.sh` (see
[docs/research/root.md](root.md#one-command-toolsinstallfel-installsh) and [docs/research/fel.md](fel.md)).

Every claim below is tagged **[verified]** (checked against source / a byte-exact reproduction run
on this machine today) or **[assumed]** (needs a panel, or a browser-runtime test, to confirm). The
reference device was never touched while writing this; the only hardware-free thing done was running
the existing Docker boot-patch locally to produce golden files, which is explicitly allowed.

Repo paths are relative to the repo root. External source paths are to the local checkouts used to
write this doc, pinned by commit:
- sunxi-tools `~/src/sunxi-tools` @ `34dfa20` (FEL protocol reference) — GPL-2.0 **[verified]**
- U-Boot `~/src/u-boot-s1` @ `934995f6` + `tools/fel/uboot/patches/*` (our gadget) — GPL-2.0 **[verified]**
- Magisk `topjohnwu/Magisk` @ `0972f67` = tag **v31.1** (`native/src/boot/*`) — GPL-3.0 **[verified]**
- fastboot.js `kdrag0n/fastboot.js` @ `5b61333` = v1.1.3 — MIT **[verified]**
- ya-webadb / Tango `yume-chan/ya-webadb` @ `cdc74fa` = v2.6.4 (stable), beta 3.0.0-beta.5 reviewed — MIT **[verified]**

---

## 0. The reference flow, and what has to move into the browser

The CLI pipeline the installer reproduces, with the module that replaces each stage:

| CLI stage | CLI file | Browser module | Transport |
|---|---|---|---|
| 1. Wait for FEL, check SoC | `fel-install.sh:40-44` | `fel/` (new TS WebUSB port) | WebUSB (`1f3a:efe8`) |
| 2. Run SPL, upload U-Boot+ATF, warm-reset | `tools/fel/fel-boot.sh` | `fel/` | WebUSB (`1f3a:efe8`) |
| 3. U-Boot autostarts `fastboot usb 0` | `aqara-s1-plus_defconfig:46-47` | — (device re-enumerates) | — |
| 4. getvars, back up partitions | `fel-install.sh:52-64`, `fb-read-partition.sh` | `fastboot/` (fastboot.js + `oem run`/`upload`) | WebUSB (`1f3a:1010`) |
| 5. Patch boot (Magisk + helper + adb key) | `tools/root/patch-boot-magisk.sh` | `bootpatch/` (JS/wasm magiskboot) | none (in-memory) |
| 6. vbmeta flags → 3 | `fel-install.sh:73-85` | `bootpatch/` + `fastboot/` | WebUSB |
| 7. Flash boot, verify, reboot | `fel-install.sh:87-93` | `fastboot/` | WebUSB |
| 8. First boot authorizes adb key; then install app | `s1kiosk_firstboot.sh`, `tools/install.sh` | `adb/` (Tango) + `provision/` | WebUSB (adb `18d1:…`/Aqara) |

The whole thing is a static site: HTML/TS/wasm plus pinned asset blobs, no backend. WebUSB needs a
secure context (HTTPS or `http://localhost`) **[verified: MDN]**, so the site must be served over
HTTPS.

### Module layout

The layout planned before the build, kept as it was. The installer now lives in `kiln/`, and the
protocol modules in `kiln/packages/` (listed in `kiln/package.json`, `workspaces`). The flow is
`kiln/packages/kiln-core`, and the S1's part of it, provisioning included, is the recipe in
`frames/aqara-s1-plus/installer`.

```
web/
  mockup/index.html          clickable static mockup (this PR)
  src/
    app/                     screen flow, state machine, i18n, persistence (IndexedDB)
    fel/                     TS WebUSB port of the sunxi-fel subset (§2)
      felDevice.ts           AWUC/AWUS framing, FEL requests, open/claim/endpoints
      spl.ts                 eGON parse+checksum, SRAM swap, thunk, SPL exec
      uboot.ts               FIT parse, load ATF+U-Boot+DTB, reset64 (RMR)
      socInfo.ts             A133 constants (addresses, swap buffers, RVBAR)
      thunks.ts              fel-to-spl + rmr thunk words (from sunxi-tools)
    fastboot/                fastboot.js vendored + extensions (§3)
      ext.ts                 oemRun(), readPartition() (download/upload/get_staged)
    bootpatch/               byte-exact boot-image patcher (§4)
      bootimg.ts             Android boot img v2 unpack/repack + SHA1 id
      cpio.ts                newc cpio load/dump, add/mkdir/backup/restore/patch
      magiskboot.wasm        Rust compressors (gzip/xz) compiled to wasm32 (§4.4)
      vbmeta.ts              AVB flags patch (§6)
    adb/                     Tango wrappers (§7): keypair, install, shell
    provision/              port of tools/install.sh (§8)
    assets/                  manifest + SHA-256 verification (§9)
```

---

## 1. Device identification and refusing unknown hardware

Three USB identities appear across the flow **[verified]**:

1. **FEL (BROM):** `1f3a:efe8` — `fel_lib.h:26-27` (`AW_USB_VENDOR_ID`/`AW_USB_PRODUCT_ID`).
2. **Our U-Boot fastboot gadget:** `1f3a:1010` — `build-s1/.config:1791-1792`
   (`CONFIG_USB_GADGET_VENDOR_NUM=0x1f3a`, `CONFIG_USB_GADGET_PRODUCT_NUM=0x1010`). bcdDevice is
   derived from the U-Boot version (`g_dnl.c:253`), so do **not** filter on it.
3. **Android adb (after first boot):** stock Aqara VID/PID — **[assumed; not yet captured on a factory
   panel]**. Tango filters the adb *interface* class/subclass/protocol `ff/42/1`
   (`adb-daemon-webusb/src/device.ts:33-35`), so WebUSB device selection should use that interface
   filter plus a user picker, not a hard-coded adb PID. Capturing the real adb VID:PID on a factory
   panel is an open item.

WebUSB `requestDevice` filters (one picker per phase, since VID/PID change between phases):
- FEL: `{ vendorId: 0x1f3a, productId: 0xefe8 }`
- fastboot: fastboot.js already filters by interface `classCode 0xff, subclassCode 0x42,
  protocolCode 0x03` (`fastboot.ts:7-9,234-240`) **[verified]**. The device also matches VID
  `0x1f3a`; keep the interface filter (more robust across bcdDevice).
- adb: Tango's `AdbDaemonWebUsbDeviceManager.requestDevice()` with the default interface filter
  `0xff/0x42/0x01` (`device.ts:33-35`) **[verified]**.

**Hardware gate (refuse anything that is not an S1 Plus), mirroring the CLI:**
- FEL: `ver` must report `soc=00001855` (A133) — `fel-install.sh:44`. The SoC id is bits [23:8] of
  the version word, i.e. `(le32 >> 8) & 0xffff` → `0x1855` (`fel_lib.c:201`). **[verified]**
- fastboot getvars (`fel-install.sh:54-56`) **[verified]**:
  - `product` = `sunxi` (`fb_getvar.c` `getvar_product` → `CONFIG_SYS_BOARD="sunxi"`, `.config:34`).
  - `partition-size:boot` = `0x0000000002000000` (32 MiB). getvar prints `0x%016zx`
    (`fb_getvar.c:276`).
  - `partition-size:super` = `0x0000000060000000` (1.5 GiB).
- Backups + the boot header parser additionally confirm the GPT/boot layout (page size 2048, header
  version 2, see §4.1). If any check fails: **stop before any write** and show "unknown hardware".

Reference backup hashes on the simulated factory panel (for the test plan; device-unique partitions
excluded) — from `~/src/aqara-panel-backups/fel-install-test-20261109-200402/SHA256SUMS`
**[verified]**:
- stock `boot` sha256 `29f329f0…e1e3`, Magisk SHA1 `48384b7f…6433`.

---

## 2. FEL: the WebUSB port of the sunxi-fel subset

Only the commands `fel-boot.sh` uses are needed: `ver`, `spl`, `write` (implicit in SPL/U-Boot
upload), `reset64`. No SPI, no SID, no readl/writel loops are required for the happy path (readl is
only needed if we add the H6 secure-boot workaround — see §2.5).

### 2.1 USB framing (AWUC/AWUS)  **[verified: `fel_lib.c:115-192`]**

FEL is a tiny protocol over one bulk IN and one bulk OUT endpoint. Endpoints are discovered by
walking the interface descriptor for the two bulk endpoints (`fel_lib.c:632-669`); in WebUSB, open
the device, `selectConfiguration(1)`, `claimInterface(0)`, and read
`configuration.interfaces[0].alternates[0].endpoints` for the IN/OUT bulk endpoint numbers.

Every logical transfer is three USB operations:

1. **Command wrapper** OUT, 32 bytes, struct `aw_usb_request` (`fel_lib.c:115-151`):
   ```
   char   signature[8] = "AWUC"    (NUL-padded to 8)
   u32le  length                   // byte count of the data phase
   u32le  unknown1    = 0x0c000000
   u16le  request                  // 0x11 = AW_USB_READ, 0x12 = AW_USB_WRITE
   u32le  length2     = length
   u8     pad[10]      = 0
   ```
   Note the struct is `__attribute__((packed))` and `request` is a `u16` at offset 18, so the two
   trailing length/pad fields are byte-packed, not aligned. Reproduce the exact byte layout, not a
   naïve struct.
2. **Data phase** — OUT for WRITE, IN for READ, `length` bytes, split into bulk transfers.
3. **Status** IN, 13 bytes, must begin `"AWUS"` (`aw_read_usb_response`, `fel_lib.c:153-159`).

On top of that sits the **FEL request** — a 16-byte `aw_fel_request` sent as the data phase of an
`AW_USB_WRITE` (`fel_lib.c:127-186`):
```
u32le request   // 0x001 VERSION, 0x101 WRITE, 0x102 EXEC, 0x103 READ
u32le address
u32le length
u32le pad = 0
```
So, e.g., **`aw_fel_read(addr, len)`** = WRITE(FEL READ req) → READ(len bytes) → READ(8-byte FEL
status) (`fel_lib.c:210-215`). **`aw_fel_write`** = WRITE(FEL WRITE req) → WRITE(data) → READ(status)
(`fel_lib.c:218-226`). **`aw_fel_execute(addr)`** = WRITE(FEL EXEC req) → READ(status)
(`fel_lib.c:229-233`).

WebUSB chunking: libusb used a 512 KiB max bulk and a 10 s timeout (`fel_lib.c:34,72`). The browser
should mirror `fel-boot.sh`'s hard lesson and keep SPL/U-Boot payload writes to **64 KiB chunks**
(`fel-boot.sh:18`), because at the SPL's 408 MHz clock the BROM's CPU-driven USB times out on large
single transfers (`fel-boot.sh:4-7`, `docs/research/fel.md` §"USB uploads"). Each `transferOut` is one 64 KiB
slice; the FEL WRITE request's `length` is the **whole** payload for that `aw_fel_write`, and the
data is streamed across several bulk transfers (as `usb_bulk_send` loops, `fel_lib.c:87-98`).
**[verified in source; browser timeout tuning is [assumed] until tested]**

### 2.2 `ver`  **[verified: `fel.c:108-120`, `fel_lib.c:195-207`]**

Send FEL VERSION, read 32 bytes into `aw_fel_version` (`soc_info.h:25-34`):
```
char signature[8]; u32 soc_id; u32 unknown_0a; u16 protocol;
u8 unknown_12; u8 unknown_13; u32 scratchpad; u32 pad[2];
```
`soc_id = (le32(raw.soc_id) >> 8) & 0xffff`. Gate on `0x1855`.

### 2.3 A133 SoC constants  **[verified: `soc_info.c:650-662`, `:154-156`]**

```
spl_addr     = 0x00020000
scratch_addr = 0x00021000
thunk_addr   = 0x00040400   thunk_size = 0x200
sram_size    = 148 * 1024          (0x25000)
swap_buffers = [ { buf1: 0x21000, buf2: 0x40000, size: 0x400 }, { size: 0 } ]
rvbar_reg    = 0x08100040
sid_base     = 0x03006000   sid_offset = 0x200
sec_boot_wa  = SECURE_BOOT_MONITOR_H6
watchdog     = { reg_mode: 0x030090b8, value: 1 }   (soc_info.c:215-218)
```

### 2.4 SPL load + execute (`spl` command)  **[verified: `fel.c:824-962`]**

`fel-boot.sh` runs `fel spl u-boot-sunxi-with-spl.bin` (`fel-test.sh:18-20` for the standalone
path; the installer reuses the same `aw_fel_write_and_execute_spl`). Steps the TS port must do, in
order:

1. **eGON header check** (`fel.c:839-853`): bytes [4:12] == `"eGON.BT0"`; `spl_len = le32(buf[16])`;
   verify the sunxi SPL checksum: `2*le32(buf[12]) - 0x5F0A6C39 - Σ le32(words[0..spl_len/4])` == 0.
   Our image satisfies this (`u-boot-sunxi-with-spl.bin` starts `1600 00ea 6547 4f4e 2e42 5430`
   = branch + `eGON.BT0`, `SPL` header at 0x14) **[verified]**.
2. A133 has **no** `needs_l2en`, so skip the L2 enable (`fel.c:855-858`).
3. `aw_get_stackinfo` — read SP/SP_irq by briefly switching to IRQ mode and back
   (`fel.c:485-521`). Needs one scratch write+exec+read.
4. `aw_backup_and_disable_mmu` — the A133 BROM leaves the MMU **off** (`spl_addr` is SRAM), so
   `aw_get_sctlr` returns with bit0 clear and the function returns `NULL` and disables nothing
   (`fel.c:725-732`). The A133 entry has no `mmu_tt_addr`, so no table is generated
   (`fel.c:864`). **Net: the MMU path is a no-op for A133** — but keep the SCTLR sanity read so we
   fail loudly if a future BROM differs. **[verified: A133 has `mmu_tt_addr`=0 in soc_info]**
5. **SRAM swap** (`fel.c:885-922`): with swap buffer `{0x21000→0x40000, 0x400}`, the SPL body is
   written in pieces: the part below 0x21000 to `spl_addr` directly, the 0x400 bytes that would land
   at 0x21000 are redirected to 0x40000 (BROM scratch), the remainder after. `spl_len_limit` ends up
   `min(sram_size, thunk_addr-spl_addr) = 0x20400`. Error out if `spl_len > spl_len_limit`.
6. **Thunk** (`fel.c:924-946`): append `spl_addr` and the swap-buffer table to the
   `fel-to-spl-thunk` word array (69 words, `thunks/fel-to-spl-thunk.h`), write it at `thunk_addr`,
   `aw_fel_execute(thunk_addr)`. The thunk restores the swapped SRAM, runs the SPL (DRAM init), and
   returns to FEL.
7. Sleep ~250 ms (`fel.c:949-950`), then read `spl_addr+4` (8 bytes) and require `"eGON.FEL"`
   (`fel.c:953-955`) — this is the "SPL returned cleanly" signal that `fel-test.sh:20` relies on.

Because A133's MMU path is a no-op, `aw_restore_and_enable_mmu` (`fel.c:768-819`) is also skipped
(`tt == NULL`, `fel.c:958`). The TS port can omit the whole MMU translation-table generator for
A133. **[verified]**

### 2.5 Secure-boot workaround  **[verified: `fel.c:610-662`]**

`sunxi-fel` runs `handle_secure_boot` right after open (`fel.c:1448`). For A133
(`SECURE_BOOT_MONITOR_H6`) it reads SID `base+0xa0`; if 0 (secure boot not fused) it returns without
doing anything (`fel.c:642-644`). Our panel has secure boot **off** (README: `secure enable bit:
0`), so this is expected to be a no-op **[assumed: SID read not done in this design session]**. The
port should implement the read-and-early-return and only upload the H6 monitor thunk
(`upload_h6_secure_boot_wa`, `fel.c:567-598`) if the fuse is non-zero — in which case we should stop
and warn, because a fused panel is out of scope.

### 2.6 Upload U-Boot + ATF and warm-reset (the `NO_BL31=1` path we use)

The CLI (`fel-boot.sh`) does **not** use `sunxi-fel uboot`. It uploads the FIT pieces itself in
64 KiB chunks and warm-resets. Our `u-boot-sunxi-with-spl.bin` embeds a FIT at file offset
**0xa000** (`d00dfeed`, total size 769781) with three images **[verified by decompiling the real
image]**:

| FIT image | type | arch | load | contents |
|---|---|---|---|---|
| `uboot` | standalone | arm64 | `0x4a000000` | U-Boot proper (`u-boot-nodtb.bin`) |
| `atf` | firmware | arm64 | `0x40000000`, entry `0x40000000` | TF-A BL31 |
| `fdt-1` | flat_dt | — | appended after U-Boot | board DTB |

`config-1` = `{ firmware: atf, loadables: uboot, fdt: fdt-1 }`. Note `u-boot.bin` ==
`u-boot-nodtb.bin ++ u-boot.dtb` **[verified: byte-equal]**, and the FIT's `uboot` image is
`u-boot-nodtb.bin`, DTB appended right after it at `load+size` (`fit_image.c:179-181`,
`:270-279`).

But the **installer's** `fel-boot.sh` is simpler than parsing the FIT — it uploads
`$B/u-boot.bin` (nodtb+DTB) to `0x4a000000` and, in the default build, BL31 to `0x40000000`, then
`reset64 <entry>` (`fel-boot.sh:14-44`). With `NO_BL31=1` (what `fel-install.sh:47` passes) it
uploads **only** `u-boot.bin` to `0x4a000000` and warm-resets straight to it at EL3 — no TF-A
(`fel-boot.sh:36-44`, `docs/research/fel.md` §"No TF-A for now"). **The browser should do exactly the
`NO_BL31=1` path:**

1. `aw_fel_write(u-boot.bin, 0x4a000000)` in 64 KiB chunks (host-side split, `fel-boot.sh:26-33`).
   `u-boot.bin` is 714184 bytes **[verified]**; ship it as a pinned asset (it already contains the
   appended DTB, so no FIT parsing is needed).
2. `reset64 0x4a000000` → `aw_rmr_request(entry, aarch64=true)` (`fel.c:1474-1478`,
   `:1159-1206`). The RMR thunk stores the entry point to **RVBAR** `0x08100040`, ORs RR|AA64 into
   RMR via `mcr p15,0,…,c12,c0,2`, then `wfi` loops (`fel.c:1178-1197`). This triggers a 64-bit warm
   reset into U-Boot at EL3. **[verified]**

After reset, U-Boot runs `CONFIG_BOOTCOMMAND="fastboot usb 0"` after a 2 s delay (`defconfig:41,47`)
and the USB device **re-enumerates as `1f3a:1010`** — a WebUSB `disconnect` then a new device. The
app must prompt a second `requestDevice` for the fastboot phase (WebUSB does not auto-carry
permission across VID/PID). **[verified config; re-enumeration timing [assumed]]**

> **Alternative considered:** implement the full FIT/`uboot` path (`fel.c:1536-1541`,
> `load_fit_images`) so a single `u-boot-sunxi-with-spl.bin` asset suffices. Rejected for v1: the
> `NO_BL31=1` + `u-boot.bin` path is exactly what passed the factory sim, needs no FIT parser, and
> keeps TF-A out (which we don't need for fastboot/eMMC and which was itself only ever run with the
> DRAM fix, untested — `docs/research/fel.md` §"No TF-A"). We still ship `u-boot-sunxi-with-spl.bin` as the
> SPL source for step 2.4.

---

## 3. Fastboot over WebUSB

> 2026-11-12 (#111): `kiln/packages/fastboot` is now a fork of fastboot.js for every frame. What it
> changed from upstream, and how the S1's wire stayed the same, is in
> [its README](../../kiln/packages/fastboot/README.md). The notes below are from the first design.

Reuse **fastboot.js** (MIT, `fastboot.ts`) essentially unmodified for `connect`, `getVariable`,
`runCommand`, `upload` (download+send), `flashBlob`, `reboot`. It already:
- filters the fastboot interface `0xff/0x42/0x03` and claims interface 0 (`fastboot.ts:160-161,
  234-240`) **[verified]**;
- reads 64-byte responses, handling `OKAY`/`INFO`/`DATA`/`FAIL` (`fastboot.ts:286-317`) **[verified]**;
- sends commands as raw UTF-8, enforcing the **64-byte** command limit (`fastboot.ts:327-335`)
  **[verified]** — this is the same limit `fb-read-partition.sh:17` works around with short env var
  names;
- downloads via `download:%08x` then streams 16 KiB bulk chunks and reads the final response
  (`fastboot.ts:443-482`, `BULK_TRANSFER_SIZE=16384` at `:12`).

Our U-Boot gadget details that matter **[verified: `f_fastboot.c`, `fb_command.c`]**:
- EP max packet 64 (FS) / 512 (HS) (`f_fastboot.c:80-104`); command & response buffers are 64+1
  (`fastboot.h:30-31`).
- `max-download-size` is the 512 MiB buffer (`.config:1280` `CONFIG_FASTBOOT_BUF_SIZE=0x20000000`).
  fastboot.js caps its own download at 1 GiB and parses the var as hex (`fastboot.ts:381-396`)
  **[verified]**. Our biggest single write is `super` (1.5 GiB) which we never write; `boot`
  (32 MiB) and `vbmeta` (≤16 MiB) are well under the buffer, so **no sparse splitting is needed**
  for anything the installer flashes. Keep `flashBlob`'s sparse path for safety but it won't trigger.

### 3.1 Two extensions to add (not in upstream fastboot.js)

**(a) `oem run:<cmd>`** — just `runCommand("oem run:" + cmd)`; U-Boot dispatches `oem run` to
`run_ucmd` → `run_command` (`fb_command.c:180-182, 548-563`) **[verified]**. The 64-byte command
limit includes the `oem run:` prefix (8 bytes), so each U-Boot command has ≤56 bytes — exactly why
`fb-read-partition.sh` uses short names. A thin `oemRun(cmd)` wrapper that throws on `FAIL` mirrors
`fb-read-partition.sh:16`.

**(b) `upload` / `get_staged`** — the custom command from `tools/fel/uboot/patches/0002`
**[verified in both the patch and the built tree]**:
- host sends `upload`; device replies `DATA%08x` with `${fastboot_upload_size}`
  (`fb_command.c:344-353`); then streams that many bytes from `fastboot_buf_addr` in EP_BUFFER_SIZE
  (4096) chunks, ending `OKAY` (`f_fastboot.c:501-522`).
- fastboot.js has no reader for a `DATA` response that it did not initiate with `download`. Add
  `getStaged(size)`: send `upload`, parse the `DATA` size from `_readResponse` (expose it;
  `fastboot.ts:306-308` already stores `dataSize`), then loop `transferIn(epIn, 4096)` until `size`
  bytes are collected, then read the trailing `OKAY`. This matches `fastboot get_staged`
  (`fb-read-partition.sh:23`).

### 3.2 Read a partition  **[verified: `fb-read-partition.sh`]**

Mirror the script exactly (`BUF=0x50000000`, `MMC=1`):
```
oemRun("mmc dev 1")
oemRun("part start mmc 1 <part> s")      // sets $s = start LBA
oemRun("part size  mmc 1 <part> n")      // sets $n = block count
oemRun("mmc read 0x50000000 $s $n")
oemRun("setexpr fastboot_upload_size $n * 200")   // 0x200 = 512 bytes/block
buf = getStaged($n * 0x200)
```
`$n * 200` is hex 0x200 = 512 (U-Boot `setexpr` default radix). Reads up to the 512 MiB buffer at
~20 MB/s (`docs/research/fel.md` step 5). `fel-install.sh` partitions: `boot vbmeta vbmeta_system
vbmeta_vendor dtbo misc recovery env bootloader` (`fel-install.sh:29`).

### 3.3 Flash + verify  **[verified: `fel-install.sh:88-90`, `fb_mmc.c:470-481`]**

`flash boot` = download the image then `flash:boot`; U-Boot writes raw (not sparse — our images are
raw, `is_sparse_image` false) via `fastboot_mmc_flash_write` → `fastboot_block_write_raw_image`
(`fb_mmc.c:476-481`). **After every write, read the partition back with §3.2 and `cmp`** (the CLI
does this for vbmeta at `:80-81` and boot at `:89-90`; on mismatch it refuses to reboot). `reboot` →
`fastboot.reboot()` (`fastboot.ts:492-506`).

---

## 4. Byte-exact boot patch (the hard part), in the browser

**Goal:** given the panel's own stock `boot.img` (read in §3.2) and the browser's adb public key,
produce a patched image **identical** to what `tools/root/patch-boot-magisk.sh` produces in Docker.

This section was **validated end-to-end on this machine today** by re-implementing the container
steps in Python and comparing against the real golden output
(`~/src/aqara-panel-backups/fel-install-test-20261109-200402/{boot,s1-boot}.img`): the rebuilt image
hashed **`d1a4925f9d2324d0e06d066862b29287c2c4aa224f4352caf1cf3d73deb4969a`**, byte-identical to the
golden `s1-boot.img` **[verified]**. Stock `boot` sha256 is `29f329f0…`, matching `docs/research/root.md`.

### 4.1 Android boot image v2 layout (this panel)  **[verified by parsing the real image]**

Header (little-endian, page size 2048, header version 2):
```
magic "ANDROID!"; kernel_size=20568072 load 0x40080000; ramdisk_size=758632 load 0x43000000;
second_size=0; tags 0x40000100; page_size=2048; header_version=2; os_version=0x14000149;
name[16]=0; cmdline[512]="selinux=1 androidboot.selinux=permissive androidboot.dtbo_idx=0,1,2 buildvariant=user";
id[32] (SHA1 in first 20 bytes, rest 0 → SHA1, not SHA256);
extra_cmdline[1024]="";
recovery_dtbo_size=0 recovery_dtbo_offset=0 header_size=1660; dtb_size=127777 dtb_addr=0x44000000.
```
Block offsets (page-aligned): kernel@2048, ramdisk@20572160, (second@=recovery_dtbo@)21331968,
dtb@21331968, image end 21460992. The file is padded to the 32 MiB partition and carries an **AVB
hash footer** (see §6). Kernel is a raw ARM64 image (`...ARMd` at +0x38); ramdisk is **gzip**
(`1f 8b 08`). **[verified]**

Magiskboot's parser and repacker, which the TS port reimplements:
- parse header, split blocks, record `tail` and detect the AVB footer (`bootimg.cpp:339-466`);
  SHA256_FLAG only if bytes 24..32 of `id` are non-zero — here they're zero → **SHA1**
  (`bootimg.cpp:346-353`). **[verified: our id has zero tail]**
- repack writes header page, then kernel, ramdisk, second, extra, recovery_dtbo, dtb, each
  page-aligned, recomputes the `id` SHA1 over (kernel|size)(ramdisk|size)(second|size)
  [(recovery_dtbo|size) if v1/v2] [(dtb|size) if v2] (`bootimg.cpp:872-901`), then handles AVB
  (§6), then pads to original size (`bootimg.cpp:834-837`). **[verified: reproduced byte-exact]**

### 4.2 What `boot_patch.sh` + the repo wrapper actually do  **[verified: scripts + golden]**

Reading `assets/boot_patch.sh` (v31.1), `assets/util_functions.sh`, and the repo wrapper
`tools/root/patch-boot-magisk.sh`, the deterministic sequence is:

1. **unpack** the stock boot image → `kernel`, `ramdisk.cpio` (gunzipped), `dtb`
   (`boot_patch.sh:85-97`).
2. **ramdisk status** = stock (`magiskboot cpio test` returns 0). Record `SHA1 = sha1(stock boot
   file)` = `48384b7f7520e49c7c8bb9357a4b2d89dddc6433` (`boot_patch.sh:124-131`,
   `magiskboot sha1`). Keep `ramdisk.cpio.orig`. **[verified: SHA1 matches golden config]**
3. **BOOTMODE=false** outside Android (`util_functions.sh:747-749`: no zygote). So `PREINITDEVICE`
   is **not** overwritten from the config, and the wrapper's `PREINITDEVICE=cache` env stands
   (`boot_patch.sh:152-156`, wrapper `:25,57`). `ui_print` needs `OUTFD` and `grep_prop` needs
   `dos2unix` — the wrapper injects both (`patch-boot-magisk.sh:62-63`). **[verified]**
4. **compress** `magisk` and `init-ld` to `.xz` (§4.4) (`boot_patch.sh:168-169`).
5. **config** file (exact bytes, LF-terminated) (`boot_patch.sh:171-179`):
   ```
   KEEPVERITY=true
   KEEPFORCEENCRYPT=true
   RECOVERYMODE=false
   VENDORBOOT=false
   PREINITDEVICE=cache
   SHA1=48384b7f7520e49c7c8bb9357a4b2d89dddc6433
   ```
   **[verified: byte-equal to golden `.backup/.magisk`]**
6. **cpio edit** on `ramdisk.cpio` (`boot_patch.sh:181-191`):
   `add 0750 init magiskinit`; `mkdir 0750 overlay.d`; `mkdir 0750 overlay.d/sbin`;
   `add 0644 overlay.d/sbin/magisk.xz`; `add 0644 overlay.d/sbin/init-ld.xz`;
   `patch` (KEEPVERITY+KEEPFORCEENCRYPT both true → **no fstab change**, §4.3);
   `backup ramdisk.cpio.orig` (diff stock vs current, store changed/new originals xz-compressed under
   `.backup/`, write `.backup/.rmlist` of added names) (`cpio.rs:597-694`);
   `mkdir 000 .backup`; `add 000 .backup/.magisk config`.
7. **dtb patch** (`boot_patch.sh:199-209`): `magiskboot dtb dtb patch` — fstab `verity`/`encrypt`
   patch inside the boot DTB. With KEEPVERITY=KEEPFORCEENCRYPT=true this is a **no-op** on our image
   (the repack reproduced byte-exact without touching the dtb). **[verified: golden dtb == stock
   dtb bytes]**
8. **kernel**: Samsung hexpatches don't match our kernel; `LEGACYSAR=false`; so kernel is unchanged
   and even kept raw (`boot_patch.sh:211-240`). **[verified]**
9. **repack** → `new-boot.img` (`boot_patch.sh:247-248`), re-gzip the ramdisk (§4.4), recompute the
   SHA1 `id`, rebuild the AVB footer (§6).
10. **the repo wrapper then runs a *second* `magiskboot cpio` pass** adding our overlay files
    (`patch-boot-magisk.sh:67-78`): `overlay.d/s1kiosk_radio.rc` (0644),
    `overlay.d/sbin/s1kiosk_radio.sh` (0700), `overlay.d/sbin/otbr-agent` (0700),
    `overlay.d/sbin/ot-ctl` (0700), `overlay.d/s1kiosk_firstboot.rc` (0644),
    `overlay.d/sbin/s1kiosk_firstboot.sh` (0700), `overlay.d/sbin/s1kiosk_adb_keys` (0600), then
    `repack` again. The browser can do it all in one in-memory cpio and one repack — the result is
    identical because cpio output is canonical (§4.5). **[verified]**

Final cpio entries (sorted, the exact golden set) **[verified]**:
```
.backup .backup/.magisk .backup/.rmlist .backup/init.xz
apex avb avb/{q,r,s}-gsi.avbpubkey debug_ramdisk dev
fstab.sun50iw10p1 init mnt
overlay.d overlay.d/s1kiosk_firstboot.rc overlay.d/s1kiosk_radio.rc
overlay.d/sbin overlay.d/sbin/{init-ld.xz,magisk.xz,ot-ctl,otbr-agent,
                               s1kiosk_adb_keys,s1kiosk_firstboot.sh,s1kiosk_radio.sh}
proc sys
```
`.backup/.rmlist` = `overlay.d\0overlay.d/sbin\0overlay.d/sbin/init-ld.xz\0overlay.d/sbin/magisk.xz\0`
— the four entries present in the patched-by-Magisk cpio but absent from stock, recorded so a
`restore` removes them (our own overlay files are added in the *second* pass, after Magisk's backup,
so they are not in the rmlist). **[verified: byte-equal]**

### 4.3 `cpio patch` (verity/forceencrypt)  **[verified: `cpio.rs:505-537`, `patch.rs`]**

With `KEEPVERITY=true KEEPFORCEENCRYPT=true` (what `fel-install.sh` uses, `patch-boot-magisk.sh:64`),
`Cpio::patch` does nothing: the `fstab` predicate is `(!keep_verity || !keep_force_encrypt) && …`
which is false, and `verity_key` is only removed when `!keep_verity` (`cpio.rs:512-527`). So the TS
`patch` step is a conditional we can implement but which is inert for our flags. Still implement
`patch_verity`/`patch_encryption` (`patch.rs`) for correctness if flags ever change. **[verified]**

### 4.4 Compression — the one piece that must be bit-identical

magiskboot v31.1 uses Rust crates with **specific** settings (`native/src/boot/compress.rs`)
**[verified]**:
- **gzip** (ramdisk): `flate2` **1.1.10** with the **`zlib-rs`** backend (**0.6.8**),
  `Compression::best()` (level 9) (`compress.rs:8-10,242`; `Cargo.toml:35`, `Cargo.lock`).
  zlib-rs at level 9 does **not** produce the same bytes as stock zlib/`CompressionStream` — our
  Python test with system zlib 1.2.12 level 9 **differed at byte 9** from the golden ramdisk
  **[verified: mismatch]**. So we cannot use the browser's `CompressionStream('gzip')`.
- **xz** (`magisk`, `init-ld`, and the `.backup/*.xz`): `lzma-rust2` **0.21.0**,
  `XzOptions::with_preset(6)` + **CRC32** check + a **BCJ pre-filter** chosen by
  `detect_bcj_filter` (`compress.rs:17-19,218-221,247-261`; `cpio.rs:703` for `.backup`). For our
  armeabi-v7a ELF payloads (`e_machine=0x28`) the filter is **ARM Thumb** (`compress.rs:250`).
  Python `lzma` with `FILTER_ARMTHUMB`+preset6 **also differed** from golden **[verified: mismatch]**
  — liblzma and lzma-rust2 are not byte-compatible.

**Decision: compile magiskboot's exact compressors to WebAssembly and call them from JS.** A small
Rust shim crate depending on the **pinned** versions above, built `--target wasm32-unknown-unknown`
(or `wasm32-wasip1`), exposing `gzip_best(&[u8])` and `xz_preset6_crc32_bcj(&[u8])`. This was
**proven today**:
- A pinned harness (`flate2=1.1.10`+`zlib-rs=0.6.8`, `lzma-rust2=0.21.0`) built **natively** in a
  `rust:1.91` container reproduced all four golden blobs exactly: `magisk.xz`, `init-ld.xz`,
  `.backup/init.xz`, and the final gzip ramdisk — **all byte-identical** **[verified]**.
- The **same crate compiled to `wasm32-wasip1`** and run under Node's WASI reproduced the same four
  blobs **byte-identically**, in <1 s each; the `.wasm` is ~295 KB (~111 KB gzipped) **[verified]**.

So the browser's `magiskboot.wasm` = those two Rust functions. It needs no filesystem; feed it
`Uint8Array` in, get `Uint8Array` out. The BCJ filter is selected in JS by the same rule
(`compress.rs:241-267`): ELF → map `e_machine` (0x28→ArmThumb, 0xB7→Arm64, 0x03/0x3E→x86,
0xF3→Riscv); non-ELF ARM64 (`"ARM\x64"` at 0x38) → Arm64; else none. Our payloads are all ELF
armeabi-v7a → ArmThumb. The streaming vs one-shot distinction does not matter: chunked writes
produced the same xz output as one-shot in the harness **[verified]**.

> Note: `compress_cmd` only sniffs the first 4096 bytes for BCJ detection, while `CpioEntry::compress`
> sniffs the whole buffer (`compress.rs:378-386` vs `cpio.rs:703`). For ELF payloads the first 20
> bytes decide it, so both agree. The wasm shim should take the detected filter as an argument so JS
> owns the (verified) decision.

### 4.5 cpio format (newc) — canonical output  **[verified: `cpio.rs`, reproduced]**

- Header is `070701` + 13 × 8-hex fields: ino, mode, uid, gid, nlink=1, mtime=0, filesize, 0, 0,
  rdevmajor, rdevminor, namesize(=len+1), check=0 (`cpio.rs:277-291`).
- **ino starts at 300000 and increments** per entry, in **sorted-by-name** order (entries live in a
  `BTreeMap`, dumped in key order) (`cpio.rs:273-301`). This ordering + inode numbering is why the
  output is deterministic and why the browser can do one pass instead of two.
- Name is NUL-terminated; header+name padded to 4 bytes; data padded to 4 bytes (`cpio.rs:293-299`,
  `align_4`).
- Trailer: one more header with mode 0755, namesize 11, name `TRAILER!!!\0`, padded
  (`cpio.rs:302-308`).
- `uid=gid=0` for all entries; directories have empty data; `add` stores the file's bytes with
  `mode|S_IFREG` (`cpio.rs:393-436`). Our reimplementation's canonical dump equalled the golden cpio
  exactly **[verified]**.

**backup/restore** (`cpio.rs:562-694`): the browser must implement `backup` (diff stock ramdisk vs
patched, xz-compress changed originals into `.backup/<name>.xz`, build `.rmlist`) and `restore`
(invert it) because the *re-run on an installed panel* path depends on `restore` running first
(§8.2). This was **verified** today: patching the already-patched golden `s1-boot.img` through the
real CLI produced `d1a4925f…` again (restore → identical repatch) **[verified]**.

---

## 5. The adb keypair baked into the image

- Generate an RSA-2048 keypair in the browser with WebCrypto, store the PKCS#8 private key in
  **IndexedDB**. This is exactly Tango's `AdbWebCredentialStore.generateKey()`
  (`adb-credential-web/src/index.ts:84-105`): `RSASSA-PKCS1-v1_5`, modulus 2048, exponent 65537,
  exportKey `pkcs8`, saved to IndexedDB (`index.ts:7`, db name "Tango") **[verified]**.
- The public key that gets baked into `overlay.d/sbin/s1kiosk_adb_keys` must be in **adb authorized_keys
  format**: `<base64 of the Android mincrypt/BoringSSL public-key struct> <name>\n`. Tango builds
  exactly this: `adbGeneratePublicKey` emits the little-endian struct
  `[modulusWords=64, n0inv, modulus(256), rr(256), exponent=65537]` (`crypto.ts:191-266` in 2.6.4 /
  `auth/public-key.ts:14-98` in beta), base64-encodes it, appends a space, a name, and `\n`
  (`auth/packet-processor.ts:170-196`). The CLI expects a line starting `QAAAA` (base64 of
  `0x40,0,0,0` = 64 words) (`patch-boot-magisk.sh:51`) — `QAAAA…` is what this struct base64s to.
  **[verified: format matches; base64 prefix `QAAAA` corresponds to modulusWords=64]**
- The first-boot service appends this line to `/data/misc/adb/adb_keys` and enables USB adb with no
  screen tap (`s1kiosk_firstboot.sh:16-32`). So the browser's own generated public key, in this
  format, is what we feed to the cpio `add 0600 overlay.d/sbin/s1kiosk_adb_keys` step (§4.2 step 10).
- **Byte-exactness caveat:** the golden image was built with the reference host's key, so the patched
  image is only reproducible byte-for-byte **given the same key** (we confirmed this by re-patching
  with the golden image's own extracted key → `d1a4925f…`). With the browser's own key, every byte
  except the `s1kiosk_adb_keys` entry (and the resulting cpio inode stream shifts + SHA1 id + gzip)
  is deterministic; the test plan (§10) covers this by substituting a fixed test key. **[verified
  logic; per-key output is deterministic but device-specific]**

---

## 6. vbmeta / AVB

Two distinct AVB concerns:

**(a) The panel's `vbmeta` partition** (`fel-install.sh:73-85`): read it, check the 32-bit **big-endian**
flags at **offset 120** of the `AVB0` vbmeta header; if not 3, set `flags |= 3`, flash, read back and
`cmp` (`fel-install.sh:74-82`). The design's `vbmeta.ts` does the same on the `Uint8Array`
(`struct.pack_into('>I',120, flags|3)`). On the sim panel the flags-3 result was byte-identical to
the `vbmeta` the panel had run before (`c5219a3d…` stock flags-0 → `00885746…` flags-3) **[verified:
hashes present in backups]**. Only flip flags if needed; our factory sim started at flags 0.

**(b) The boot image's own AVB hash footer** (part of §4 repack): our stock `boot.img` ends with an
`AVBf` footer (version 1.0, original_image_size 21460992, vbmeta_offset 21463040, vbmeta_size 576,
**flags 0**) plus the `AVB0` vbmeta block **[verified by parsing]**. magiskboot's repack, because it
sees `AVB_FLAG`, re-emits the footer: 4096-align, write the preserved vbmeta block, patch
`original_image_size` and `vbmeta_offset`, and — only if `PATCHVBMETAFLAG` — set the boot vbmeta
flags to 3 (`bootimg.cpp:818-924`). The wrapper does **not** pass `PATCHVBMETAFLAG`
(`patch-boot-magisk.sh:64` sets only KEEPVERITY/KEEPFORCEENCRYPT), so the boot image's own vbmeta
flags stay 0, and §6(a) handles the standalone `vbmeta` partition instead. Our reproduction kept the
boot footer's flags at 0 and matched golden **[verified]**. The TS repack must replicate the
4096-alignment and footer patching exactly (it did, in the Python model).

---

## 7. adb over WebUSB (Tango) — the `tools/install.sh` port

Use **ya-webadb / Tango** (MIT). Pin **v2.6.4** (stable) unless the 3.0 beta API is wanted; the
relevant pieces exist in both, with renamed symbols (`AdbWebCredentialStore` →
`AdbWebCryptoCredentialManager` + `TangoKeyStorage`, `AdbDaemonTransport.authenticate` static →
`adbDaemonAuthenticate` function). Flow **[verified: API shapes in source]**:

```
const manager = AdbDaemonWebUsbDeviceManager.BROWSER            // manager.ts:15-21
const device  = await manager.requestDevice()                  // manager.ts:39 (interface 0xff/0x42/0x01)
const connection = await device.connect()                      // device.ts:333
const store = new AdbWebCredentialStore("s1kiosk")             // index.ts:70 (same IndexedDB key as §5)
const transport = await AdbDaemonTransport.authenticate({      // transport.ts:159
    serial: device.serial, connection, credentialStore: store })
const adb = new Adb(transport)                                 // adb.ts:64,99
```
Because the first-boot service already authorized our key (§5, `s1kiosk_firstboot.sh`), the panel
does **not** prompt on screen — `authenticate` succeeds with the stored key and we get a shell
immediately. **[verified logic; on-device no-prompt behavior verified by the CLI factory sim per
docs/research/root.md]**

Commands the port needs (all via `adb.subprocess.noneProtocol.spawnWaitText(...)`,
`adb/src/commands/subprocess/none/spawner.ts:57-66`, and `adb.subprocess` service
`service.ts:13-29`):
- **install APK:** `PackageManager.installStream(size, stream, {replace:true, grantRuntimePermissions:true})`
  (`android-bin/src/pm.ts:293-366`) — this is `adb install -r -g` (`install.sh:82`). Stream the
  kiosk APK blob (103 MB, §9). For Android 10 (sdk 29) `cmd`/streaming install is supported
  (`pm.ts:359-363`). **[verified API; APK is sdk-29 target]**
- **shell one-liners**, 1:1 with `install.sh`: `pm grant … RECORD_AUDIO|CAMERA|WRITE_SECURE_SETTINGS`
  (`:85-87`), `appops set … SYSTEM_ALERT_WINDOW allow` (`:88`), `dpm set-active-admin`
  (`:90`), `cmd package set-home-activity sh.edm.s1kiosk/.MainActivity` (`:93`),
  `service call A_device_control 7 i32 0 i32 0` (disable Aqara DND, `:97`), the debloat
  `pm disable-user --user 0 <pkg>` set (`debloat.sh:38-41` / `install.sh:102-103`), optional IME,
  timezone `service call alarm 3 s16 <TZ>` (`:116`), locale `settings put system system_locales`
  (`:120`), and `am start -n …/.MainActivity --es url <HA_URL>` (`:130`).
- **wake-word models** (`install.sh:124` → `voice/fetch-models.sh`): the five openWakeWord v0.5.1
  `.tflite` files pushed to `/sdcard/Android/data/sh.edm.s1kiosk/files/models/`
  (`fetch-models.sh:28-34, 83-90`). In the browser, host these as pinned assets (CC-BY-NC-SA, §9)
  and push with Tango's sync service (`adb/src/commands/sync/*`). Verify each is a TFLite flatbuffer
  (`"TFL3"` at offset 4, `fetch-models.sh:45-49`) before pushing.

The HA dashboard URL the user types on the "configure" screen becomes the `--es url` extra
(`MainActivity.kt:411`, `KioskPrefs.KEY_URL="url"`). **[verified: extra key]**

### 7.1 What the panel does while the app goes in  **[verified 2026-11-12, dev panel, read-only logcat]**

Kiln shows the app install in phases (#141). Each phase starts at a command the installer runs, so
the screen follows what the panel does, not a timer. To find where the restarts fall, we read the
dev panel's log after an `adb install -r` of S1 Kiosk 0.4.3 over itself, with the app running:

```
$ adb logcat -d -b all | grep -E 'installPackageLI|dexopt|dex2oat took|pkg removed|Start proc [0-9]+:sh.edm.s1kiosk/'
18:39:25.468 ActivityManager: Force stopping sh.edm.s1kiosk appid=10083 user=-1: installPackageLI
18:39:25.713 PackageManager.DexOptimizer: Running dexopt (dexoptNeeded=1) on: …/base.apk pkg=sh.edm.s1kiosk isa=arm … targetFilter=speed-profile
18:39:25.743 ActivityManager: Start proc 7498:sh.edm.s1kiosk/u0a83 for activity {sh.edm.s1kiosk/sh.edm.s1kiosk.MainActivity}
18:39:47.740 dex2oat : dex2oat took 21.964s (16.386s cpu) (threads: 3) …
18:39:47.938 ActivityManager: Force stopping sh.edm.s1kiosk appid=10083 user=0: pkg removed
$ adb shell dumpsys package sh.edm.s1kiosk | grep -A1 'Dexopt state'
      arm: [status=speed-profile] [reason=install]
```

What it shows:

1. Android stops the app as the install starts (`installPackageLI`). The app is the home screen, so
   it starts again at once, while dex2oat runs.
2. dex2oat compiles the app for this panel (`speed-profile`, about 22 s on the S1). When it ends,
   Android stops the app again (`pkg removed`), and it starts again.
3. `pm install` returns `Success` only after dex2oat: on Android 10 the install-time compile is
   synchronous (`reason=install`).

This was an **update**: S1 Kiosk was installed and running. A **first install** (a panel without
S1 Kiosk, after Kiln's boot step) has no app running when `pm install` starts, so step 1's stop
can't happen, and Android has no S1 Kiosk to restart when dex2oat ends. We expect no restart there
until `am start`, but that is **untested**: it needs a factory-fresh panel, and the dev panel always
has the app. So Kiln says the app restarts only on an update (it checks `pm path sh.edm.s1kiosk`
first), and says nothing about restarts on a first install.

The installer can see these points, in order, and `provision()` reports them as `AppPhase`
(`frames/aqara-s1-plus/installer/src/provision.ts`). Every APK the recipe installs gets its own copy
and install, so none goes in silently:

| Phase | Starts at | What the panel does |
|---|---|---|
| `apk kiosk copy` | before the S1 Kiosk push | nothing yet; the APK goes to `/data/local/tmp` |
| `apk kiosk install` | the push is done, `pm install` runs | install and dex2oat; on an update the app stops and restarts twice |
| `home` | `pm install` returned | permissions, home app, Aqara's DND off, debloat |
| `apk keyboard copy`, `install` | the keyboard push, then its `pm install` (when the recipe has the keyboard) | the on-screen keyboard goes in |
| `prefs` | after the keyboard | time zone, language, clock |
| `start` | `am start` with the settings | the app starts with them (again, on an update) |

When the installed S1 Kiosk has another signing key, the switch (`switch.ts`) uninstalls it and runs
its own `pm install` calls (the new APK, and the old one back if that fails). They report no phases of
their own, so on the screen they sit under the S1 Kiosk install row.

Kiln ends `start` when the app's Improv socket is listening in `/proc/net/unix` again. The screen
names no durations: 22 s is one measurement on one panel.

Not checked: the first-install timeline (above), and whether the signing-key switch path
(`switch.ts`) restarts the app more often.

---

## 8. Screen flow and the two special cases

### 8.1 Happy path (factory panel), mirroring `fel-install.sh` steps 1-8
1. **Welcome / OS onboarding** (§11): per-OS driver/permission note + browser check.
2. **Enter FEL** — show `docs/img/fel-button.jpg` (1400×695) and instructions: power off, hold PCB
   button, plug USB-C, hold ~10 s ([docs/install.md](../install.md#finding-the-fel-button)). "Connect" → `requestDevice`
   `1f3a:efe8`; run `ver`; gate `0x1855` (§1).
3. **Boot our U-Boot** — SPL + U-Boot upload + reset64 (§2); wait for the `1f3a:1010` re-enumeration;
   second `requestDevice` for fastboot; getvar gate (§1).
4. **Back up** — read the nine partitions (§3.2); offer a `.zip` **download** *and* store in
   IndexedDB; compute SHA-256; read `boot` twice and compare (`fel-install.sh:60-61`). **Block
   "continue" until the backup exists.**
5. **Patch** — build the patched boot in-memory (§4) with the browser's adb key; show the stock vs
   patched SHA-256.
6. **vbmeta** — if flags≠3, patch+flash+verify (§6a).
7. **Flash boot** — flash, read back, `cmp`; on mismatch show the recovery card (§8.3) and do **not**
   reboot.
8. **Reboot + wait for adb** — `fastboot reboot`; prompt the user to accept the adb device picker
   when the panel comes up (~21 s on the sim, `docs/research/root.md`); authenticate (§7).
9. **Configure** — HA URL entry, timezone/locale, optional keyboard; run the §7 provisioning;
   install APK; push models; `am start`. Done screen with the HA ESPHome add-integration hint
   (`install.sh:139-142`).

### 8.2 Re-run on an already-installed panel  **[verified: deterministic repatch]**
- If the first `requestDevice` finds the **adb** interface (panel already booted & authorized), skip
  FEL entirely and go to §9 "configure/repair" (install/upgrade the app, re-push models) — like
  running `tools/install.sh` alone.
- If the user re-enters FEL on an already-patched panel: `patch-boot-magisk.sh` detects "Magisk
  patched boot image" and `restore`s the stock ramdisk first, then re-patches → **identical** output
  (`boot_patch.sh:132-146`, `cpio.rs:562-595`; verified: repatch of golden → `d1a4925f…`). The
  installer must surface the CLI's warning that in this case the "stock" backup is **not** stock
  (`fel-install.sh:68-71`) and tell the user to keep their *first* backup for a full undo.

### 8.3 Error states & recovery  **[verified: CLI refusal points]**
- FEL not found / wrong SoC → re-seat + button-timing help; never proceed.
- SPL `eGON.FEL` check fails (§2.4 step 7) → power-cycle and retry (RAM-only, nothing written).
- fastboot getvar mismatch → "unknown hardware", stop.
- read-back `cmp` mismatch after a write → **Undo** card: "flash `boot.img`/`vbmeta.img` from your
  backup" using the §3.3 flash path, exactly `fel-install.sh:90`'s advice and `docs/research/root.md`
  §"Undo". The backup is in IndexedDB and was offered as a download.
- WebUSB transfer stall / device reset → fastboot.js opportunistically `reset()`s on connect
  (`fastboot.ts:154-158`); offer "reconnect".

---

## 9. Asset manifest & hosting  **[verified hashes unless noted]**

All assets are hosted with the static site and each is pinned by SHA-256; the loader fetches, hashes
(WebCrypto `SHA-256`), and refuses on mismatch. Licence + notice pages are linked from the footer.

| Asset | Source / licence | Size | SHA-256 |
|---|---|---|---|
| `u-boot.bin` (nodtb+DTB, loaded to 0x4a000000) | our U-Boot, **GPL-2.0** (+ source & `tools/fel/uboot/patches`) | 714184 | `431f107a…e61f` |
| `u-boot-sunxi-with-spl.bin` (SPL source for §2.4) | our U-Boot, GPL-2.0 | 810741 | `fe9a00a6…f3f` |
| `bl31-s1.bin` (only if a BL31 build is offered) | TF-A, BSD-3-Clause | 53616 | not pinned here; built per `build-uboot.sh` |
| `Magisk-v31.1.apk` (re-hosted; GitHub blocks cross-origin) | **GPL-3.0**, link to source | 10679283 | `89cefac0…c6e7` |
| &nbsp;→ `libmagiskinit.so` (armeabi-v7a → cpio `init`) | from APK | 148776 | `8cd3bdee…c8d1` |
| &nbsp;→ `libmagisk.so` (→ `magisk.xz`) | from APK | 251288 | `57cf1284…d8a9` |
| &nbsp;→ `libinit-ld.so` (→ `init-ld.xz`) | from APK | 3528 | `1e95b09a…4162` |
| `s1kiosk_radio.rc` | repo, MIT | — | `8432ffd6…ab6c` |
| `s1kiosk_radio.sh` | repo, MIT | — | `6953e0c4…6acf` |
| `s1kiosk_firstboot.rc` | repo, MIT | — | `88a09be9…cef0` |
| `s1kiosk_firstboot.sh` | repo, MIT | — | `6a7248f2…1952` |
| `otbr-agent` (armeabi-v7a, static) | otbr/OpenThread, **BSD-3** (CPC: Silabs MSLA) | 1392280 | `ca137419…f5af` |
| `ot-ctl` | same | 366700 | `cbbfb98b…ab6a` |
| kiosk APK (`sh.edm.s1kiosk`) | repo, MIT | 103484080 | build per `app/`; pin at release |
| 5× openWakeWord `.tflite` (v0.5.1) | models **CC-BY-NC-SA-4.0**, code Apache-2.0 | — | verify `TFL3` magic + pin at build |
| `magiskboot.wasm` (our Rust shim, §4.4) | GPL-3.0 (links Magisk crates' pinned deps) | ~295 KB | pin at build |

The three Magisk payload `.so` files can be extracted at build time from the pinned APK (the APK
itself is re-hosted with its GPLv3 licence and a source link, as the user approved). `magiskboot.so`
is **not** shipped — its compressors are reimplemented as `magiskboot.wasm`; the cpio/boot-img logic
is reimplemented in TS. Licence pages: GPL-2.0 (U-Boot + our patches + build instructions),
GPL-3.0 (Magisk + our wasm shim + source link), BSD-3 (otbr/OpenThread + mbedTLS Apache-2.0 +
Silabs MSLA notice for CPC), MIT (repo scripts, kiosk app, fastboot.js, ya-webadb),
CC-BY-NC-SA-4.0 (wake-word models).

---

## 10. Test plan

**Hardware-free (CI-able), the core of correctness:**
1. **Boot-patch golden test (the headline).** Given the committed stock `boot.img`
   (sha256 `29f329f0…`) and a **fixed test adb key**, the browser patcher must output a boot image
   whose sha256 equals the Docker output for the same key. With the reference key the target is
   `d1a4925f…` (reproduced byte-exact today in a Python model of the container steps). CI runs the
   real `tools/root/patch-boot-magisk.sh` in Docker to produce the golden, then diffs.
2. **Compressor golden tests.** `magiskboot.wasm` `xz_preset6_crc32_bcj(ArmThumb)` must reproduce
   `magisk.xz`, `init-ld.xz`, `.backup/init.xz`; `gzip_best` must reproduce the patched ramdisk.
   (All four verified today both natively and as wasm32-wasip1 under Node.) Run the wasm under
   headless Chrome, not just Node, to catch wasm-in-browser differences. **[core verified; browser
   run pending]**
3. **cpio canonical-dump test** — load golden stock cpio, apply the §4.2 ops, dump, compare to
   golden patched cpio (verified in Python).
4. **Re-patch / restore test** — feed the patcher the *patched* image; it must `restore` then
   re-patch to the identical `d1a4925f…` (verified via the real CLI today).
5. **vbmeta flags test** — flags-0 `vbmeta` → flags-3 must equal the known-good `00885746…`
   (hash present in backups).
6. **adb public-key format test** — a known PKCS#8 key → the exact `authorized_keys` line Tango /
   the Android mincrypt format produce (unit test against `adbGeneratePublicKey`).
7. **FEL framing unit tests** — AWUC/AWUS byte layout, FEL request layout, eGON checksum, against
   vectors derived from `fel_lib.c`/`fel.c`.

**On real hardware (requires a panel; out of scope for this session, mirrors the CLI factory sim):**
8. FEL enumerate + `ver` gate; SPL `eGON.FEL`; U-Boot re-enumeration to `1f3a:1010`; getvar gate.
9. Backup → patch → vbmeta → flash boot → read-back `cmp` → reboot → adb authorized with no screen
   tap (the CLI hit 21 s, `docs/research/root.md`), Magisk + helper live.
10. Full provisioning (`tools/install.sh` parity): app installed, HOME set, debloat, models pushed,
    kiosk shows the HA URL; ESPHome device appears.
11. Per-OS: Windows (Zadig for FEL; fastboot/adb driver — §11), Linux (udev), macOS (no setup);
    Chrome/Edge/Brave desktop.

---

## 11. Per-OS onboarding

**Windows [verified where cited]:**
- **FEL `1f3a:efe8` needs WinUSB.** WebUSB on Windows can only open a device whose interface is bound
  to WinUSB.sys ([Chrome: Building a device for WebUSB]). The Allwinner BROM ships **no** MS OS
  descriptors, so Windows won't auto-bind WinUSB → the user must run **Zadig** once and replace the
  driver for `1f3a:efe8` with **WinUSB** (not libusbK/libusb0 — Chromium's Windows backend talks to
  WinUSB.sys; libusbK is **[assumed unsupported]**, consistent with the Chrome docs naming only
  WinUSB). Show Zadig instructions + screenshot on Windows for the FEL step only.
- **Our fastboot gadget `1f3a:1010` should bind WinUSB automatically.** U-Boot's fastboot function
  advertises **MS OS 1.0 descriptors** with compatible-ID `"WINUSB"` and a `DeviceInterfaceGUID`
  extended property (`f_fastboot.c:49-66`), enabled via `c->cdev->use_os_string = 1` and the
  `b_vendor_code = 0x40` / `MSFT100` string machinery in composite.c
  (`f_fastboot.c:219-230`, `composite.c:26,653-663,1161-1205,1416-1421`). **But** this requires
  `CONFIG_USB_GADGET_OS_DESCRIPTORS=y` — the OS-descriptor vendor request is gated on it
  (`composite.c:1161`). **Our build does not set it** (`.config` has no `USB_GADGET_OS_DESCRIPTORS`;
  it's set only on some TI/Toradex configs). **So on Windows the fastboot device will *not*
  auto-bind WinUSB and the user would need Zadig for `1f3a:1010` too.** **Recommendation
  [assumed→action]:** add `CONFIG_USB_GADGET_OS_DESCRIPTORS=y` to `aqara-s1-plus_defconfig` and
  rebuild, so Windows auto-loads WinUSB for the gadget (it already carries the descriptors in code).
  This is a one-line defconfig change to evaluate — it is **not** done here (don't modify scripts),
  but it's the cleanest fix and is called out as the top Windows risk. Without it, document a second
  Zadig step for `1f3a:1010`.
- **adb interface** (after first boot): the standard "Android ADB Interface"/WinUSB story. Stock
  Aqara adb usually works with Google's USB driver; **[assumed]** until the factory panel's adb
  VID:PID + descriptors are captured. Edge/Chrome need no extra setup beyond the driver bind.

**Linux:** udev rules so the user (not root) can open the devices, mirroring sunxi-tools/android:
```
# FEL
SUBSYSTEM=="usb", ATTR{idVendor}=="1f3a", ATTR{idProduct}=="efe8", MODE="0660", TAG+="uaccess"
# our U-Boot fastboot gadget
SUBSYSTEM=="usb", ATTR{idVendor}=="1f3a", ATTR{idProduct}=="1010", MODE="0660", TAG+="uaccess"
# adb (after first boot) — fill in the panel's real VID:PID once captured
```
`TAG+="uaccess"` gives the logged-in seat access without a plugdev group on modern systemd. Also
ensure no kernel driver claims the interface (sunxi-fel detaches it; `fel_lib.c:676-681`); WebUSB/
Chrome handles detach itself on Linux. **[verified rule shape; adb PID pending]**

**macOS:** nothing to install — WebUSB opens both FEL and the gadget directly. (This is the dev
machine; the CLI flow runs here.) **[assumed: macOS WebUSB for these PIDs not tested, but no driver
is needed in general]**

**Browsers:** Chrome/Edge/Brave **desktop** support WebUSB in a secure context
([MDN: Navigator.usb]). **Brave** requires *Settings → Privacy → USB devices* enabled (it's a site
permission, on by default for asking) ([brave-browser#4669]). **No WebUSB on Android/iOS browsers**
(Brave Android lacks `navigator.usb`, [brave-browser#31501]; Safari has no WebUSB) — the installer
is desktop-only; detect and tell mobile users to use a computer. Firefox has no WebUSB. Require
`navigator.usb` at load and show a browser-support card if missing.

Sources: [Chrome: Building a device for WebUSB](https://developer.chrome.com/docs/capabilities/build-for-webusb),
[MDN: Navigator.usb](https://developer.mozilla.org/en-US/docs/Web/API/Navigator/usb),
[brave-browser#4669](https://github.com/brave/brave-browser/issues/4669),
[brave-browser#31501](https://github.com/brave/brave-browser/issues/31501).

---

## 12. Risks & open questions

1. **Compression byte-exactness is the whole ballgame.** Mitigated by compiling magiskboot's exact
   pinned Rust crates (`flate2 1.1.10`+`zlib-rs 0.6.8`, `lzma-rust2 0.21.0`) to wasm — **verified
   byte-exact today** natively *and* as wasm32-wasip1. **Remaining [assumed]:** the same wasm run
   inside a real browser (not Node WASI) is byte-identical, and future Magisk releases keep these
   crate versions. Pin Magisk at v31.1.
2. **`wasm32-unknown-unknown` vs `wasip1`.** Verified under `wasm32-wasip1`. For the browser prefer a
   no-WASI build (`wasm32-unknown-unknown`, memory in/out) to avoid a WASI shim; must re-verify its
   bytes match (expected identical — compression is pure compute). **[assumed]**
3. **Windows WinUSB for the fastboot gadget** — the descriptors exist in code but
   `CONFIG_USB_GADGET_OS_DESCRIPTORS` is off in our build (§11). Either enable it (one defconfig
   line, recommended) or ship a second Zadig step. **[verified gap]**
4. **adb device identity on a factory panel** — VID:PID + whether Windows binds it without a custom
   INF is **[assumed]**; capture on a real panel. Tango's interface filter (`ff/42/1`) should match
   regardless.
5. **FEL re-enumeration timing in the browser** — WebUSB disconnect/connect across the SPL→U-Boot→
   gadget transitions, and whether a fresh `requestDevice` is needed each time or getDevices()
   remembers permission per-PID. **[assumed]**
6. **64 KiB chunk timing under WebUSB** — the CLI needed 64 KiB chunks against libusb's 10 s timeout;
   WebUSB transfer sizing/latency may differ. Start at 64 KiB (matches `fel-boot.sh`). **[assumed]**
7. **103 MB kiosk APK over WebUSB adb** — install via Tango streaming; memory/time on low-end hosts.
   **[assumed]**
8. **Magisk GPLv3 re-hosting** — approved by the user; ensure the licence + source-link pages ship
   and the APK hash is pinned (`89cefac0…`).
9. **`os_version`/`id` SHA1 vs SHA256** — our image uses SHA1 (`id` tail zero). The repack picks SHA1
   accordingly (`bootimg.cpp:346-353,873`). Verified, but re-check if a future stock image sets the
   SHA256 flag. **[verified for current firmware]**
