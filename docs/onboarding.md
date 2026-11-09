# Onboarding plan: from "fresh panel" to "showing my dashboard" with minimal config

Goal: a user who has just installed S1 Kiosk never types a URL, token, IP or key on the panel.
Everything rides on standard Home Assistant mechanisms (ESPHome discovery, HA OAuth, Improv),
not on a custom integration.

Status: build-order steps 1–3 are ✅ implemented (dynamic key, HA discovery, QR sign-in + external
auth); the rest is still the plan. Nothing below is implemented unless marked ✅.

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

### 1. Wi-Fi: Improv via BLE (+ fallback)

- [Improv](https://www.improv-wifi.com/) is the open Wi-Fi provisioning standard ESPHome uses.
  HA's `improv_ble` integration discovers Improv devices over Bluetooth (or through an ESPHome
  Bluetooth proxy), shows a notification, and lets you enter Wi-Fi credentials in the HA UI.
  Phones can use improv-wifi.com (Web Bluetooth) too.
- The app would implement the Improv BLE GATT service (service `00467768-6228-2272-4663-277478268000`)
  with an Android `BluetoothLeAdvertiser` and `BluetoothGattServer`. It would connect with
  `WifiNetworkSuggestion` (Android 10), or with root `wpa_cli`/`cmd wifi` if suggestions are too
  limited for a system-ish kiosk, then return a redirect URL (the panel's setup page) per the
  Improv spec.
- **To verify first (spike):** that the panel's Bluetooth controller supports LE peripheral mode
  (`BluetoothAdapter.isMultipleAdvertisementSupported()`). The panel has `bluetooth_le`, but
  advertising capability isn't visible from the shell.
- Fallback: the setup screen offers Android's Wi-Fi panel (`Settings.Panel.ACTION_WIFI`).

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
`SignInServer.kt`, `HaAuth.kt`), `SignInOverlay.kt`, `MainActivity` and
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
- The sign-in screen appears when the GeckoView lands on HA's `/auth/authorize` (logged out) and
  there is no refresh token, after a sign-out, or when HA isn't known yet (manual URL field). It
  goes away by itself when the token arrives. `--ez show_signin true` previews it without
  touching the session.

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

### 5. Which dashboard

- With a token, the app can list dashboards (`lovelace/dashboards/list`) and expose them as an
  ESPHome **select** "Dashboard" on the device page (plus "Default"). Changing it reloads the panel.
- ✅ The existing `text.s1_panel_dashboard_url` stays for anything custom.

## Build order

| # | Piece | Size | Notes |
|---|---|---|---|
| 1 | ✅ Dynamic encryption key (`api_encryption_provisionable` + set-key over zero-PSK Noise) | S | aioesphomeapi test of HA's exact flow |
| 2 | ✅ HA URL discovery (`_home-assistant._tcp` + ESPHome peer) | S | Found HA over mDNS on the panel |
| 3 | ✅ QR sign-in (mini HTTP server + IndieAuth code exchange) + external-auth bridge | M | Real end-to-end sign-in still to be done by hand |
| 4 | First-boot setup screen | M | Ties 1–3 together |
| 5 | Dashboard select entity | S | Needs 3 |
| 6 | Improv via BLE | M | Spike BLE peripheral support first |

## Open questions

- Dedicated "Panel" HA user, or sign in as yourself?
- Should a panel that can't reach HA's mDNS (separate VLAN) ask for the URL on the setup screen, or
  rely on HA's first ESPHome connection to learn it?
