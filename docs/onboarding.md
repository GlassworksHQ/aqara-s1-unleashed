# Onboarding plan: from "fresh panel" to "showing my dashboard" with minimal config

Goal: a user who has just installed S1 Kiosk never types a URL, token, IP or key on the panel.
Everything rides on standard Home Assistant mechanisms (ESPHome discovery, HA OAuth, Improv),
not on a custom integration.

Status: build-order steps 1–6 are ✅ implemented (dynamic key, HA discovery, QR sign-in + external
auth, setup screen, dashboard select, Improv). The setup screen and the dashboard select are not yet
verified on the panel. Nothing below is implemented unless marked ✅.

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
by itself as each step completes ✅ (see [Setup screen](#setup-screen-)).

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

### 2. Adoption + encryption: ESPHome dynamic keys ✅

- ✅ The panel advertises `_esphomelib._tcp` and implements the ESPHome native API with Noise.
- ✅ **Dynamic key** (`esphome/NoiseKeys.kt`, `ApiServer`). A panel without a key behaves like an
  ESPHome build with `api: encryption:` and no YAML key; a panel with a key works as before.

  Verified against the sources:
  - HA 2026.11.0 `components/esphome/manager.py`: `on_connect` → `_handle_dynamic_encryption_key`.
    If the entry has no key and `device_info.api_encryption_supported`, HA generates
    `base64.b64encode(secrets.token_bytes(32))` (or reuses its stored key for that MAC). With
    `api_encryption_provisionable` it opens a second client with `noise_psk=ZERO_NOISE_PSK`
    (`_async_provision_key_over_noise`) and calls `noise_encryption_set_key(new_key)`, then stores
    the key and writes it to the entry. "The device drops the keyless session and the reauth flow
    reloads the entry with the stored key" (`_async_register_outgoing_target` docstring). The
    plaintext set-key path is HA's fallback for old firmware only. Removing the entry sends an
    empty key (`__init__.py` `_async_clear_dynamic_encryption_key`).
  - `config_flow.py`: `PROBE_NOISE_PSK` is a deliberately *wrong* key (base64 of 32 ASCII "0"s),
    used only to read the name from the "Handshake MAC failure" reply. It is not the provisioning
    key. Zeroconf only treats TXT `api_encryption` as "key required".
  - aioesphomeapi 46.25.0 (pinned in HA's `esphome/manifest.json`) `api.proto`:
    `DeviceInfoResponse.api_encryption_supported = 19`, `api_encryption_provisionable = 26`,
    `NoiseEncryptionSetKeyRequest` (124, `bytes key = 1`, the base64 *text*),
    `NoiseEncryptionSetKeyResponse` (125, `bool success = 1`). `ZERO_NOISE_PSK` = 32 zero bytes.
  - ESPHome 2026.10.2 (the version we report) device side. `api_frame_helper_plaintext.cpp`: with
    no key, a first byte `0x01` hands the socket to a Noise helper with the all-zeros PSK
    (`upgrade_helper_to_noise_`). `api_connection.cpp`: supported = true, provisionable =
    `!has_psk()`. Set-key decodes base64 to exactly 32 bytes, rejects all zeros, empty = clear,
    and over plaintext it still works but logs a deprecation warning. `api_server.cpp`
    `save_noise_psk`: the same key is a no-op success. The key is persisted first and activated
    **100 ms later**; then a `DisconnectRequest` goes to every client and they are force-closed.
    `mdns_component.cpp`: TXT `api_encryption=Noise_NNpsk0_25519_ChaChaPoly_SHA256` with a key,
    otherwise `api_encryption_supported=<same>` + `api_provisioning=zero-psk`.
  - The panel implements exactly that. The key goes to `KioskPrefs` (`api_key` + `api_encryption`,
    written with `commit()`) and survives restarts. Logs show only "key set (fingerprint xxxx)", the
    first 4 hex digits of SHA-256(key). The old "generate and log a key on first start" is gone.
  - Tested in `EspHomeApiTest` with aioesphomeapi playing HA: keyless connect → zero-PSK client
    `noise_encryption_set_key` → the keyless session is dropped → plaintext is refused
    (`RequiresEncryptionAPIError`), the zero PSK is refused (`InvalidEncryptionKeyAPIError`) →
    reconnecting with the new key works. Also: bad and all-zero keys are refused, the same key is a
    no-op, the key survives a server restart, and an empty key clears it. State machine unit tests
    are in `NoiseKeysTest`.
- Caveat: discovery is mDNS, so it needs HA and the panel on the same L2 network or an mDNS
  reflector. Otherwise it's a one-time "Add ESPHome → panel IP" in HA.

### 3. Finding Home Assistant ✅

The dashboard URL setting now defaults to **`auto`** (`BuildConfig.DEFAULT_URL` is gone). A URL that
is already saved in the prefs is kept as is. In `auto` mode the panel loads `<HA>/`. `HaDiscovery`
learns HA's URL without asking, in order:
1. ✅ HA's own mDNS advertisement `_home-assistant._tcp`, browsed with `NsdManager`. HA 2026.11.0
   `components/zeroconf/__init__.py` publishes TXT `location_name`, `uuid`, `version`,
   `external_url`, `internal_url` and `base_url` (= external or internal, "for backward
   compatibility"). Candidates are tried in order: `internal_url`, `base_url`, then the resolved
   `http://<ip>:<port>` (Android 10 can't resolve `.local` names). Verified: HA here advertises
   `internal_url=http://192.168.1.100:8123`, and the panel found it over mDNS.
2. ✅ The address of the first ESPHome API client whose Hello `client_info` starts with
   "Home Assistant" (HA's `CLIENT_INFO`), with port 8123.
3. ✅ Manual entry on the sign-in screen when nothing was found.

Every candidate is confirmed with `GET <url>/manifest.json` (the frontend's PWA manifest, `name`
"Home Assistant") before it is stored (`ha_url`, `ha_url_source`). A manual entry is never
overwritten. ESPHome exposes the URL the panel uses as the text_sensor **Home Assistant URL**.

### 4. Login without typing on the panel: QR sign-in + external auth ✅

This is the part that removes the login screen. Implemented in `auth/` (`OAuth.kt`,
`SignInServer.kt`, `HaAuth.kt`), the setup screen's Sign-in step (`setup/SetupOverlay.kt`, formerly
`SignInOverlay.kt`), `MainActivity` and
`assets/extension/content.js`. Verified details:

- **IndieAuth** (HA 2026.11.0 `components/auth/indieauth.py`): `verify_redirect_uri` accepts a
  redirect_uri with the same scheme + netloc as the client_id without fetching anything.
  `_parse_client_id` accepts any *local* IP host (192.168.x is fine). `/signin` builds both from
  the `Host` header the phone used: `client_id=http://<panel-ip>:8099/`,
  `redirect_uri=http://<panel-ip>:8099/callback`. HA's authorize page loads for them (GET checked
  against this HA).
- **Login flow → callback** (`login_flow.py`, frontend `src/data/auth.ts` `redirectWithAuthCode`):
  when the flow finishes, the redirect_uri is checked and the browser goes to
  `<redirect_uri>?code=<code>&state=<state>` (plus `&storeToken=true` if ticked).
- **Token endpoint** (`components/auth/__init__.py` `TokenView`): form-encoded.
  `authorization_code` + `code` + `client_id` returns `access_token`, `refresh_token`,
  `expires_in` (1800), `token_type`, `ha_auth_provider`. Codes are single use and expire after 10
  min (`{"error":"invalid_request","error_description":"Invalid code"}`).
  `refresh_token` + `client_id` must use the *same* client_id the token was issued to, otherwise
  `invalid_request`. A revoked token gives `invalid_grant`. `POST /auth/revoke token=…` always
  answers 200.
- The panel keeps `{refresh_token, client_id, ha_base}` in the private `auth` prefs (never
  logged). The access token is cached until 60 s before `expires_in`. `invalid_grant` /
  `access_denied` on refresh deletes the token and shows the sign-in screen.
- **External auth** (frontend 20261028.4, the version HA 2026.11.0 pins). `src/data/external.ts`:
  `isExternal = window.externalAppV2 || window.externalApp || webkit… || ?external_auth=1`,
  evaluated once at startup. `external_auth.ts` throws unless `externalApp` exists, calls
  `externalApp.getExternalAuth(JSON.stringify({callback:"externalAuthSetToken", force?}))` and
  waits for `window.externalAuthSetToken(true, {access_token, expires_in})` (or `(false)`). On
  logout it calls `revokeExternalAuth('{"callback":"externalAuthRevokeToken"}')`, and the app
  answers `window.externalAuthRevokeToken(true)`. If `externalApp.externalBus` exists,
  `createExternalAuth` *awaits* `config/get` over the bus, so the bridge answers it with
  `window.externalBus({id, type:"result", success:true, result:{…}})` (asynchronously, because the
  frontend registers the id after the call). The other answer-expecting messages
  (`entity/add_to/get_actions`, `matter/share_device`, `webrtc/stream/start`) get
  `success:false`. Fire-and-forget messages are ignored. The callback names match the Companion
  app's `FrontendJsBridge.kt` (`EXPECTED_GET_AUTH_CALLBACK` / `EXPECTED_REVOKE_AUTH_CALLBACK`).
- The content script installs `window.externalApp` (`createObjectIn` / `exportFunction`) only on
  pages loaded with `external_auth=1`. The app adds the flag to every top-level HA page load once a
  refresh token exists (`onLoadRequest`). Tokens are only given to top-level pages on the
  signed-in HA origin. **Without a refresh token nothing changes**: the GeckoView's own
  cookie/localStorage session works as before.
- The sign-in screen (now the setup screen's Sign-in step) appears when the GeckoView lands on HA's
  `/auth/authorize` (logged out) and there is no refresh token, or after a sign-out. It goes away by
  itself when the token arrives. `--ez show_signin true` previews it without touching the session.

The original design notes:

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

**Verified end to end on the panel (2026-11-09):** QR flow → HA login (as a non-admin user) →
`/callback` stores the refresh token → the panel reloads HA with `?external_auth=1` and the frontend
connects with app-minted access tokens (no login page). The sign-in survives a full app restart
(fresh refresh), and HA shows `sensor.<panel>_signed_in = yes`. Two GeckoView 159 quirks were found and fixed
on the way:
- Replies to `browser.runtime.sendNativeMessage` never reach the page (the parent logs
  "Invalid event data for callback", and the promise rejects with `undefined`). All app ↔ page
  request/response now goes over the long-lived native port with request ids (`content.js` `request()`,
  `MainActivity.onPortRequest`).
- `GeckoBundle` has no `Long`; reply fields must be Int, Double, String or Boolean (`expires_in` is sent as Int).

### 5. Which dashboard ✅

- ✅ ESPHome **select** `Dashboard` (`select.s1_panel_dashboard`, object_id `dashboard`) on the
  device page. Options: **Default** (HA's root, i.e. the user's default dashboard), then every
  Lovelace dashboard by title (duplicates get the `url_path` appended), then **Custom** only while
  the saved URL matches none of them. Choosing one saves the dashboard URL and reloads; the URL stays
  on the same HA host, so no restart for the secure-context hosts. Default goes back to `auto` when
  discovery found that same HA, else to `<ha>/`. Code: `dashboard/DashboardOptions.kt` (pure,
  `DashboardOptionsTest`), `PanelDevice`.
- ✅ Current state: `auto`, `<ha>/` and `<ha>/lovelace…` → Default; `<ha>/<url_path>[/view][?…]` → that
  dashboard; any other path or host → Custom.
- ✅ **Where the list comes from:** the logged-in HA frontend in the GeckoView, so it works with
  today's cookie session as well as with external auth. `content.js` calls the page's own
  `document.querySelector('home-assistant').hass.callWS({type: 'lovelace/dashboards/list'})`
  (through `wrappedJSObject`, callbacks via `exportFunction`) 5 s after an HA page loads (retrying
  every 5 s for 3 min until `hass.connected`), then every 10 min. It sends
  `{type: "dashboards", json: "[{url_path,title}]"}` fire-and-forget on the native port (a JSON
  string, so no GeckoBundle typing issues). `MainActivity.onPortRequest` only accepts it from the
  top-level page on the HA the panel uses. For non-admin users, `require_admin` dashboards are left
  out. HA 2026.11.0 `lovelace/dashboard.py` `DashboardsCollectionWebSocket.ws_list_item` returns
  `dashboard.config` for every dashboard that has one (storage and YAML). The built-in default has
  none, so it is never listed.
- **Not done:** the app-side fallback (fetching the list over HA's websocket with the refresh token
  when no HA page is open). There's no websocket client in the app, and a hand-written one isn't
  trivial. The frontend path covers both session types, so the list only goes stale while the panel
  shows a non-HA page; the last list is kept in the prefs (`dashboards`).
- ✅ **Options are static per entity listing**, so a changed option list rebuilds the entity set and
  drops the API connections (`ApiServer.setEntities`, the same mechanism as the wall base), and HA
  re-lists. It's debounced in `dashboard/StableChange.kt` (`StableChangeTest`): only when the
  options really changed, stayed stable for 10 s, and at most once a minute. A list change that
  keeps the same options only updates the mapping. The key never changes (fixed object_id). The
  list the options were built from is persisted, so a restart doesn't rebuild. Expect **one**
  rebuild (an HA reconnect) the first time the list arrives after updating to this version, and
  one each time "Custom" appears or disappears.
- ✅ The existing `text.s1_panel_dashboard_url` stays for anything custom.

### Setup screen ✅

`setup/SetupOverlay.kt` (UI), `setup/SetupState.kt` (rules, pure, `SetupStateTest`), wired in
`MainActivity`. A native full-screen overlay over the GeckoView in the dashboard's design tokens
(`#0a0b0f` with sky/violet radial glows, glass cards `rgba(255,255,255,.045)` / border `.07`,
22–26 px radii, uppercase letter-spaced `#63636b` labels, light headings, amber `#fbbf24` current
step, green `#34d399` checks). Left: the four steps with checks. Right: a card for the current step.
Laid out for 960×480 dp.

1. **Wi-Fi**: "Connected to <SSID>" (Ethernet too), or "Not connected: set up Wi-Fi over Bluetooth
   from Home Assistant, or [Choose network]". The button opens `Settings.Panel.ACTION_WIFI` (falls
   back to `Settings.ACTION_WIFI_SETTINGS`). The status comes from the `WifiSetupStatus` interface
   (`setup/WifiSetupStatus.kt`: state `CONNECTED | NOT_CONNECTED | CONNECTING`, SSID, optional
   message). The default is plain Android connectivity. Improv can set `WifiSetupStatus.provider` to
   feed its own progress (not wired yet). The SSID needs location permission on Android 10; without
   it the step shows "Connected".
2. **Add to Home Assistant**: done once an ESPHome client whose Hello says "Home Assistant"
   **subscribes to states**. HA's config flow only probes DeviceInfo during discovery, so a plain
   Hello doesn't count. `ApiServer.onStatesSubscribed` → `PanelDevice` persists `ha_seen` and
   notifies `SetupSignals`. Until then the step shows "In Home Assistant: Settings → Devices &
   services → S1 Panel discovered → Add", the panel's IP + port 6053 for a manual ESPHome add, and
   the HA URL (or the manual URL field from step 3 when HA wasn't found). **Skip for now** persists
   `setup_ha_skipped` (for kiosk-only use without the ESPHome device).
3. **Sign in**: the QR code and texts of the former sign-in screen, plus "Sign in on this panel
   instead".
4. **Done**: a check and "You're all set". The dashboard loads (unless it's already showing), then
   the screen fades out.

**When it shows** (`SetupState.decide`). Only strong evidence counts, so it never covers a panel
that already works (the desk panel: Wi-Fi, ESPHome connection from HA, a saved URL, a cookie
session without a refresh token):
- offline for 30 s (boot grace; a short Wi-Fi dropout doesn't trigger it);
- online but HA isn't known at all (no saved URL, nothing discovered);
- HA never added the panel: no HA client connected within 2 min of startup *and* no `ha_seen`, no
  API key, not skipped;
- no refresh token *and* the GeckoView actually reached HA's `/auth/authorize` (or the user signed
  out on the panel), unless "Sign in on this panel instead" was chosen.

The current step is the first one not done (Wi-Fi → HA → Sign in). Once shown, the screen stays
until all steps are done, so it doesn't flicker between steps. It is re-evaluated on connectivity,
sign-in, HA connection and page changes, and every 2 s. The trigger is logged (`S1Kiosk`:
"Setup screen: <reasons> (step …)").

Preview without changing state: `--ez show_setup true` (live step), `--es setup_step
wifi|ha|signin|done` (a given step), `--ez show_setup false` to close. In preview, Skip / manual URL
save nothing, and a "Preview · Close" chip is shown. `--ez show_signin true` = the Sign-in step.

## Build order

| # | Piece | Size | Notes |
|---|---|---|---|
| 1 | ✅ Dynamic encryption key (`api_encryption_provisionable` + set-key over zero-PSK Noise) | S | aioesphomeapi test of HA's exact flow |
| 2 | ✅ HA URL discovery (`_home-assistant._tcp` + ESPHome peer) | S | Found HA over mDNS on the panel |
| 3 | ✅ QR sign-in (mini HTTP server + IndieAuth code exchange) + external-auth bridge | M | Verified end to end on the panel |
| 4 | ✅ First-boot setup screen | M | Rules unit-tested (desk panel must not show it); not yet seen on the panel |
| 5 | ✅ Dashboard select entity | S | List from the HA frontend (cookie session OK); aioesphomeapi test of the option rebuild; no app-side websocket fallback |
| 6 | Improv via BLE ✅ | M | Advertising verified on the RTL8821CS; real connect test pending |

## Open questions

- Dedicated "Panel" HA user, or sign in as yourself?
- Should a panel that can't reach HA's mDNS (separate VLAN) ask for the URL on the setup screen, or
  rely on HA's first ESPHome connection to learn it?
