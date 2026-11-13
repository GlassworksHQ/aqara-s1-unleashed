// What Kiln's screens say and show for the Aqara S1 Plus. The hardware steps are the recipe's
// (@glassworks/aqara-s1-plus-installer); this is the words, the drawing data and the links.
import type { AskKind } from '@glassworks/kiln-core';
import recovery from '../../../../../frames/aqara-s1-plus/design/recovery.json';
import photo from '../../../../../docs/img/fel-button.jpg';
import type { RecoveryData } from '../recovery/player';
import type { RadioPlan } from '../wizard/state';

const DOCS = 'https://github.com/GlassworksHQ/aqara-s1-unleashed/blob/main/docs';

export interface AskCopy { title: string; body: string; pick: string }

export const s1PlusUi = {
  id: 'aqara-s1-plus',
  name: 'Aqara S1 Plus',
  short: 'S1 Plus',
  recovery: recovery as RecoveryData,
  photo,
  photoAlt: 'The S1 Plus board with the back cover off. The recovery button is circled: right of the shield\'s notch, below the round sensor window.',
  /** The name the browser's USB list shows at each "your turn". */
  asks: {
    fastboot: { title: 'One click to continue.', body: 'The panel restarted into the installer. Nothing has been written yet.', pick: 'USB download gadget' },
    adb: { title: 'One last click.', body: 'The panel is starting its new software. Leave its screen alone meanwhile.', pick: 'QUAD-CORE R818 EVB2' },
    connect: { title: 'Connect to your panel.', body: 'It should be running S1 Kiosk and plugged in to this computer. Nothing is reflashed.', pick: 'QUAD-CORE R818 EVB2' },
  } satisfies Record<AskKind, AskCopy>,
  /** The device in recovery mode, as the browser lists it. */
  felPick: 'USB Device (1f3a:efe8)',
  drivers: {
    win: 'Windows needs a driver once: in Zadig, give USB Device (1f3a:efe8) the WinUSB driver.',
    linux: 'Linux needs a udev rule once for 1f3a:efe8 and 1f3a:1010.',
  },
  help: {
    recovery: `${DOCS}/install.md#troubleshooting`,
    undo: `${DOCS}/install.md#undo`,
    undoCli: 'tools/install/fel-install.sh --undo ~/s1-backup',
    drivers: `${DOCS}/install.md#1--check-this-browser`,
    learn: `${DOCS}/install.md`,
  },
  app: 'S1 Kiosk',
  /** Undo's two depths (docs/install.md#undo). */
  undo: {
    factory: { title: 'As it came from Aqara', detail: 'The original software, then a factory reset on the panel. Everything is removed.' },
    keep: { title: 'Keep its settings', detail: 'Aqara\'s apps come back first, over USB with the panel running. Wi-Fi and the rest stay.', button: 'Give back Aqara\u2019s apps' },
  },
};

export type FrameUi = typeof s1PlusUi;

/**
 * After the radio role is applied: what to do in Home Assistant to use it, per mode (docs/radio.md).
 * Undefined when there is nothing to do (Off).
 */
