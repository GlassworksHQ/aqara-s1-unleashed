# Using the panel's Zigbee/Thread radio

The S1 Plus has a Silicon Labs EFR32 radio inside. S1 Unleashed can offer it to Home Assistant in one
role at a time. You pick the role in Home Assistant, or on the panel itself ([below](#on-the-panel)).
This page is the how-to. [radio.md](radio.md) is the technical reference: how each mode works, what was tested, and
the root helper.

You need the panel's root helper (installed by the [web installer](install.md)) for every mode except
*Off*. The panel shows up in Home Assistant as an ESPHome device, and all of the controls below are on
that device's page.

## Pick a mode

Set **Radio mode** on the panel's device page:

| Mode | What the panel becomes | Status |
|---|---|---|
| **Off** (default) | Nothing; the radio is idle | Works |
| **Zigbee coordinator (TCP)** | The coordinator of a Zigbee network, for ZHA (or Zigbee2MQTT, below) | Works with ZHA; Zigbee2MQTT experimental |
| **Zigbee router (repeater)** | A repeater in your existing Zigbee network | Experimental; joined a real home network |
| **Thread border router** | Runs its own Thread network, for Home Assistant's OpenThread Border Router integration | Experimental |
| **Thread router (repeater)** | A repeater in your existing Thread network | Experimental; joined a real home network |

*Thread border router* and *Thread router (repeater)* are different roles: the border router starts a
Thread network of its own; the router joins the one you already have (from Home Assistant's
OpenThread Border Router app, or another border router) and extends it.

Before 0.4.3 the repeaters were called *Zigbee router* and *Thread router*. Automations that set
**Radio mode** to the old names need updating.

**Radio status** says what the radio is doing, and who may connect to it. If something doesn't work,
look there first.

Only one Zigbee coordinator can run per ZHA install, and Zigbee and Thread can't run at the same time
on this radio (Home Assistant dropped that kind of sharing too).

## On the panel

The kiosk settings menu (tap the top-left corner five times) has a **Radio** section:

- the same roles as **Radio mode**, as a row of tiles: Zigbee roles in blue, Thread roles in violet,
  the one that is on with a green dot (amber while it starts, red if it failed), β for experimental.
  Tap another tile to see what it does, then *Turn on*; switching asks first, because it restarts the
  radio;
- under the tiles, what to do next for the role that is on, with a QR code your phone can scan to open
  the right page in Home Assistant (shown once the panel knows Home Assistant's address), and the role's
  own buttons: **Zigbee stack**, **Join / Leave Zigbee network**, **Leave Thread network**. Leaving a
  network asks first. *How does this work?* has the longer explanation;
- who may connect ([below](#who-may-connect)).

The setup screen's radio step shows a short version, with the same QR codes, after you apply a mode. The
buttons do exactly what the Home Assistant controls of the same name do. In Home Assistant they only
work over the panel's encrypted connection; on the panel, being there is what allows them, as for the
"who may connect" field.

## Zigbee coordinator with ZHA

1. Set **Radio mode** to *Zigbee coordinator (TCP)* and wait until **Radio status** starts with `ok`.
2. In Home Assistant, add the **Zigbee Home Automation** integration. Choose radio type **EZSP** and
   enter the serial path `socket://<panel IP>:6638` (Radio status shows it).
3. When ZHA asks about the network, choose **keep the radio's network settings**. Creating a new
   network or restoring a backup hangs until a fix in ZHA's radio library
   ([bellows#774](https://github.com/zigpy/bellows/pull/774), merged on 2026-11-10) reaches a Home
   Assistant release.

## Zigbee coordinator with Zigbee2MQTT (experimental)

Zigbee2MQTT needs a newer Zigbee stack than the one Aqara ships. This needs S1 Kiosk 0.4.0 or later
and the boot software that came with it (Home Assistant's **Radio helper** sensor says whether you have
it; [install.md](install.md#updating) says how to update it). Forming a network, resuming it and
pairing have been tested on the panel, but not yet for long with real devices.

1. Set **Zigbee stack** to *Silicon Labs 8.2.2 (EZSP 18, Zigbee2MQTT, experimental)*. The panel downloads Silicon Labs' stack from Silicon
   Labs itself (we can't ship it, for licence reasons) and checks it. **Radio status** shows the
   progress, then `EZSP 18`.
2. Set **Radio mode** to *Zigbee coordinator (TCP)*.
3. In Zigbee2MQTT's configuration:
   ```yaml
   serial:
     adapter: ember
     port: tcp://<panel IP>:6638
   ```
4. If Zigbee2MQTT runs on another machine than Home Assistant (an add-on runs on the same one), put
   that machine's address in **Radio allowed clients** (see [Who may connect](#who-may-connect)).

ZHA also works with this stack. Switching back to *Aqara 7.3.3* gives back Aqara's stack and its own
network, untouched. The Zigbee router mode always uses Aqara's stack.

Zigbee2MQTT gives the radio 5 seconds to answer its reset, and this stack takes about 4.3. If a start
fails now and then with a reset timeout, restart Zigbee2MQTT; it usually works the next time.

## Thread border router (experimental)

This mode makes the panel a border router with its own Thread network. To extend a Thread network you
already have, use *Thread router (repeater)* instead.

1. Set **Radio mode** to *Thread border router*.
2. In Home Assistant, add the **OpenThread Border Router** integration with the URL
   `http://<panel IP>:8081`. On the panel, the setup screen and the settings menu show this URL and a
   QR code that opens the integration's setup in Home Assistant on your phone.

Home Assistant's side of this has been tested. Traffic between your network and real Thread devices
through the panel hasn't been tested yet.

## Repeater modes (experimental)

**Zigbee router (repeater).** Set **Radio mode** to *Zigbee router (repeater)*. In ZHA, open joining
(*Add device*; the QR code on the panel opens it), then press **Join Zigbee network** within that
window, on the panel's device page or in the panel's settings menu. ZHA lists the panel as a router.
**Leave Zigbee network** takes it out again (remove it from ZHA too).

**Thread router (repeater).**

1. Set **Radio mode** to *Thread router (repeater)*.
2. Give the panel your Thread network. The easiest is **Get it from Home Assistant** on the panel's
   Thread router card (or **Join Home Assistant's Thread network** on its device page): the panel gets
   your *preferred* Thread network from Home Assistant by itself. If the panel is signed in to Home
   Assistant as an administrator, that's all. Otherwise it shows a QR code: scan it with your phone
   and sign in as a Home Assistant administrator (Home Assistant only gives a Thread network's
   settings to administrators). The panel uses that sign-in once, to read the settings, and then
   deletes it again (it no longer appears under your profile's *Refresh tokens*). The QR code works
   for 10 minutes. If Home Assistant has several Thread networks, make the one you want *preferred*
   first. **Thread join** on the device page says how it went.
3. Or copy the dataset by hand: open Home Assistant's **Thread** page (the panel's card has a QR code
   for it). Under *Preferred network*, open ⓘ *Network information* and copy **Active dataset TLVs**
   (Home Assistant shows it to admin users, for a network that comes from an OpenThread Border Router
   it manages). Paste it into the panel's **Thread dataset** field (the panel's device page in Home
   Assistant → *Configuration*). Spaces or line breaks in the copy are fine. The field is write-only
   and the panel keeps the dataset where only root can read it. (The settings menu on the panel has
   the same field under *Advanced*, also for datasets longer than the 255 characters Home Assistant
   allows.)
4. **Radio status** shows the Thread role and the network's name: first *joined as a child*, then
   *as a router* once the network promotes the panel, which takes a few minutes.

**Leave Thread network** makes the panel forget the network again (needs the boot software from 0.4.0
or later). The panel's settings menu offers it whenever a Thread network is still stored, also after
switching to another mode.

The dataset contains your Thread network key: paste it only into the panel's field, never into a chat
or a bug report.

## Who may connect

The radio's network ports have no password of their own, so the panel lets only Home Assistant in. It
learns Home Assistant's address from Home Assistant's encrypted connection to the panel. To let other
computers in (Zigbee2MQTT on another machine, a laptop for testing), set **Radio allowed clients**:
addresses or ranges separated by spaces or commas, e.g. `192.0.2.10 198.51.100.0/24`, or `any` to
turn the check off. Leave it empty for "Home Assistant only". The same setting is on the panel itself:
tap the top-left corner five times, *Radio*, *Who may connect*.

If **Radio status** says `nobody yet (waiting for Home Assistant to connect over ESPHome)` and stays
that way, Home Assistant is probably connecting without an encryption key (Home Assistant older than
2026.11). Update Home Assistant, or put its address in **Radio allowed clients**.

## Radio firmware

The radio runs Aqara's firmware, and nothing here needs a different one. The panel can flash radio
firmware (boot software from 0.4.0 or later), but never by itself: choose an image in **Radio
firmware image**, then press **Flash radio firmware** within two minutes. **Radio firmware** shows the
progress. If anything goes wrong it puts Aqara's original firmware back. No newer firmware is offered
yet; flashing Aqara's original image again has been tested on the panel.

## Going back to Aqara's radio

*Off* stops Aqara's radio services too. To get Aqara's own hub back, undo the install
([install.md](install.md#undo)) and restart the panel.
