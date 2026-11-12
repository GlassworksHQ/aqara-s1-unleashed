// The panel over adb (Tango / ya-webadb on WebUSB), once Android is up.
import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { ReadableStream } from '@yume-chan/stream-extra';
import { openAdbStream } from '../../packages/improv-serial/src/adb';
import type { ByteStream } from '../improv';
import type { AdbKey } from './key';

/** What provisioning needs from a panel. Panel implements it over adb; the tests use a fake. */
export interface PanelShell {
  /** Runs a shell command; returns its output (stdout + stderr) and exit code. */
  run(cmd: string): Promise<{ out: string; code: number }>;
  push(path: string, data: Uint8Array, mode?: number): Promise<void>;
  /** A raw stream to a service on the panel (e.g. 'localabstract:…'), if the transport has one. */
  openStream?(service: string): Promise<ByteStream>;
}

const RC = '__S1RC=';

export class Panel implements PanelShell {
  private constructor(private readonly adb: Adb) {}

  get serial(): string { return this.adb.serial; }

  /** Asks for the panel's adb interface (needs a user gesture), then authenticates with `key`. */
  static async connect(key: AdbKey, device?: USBDevice): Promise<Panel> {
    const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
    if (!manager) throw new Error('this browser has no WebUSB (use Chrome or Edge)');
    let d: AdbDaemonWebUsbDevice | undefined;
    if (device) {
      d = (await manager.getDevices()).find((x) => x.raw === device);
    } else {
      d = await manager.requestDevice();
    }
    if (!d) throw new Error('no panel selected');
    let connection;
    try {
      connection = await d.connect();
    } catch (e) {
      if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) {
        throw new Error('another program holds the panel\'s adb connection: run "adb kill-server" on this computer and try again');
      }
      throw e;
    }
    const transport = await AdbDaemonTransport.authenticate({
      serial: d.serial, connection, credentialStore: key.store,
    });
    return new Panel(new Adb(transport));
  }

  /** Already-permitted adb devices (no prompt). */
  static async known(): Promise<USBDevice[]> {
    const m = AdbDaemonWebUsbDeviceManager.BROWSER;
    return m ? (await m.getDevices()).map((d) => d.raw) : [];
  }

  async run(cmd: string): Promise<{ out: string; code: number }> {
    const text = await this.adb.subprocess.noneProtocol.spawnWaitText(`${cmd}; echo "${RC}$?"`);
    const i = text.lastIndexOf(RC);
    if (i < 0) return { out: text, code: -1 };
    return { out: text.slice(0, i), code: Number.parseInt(text.slice(i + RC.length), 10) };
  }

  async getProp(key: string): Promise<string> {
    return (await this.adb.getProp(key)).trim();
  }

  async push(path: string, data: Uint8Array, mode = 0o644): Promise<void> {
    const sync = await this.adb.sync();
    try {
      await sync.write({
        filename: path,
        file: new ReadableStream<Uint8Array>({ start(c) { c.enqueue(data); c.close(); } }),
        permission: mode,
      });
    } finally {
      await sync.dispose();
    }
  }

  openStream(service: string): Promise<ByteStream> {
    // Adds the NUL terminator Android 10's adbd needs (see openAdbStream).
    return openAdbStream(this.adb, service);
  }

  async close(): Promise<void> {
    await this.adb.close();
  }
}
