// Fastboot client for our FEL-booted U-Boot (tools/fel/uboot): getvar, oem run, download + flash,
// and the 'upload' command added by patch 0002, which tools/install/fb-read-partition.sh uses to read
// partitions back. Same transport as FEL (fel/transport.ts).
import { WebUsbBulk, type BulkTransport } from '../fel/transport';

/** U-Boot's fastboot gadget (CONFIG_USB_GADGET_VENDOR_NUM/PRODUCT_NUM in our defconfig). */
export const FASTBOOT_FILTER: USBDeviceFilter = { vendorId: 0x1f3a, productId: 0x1010 };
const FASTBOOT_INTERFACE = { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x03 };

/** CONFIG_FASTBOOT_BUF_ADDR / _SIZE: partitions are staged here for reads and downloads. */
export const FASTBOOT_BUF = 0x50000000;
export const FASTBOOT_BUF_SIZE = 0x20000000;
/** eMMC is mmc 1 on the S1 Plus. */
const MMC = 1;
const MAX_COMMAND = 64;
const XFER = 1024 * 1024;
/** Flashing 32 MiB to eMMC takes a few seconds; 'oem run' of 'mmc read' about as long. */
const SLOW_MS = 120_000;

export class FastbootError extends Error {}

export class Fastboot {
  log: (line: string) => void = () => {};

  constructor(private readonly t: BulkTransport) {}

  static async request(): Promise<Fastboot> {
    return Fastboot.fromDevice(await navigator.usb.requestDevice({ filters: [FASTBOOT_FILTER] }));
  }

  static async fromDevice(d: USBDevice): Promise<Fastboot> {
    return new Fastboot(await WebUsbBulk.open(d, FASTBOOT_INTERFACE, XFER));
  }

  /** Already-permitted fastboot devices (no prompt). */
  static async known(): Promise<USBDevice[]> {
    return (await navigator.usb.getDevices()).filter(
      (d) => d.vendorId === FASTBOOT_FILTER.vendorId && d.productId === FASTBOOT_FILTER.productId,
    );
  }

  /** Sends a command, returns the OKAY payload (or the DATA size for download/upload). */
  private async command(cmd: string, timeoutMs = SLOW_MS): Promise<{ kind: 'OKAY' | 'DATA'; value: string }> {
    const bytes = new TextEncoder().encode(cmd);
    if (bytes.length > MAX_COMMAND) throw new FastbootError(`fastboot command too long (${bytes.length} > 64): ${cmd}`);
    await this.t.out(bytes, timeoutMs);
    return this.response(cmd, timeoutMs);
  }

  private async response(cmd: string, timeoutMs: number): Promise<{ kind: 'OKAY' | 'DATA'; value: string }> {
    for (;;) {
      const r = new TextDecoder().decode(await this.t.inPacket(256, timeoutMs));
      const kind = r.slice(0, 4), value = r.slice(4);
      if (kind === 'INFO' || kind === 'TEXT') { this.log(value); continue; }
      if (kind === 'OKAY' || kind === 'DATA') return { kind, value };
      if (kind === 'FAIL') throw new FastbootError(`${cmd}: ${value || 'failed'}`);
      throw new FastbootError(`${cmd}: unexpected reply '${r}'`);
    }
  }

  async getvar(name: string): Promise<string> {
    return (await this.command(`getvar:${name}`, 10_000)).value;
  }

  /** 'oem run:<cmd>': runs one U-Boot shell command (CONFIG_FASTBOOT_OEM_RUN). */
  async oemRun(cmd: string): Promise<void> {
    await this.command(`oem run:${cmd}`);
  }

  async partitionSize(name: string): Promise<number> {
    const v = await this.getvar(`partition-size:${name}`);
    const n = Number.parseInt(v, 16);
    if (!/^0x[0-9a-f]+$/i.test(v) || !Number.isSafeInteger(n)) throw new FastbootError(`no partition ${name} (${v})`);
    return n;
  }

  /**
   * Reads a whole eMMC partition: fb-read-partition.sh (mmc read into the fastboot buffer, then
   * 'upload'). Read-only. Short env names because each command must fit in 64 bytes.
   */
  async readPartition(name: string, onProgress?: (done: number, total: number) => void): Promise<Uint8Array> {
    if (!/^[a-z0-9_]+$/.test(name)) throw new FastbootError(`bad partition name ${name}`);
    const size = await this.partitionSize(name);
    if (size > FASTBOOT_BUF_SIZE) throw new FastbootError(`${name} is larger than the fastboot buffer`);
    await this.oemRun(`mmc dev ${MMC}`);
    await this.oemRun(`part start mmc ${MMC} ${name} s`);
    await this.oemRun(`part size mmc ${MMC} ${name} n`);
    await this.oemRun(`mmc read 0x${FASTBOOT_BUF.toString(16)} $s $n`);
    await this.oemRun('setexpr fastboot_upload_size $n * 200');
    const r = await this.command('upload');
    if (r.kind !== 'DATA') throw new FastbootError('upload: no DATA reply');
    const n = Number.parseInt(r.value, 16);
    if (n !== size) throw new FastbootError(`upload of ${name}: got ${n} bytes, partition is ${size}`);
    const out = new Uint8Array(n);
    for (let got = 0; got < n; ) {
      const part = await this.t.in(Math.min(XFER, n - got), SLOW_MS);
      out.set(part, got);
      got += part.length;
      onProgress?.(got, n);
    }
    await this.response('upload', SLOW_MS);
    return out;
  }

  async download(data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void> {
    if (data.length > FASTBOOT_BUF_SIZE) throw new FastbootError('image is larger than the fastboot buffer');
    const r = await this.command(`download:${data.length.toString(16).padStart(8, '0')}`);
    if (r.kind !== 'DATA' || Number.parseInt(r.value, 16) !== data.length) throw new FastbootError('download refused');
    for (let off = 0; off < data.length; off += XFER) {
      await this.t.out(data.subarray(off, Math.min(off + XFER, data.length)), SLOW_MS);
      onProgress?.(Math.min(off + XFER, data.length), data.length);
    }
    await this.response('download', SLOW_MS);
  }

  /** Raw image only (no sparse); it must not be larger than the partition. */
  async flash(name: string, data: Uint8Array, onProgress?: (done: number, total: number) => void): Promise<void> {
    const size = await this.partitionSize(name);
    if (data.length > size) throw new FastbootError(`${name}: image (${data.length}) larger than partition (${size})`);
    await this.download(data, onProgress);
    await this.command(`flash:${name}`);
  }

  /** Flash, read back, compare: what fel-install.sh does for every write. */
  async flashVerified(name: string, data: Uint8Array, onProgress?: (phase: 'write' | 'verify', done: number, total: number) => void): Promise<void> {
    await this.flash(name, data, (d, t) => onProgress?.('write', d, t));
    const back = await this.readPartition(name, (d, t) => onProgress?.('verify', d, t));
    // Partitions are larger than most images: compare the image, the rest is whatever was there.
    for (let i = 0; i < data.length; i++) {
      if (back[i] !== data[i]) throw new FastbootError(`${name}: read-back mismatch at byte ${i}`);
    }
  }

  async reboot(): Promise<void> {
    try { await this.command('reboot', 10_000); } finally { await this.t.close(); }
  }

  async close(): Promise<void> {
    await this.t.close();
  }
}
