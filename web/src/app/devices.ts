// The real USB devices behind flow.ts's Devices interface.
import { Panel } from '../adb/panel';
import type { AdbKey } from '../adb/key';
import { Fastboot } from '../fastboot/fastboot';
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
