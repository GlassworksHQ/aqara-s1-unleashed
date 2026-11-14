# ESPHome device (native API, voice assistant, panel controls)

The kiosk app makes the panel look like **one ESPHome device** to Home Assistant. HA's built-in
**ESPHome** integration connects to it over the ESPHome native API (TCP port **6053**) and gets:

- an **Assist satellite** with an on-device wake word (microWakeWord on TFLite, as Home Assistant
  Voice PE runs it), like Voice PE: wake word select, pipeline select, VAD sensitivity, announcements, timers;
- a **media player** (`media_player.s1_panel_speaker`) for TTS, announcements and audio;
- the **screen** as a light, the presence and light sensors, and the kiosk/screen settings;
- the switch for the panel's native **Music Assistant player** (Sendspin, [sendspin.md](sendspin.md)),
  and the Sendspin pairing-token hand-off that lets Music Assistant pair with it automatically.

One integration, discovered automatically, no Wyoming or MQTT. It replaces the earlier Wyoming
satellite (remove that Wyoming entry from HA when you switch).

Code: the protocol core is the reusable pure-JVM library [`app/libs/esphome-api`](../app/libs/esphome-api/README.md)
(package `sh.edm.esphome`), with mDNS from [`app/libs/esphome-api-android`](../app/libs/esphome-api-android/README.md);
the panel's entities and glue are `PanelDevice` and `VoiceAssistantController` in
`app/kiosk/src/main/java/sh/edm/s1kiosk/esphome/`, with the wall base and the Aqara cloud switch in
`base/WallBasePane.kt` and the radio in `radio/RadioPane.kt`, owned by the foreground `KioskService`, so it
keeps working while the screen is off. Logs: `adb logcat -s S1ESPHome S1Voice`.

## Add it to Home Assistant

