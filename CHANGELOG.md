# Changelog

What changed in each S1 Kiosk release, for people running a panel. The first line of each version is
what Home Assistant shows on the update card. Releases are built from tags by
[`.github/workflows/release.yml`](.github/workflows/release.yml), which publishes the matching section
below as the release notes.

## [Unreleased]

**A new settings menu, and the panel looks like S1 Kiosk from the boot animation on, with no Aqara screen in between.**

### Added
- **Kiosk settings menu.** The hidden settings (tap the top-left corner five times) are now a full-screen
  menu in the setup screen's look, in sections: Home Assistant & dashboard, Radio: who may connect,
  Panel, About (version and open source licences). Every setting from the old dialog is still there,
  and *Save & restart* works as before. Under Panel, *Panel settings (Aqara)* opens Aqara's own settings
  app (Wi-Fi, sound, display, updates, factory reset).
- **Tapping S1 Kiosk's notification opens the menu**, as a second way in besides the five taps. Its
  *Panel settings* button opens Aqara's settings app. Anyone at the panel can pull the notification
  shade down, so the menu is exactly as hidden as the corner gesture.

### Fixed
- **No Aqara screen at boot any more.** For about 4 seconds after the boot animation, Android showed
  Aqara's fallback home screen while it finished starting up. S1 Kiosk's loading screen now shows
  instead, straight through to the dashboard. It is turned on the first time the new version starts,
  so you see it from the reboot after that. On panels where Aqara's launcher or factory app is still
  enabled (no de-bloat), it stays off so the choice of home app isn't disturbed.
- After a reboot the screen said *No network yet* and how to set up Wi-Fi, while the panel was just
  rejoining its saved network. It now says *Connecting to Wi-Fi…* and *Home Assistant loads as soon
  as it's connected*, and loads the dashboard as soon as Wi-Fi is up. The Wi-Fi setup hint appears
  only when Wi-Fi is off, no network is saved, or the panel hasn't connected within 30 seconds.
- The loading screen's background no longer looks banded right after a reboot. It was drawn as
  plain gradients until the smooth version was ready, which took seconds while the panel was still
  starting up. The smooth version is now prepared straight away and much faster.
- **Every new Zigbee2MQTT or ZHA connection to the panel's radio starts clean.** Messages left over
  from the previous client no longer greet the next one with a *CRC error* and an ack-timeout error
  (Zigbee2MQTT recovered, but it was untidy). This fix comes with the app's radio package: no new
  boot software is needed.
- Flashing the radio firmware shows real progress (or the seconds so far) instead of
  *flashing stock: %*. Also part of the app's radio package.
- The web installer offers *Zigbee router* and *Thread router* as experimental choices, as the panel
  does, instead of listing them as *coming soon*.
- `tools/install.sh --dry-run` no longer states that the panel's app is signed with a different key
  when it may not be; it says what happens *if* the keys differ.
- `tools/debloat/debloat.sh` no longer says the launcher was not disabled and then disables it.

## [0.4.1] - 2026-11-10

**Updates from Home Assistant install again: earlier versions always said "the APK is not signed".**

### Fixed
- Installing an update from Home Assistant failed with *the APK is not signed*, although the release
  is signed: on this Android version the app couldn't read a downloaded APK's certificate the way it
  asked for it. It now reads it the way Android 10 supports.

### Note
- Panels on 0.4.0 or older can't install this update from Home Assistant (the bug is in the version
  doing the installing). Update once with the web installer (*Already installed? Finish setting it
  up*) or `adb install -r`; from 0.4.1 on, updates from Home Assistant work.

## [0.4.0] - 2026-11-10

**Zigbee2MQTT on the panel's radio, and the panel's root side now updates with the app.**

