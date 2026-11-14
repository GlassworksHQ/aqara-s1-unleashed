# Changelog

What changed in each S1 Kiosk release, for people running a panel. The first line of each version is
what Home Assistant shows on the update card. Releases are built from tags by
[`.github/workflows/release.yml`](.github/workflows/release.yml), which publishes the matching section
below as the release notes.

## [Unreleased]

### Added
- **Every pane's settings page shows what it is doing.** No page says "no settings of its own" any
  more. Diagnostics shows CPU, temperature, RAM, Wi-Fi, storage and uptime, with the last hour as a
  line. Wall base shows the base, its firmware, protection and power, and each relay with its switch
  and its button's last press. Time zone lets you pick a zone from a list, or plain UTC offsets, and
  keep Home Assistant's in one tap. Voice has the wake word, its threshold, mute, mic gain, the wake
  sound and the overlay. Music shows what plays and its volume. Radio shows its firmware and the root
  helper. A pane with nothing of its own lists its entities as Home Assistant shows them.

### Changed
- **Panes you turned off fold at the bottom of the list.** In the settings menu they sit under
  "Off · N"; open it, or a pane in it, to see them. An off pane's page says what being off means.
  Every pane shows its state after its name, Running, On, Off or Can't run, as the Manage page does,
  so every pane's name starts in line.
- **Turning a pane on or off from Home Assistant needs the panel's key.** The pane switches, the
  *Music Assistant player (Sendspin)* switch included, only answer an encrypted connection, as *Time
  zone* does. Home Assistant 2026.11 and later sets the key when you add the panel. On a panel without
  one, use the panel's settings.
