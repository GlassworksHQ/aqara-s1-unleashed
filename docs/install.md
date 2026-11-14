# Installing S1 Unleashed

There are two ways to install, and they do the same thing. The **web installer** runs in your
browser and needs nothing else. The **command line** runs the same steps as shell scripts.
Both back up the panel first and both can undo.

- [Before you start](#before-you-start)
- [Web installer](#web-installer)
- [Command line](#command-line)
- [Updating](#updating)
- [Switching a development build to a release](#switching-a-development-build-to-a-release)
- [Time zone](#time-zone)
- [Undo](#undo)
- [Troubleshooting](#troubleshooting)

> [!NOTE]
> The web installer has been tested end to end on a real S1 Plus (firmware 4.5.80), from FEL mode
> through the backup, the install and the app setup, and so has the command-line path. The boot
> patch has been tested on two firmware versions: 4.5.80 and the December 2025 firmware the panel
> shipped with. So far that is a small number of panels.

## Before you start

- **Panel:** Aqara Panel Hub S1 Plus (MP-K03D). See [supported hardware](../frames/aqara-s1-plus/README.md#supported-hardware).
- **Cable:** a USB-C **data** cable. Many charging cables carry no data.
- **Tool:** a small screwdriver to open the back cover. You need to reach one button on the board.
- **Computer:** Chrome or Edge on Windows, macOS or Linux (WebUSB). Firefox and Safari don't
  support WebUSB.
- **Home Assistant** on the same network, with the ESPHome integration available (it's built in).
  2026.11 or newer is recommended: it sets up the panel's encryption by itself.

### Finding the FEL button

FEL is the A133's USB recovery mode. It lives in the chip's mask ROM, so it is always there and
it doesn't care what is on the eMMC. With the back cover off, the button is a small tactile
switch to the right of the shield can's notch, just below the round sensor window. It is not the
reset button on the case.

![The S1 Plus board with the back cover removed; the FEL button is circled](img/fel-button.jpg)

To enter FEL: unplug the panel, **hold the button, plug in USB-C**, then let go. It enters FEL as
soon as it has power, within about two seconds. The screen stays black. That's expected.

## Web installer

Open **[the installer](https://s1.edm.sh/)** in Chrome or Edge. Nothing
is uploaded anywhere: the page talks to the panel over USB and keeps its backup in the browser.

### 1 · Check this browser

<img src="img/installer/welcome.webp" alt="Step 1: browser and secure-connection checks, a per-OS tab, and what you need." width="100%">

One-time setup, depending on your computer:

| OS | What to do |
|---|---|
| **macOS** | Nothing. If you use `adb`, run `adb kill-server` first, because only one program can hold the panel. |
| **Windows** | Once: with the panel in FEL mode, open [Zadig](https://zadig.akeo.ie/), choose *Options → List all devices*, pick `USB Device (1f3a:efe8)`, select **WinUSB** and click *Replace driver*. The installer's own USB mode installs its driver by itself. |
| **Linux** | Once: save the rule below as `/etc/udev/rules.d/50-s1.rules` and run `sudo udevadm control --reload`. Stop `adb` if it is running (`adb kill-server`). |

```
SUBSYSTEM=="usb", ATTR{idVendor}=="1f3a", ATTR{idProduct}=="efe8", MODE="0660", TAG+="uaccess"
SUBSYSTEM=="usb", ATTR{idVendor}=="1f3a", ATTR{idProduct}=="1010", MODE="0660", TAG+="uaccess"
```

### 2 · Enter FEL mode

<img src="img/installer/fel.webp" alt="Step 2: the board photo with the button circled, and the steps hold the button, plug in USB-C, keep holding 10 seconds." width="100%">

Put the panel in FEL mode as described [above](#finding-the-fel-button), then click **Connect**
and pick `USB Device (1f3a:efe8)`. The installer checks the chip is an A133 before doing anything else.

### 3 · Back up and install

<img src="img/installer/install.webp" alt="Step 3: a progress ring with the phases back up, patch, flash and verify." width="100%">

Keep the cable in and the page open. The installer:

1. boots its own U-Boot **from RAM**, so nothing is written yet, and checks the partition layout
   is an S1 Plus;
2. **backs up** `boot`, `vbmeta` and the other small partitions, reading `boot` twice and comparing.
   The backup is stored in the browser and offered as a `.zip` download with a `SHA256SUMS`;
3. **patches** `boot` in the browser: Magisk v31.1, the S1 root helper, the boot animation, and
   this browser's adb key for the first boot. The result is byte-for-byte what the CLI's
   Docker-based patch produces;
4. sets the `vbmeta` flags to "verification off" if they aren't already, and swaps in the Glass
   Tile splash;
5. **flashes** each partition and **reads it back** to verify, then restarts the panel.

The first start takes a minute or two. When the panel's adb shows up, the browser asks you to
pick it.

**Download the backup and keep it.** The backup from your first install is the only copy of the
panel's original software.

### 4 · Set up the app

S1 Kiosk is installed and made the home screen. Then, from the same page:

<table>
<tr>
<td width="50%"><img src="img/installer/wifi.webp" alt="Wi-Fi: a list of networks in range of the panel, and a password field."></td>
<td width="50%"><img src="img/installer/ha.webp" alt="Home Assistant: the address found on the network, signed in, and a prompt to add the discovered panel."></td>
</tr>
</table>

- **Wi-Fi.** The panel scans and you pick a network. The password goes straight to the panel over
  USB and isn't stored on your computer. A panel that already has Wi-Fi keeps it.
- **Home Assistant.** The address is found on your network. *Sign in* opens the panel's sign-in
  page, the same one its QR code points to, so your login goes from Home Assistant straight to the
  panel. Then click **Add** on the discovered *Glassworks Panel* in *Settings → Devices & services*.
- **Radio.** Choose what the Zigbee/Thread radio does. You can change it any time in Home Assistant.
- **Panel settings.** Language, time zone, 12- or 24-hour clock, and optionally Simple Keyboard
  (Apache-2.0) instead of Aqara's keyboard. The time zone is also kept from then on: see
  [Time zone](#time-zone).

<img src="img/installer/radio.webp" alt="The radio choice: Off (selected), Zigbee coordinator, Thread border router (experimental), Zigbee router and Thread router (experimental)." width="100%">

*An older screenshot: the repeater roles are now offered too, as experimental, and called Zigbee router (repeater) and Thread router (repeater).*

| Radio mode | Status |
|---|---|
| Off | Default. The radio stays idle. |
| Zigbee coordinator | Home Assistant runs a Zigbee network on the panel's radio, over the network: ZHA, or Zigbee2MQTT (**experimental**). |
| Thread border router | **Experimental.** Runs its own Thread network for Home Assistant's OpenThread Border Router integration. |
| Zigbee router (repeater), Thread router (repeater) | **Experimental.** Joins the network you already have and extends it. |

What to do next in Home Assistant for each one: [radio-guide.md](radio-guide.md). After you choose, the
installer shows the steps with links that open the right pages in Home Assistant.

Wi-Fi and Home Assistant can be skipped and done later on the panel itself. Its setup screen
covers Wi-Fi (including Improv over Bluetooth), adding it to Home Assistant, and QR sign-in.
The radio starts as *Off*; just click *Finish* to leave it that way. See
[onboarding.md](onboarding.md).

<img src="img/panel/improv-allow.webp" alt="On the panel: Allow Wi-Fi setup over Bluetooth? Home Assistant (Improv via BLE) or improv-wifi.com on a phone can send it Wi-Fi details." width="70%">

*Setting up Wi-Fi on the panel instead: it asks before accepting Wi-Fi details over Bluetooth.*

### 5 · Done

<img src="img/installer/done.webp" alt="Done: the panel is on the network; Home Assistant finds it as an ESPHome device." width="100%">

Home Assistant discovers the panel as an ESPHome device and sets up encryption by itself.
What you get in HA: [esphome.md](esphome.md). Music Assistant pairs with the panel automatically:
[sendspin.md](sendspin.md).

### Resume, or jump to a step

Already installed? You never need to reflash to finish or redo setup. Open the installer with one of
these at the end of its address (or use **Already installed? Finish setting it up** on the first
screen); each connects to the running panel over USB first:

| Address | Does |
|---|---|
| `#/resume` | Updates S1 Kiosk with your settings, then Wi-Fi, Home Assistant and radio |
| `#/wifi` | Just the Wi-Fi step |
| `#/home-assistant` | Address, sign-in and the Add-to-Home-Assistant link |
| `#/radio` | The Zigbee/Thread radio role |
| `#/undo` | Put the original software back |
| `#/install` | Start a full install (FEL mode) |

The address follows you through the installer, so reloading the page picks up where you were.

## Updating

**S1 Kiosk updates through Home Assistant.** A new release shows up under *Settings → Updates* as
the panel's **App update**. Press **Install**; the panel downloads it, checks its checksum and
signing key, installs it and restarts the app. There's nothing to confirm on the panel (on boot
software from before the first releases, Android asks on the panel's screen). Details:
[esphome.md](esphome.md#app-updates).

- **On v0.4.0 or older, update by hand once.** Those versions can't install an update from Home
  Assistant: it always fails with "the APK is not signed". Plug the panel in, open the installer,
  choose **Already installed? Finish setting it up** (`#/resume`) and let it update S1 Kiosk. Or
  download the APK from the [latest release](https://github.com/GlassworksHQ/glassworks/releases/latest)
  and run `adb install -r s1-kiosk-<version>.apk`. From v0.4.1 on, updates from Home Assistant work.
- **The boot software** (the patched `boot` with the root helper) is not updated by the app. Since
  v0.4.0 the radio side updates with the app instead, but only on a panel with that version's boot
  software. Home Assistant's **Radio helper** sensor says whether it needs updating. To update it,
  run the full install again: *Get started* (`#/install`), with the FEL button. It backs up again
  first, but keep the backup from your **first** install: it's the only one with Aqara's original
  `boot`.
- A panel running a **development build** can't take release updates. See the next section.

## Command line

Use this path to script the install, or if you don't want to use WebUSB. It runs on macOS or
Linux and needs `sunxi-fel`, `fastboot`, `adb`, Docker (for the boot patch) and a U-Boot build.

```sh
# 0. Build U-Boot for the panel (once) and the kiosk app
UBOOT_SRC=~/src/u-boot BL31=<bl31.bin> tools/fel/build-uboot.sh
(cd app && ./gradlew :kiosk:assembleDebug)

# 1. FEL → boot U-Boot from RAM → back up → patch → vbmeta → splash → flash → verify → adb
tools/install/fel-install.sh ~/s1-backup

# 2. Install and configure S1 Kiosk over adb
tools/install.sh --url http://homeassistant.local:8123 \
    --timezone America/Los_Angeles --locale en-US \
    --keyboard rkr.simplekeyboard.inputmethod_148.apk     # optional, from F-Droid
```

- `fel-install.sh` waits for FEL (hold the button, plug in), then backs up to `~/s1-backup` with
  a `SHA256SUMS` file. Opt out of the branding with `BRANDING=none` and `BOOT_ANIMATION=none`.
- `install.sh` installs the app, grants its permissions, makes it the home screen, disables
  Aqara's bloat and night screen-off, and pushes the wake word models. `--dry-run` prints what
  it would do. `--help` lists every option.
- Already have adb on a panel? `tools/install.sh` alone sets up the app without root. You only
  get the radio modes with the root helper from `fel-install.sh`.

How each step works, and why: [frames/aqara-s1-plus/research/root.md](../frames/aqara-s1-plus/research/root.md) and [frames/aqara-s1-plus/research/fel.md](../frames/aqara-s1-plus/research/fel.md).

## Switching a development build to a release

A panel set up with the command line usually runs a **development build** of S1 Kiosk
(`assembleDebug`, signed with your computer's debug key). Releases are signed with the project's
key, and Android won't update an app to one signed with a different key. So the switch removes
the app and installs the release. On its own, that has two side effects:

- the app's data goes: its settings, the Home Assistant sign-in and the pairing key;
- Android 10 also forgets every Wi-Fi network the app saved. S1 Kiosk saves the panel's Wi-Fi itself
  (Improv), so the panel would drop off the network.

Both installers handle this. Install as usual (the web installer's `#/resume`, or `tools/install.sh
--apk <release APK>`). When Android refuses the new version, nothing has been changed yet. The
installer then says what will happen on this panel and asks you to confirm: a dialog in the browser,
or typing `yes` in the terminal (`--switch` confirms in advance, for scripts). `--dry-run` shows the
plan without changing anything.

| | adb has root | adb has no root (a normal install) |
|---|---|---|
| App settings, sign-in, pairing key | Kept: copied out before the removal, then copied back to the new install | **Reset**: sign in again, and and set the panel's options again (dashboard, screen and voice settings, for example). Home Assistant may ask to add the panel again, and Music Assistant shows it as a new player |
| Wi-Fi | Read from Android's Wi-Fi store into the installer's memory, then sent to the new app over USB | Web: the Wi-Fi step asks for the password. CLI: asks in the terminal (Enter skips) |

- **Root** here means that adb's shell can run `su`. That is only the case with a development boot
  image (`DEV_SU_HOURS`) or a Magisk policy for the adb shell, see
  [frames/aqara-s1-plus/research/root.md](../frames/aqara-s1-plus/research/root.md#persistent-root-magisk--the-s1-kiosk-root-helper). A normal
  install doesn't give adb root. The installer checks with `su -c id`.
- The Wi-Fi password is never written to a file, a log or the screen. A network that S1 Kiosk didn't
  save, for example one set up in Aqara's settings, is not affected.
- While the app is copied it is disabled, so it can't start as the home screen meanwhile. Disabling
  doesn't touch its Wi-Fi.
- If the new version won't install, the previous one is put back (with its data, given root).
- The panel stays reachable over the USB cable throughout. If it ends up without Wi-Fi anyway, send
  it again: the web installer's `#/wifi` page, or Improv over Bluetooth from the panel's setup screen
  or Home Assistant.

## Time zone

Aqara's system software sets the time zone again at every boot. Its own (cloud-side) setting is empty
on a panel without Aqara's cloud, so it falls back to UTC+8 and picks the first zone with that offset,
`Antarctica/Casey`. The panel's root helper (boot image with helper v4) puts the zone you chose back
within seconds after boot, and whenever it changes later.

- The installer records your choice (`persist.s1kiosk.timezone`) along with setting it.
- To change it later: the panel's ESPHome device in Home Assistant, Configuration, **Time zone**: an id
  like `Europe/Berlin` or `America/New_York`. HA adds it turned off, so turn it on first
  ([how](esphome.md#categories-and-defaults)). It only accepts zones the panel knows, and only from Home
  Assistant's encrypted connection. **Time zone status** (Diagnostic) says whether the zone is being
  kept.
- Panels set up before this: on their first boot with the new helper it keeps the zone that is in place
  then, unless that is Aqara's `Antarctica/Casey`; then set **Time zone** once.
- With an older boot image the reset still happens; Time zone status says so.

## Undo

<img src="img/installer/undo.webp" alt="Undo: choose back to how it came from Aqara, or keep the panel's settings; restore the original software over FEL, then factory reset." width="100%">

The order matters:

1. **Restore the original software.** In the web installer, choose *Undo a previous install*, pick
   your backup (from this browser or the `.zip`), and put the panel in FEL mode. It writes `boot`,
   `vbmeta` and the splash partition back from the backup and verifies each one.
   From the CLI: `tools/install/fel-install.sh --undo ~/s1-backup`.
2. **Factory reset the panel**, if you want it exactly as it came from Aqara. Swipe down from the
   top, tap *Settings* on the S1 Kiosk notification, then *Advanced settings → Factory reset*.
   A factory reset alone doesn't restore the software, so restore first.

To keep your Wi-Fi and settings but drop the kiosk, skip the factory reset and run
`tools/install.sh --undo` over adb instead (in the web installer: *Keep the panel's settings*). It
re-enables Aqara's apps and launcher, restores Do Not Disturb and the keyboard, removes S1 Kiosk,
and starts Aqara's radio services again.

If S1 Kiosk saved the panel's Wi-Fi (it does when you set Wi-Fi up through it), it is **disabled
rather than removed**. Android 10 forgets the networks an app saved when that app is uninstalled,
which would take the panel off its network. A disabled S1 Kiosk doesn't run and isn't the home
screen; `adb shell pm enable sh.edm.s1kiosk` brings it back. To remove it completely anyway, run
`adb uninstall sh.edm.s1kiosk`, then join the Wi-Fi again in Aqara's own settings.

Use the backup from your **first** install. A backup taken on an already-installed panel holds the
patched `boot`, and both installers refuse to restore it. They also refuse a backup whose splash is
already ours, not Aqara's. That happens when `boot` was put back without its splash (by hand, as
below), and the panel was then installed again: that second backup has a stock `boot` but our splash.
Use the older backup, from the first install. If you no longer have it, put back only `boot` and
`vbmeta` by hand, as below. The splash then stays ours. It is only the power-on image, so the panel
works the same.

Keep the whole backup: the `.zip` the web installer offers, or the CLI's folder. Undo needs `boot.img`,
`vbmeta.img`, `bootloader.img` (the splash) and `SHA256SUMS`, and refuses a backup that lacks one of
them, before it writes anything. If you only have `boot.img` and `vbmeta.img`, you can still put those
two back by hand. The splash then stays ours. The same goes for a backup refused because its splash
is ours. First check those two, in the backup's folder. It needs
both entries in `SHA256SUMS` and both files to match. Go on only if it ends with
`boot and vbmeta OK: go on`:

<!-- install-tests.sh runs this block: keep the marker right above it -->
```sh
grep -q '  boot\.img$' SHA256SUMS && grep -q '  vbmeta\.img$' SHA256SUMS &&
  grep -E '  (boot|vbmeta)\.img$' SHA256SUMS | shasum -a 256 -c - &&
  echo "boot and vbmeta OK: go on"
```

Then put the panel in FEL mode and run:

```sh
NO_BL31=1 tools/fel/fel-boot.sh            # our U-Boot in RAM; it starts fastboot
unset ANDROID_SERIAL                       # our U-Boot's fastboot serial isn't the panel's adb one
fastboot flash boot boot.img
fastboot flash vbmeta vbmeta.img
fastboot reboot
```

## Troubleshooting

| Symptom | What to try |
|---|---|
| The browser lists no `1f3a:efe8` device | The panel isn't in FEL. Unplug it, hold the board button (not the case button), plug in, and hold for 10 s. Try another cable or port, preferably directly on the computer rather than through a hub. |
| Windows: the device shows but won't open | The Zadig step: `1f3a:efe8` needs **WinUSB**. |
| Linux: "access denied" | The udev rule, then unplug and replug. |
| "Unknown hardware" | The installer stopped before writing anything. That panel isn't a layout it knows; please [open an issue](https://github.com/GlassworksHQ/glassworks/issues). |
| A read-back didn't match | The installer stops and doesn't restart the panel. Retry, or restore from your backup with *Undo*. FEL is always available. |
| `adb` or another tool already holds the panel | Run `adb kill-server` and close other flashing tools. |
| The screen turns off at night | That's the kiosk's night mode, which you can tune in Home Assistant. With `install.sh --keep-dnd`, Aqara's own Do Not Disturb (22:00–07:00) also forces it off; see [frames/aqara-s1-plus/research/screen-policy.md](../frames/aqara-s1-plus/research/screen-policy.md). |
