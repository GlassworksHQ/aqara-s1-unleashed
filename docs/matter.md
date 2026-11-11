# Pairing the panel with a phone (Matter)

**Experimental.** The panel can be paired the way Thread devices are: scan its QR code in your phone's
home app, and the phone sends it your Thread network over Bluetooth. The panel then joins that network
as a *Thread router* ([radio-guide.md](radio-guide.md#repeater-modes-experimental)), and stays a
Matter device in your home: its screen as an On/Off switch, its presence sensor as an occupancy
sensor, and its ambient light sensor as a light sensor.

This is the third way to give the panel your Thread network, next to *Join Home Assistant's Thread
network* (an administrator signs in once, [radio-guide.md](radio-guide.md#repeater-modes-experimental))
and pasting the dataset by hand. It is the only one that doesn't need Home Assistant at all.

## Before you start

- **Home Assistant:** in *Settings → Apps → Matter Server → Configuration*, turn on **Enable test-net
  DCL usage**. The panel identifies itself with the Matter SDK's *test* certificate (vendor `0xFFF1`):
  this project isn't a certified Matter vendor. Without the option, Home Assistant refuses the panel at
  "device attestation". Only turn it on if you're fine with Home Assistant trusting test devices.
- **Your phone** pairs over Bluetooth, so be close to the panel. Tested with an iPhone (Apple Home,
  then shared to Home Assistant). Android phones pair Matter devices through Google Play services, which
  may refuse test devices.
- A Thread border router on your network (Apple, Google, Home Assistant's own…): the panel reports the
  Thread networks your border routers announce on the LAN, which is what your phone looks for.
- The root helper, for the *Thread router* mode the panel switches to ([radio-guide.md](radio-guide.md)).

## Pairing

1. On the panel, choose the **Thread router (repeater)** role (setup screen, or the settings menu: tap
   the top-left corner five times → **Radio**), then **Pair with a phone** on its card. The panel shows
   a QR code and a code to type.
2. In your phone's home app, add a device and scan the code (in the Home Assistant app: *Settings →
   Devices & services → Add device → Add Matter device*).
3. The phone pairs over Bluetooth and sends the panel your Thread network. The panel says **Paired** and
   switches to *Thread router*; **Radio status** on its device page says when it's in.

The code stays valid for 15 minutes; **Close** stops it earlier. The panel keeps its own pairing code:
it is random, made once per panel.

After pairing, the panel is a Matter device ("S1 Panel") wherever it was added, with three endpoints:
the screen (an On/Off light: turning it off and on switches the screen), presence (an occupancy
sensor, from the panel's time-of-flight sensor) and ambient light (a light sensor, in lux; reported
when it changes by about a quarter, or after a minute). **Pair with a phone** on a paired panel opens
pairing again, to add it to another home app or hub. To undo the pairing, remove the device in your home app; **Leave Thread network**
on the panel's device page makes the radio forget the network.

## How it works

The panel runs a Matter device built on [rs-matter](https://github.com/project-chip/rs-matter), with an
Android port of it that this project wrote: `rs-matter-android`, in the owner's fork
([aguilaair/rs-matter](https://github.com/aguilaair/rs-matter), branch `android`; git submodule
`app/third_party/rs-matter`). It is meant as a general library: any Android device can be a Matter
device this way. The app part is `app/kiosk/.../matter/PanelMatter.kt`.

- **Bluetooth:** Android's `BluetoothGattServer` serves the Matter BLE service (`0xFFF6`, C1 and C2) and
  advertises it; the Matter transport over it (BTP) runs in Rust, through JNI.
- **Commissioning is Bluetooth-only** while the window is open, like a Thread device that isn't on any
  network yet: phones send network credentials only to devices they commission over Bluetooth. (A device
  they can see on the LAN gets commissioned there, with no credentials.)
- **The scan:** before sending a Thread dataset, Apple's commissioner asks the device to scan, and waits
  for its network to show up. The panel answers with the networks the LAN's border routers advertise
  (`_meshcop._udp`: network name, extended PAN ID). They don't announce the channel or PAN ID; the panel
  reports 0, which Apple accepts. rs-matter couldn't answer scans during Bluetooth commissioning at all;
  the fork adds a hook for that.
- **The dataset** goes the same way as the other ways in: to the root helper (`files/radio/thread-dataset`),
  then *Thread router* mode (`RadioController.joinThreadNetwork`). It is never logged or shown.
- **Afterwards** the device runs over the panel's Wi-Fi, not over Thread: the panel's Thread radio is
  Aqara's, run by OpenThread, and the Matter device is the app.

Tested on 2026-11-10 with the library's sample app on the panel and an iPhone: commissioned over
Bluetooth, the Thread dataset received, handed over to Home Assistant, the On/Off endpoint switched from
Home Assistant. The panel's own integration (this page) builds and its unit tests pass; it hasn't been
run on the panel yet. Research notes: [research/matter.md](research/matter.md).
