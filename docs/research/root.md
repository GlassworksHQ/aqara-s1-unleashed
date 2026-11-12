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

Since helper v4, binaries and a newer helper come from a **signed radio bundle** installed by the app
(below); copies left in `/data/s1kiosk/radio` are only used with a root-made `dev-override` marker.

**Undo:** `fastboot flash boot stock-boot.img` through the same FEL path.

**Root shell for maintenance:**
- No `su` grants exist by default.
- For a debugging session, a time-limited Magisk policy for the adb shell (uid 2000) can be added
  with an `until` timestamp, so it expires by itself:
  `magisk --sqlite "REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(2000,2,<epoch>,1,0)"`.
- Revoke it with `DELETE FROM policies WHERE uid=2000`.

## One command: `tools/install/fel-install.sh`

`tools/install/fel-install.sh [backup-dir]` does everything above, plus two more things.
- **Steps:**
  1. Waits for FEL and checks the SoC id.
  2. Boots our U-Boot. If our U-Boot is already in fastboot, it resumes from there instead.
  3. Checks the partition layout.
  4. Backs up `boot vbmeta vbmeta_system vbmeta_vendor dtbo misc recovery env bootloader`. `boot` is
     read twice and compared, and a `SHA256SUMS` file is written.
  5. Patches `boot`.
  6. Sets `vbmeta` flags to 3 if they aren't already, verified by read-back.
  7. Flashes `boot`, verifies it by read-back, then reboots.
  8. Waits for adb.
- **Extra in the boot image: first-boot provisioning.** `tools/root/s1kiosk_firstboot.{sh,rc}` is a
  one-shot root service. It authorizes the installing computer's adb key, which is baked into the
  image (`ADB_PUBKEY`, default `~/.android/adbkey.pub`), and turns USB adb on. It runs once and
  leaves a marker in `/data/s1kiosk`.