### Added
- **Zigbee2MQTT support (experimental).** A new *Zigbee stack* select in Home Assistant switches the
  Zigbee coordinator from Aqara's stack (ZHA only) to Silicon Labs' newer one, which Zigbee2MQTT's
  `ember` adapter needs. The panel downloads it straight from Silicon Labs (it can't be bundled for
  licence reasons) and checks it. Then use `adapter: ember`, `port: tcp://<panel IP>:6638`; *Radio
  status* shows the values. No radio firmware change is needed. ZHA works with either stack.
- **Updates for the root side through the app.** After one more update of the panel's boot software
  with the web installer, fixes to the radio helper, new radio stacks and radio firmware arrive with
  normal app updates, as a package signed by the project that the panel checks before installing. If
  anything is wrong with it, the panel keeps using the version built into its boot software. The new
  *Radio helper* sensor says which one runs and whether the boot software needs updating.
- **Radio firmware updates**, never automatic: choose an image in *Radio firmware image*, then press
  *Flash radio firmware* within two minutes. The panel stops the radio, flashes it with Aqara's own
  tool, checks the version, and goes back to Aqara's original firmware if anything fails. *Radio
  firmware* shows progress. No new firmware is offered yet; Aqara's original can always be put back.
- **Leave Thread network** button: the panel forgets the Thread network it was given (the dataset with
  the network key, and the router's settings), in any radio mode. Needs the new boot software.
- *Radio allowed clients* can also be changed on the panel: in the hidden settings (tap the top-left
  corner five times), under *Radio: who may connect*. It's the same setting as in Home Assistant, and the
  dialog shows who is allowed right now.
- **Open source licences** in the hidden settings list the open-source software the app and the
  panel's radio package include, with each licence's full text.

### Fixed
- **Moving a panel from a development build to a release no longer leaves it off Wi-Fi.** The
  installers explain the switch and ask first. Where adb has root they carry the Wi-Fi and the app's
  settings over by themselves; otherwise the settings are reset and the installer asks for the Wi-Fi
  password again.
- Undoing an install over adb (`install.sh --undo`, or *Keep the panel's settings* in the web
  installer) no longer makes the panel forget its Wi-Fi: S1 Kiosk is turned off instead of removed
  when it saved the network.
- **The time zone no longer resets to Antarctica/Casey (UTC+8) at every boot.** Aqara's system software
  set it back on each start. The panel now keeps the zone you chose in the installer, and a new *Time zone*
  setting in Home Assistant changes it later (*Time zone status* shows whether it is kept). Needs the new
  boot software; panels set up earlier keep the zone they have at their first boot with it, or set *Time
  zone* once.
- Home Assistant no longer loses the panel's connection every 90 seconds. The panel now checks on a
  quiet Home Assistant the way ESPHome devices do, instead of hanging up on it.
- The app update card no longer shows an update with a blank version when the panel started without
  Wi-Fi. It shows "up to date" until the first real check, and checks again as soon as Wi-Fi is back
  instead of 15 minutes later.

### Changed
- The app no longer includes Google's Play services libraries, which came with the built-in browser
  but could never run on the panel.
- The Zigbee coordinator now waits until the radio's start-up message is cleared before letting a
  client in, which avoids a failed first start in Zigbee2MQTT (ZHA was not affected).
- Old hand-made copies of the radio helper on the panel are no longer used automatically (they would
  have hidden updates).
- Radio firmware, Zigbee stack and Thread leave controls only work from Home Assistant's encrypted
  connection, not from an unencrypted one on a panel that was never paired.
- After *Leave Zigbee network*, the panel no longer briefly says the network is rejoining.

### Note
- The new features need the panel's boot software from the web installer once more (one press of the
  button on the back). Panels that skip it keep working as before; Home Assistant says what needs it.

## [0.3.1] - 2026-11-10

**Only Home Assistant can reach the radio now, and update notes no longer get cut off.**

