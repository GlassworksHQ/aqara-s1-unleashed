# Matter on the panel (2026-11-10)

GitHub issue #1 asks for the panel to join a Thread network the way Matter devices do: scan a code in
the Home Assistant app, and the panel receives the Thread dataset during commissioning. This note is
what a one-evening spike found. The panel can already get the dataset from Home Assistant without
Matter ("Join Home Assistant's Thread network", [radio-guide.md](../radio-guide.md#repeater-modes-experimental)).
This is about the Matter route.

## Which stack

- **connectedhomeip (the Matter SDK)** has an Android platform layer, but it is built for
  controllers (CHIPTool, Google Play services) and IP-only devices (the TV app). As a BLE
  *peripheral* it does nothing: `src/platform/android/BLEManagerImpl.cpp` returns
  `CHIP_ERROR_NOT_IMPLEMENTED` for the advertising mode, the advertising name and `SendIndication`.
- **rs-matter** (pure Rust, `project-chip/rs-matter`, Matter 1.6) has a `GattPeripheral` trait
  (`rs-matter-stack/src/ble.rs`) that a platform implements, and a `NetCtl` trait
  (`rs-matter-dm/src/clusters/net_comm.rs`) that receives the network credentials
  (`connect(&WirelessCreds)`), including a Thread dataset. Both fit the kiosk: a Kotlin
  `BluetoothGattServer` behind JNI for the first, `RadioController.joinThreadNetwork` for the second.

## What was run

- **Build.** rs-matter at `c800469` needs Rust 1.92. Its `onoff_light` example cross-compiles for
  `armv7-linux-androideabi` with the NDK's `armv7a-linux-androideabi29-clang` as linker, unchanged:
  a 2.9 MB PIE, release build in about 2.5 minutes.
- **Run.** Pushed to `/data/local/tmp` and started as the adb shell user (uid 2000, no root, no
  install). It opens the commissioning window, listens on UDP 5540, and its built-in mDNS responder
  shares UDP 5353 with Android's `mdnsd` and `otbr-agent` (all three sockets bound, no errors). A Mac
  on the LAN resolved `_matterc._udp` with `D=3840 CM=1 VP=65521+32769`.
- **Commissioning, over IP.** An iPhone (HA app → *Add Matter device*) commissioned it into the
  phone's own fabric, then opened a window and handed it to Home Assistant's Matter Server.
  - With the Matter Server app's default settings, HA refused it at device attestation: the example
    uses the SDK's development DAC (test vendor 0xFFF1), and the server reported
    `TrustedAsTestCertificate` and "enable the test-net DCL option".
  - With **Enable test-net DCL usage** on (Matter Server app configuration), the same flow worked:
    `Commissioned peer… as @1:1a`, fabric label set, subscription primed. The device then had two
    fabrics, the phone's and HA's.

## What this means for issue #1

- **Over IP, no dataset is sent.** connectedhomeip's `AutoCommissioner` (which HA's commissioning
  flow follows, and the phone's) only runs the network-setup stages when PASE ran over BLE, NFC,
  Wi-Fi PAF or a commissioning proxy (`src/controller/AutoCommissioner.cpp`, `mNeedsNetworkSetup`).
  On IP it assumes the device is already on its network. So the spike proves the Matter part, not
  the Thread part.
- **BLE is the remaining piece:** a Kotlin GATT server for the Matter service (UUID `0xFFF6`, C1/C2/C3)
  driving rs-matter's BTP over JNI, alongside the Improv BLE server, plus a `NetCtl` whose Thread
  `connect` hands the dataset to the radio helper.
- **Certificates:** without a real DAC from a Matter vendor, every user would have to turn on
  test-net DCL in the Matter Server, as above. That is the main product question, more than the code.

Not tried: BLE commissioning, the Android HA app (Google Play services' commissioning may treat test
devices differently), and a build inside the app as a native library.

## Update, later the same night: over Bluetooth, end to end

The BLE piece was built as `rs-matter-android` (fork branch `android`, [matter.md](../matter.md)) and
tested with a sample app on the panel and an iPhone:

- **Commissioning over BLE works** through Android's `BluetoothGattServer`: PASE, attestation, NOC.
- **It must be BLE-only.** In rs-matter's concurrent mode the node is also commissionable on the LAN,
  and the iPhone used the LAN (sending no network credentials). Non-concurrent mode advertises over BLE
  only while the window is open.
- **Apple scans first.** After `AddNOC` the iPhone sent `ScanNetworks` (`0/0x31/0x0`). rs-matter answered
  it with `InvalidAction` during BLE commissioning (no operational controller yet), which aborted the
  pairing; an empty, successful scan made the iPhone wait until the fail-safe expired. With the panel's
  Thread network in the results, it went on: `AddOrUpdateThreadNetwork`, `ConnectNetwork`, the dataset
  (110 bytes) reached the app, `CommissioningComplete`, then the handover to Home Assistant.
- **The scan results can come from the LAN:** border routers advertise `_meshcop._udp` with the network
  name and extended PAN ID. Reporting channel and PAN ID 0 (they aren't in the TXT records) was accepted.
- **rs-matter's connection manager** needs `connected()` to become true after `connect`, or it reconnects
  in a tight loop.
- The SDK's development DAC still needs Home Assistant's test-net DCL option.

