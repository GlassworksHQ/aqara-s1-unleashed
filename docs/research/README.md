# How we got here

This is the engineering story behind S1 Unleashed: how a sealed Aqara wall panel became an open
Home Assistant device that installs from a browser. The front page says *what* it does. These
notes say *how* it works and *how we found out*, with the evidence.

You don't need any of this to use the project. It's here for people who want to check the
work, extend it, or port it to another panel.

<p align="center">
  <img src="../img/fel-button.jpg" alt="The S1 Plus board with the back cover removed; the FEL button is circled." width="80%">
</p>

**Contents**

1. [A panel that was more open than it looked](#1-a-panel-that-was-more-open-than-it-looked)
2. [Finding the way in: FEL](#2-finding-the-way-in-fel)
3. [Mainline U-Boot, and the DRAM bug](#3-mainline-u-boot-and-the-dram-bug)
4. [Persistent root with Magisk](#4-persistent-root-with-magisk)
5. [The installer, moved into the browser](#5-the-installer-moved-into-the-browser)
6. [Making it a good wall panel](#6-making-it-a-good-wall-panel)
7. [The radio](#7-the-radio)
8. [The wall base](#8-the-wall-base)
9. [The research notes](#the-research-notes)

---

## 1. A panel that was more open than it looked

The S1 Plus is an Allwinner A133 board running Android 10, with an Aqara launcher on top. The
first step was the panel's 3-pin debug UART: 3.3 V, 115200 8N1, reached through flat pads that
need a firm or soldered contact. An ESP32-S3 served as the USB-to-UART bridge
([`tools/esp32-uart-bridge`](../../tools/esp32-uart-bridge)).

The stock boot log, captured over that console, showed how little was locked down:

```
secure enable bit: 0                                  ← secure boot off
tool did not set rotpk burn flag, skip rotpk burn     ← no root-of-trust key fused
magic = AVB0 ... release tool = avbtool 1.1.0         ← verified boot present, not enforced
```

A root shell confirmed the rest: SELinux was permissive, an AOSP `su` shipped in
`/system/xbin`, and the build was `test-keys`. The chip would boot unsigned code. The
remaining question was how to reach it without a serial cable.

→ [hardware.md](../../frames/aqara-s1-plus/research/hardware.md) · [boot-log.md](../../frames/aqara-s1-plus/research/boot-log.md) · [findings.md](../../frames/aqara-s1-plus/research/findings.md)

## 2. Finding the way in: FEL

Every Allwinner SoC has **FEL**, a USB recovery mode in mask ROM. It can't be erased, and on
this panel it isn't authenticated. That makes the panel very hard to brick for good: you can
always get back into FEL.

Before settling on FEL, which needs a button on the board, we looked hard for a way in with no
button. The stock U-Boot opens a USB gadget on every boot (`usb burn from boot`). Reading the
BSP source showed it is a **key-burning** mode: it has a ~700 ms window, vendor SCSI opcodes,
and no way to flash partitions. Every other route (`efex` via `misc`, the RTC FEL flag,
`auto_fel`) needs root on the panel first. So the PCB button became the one physical step:
open the back cover, hold the button, plug in USB-C.

→ [findings.md: Bootloader USB entry points](../../frames/aqara-s1-plus/research/findings.md#bootloader-usb-entry-points-stock-u-boot-201805-read-from-the-binary)

## 3. Mainline U-Boot, and the DRAM bug

To flash anything over USB, we needed our own bootloader running from RAM. That meant mainline
U-Boot with a board config for the panel, using the real DRAM parameters read from the stock
BOOT0 header (DDR4, 792 MHz, 1200 mV, AXP806 PMIC).

It "worked": 2 GiB came up and passed every write-then-read test. Then real code crashed at
random. TF-A hit undefined instructions on valid opcodes, `memcpy` hung, and crash dumps came
out garbled. A copy loop (load from one address, store to another) found the pattern: about
**300 corrupted 64-bit words per MiB**, all on read→write bus turnarounds.

To find the cause, we diffed the DRAM controller registers two ways: as our SPL programmed
them (read over FEL), and as stock boot0 programmed them (read from Android through
`/sys/class/sunxi_dump`). Six differences stood out:

| Register | Mainline | Stock | Why it mattered |
|---|---|---|---|
| MR0 (`INIT3` + MRS) | `0x840` | `0x520` | The RAM ran a different CAS latency than the controller assumed |
| `MSTR` bit 0 | geardown on | off | The DRAM's own MR3 has geardown off |
| `INIT7` (MR6) | `0x826` | `0x862` | A typo |
| `DRAMTMG6` | `0x00000005` | `0x02020005` | `0x20 << 16` truncated to 0 |
| `ADDRMAP1`/`ADDRMAP8` | `0x1f` | `0x3f` | "Disabled" in 6-bit fields |
| `SCHED[3:0]` | `0x1` | `0x5` | |

With all six matched to stock, everything booted. A bisect then showed that **MR0 alone is
the fix**: without it, U-Boot hangs even with the other five changes; with only MR0, a copy test
of 16 MiB passes 12 times out of 12. The other five just match boot0. This likely affects every
A133 board with DDR4, so the MR0 fix goes upstream as a single patch. Three smaller fixes
followed:

- **CPU clock.** The CPU is pinned to 408 MHz, because there is no SPL driver for the AXP806.
- **USB uploads.** These go in 64 KiB chunks, because the boot ROM's USB is slow at that clock.
- **No TF-A.** U-Boot runs straight at EL3 for now.

Then came fastboot over USB-C, plus a small `upload` extension to **read** partitions back
at about 20 MB/s.

→ [fel.md](../../frames/aqara-s1-plus/research/fel.md) · patches in [`tools/fel/uboot/patches`](../../tools/fel/uboot/patches)

## 4. Persistent root with Magisk

The radio modes need a root service, and the stock image makes that awkward:

- adbd drops the capabilities that Aqara's `su` needs.
- The boot ramdisk is first-stage only.
- Changing `system` would mean rebuilding `super`.

Magisk changes only `boot`, and its `overlay.d` injects an init service into second-stage
init. That is exactly what the S1 root helper needs. The app never gets `su`; it only sets
properties, and the init service does the root work.

- **AVB.** It turned out not to matter. The panel already boots a `vbmeta` with verification
  disabled, even while it reports `locked`, so a patched `boot` just boots.
- **First boot.** A one-shot service authorises the installing computer's adb key, which is
  baked into the image, so adb comes up with no taps on the screen.
- **Factory simulation.** We wiped the reference panel back to factory state: adb off, keys
  cleared, stock `boot`, factory `vbmeta`. One command then installed and verified everything,
  and adb was back 21 s after reboot. Re-running on a patched panel produces the identical
  image.

→ [root.md](../../frames/aqara-s1-plus/research/root.md)

## 5. The installer, moved into the browser

The CLI chains `sunxi-fel`, `fastboot`, Docker and `adb`. The web installer does the same in
one page, over WebUSB:

```mermaid
flowchart LR
  A[FEL<br/>1f3a:efe8] -->|SPL + U-Boot to RAM| B[fastboot<br/>1f3a:1010]
  B -->|read partitions| C[Backup<br/>browser + .zip]
  C --> D[Patch boot<br/>in the browser]
  D -->|flash + read back| B
  B -->|reboot| E[adb<br/>Tango]
  E --> F[S1 Kiosk, Wi-Fi,<br/>HA, radio]
```

- **FEL over WebUSB.** A TypeScript port of the subset of `sunxi-fel` we use: `ver`, `spl` and
  `reset64`, uploading in chunks.
- **fastboot.** Our gadget, plus the partition-read extension.
- **A byte-exact Magisk patch.** This was the hard part. magiskboot compresses with specific
  Rust crates (`zlib-rs` at level 9, `lzma-rust2` with an ARM Thumb BCJ filter), and neither
  the browser's gzip nor liblzma produces the same bytes. So those exact pinned crates are
  compiled to WebAssembly. The browser's output matches the CLI's Docker patch byte for byte,
  and the tests check it against golden images from the CLI.
- **adb over WebUSB.** Tango (ya-webadb) installs the app and configures the panel. Wi-Fi
  credentials go over Improv Serial through adb.

The UI went through five mockups before *option E*, the one that shipped.

<p align="center">
  <img src="../img/installer/welcome.webp" alt="The web installer's first screen: the step rail on the left and the browser checks on the right." width="80%">
</p>

→ [web-installer.md](../../frames/aqara-s1-plus/research/web-installer.md) (the design spec, written before the build) · [`kiln/`](../../kiln/src/README.md)

## 6. Making it a good wall panel

- **A browser that can run Home Assistant.** The system WebView is Chromium 74. GeckoView ships
  its own current engine, so S1 Kiosk is built on it.
- **The screen that turned itself off.** At night the panel kept going dark, and it looked like a
  kiosk bug. It was Aqara's system manager: a hidden Do Not Disturb rule from 22:00 to 07:00
  forces the screen off 15 s after every wake. The panel had also shipped in the
  `Africa/Algiers` time zone. The installer now turns that rule off through Aqara's own binder API,
  and the kiosk runs its own night mode, using the panel's time-of-flight and light sensors.
  → [screen-policy.md](../../frames/aqara-s1-plus/research/screen-policy.md) · [../sensors.md](../sensors.md)
- **Aqara's cloud.** Disabling Aqara's apps didn't stop it phoning home. A native daemon,
  `ha_master`, kept the cloud link up. A watchdog in Aqara's system manager also forced a Wi-Fi
  reassociation whenever that link dropped. The kiosk stops the daemon at start, and the panel
  then holds no internet connections. → [debloat.md](../../frames/aqara-s1-plus/research/debloat.md)
- **Voice.** openWakeWord runs on the panel with TFLite. The ESPHome native API makes the panel an
  Assist satellite, and the *Ambient Stage* overlay shows the conversation.

<p align="center">
</p>

## 7. The radio

The panel's 802.15.4 radio is a Silicon Labs EFR32. It sits on SPI and runs **multi-PAN RCP**
firmware over CPC. Aqara's stack is
`cpcd → zigbeed (spinel IID 1) → EZSP → Z3Gateway → MQTT`.

- **Zigbee coordinator.** Keep Aqara's `cpcd` and `zigbeed`, and bridge EZSP to TCP so that
  ZHA on Home Assistant drives the radio over the network.
- **Thread.** OpenThread's `otbr-agent`, cross-built for the panel, talks to the same RCP on the
  free spinel IID 2. A full border-router build is still in progress, so this mode is
  **experimental**.
- **Routers.** Zigbee router and Thread router modes shipped in 0.3.0 and joined a real home network on 2026-11-10 (experimental).
- **Deliberately not offered:** Zigbee and Thread at the same time on one radio. They would share
  a channel, and Home Assistant deprecated the same multiprotocol design on its own hardware.

The radio's firmware flash path was proven by reflashing the identical stock RCP image.

→ [../radio.md](../radio.md)

## 8. The wall base

The mains relay base talks to the panel over `/dev/ttyS3`. Its protocol was reverse-engineered
from Aqara's `bracket_cli` and `libha_bracket.so`, which are 32-bit ARM, stripped, and read with
`llvm-objdump` and radare2.

To check the result, we wrote an emulator from that reading and ran **Aqara's own `bracket_cli`
against it on the panel**. Aqara's tool accepted it. The kiosk's native driver passes the same
test, but it has **not yet met a real base**.

→ [bases.md](../../frames/aqara-s1-plus/research/bases.md) · [base-protocol.md](../../frames/aqara-s1-plus/research/base-protocol.md)

---

## The research notes

This page is the index of every note. Notes about one device live with its frame, in
`frames/<id>/research/`; notes that apply to every frame live here, in `docs/research/`.

### General (`docs/research/`)

| File | What's in it |
|---|---|
| [bringup.md](bringup.md) | The method: how to break into a device step by step, and what the S1 taught us about FEL, U-Boot, fastboot and Android 10 |
| [TEMPLATE.md](TEMPLATE.md) | Start a new note from this |
| [microwakeword.md](microwakeword.md) | Replacing openWakeWord with microWakeWord: the Kotlin port of TFLite Micro's audio frontend, the models, test speech, and what is left for the panel |
| [ha-esphome-actions-events.md](ha-esphome-actions-events.md) | What a notification can carry over ESPHome each way: why `notify` takes one JSON `data` argument, and why a tapped button goes to HA as a bus event, not an event entity (#118) |
| [camera-presence.md](camera-presence.md) | Which detector wakes the screen from a camera: ML Kit and a person model weighed, then the maintainer's lighting-insensitive motion detector chosen (#117) |
| [onvif-rtsp.md](onvif-rtsp.md) | What HA's ONVIF integration and UniFi Protect need from a camera, the libraries weighed, and the maintainer's tested RTSP and ONVIF server that Glassworks ports (#117) |

### Aqara S1 Plus ([`frames/aqara-s1-plus/`](../../frames/aqara-s1-plus/README.md#research-notes))

| File | What's in it |
|---|---|
| [hardware.md](../../frames/aqara-s1-plus/research/hardware.md) | Board, SoC, FEL button, the 3-pin UART console and an ESP32-S3 as the adapter |
| [boot-log.md](../../frames/aqara-s1-plus/research/boot-log.md) | Annotated stock boot log: BOOT0 DRAM bring-up, secure-boot state, partitions |
| [findings.md](../../frames/aqara-s1-plus/research/findings.md) | Root, SELinux, AVB, partitions, USB/adb defaults; why there is no button-free USB entry |
| [fel.md](../../frames/aqara-s1-plus/research/fel.md) | Mainline U-Boot from FEL: the A133 DDR4 fix, CPU clock, chunked uploads, fastboot |
| [root.md](../../frames/aqara-s1-plus/research/root.md) | Persistent root with Magisk, the root helper, first-boot adb provisioning, `fel-install.sh` |
| [web-installer.md](../../frames/aqara-s1-plus/research/web-installer.md) | The WebUSB installer's design spec: FEL port, fastboot, byte-exact boot patch, per-OS setup |
| [screen-policy.md](../../frames/aqara-s1-plus/research/screen-policy.md) | Why the panel turned its screen off (Aqara's DND night rule) and what replaced it |
| [debloat.md](../../frames/aqara-s1-plus/research/debloat.md) | Aqara's packages, the reversible de-bloat, and the cloud daemon and its Wi-Fi watchdog |
| [bases.md](../../frames/aqara-s1-plus/research/bases.md) | Which wall bases exist, how the panel talks to them, the kiosk's driver |
| [base-protocol.md](../../frames/aqara-s1-plus/research/base-protocol.md) | The wall base UART protocol, with the emulator proof against Aqara's own tool |
| [zigbee2mqtt.md](../../frames/aqara-s1-plus/research/zigbee2mqtt.md) | Zigbee2MQTT on the stock radio: Silicon Labs' zigbeed 8.2.2 on Aqara's RCP, panel tests, licences |
| [rcp-firmware.md](../../frames/aqara-s1-plus/research/rcp-firmware.md) | Could a newer multiprotocol RCP be built for this MG21? CPC protocol, tooling, board config |
| [framework-restart.md](../../frames/aqara-s1-plus/research/framework-restart.md) | Android 10's ActivityManager breaking during fast kiosk restarts, and `system_server` restarting; the likely (untested) cause of the panel "dropping off USB" |
| [boot-animation.md](../../frames/aqara-s1-plus/research/boot-animation.md) | What Android 10's boot animation and the 32 MiB boot image allow: texture caching, trim, the size budget, and what was cut from the #63 design |
| [idle-cpu.md](../../frames/aqara-s1-plus/research/idle-cpu.md) | Why the kiosk uses ~40% of a core at idle: the wake word's embedding model, per-thread numbers, and the options (#105) |
| [bluetooth.md](../../frames/aqara-s1-plus/research/bluetooth.md) | The panel's Bluetooth (RTL8821CS, shared with Wi-Fi), Android 10's scan rules from AOSP, and what HA needs from an ESPHome Bluetooth proxy (#121) |

### lululemon Studio Mirror ([`frames/lululemon-mirror/`](../../frames/lululemon-mirror/README.md#research-notes))

| File | What's in it |
|---|---|
| [mirror-android.md](../../frames/lululemon-mirror/research/mirror-android.md) | Can the lululemon Mirror run Android 8+ for the current GeckoView? The signed boot image blocks it; the kexec idea, the paths, blobs and read-only probes (desk research, #39) |
| [revisions.md](../../frames/lululemon-mirror/research/revisions.md) | Which Mirror revisions are a target: the later NXP i.MX8 revisions (only JTAG access reported), and why screen size doesn't identify it (read, no hardware) |
| [mirror-abis.md](../../frames/lululemon-mirror/research/mirror-abis.md) | Which ABIs the Mirror's userspace runs: 64-bit in the community image, stock untested (#98) |

### Generic Android ([`frames/generic-android/`](../../frames/generic-android/README.md))

No notes yet.

User-facing guides stay one level up, in [docs/](../README.md).

### Ground rules for these notes

- No device-unique values: no serials, MACs, EUI64s, keys or PAN IDs. They are redacted wherever
  a log contained them.
- No Aqara binaries or firmware in the repository. Backups and dumps stay off-repo.
- Every claim is something we ran or read. Where something is untested, the note says so.