### Changed
- **The radio's network ports only accept Home Assistant.** The Zigbee coordinator (port 6638) and the
  Thread border router's REST API (port 8081, which can hand out the Thread network key) used to accept
  anyone on the network. The panel now learns Home Assistant's address from its encrypted ESPHome
  connection and lets only that address in. *Radio status* says who is allowed. To allow other
  computers, set *Radio allowed clients* in Home Assistant (addresses or ranges, or `any`). This needs the
  new root helper from the web installer; panels with the older helper keep working as before, and
  *Radio status* says so.
- The web installer reminds you to add the panel to Home Assistant first when you pick a radio role.

### Fixed
- The update card in Home Assistant showed release notes cut off mid-sentence. It now shows whole
  sections, and no longer shows a "Last update" line.
- Long radio status messages could leave *Radio status* stuck on "applying".

## [0.3.0] - 2026-11-10

**Zigbee and Thread: the panel's built-in radio can now work for Home Assistant.**

### Added
- **Radio modes**, chosen in the panel's setup or with the *Radio mode* select in Home Assistant:
  - **Zigbee coordinator** for ZHA: radio type EZSP, serial path `socket://<panel IP>:6638`.
  - **Thread border router** (experimental): add *OpenThread Border Router* in Home Assistant with
    `http://<panel IP>:8081`.
  - **Zigbee router** and **Thread router** (experimental): the panel joins your existing network as a
    repeater. New *Join Zigbee network* / *Leave Zigbee network* buttons, and a write-only
    *Thread dataset* field for handing over your Thread network.
- The panel's radio step shows the ZHA serial path and a QR code that opens Zigbee pairing in Home
  Assistant on your phone.
- The web installer explains, for the mode you pick, what to do next in Home Assistant.

### Changed
- The radio helper waits until the Zigbee stack is really ready before reporting the mode as on, and
  keeps the Zigbee connection alive so a vanished client can't block it.
- The installer ships a border-router build of OpenThread (REST API, border routing, SRP).

### Known issues
- In ZHA, **creating a new network** or **restoring a backup** onto the panel hangs, because of a bug in
  ZHA's radio library (fix submitted: [zigpy/bellows#774](https://github.com/zigpy/bellows/pull/774)).
  Choose *keep the radio's network settings* instead.
- Zigbee2MQTT doesn't work with this radio yet (it needs a newer Zigbee stack, EZSP 13+).

## [0.2.0] - 2026-11-10

**First signed release: from now on, updates come through Home Assistant.**

### Added
- **App updates in Home Assistant**: the panel shows an update under Settings → Updates and installs it
  when you press Install, with no prompt on the panel. Each update is checked against its checksum and
  signing key before it's installed.
- **New voice assistant overlay** ("Ambient Stage"): a full-screen glass orb that listens, thinks and
  speaks, shows what it heard and the answer, and can be cancelled with a tap.
- **Setup without touching the panel**: the web installer can send Wi-Fi, the Home Assistant address,
  sign-in and the radio mode over USB. On the panel, Wi-Fi over Bluetooth (Improv) now lives inside the
  setup screen, and there's a radio step.
- Language, time zone and 12/24-hour clock can be set from the installer.
- Wake-word models are downloaded by the panel itself.

### Fixed
- Wi-Fi over Bluetooth on a factory-reset panel (Bluetooth was off, and the Allow prompt went missing).
- A black screen while the app starts, and a "Waiting for a network" screen on a new panel's first
  boot: it now goes straight to setup.
- Joining Wi-Fi could restart the app halfway and lose the result.
- Visible banding in the setup and loading backgrounds.

### Note
- Panels running a development build need a one-time reinstall to switch to release builds (Android
  won't update an app across signing keys). That resets the app's settings.

[Unreleased]: https://github.com/aguilaair/aqara-s1-unleashed/compare/v0.4.1...HEAD
[0.4.1]: https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v0.4.1
[0.4.0]: https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v0.4.0
[0.3.1]: https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v0.3.1
[0.3.0]: https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v0.3.0
[0.2.0]: https://github.com/aguilaair/aqara-s1-unleashed/releases/tag/v0.2.0
