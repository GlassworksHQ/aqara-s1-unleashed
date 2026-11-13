# Breaking into a device: the playbook

How we got from a sealed retail panel to an open one, written as a method you can use on the next
device. The first part is general. The second lists what the S1 Plus taught us about Allwinner
FEL, U-Boot, fastboot and Android 10. The story is in [README.md](README.md); the evidence is in the
notes it links.

Write things down as you go (see [Document everything](#document-everything)). A failed attempt you
recorded is worth more to the next person than a success nobody can repeat.

## The order of work

Each step makes the next one safer. Don't skip ahead to flashing.

1. **Look before you open it.** Check its USB descriptors when plugged in (`ioreg -p IOUSB` /
   `lsusb -v`), whether adb answers, open ports (`nmap`), the hostnames it calls, OTA URLs, and any
   FCC filings or teardown photos. Write down the model, firmware version and date.
2. **Get a console.** Find the debug UART: three pads near the SoC, with TX idling at 3.3 V. Use a
   3.3 V adapter, never 5 V, and check it with a TX↔RX loopback before you connect it to the board.
   An ESP32-S3 works as the bridge ([`tools/esp32-uart-bridge`](../../tools/esp32-uart-bridge)).
   Opening the serial port can toggle DTR/RTS and reset the bridge, so scripts should clear both.
3. **Capture and annotate the stock boot log**, from power-on to the Android shell
   ([boot-log.md](../../frames/aqara-s1-plus/research/boot-log.md)). It answers most of the early questions:
   - Is secure boot on, and is a root-of-trust key fused? (`secure enable bit`, `rotpk`)
   - Is AVB present, and is it enforced? (`magic = AVB0`, vbmeta flags)
   - Which bootloader and version? Is autoboot interruptible?
   - The DRAM type, clock, voltage and training parameters. You will need them for your own
     bootloader.
   - The PMIC, the eMMC controller and the partition table.
4. **Read the running system.** From the console shell: `id`, whether `su` exists and its syntax,
   `getenforce`, `ro.build.type`/`ro.secure`/`ro.debuggable`, `ls -l /dev/block/by-name`, the USB and
   adb properties, `ps -A`, `getprop`. Record what each one rules in or out ([findings.md](../../frames/aqara-s1-plus/research/findings.md)).
5. **Back up everything, read-only, before you write anything.** Every partition you might ever
   touch, plus the bootloader package and `env`. Hash them, keep them outside the repo, and keep two
   copies. On the S1, boot0 was first read over the UART with `dd` and `od`, before any USB path
   existed.
6. **Find the entry point the user will use.** Prefer a mask-ROM recovery mode: it can't be erased,
   so the device can't be bricked for good. Allwinner has FEL, Rockchip MaskROM, MediaTek BROM and
   Qualcomm EDL (usually locked to signed programmers). Then **rule out every easier path and write
   down why**: vendor burn modes, `misc` boot commands, RTC flags, button-free USB. On the S1 that
   took a day and saved every later contributor from repeating it
   ([findings.md: USB entry points](../../frames/aqara-s1-plus/research/findings.md#bootloader-usb-entry-points-stock-u-boot-201805-read-from-the-binary)).
7. **Run your own bootloader from RAM.** Nothing is written, so a mistake costs a power cycle. Get
   it to a prompt, then to `mmc info`, then to a USB gadget (fastboot).
8. **Add a read path before the write path.** Read partitions back over USB and compare them with
   the UART-era backups. Only then flash, and verify every write by reading it back.
9. **Make the smallest persistent change.** On Android that usually means changing only `boot`
   (Magisk) and leaving `system`, `vendor` and `super` alone. Keep the vendor's image working, and
   keep the undo to a single flash.
10. **Automate only what is repeatable by hand,** and then test it from factory state: wipe the
    device back to stock (adb off, keys cleared, stock `boot`, factory `vbmeta`) and run the
    installer with no manual steps. Run it twice and check that the output is identical.

## Working habits

- **Change one variable at a time, and bisect.** The S1's DRAM bug had six candidate differences.
  Matching all six made it boot. A bisect showed that only one mattered, and that turned a
  six-change patch into one upstream fix.
- **A memory test that passes proves little.** Write-then-read tests from the host passed on RAM
  that corrupted ~300 words per MiB under real code. Test with the access patterns real code uses:
  copies, bursts, and code executing from DRAM.
- **Diff against the vendor's working state.** Most bring-up bugs are "we program it differently from
  the vendor". Read the vendor's registers from its running OS (on Allwinner,
  `/sys/class/sunxi_dump/dump`) and yours over the recovery mode, and compare them.
- **Get a signal out early.** When nothing prints, put raw UART writes at each stage. The TF-A debug
  patch in [`tools/fel/tfa`](../../tools/fel/tfa/README.md) does this.
- **Prove a protocol against the vendor's own tool.** For the wall base, we wrote an emulator from the
  disassembly and ran Aqara's `bracket_cli` against it on the panel ([base-protocol.md](../../frames/aqara-s1-plus/research/base-protocol.md)).
  For the radio, we proved the flash path by reflashing the identical stock image first.
- **Vendor binaries:** these are mostly 32-bit ARM and stripped. `strings`, `llvm-objdump -d` and
  radare2 go a long way. Record the binary's name, version and the offsets you read, never the bytes.
- **Name the vendor behaviour that fights you.** "The screen turns off at night" turned out to be a
  hidden DND rule in Aqara's system manager ([screen-policy.md](../../frames/aqara-s1-plus/research/screen-policy.md)). "Wi-Fi drops"
  turned out to be a watchdog tied to the cloud daemon ([debloat.md](../../frames/aqara-s1-plus/research/debloat.md)). Find the
  component before you work around the symptom.

## What the S1 Plus taught us

### Allwinner A133 and FEL

- FEL: hold the PCB button while plugging in USB-C. The device is `1f3a:efe8`, SoC id `0x1855`. FEL
  on this unit is unauthenticated, because secure boot is off and no ROTPK is fused.
- The stock U-Boot's USB gadget on every boot (`usb burn from boot`) is a key-burning mode, with a
  ~700 ms window and no flashing. It is not a way in.
- `sunxi-fel spl` runs the SPL (DRAM init) and returns to FEL. Then upload U-Boot in **64 KiB
  chunks** and `reset64` into it. `sunxi-fel uboot` times out because the CPU, and so the boot ROM's
  USB, runs at only 408 MHz.
- There is no SPL driver for the AXP806 PMIC, so `SUNXI_NO_PMIC` applies, and the CPU must stay at
  `SYS_CLK_FREQ=408000000`. Mainline would otherwise clock it to 1008 MHz at the power-on voltage.
- U-Boot runs at EL3 without TF-A (`NO_BL31=1`). That is enough for fastboot and eMMC, though Linux
  SMP would need PSCI.

### DRAM (DDR4)

- The parameters come from the stock BOOT0 log and header: DDR4, 792 MHz, 1200 mV, `tpr11`–`tpr13`
  ([boot-log.md](../../frames/aqara-s1-plus/research/boot-log.md)).
- Mainline's A133 DDR4 code programs **MR0 = `0x840`, but boot0 programs `0x520`**. The RAM then
  runs a different CAS latency from the one the controller's timings assume. Read→write turnarounds
  corrupt data, while simple tests still pass. Patch 0001 fixes it, and the MR0 change was sent
  upstream on 2026-11-11 ([fel.md](../../frames/aqara-s1-plus/research/fel.md#what-had-to-be-fixed)).
- The sunxi-fel SRAM burst test does not catch this. You need U-Boot running from DRAM, copying with
  `cp.q` and checking with `cmp.q`.

### U-Boot and fastboot

- Build U-Boot in an arm64 Linux container. Its host tools don't build cleanly on macOS.
- Set `ENV_IS_NOWHERE`, so `saveenv` can never overwrite the Android `env` partition.
- Stock fastboot can only write. Patch 0002 adds `upload` (`fastboot get_staged`). Combined with
  `oem run:` (`part start`/`part size`, `mmc read`, `setexpr fastboot_upload_size`), it reads
  partitions at about 20 MB/s. Each `oem run:` must fit in 64 bytes.
- The buffer is 512 MiB, which caps a single read. eMMC numbering differs between U-Boot (`mmc 1`)
  and the BSP (`mmc 2`).
- Unset `ANDROID_SERIAL` before running `fastboot`, because fastboot reads it too.

### Android 10 on the panel

- Aqara's `su` works only from the UART console (`su 0 <cmd>`, not `su -c`). Under adbd it fails,
  because adbd drops the capabilities it needs. `buildvariant=user` means no `adb root`.
- Init has two stages. A `.rc` file in the boot ramdisk is never read by the real init. Magisk's
  `overlay.d/*.rc` is injected into the second stage, which is why we use it.
- The panel already runs `vbmeta` with flags 3 (verification disabled) while it reports `locked`.
  Stock U-Boot doesn't enforce AVB. The flags are a big-endian u32 at offset 120.
- Out of the box, adb is off (`persist.sys.usb.config=none`) and needs a key
  (`ro.adb.secure=1`). The installer bakes the host's public key into the boot image, and a
  one-shot service authorises it on first boot, so the user never has to tap the screen.
- SELinux is permissive. That makes root easier, and it also means any app can start init services.
  Design every root service for that ([root.md](../../frames/aqara-s1-plus/research/root.md)).
- The system WebView is Chromium 74, so the kiosk ships GeckoView. Never change `wm density`.
- Panels can ship in the wrong time zone (`Africa/Algiers`) and with a hidden 22:00–07:00
  screen-off rule. Check both on any Aqara device.

## Document everything

For every session on the hardware, record the following in the relevant note, or in a new one from
[TEMPLATE.md](TEMPLATE.md):

- **Date, device, firmware**, and what state the device was left in.
- **Exact commands and their output,** redacted: no serials, MACs, EUI64s, keys, PAN IDs or tokens.
- **The confidence of each claim:** *verified* (ran it, with the date), *read* (from source or a
  binary, with the file and the line or offset), or *untested*.
- **Dead ends,** with the reason each one failed.
- **The scripts you used.** Make them reusable and put them in `tools/`, with a header saying what
  they touch (RAM only, read-only, writes eMMC). Hardcoded serials, IPs and home paths come out
  first.
- **Upstream state:** patches sent, with their Message-ID or PR link, and what to drop once they land.

## Checklist for a new device (frame)

Fill this in as the new frame's `hardware.md` before writing frame code. Each blank line is a task.

| Question | S1 Plus answer |
|---|---|
| SoC, RAM, storage, PMIC | A133 (`0x1855`), 2 GiB DDR4, 8 GB eMMC, AXP806 |
| Recovery mode, and how to enter it | FEL, the PCB button plus USB-C |
| Console | 3-pin UART, 3.3 V, 115200 8N1 |
| Secure boot / AVB enforced? | Off / no |
| Root available? How? | Console `su`; persistent root via Magisk on `boot` |
| Android version, ABI, `build.type` | 10, `armeabi-v7a` userspace, `user` with `test-keys` |
| Display size, density | 1440×720, 1.5 |
| System WebView version | Chromium 74 (too old for HA) |
| Radios (chip, firmware, bus) | EFR32MG21, multi-PAN RCP over CPC on SPI |
| Sensors | VI5300 time-of-flight, MK6212 ambient light |
| Vendor daemons that fight a kiosk | `ha_master` (cloud), system manager DND rule, Wi-Fi watchdog |
| Peripherals | Wall base over `/dev/ttyS3` |
| Undo path | Flash the stock `boot` and `vbmeta` from backup |
