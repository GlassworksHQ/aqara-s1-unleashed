// The panel over adb (Tango / ya-webadb on WebUSB), once Android is up.
import { Adb, AdbDaemonTransport } from '@yume-chan/adb';
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb';
import { ReadableStream } from '@yume-chan/stream-extra';
import type { ByteStream } from '../improv/serial';
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

  async openStream(service: string): Promise<ByteStream> {
    // Android 10's adbd reads the OPEN payload as a C string. Tango omits the NUL terminator for
    // protocol >= 0x01000001, so adbd can read past the name into garbage and refuse the service
    // (seen on the panel: localabstract:s1kiosk_improv refused, with the NUL it opens). Real adb
    // always sends it.
    const socket = await this.adb.createSocket(service.endsWith('\0') ? service : `${service}\0`);
    const writer = socket.writable.getWriter();
    return {
      write: (data) => writer.write(data),
      onData: (cb) => {
        void (async () => {
          const reader = socket.readable.getReader();
          try {
            for (;;) {
              const { done, value } = await reader.read();
              if (done) break;
              cb(value);
            }
          } catch { /* closed */ }
        })();
      },
      close: async () => { try { await socket.close(); } catch { /* already closed */ } },
    };
  }

  async close(): Promise<void> {
    await this.adb.close();
  }
}
