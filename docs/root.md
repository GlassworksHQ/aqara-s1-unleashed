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

## Steps (no UART, no adb)

1. **Enter FEL and boot our U-Boot:** `NO_BL31=1 tools/fel/fel-boot.sh`. It starts fastboot on its own.
2. **Back up over USB:** `tools/install/fb-read-partition.sh boot stock-boot.img`, and the same for
   `vbmeta` and the other small partitions.
   - This uses U-Boot `oem run` plus our fastboot `upload` (patch 0002).
   - It reads 32 MiB in about 1.6 s.
   - On the reference panel the read-back was byte-identical to a known image.
3. **Patch on the host:** `tools/root/patch-boot-magisk.sh stock-boot.img s1-boot.img`.
   - Runs in Docker (arm64 Linux): Magisk's own `boot_patch.sh` with the arm64 `magiskboot`, and the
     panel's armeabi-v7a `magiskinit`/`magisk`/`init-ld` as payload.
   - Flags: `KEEPVERITY=true KEEPFORCEENCRYPT=true PREINITDEVICE=cache`. That is exactly what
     Magisk computes on the panel, and the config matches an on-device patch.
   - **Bundles the helper:** `overlay.d/s1kiosk_radio.rc`, plus `s1kiosk_radio.sh`, `otbr-agent`
     and `ot-ctl` in `overlay.d/sbin`. At runtime those are `${MAGISKTMP}` = `/sbin`.
4. **Flash and verify:**
   1. `fastboot flash boot s1-boot.img`.
   2. Read `boot` back with step 2's tool and compare hashes.
   3. `fastboot reboot`.

On first boot the helper creates `/data/s1kiosk/radio` itself (root 0700), so nothing else needs
installing. Verified on the reference panel on 2026-11-09, after deleting `/data/s1kiosk` to
simulate a fresh panel:
- the helper runs from `/sbin` and reports `ok off`;
- switching `select.s1_panel_radio_mode` in HA to *Thread border router* started the bundled
  `otbr-agent` on `wpan0`, and `ot-ctl` answers;
- switching back to *Off* stopped it.

Binaries in `/data/s1kiosk/radio` (e.g. a newer `otbr-agent`, or a `zigbee-router`) override the
bundled ones.

**Undo:** `fastboot flash boot stock-boot.img` through the same FEL path.

**Root shell for maintenance:**
- No `su` grants exist by default.
- For a debugging session, a time-limited Magisk policy for the adb shell (uid 2000) can be added
  with an `until` timestamp, so it expires by itself:
  `magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(2000,2,<epoch>,1,0)"`.
- Revoke it with `DELETE FROM policies WHERE uid=2000`.

## Next
- **Enable adb on factory panels.** Ship the installing host's adb public key in the boot image,
  and have the helper write `/data/misc/adb/adb_keys` and turn adb on at first boot. The kiosk app
  and `tools/install.sh` can then follow over adb.
- **Write one end-to-end `fel-install` script:** FEL → backup → patch → flash → verify → reboot.
- **vbmeta:** the reference panel runs `vbmeta` with flags 3. A factory panel (flags 0) may need
  `fastboot --disable-verity --disable-verification flash vbmeta <its own vbmeta>`. To be tested.
