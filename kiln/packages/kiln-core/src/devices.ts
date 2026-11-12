// The real USB devices behind kiln-core's Devices interface.
import { Panel, type AdbKey } from '@glassworks/adb';
import { Fastboot } from '@glassworks/fastboot';
import type { Devices } from './flow';

export const webUsbDevices: Devices = {
  async knownFastboot() {
    const [d] = await Fastboot.known();
    return d ? Fastboot.fromDevice(d) : undefined;
  },
  requestFastboot: () => Fastboot.request(),
  async knownAdb(key: AdbKey) {
    const [d] = await Panel.known();
    return d ? Panel.connect(key, d) : undefined;
  },
  requestAdb: (key: AdbKey) => Panel.connect(key),
};
