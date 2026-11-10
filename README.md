# Aqara S1 Unleashed

Opening up the **Aqara Panel Hub S1 Plus** (model **MP-K03D**, internally **MagicPad S1 Plus**) so it can run your own Android apps — the end goal being a **Home Assistant voice-satellite kiosk** that replaces the stock Aqara launcher.

> **Status: working on the reference panel; installer for fresh panels in progress.** The S1 Kiosk app
> runs the panel as a Home Assistant kiosk + voice assistant + Music Assistant speaker, with Aqara's
> launcher, apps and cloud disabled. Getting it onto a *sealed* panel without the debug UART (FEL
> installer) is the remaining big step. See [Roadmap](#roadmap).

> ⚠️ **Disclaimer.** This modifies hardware you own, at your own risk. It can brick the device if you deviate from tested steps. Not affiliated with or endorsed by Aqara/Lumi. No warranty. Don't publish device-unique secrets (MAC, serial, SID, keys).

---

## The device

| | |
|---|---|
| Product | Aqara Panel Hub S1 Plus (MP-K03D) / "MagicPad S1 Plus" |
| SoC | **Allwinner A133** (aka R818) — quad Cortex-A53, `sun50iw10` |
| RAM | 2 GiB DDR4 (792 MHz) |
| Storage | ~8 GB eMMC (MMC 5.1) |
| PMIC | X-Powers AXP806 |
| OS | **Android 10** (`QP1A.191105.004`), board `peony_evb2`, **test-keys** |
| Bootloader | BOOT0 → ARM Trusted Firmware (BL31) → **U-Boot 2018.05** (Allwinner) |

## Why this is tractable (the good news)

From the stock boot log and a root shell, the device is wide open:

- **Secure boot is OFF** — `secure enable bit: 0`, and the verified-boot root key (ROTPK) was never fused (`tool did not set rotpk burn flag, skip rotpk burn`). The SoC boots unsigned bootloaders.
- **FEL works unauthenticated** — the Allwinner BROM USB recovery mode (FEL) accepts code with no signing. This is the universal, UART-free entry point.
- **AVB (Android Verified Boot) is present but not hardware-enforced** — `avbtool 1.1.0`, `vbmeta` flag `0`. Because secure boot is off, AVB can be disabled (vbmeta "verification disabled" flag) or re-signed.
- **Root is available out of the box** — an AOSP `su` binary ships at `/system/xbin/su` (syntax: `su 0 <cmd>`).
- **SELinux is permissive** (`/sys/fs/selinux/enforce` = `0`).
- **U-Boot offers fastboot** — it probes for USB fastboot/burn at boot (`usb burn from boot`) and has an interruptible `Hit any key to stop autoboot`.

See [docs/findings.md](docs/findings.md) and [docs/boot-log.md](docs/boot-log.md) for the evidence.

## Entering FEL mode

FEL is the Allwinner mask-ROM USB recovery mode. It lives in silicon and **cannot be erased**, which also makes the device very hard to permanently brick (you can always re-enter FEL and rewrite).

1. Power the panel off (unplug USB-C).
2. **Open the back cover.** On the PCB there is a single **unlabeled tactile button** (not the external reset button — that one does nothing for this).
3. **Hold that button** and plug USB-C back in while holding, for ~10 s.
4. The screen stays **black**. On a host, `sunxi-fel -l` now lists an Allwinner device (reports as `A133`, SoC id `0x1855`).

The external case button and the Aqara app pairing button do **not** trigger FEL.

## DRAM parameters (needed to build a working FEL U-Boot)

The generic mainline A133 configs don't match this board; these are the real values read from BOOT0:

```
SoC          : sun50iw10 (A133)
DRAM type    : DDR4
DRAM clock   : 792 MHz
DRAM voltage : 1200 mV   (PMU: AXP806)
Size         : 2048 MiB
para1        : 0x610a
para2        : 0x8000000
tpr11        : 0xf101612
tpr12        : 0xc0c110f
tpr13        : 0x107501
```

## Repo layout

```
docs/
  hardware.md    Board, FEL button, 3-pin UART console pinout
  boot-log.md    Annotated stock boot log (DRAM, secure-boot, partitions)
  findings.md    Root, SELinux, AVB, partitions, what's unlocked
  debloat.md     Reversible Aqara de-bloat, locale/time-zone fixes
  screen-policy.md  Why the panel turns its screen off (Lumi DND night mode)
  sensors.md     VI5300 ToF presence, MK6212 light, SC7A20 accel; how the kiosk uses them
  esphome.md     The panel as an ESPHome device in HA (all entities, voice assistant)
  onboarding.md  Fresh-panel setup: Improv Wi-Fi, auto key, HA discovery, QR sign-in
  sendspin.md    Native Music Assistant player (synced playback)
  bases.md       Wall bases (relay modules); base-protocol.md = the UART protocol
  radio.md       EFR32 Zigbee/Thread radio: firmware facts, modes, root-helper contract
app/             S1 Kiosk: MIT GeckoView kiosk app (sh.edm.s1kiosk)
tools/
  install.sh           One-shot panel setup over ADB (and --undo)
  fel/                 U-Boot board config + RAM-only FEL test for the S1 Plus
  bracket/             Wall-base emulator (proves the UART protocol against Aqara's bracket_cli)
  radio/               Radio root helper (script + init .rc, not in any image yet), otbr-agent build
  voice/               Fetch + push the openWakeWord models
  debloat/             Reversible `pm disable-user` de-bloat script
  gecko-eval/          Evaluate JS in the kiosk page over Firefox remote debugging
  mictest/             getUserMedia / secure-context test page
  esp32-uart-bridge/   ESP-IDF firmware turning any ESP32-S3 into a
                       3.3 V USB↔UART bridge for the panel's debug console
                       (dev-only; end users won't need UART)
```

## Setting up a panel (current state)

Once ADB is enabled on the panel (today that takes the debug UART; a UART-free FEL route is in
progress, see `tools/fel/`):

```sh
cd app && ./gradlew :kiosk:assembleDebug && cd ..
export ANDROID_SERIAL=<panel serial>
tools/install.sh --url http://homeassistant.local:8123/my-dashboard \
    --timezone America/Los_Angeles --locale en-US \
    --keyboard rkr.simplekeyboard.inputmethod_148.apk   # optional, from F-Droid
```

`install.sh` installs the [S1 Kiosk](app/README.md) app, grants its permissions, makes it the home
screen, disables Aqara's DND screen-off and bloat, and pushes the wake-word models. Then log in to
HA on the panel once and add it in HA as an **ESPHome** device (port 6053). The panel then shows up
in HA with its screen, presence, light sensor, speaker, voice assistant and wall base.
Everything is reversible: `tools/install.sh --undo`.
`--dry-run` prints what it would do.

## Roadmap

**Reverse engineering / access**
- [x] Identify SoC, confirm FEL entry (PCB button + power)
- [x] Confirm secure boot off / FEL unauthenticated
- [x] Debug UART console (115200 8N1) + root shell; real DRAM parameters
- [x] U-Boot board config for the S1 Plus built (`tools/fel/`)
- [ ] Boot it via FEL (RAM only); bring up fastboot ⟵ *needs the PCB-button test*
- [ ] `provisioning.img` + one-command `install.sh --fel` for sealed panels (no Aqara app/account)
- [ ] Persistent root (boot-image patch), needed for the Zigbee/Thread radio modes
- ~~Button-free USB entry~~: not feasible on this firmware (see docs/findings.md)

**S1 Kiosk app (verified on the reference panel)**
- [x] GeckoView kiosk rendering the current HA frontend full-screen; HA header/sidebar hidden
- [x] HOME launcher; Aqara launcher, bloat, DND screen-off and **cloud daemon** disabled (reversible)
- [x] Screen policy from the panel's own sensors (presence wake, idle off, night window, auto-brightness)
- [x] One ESPHome device in HA: screen, sensors, settings, encryption with automatic key
- [x] Voice assistant with on-device wake word, timers, announcements, on-screen feedback
- [x] Native Music Assistant player (Sendspin, synced playback)
- [x] Onboarding: setup screen, QR sign-in (no login page), dashboard picker, Improv Wi-Fi over BLE
- [x] Wall-base driver straight over UART (no Aqara daemon); proven against Aqara's own tool
- [ ] Real-world checks: real wall base, Improv Wi-Fi switch, wake word from across the room, multi-room sync by ear
- [ ] Zigbee / Thread radio modes (EFR32 radio; user-selectable, [docs/radio.md](docs/radio.md)): `Off` done; coordinator-over-TCP and Thread proven with temporary root, need the root helper
- [ ] Release build (after the real-world checks)

## Design goal for end users

No soldering, no UART, no teardown of the software:

```
# hold the PCB button, plug in USB-C, then:
./install.sh
```

UART is only used here as a **development tool** to reverse-engineer and verify; the shipped flow is pure FEL over the USB-C port.

## Credits

Reverse-engineering and tooling by [@aguilaair](https://github.com/aguilaair). Built on the [linux-sunxi](https://linux-sunxi.org/) project (`sunxi-tools`, U-Boot, ARM Trusted Firmware).
