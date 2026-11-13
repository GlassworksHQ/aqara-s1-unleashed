// Fastboot over WebUSB, for every frame. A fork of fastboot.js (kdrag0n, MIT, see README.md):
// its flashBlob, sparse splitting and bootBlob, on our transport (the one FEL uses, so the tests
// can run against simulated devices), plus what our FEL-booted U-Boot (tools/fel/uboot) adds:
// 'oem run' and the 'upload' of patch 0002, which reads partitions back
// (tools/install/fb-read-partition.sh).
import { WebUsbBulk, type BulkTransport } from '@glassworks/fel/transport';
import * as Sparse from './sparse';

/** U-Boot's fastboot gadget (CONFIG_USB_GADGET_VENDOR_NUM/PRODUCT_NUM in our defconfig). */
export const FASTBOOT_FILTER: USBDeviceFilter = { vendorId: 0x1f3a, productId: 0x1010 };
const FASTBOOT_INTERFACE = { classCode: 0xff, subclassCode: 0x42, protocolCode: 0x03 };

/** CONFIG_FASTBOOT_BUF_ADDR / _SIZE: partitions are staged here for reads and downloads. */
export const FASTBOOT_BUF = 0x50000000;
export const FASTBOOT_BUF_SIZE = 0x20000000;
/** eMMC is mmc 1 on the S1 Plus. */
const MMC = 1;
const MAX_COMMAND = 64;
/** Bytes per USB transfer. Upstream sends 16 KiB; 1 MiB is what the S1 has always used. */
const XFER = 1024 * 1024;
const GETVAR_MS = 10_000;
/** Flashing 32 MiB to eMMC takes a few seconds; 'oem run' of 'mmc read' about as long. */
const SLOW_MS = 120_000;

// When the bootloader names no max-download-size, upstream assumes 512 MiB. It caps larger values
// at 1 GiB "to conserve RAM and work around Chromium's ~2 GiB size limit".
const DEFAULT_DOWNLOAD_SIZE = 512 * 1024 * 1024;
const MAX_DOWNLOAD_SIZE = 1024 * 1024 * 1024;

export class FastbootError extends Error {
  /** 'FAIL' when the bootloader refused; undefined for errors found on this side. */
  constructor(message: string, readonly status?: string) {
    super(message);
  }
}

/** Progress of flashBlob and bootBlob, between 0 and 1. */
export type FlashProgressCallback = (progress: number) => void;

export class Fastboot {
  log: (line: string) => void = () => {};

  constructor(private readonly t: BulkTransport) {}

  /** `filters`: the frame's fastboot device; our U-Boot's by default. */
  static async request(filters: USBDeviceFilter[] = [FASTBOOT_FILTER]): Promise<Fastboot> {
    return Fastboot.fromDevice(await navigator.usb.requestDevice({ filters }));
  }

  static async fromDevice(d: USBDevice): Promise<Fastboot> {
    return new Fastboot(await WebUsbBulk.open(d, FASTBOOT_INTERFACE, XFER));
  }

