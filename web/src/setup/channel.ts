// The kiosk app's setup channel, reached through adb like Improv Serial (src/improv/serial.ts).
// Everything the panel's own onboarding asks for besides Wi-Fi, so the installer can do it from the
// computer: Home Assistant address, sign-in status, radio mode.
//
// Protocol (the app implements the same; keep both in sync):
//   abstract socket `s1kiosk_setup`; the app only accepts adb's shell user (uid 2000) or root.
//   One JSON object per line, UTF-8, both ways. Requests carry an `id`; the reply echoes it.
//     → {"id":1,"op":"state"}
//     ← {"id":1,"ok":true,"state":SetupState}
//     → {"id":2,"op":"set_ha","url":"http://homeassistant.local:8123"}
//     ← {"id":2,"ok":true,"state":SetupState}            or {"id":2,"ok":false,"error":"…"}
//     → {"id":3,"op":"set_radio","mode":"zigbee_tcp"}
//     ← {"id":3,"ok":true,"state":SetupState}            or {"id":3,"ok":false,"error":"…"}
//   The app may also push {"event":"state","state":SetupState} whenever something changes
//   (signed in, radio status, Wi-Fi), so the installer doesn't have to poll.
//   No secrets ever cross this channel (no tokens, no keys, no passwords).
import type { ByteStream } from '../improv/serial';

export const SETUP_SOCKET = 'localabstract:s1kiosk_setup';

export interface RadioOption { id: string; label: string; detail: string; experimental?: boolean }

export interface SetupState {
  /** Panel's Wi-Fi IPv4 address, if connected. */
  ip?: string;
  ha: {
    /** Address the app found on the network by itself (mDNS), if any. */
    discovered?: string;
    /** Address the app uses now (empty until set). */
    url?: string;
    signedIn: boolean;
    /** The panel's sign-in page (`http://<ip>:8099/signin`): the same link as its QR code. */
    signinUrl?: string;
  };
  radio: {
    mode: string;
    status: string;
    /** Modes offered in onboarding (Off, Zigbee coordinator, Thread border router). */
    options: RadioOption[];
    /** False when the root helper is missing: only Off works then. */
    helper: boolean;
  };
}

type Reply = { id: number; ok: boolean; state?: SetupState; error?: string };

export class SetupChannel {
  private buf = '';
  private nextId = 1;
  private pending = new Map<number, (r: Reply) => void>();
  state?: SetupState;
  onState: (s: SetupState) => void = () => {};

  constructor(private readonly io: ByteStream) {
    const dec = new TextDecoder();
    io.onData((chunk) => {
      this.buf += dec.decode(chunk, { stream: true });
      let nl: number;
      while ((nl = this.buf.indexOf('\n')) >= 0) {
        const line = this.buf.slice(0, nl).trim();
        this.buf = this.buf.slice(nl + 1);
        if (!line) continue;
        let msg: Partial<Reply> & { event?: string };
        try { msg = JSON.parse(line); } catch { continue; }
        if (msg.state) { this.state = msg.state; this.onState(msg.state); }
        if (typeof msg.id === 'number') {
          this.pending.get(msg.id)?.(msg as Reply);
          this.pending.delete(msg.id);
        }
      }
    });
  }

  private request(op: string, args: Record<string, string> = {}, timeoutMs = 8000): Promise<SetupState> {
    const id = this.nextId++;
    return new Promise((resolve, reject) => {
      const t = setTimeout(() => { this.pending.delete(id); reject(new Error(`the panel did not answer (${op})`)); }, timeoutMs);
      this.pending.set(id, (r) => {
        clearTimeout(t);
        if (r.ok && r.state) resolve(r.state);
        else reject(new Error(r.error || `${op} failed`));
      });
      void this.io.write(new TextEncoder().encode(`${JSON.stringify({ id, op, ...args })}\n`)).catch(reject);
    });
  }

  getState(): Promise<SetupState> { return this.request('state'); }
  setHa(url: string): Promise<SetupState> { return this.request('set_ha', { url }, 15_000); }
  setRadio(mode: string): Promise<SetupState> { return this.request('set_radio', { mode }, 30_000); }
  close(): Promise<void> { return this.io.close(); }
}

/** http(s)://host[:port][/path], no spaces or quotes. */
export function validHaUrl(u: string): boolean {
  try {
    const x = new URL(u);
    return (x.protocol === 'http:' || x.protocol === 'https:') && !!x.hostname && !/[\s'"]/.test(u);
  } catch { return false; }
}
