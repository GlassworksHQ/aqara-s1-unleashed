# Findings

Everything below is from the stock firmware on a retail unit, via the debug UART console.

## Root

```
console:/ $ su 0 id
uid=0(root) gid=0(root) groups=0(root),1007(log),3009(readproc) context=u:r:su:s0
```

- AOSP `su` ships at `/system/xbin/su`. **Syntax is `su 0 <cmd>`** (not `su -c`).
- `ro.build.type = user`, `ro.secure = 1`, `ro.debuggable = 0` — but `su` is present anyway (engineering/`test-keys` build).

## SELinux

```
console:/ $ cat /sys/fs/selinux/enforce
0        # permissive
```

AVC denials are logged but not blocked. This removes a major obstacle to modifying the running system.

## Secure boot / verified boot

From the boot log:

```
[01.165]secure enable bit: 0
[01.329]tool did not set rotpk burn flag, skip rotpk burn
[01.342]no secure os for keybox operation
```

- **Secure boot disabled**, ROTPK not fused → the BROM/U-Boot will run unsigned bootloaders and images.
- **FEL is unauthenticated** — `sunxi-fel` talks to the BROM with no signature challenge.

### AVB

U-Boot parses `vbmeta` for `boot`, `system`, `vendor` (`magic = AVB0`, `avbtool 1.1.0`, `flag = 0`). AVB is present but, with secure boot off, it is **not hardware-enforced** — it can be disabled via the vbmeta "verification disabled" flag, or the chain re-signed with your own key, and flashed over FEL/fastboot.

## Bootloader

```
U-Boot 2018.05 (Dec 02 2025 ...) Allwinner Technology
```

- `Hit any key to stop autoboot: 0` — interruptible to a U-Boot shell.
- `usb burn from boot` / `do_burn_from_boot usb` — U-Boot probes USB for fastboot/burn mode at boot.
- `device_unlock` and `fastboot_status_flag` live in Allwinner "secure storage" (found empty on this unit).

## Storage / partitions

- eMMC: **MMC 5.1, ~7456 MB, 8-bit, HS400**, on controller **mmc 2** → `/dev/mmcblk0*`.
- `boot` partition: start `0x2a000`, size `0x10000` (512-byte sectors).
- `pstore` → `/dev/mmcblk0p17`.
- `dtbo` partition present (start `0x4c2000`). `userdata` is the usual large final partition.
- `/dev/block/by-name/`: `bootloader` (p1), `env` (p2), `boot` (p3), `super` (p4, dynamic system/vendor/product), `misc` (p5), `recovery` (p6), `cache` (p7), `metadata` (p11), `private` (p12), `frp` (p13), `media_data` (p16), `pstore` (p17), `lumi` (p18), `UDISK` = `/data` (p19); plus `dtbo`, `vbmeta*`, `empty`.

## USB / ADB

- `sys.usb.config = mtp`, `persist.sys.usb.config = none` by default (why plain `adb` isn't exposed on USB out of the box).
- `ro.adb.secure = 1` — adbd requires an authorized key. With root you can drop your host key into `/data/misc/adb/adb_keys` and enable TCP adb (`service.adb.tcp.port`), then `adb connect`.

## Implications for the project

Because secure boot is off, FEL is open, SELinux is permissive, and root is available, we can:

1. Boot our own DRAM-correct U-Boot entirely in **RAM** via FEL (no eMMC writes).
2. Use that U-Boot's **fastboot** to `fastboot boot` a one-time provisioning image.
3. Provision Home Assistant as the kiosk launcher from that image — persisting in `/data` like a normal install, with nothing permanently reflashed.

> Device-unique values (serial, MAC, SID, vbmeta hash) are intentionally omitted from this repo.

## Bootloader USB entry points (stock U-Boot 2018.05, read from the binary)

The stock boot package (`sunxi-package` at eMMC sector 32800) and `env` partition were dumped read-only
and are backed up outside the repo. Strings in the binary show these USB paths:

- **`usb burn from boot`** runs on every boot (`delay time 0`, then `usb prepare/sof/probe/setup ok`,
  `exit usb burn from boot`, `next work 1` = `SUNXI_UPDATE_NEXT_ACTION_NULL`). Its other exit path is
  `out of usb burn from boot: not need burn key`. It appears to be a **key-burning** hook (PC tool burns
  MAC/serials into secure storage) rather than a general entry point. Protocol strings: `sunxi pburn`,
  `SUNXI_USB_PBURN_EXIT`, `pburn [mode]`.
- **efex** (Allwinner's in-U-Boot USB flashing mode, as used by PhoenixSuit / LiveSuit): reached via the
  `misc` partition (`[boot efex] set misc : boot efex` → `## jump to efex ...` → `run usb efex`), magic
  `AWUSBFEX`. Writing a `boot-efex` bootloader message into `misc` requires root on a running panel.
- **fastboot**: `boot_fastboot=fastboot` in env; `FAIL:secure mode,fastboot limited used` only in secure
  mode (off here).
- Also present: `[boot recovery]`, `[boot factory] ... wipe data`, `[ir sysrecovery]` misc commands.

The environment has no burn-delay or USB-update variable, so `delay time` is set elsewhere
(device-tree / sys_config or secure storage). Under investigation.

### Verdict: no-button USB entry is not practical (source-confirmed)

Allwinner BSP U-Boot 2018.05 source (`LubanCat/u-boot-allwinner` v2018.07, the shared BSP) was read
to settle this:

- The panel opens a USB gadget at every power-on because **`burn_key=1`** is set (vendor enabled
  key-burning) and secure storage has no `key_burned_flag`. VID/PID `1f3a:efe8`, product
  "USB Mass Storage", USB Mass-Storage Bulk-Only transport.
- It is a **key-burning tool**, not a general entry point. Vendor SCSI opcodes `0xf0` (burn/read/erase
  keys to secure storage; sub-9 reboot, sub-10 erase misc → Android recovery), `0xf3` (raw read/write of
  the `private` partition only). **No fastboot, no arbitrary partition flashing.** FEL (`0xf8` sub-2)
  only exists when `auto_fel=1` in the device tree — it is **not** set here.
- The host wait window is **~300 ms to enumerate + ~400 ms for the first command**, and `delay time 0`
  (the burn path) is a compile-time constant, not configurable without rebuilding U-Boot.
- **macOS (and any host without custom software) cannot drive it**: it enumerates the gadget as a USB
  disk and sends only standard SCSI, never the `0xf0` handshake, so U-Boot prints
  `have no handshake` → `exit usb burn from boot` → normal boot (exactly our log).
- **No open-source tool speaks this protocol.** `sunxi-fel`/`xfel` speak BROM-FEL, a different protocol
  reachable only via the FEL button / unbootable media.

Other no-button paths (`reboot efex`, RTC FEL flag, `boot-recovery`/`boot-efex` in `misc`, `auto_fel=1`)
all need **on-device root or a DT reflash first** — which on a sealed panel needs the FEL button anyway.

**Conclusion: the FEL button (hold PCB button + power) stays the entry point for a fresh panel.** No
soldering, no Aqara app/account — but the back cover must be opened once. A button-free USB installer
is not feasible on this firmware without custom host software for a mode that still can't flash the
system.
