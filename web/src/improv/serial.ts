// Improv Serial (https://www.improv-wifi.com/serial/), carried over an adb stream to the kiosk app
// instead of a UART: the panel's USB port is Android's adb, not a serial port. The app listens on the
// abstract socket below and only accepts adb's shell user, so the USB connection (authorized with this
// browser's key) is the physical authorization; there is no Allow tap.
//
// Packet: "IMPROV" | version 1 | type | length | data[length] | checksum (sum of all previous bytes).

export const IMPROV_SOCKET = 'localabstract:s1kiosk_improv';

const HEADER = [0x49, 0x4d, 0x50, 0x52, 0x4f, 0x56]; // "IMPROV"
const VERSION = 1;

export const PacketType = { State: 0x01, Error: 0x02, Rpc: 0x03, RpcResult: 0x04 } as const;
export const Rpc = { WifiSettings: 0x01, RequestState: 0x02, RequestInfo: 0x03, ScanWifi: 0x04 } as const;
export const State = { Ready: 0x02, Provisioning: 0x03, Provisioned: 0x04 } as const;
const ERRORS: Record<number, string> = {
  0x01: 'The panel did not understand the request.',
  0x02: 'The panel does not support that request.',
  0x03: 'The panel could not join that network. Check the password, or that the network is in range.',
  0xff: 'Something went wrong on the panel.',
};

export interface Packet { type: number; data: Uint8Array }
export interface WifiNetwork { ssid: string; rssi: number; secure: boolean }
export interface DeviceInfo { firmware: string; version: string; chip: string; name: string }

const enc = new TextEncoder();
const dec = new TextDecoder();

export function frame(type: number, data: Uint8Array): Uint8Array {
  if (data.length > 255) throw new Error('Improv packet too large');
  const out = new Uint8Array(HEADER.length + 3 + data.length + 1);
  out.set(HEADER);
  out[6] = VERSION;
  out[7] = type;
  out[8] = data.length;
  out.set(data, 9);
  let sum = 0;
  for (let i = 0; i < out.length - 1; i++) sum += out[i]!;
  out[out.length - 1] = sum & 0xff;
  return out;
}

export function rpc(command: number, payload: Uint8Array = new Uint8Array(0)): Uint8Array {
  const d = new Uint8Array(2 + payload.length);
  d[0] = command;
  d[1] = payload.length;
  d.set(payload, 2);
  return frame(PacketType.Rpc, d);
}

/** Length-prefixed UTF-8 strings, as Improv RPC payloads and results use them. */
export function strings(...ss: string[]): Uint8Array {
  const parts = ss.map((s) => enc.encode(s));
  for (const p of parts) if (p.length > 255) throw new Error('Improv string longer than 255 bytes');
  const out = new Uint8Array(parts.reduce((n, p) => n + 1 + p.length, 0));
  let o = 0;
  for (const p of parts) { out[o++] = p.length; out.set(p, o); o += p.length; }
  return out;
}

export function wifiSettings(ssid: string, psk: string): Uint8Array {
  if (!ssid) throw new Error('no network name');
  return rpc(Rpc.WifiSettings, strings(ssid, psk));
}

/** Data of an RPC result packet: command, length, then length-prefixed strings. */
export function parseResult(data: Uint8Array): { command: number; strings: string[] } {
  const command = data[0] ?? 0;
  const len = data[1] ?? 0;
  const out: string[] = [];
  for (let o = 2; o < 2 + len && o < data.length; ) {
    const n = data[o]!;
    out.push(dec.decode(data.subarray(o + 1, o + 1 + n)));
    o += 1 + n;
  }
  return { command, strings: out };
}

/** Accumulates bytes and yields complete packets with a valid checksum (garbage is skipped). */
export class PacketReader {
  private buf = new Uint8Array(0);

  push(chunk: Uint8Array): Packet[] {
    const b = new Uint8Array(this.buf.length + chunk.length);
    b.set(this.buf);
    b.set(chunk, this.buf.length);
    this.buf = b;
    const out: Packet[] = [];
    for (;;) {
      const start = this.findHeader();
      if (start < 0) { this.buf = this.buf.subarray(Math.max(0, this.buf.length - 5)); break; }
      if (start > 0) this.buf = this.buf.subarray(start);
      if (this.buf.length < 9) break;
      const len = this.buf[8]!;
      const total = 9 + len + 1;
      if (this.buf.length < total) break;
      let sum = 0;
      for (let i = 0; i < total - 1; i++) sum += this.buf[i]!;
      if ((sum & 0xff) === this.buf[total - 1] && this.buf[6] === VERSION) {
        out.push({ type: this.buf[7]!, data: this.buf.slice(9, 9 + len) });
        this.buf = this.buf.subarray(total);
      } else {
        this.buf = this.buf.subarray(1); // bad packet: resync on the next header
      }
    }
    return out;
  }

  private findHeader(): number {
    outer: for (let i = 0; i + HEADER.length <= this.buf.length; i++) {
      for (let j = 0; j < HEADER.length; j++) if (this.buf[i + j] !== HEADER[j]) continue outer;
      return i;
    }
    return -1;
  }
}