export function radioPlan(mode: string, ip: string, haUrl: string, allowed: string | undefined): RadioPlan | undefined {
  const ha = haUrl ? new URL(haUrl).origin : '';
  const zhaAdd = ha ? `${ha}/config/zha/add` : 'https://my.home-assistant.io/redirect/integration/?domain=zha';
  // Home Assistant's own pages when the panel knows its address (frontend 2026.11), else My Home Assistant.
  const otbrAdd = ha ? `${ha}/config/integrations/dashboard/add?domain=otbr` : 'https://my.home-assistant.io/redirect/config_flow_start/?domain=otbr';
  const threadPage = ha ? `${ha}/config/thread` : 'https://my.home-assistant.io/redirect/integration/?domain=thread';
  const esphome = ha ? `${ha}/config/integrations/integration/esphome` : 'https://my.home-assistant.io/redirect/integration/?domain=esphome';
  const plans: Record<string, RadioPlan> = {
    zigbee_tcp: {
      title: 'Use the radio in Home Assistant.', lead: 'The panel is now a Zigbee coordinator. Add it to Home Assistant, then pair your devices.',
      steps: [
        { title: 'Add the radio to Home Assistant', body: 'Opens Zigbee (ZHA) setup. Choose Enter manually, then use these values. When ZHA asks about network settings, keep the radio\'s current ones: creating a new network from ZHA doesn\'t work with this radio yet.',
          kv: [['Radio type', 'EZSP'], ['Serial path', `socket://${ip}:6638`, true], ['Port speed', 'Leave as is (115200)']],
          link: 'https://my.home-assistant.io/redirect/config_flow_start/?domain=zha' },
        { title: 'Pair your Zigbee devices', body: 'Opens Home Assistant\'s Zigbee “Add device”, which starts pairing for a few minutes. Put each device in pairing mode.', link: zhaAdd },
        { title: 'Using Zigbee2MQTT instead? (experimental)', body: 'On the panel\'s device in Home Assistant (Glassworks Panel, under ESPHome), set Zigbee stack to Silicon Labs 8.2.2. The panel downloads it from Silicon Labs, which takes a minute. Then point Zigbee2MQTT at the panel. If the panel\'s Radio helper says v3, install the panel\'s software with Kiln once more first.',
          kv: [['adapter', 'ember'], ['port', `tcp://${ip}:6638`, true]] },
      ],
    },
    thread: {
      title: 'Use the border router in Home Assistant.', lead: 'The panel now runs its own Thread network for Home Assistant\'s OpenThread Border Router integration.',
      steps: [
        { title: 'Add OpenThread Border Router', body: 'Opens the integration setup in Home Assistant. Enter this URL. If you already have a border router, Home Assistant can use both.',
          kv: [['URL', `http://${ip}:8081`, true]], link: otbrAdd },
      ],
    },
    zigbee_router: {
      title: 'Join your Zigbee network.', lead: 'The panel is now a Zigbee router (repeater, experimental). It joins your existing network and extends it.',
      steps: [
        { title: 'Open pairing in Home Assistant', body: 'Opens your Zigbee “Add device”, which lets new devices join for a few minutes.', link: zhaAdd },
        { title: 'Tell the panel to join', body: 'In Home Assistant, open the panel\'s device (Glassworks Panel, under ESPHome) and press Join Zigbee network. It shows up in ZHA as a router.' },
      ],
    },
    thread_router: {
      title: 'Join your Thread network.', lead: 'The panel is now a Thread router (repeater, experimental). It joins your existing Thread network and extends it.',
      steps: [
        { title: 'Copy your Thread network\'s dataset', body: 'Opens Home Assistant\'s Thread page. Under Preferred network, open ⓘ Network information and copy Active dataset TLVs. Home Assistant shows it to admin users, for a network from an OpenThread Border Router it manages.', link: threadPage },
        { title: 'Give it to the panel', body: 'Opens ESPHome in Home Assistant. Open the panel\'s device (Glassworks Panel) → Configuration and paste it into Thread dataset; spaces or line breaks in the copy are fine. The dataset contains the network key: paste it only there. It is never shown again.', link: esphome },
        { title: 'Watch it join', body: 'The panel\'s Radio status says “joined … as a child”, then “as a router” once the network promotes it, which takes a few minutes.' },
      ],
    },
  };
  const plan = plans[mode];
  if (!plan) return undefined;
  // Helper v3 firewall: the radio's ports only open to Home Assistant, once it has added the panel.
  if ((mode === 'zigbee_tcp' || mode === 'thread') && allowed === '') {
    return { ...plan, steps: [{ title: 'First, add the panel to Home Assistant', body: 'Only Home Assistant can reach the radio, and the panel learns its address when Home Assistant adds the panel. If you haven\'t yet: in Home Assistant open Settings → Devices & services, and add the Glassworks Panel shown under Discovered (ESPHome). Until then the radio refuses every connection.',
      link: 'https://my.home-assistant.io/redirect/integrations/' }, ...plan.steps] };
  }
  return plan;
}
