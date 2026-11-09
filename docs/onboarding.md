# Onboarding plan: from "fresh panel" to "showing my dashboard" with minimal config

Goal: a user who has just installed S1 Kiosk never types a URL, token, IP or key on the panel.
Everything rides on standard Home Assistant mechanisms (ESPHome discovery, HA OAuth, Improv),
not on a custom integration.

Status: **plan**. Nothing below is implemented unless marked ✅.

## The flow the user sees

1. **Wi-Fi.** Usually already done: a panel that was set up with the Aqara app keeps its Wi-Fi
   after our install. A fresh or reset panel gets Wi-Fi through **Improv via BLE**, with an on-screen
   picker as fallback.
2. **HA finds it.** "New device discovered: S1 Panel" appears in HA → click **Add**. HA generates
   and pushes the encryption key itself.
3. **Sign in with your phone.** The panel shows a QR code. Scan it, sign in to HA on the phone (normal
   HA login, password manager works), done. The panel never shows a login form.
4. **Pick a dashboard** from a dropdown on the panel's device page in HA (defaults to your default
   dashboard).

A first-boot **setup screen** (same visual style as the dashboard) walks through these and moves on
by itself as each step completes.

## Pieces and how they work

### 1. Wi-Fi: Improv via BLE (+ fallback) ✅ (connect path not yet tested on a real network change)

- [Improv](https://www.improv-wifi.com/) is the open Wi-Fi provisioning standard ESPHome uses.
  HA's `improv_ble` integration discovers Improv devices over Bluetooth (or through an ESPHome
  Bluetooth proxy), shows a notification, and lets you enter Wi-Fi credentials in the HA UI.
  Phones can use improv-wifi.com (Web Bluetooth) too.
- Fallback: the setup screen offers Android's Wi-Fi panel (`Settings.Panel.ACTION_WIFI`).

#### What's implemented (`app/kiosk/.../improv/`)

| File | Role |
|---|---|
| `ImprovProtocol.kt` | UUIDs, enums, RPC encode/parse + checksum, fragment reassembly, scan packing, service data (pure, unit-tested) |
| `ImprovSession.kt` | State machine: authorization + timeout, RPC dispatch, Wi-Fi result handling (pure, unit-tested) |
| `ImprovBleServer.kt` | `BluetoothGattServer` + `AdvertisingSet` (legacy PDU), notification queue, long writes, MTU |
| `WifiProvisioner.kt` / `WifiSecurity.kt` | Join the network (legacy API or suggestion), verify, restore the previous network on failure |
| `ImprovOverlay.kt` | "Allow Wi-Fi setup over Bluetooth?" card (dashboard style), status/countdown/result |
| `ImprovController.kt` | Lifecycle: auto/on-demand start, 10 min window, identify (wake + flash + 3 beeps), device-owner escape hatch |
| `ImprovActivity.kt` | Invisible trampoline for the adb intent extras |

Hooks outside the package: `KioskService` (init/shutdown/intent), two new `KioskPrefs` members,
manifest (permissions + `ImprovActivity`), and one ESPHome button in `PanelDevice`.

**When it runs** (never permanently):
- automatically if the panel has **no network 30 s after `KioskService` starts** (boot), unless
  `improv_auto` is off;
- on demand: `adb shell am start -n io.github.aguilaair.s1kiosk/.improv.ImprovActivity --ez improv true`
  (`--ez improv false` stops), or the HA button **"Start Wi-Fi setup (Bluetooth)"**
  (`button.s1_panel_start_wi_fi_setup_bluetooth`, config category);
- for **10 minutes** (extended while connecting / after a failure), then advertising and the GATT
  server stop. After PROVISIONED it keeps advertising that state for 10 s, then stops (like ESPHome).
- In auto mode, if a network appears by other means it reports PROVISIONED and stops.

**Authorization** (physical action): the overlay's **Allow** button. Allowed for **2 minutes**
(spec suggests ~1 min; 2 gives time to type a password on a phone), restarted after a failed connect.
**Not now** stops Improv. Optional alternative: a proximity far→near approach
(`--ez improv_proximity_auth true`, off by default because on a desk the VI5300 reads "near"
permanently and only fires on threshold crossings).

