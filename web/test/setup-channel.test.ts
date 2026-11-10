import { describe, expect, it } from 'vitest';
import type { ByteStream } from '../src/improv';
import { SetupChannel, validHaUrl, type SetupState } from '../src/setup/channel';

const base: SetupState = {
  ip: '192.168.1.50',
  ha: { discovered: 'http://homeassistant.local:8123', url: '', signedIn: false, signinUrl: 'http://192.168.1.50:8099/signin' },
  radio: { mode: 'off', status: 'Off', helper: false, options: [{ id: 'off', label: 'Off', detail: '' }] },
};

/** The app's side: JSON lines, replies echo the id, pushes state events. */
function fakeApp(): ByteStream & { sent: object[]; push(o: object): void } {
  let cb: (c: Uint8Array) => void = () => {};
  const state = structuredClone(base);
  const self = {
    sent: [] as object[],
    onData: (f: (c: Uint8Array) => void) => { cb = f; },
    close: async () => {},
    push: (o: object) => cb(new TextEncoder().encode(`${JSON.stringify(o)}\n`)),
    write: async (d: Uint8Array) => {
      for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
        const m = JSON.parse(line);
        self.sent.push(m);
        if (m.op === 'set_radio' && !state.radio.helper && m.mode !== 'off') { queueMicrotask(() => self.push({ id: m.id, ok: false, error: 'needs the root helper' })); continue; }
        if (m.op === 'set_ha') state.ha.url = m.url;
        // reply split across two chunks, to exercise line reassembly
        const out = new TextEncoder().encode(`${JSON.stringify({ id: m.id, ok: true, state })}\n`);
        queueMicrotask(() => { cb(out.subarray(0, 7)); cb(out.subarray(7)); });
      }
    },
  };
  return self;
}

describe('setup channel', () => {
  it('state, set_ha round trip; replies matched by id', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    expect((await c.getState()).ha.discovered).toBe('http://homeassistant.local:8123');
    expect((await c.setHa('http://ha.local:8123')).ha.url).toBe('http://ha.local:8123');
    expect(app.sent).toEqual([{ id: 1, op: 'state' }, { id: 2, op: 'set_ha', url: 'http://ha.local:8123' }]);
  });

  it('errors come back as rejections; pushed events update the state', async () => {
    const app = fakeApp();
    const c = new SetupChannel(app);
    await expect(c.setRadio('zigbee_tcp')).rejects.toThrow(/root helper/);
    const seen: boolean[] = [];
    c.onState = (s) => seen.push(s.ha.signedIn);
    app.push({ event: 'state', state: { ...base, ha: { ...base.ha, signedIn: true } } });
    expect(seen).toEqual([true]);
    expect(c.state?.ha.signedIn).toBe(true);
  });

  it('validates addresses', () => {
    expect(validHaUrl('http://homeassistant.local:8123')).toBe(true);
    expect(validHaUrl('https://ha.example.com/dash')).toBe(true);
    expect(validHaUrl('homeassistant.local')).toBe(false);
    expect(validHaUrl("http://x'; reboot")).toBe(false);
  });
});