- **The project moved to [GlassworksHQ/glassworks](https://github.com/GlassworksHQ/glassworks).**
  Panels now check for updates there directly. Older versions keep updating through GitHub's
  redirect, so nothing needs doing on your panel.
- **The wake word uses far less CPU.** The panel now listens with microWakeWord, the engine Home
  Assistant Voice PE uses, instead of openWakeWord. On the S1, the microphone's thread went from
  about 37% of a core to about 4%. Your wake word stays as it was (Hey Jarvis, Alexa or Hey
  Mycroft), and Okay Nabu is new. The panel downloads the new models itself once. *Wake word
  threshold* now means the new engine's cutoff: each word starts at its own (0.9 to 0.97), lower
  is more sensitive, and a threshold you set for openWakeWord doesn't carry over. The old
  openWakeWord words stay in the list as "(openWakeWord)" for this release.
- **Kiln's panel cards show a real Home Assistant dashboard.** Each device on kiln.glassworks.dev now
  shows the same made-up home, drawn by Home Assistant's own frontend and filling that device's
  screen: the clock and weather, nine rooms and areas with thermostats, lights, music and TV, two
  cameras, a shopping list, the alarm and the day's graphs. Before, it was a sketch with a fixed date. The
  Mirror's is portrait.
- **A new boot screen.** The power-on logo and the boot animation now show the Glassworks mark:
  three glass plates drop into place, the top one lights up from sky to violet, and "Glassworks" and
  "On Aqara S1 Plus" fade in. While the panel finishes starting, the stack floats and the top plate
  breathes. It reaches a panel when you run the installer again.
- **New connecting and setup screens.** They use the Glassworks look: the glass stack tells you what
  is happening. Light rises through it while the panel connects, the plates dim when there is no
  network, and the top one turns red and dashed when Home Assistant doesn't answer, with a countdown
  to the next try. The connecting screen starts on the boot animation's last frame and the stack
  settles from there, so power-on is one continuous picture. It stays until Home Assistant has drawn
  the dashboard, so Home Assistant's white loading page no longer flashes in between. Setup goes
  Wi-Fi, Home Assistant (add it, then sign in), Panes, Done. On Panes you choose what the panel
  does: voice, music and the Zigbee / Thread radio. A pane you turn on then asks what it needs: the
  radio asks Zigbee or Thread and the role, then shows what to do in Home Assistant, where a Thread
  router can join Home Assistant's Thread network. A step that is already done says so with
  Continue, and the list on the left opens a step you've done.
- **Settings apply as you change them.** The settings menu has the new look: one section at a time,
  each setting with its value under it. A switch takes effect when you tap it, and a value when you
  save it. There is no *Save & restart* any more; the few changes that need the app to restart (the
  dashboard's address, zoom, secure hosts) say so and offer *Restart now*. The list on the left is
  the same as setup's: Dashboard, Screen, then your panes (Voice, Music, Radio) under Panes, then
  Log and About. Each pane has its on/off at the top right of its page; turning the radio off asks
  first. The radio's roles are picked as in setup, Zigbee or Thread first. On the Screen page the
  presence range is a slider, and the idle times and the night window are picked, not typed. About
  shows the version; the screen and sensor line moved to Log.
- **The panel is called Glassworks Panel, in Home Assistant too.** A panel you already added keeps all
  its entity ids, so automations and dashboards keep working; Home Assistant shows the device under
  the new name after the update, unless you renamed it yourself. On Home Assistant 2025.12 or later,
  new entities take the device's name as Home Assistant shows it (`glassworks_panel`, or your own
  name), and from 2026.8 the area's name can come first. On older versions they still start with
  `s1_panel`.
- **New pages on your phone when you scan the panel's code.** Signing in and sharing your Thread
  network open a short page first that says what happens, then *Continue to Home Assistant*. They
  follow your phone's light or dark mode.
- **A shorter entity list in Home Assistant.** When Home Assistant adds a panel, it now turns on 15 of
  its 63 entities, down from 55: presence, light level, the screen, the speaker, screen mode, mute,
  reload and restart, app updates, IP address, radio status and four health values (plus a wall
  base's relays, buttons, power, energy and protection). Settings you set once, the radio's controls
  and the troubleshooting sensors are created turned off. To use one, turn it on from the panel's
  device page in Home Assistant; many settings are also in the panel's settings menu. A panel Home
  Assistant already has keeps its entities as they were; only *Base protection* moves to the
  Diagnostic section.

### Added
- **The panel can be a Bluetooth proxy for Home Assistant.** Turn on *Bluetooth proxy* in the
  panel's settings or on setup's Panes step, and Home Assistant finds it by itself and hears the
  Bluetooth thermometers, plant sensors and tags near the panel. It only listens: devices that need a
  connection, like locks, still need an ESP32 proxy. Off by default. It never logs or keeps the
  addresses it hears, and sends them only over an encrypted connection with the panel's key, which
  Home Assistant 2026.11 and later sets when you add the panel.
- **Setup's Panes step scrolls when there are more panes than fit.** Continue stays where it is,
  below the list, and every pane's name shows in full.
- **Notifications from Home Assistant on the panel.** Call `esphome.<panel>_notify` with a title, a
  message and the Companion app's fields, and the panel shows a card over the dashboard, with a
  camera's picture, up to three buttons and a sound. A button press fires `esphome.notification_action`
  in Home Assistant. A high or critical one wakes the screen, `url` opens a dashboard view full screen
  and comes back after the timeout, and *Do not disturb* holds everything but critical. Off until you
  turn it on, on the setup screen or in the settings. A doorbell blueprint is in the repo. Examples:
  docs/notifications.md.
- **Turn any pane off, and back on, without a restart.** Voice, music, the wall base, the time zone
  and diagnostics each have an on/off: at the top of the pane's page in the panel's settings, in
  Home Assistant (new switches Voice, Wall base, Keep time zone and Diagnostics, off on the device
  page until you turn them on there), and with adb. A pane that is off stops its work and comes
  back when you turn it on. Voice no longer restarts the app. The wall base now has its card on
  setup's Panes step. The radio's on/off stays its mode.
- **A Manage page for the panel, in your browser.** Open the panel's device page in Home Assistant
  and click *Visit*, or go to `http://<panel>:8099/`. Sign in with a Home Assistant administrator, or
  with a code the panel shows when Home Assistant is down. It shows how the panel is doing and which
  features run, keeps a log across reboots, downloads diagnostics with tokens and keys removed, and
  can reload the dashboard, restart the app, reboot (with the code on the panel) and check for
  updates. Settings show there but are changed on the panel for now.
- **Kiln shows what the panel is doing while S1 Kiosk goes in.** On kiln.glassworks.dev the app's
  install now lists each step as the panel goes through it: S1 Kiosk and the on-screen keyboard
  going in, the home screen, your settings, and the first start. When it updates an app that's
  already on the panel, it says the app restarting meanwhile is expected. The Wi-Fi step says
  *Connected to <network>* when the panel is on Wi-Fi already, with another network as the other
  choice. Back and the step list on the left go back through the
  set-up steps without losing what you entered.
- **Kiln's picker lists Generic Android as planned.** Next to the S1 and the Mirror, a card for any
  Android tablet or panel, which will install as a normal app without root. It can't be picked yet.
- **The panel's health shows in Home Assistant.** The device page's *Diagnostic* section gets CPU
  usage, CPU temperature, Wi-Fi signal and uptime, so you can graph them and alert on them. More
  sensors are there but off until you turn them on: the app's own CPU and RAM, free RAM and storage,
  Wi-Fi link speed, the app's uptime, its web content processes and why it last restarted. The panel
  reads them once a minute and sends a value only when it changed.
- **Releases now include an APK for 64-bit Android devices.** Each release carries
  `s1-kiosk-arm64-v8a-<version>.apk` beside the S1's APK, for arm64-v8a tablets and similar devices.
  It is experimental and has only run on an emulator. S1 panels keep updating from
  `s1-kiosk-<version>.apk` as before.
- **The panel gets your Thread network from Home Assistant**, instead of you copying the dataset.
  *Get it from Home Assistant* on the panel's Thread router card (setup screen and settings menu), or
  the *Join Home Assistant's Thread network* button on its device page, reads your preferred Thread
  network from Home Assistant and makes the panel a Thread router. Home Assistant only gives a Thread
  network's settings to administrators, so unless the panel is signed in as one, it shows a QR code:
  scan it and sign in as an administrator on your phone. The panel uses that sign-in once and deletes
  it straight away. The *Thread join* sensor says how it went. Pasting the dataset still works.

### Fixed
- **A pane you turn off stays off after a restart.** Turning voice or music off, then restarting
  the app at once, could bring the pane back on: the setting was saved after the app had gone.
  Zoom, secure hosts and remote debugging, which restart the app, could lose their change the same
  way. They are now saved before the app restarts.
- **One stuck connection no longer freezes the panel's entities in Home Assistant.** If a client
  stopped reading (a Home Assistant that hangs, or a connection that is half gone), states, action
  replies and keepalive pings for every other client waited behind it, for minutes, until the panel
  gave up on the stuck one. Now only that client waits, until the panel drops it.
- **Keeping the radio Off in Kiln finishes the panel's radio step.** A fresh panel installed with
  Kiln no longer asks *Radio* again on its own screen when you left the radio Off. Choosing the mode
  the radio already runs doesn't restart it. Needs the S1 Kiosk release with this fix.
- **Kiln's Done always offers your backup.** After an install, Done shows the backup's download even
  when set-up ran after leaving and coming back to the wizard, or after a reload. Before, it could
  show no download, and the backup was only in that browser. When a browser blocks site storage,
  Kiln no longer says "nothing was written" after a reload: it says it can't tell. Every screen now
  has one main button, the recovery steps no longer look like buttons ("Remove the back cover"), and
  the ends of the main buttons are no longer cut off. For a Thread router, Kiln points you to the
  panel's *Join Home Assistant's Thread network* when its S1 Kiosk has it, instead of copying the
  dataset; with an older S1 Kiosk, copying stays, under *More help*.
- **Undo no longer puts our splash back as Aqara's.** If a panel's `boot` was put back without its
  splash and the panel was then installed again, that install's backup held a stock `boot` with our
  power-on splash, and undo restored our splash while saying the stock one was back. Both installers
  now refuse such a backup before writing anything, and point you to the first install's backup or
  the by-hand undo in docs/install.md.
- **A Thread dataset typed in the settings menu no longer shows an error.** After you pasted the
  dataset under the radio's *Advanced* and the radio took it, the menu said "The kiosk service isn't
  running yet" instead of "Dataset handed to the radio". The dataset was stored either way; now the
  menu says so.
- **Kiln: Undo and Try again say the right thing in rare cases.** On kiln.glassworks.dev, once a
  try has written to the panel, or given it back to Aqara's apps, a later try that fails before
  writing says so and keeps *Undo with your backup*, instead of "Nothing was written." This holds in
  the same browser tab after going back to Kiln's first page or reloading, until a run finishes.
  Closing the browser's device list on such a retry asks for recovery mode, then Undo. On Undo, a
  backup this browser can't read now says so on the screen. Once *Keep its settings* has given
  Aqara's apps back, that backup is the one Undo restores: another can't be picked, even after
  changing how far back to go. A rescan that changes the picked Wi-Fi network clears the password
  typed for the old one. Links that are off now look off.
- **The screen starts at your saved brightness.** With *Auto brightness* off, the app used the
  system brightness after a restart until the light sensor reported a change, while Home Assistant
  already showed your saved brightness. In a dark, still room that could last a long time. The app
  now applies your saved brightness as soon as it starts.
- **Wi-Fi reconnects while the screen is off.** If Wi-Fi dropped at night (a router restart, an
  outage), the panel stayed offline until someone woke the screen, because its Wi-Fi chip can't scan
  on its own with the screen off. The app now asks for a scan 10 s after Wi-Fi drops, then after
  40 s, 60 s and every 120 s, while Wi-Fi is not connected and the screen is off. It needs the app's
  location permission, which the installer grants, and location turned on. The app's always-on
  service now also runs as a location service, because Android 10 only lets an app use location
  with the screen off that way. The app reads no location. On the reference panel, Wi-Fi was back
  about 20 s after it was turned off and on, with the screen still asleep.
- **Android 14 and newer: the app no longer crashes on a fresh install.** Before the microphone
  permission was granted, the app's always-on service stopped at once and Android showed "S1 Kiosk
  keeps stopping". The service now starts without the microphone, and takes it as soon as you allow
  it, so voice starts without a restart. S1 panels run Android 10 and are not affected.
- **Sendspin: switching the Music Assistant player off and on quickly leaves one connection.** After a
  quick off and on, or a setting change that restarts the player, the old connection could end the new
  stream, make the status say "disconnected" while the panel was connected, or connect a second time
  next to the new one. Now the old connection can't touch the new one.
- **Undo refuses an incomplete backup instead of half-restoring it.** Undo puts back boot, vbmeta and
  the splash. A backup missing one of them (a damaged or hand-made `.zip` or folder) used to restore
  the rest and report success, leaving our splash on the panel. Now the web installer and
  `fel-install.sh --undo` both refuse it before writing anything, and the
  [install guide](docs/install.md#undo) says how to put back boot and vbmeta by hand.
- **Sign-in: someone on your network who reads the sign-in code can no longer use it.** Both sign-ins (the
  dashboard QR code and the administrator sign-in for the Thread join) now use PKCE, so Home Assistant
  only hands out the panel's token to the panel itself. Needs Home Assistant 2026.10 or later to take
  effect; older versions sign in as before. A sign-in that was halfway through when the update
  arrived needs a new scan.
- **Sendspin: Music Assistant pairing was still offered after you switched Sendspin off.** Once the
  player had started, turning it off in Home Assistant still let the pairing request through. It now
  says Sendspin is disabled until you turn it back on.
- **Thread border router: replies from Thread devices stopped after Wi-Fi reconnected.** Turning
  Wi-Fi off and on gives Android's Wi-Fi interface a new number, and the radio helper's routing rule
  for traffic from Thread to your network kept pointing at the old one. It now follows the
  interface. Comes with the radio bundle.
- **Settings menu: cards were cut off 30 dp from the top and bottom of the screen.** They now scroll
  all the way to the screen edges.

### Changed
- **Updates are found among the repo's other releases.** The panel now picks its own newest release
  (tag `v1.2.3` or `aqara-s1-plus-v1.2.3`) instead of whatever release is newest, so releases of
  other devices and libraries in the same repo can't hide an update.
- **Thread border router docs:** the panel has to be on Home Assistant's network to be its border
  router, and has now been tested as a second border router next to an existing one.

## [0.4.3] - 2026-11-11

**Pick the radio's role on the panel with role tiles and QR codes, voice timers you can move or hide, and a new typeface.**

### Added
- **Radio in the panel's settings menu.** A row of role tiles (*Off*, *Zigbee coordinator*, *Zigbee
  router (repeater)*, *Thread border router*, *Thread router (repeater)*), each with its icon in the
  technology's colour (Zigbee blue, Thread violet) and, on the active one, a status dot. Under them, a
  panel for the active role: its status, what to do next, and the same buttons as in Home Assistant
  (*Zigbee stack*, *Join / Leave Zigbee network* with the router's live status, *Leave Thread
  network*). Tapping another tile previews that role with *Turn on*; switching, changing the Zigbee
  stack and leaving a network ask first. In Home Assistant these only work over the panel's encrypted
  connection; on the panel, being there is what allows them, as for the *who may connect* field.
- **QR codes for the Thread roles**, as the Zigbee coordinator already had. On the setup screen and in
  the settings menu: *Thread border router* shows the URL to enter and a code that opens Home
  Assistant's *Add OpenThread Border Router*; *Thread router (repeater)* shows a code that opens Home
  Assistant's Thread page, with the steps to copy the dataset. No code while the panel doesn't know
  Home Assistant's address; the text is there anyway.
- **The Thread router says whether it has joined**, on the panel and in *Radio status*: the Thread
  role (child, router, leader, or still looking) and the network's name, never its key. It keeps
  updating, so *joined as a router* shows up when the network promotes the panel. This comes
  with the app's radio package: no new boot software is needed.
- *Leave Thread network* is offered on the panel whenever a Thread network is still stored, also after
  switching to another mode, so it can be cleaned up.
- The Thread dataset can also be pasted on the panel (settings menu, Thread router, *Advanced*),
  including datasets longer than Home Assistant's 255-character limit for text fields.
- The web installer's next steps for the Thread roles link straight to the right pages in Home
  Assistant.
- **New look for voice timers**, to match the voice assistant: each timer is a small glass orb whose
  amber ring runs down, with its name underneath, at the bottom right. Its last 10 seconds glow and
  count down in large digits; a paused timer shows a pause sign and a grey ring. A timer you set by
  voice comes out of the assistant's orb as it closes.
- **Move or hide the timers** if they cover something: drag them to any corner or edge (they snap
  into place and stay there for later timers), or tap one and choose *Hide timers*. Hidden timers
  become a small half-orb at the screen edge that still shows the time left; tap it to bring them
  back. They come back by themselves for their last 10 seconds, and when you set a new one.
- **Timer done** is now a full-screen moment like the voice assistant: the orb glows amber with each
  chime, and it shows the timer's name and length (or "2 timers done" when several finish together)
  and which timers are still running. Tap anywhere to dismiss it, which also stops the remaining
  chimes.

### Changed
- **New typeface: Inter**, bundled with the app (SIL Open Font License), on the voice assistant, the
  timers, the settings menu and the setup and loading screens, matching the boot animation.
- The repeater roles are now called *Zigbee router (repeater)* and *Thread router (repeater)*
  everywhere, so they aren't mixed up with the Thread border router. The border router's card says
  it runs its own Thread network, and that joining yours is the *Thread router (repeater)* role.
- The setup screen's radio step shows each role's icon in its technology's colour, with shorter
  descriptions so *Apply* and *Skip* always fit.

### Fixed
- A blank strip about 85 px tall could cover the bottom of the dashboard. The kiosk hides Home
  Assistant's header, but cards that subtract the header's height from the screen height still
  subtracted it; they now get the full screen.
- Pasting the Thread dataset works even when the copy includes spaces or line breaks.
- Small capital labels, such as the voice assistant's *LISTENING*, showed in lower case.

### Note
- The repeater roles are now called *Zigbee router (repeater)* and *Thread router (repeater)* in the
  Radio mode select; automations that set the old names need updating.

## [0.4.2] - 2026-11-10

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

[Unreleased]: https://github.com/GlassworksHQ/glassworks/compare/v0.4.3...HEAD
[0.4.3]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.3
[0.4.2]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.2
[0.4.1]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.1
[0.4.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.4.0
[0.3.1]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.3.1
[0.3.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.3.0
[0.2.0]: https://github.com/GlassworksHQ/glassworks/releases/tag/v0.2.0