/** A byte stream both ways: an adb socket in the browser, a simulation in the tests. */
export interface ByteStream {
  write(data: Uint8Array): Promise<void>;
  /** Registers the receiver for incoming bytes (called once). */
  onData(cb: (chunk: Uint8Array) => void): void;
  close(): Promise<void>;
}

export class ImprovSerialClient {
  private reader = new PacketReader();
  private waiters: { match: (p: Packet) => boolean; resolve: (p: Packet) => void }[] = [];
  /** Packets nobody was waiting for yet (a scan's results arrive back to back). */
  private inbox: Packet[] = [];
  state = 0;
  lastError = 0;
  onState: (state: number) => void = () => {};

  constructor(private readonly io: ByteStream) {
    io.onData((chunk) => {
      for (const p of this.reader.push(chunk)) {
        if (p.type === PacketType.State) { this.state = p.data[0] ?? 0; this.onState(this.state); }
        if (p.type === PacketType.Error) this.lastError = p.data[0] ?? 0;
        const i = this.waiters.findIndex((w) => w.match(p));
        if (i >= 0) this.waiters.splice(i, 1)[0]!.resolve(p);
        else if (p.type === PacketType.RpcResult || p.type === PacketType.Error) {
          this.inbox.push(p);
          if (this.inbox.length > 64) this.inbox.shift();
        }
      }
    });
  }

  private next(match: (p: Packet) => boolean, timeoutMs: number, what: string): Promise<Packet> {
    const queued = this.inbox.findIndex(match);
    if (queued >= 0) return Promise.resolve(this.inbox.splice(queued, 1)[0]!);
    return new Promise((resolve, reject) => {
      const w = { match, resolve: (p: Packet) => { clearTimeout(t); resolve(p); } };
      const t = setTimeout(() => {
        this.waiters.splice(this.waiters.indexOf(w), 1);
        reject(new Error(`the panel did not answer (${what})`));
      }, timeoutMs);
      this.waiters.push(w);
    });
  }

  private static errorOrNot(p: Packet): string | undefined {
    return p.type === PacketType.Error && p.data[0] ? ERRORS[p.data[0]] ?? `error ${p.data[0]}` : undefined;
  }

  /** Drops leftovers of an earlier request (e.g. a scan that timed out). */
  private fresh() { this.inbox = []; }

  async requestState(timeoutMs = 5000): Promise<number> {
    const p = this.next((x) => x.type === PacketType.State, timeoutMs, 'state');
    await this.io.write(rpc(Rpc.RequestState));
    return (await p).data[0] ?? 0;
  }

  async info(timeoutMs = 5000): Promise<DeviceInfo> {
    this.fresh();
    const p = this.next((x) => x.type === PacketType.RpcResult && x.data[0] === Rpc.RequestInfo, timeoutMs, 'device info');
    await this.io.write(rpc(Rpc.RequestInfo));
    const [firmware = '', version = '', chip = '', name = ''] = parseResult((await p).data).strings;
    return { firmware, version, chip, name };
  }

  /** Networks the panel sees: one result per network, then an empty result. */
  async scan(timeoutMs = 20_000): Promise<WifiNetwork[]> {
    this.fresh();
    const found = new Map<string, WifiNetwork>();
    const until = Date.now() + timeoutMs;
    const first = this.next((x) => x.type === PacketType.RpcResult && x.data[0] === Rpc.ScanWifi, timeoutMs, 'Wi-Fi scan');
    await this.io.write(rpc(Rpc.ScanWifi));
    for (let p = await first; ; p = await this.next((x) => x.type === PacketType.RpcResult && x.data[0] === Rpc.ScanWifi, Math.max(1, until - Date.now()), 'Wi-Fi scan')) {
      const r = parseResult(p.data);
      if (r.strings.length === 0) break;
      const [ssid = '', rssi = '0', auth = 'NO'] = r.strings;
      const n = { ssid, rssi: Number(rssi), secure: auth.toUpperCase() === 'YES' };
      const prev = found.get(ssid);
      if (ssid && (!prev || n.rssi > prev.rssi)) found.set(ssid, n);
    }
    return [...found.values()].sort((a, b) => b.rssi - a.rssi);
  }

  /**
   * Sends the network; resolves with the panel's redirect URL (may be empty) once it is provisioned,
   * or throws with the panel's error (e.g. wrong password).
   */
  async provision(ssid: string, psk: string, onProgress: (s: string) => void = () => {}, timeoutMs = 60_000): Promise<string> {
    this.fresh();
    const done = this.next((x) => (x.type === PacketType.RpcResult && x.data[0] === Rpc.WifiSettings) || !!ImprovSerialClient.errorOrNot(x), timeoutMs, 'Wi-Fi');
    const prev = this.onState;
    this.onState = (s) => { prev(s); if (s === State.Provisioning) onProgress(`Connecting to ${ssid}…`); };
    try {
      await this.io.write(wifiSettings(ssid, psk));
      const p = await done;
      const err = ImprovSerialClient.errorOrNot(p);
      if (err) throw new Error(err);
      return parseResult(p.data).strings[0] ?? '';
    } finally {
      this.onState = prev;
    }
  }

  close(): Promise<void> {
    return this.io.close();
  }
}
