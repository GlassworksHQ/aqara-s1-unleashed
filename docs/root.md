# Persistent root: Magisk + the S1 Kiosk root helper

**Status (2026-11-09): working on the reference panel.**
- `boot` is Magisk v31.1 plus our `overlay.d/s1kiosk_radio.rc`.
- It was flashed from the FEL U-Boot with `fastboot flash boot`.
- After a cold reboot with no manual steps:
  - Magisk is live;
  - the root helper (`init.svc.s1kiosk_radio`) runs and reports `ok off`;
  - Aqara's radio and cloud services stay stopped;
  - the kiosk and Home Assistant (ESPHome) are unaffected.

## Why Magisk, and why this shape

- **What the stock image allows:**
  - `buildvariant=user` means no `adb root`.
  - Aqara's `/system/xbin/su` (`-rwsr-x--- root shell`) only works from the UART console; under adbd
    it fails with `setgid failed`, because adbd drops those capabilities.
  - The boot ramdisk is first-stage only (two-stage init), so a plain `.rc` there is never read by
    the real init.
  - Changing `vendor`/`system` would mean rebuilding `super`.
- **What Magisk changes:** only `boot`. Its `overlay.d/*.rc` files are injected into the
  second-stage init, which is exactly what the helper's init service needs.
- **What we deliberately don't do:**
  - grant `su` to the app or to the adb shell. The app only sets properties
    (`persist.s1kiosk.radio_mode`, `ctl.restart s1kiosk_radio`), and the init service does the
    root work;
  - ship Magisk. It is GPLv3, so `tools/root/patch-boot-magisk.sh` downloads the official release
    and checks the SHA-256 GitHub publishes for it.
- **AVB is not a problem on this firmware:**
  - the panel already runs a `vbmeta` with flags = 3 (hashtree and verification disabled);
  - it boots normally while reporting `vbmeta.device_state=locked`;
  - so stock U-Boot does not enforce AVB, and a modified `boot` boots.

## Steps (reference panel)

1. **Back up, and verify against the panel:** `boot.img` must match
   `sha256sum /dev/block/by-name/boot` byte for byte.
2. **Patch:** `tools/root/patch-boot-magisk.sh stock-boot.img s1-boot.img`.
   - Runs on the panel over adb as the shell user; no root needed.
   - Uses `KEEPVERITY=true KEEPFORCEENCRYPT=true`, so Magisk changes nothing it doesn't need to.
   - Adds `overlay.d/s1kiosk_radio.rc`.
   - The output keeps the partition size (32 MiB).
3. **Flash:**
   1. Enter FEL (hold the PCB button while plugging in).
   2. `NO_BL31=1 tools/fel/fel-boot.sh`.
   3. `fastboot flash boot s1-boot.img`.
   4. `fastboot reboot`.
4. **Install the helper script once,** as root, to `/data/s1kiosk/radio/` (root 0700). On the
   reference panel the UART console was used: `/system/xbin/su 0 …`. Plain `su` is now Magisk's,
   and it denies the console because there is no policy.

**Undo:** `fastboot flash boot <stock boot.img>` through the same FEL path.

## Next
- **Make the boot image self-contained** for sealed panels, so step 4 needs no UART. Ship the helper
  script in `overlay.d/sbin`, reachable at `${MAGISKTMP}` from the `.rc`, and let it create
  `/data/s1kiosk/radio` itself.
- **Fresh panels have no adb,** so patching has to move to the host (`magiskboot` for arm64 Linux in
  Docker), and the stock `boot` has to be read over USB from the FEL U-Boot (`ums` or a fastboot
  upload).
- Build `otbr-agent`/`ot-ctl` (`tools/radio/build-otbr.sh`) and install them for the Thread modes.
