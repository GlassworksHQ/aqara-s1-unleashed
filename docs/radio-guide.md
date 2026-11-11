# Using the panel's Zigbee/Thread radio

The S1 Plus has a Silicon Labs EFR32 radio inside. S1 Unleashed can offer it to Home Assistant in one
role at a time. You pick the role in Home Assistant; nothing on the panel needs touching. This page is
the how-to. [radio.md](radio.md) is the technical reference: how each mode works, what was tested, and
the root helper.

You need the panel's root helper (installed by the [web installer](install.md)) for every mode except
*Off*. The panel shows up in Home Assistant as an ESPHome device, and all of the controls below are on
that device's page.

## Pick a mode

Set **Radio mode** on the panel's device page:

| Mode | What the panel becomes | Status |
|---|---|---|
| **Off** (default) | Nothing; the radio is idle | Works |
| **Zigbee coordinator (TCP)** | The coordinator of a Zigbee network, for ZHA (or Zigbee2MQTT, below) | Works with ZHA |
| **Zigbee router** | A repeater in your existing Zigbee network | Experimental |
| **Thread border router** | A Thread border router for Home Assistant's OpenThread Border Router integration | Experimental |
| **Thread router** | A repeater in your existing Thread network | Experimental |

**Radio status** says what the radio is doing, and who may connect to it. If something doesn't work,
look there first.

Only one Zigbee coordinator can run per ZHA install, and Zigbee and Thread can't run at the same time
on this radio (Home Assistant dropped that kind of sharing too).

## Zigbee coordinator with ZHA

1. Set **Radio mode** to *Zigbee coordinator (TCP)* and wait until **Radio status** starts with `ok`.
2. In Home Assistant, add the **Zigbee Home Automation** integration. Choose radio type **EZSP** and
   enter the serial path `socket://<panel IP>:6638` (Radio status shows it).
3. When ZHA asks about the network, choose **keep the radio's network settings**. Creating a new
   network or restoring a backup hangs until a fix in ZHA's radio library
   ([bellows#774](https://github.com/zigpy/bellows/pull/774), merged) reaches a Home Assistant release.

## Zigbee coordinator with Zigbee2MQTT (experimental)

Zigbee2MQTT needs a newer Zigbee stack than the one Aqara ships. This needs the panel's boot software
from November 2026 or later (Home Assistant's **Radio helper** sensor says whether you have it).

1. Set **Zigbee stack** to *Silicon Labs 8.2.2*. The panel downloads Silicon Labs' stack from Silicon
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
network, untouched.

## Thread border router (experimental)

1. Set **Radio mode** to *Thread border router*.
2. In Home Assistant, add the **OpenThread Border Router** integration with the URL
   `http://<panel IP>:8081`.

## Repeater modes (experimental)

**Zigbee router.** Set **Radio mode** to *Zigbee router*. In ZHA, open joining (*Add device*), then
press **Join Zigbee network** on the panel's device page within that window. ZHA lists the panel as a
router. **Leave Zigbee network** takes it out again.

**Thread router.** Copy your Thread network's active dataset from Home Assistant (*Settings → Devices &
services → Thread*, your preferred network's details; the dataset is a long hex string) into the
panel's **Thread dataset** field, then set **Radio mode** to *Thread router*. The field is write-only
and the panel keeps the dataset where only root can read it. **Leave Thread network** makes the panel
forget it again (needs the November 2026 boot software).

The dataset contains your Thread network key: paste it only into the panel's field, never into a chat
or a bug report.

## Who may connect

The radio's network ports have no password of their own, so the panel lets only Home Assistant in. It
learns Home Assistant's address from Home Assistant's encrypted connection to the panel. To let other
computers in (Zigbee2MQTT on another machine, a laptop for testing), set **Radio allowed clients**:
addresses or ranges separated by spaces or commas, e.g. `192.0.2.10 198.51.100.0/24`, or `any` to
turn the check off. Leave it empty for "Home Assistant only". The same setting is on the panel itself:
tap the top-left corner five times, *Radio: who may connect*.

If **Radio status** says `nobody yet (waiting for Home Assistant to connect over ESPHome)` and stays
that way, Home Assistant is probably connecting without an encryption key (Home Assistant older than
2026.11). Update Home Assistant, or put its address in **Radio allowed clients**.

## Radio firmware

The radio runs Aqara's firmware, and nothing here needs a different one. The panel can flash radio
firmware (boot software from November 2026 or later), but never by itself: choose an image in **Radio
firmware image**, then press **Flash radio firmware** within two minutes. If anything goes wrong it
puts Aqara's original firmware back. No newer firmware is offered yet.

## Going back to Aqara's radio

*Off* stops Aqara's radio services too. To get Aqara's own hub back, undo the install
([install.md](install.md#undo)) and restart the panel.
