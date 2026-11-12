// A bulk IN/OUT pair. WebUSB in the browser; simulated devices in the tests.

export interface BulkTransport {
  /** Sends all of `data` (may be split into several USB transfers). */
  out(data: Uint8Array, timeoutMs?: number): Promise<void>;
  /** Receives exactly `length` bytes. */
  in(length: number, timeoutMs?: number): Promise<Uint8Array>;
  /** Receives one USB transfer of at most `maxLength` bytes (ends at a short packet). */
  inPacket(maxLength: number, timeoutMs?: number): Promise<Uint8Array>;
  close(): Promise<void>;
}

/** Same as libusb's 10 s in sunxi-fel; one 64 KiB FEL write at the slowest seen speed takes ~1 s. */
export const DEFAULT_TIMEOUT_MS = 10_000;

function withTimeout<T>(p: Promise<T>, what: string, ms: number): Promise<T> {
  let t: ReturnType<typeof setTimeout>;
  const timer = new Promise<never>((_, reject) => {
    t = setTimeout(() => reject(new Error(`USB ${what} timed out after ${ms / 1000} s`)), ms);
  });
  return Promise.race([p, timer]).finally(() => clearTimeout(t));
}

export interface InterfaceMatch { classCode: number; subclassCode: number; protocolCode: number }

/** Opens a USB device and finds the bulk endpoints of one interface (fel_lib.c feldev_get_endpoint). */
export class WebUsbBulk implements BulkTransport {
  private constructor(
    readonly device: USBDevice,
    private readonly epIn: number,
    private readonly epOut: number,
    private readonly maxOut: number,
  ) {}

  /** `iface`: an interface number, or the class/subclass/protocol to look for (fastboot: ff/42/03). */
  static async open(device: USBDevice, iface: number | InterfaceMatch = 0, maxOut = 64 * 1024): Promise<WebUsbBulk> {
    if (!device.opened) await device.open();
    if (device.configuration === null) await device.selectConfiguration(1);
    const ifs = device.configuration?.interfaces ?? [];
    const found = typeof iface === 'number'
      ? ifs.find((i) => i.interfaceNumber === iface)
      : ifs.find((i) => i.alternates.some((a) =>
        a.interfaceClass === iface.classCode && a.interfaceSubclass === iface.subclassCode &&
        a.interfaceProtocol === iface.protocolCode));
    if (!found) throw new Error('this USB device does not have the expected interface');
    await device.claimInterface(found.interfaceNumber);
    const bulk = found.alternate.endpoints.filter((e) => e.type === 'bulk');
    const epIn = bulk.find((e) => e.direction === 'in')?.endpointNumber;
    const epOut = bulk.find((e) => e.direction === 'out')?.endpointNumber;
    if (epIn === undefined || epOut === undefined) {
      throw new Error('this USB device has no bulk IN/OUT endpoint pair');
    }
    return new WebUsbBulk(device, epIn, epOut, maxOut);
  }

  async out(data: Uint8Array, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<void> {
    let off = 0;
    while (off < data.length) {
      const chunk = data.subarray(off, Math.min(off + this.maxOut, data.length));
      const r = await withTimeout(this.device.transferOut(this.epOut, chunk as Uint8Array<ArrayBuffer>), 'write', timeoutMs);
      if (r.status !== 'ok') throw new Error(`USB write failed (${r.status})`);
      if (r.bytesWritten === 0) throw new Error('USB write sent nothing');
      off += r.bytesWritten;
    }
  }

  async inPacket(maxLength: number, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<Uint8Array> {
    const r = await withTimeout(this.device.transferIn(this.epIn, maxLength), 'read', timeoutMs);
    if (r.status !== 'ok' || !r.data) throw new Error(`USB read failed (${r.status})`);
    return new Uint8Array(r.data.buffer, r.data.byteOffset, r.data.byteLength);
  }

  async in(length: number, timeoutMs = DEFAULT_TIMEOUT_MS): Promise<Uint8Array> {
    const buf = new Uint8Array(length);
    let got = 0;
    while (got < length) {
      const part = await this.inPacket(length - got, timeoutMs);
      buf.set(part.subarray(0, length - got), got); // zero-length packets just loop
      got += part.length;
    }
    return buf;
  }

  async close(): Promise<void> {
    try { await this.device.close(); } catch { /* already gone (it re-enumerates on reset) */ }
  }
}