  /** Already-permitted fastboot devices (no prompt). */
  static async known(filters: USBDeviceFilter[] = [FASTBOOT_FILTER]): Promise<USBDevice[]> {
    return (await navigator.usb.getDevices()).filter((d) => filters.some((f) =>
      (f.vendorId === undefined || f.vendorId === d.vendorId) && (f.productId === undefined || f.productId === d.productId)));
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
      if (kind === 'FAIL') throw new FastbootError(`${cmd}: ${value || 'failed'}`, 'FAIL');
      throw new FastbootError(`${cmd}: unexpected reply '${r}'`);
    }
  }

  /** Some bootloaders (Qualcomm LK) pad values with whitespace, so the value is trimmed. */
  async getvar(name: string): Promise<string> {
    return (await this.command(`getvar:${name}`, GETVAR_MS)).value.trim();
  }

  /** getvar, or null when the bootloader answers FAIL (some do for a variable they don't have). */
  private async getVariable(name: string): Promise<string | null> {
    try {
      return (await this.getvar(name)) || null;
    } catch (e) {
      if (e instanceof FastbootError && e.status === 'FAIL') return null;
      throw e;
    }
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

  /** `limit`: the bootloader's buffer. Our U-Boot's by default; flashBlob passes max-download-size. */
  async download(data: Uint8Array, onProgress?: (done: number, total: number) => void, limit = FASTBOOT_BUF_SIZE): Promise<void> {
    if (data.length > limit) throw new FastbootError('image is larger than the fastboot buffer');
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

  /** Flash, read back, compare: what fel-install.sh does for every write. Needs our U-Boot's 'upload'. */
  async flashVerified(name: string, data: Uint8Array, onProgress?: (phase: 'write' | 'verify', done: number, total: number) => void): Promise<void> {
    await this.flash(name, data, (d, t) => onProgress?.('write', d, t));
    const back = await this.readPartition(name, (d, t) => onProgress?.('verify', d, t));
    // Partitions are larger than most images: compare the image, the rest is whatever was there.
    for (let i = 0; i < data.length; i++) {
      if (back[i] !== data[i]) throw new FastbootError(`${name}: read-back mismatch at byte ${i}`);
    }
  }

  /** max-download-size, capped, or upstream's default when the bootloader names none. */
  private async downloadSize(): Promise<number> {
    const n = Number.parseInt((await this.getVariable('max-download-size')) ?? '', 16);
    return Number.isSafeInteger(n) && n > 0 ? Math.min(n, MAX_DOWNLOAD_SIZE) : DEFAULT_DOWNLOAD_SIZE;
  }

  /**
   * Flashes a Blob (a File stays on disk) of any image format the bootloader takes. A raw image
   * larger than max-download-size becomes sparse, and a sparse image is split into pieces that
   * each fit, as AOSP fastboot does. Each piece is read from the Blob only when it is sent.
   * No read-back: verify some other way (on the S1, use flashVerified).
   */
  async flashBlob(partition: string, blob: Blob, onProgress: FlashProgressCallback = () => {}): Promise<void> {
    // Use current slot if partition is A/B
    if ((await this.getVariable(`has-slot:${partition}`)) === 'yes') {
      partition += `_${await this.getVariable('current-slot')}`;
    }

    const maxDlSize = await this.downloadSize();
    const fileHeader = await blob.slice(0, Sparse.FILE_HEADER_SIZE).arrayBuffer();

    // A sparse magic with a bad header throws ImageError: upstream flashed such an image as raw.
    const sparseHeader = Sparse.parseFileHeader(fileHeader);
    const isSparse = sparseHeader !== null;
    const totalBytes = isSparse ? sparseHeader.blocks * sparseHeader.blockSize : blob.size;

    // Convert image to sparse (for splitting) if it exceeds the size limit
    if (blob.size > maxDlSize && !isSparse) {
      this.log(`${partition} image is raw, converting to sparse`);
      blob = await Sparse.fromRaw(blob);
    }

    // Build the first piece before anything changes on the device: it checks the sparse header and
    // the download limit, so a refused image leaves a logical partition at its old size.
    const pieces = Sparse.splitBlob(blob, maxDlSize);
    let piece = await pieces.next();

    // Logical partitions need to be resized before flashing because they're sized perfectly to the payload.
    if ((await this.getVariable(`is-logical:${partition}`)) === 'yes') {
      // As per AOSP fastboot, we reset the partition to 0 bytes first to optimize extent allocation.
      await this.command(`resize-logical-partition:${partition}:0`);
      await this.command(`resize-logical-partition:${partition}:${totalBytes}`);
    }

    this.log(`Flashing ${blob.size} bytes to ${partition}, ${maxDlSize} bytes per split`);
    let splits = 0;
    let sentBytes = 0;
    for (; !piece.done; piece = await pieces.next()) {
      const split = piece.value;
      const data = new Uint8Array(split.data);
      await this.download(data, (done, total) => onProgress((sentBytes + (done / total) * split.bytes) / totalBytes), maxDlSize);
      await this.command(`flash:${partition}`, flashTimeout(data.length));
      splits += 1;
      sentBytes += split.bytes;
    }
    this.log(`Flashed ${partition} with ${splits} split(s)`);
  }

  /** `fastboot boot`: runs an image from RAM, nothing is written. */
  async bootBlob(blob: Blob, onProgress: FlashProgressCallback = () => {}): Promise<void> {
    const limit = await this.downloadSize();
    if (blob.size > limit) throw new FastbootError('image is larger than the fastboot buffer');
    const data = new Uint8Array(await blob.arrayBuffer());
    await this.download(data, (done, total) => onProgress(done / total), limit);
    await this.command('boot');
  }

  /**
   * U-Boot resets the panel as soon as it gets `reboot`, and the USB link often drops before its OKAY
   * can be read ("The device was disconnected"). Once the command is out, the reboot is happening:
   * a missing or failed reply is not an error.
   */
  async reboot(): Promise<void> {
    try {
      await this.t.out(new TextEncoder().encode('reboot'), 10_000);
      try { await this.response('reboot', 5_000); } catch (e) { this.log(`reboot: no reply (${(e as Error).message}); the panel is restarting`); }
    } finally {
      await this.t.close();
    }
  }

  async close(): Promise<void> {
    await this.t.close();
  }
}

/** Upstream waits for 'flash:' without a limit. Ours: the S1's 120 s plus 1 s per 4 MiB written. */
function flashTimeout(bytes: number): number {
  return SLOW_MS + Math.ceil(bytes / (4 * 1024 * 1024)) * 1000;
}
