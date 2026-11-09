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
| 6 | Improv via BLE | M | Spike BLE peripheral support first |

## Open questions

- Dedicated "Panel" HA user, or sign in as yourself?
- Should a panel that can't reach HA's mDNS (separate VLAN) ask for the URL on the setup screen, or
  rely on HA's first ESPHome connection to learn it?