- **Extra in the boot image: the app update service.** `tools/root/s1kiosk_update.{sh,rc}` is a
  disabled one-shot root service. At boot init sets `sys.s1kiosk.update.version=1`, so the app knows
  it exists. Details under [App update service](#app-update-service).
- **Extra in the boot image: the radio bundle loader (helper v4).** `s1kiosk_bundle.{sh,rc}`,
  `s1kiosk_verify` (static ARM Ed25519 checker) and `s1kiosk_bundle.pub` (the project's public key).
  After this boot image, helper fixes, new radio stacks and radio firmware images come with app
  updates; see [Radio bundle service](#radio-bundle-service).

**Undo:** `tools/install/fel-install.sh --undo <backup-dir>`.
- It verifies the backup against its `SHA256SUMS` and refuses a `boot.img` that is already
  Magisk-patched. That happens with a re-run's backup; use the first install's backup instead.
- It flashes stock `boot` and `vbmeta` through FEL and verifies both by read-back.
- `/data` is not touched, so the kiosk app keeps working without root.

**Factory simulation on the reference panel (2026-11-09), passed:**
- Starting state: adb turned off, the adb key list emptied, stock `boot` and factory `vbmeta`
  (flags 0) flashed.
- `fel-install.sh` then:
  - backed up the stock partitions;
  - changed `vbmeta` flags 0 → 3. The result is byte-identical to the flags-3 `vbmeta` the panel had
    run on before;
  - flashed and verified `boot`.
- Without touching the screen, adb was back after the reboot: `s1kiosk_firstboot: authorized 1 new
  adb key(s)` at 21 s. Magisk and the helper were live.
- Re-running on an already-patched panel produces the same image as patching stock (deterministic
  output). Magisk restores the stock ramdisk first.

Gotcha: `fastboot` also honours `ANDROID_SERIAL`, and our U-Boot's fastboot serial is not the
panel's adb serial. The scripts keep `ANDROID_SERIAL` for adb only.

## App update service

`s1kiosk_update` installs app updates that the app downloaded and verified ([esphome.md](../esphome.md#app-updates)).

- **App side:**
  1. write `/data/data/sh.edm.s1kiosk/files/update/pending.apk`;
  2. `setprop sys.s1kiosk.update.status requested`;
  3. `setprop ctl.start s1kiosk_update`.
- **Service side:** `sys.s1kiosk.update.status` = `running: …`, then `ok: sh.edm.s1kiosk <old> -> <new>`
  or `error: <why>`.
- **Threat model.** SELinux is permissive, so other apps may be able to start init services and
  set properties. The service therefore takes no input except that one file, and checks:
  - the kiosk's uid from `pm list packages -U`, which must match the owner of `/data/data/sh.edm.s1kiosk`;
  - `files`, `files/update` and `pending.apk`: no symlinks, all owned by that uid (only the kiosk
    can write its private dir), and `pending.apk` is a single-link regular file of at most 256 MiB;
  - it copies the file into root-only `/data/s1kiosk/update` (with `s1kiosk_verify --copy` on boot
    images that have it, so a path swapped for a symlink or FIFO after the checks can't redirect or
    hang the copy) and installs the copy over stdin with
    `pm install -r -S <size>`, without `-d`. Android refuses another signing key for the same
    package, and refuses downgrades;
  - afterwards, the kiosk's versionCode must have gone up, and no other package may have changed.
    A package that appeared is uninstalled again, and the status says `refused`.
  - At worst, another app can make it install what the kiosk itself staged, or spoof the status
    property the app displays.
- **Afterwards:** the old process is gone. The kiosk is HOME and restarts on `MY_PACKAGE_REPLACED`;
  the service also runs `am start` of MainActivity if it isn't running after 3 s.
- **No service** (boot images from before it): the app falls back to a PackageInstaller session
  with an on-screen confirmation.

## Radio bundle service

`s1kiosk_bundle` installs the signed radio bundle the app ships ([radio.md](../radio.md#radio-bundles-helper-v4)).
Same shape and threat model as `s1kiosk_update`:

- **App side:** stage `/data/data/sh.edm.s1kiosk/files/radio/bundle-in/` (manifest, `manifest.sig`, the
  files), `setprop sys.s1kiosk.bundle.status requested`, `setprop ctl.start s1kiosk_bundle`. At boot init
  sets `sys.s1kiosk.bundle.loader=1`, so the app knows the boot image has it.
- **Service side:** finds the kiosk's uid (pm and the data dir owner must agree); copies manifest and
  signature into a fresh root-only `/data/s1kiosk/radio/bundle/stage`; checks the Ed25519 signature
  with the key **in the boot image** before reading anything else; refuses a version below the floor
  (`bundle/version`, the highest ever installed, and at least the installed one); copies only the
  listed files with `s1kiosk_verify --copy` (an `openat(O_NOFOLLOW)` walk, kiosk-owned all the way, the
  file opened `O_NONBLOCK` and checked on the fd: regular, one link, the listed size; exactly that many
  bytes copied) and checks the copy's SHA-256; records the new floor; renames the result into
  `bundle/current`; writes the app-readable `/data/s1kiosk-pub/radio-bundle`; restarts the radio helper. Status:
  `sys.s1kiosk.bundle.status` = `running: …` / `ok: radio bundle <v> installed …` / `error: …`.
- **At worst** another app makes it reinstall a bundle we signed (same or newer version) or spoofs the
  status property. Running its own root code would need the private key, which is not on the panel.
- The helper's loader re-checks `bundle/current` (`s1kiosk_bundle.sh --check`) at every start and falls
  back to the boot image's helper if it fails or the bundle's helper keeps exiting.

## Next
- Browser installer (WebUSB): built, in [`kiln/`](../../kiln/src/README.md); design in
  [web-installer.md](web-installer.md), user guide in [docs/install.md](../install.md).
- After `fel-install.sh`, `tools/install.sh` installs and configures the kiosk app over adb.