**Capabilities** = `0x07`: identify, device info, scan Wi-Fi. Hostname (0x05) and device name
(0x06) are answered with error 0x02 *unknown RPC*: Android 10 doesn't let an app set the DHCP
hostname.

#### Verified protocol facts (sources checked 2026-11-09)

- Service `00467768-6228-2272-4663-277478268000`; characteristics `…8001` state (R/N), `…8002`
  error (R/N), `…8003` RPC command (W), `…8004` RPC result (R/N), `…8005` capabilities (R).
  [spec](https://www.improv-wifi.com/ble/), `improv-wifi/sdk-cpp` `src/improv.h`.
- States 1 authorization required, 2 authorized, 3 provisioning, 4 provisioned (0 "stopped" is
  internal to sdk-cpp/ESPHome and must never be advertised). Errors 0 none, 1 invalid RPC,
  2 unknown RPC, 3 unable to connect, 4 not authorized, 5 bad hostname, 0xFF unknown.
- RPC packet `[cmd, len, data…, checksum]`, checksum = sum of the previous bytes & 0xFF; data is
  length-prefixed UTF-8 strings. Wi-Fi settings = `[ssid, password]`; its result's first string is
  the redirect URL (omit for none). Identify has no result. Device info returns firmware name,
  version, chip, device name (spec allows OS name/version after them, but **py-improv-ble-client
  2.0.1 `DeviceInfoRes` rejects anything but exactly 4 strings**, so we send 4). Scan Wi-Fi is ONE
  result with (SSID, RSSI, auth) triples (spec rev 2.2), auth one of WEP/WPA/WPA2/WPA2 EAP/WPA3/WAPI/NO.
  Results are capped at 255 data bytes (sdk-cpp `RPC_RESPONSE_MAX_SIZE` 258) and at the
  notification MTU.
- Writes longer than MTU-3 arrive in pieces; we accumulate until `size-3 == len` (ESPHome
  `improv_ble_component.cpp process_incoming_data_`), drop stale fragments after 2 s, and also
  support prepared (long) writes.
- Advertisement: service UUID **and** service data UUID `0x4677` = `[state, caps, 0, 0, 0, 0]` in the
  same PDU, not in the scan response (spec rev 2.0+). Flags 3 + UUID128 18 + service data 10 =
  exactly 31 bytes, so the name goes into the scan response. HA's bluetooth matcher needs both
  (`improv_ble/manifest.json` at 2026.11.0: `service_uuid` + `service_data_uuid`
  `00004677-0000-1000-8000-00805f9b34fb`); `improv_ble_client.device_filter` and
  `ImprovServiceData.from_bytes` require exactly 6 bytes and a state of 1..4, and HA's config flow
  aborts on PROVISIONING/PROVISIONED (`config_flow.py _abort_if_provisioned`).
- HA's flow (`config_flow.py`): connect → read capabilities → menu (identify / provision) → ask SSID
  + password → if state is 1, show "authorize" and wait for a state notification ≠ 1 → write the
  Wi-Fi settings RPC (write with response) → wait for error ≠ 0 or the RPC result → shows the
  redirect URL, or chains to another integration's discovery flow (e.g. ESPHome) if one registers
  for the BLE MAC within `PROVISIONING_TIMEOUT`.

#### Verified on the panel (2026-11-09)

- Realtek RTL8821CS (BT 4.2): `isMultipleAdvertisementSupported()` = **false**,
  `bluetoothLeAdvertiser` = **present**, extended advertising false. Despite the first value,
  `startAdvertisingSet` (legacy PDU, connectable) **works**, and `setAdvertisingData` updates the
  state in place (seen from the Mac: `010700000000` → `020700000000` after tapping Allow).
- From the Mac (bleak 3.1 + py-improv-ble-client 2.0.1): found as "MagicPad S1 Plus" (the BT
  adapter name, unchanged) with RSSI around -50, `device_filter()` = True (HA would discover it),
  MTU 517, capabilities 7, `need_authorization` True, identify OK (screen wakes, card pulses, 3 beeps),
  device info `S1 Kiosk, 0.1.0, Aqara Panel Hub S1 Plus/sun50iw10p1, S1 Panel`, hostname RPC →
  error 0x02. Scan returned 0 networks because the app doesn't hold the location permission yet
  (see below). After `--ez improv false` nothing advertises the Improv service.
- **Not tested on the device**: sending Wi-Fi credentials / joining a network (by design; unit
  tests + review only).

#### Joining the network on Android 10 (API 29) with targetSdk 34

From AOSP `android10-release` `frameworks/opt/net/wifi` `WifiServiceImpl.java`:
`addOrUpdateNetwork`, `enableNetwork`, `removeNetwork`, `reconnect` and `getConfiguredNetworks`
check `isTargetSdkLessThanQOrPrivileged()`, which is true for target < Q, privileged/system apps,
**device/profile owners**, and **apps holding the SYSTEM_ALERT_WINDOW app-op**
(`WifiPermissionsUtil.checkSystemAlertWindowPermission`). The panel already grants our app that
app-op (`tools/install.sh`), so the "legacy" path works **without device owner** on this Android
version. (That SAW exemption is gone from Android 11; there only device owner works.)
`setWifiEnabled` is not covered: if Wi-Fi is off we report "unable to connect".

Order of attempts in `WifiProvisioner.connect()`:
1. Already connected to the requested SSID → success, config untouched.
2. Legacy (`device owner` or `SAW` on API 29): `addNetwork` (PSK / SAE / OWE / WEP / open, chosen
   from the scan result; transition WPA2/WPA3 → PSK) → `enableNetwork(id, true)` → `reconnect()`.
   Wait up to 30 s (ESPHome's timeout) for supplicant COMPLETED on that network with an IPv4;
   2 authentication errors = wrong password.
3. Otherwise `WifiNetworkSuggestion`: Android 10 shows a notification the user must approve, and
   never switches away from a working saved network, so this only helps a panel with no Wi-Fi.
   The overlay tells the user to approve it; waits up to 2 min.

On failure: our new config is removed (or the suggestion withdrawn), the previous network is
re-selected with `enableNetwork(prevId, true)` + `reconnect()` (autojoin otherwise), and the client
gets error 0x03 *unable to connect*; state goes back to authorized. On success the RPC result is
`http://<new-ip>:8099/` (the panel's setup page), then state 4.

**Location:** reading the current SSID/networkId (`getConnectionInfo` redacts them) and scan results
needs `ACCESS_FINE_LOCATION` **and** location mode on (`WifiPermissionsUtil.enforceCanAccessScanResults`).
Location mode is on (`location_mode=3`) on the panel; the permission is not granted. Without it the
app refuses to change networks while already on Wi-Fi (it couldn't tell the new network from the old
one) and the scan RPC returns an empty list. Grant it once with
`adb shell pm grant io.github.aguilaair.s1kiosk android.permission.ACCESS_FINE_LOCATION`
(`adb install -g` also grants it), or make the app device owner, which grants it to itself
(`setPermissionGrantState`).

#### Device owner (optional)

Needed on Android 11+, or if SYSTEM_ALERT_WINDOW is ever revoked. `device_admin.xml` needs no extra
policies (device-owner Wi-Fi rights aren't policy-gated); the existing `AdminReceiver` is used.

```sh
# needs: no accounts on the device (verified: 0) and a single user (verified)
adb shell dpm set-device-owner io.github.aguilaair.s1kiosk/.AdminReceiver
adb shell dumpsys device_policy | grep -A3 "Device Owner"
# rollback (escape hatch; `dpm remove-active-admin` doesn't work for non-testOnly apps):
adb shell am start -n io.github.aguilaair.s1kiosk/.improv.ImprovActivity --ez release_device_owner true
# clearDeviceOwnerApp also removes the active admin: re-enable lockNow screen-off with
adb shell dpm set-active-admin io.github.aguilaair.s1kiosk/.AdminReceiver
```

While device owner, the app can't be uninstalled (release first), and Settings shows "managed by your
organization". `adb install -r` updates keep working.

#### Open risks

- The connect path is untested on hardware (vendor builds can differ from AOSP).
- Android uses a resolvable private address for advertising; it rotates (~15 min), so HA may
  rediscover the panel under a new address during a long provisioning window.
- If a saved (but not connected) network with the same SSID exists, `addNetwork` overwrites its
  password; a failure then removes it.
- The suggestion fallback needs someone to open the notification shade on a kiosk with hidden bars.
- Only one Improv client at a time is tested; notifications go to every subscribed client.

### 2. Adoption + encryption: ESPHome dynamic keys

- ✅ The panel already advertises `_esphomelib._tcp` and implements the ESPHome native API with
  Noise encryption.
- **New:** report `api_encryption_supported` and `api_encryption_provisionable` in DeviceInfo, and
  accept `NoiseEncryptionSetKeyRequest` over a zero-PSK Noise session. HA 2026.11's
  `esphome/manager.py` (`_async_handle_dynamic_encryption_key`) then generates a key, provisions it
  over that encrypted session (it can't be sniffed), and stores it. The user never sees a key.
- Caveat: discovery is mDNS, so it needs HA and the panel on the same L2 network or an mDNS
  reflector. Otherwise it's a one-time "Add ESPHome → panel IP" in HA.

### 3. Finding Home Assistant

The panel learns HA's URL without asking, in order:
1. HA's own mDNS advertisement `_home-assistant._tcp` (TXT `internal_url`, `base_url`), browsed with
   `NsdManager`. Verified: HA here advertises it.
2. The source address of HA's first ESPHome API connection (HA had to reach us), with port 8123.
3. Manual entry on the setup screen (last resort).

### 4. Login without typing on the panel: QR sign-in + external auth

This is the part that removes the login screen.

- **QR → phone → HA OAuth.** The panel runs a tiny HTTP server (e.g. `:8099`) and shows a QR code for
  `http://<panel-ip>:8099/signin`. That page redirects the phone to HA's standard OAuth endpoint:
  `/auth/authorize?client_id=http://<panel-ip>:8099/&redirect_uri=http://<panel-ip>:8099/callback`.
  HA uses IndieAuth: the client is identified by its URL, so nothing has to be registered in HA. The
  user signs in on the phone. HA redirects the phone back to the panel with an auth code, and the panel
  exchanges it for a **refresh token** (`/auth/token`). The phone shows "Panel signed in ✓".
- **External auth bridge.** The HA frontend supports being embedded by an app that owns the tokens.
  This is how the Companion app avoids the login page. Load the dashboard with
  `?external_auth=1` and expose `window.externalApp.getExternalAuth()/revokeExternalAuth()` (our
  built-in web extension can inject it). The frontend then asks the app for an access token, and the
  app mints it from the refresh token. No login page, sessions don't expire, and **revoking** is
  standard: HA → Profile → Security → refresh tokens.
- Recommended: a dedicated non-admin HA user for panels (e.g. "Panel"), so a stolen panel can't
  administer HA.

Alternatives, kept as options:
- **Sign in on the panel** (works today). Uses the on-screen keyboard.
- **Paste a long-lived token** into a `text` entity on the panel's ESPHome device page in HA (no
  typing on the panel, but tokens never rotate; HA text entities allow up to 255 chars, enough for a
  JWT).
- **`trusted_networks` auth provider** with `allow_bypass_login`. Truly zero-touch, but needs YAML
  edits, a static panel IP and a security trade-off. Document only.

### 5. Which dashboard

- With a token, the app can list dashboards (`lovelace/dashboards/list`) and expose them as an
  ESPHome **select** "Dashboard" on the device page (plus "Default"). Changing it reloads the panel.
- ✅ The existing `text.s1_panel_dashboard_url` stays for anything custom.

## Build order

| # | Piece | Size | Notes |
|---|---|---|---|
| 1 | Dynamic encryption key (`api_encryption_provisionable` + set-key over zero-PSK Noise) | S | Testable with aioesphomeapi + HA now |
| 2 | HA URL discovery (`_home-assistant._tcp` + ESPHome peer) | S | |
| 3 | QR sign-in (mini HTTP server + IndieAuth code exchange) + external-auth bridge | M | Biggest UX win |
| 4 | First-boot setup screen | M | Ties 1–3 together |
| 5 | Dashboard select entity | S | Needs 3 |
| 6 | Improv via BLE ✅ | M | Advertising verified on the RTL8821CS; real connect test pending |

## Open questions

- Dedicated "Panel" HA user, or sign in as yourself?
- Should a panel that can't reach HA's mDNS (separate VLAN) ask for the URL on the setup screen, or
  rely on HA's first ESPHome connection to learn it?