1. Install the app ([install.md](install.md)). The panel downloads the wake word models itself the
   first time it needs one (`voice/ModelFetcher.kt`): the four official microWakeWord models
   (`okay_nabu`, `hey_jarvis`, `alexa`, `hey_mycroft`) from
   [esphome/micro-wake-word-models](https://github.com/esphome/micro-wake-word-models) at a pinned
   commit, each checked against a pinned SHA-256, into
   `/sdcard/Android/data/sh.edm.s1kiosk/files/models/micro/`. `tools/install.sh` also pushes them
   over adb (`tools/voice/fetch-models.sh`, which downloads and checks the same files; `--no-models`
   skips it). Without models the API still works; the engine retries loading every 30 s.
2. **Discovery**: the panel advertises `s1-panel._esphomelib._tcp` over mDNS. If mDNS reaches HA,
   **Settings → Devices & services** shows a discovered **ESPHome** device "Glassworks Panel". Click
   **Add**.
3. **Manual**: **Settings → Devices & services → Add integration → ESPHome**, host = the panel's
   IP, port `6053`.
4. On the device page pick the **Assist pipeline** and the **Wake word** (one select per pipeline;
   the options are the models found on the panel). Say the wake word.

### Encryption (automatic)

Noise encryption (`Noise_NNpsk0_25519_ChaChaPoly_SHA256`, the same as ESPHome) works like an
ESPHome device built with `api: encryption:` and no key in YAML:

- **No key yet** (fresh panel): plaintext and zero-PSK Noise clients are accepted. DeviceInfo
  reports `api_encryption_supported` and `api_encryption_provisionable`. mDNS advertises
  `api_encryption_supported` + `api_provisioning=zero-psk`. When you add the panel in HA (2026.11+),
  HA generates a key and sends it over a zero-PSK Noise session (`NoiseEncryptionSetKeyRequest`).
  The panel stores it, activates it 100 ms later and drops every older session. HA reconnects with
  the key. You never see the key.
- **With a key**: only Noise with that key (as before). Logs show only a 4-hex fingerprint.
- Removing the device in HA sends an empty key, which puts the panel back to "no key".
- Manual key, as before: `$A --es api_key $(openssl rand -base64 32) --ez api_encryption true`
  (applied live; all sessions are dropped). Then enter the same key in HA.

Details and sources: [onboarding.md](onboarding.md#2-adoption--encryption-esphome-dynamic-keys-).

## Device identity

| Field | Value |
|---|---|
| name / friendly name | `s1-panel` / `Glassworks Panel` (before #63: `S1 Panel`; see [Names](#names)) |
| MAC (unique id in HA) | the Wi-Fi MAC of `wlan0` if readable, else a locally administered MAC derived from the chip serial (SHA-256 of `ro.serialno`), else a random one; persisted in prefs (`device_mac`). The first two come out the same after a reinstall, so HA keeps the same device |
| manufacturer / model | `Aqara` / `Panel Hub S1 Plus (S1 Kiosk)` |
| esphome_version / compilation_time | `2026.10.2` / `S1 Kiosk <app version>` (HA shows "2026.10.2 (S1 Kiosk 0.1.0)") |
| API version | 1.20 (as ESPHome 2026.10/11); `DeviceCapabilitiesRequest` is answered |
| Sendspin | `DeviceCapabilitiesResponse.sendspin` = `{feature_flags: PAIRING_TOKEN, client_id}`; `SendspinPairingTokenRequest` (159) → `SendspinPairingTokenResponse` (160), token only over Noise with a key ([sendspin.md](sendspin.md#esphome-hand-off-how-ha-gives-ma-the-token)) |
| voice_assistant_feature_flags | 61 = VOICE_ASSISTANT 1 + API_AUDIO 4 + TIMERS 8 + ANNOUNCE 16 + START_CONVERSATION 32 (0 when `voice_enabled` is false). Voice turned on or off while the panel runs changes it, and the panel asks Home Assistant to reconnect so it reads it again (#67) |

## Entities

HA names entities `<friendly name> <entity name>`, so they show as "Glassworks Panel Screen" and so
on. The `object_id` is the ESPHome-style snake_case name, the key is FNV-1a of the `object_id`
(stable). The entity ids in this page are those of a panel added before #63 (`light.s1_panel_screen`);
[Names](#names) says when they read `glassworks_panel_*` instead.

### Names

The panel calls itself **Glassworks Panel** since #63. What Home Assistant does with that (read
2026-11-13 from home-assistant/core's tags and aioesphomeapi 46.25.0; on the dev panel's HA 2026.11 on
2026-11-12, the device showed "Glassworks Panel" and all 67 existing ids stayed `s1_panel_*`):

| | A panel added before #63 | A panel added after #63 |
|---|---|---|
| Device name | Becomes "Glassworks Panel" on the next connection (HA's device registry follows the friendly name), unless you renamed the device in HA | "Glassworks Panel" |
| Config entry title | Stays "S1 Panel" (HA doesn't retitle an entry) | "Glassworks Panel" |
| Entity ids | Unchanged (`light.s1_panel_screen`, …): an entity id is made once, and the unique id (`<mac>/<device_id>/<domain>/<entity name>`) holds no device name | HA 2025.12 and later: from the device's name as HA shows it, `light.glassworks_panel_screen`, …. HA up to 2025.11: from the node name, `light.s1_panel_screen`, … |
| Entities that appear after the update (a new feature's entities, or one re-added) | HA 2025.12 and later: `glassworks_panel_*`, next to the old `s1_panel_*` ones (mixed prefixes). Up to 2025.11: `s1_panel_*` | As above |
| Node name (`device_name`, mDNS) | `s1-panel` | `s1-panel` |

Where a new entity's id comes from depends on Home Assistant's version:
- **Up to 2025.11** the esphome integration sets it itself, from the node name (`device_info.name`):
  `{domain}.{node name}_{entity}` (`esphome/entity.py` 2025.1.0:234, 2025.11.0:366). HA takes an id an
  entity supplies as it is, so new ids are `s1_panel_*` whatever the friendly name.
- **From 2025.12** it doesn't (`esphome/entity.py` 2025.12.0 sets no `entity_id`). The entity has
  `has_entity_name`, so HA builds the id from the device's name as HA shows it: `name_by_user`, or the
  name the integration registers from the friendly name (`esphome/manager.py` 2025.1.0:601, 2025.10.0:913;
  `helpers/entity_platform.py` 2025.1.0:800, 2026.1.0:1244). So `glassworks_panel_*`, or your own name.
- **From 2026.8** the area's name can come first (`light.<area>_glassworks_panel_screen`,
  `helpers/entity_registry.py` 2026.8.0:1367; earlier versions have no area part).

The node name `s1-panel` never changes (`PanelDevice.NAME`, `PanelNameTest`): HA's config entry keeps
it (`device_name`), the mDNS service is named after it, and HA up to 2025.11 makes new entity ids from it.

### Categories and defaults

Each entity has a category and a default, following the rule in
[the pane rules](../app/kiosk/src/main/java/dev/glassworks/README.md#entities). An entity is enabled
when HA first adds it only if people reach for it day to day. When HA adds a panel, 15 entities
are on (22 with a two-channel switch base). The rest are created turned off. To turn one on, open
**Settings → Devices & services → ESPHome → Glassworks Panel**, select *+N entities not shown*,
open the entity, and turn on *Enabled*.

| Entity | object_id | Pane | Category | Default | Why |
|---|---|---|---|---|---|
| Presence | `presence` | core | none | on | Automations |
| Illuminance | `illuminance` | core | none | on | Automations |
| Screen | `screen` | core | none | on | Dashboards and automations |
| Speaker | `speaker` | core | none | on | Dashboards, Music Assistant, announcements |
| Reload dashboard | `reload_dashboard` | core | none | on | Used often |
| Restart app | `restart_app` | core | config | on | Used often |
| Screen mode | `screen_mode` | core | config | on | Automations (night, away) |
| App update | `app_update` | core | config | on | HA shows updates only while it is on |
| IP address | `ip_address` | core | diagnostic | on | People check it |
| Auto brightness | `auto_brightness` | core | config | off | Set once |
| Idle timeout | `idle_timeout` | core | config | off | Set once |
| Night idle timeout | `night_idle_timeout` | core | config | off | Set once |
| Night window | `night_window` | core | config | off | Set once |
| Presence range | `presence_range` | core | config | off | Set once, for the room |
| Zoom | `zoom` | core | config | off | Set once |
| Dashboard | `dashboard` | core | config | off | Set once. Turn it on to pick a dashboard; the panel has no picker of its own yet |
| Dashboard URL | `dashboard_url` | core | config | off | Set once |
| Hide HA header | `hide_ha_header` | core | config | off | Set once |
| Open device settings | `open_settings` | core | config | off | Rare; you can open the menu on the panel |
| Start Wi-Fi setup (Bluetooth) | `improv_ble` | core | config | off | Only when the panel changes network |
| Sign out | `sign_out` | core | config | off | Rare |
| App version | `app_version` | core | diagnostic | off | *App update* shows the version |
| Update status | `update_status` | core | diagnostic | off | *App update* shows progress; for troubleshooting |
| Home Assistant URL | `home_assistant_url` | core | diagnostic | off | Troubleshooting |
| Signed in | `signed_in` | core | diagnostic | off | Troubleshooting |
| CPU usage, CPU temperature, Wi-Fi signal, Uptime | `cpu_usage`, `cpu_temperature`, `wifi_signal`, `uptime` | diagnostics | diagnostic | on | The few health values people check ([Diagnostics](#diagnostics)) |
| Diagnostics | `diagnostics` | diagnostics | config | off | Set once: the pane's on/off |
| Bluetooth proxy | `bluetooth_proxy` | Bluetooth proxy | config | off | Set once: the pane's on/off ([Bluetooth proxy](#bluetooth-proxy)) |
| Bluetooth advertisements, Bluetooth proxy status | `bluetooth_advertisements`, `bluetooth_proxy_status` | Bluetooth proxy | diagnostic | off | Troubleshooting: the settings page shows both. Unavailable while the pane is off |
| App CPU usage, RAM available, App RAM, Storage free, Wi-Fi link speed, App uptime, Web content processes, Last restart reason | `app_cpu_usage`, `memory_available`, `app_memory`, `storage_free`, `wifi_link_speed`, `app_uptime`, `web_content_processes`, `last_restart_reason` | diagnostics | diagnostic | off | Troubleshooting (unchanged since #142) |
| Voice | `voice` | voice | config | off | Set once: the pane's on/off ([Panes on and off](#panes-on-and-off)) |
| Mute microphone | `mute_microphone` | voice | config | on | Privacy, toggled day to day |
| Wake sound | `wake_sound` | voice | config | off | Set once |
| Voice overlay | `voice_overlay` | voice | config | off | Set once |
| Mic gain | `mic_gain` | voice | config | off | Set once |
| Wake word threshold | `wake_word_threshold` | voice | config | off | Set once |
| Music Assistant player (Sendspin) | `sendspin` | music | config | off | Set once |
| Music Assistant player status | `sendspin_status` | music | diagnostic | off | Troubleshooting |
| Notifications | `notifications` | notifications | config | off | Set once: the pane's on/off. The pane itself is off by default ([notifications.md](notifications.md)) |
| Do not disturb | `do_not_disturb` | notifications | config | on | Automations (bedtime) |
| `notify` action (`esphome.<panel>_notify`) | `notify` | notifications | n/a | listed | An action, not an entity: HA lists it while the panel is connected, and it answers "off" while the pane is off |
| Radio status | `radio_status` | radio | diagnostic | on | People check it when they use the radio |
| Radio mode | `radio_mode` | radio | config | off | Set once |
| Zigbee stack | `zigbee_stack` | radio | config | off | Set once |
| Radio allowed clients | `radio_allowed_clients` | radio | config | off | Set once |
| Radio firmware image, Flash radio firmware | `radio_firmware_image`, `radio_firmware_flash` | radio | config | off | Rare, and risky |
| Join / Leave Zigbee network | `zigbee_router_join`, `zigbee_router_leave` | radio | config | off | Once per network |
| Join Home Assistant's Thread network, Leave Thread network | `thread_join_ha`, `thread_leave` | radio | config | off | Once per network |
| Thread dataset | `thread_dataset` | radio | config | off | Once per network |
| Thread join | `thread_join` | radio | diagnostic | off | Only during a join |
| Radio helper, Radio firmware | `radio_helper`, `radio_firmware` | radio | diagnostic | off | Troubleshooting |
| Keep time zone | `keep_time_zone` | time zone | config | off | Set once: the pane's on/off |
| Time zone | `time_zone` | time zone | config | off | Set once |
| Time zone status | `time_zone_status` | time zone | diagnostic | off | Troubleshooting |
| Wall base | `wall_base` | wall base | config | off | Set once: the pane's on/off |
| Block Aqara cloud | `block_aqara_cloud` | wall base | config | off | Set once |
| Aqara cloud daemon | `aqara_cloud` | wall base | diagnostic | off | Troubleshooting |
| Base | `base` | wall base | diagnostic | off | Troubleshooting; the base's own entities show what is mounted |
| Base firmware | `base_firmware` | wall base | diagnostic | off | Troubleshooting |
| Base relay N, Base button N, Base power, Base energy | `base_relay_N`, `base_button_N`, `base_power`, `base_energy` | wall base | none | on | The reason to mount a base |
| Base protection | `base_protection` | wall base | diagnostic | on | Overheat and overload; people automate on it. It had no category before #132 |

HA adds its own voice entities (*Assist satellite*, *Assist pipeline*, *Wake word*, *Finished speaking
detection*). The panel doesn't send those, so their defaults are HA's.

The pin goldens (`panel-entities*.golden.txt`) end a line with `disabledByDefault` when the entity is
off by default, so a review shows every new entity's default.

#### A panel that HA already has

HA reads `disabled_by_default` only when it first registers an entity. Read 2026-11-13 from
home-assistant/core 2026.11.1:

- `esphome/entity.py`:616 turns `disabled_by_default` into `entity_registry_enabled_default`, and
  :623-626 maps `entity_category`. Both are read again on every listing.
- `helpers/entity_platform.py`:1108-1109 makes that `disabled_by=INTEGRATION`, and passes it with the
  category to `async_get_or_create`.
- `helpers/entity_registry.py`:1650-1666: for an entity already in the registry, `async_get_or_create`
  updates the category, the name and the icon but not `disabled_by`. Its docstring (:1634) says
  `disabled_by` "does not affect an existing entity".
- An entity HA deleted and later sees again gets back the enabled state it had before
  (`entity_registry.py`:1694, :1836).

So on a panel HA already has:

| What | After the update |
|---|---|
| Entities that were on | Stay on, with the same entity id, area and name |
| Entities you turned off | Stay off |
| Category | Follows the panel: *Base protection* moves to the device page's *Diagnostic* section. No other category changes |
| Entities that appear later (a new pane, a wall base mounted after the update) | Get the new default when HA first sees them |
| Removing the panel from HA and adding it back | Each entity gets back the state it had, while HA still remembers the deleted entities |

### What each entity does

| Entity | object_id | What it does |
|---|---|---|
| light `Screen` | `screen` | on → wake the screen, off → screen off (`ScreenPolicy.wake/sleep`); brightness sets a fixed brightness and turns *Auto brightness* off |
| switch `Auto brightness` | `auto_brightness` | light-sensor brightness curve |
| binary_sensor `Presence` (occupancy) | `presence` | VI5300 ToF "near" |
| sensor `Illuminance` (lx) | `illuminance` | MK6212 light sensor |
| select `Screen mode` | `screen_mode` | `presence` / `always` |
| number `Idle timeout` (s) | `idle_timeout` | screen off after idle (0 = never) |
| number `Night idle timeout` (s) | `night_idle_timeout` | same, inside the night window |
| text `Night window` | `night_window` | `HH:MM-HH:MM`, empty = off |
| number `Presence range` (m, 0–2.5, step 0.1) | `presence_range` | VI5300 threshold (needs `WRITE_SECURE_SETTINGS`); 0 = firmware default |
| text `Dashboard URL` | `dashboard_url` | sets + loads the dashboard; `auto` (default) = HA's root on the discovered instance |
| select `Dashboard` | `dashboard` | `Default` + every Lovelace dashboard (by title) + `Custom` only while the saved URL matches none; choosing one saves the URL and reloads ([onboarding.md](onboarding.md#5-which-dashboard-)). The list comes from the HA frontend on the panel; when the options change the entity list is rebuilt (debounced 10 s, at most once a minute) and HA reconnects |
| number `Zoom` (0 = auto) | `zoom` | CSS px ratio; restarts the app |
| button `Reload dashboard` | `reload_dashboard` | |
| button `Open device settings` | `open_settings` | opens the panel's hidden settings dialog on its screen |
| button `Restart app` | `restart_app` | restarts the kiosk process |
| button `Start Wi-Fi setup (Bluetooth)` | `improv_ble` | starts Improv over Bluetooth on demand ([onboarding.md](onboarding.md)) |
| switch `Hide HA header` | `hide_ha_header` | header + sidebar, applied live |
| media_player `Speaker` | `speaker` | play/pause/stop, volume, mute, play media, announce; formats: mp3 (default + announcement) |
| switch `Music Assistant player (Sendspin)` | `sendspin` | the native Sendspin player ([sendspin.md](sendspin.md)), default on; off sends `client/goodbye` `user_request`; encrypted connection only ([Panes on and off](#panes-on-and-off)) |
| text_sensor `Music Assistant player status` | `sendspin_status` | diagnostic; e.g. `ready, paired with Music Assistant (…)`, `playing from …: Artist – Title`, `waiting for pairing or approval by …`, `off` |
| switch `Mute microphone` | `mute_microphone` | releases the microphone entirely |
| switch `Wake sound` | `wake_sound` | short two-tone chime when the wake word is detected (default on) |
| switch `Voice overlay` | `voice_overlay` | Full-screen overlay during voice runs: an orb that follows the voice, what was heard and the reply; tap anywhere to cancel the run (default on) |
| number `Mic gain` | `mic_gain` | 0.5–4× |
| number `Wake word threshold` | `wake_word_threshold` | 0.10–0.99. For a microWakeWord word, the probability cutoff for the mean of the model's last 5 outputs; it starts at the model's own (Okay Nabu and Hey Jarvis 0.97, Mycroft 0.95, Alexa 0.9) and goes back to it when you pick another word. Lower is more sensitive. For a legacy openWakeWord word, openWakeWord's score threshold (default 0.5), as before |
| select `Radio mode` | `radio_mode` | `Off` (default), `Zigbee coordinator (TCP)`, `Zigbee router (repeater)`, `Thread border router`, `Thread router (repeater)` ([radio.md](radio.md); before 0.4.3 the repeaters were `Zigbee router` / `Thread router`, still accepted when sent); without the root helper only `Off` applies, other choices are refused and snap back |
| text_sensor `Radio status` | `radio_status` | diagnostic; e.g. `Off: Aqara's radio services stopped, radio idle. …`, or the root helper's `ok zigbee_tcp: EZSP on tcp:6638`, plus who may connect and the ZHA / Zigbee2MQTT settings |
| text `Radio allowed clients` | `radio_allowed_clients` | who may reach the radio's ports besides Home Assistant: addresses or CIDRs, `any`, or empty = Home Assistant only ([radio-guide.md](radio-guide.md#who-may-connect)) |
| select `Zigbee stack` | `zigbee_stack` | `Aqara 7.3.3 (EZSP 12, ZHA)` (default) or `Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)` ([radio.md](radio.md#zigbee-stack-aqara-or-silicon-labs-helper-v4)); encrypted connection only, needs helper v4 |
| text_sensor `Radio helper` | `radio_helper` | diagnostic; which root helper runs (boot image or radio bundle, version) and whether the boot image needs updating |
| text_sensor `Radio firmware` | `radio_firmware` | diagnostic; the radio's firmware version, flash progress or result |
| select `Radio firmware image` | `radio_firmware_image` | `Keep current`, `Aqara stock image`, `Image from the radio bundle`; arms the choice for 2 minutes ([radio.md](radio.md#radio-firmware-updates-helper-v4)); encrypted connection only |
| button `Flash radio firmware` | `radio_firmware_flash` | flashes the armed image; encrypted connection only |
| button `Join Zigbee network` / `Leave Zigbee network` | `zigbee_router_join` / `zigbee_router_leave` | Zigbee router mode ([radio.md](radio.md#zigbee-router)) |
| button `Leave Thread network` | `thread_leave` | forgets the Thread dataset and router settings, in any mode; encrypted connection only, needs helper v4 |
| button `Join Home Assistant's Thread network` | `thread_join_ha` | gets the preferred Thread network's dataset from Home Assistant and switches to Thread router; if the panel's HA user isn't an administrator, the panel shows a QR code for a one-off administrator sign-in ([radio-guide.md](radio-guide.md#repeater-modes-experimental)); encrypted connection only |
| text_sensor `Thread join` | `thread_join` | diagnostic; how the last join went (waiting for the QR code, joining `<network>`, or why not) |
| text `Thread dataset` | `thread_dataset` | write-only (always reads empty): the active dataset hex for the Thread router mode; whitespace in a paste (spaces, line breaks, no-break or zero-width spaces) is accepted and dropped; never logged |
| text_sensor `IP address` | `ip_address` | diagnostic |
| text `Time zone` | `time_zone` | an Olson id such as `Europe/Berlin`, kept by the root helper across reboots ([install.md](install.md#time-zone)); encrypted connection only |
| text_sensor `Time zone status` | `time_zone_status` | diagnostic; whether the zone is being kept |
| text_sensor `App version` | `app_version` | diagnostic |
| update `App update` (firmware) | `app_update` | the latest GitHub release vs this app; install from HA ([app updates](#app-updates)) |
| text_sensor `Update status` | `update_status` | diagnostic; e.g. `up to date (1.2.0)`, `1.3.0 available`, `Downloading 1.3.0: 42%`, `Install of 1.3.0 failed: …` |
| text_sensor `Home Assistant URL` | `home_assistant_url` | diagnostic; the HA the panel uses: the origin of a configured dashboard URL, else the discovered one ([onboarding.md](onboarding.md)) |
| text_sensor `Signed in` | `signed_in` | diagnostic; `yes` / `no`: whether a QR sign-in refresh token is stored (never the token) |
| button `Sign out` | `sign_out` | revokes the refresh token at HA (`/auth/revoke`), deletes it, shows the sign-in screen |

Plus `Block Aqara cloud` and `Aqara cloud daemon` ([below](#aqara-cloud)), the wall base entities, and the entities HA adds itself for a voice device: `assist_satellite.s1_panel_assist_satellite`,
*Assist pipeline* and *Wake word* selects (two of each), *Finished speaking detection*.

### Panes on and off

Each pane has a config switch that turns it on or off while the panel runs (#67), with two
exceptions: the radio, whose on/off is `Radio mode` (`Off`), and the timers, which come with voice and
have no switch of their own. The settings menu (the switch at the top of the pane's page), setup's
Panes step and the adb extras set the same setting. A pane that is off keeps its entities, with no
state where it has none to give, so the entity list doesn't change. Each switch is a config entity,
off in Home Assistant until you turn it on on the device page
([Categories and defaults](#categories-and-defaults)): people set them once. Only an encrypted
connection with the panel's key may change one, as for `Time zone`: turned on, a pane can open the
microphone or a port.

| Entity | object_id | Pane | adb extra | Enabled in HA by default |
|---|---|---|---|---|
| switch `Voice` | `voice` | the voice assistant and timers | `voice_enabled` | no |
| switch `Music Assistant player (Sendspin)` | `sendspin` | Sendspin (the same entity as before) | `sendspin_enabled` | no |
| switch `Wall base` | `wall_base` | the wall base driver; off closes the port, and Aqara's daemon may run again unless `Block Aqara cloud` is on | `wall_base_enabled` | no |
| switch `Keep time zone` | `keep_time_zone` | the time zone; off sets the zone to keep to `(off)`, so the root helper keeps none and adopts none ([radio.md](radio.md#root-helper-contract-v4)), and on keeps the same zone again. *Time zone status* says `off` only once the helper has let go | `time_zone_enabled` | no |
| switch `Diagnostics` | `diagnostics` | the diagnostic sensors; off stops the sampling | `diagnostics_enabled` | no |
| switch `Bluetooth proxy` | `bluetooth_proxy` | the Bluetooth proxy; **off by default**. On or off changes what the device info offers, so Home Assistant reconnects | `bluetooth_proxy_enabled` | no |

The radio's on/off is `Radio mode` (`Off`). Voice on or off changes the voice features in the device
info, so the panel asks Home Assistant to reconnect, as it does when the entity list changes. For those
few seconds every entity of the panel is unavailable (on the dev panel, 2026-11-14: 2 to 8 s, the screen,
presence and speaker together). Then:

- The **speaker** (`media_player`) is the panel's, not voice's: it stays, and Home Assistant can play on
  it with Voice off.
- The **Assist satellite** and HA's own voice entities (*Assist pipeline*, *Wake word*, *Finished
  speaking detection*) go away while Voice is off, because the panel has no voice features then. They
  come back when Voice is on.

### Bluetooth proxy

The Bluetooth proxy pane (`dev.glassworks.bluetoothproxy`, #121) makes the panel an ESPHome Bluetooth
proxy. It is off by default. Turn it on in the panel's settings, on setup's Panes step, or with the
*Bluetooth proxy* switch. Home Assistant then finds it by itself under *Settings › Devices &
services › Bluetooth*, and hears the BLE sensors near the panel through it. Nothing is added to HA.

- **Listening only.** It forwards advertisements, which covers thermometers, plant sensors and tags.
  Devices that need a connection (locks) need an ESP32 proxy for now.
- **It scans only while Home Assistant listens**, full time then. On the S1 that cost nothing
  measurable: Wi-Fi on 5 GHz kept its throughput and Sendspin had no underruns (2026-11-14, #121).
- *Bluetooth advertisements* is how many it forwarded in the last full minute. *Bluetooth proxy
  status* is `Listening`, `Waiting for Home Assistant`, `Waiting for the panel's key` (a client was
  refused for want of the key), `Bluetooth off`, `Location off`,
  `No permission`, `Retrying` or `No Bluetooth LE`. Both are diagnostic and off in HA until you turn
  them on there; the panel's settings page shows both anyway. Both are unavailable while the pane is off.
- Android hands Bluetooth results to apps only while **location** is on. The settings page offers to
  turn Bluetooth and location on when they are off.
- The panel never logs or keeps the addresses it hears. They go only to Home Assistant, and only
  over an encrypted connection with the panel's key: Home Assistant 2026.11 and later sets it when
  you add the panel. Without a key, the proxy answers nobody, so no one else on the LAN can listen.

How it works, and Android's limits on scanning, are in the
[bluetooth-proxy library](../app/libs/bluetooth-proxy/README.md).

### Diagnostics

The diagnostics pane (`dev.glassworks.diagnostics`) shows the panel's health. All of its entities
are diagnostic, so they sit in the device page's *Diagnostic* section and stay off dashboards. Only
the four marked *on* are enabled when HA adds the panel. Turn the others on from the device page.

The pane samples once a minute. It sends a value only when it moved by at least its band since the
last value it sent, so a quiet panel sends almost nothing. It reads files in `/proc` and `/sys` and
asks Android. It runs no shell commands and needs no root.

| Entity | object_id | Default | Band | Source |
|---|---|---|---|---|
| sensor `CPU usage` (%) | `cpu_usage` | on | 1 % | `/proc/stat`, all cores, busy share over the last minute |
| sensor `App CPU usage` (%) | `app_cpu_usage` | off | 0.5 % | `/proc/<pid>/stat` of every app process (Gecko's too), as a share of all cores |
| sensor `CPU temperature` (°C) | `cpu_temperature` | on | 0.5 °C | the frame's thermal zone; on the S1 `cpu_thermal_zone`. No entity on a frame without one |
| sensor `RAM available` (MiB) | `memory_available` | off | 8 MiB | `ActivityManager.MemoryInfo.availMem` |
| sensor `App RAM` (MiB) | `app_memory` | off | 4 MiB | PSS of every app process, from `/proc/<pid>/smaps_rollup`; unknown for a minute if one of them can't be read |
| sensor `Storage free` (GiB) | `storage_free` | off | 0.05 GiB | `StatFs` on `/data` |
| sensor `Wi-Fi signal` (dBm) | `wifi_signal` | on | 2 dB | `WifiManager`; unknown while Wi-Fi is down |
| sensor `Wi-Fi link speed` (Mbit/s) | `wifi_link_speed` | off | 1 Mbit/s | `WifiManager` |
| sensor `Uptime` (timestamp) | `uptime` | on | 120 s | when the panel booted; HA shows "3 hours ago" |
| sensor `App uptime` (timestamp) | `app_uptime` | off | 120 s | when the app process started |
| sensor `Web content processes` | `web_content_processes` | off | 1 | the engine's content processes (Gecko's tabs) |
| text_sensor `Last restart reason` | `last_restart_reason` | off | | why the app's process last ended, e.g. `crash`, `low memory`, `app updated`; Android 11 and later. Always `unknown` on the S1 (Android 10) |

- CPU usage and App CPU usage are unknown for the first minute after a start: they need two samples.
- CPU usage counts I/O wait as idle. `top` shows `iow` apart from `idle`, so a busy share worked out from `top`'s `idle` column counts I/O wait as busy and reads higher.
- The timestamps are accurate to about a minute, because ESPHome sends sensor values as 32-bit floats.
- The pane logs its own cost under `S1ESPHome` (`diagnostics: sample N took X ms CPU`) for the
  first three samples and then once an hour.

### Wall base entities

Built from the detected base, through the app's own UART driver (default) or the stock LocalService
([frames/aqara-s1-plus/research/bases.md](../frames/aqara-s1-plus/research/bases.md#implementation-s1-kiosk)); both backends produce the same entities.
Only *Base* is always present; the rest exist only while a matching base is detected.

| Entity | object_id | When | What it does |
|---|---|---|---|
| text_sensor `Base` (diagnostic) | `base` | always | `none`, `switch (2ch)`, `ethernet`, `unknown (0xNN)` (`0x7f` = UART: an unsupported model answered), or `unavailable` (port/LocalService not usable) |
| text_sensor `Base firmware` (diagnostic) | `base_firmware` | any base | `8.0.2022`, raw |
| switch `Base relay N` | `base_relay_1`..`3` | switch base, per channel | writes `4.N.85` = 0/1. UART: the ack (else a re-read) confirms it; LocalService: re-reads |
| sensor `Base power` (W, power, measurement) | `base_power` | switch base | `0.12.85` |
| sensor `Base energy` (total_increasing, no unit) | `base_energy` | switch base | `0.13.85`. UART: integrated in the app from power, Wh (as libha_bracket does); LocalService: ha_master's value |
| text_sensor `Base protection` (diagnostic) | `base_protection` | switch base | `8.0.2014`: `normal`, `over_temperature`, `over_load`, `over_temperature_error`, `over_load_error`, `over_temperature_removed`, `over_load_removed` |
| event `Base button N` (button) | `base_button_1`..`3` | switch base, per channel | `13.N.85` clicks (LocalService only; the UART protocol has no click frames); event types `single`, `double`, `triple`, `long`, `release`, `unknown` (mapping unverified, raw value logged) |

On a panel added before #63 the entity ids are `switch.s1_panel_base_relay_1`,
`event.s1_panel_base_button_1`, and so on (`glassworks_panel_*` on one added later, see [Names](#names)). The names are fixed and don't follow the channel names
configured in the Aqara app: HA 2026.11 derives the ESPHome unique id from the entity *name*
(aioesphomeapi `build_unique_id` v3), so a rename on the Aqara side would orphan the HA entity.
Rename them in HA.

When the detected base changes (none ↔ switch, channel count) and stays changed for 5 s, the panel
rebuilds its entity list and sends `DisconnectRequest` to every API client. HA reconnects (after
its short "expected disconnect" cool-down), lists the entities again and adds the new ones. Removed
entities stay in HA's registry, shown as unavailable/removed. The last layout is persisted, so
restarting the app with the same base doesn't trigger this.

The **Dashboard** select uses the same mechanism: its options are only sent in the entity listing,
so a changed dashboard list triggers the same rebuild + `DisconnectRequest` (same object_id, so the
entity keeps its key and its id in HA). The list it was built from is persisted (`dashboards`).

Two hooks feed the setup screen ([onboarding.md](onboarding.md#setup-screen-)): a Hello from a
client whose `client_info` starts with "Home Assistant", and `SubscribeStatesRequest` from one
(`ApiServer.onStatesSubscribed`). HA only subscribes after it has added the panel, so that is
persisted as `ha_seen`.

Kiosk actions are applied in the running activity exactly like the `adb am start` extras
(`GlassworksRuntime.commandSink`). State changes made on the panel (settings dialog, `adb` extras,
sensors) are pushed to HA right away (pref listener + `ScreenPolicy` listeners), with a 5 s
poll as a safety net.

### App updates

`update/UpdateManager.kt`; releases and signing in [releasing.md](releasing.md).

- **State:** `current_version` is the app's versionName and `title` is `S1 Kiosk`. `missing_state`
  stays set until the first successful check, and until then `latest_version` = `current_version`:
  Home Assistant 2026.11 ignores `missing_state` for update entities, and an empty `latest_version`
  can't be compared, which its `UpdateEntity.has_update` counts as an update (the entity turned on with
  a blank version after an offline start). After a check: `latest_version`, `release_url`, and
  `release_summary` (the release notes, cut to HA's 255 characters). A status line goes on top of
  the summary when there is one, e.g. a failed install.
- **Check:** `GET https://api.github.com/repos/GlassworksHQ/glassworks/releases?per_page=30`, then
  the newest release whose tag is `v<version>` or `aqara-s1-plus-v<version>`. Other frames' and
  libraries' releases in the same repo are skipped; a refused newer release is reported, and the
  newest acceptable one is still offered.
  - When: 30 s after start, when a network comes up, every 24 h, and on HA's check command
    (`homeassistant.update_entity`, at most once a minute).
  - ETag'd, so an unchanged answer costs no rate limit.
  - Failures back off from 15 min to 6 h, or until GitHub's rate-limit reset. They are logged once
    per new reason, not on every retry. A check that failed because the panel had no network
    (`UnknownHostException`, no route, timeouts; not TLS or HTTP errors) is retried 10 s after Wi-Fi
    is back (available or validated; callbacks debounced, at most one such retry per 30 s) instead of
    after its backoff.
  - Ignored: drafts, pre-releases (GitHub's flag or a `-rc` tag), and releases without both
    the APK for one of the device's ABIs and its `.sha256`. The S1 (`armeabi-v7a`) takes
    `s1-kiosk-<version>.apk`; an `arm64-v8a` device takes `s1-kiosk-arm64-v8a-<version>.apk`
    ([releasing.md](releasing.md#abis)). Assets must download from github.com over HTTPS.
- **Install:**
  1. Download the `.sha256`, then the APK over HTTPS into `files/update/`. `in_progress` is set and
     `progress` goes 0-100 %.
  2. Check the SHA-256 against the `.sha256` file and against GitHub's own asset digest.
  3. `PackageManager.getPackageArchiveInfo(GET_SIGNING_CERTIFICATES | GET_SIGNATURES)` must give
     package `sh.edm.s1kiosk`, the release's version and versionCode, and the installed app's signing
     certificate. Otherwise the install is refused with the reason. Both flags are needed: on Android
     10, `GET_SIGNING_CERTIFICATES` alone leaves `signingInfo` empty for a downloaded APK, so up to
     0.4.0 every update was refused as "the APK is not signed". **Fixed in 0.4.1**; because the bug is
     in the installed version, panels on 0.4.0 or older update once by hand
     ([install.md](install.md#updating)).
  4. The verified file becomes `files/update/pending.apk`, and the root update service installs it
     ([frames/aqara-s1-plus/research/root.md](../frames/aqara-s1-plus/research/root.md)). On success the app is replaced and restarted, and the
     new version reports the result in *Update status*.
  5. Boot images from before the service (`sys.s1kiosk.update.version` unset) use a
     PackageInstaller session instead. Android then asks for confirmation on the panel's screen,
     which the summary says while an update waits.
  6. Any failure, or a phase stuck for 30 min (download) or 10 min (verify/install), ends with
     `in_progress` off and the reason on top of the summary.
- A debug build can't update to a release: the keys differ, and the entity says so.

## Voice assistant

Same model as an ESPHome `voice_assistant` with a media player (Voice PE): audio goes over the API
connection, TTS is played from a URL. Checked against esphome `voice_assistant.cpp`,
`api_connection.cpp`, aioesphomeapi `client.py` / `api.proto` (v46.25.0, the version HA
2026.11.0 pins) and HA `components/esphome/assist_satellite.py` (2026.11.0).

| Direction | Message | What we do |
|---|---|---|
| HA → panel | `SubscribeVoiceAssistantRequest` | This connection receives voice messages. |
| HA → panel | `VoiceAssistantConfigurationRequest` | Reply with the four microWakeWord words (`okay_nabu` → "Okay Nabu", …), then any legacy openWakeWord model already on the panel (`hey_jarvis_v0.1` → "Hey Jarvis (openWakeWord)"), the active one, max 1. |
| HA → panel | `VoiceAssistantSetConfiguration` | Switch the wake word live, persist its id (`wake_word`); empty list = no wake word. |
| panel → HA | `VoiceAssistantRequest{start, flags=USE_VAD, wake_word_phrase, conversation_id}` | On a local detection. `USE_WAKE_WORD` is not set, so HA starts at STT. |
| HA → panel | `VoiceAssistantResponse{port=0}` | Start streaming; audio captured since the wake word (≤ 2 s) is sent first. |
| panel → HA | `VoiceAssistantAudio{data}` | 16 kHz s16le mono, 2560-byte (80 ms) chunks. |
| HA → panel | `RUN_START{url}` | Remember the streaming TTS URL. |
| HA → panel | `STT_VAD_END` / `STT_END` | Stop streaming → *Thinking*. |
| HA → panel | `INTENT_PROGRESS{tts_start_streaming=1}` | Start playing the RUN_START URL early (streaming TTS). |
| HA → panel | `INTENT_END{conversation_id, continue_conversation}` | Keep the conversation id (5 min). |
| HA → panel | `TTS_END{url}` | Play the URL (unless already streaming). |
| HA → panel | `RUN_END` | Back to idle if nothing is playing. |
| HA → panel | `ERROR{code,message}` | Stop (`VoiceAssistantRequest{start=false}`) and go idle; wake-word codes are ignored like ESPHome. |
| panel → HA | `VoiceAssistantAnnounceFinished{success}` | After the TTS / announcement finished playing; HA marks the response finished. If `continue_conversation` was set, a new run starts without a wake word. |
| HA → panel | `VoiceAssistantAnnounceRequest{media_id, text, preannounce_media_id, start_conversation}` | Play the pre-announce chime then the media, reply `AnnounceFinished`, optionally start a conversation. |
| HA → panel | `VoiceAssistantTimerEventResponse` | Started / updated / paused / cancelled / finished: shown on screen (see [Timers on screen](#timers-on-screen)). On *finished* the screen wakes and a chime plays 3×, 3 s apart. |

Playback uses **Media3 ExoPlayer** (`ExoAudioPlayer`), which streams mp3/flac/aac/ogg/wav over HTTP
in-process, including endless streams. Android's `MediaPlayer` is deliberately avoided: on this
panel it is backed by Allwinner's CedarX player inside `mediaserver`, whose HTTP code aborts
(`FORTIFY: FD_SET: file descriptor 1024 >= FD_SETSIZE`, `libcdx_base CdxSockAsynConnect`) and kills
`mediaserver` on the first stream. Because the media
player entity declares mp3 as its announcement format, HA asks the TTS engine for mp3 and routes
other media through its ffmpeg proxy as mp3. URLs from HA are absolute (HA's internal URL);
relative ones are resolved against the dashboard URL. Music on the media player is paused while the
assistant listens or talks.

The microphone has one consumer, `VoiceEngine`: every 80 ms frame goes through the wake word model
(always, so its buffers stay fresh), and during a run the same frame is handed to the API stream.
Detections only count while idle and 3 s after the previous one. Safety caps: 15 s maximum stream,
10 s for HA to answer a start request, 60 s waiting for a response.

A detection reports the word of the model that fired: while a newly picked word loads, the old one
keeps listening as itself. If the new word can't load (no network for its model), the old model
stops, the panel shows the error, and it retries every 30 s.

**What HA does with the wake word select.** HA's select (`homeassistant/components/esphome/select.py`,
2026.11.1) restores its last state, but on every connection it only reads the panel's configuration
and shows the active word; it never sends its restored value back. Only picking a word (a person, an
automation, a scene or a script calling `select.select_option`) sends one. Options are matched by
name, so a restored "Hey Jarvis" from 0.4 now means the microWakeWord `hey_jarvis`; the legacy ones
are named "… (openWakeWord)" so the two never share a name.

**Updating from 0.4 (openWakeWord).** The word 0.4 saved (`wake_model`, e.g. `hey_jarvis_v0.1.tflite`)
moves to the microWakeWord model of the same word (`wake_word` = `hey_jarvis`) with no user action. Its
openWakeWord threshold doesn't carry over, because the scales differ: the word starts at its model's
cutoff. `wake_model` and `wake_threshold` are only read, so a downgrade to 0.4 finds its own settings.
A custom openWakeWord model stays on openWakeWord. The openWakeWord models already on the panel stay
selectable for this release as "<word> (openWakeWord)"; a later release removes them and their
CC BY-NC-SA models.

### microWakeWord parameters

The engine runs ESPHome's microWakeWord v2 streaming models (`MicroWakeWord.kt`), as
pymicro-wakeword and ESPHome's `micro_wake_word` do:

| Parameter | Value |
|---|---|
| Front end | `MicroFrontend.kt`, a port of TFLite Micro's audio microfrontend: 30 ms Hann window every 10 ms, 512-point 16-bit fixed-point FFT (kissfft), 40 mel channels 125–7500 Hz, noise reduction (smoothing bits 10, even 0.025, odd 0.06, min 0.05), PCAN (strength 0.95, offset 80, gain bits 21), log scale (shift 6) |
| Model input | int8 `[1, 3, 40]`: three 10 ms feature rows, so one inference per 30 ms. `round(value × 0.0390625 / scale + zero_point)`, half to even, clamped to int8 (scale 0.10196, zero point −128 for all four) |
| Model output | uint8 `[1, 1]`, × 1/256; the model keeps its own streaming state (resource variables) |
| Score | mean of the last `sliding_window_size` outputs (5 for all four), 0 until there are 5; per 80 ms microphone frame, the best mean in that frame |
| Detection | score ≥ the threshold entity (default: the manifest's `probability_cutoff`), then 3 s before the next one. This is pymicro-wakeword's comparison, not ESPHome's: ESPHome fires when the sum of the 5 raw outputs is above `int(cutoff × 255) × 5`, so at the same cutoff the panel needs about 1.1 more per output (sum 1242 instead of 1236 at 0.97, 1216/1211 at 0.95, 1152/1146 at 0.9), about 0.5% stricter than Voice PE |
| Models | `okay_nabu`, `hey_jarvis`, `alexa`, `hey_mycroft` from esphome/micro-wake-word-models `models/v2` at `40ff33f`, SHA-256 in `WakeWords.kt` |

The research note is [research/microwakeword.md](research/microwakeword.md).

### Timers on screen

Timers live in Home Assistant; the panel only shows what HA's timer events say, so pausing,
cancelling or adding time is done by voice ("pause the pasta timer"). Code: `TimerOverlay`,
`TimerBoard`, `timers/`.

- **Running timers** are small glass orbs at the bottom right, one per timer, soonest nearest the
  corner: the time left inside, an amber ring that runs down, the name underneath ("TIMER" if it has
  none). In the last 10 seconds the orb glows and counts down in large digits, with a ripple each
  second. A paused timer shows a pause sign and a grey ring.
- **Move:** drag the timers anywhere; they snap to the nearest of eight spots (corners, the middle of
  each edge), 16 dp from the edges, and later timers appear there too (stored in the app's `timers`
  preferences).
- **Hide:** tap a timer, then *Hide timers*. They tuck into the nearest side (or the top/bottom edge
  for the middle spots) as half an orb whose amber arc still shows the time left; tap it to bring
  them back. Hidden timers come back by themselves for their last 10 seconds, and when a new timer is
  set; when the last one ends, hiding is forgotten. Touches anywhere else go to the dashboard.
- **Set by voice:** a timer set while the voice overlay is up comes out of the assistant's orb as the
  overlay closes.
- **Timer done** is full screen, like the voice overlay: the amber orb with a ripple per chime, the
  timer's name and length ("2 timers done" when several finish together) and the timers still
  running. A tap anywhere dismisses it and stops the remaining chimes; otherwise it closes 60 s after
  the last timer finished. It shows even when the timers were hidden.

### openWakeWord parameters (legacy)

Kept for one release as the fallback. The code is a port of `AudioFeatures` (`openwakeword/utils.py`) and `Model.predict`
(`openwakeword/model.py`), fed exact 1280-sample frames:

| Parameter | Value |
|---|---|
| Frame | 1280 samples (80 ms) of 16 kHz int16, passed to the mel model as float32 *without* normalisation |
| Mel input | last `1280 + 160*3 = 1760` samples (only 1280 on the very first frame, like upstream) → 8 frames × 32 bins (5 for 1280) |
| Mel transform | `x / 10 + 2` |
| Mel buffer | starts as `ones(76, 32)`, capped at `10*97 = 970` frames |
| Embedding | last 76 mel frames, `[1, 76, 32, 1]` → 96 floats, one per 80 ms frame |
| Feature buffer | starts with embeddings of 4 s of uniform random int16 noise in [-1000, 1000) (76-frame windows, step 8 → 41 rows), capped at 120 rows |
| Wake model input | last 16 rows → `[1, 16, 96]` (read from the model's input shape), output `[1, 1]` |
| Warm-up | the first 5 scores are forced to 0 (as in `Model.predict`) |
| Detection | score ≥ `threshold` (0.5), then `refractoryMs` (3 s) before the next one |

TFLite Java detail: the `Interpreter` constructor allocates tensors at the model's *stored* input
shape. `melspectrogram.tflite` stores `[1, 1]` (signature `[-1, -1]`), and at that size its
CONV_2D fails to prepare, so the constructor throws. Upstream Python avoids this by resizing
before it allocates. We patch the stored shape in the FlatBuffer to `[1, 1760]` in memory before
building the interpreter (`TfliteShapePatch`), then `resizeInput` as upstream does. We checked
with the Python TFLite runtime that the patched model gives bit-identical output at 1280, 1760 and
64000 samples.

## Wire protocol notes

- **Keepalive** (as ESPHome's `api_connection.cpp`): when a client has sent nothing for 60 s, the
  panel sends it a `PingRequest`; only when nothing at all arrives for 150 s after that is it dropped.
  Any frame from the client counts as traffic. A plain read timeout doesn't work: aioesphomeapi
  (46.25.0, `connection.py _async_send_keep_alive`) only pings when it has *received* nothing for 20 s,
  so a device that keeps sending states hears nothing from Home Assistant. Until 2026-11-10 the server
  closed after 90 s without input, so HA reconnected every 90 s (panel log: "idle for 90 s; closing",
  then a new hello 70 ms later). `EspHomeKeepaliveTest` checks both the raw ping/close timing and a real
  aioesphomeapi client staying connected while states stream (with `ESPHOME_PYTHON`).

- **Unprovisioned**: the first byte picks the transport: `0x00` plaintext, `0x01` Noise with the
  all-zeros PSK (esphome `api_frame_helper_plaintext.cpp` hand-off). `NoiseEncryptionSetKeyRequest`
  (124, base64 text of 32 bytes) → `NoiseEncryptionSetKeyResponse` (125).
- **Plaintext framing**: `0x00`, varint payload length, varint message type, protobuf payload. A
  Noise client gets `0x00 "Bad indicator byte"` back (aioesphomeapi reports "device is using
  plaintext").
- **Noise**: frames are `0x01` + 16-bit length. ClientHello (empty) → ServerHello
  `01 "s1-panel" 00 <mac hex> 00` → NNpsk0 handshake with prologue `"NoiseAPIInit" 00 00`
  (ESPHome appends the ClientHello's length and contents). A wrong key gets
  `01 "Handshake MAC failure"` (HA shows "invalid encryption key"); a plaintext client gets a Noise
  reject frame (HA shows "requires encryption"). X25519 and ChaCha20-Poly1305 come from BouncyCastle
  (`bcprov-jdk18on`, lightweight API).
- Protobuf is hand-written (`Proto.kt`, `Entities.kt`, `ApiServer.kt`), with field numbers from
  aioesphomeapi `api.proto`. Unknown messages are ignored, as ESPHome does.
- **Event entities**: `ListEntitiesEventResponse` (107: object_id 1, key 2, name 3, icon 5,
  disabled_by_default 6, entity_category 7, device_class 8, event_types 9) and `EventResponse`
  (108: key 1, event_type 2). Events have no state: nothing is sent on `SubscribeStates`, one
  `EventResponse` per occurrence (`ApiServer.sendEvent`).
- **Update entities**: `ListEntitiesUpdateResponse` (116: object_id 1, key 2, name 3, icon 5,
  disabled_by_default 6, entity_category 7, device_class 8), `UpdateStateResponse` (117: key 1,
  missing_state 2, in_progress 3, has_progress 4, progress 5 (float, percent), current_version 6,
  latest_version 7, title 8, release_summary 9, release_url 10) and `UpdateCommandRequest` (118:
  key 1, command 2 = `UpdateCommand` NONE 0 / INSTALL 1 / CHECK 2). From aioesphomeapi 46.25.0's
  `api_pb2` descriptors.
- **Entity set changes**: `ApiServer.setEntities` swaps the list and asks every client to
  disconnect (`DisconnectRequest`, closed after the ack or 2 s).
- **Home Assistant's time zone** (#212): once an authenticated client subscribes to states, the
  panel sends `GetTimeRequest` (36). Home Assistant answers with `GetTimeResponse` (37), and its
  `timezone` (field 2) is a POSIX `TZ` rule, not an IANA id: aioesphomeapi 46.25.0 sent
  `Europe/Madrid` as `CET-1CEST,M3.5.0,M10.5.0/3` (verified 2026-11-13, `EspHomeApiTest`). The time
  zone page matches the rule to the zones the panel knows that keep the same clock all year
  (`PosixTimeZone`). A plaintext client's answer is ignored. The panel never sets its clock from it.
- **The wake word, picked on the panel**: Home Assistant reads the satellite's wake words
  (`VoiceAssistantConfigurationRequest`) only when it sets the satellite up, which it does on every
  connect: `esphome/manager.py` `on_disconnect` unloads the `assist_satellite` platform (HA core,
  read 2026-11-13). So a wake word picked on the panel asks HA to reconnect, as an entity set change
  does. Once is not enough: HA's wake word select keeps its own pick unless that pick reads
  `no_wake_word`, and a pick that isn't active turns into `no_wake_word`
  (`esphome/select.py` `async_satellite_config_updated`, read 2026-11-14). After one reconnect it read
  `no_wake_word` (verified on the panel, 2026-11-14). So the panel asks again about 2 s after HA's
  first configuration read, and HA's select then takes the panel's word (`AssistPaneTest`).
- aioesphomeapi sends `SubscribeVoiceAssistantRequest.flags = 4` for API audio while `api.proto`
  defines `VOICE_ASSISTANT_SUBSCRIBE_API_AUDIO = 1`; like ESPHome, the panel ignores the flags and
  uses `VoiceAssistantResponse.port` (0 = API audio).

## Tests (host)

```sh
cd app
python3 -m venv /tmp/esphome-venv && /tmp/esphome-venv/bin/pip install aioesphomeapi==46.25.0
MODELS_CACHE=/tmp/oww-models ../tools/voice/fetch-models.sh --no-push
ESPHOME_PYTHON=/tmp/esphome-venv/bin/python OWW_MODELS_DIR=/tmp/oww-models ./gradlew :kiosk:testDebugUnitTest
```

- `EspHomeApiTest`: runs the API server (plaintext and Noise) on the JVM against the real
  aioesphomeapi client (`src/test/resources/esphome_client.py` plays HA): hello/API version,
  device info, device capabilities, list entities, subscribe states, a command for every entity
  type, voice assistant subscribe, wake word configuration get/set, a full pipeline run (wake →
  start → mic audio → events → TTS playback → `AnnounceFinished`), an announcement with
  pre-announce, a timer event, and Noise with the right key, a wrong key, no key, and a Noise
  client against the plaintext server, an event entity (list + fired event), and an entity set
  change (server-initiated disconnect, reconnect lists the new entity), and HA 2026.11's dynamic
  key provisioning (keyless connect, zero-PSK `noise_encryption_set_key`, the keyless session is
  dropped, plaintext and zero PSK are refused, reconnect with the key, bad/same/empty keys, key
  survives a restart), and the Sendspin capability (client id) and pairing token (`OK` over Noise,
  `ENCRYPTION_REQUIRED` over plaintext), and the update entity (missing state, check and install
  commands, release fields, progress). Skipped without
  `ESPHOME_PYTHON`. Also passes with
  aioesphomeapi 46.28.0.
- `NoiseKeysTest`: the key state machine. `OAuthTest` / `SignInServerTest`: authorize URL, state
  store, token replies, access token cache, external-auth URL flag, and `/signin` → `/callback`
  over sockets against a fake HA token endpoint. `HaUrlsTest`: mDNS TXT parsing, manifest probe.
- `BaseProtocolTest` / `BaseTrackerTest`: wall base dock class decoding, mask/count to channels,
  LocalService reply/event parsing (all known shapes, foreign DIDs, garbage), DID redaction,
  debounce (no rebuild loop on flapping), click de-duplication, entity ids per layout.
- `WallBasePaneTest`: a new layout asks for an entity rebuild and saves the layout; a button press
  fires the event entity Home Assistant has. `BaseLayoutEntitiesPinTest`: the full entity list
  for each kind of base, against `panel-entities-base.golden.txt`.
- `BracketProtocolTest` / `BracketSessionTest`: the wall base UART protocol. CRC-16/MODBUS, frames
  byte-identical to Aqara's `bracket_cli` output, resource ids/types, a resyncing framer (noise,
  bad/swapped CRC), identity → dock class, protection mapping, termios verification; the handshake
  sequence, retries (200 ms, 3 sends, new seq), unsupported model, reports/energy, an unacked write
  → read, and silence → probe → removal. `UartBaseLinkEmulatorTest`: the real `UartBaseLink` over
  TCP against `tools/bracket/base_emulator.py` (needs only python3; uses `BRACKET_PYTHON` /
  `ESPHOME_PYTHON` / `python3`).
- `update/*Test`: version comparison and versionCode, GitHub release parsing (fixture shaped like
  the real `releases/latest` response), asset selection and URL checks, `.sha256` parsing, the
  pre-install APK checks, the root service status, check scheduling and rate limits, and the update
  entity's state machine.
- `VoiceAssistantControllerTest`: pre-roll, "never mind" runs, pipeline errors, the max-stream
  cap, continue-conversation.
- `MicroFrontendTest`: compares `MicroFrontend` with pymicro-features (the TFLite Micro frontend's C
  code) on synthesised speech and a synthetic signal (fixtures made by `tools/voice/mww_reference.py`).
- `MicroDetectorTest`: strides, quantization and the sliding window. `MicroWakeWordClipTest` runs
  the four real models (LiteRT) behind the Kotlin pipeline over the clip: each fires in its own word
  only. Skipped without `MWW_PYTHON`; CI's `wake-word` job runs it with LiteRT from
  `app/kiosk/mww-test-requirements.txt` and fails if it skipped.
- `WakeWordCatalogTest`, `AssistPaneTest`: the words HA is offered, the 0.4 migration, the threshold.
- `OwwFeaturesTest`: compares the Kotlin feature buffering with openWakeWord's own
  `AudioFeatures` code, run with deterministic fake models (fixture made by
  `tools/voice/oww_reference.py`).
- `TfliteShapePatchTest`: patches the real `melspectrogram.tflite`. Skipped without
  `OWW_MODELS_DIR`.

## Licensing

- The ESPHome API and voice code is MIT, like the rest of the app. The openWakeWord feature pipeline is
  re-implemented from the Apache-2.0 upstream. `MicroFrontend.kt` is a port of TFLite Micro's audio
  microfrontend (Apache-2.0) and kissfft (BSD-3-Clause), and keeps those licences.
- **The microWakeWord models are Apache-2.0** (esphome/micro-wake-word-models). They are downloaded
  by the panel, not shipped.
- TensorFlow Lite (`org.tensorflow:tensorflow-lite:2.16.1`) is Apache-2.0.
- **The openWakeWord pre-trained models are CC BY-NC-SA 4.0** (non-commercial, share-alike).
  That's why they are **not bundled** in the APK or the repo. They are only used as the legacy
  fallback; `fetch-models.sh --openwakeword` downloads them. Don't ship them in a commercial product. Custom-trained models carry
  whatever license you give them.
- BouncyCastle (`org.bouncycastle:bcprov-jdk18on`) is MIT-style (Bouncy Castle licence).

## Limitations / known gaps

- **Verified on the panel (2026-11-09):** HA adds it (by IP; the panel sits on a separate VLAN so
  mDNS didn't reach HA), all entities report live values, `announce` with pre-announce chime plays,
  `media_player.play_media` plays and reports playing → idle. **Not yet verified:** real-speech
  wake word accuracy / mic gain, mDNS discovery on a flat network. Music Assistant playback has
  since been measured on the panel ([sendspin.md](sendspin.md#measured-on-the-panel)).
- The mDNS SRV target is the Android hostname, not `s1-panel.local`; HA uses the IP it discovered and
  replaces the name with `DeviceInfoResponse.name` once connected.
- No `SPEAKER` flag: TTS is fetched by URL, not streamed as PCM over the API.
- One wake word at a time (`max_active_wake_words = 1`); wake word models HA offers for download
  are ignored (the panel has the four official ones).
- No acoustic echo cancellation / barge-in: detections are ignored while listening or speaking.
- Shared microphone with GeckoView: if the dashboard's in-browser Assist uses the mic at the same
  time, one of the two captures may get silence. *Mute microphone* releases it.
- Music Assistant: the panel is a native, synced Sendspin player (`media_player.s1_panel`, see
  [sendspin.md](sendspin.md)), so MA's *Home Assistant* player provider is no longer needed for it.
  `media_player.s1_panel_speaker` stays for TTS, announcements and HA media; while it announces,
  the Sendspin output is muted locally.
- Chromecast: not possible. A Cast receiver must be a Google-certified device.
- Radio modes other than `Off` need the root helper from the boot-image patch ([radio.md](radio.md)).
- Entities marked "encrypted connection only" refuse changes from a plaintext connection (a panel that
  was never given a key).

### Aqara cloud

- switch `Block Aqara cloud` (`block_aqara_cloud`, default on): stops Aqara's cloud daemon `ha_master`.
  With the UART wall-base backend (default) it is always stopped; with the LocalService backend it is
  kept only while a switch base is attached (or before the base check has read the dock). With no base,
  blocking wins and it stays stopped. With the wall base pane off, only this switch counts. See [frames/aqara-s1-plus/research/debloat.md](../frames/aqara-s1-plus/research/debloat.md).
- text_sensor `Aqara cloud daemon` (`aqara_cloud`): `blocked`, `running`,
  `stopped (UART wall base backend owns /dev/ttyS3)`, `running (needed by the wall base, LocalService
  backend)`, or `error (…)`.
