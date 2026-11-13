# Agent guide: FEL, U-Boot, the root side and the radio (`tools/`)

The repo-wide rules are in [`../AGENTS.md`](../AGENTS.md). This is the part of the repo that can
brick a panel, so read [`../docs/research/bringup.md`](../docs/research/bringup.md) before you
change the boot path.

## Map

| Path | What | Notes |
|---|---|---|
| `fel/` | `build-uboot.sh`, `fel-boot.sh`, the board defconfig and DTS, patches against mainline U-Boot | RAM only: nothing is written to eMMC |
| `fel/uboot/patches/0001` | A133 DDR4 controller setup matching boot0 (MR0 is the actual fix) | MR0 was sent upstream on 2026-11-11; drop that hunk once it lands |
| `fel/uboot/patches/0002` | fastboot `upload`, for reading partitions back (`fastboot get_staged`) | |
| `install/` | `fel-install.sh` (FEL → backup → patch → flash → verify), `fb-read-partition.sh`, the `--undo` path | Has off-device tests in `install/test/` |
| `root/` | Init services baked into `boot` via Magisk `overlay.d`: first boot, update, bundle, netwatch, branding; `s1kiosk_verify` (C) | Runs as root under permissive SELinux |
| `radio/` | Root helper `s1kiosk_radio.sh` (contract v4), the otbr/zigbeed builds, the bundle maker | Tests: `radio/test/run.sh` |
| `perf/` | `run.sh`: Clarity, the performance test and grade for any frame; `summary.py` exports a frame's measurements, `grade.ts` computes the letter from them ([docs/perf.md](../docs/perf.md)); `threadcpu.py`: per-thread CPU of the app over a window, read-only ([idle-cpu.md](../docs/research/idle-cpu.md)) | Opens test pages and restarts the app; points the dashboard setting at its fake Home Assistant for the graded part (and turns panes off and on with `--pane-ram`), then puts them back |
| `bracket/` | The wall base emulator and the proof script | |
| `esp32-uart-bridge/` | ESP32-S3 USB↔UART bridge for the debug console | |

## The root side

- Scripts run under **`/system/bin/sh` on Android 10, which is mksh with toybox**, not bash and not
  busybox. No arrays, no `[[ ]]`, no GNU flags. `tools/radio/test/run.sh` runs them under mksh in a
  32-bit ARM container. Add a case there for every behaviour you change.
- **Threat model:** SELinux is permissive, so any app may set properties or start init services.
  Services take no input except files owned by the kiosk's uid, which they check. They don't follow
  symlinks (`s1kiosk_verify --copy`), and they report through `sys.s1kiosk.*.status`. Don't add an
  input path that skips those checks. Read [`../docs/research/root.md`](../docs/research/root.md) first.
- Helper fixes reach panels through the **signed radio bundle**, not by reflashing `boot`. A change
  that needs a new boot image is a much bigger deal, because every user reruns the installer. Say
  which of the two your change needs.
- `.rc` and service changes must be tested on a real panel. Off-device tests can't see init.

## FEL and fastboot gotchas

- FEL: hold the PCB button while plugging in USB-C, and keep holding for about 10 s. The screen
  stays black. Check with `sunxi-fel ver` (SoC `0x1855`).
- Boot with `NO_BL31=1 tools/fel/fel-boot.sh <build-dir>`. **Don't use `sunxi-fel uboot`.** At
  408 MHz the boot ROM's USB is too slow for large transfers and they hit its 10 s timeout. The
  script uploads in 64 KiB chunks and starts the CPU with `reset64`.
- **`unset ANDROID_SERIAL` before running `fastboot`.** fastboot honours it, and our U-Boot's serial
  is not the panel's adb serial.
- Each `fastboot oem run:<cmd>` must fit in 64 bytes, hence the short env names (`$s`, `$n`).
- The fastboot buffer is 512 MiB at `0x50000000`, so `super` can't be read in one go. eMMC is
  `mmc 1` in our U-Boot, but `mmc 2` / `mmcblk0` in the stock BSP.
- `ENV_IS_NOWHERE` is set on purpose: `saveenv` must never write the panel's Android `env` partition.
- `build-uboot.sh` builds in an arm64 Ubuntu container, because U-Boot's host tools don't build
  cleanly on macOS.
- Unplug and replug without the button to get back to Android.

## Testing on the panel

- Keep backups outside the repo, with a `SHA256SUMS` file. `fel-install.sh --undo` refuses a backup
  whose `boot` is already patched, so always keep the first install's backup.
- Root on a dev panel: either a dev boot image (`DEV_SU_HOURS`), or a time-limited Magisk policy for
  uid 2000 (in `root.md`). Revoke it before the panel goes to anyone else.
- Put the panel back the way you found it (radio mode, app version, dev overrides), or say in the
  PR what you left behind.
