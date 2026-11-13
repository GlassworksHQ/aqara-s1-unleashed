// The S1 Kiosk app's real replies to what Kiln sends (the app's SetupChannelStepsTest pins them in
// s1-kiosk-transcript.json), played back through this package's client. If the app's side of the
// protocol changes, that test fails; if what this client needs from the replies changes, this one does.
import { describe, expect, it } from 'vitest';
import type { ByteStream } from 'improv-serial';
import { SetupChannel } from '../src/channel';
import transcript from './s1-kiosk-transcript.json';

type Entry = { request: { id: number; op: string } & Record<string, unknown>; reply: Record<string, unknown> };

/** Answers each request with the reply the app gave to the same request. */
function recordedApp(entries: Entry[]): ByteStream & { sent: object[] } {
  let cb: (c: Uint8Array) => void = () => {};
  const self = {
    sent: [] as object[],
    onData: (f: (c: Uint8Array) => void) => { cb = f; },
    close: async () => {},
    write: async (d: Uint8Array) => {
      for (const line of new TextDecoder().decode(d).split('\n').filter(Boolean)) {
        const m = JSON.parse(line);
        self.sent.push(m);
        const hit = entries.find((e) => JSON.stringify(e.request) === JSON.stringify(m));
        if (hit) queueMicrotask(() => cb(new TextEncoder().encode(`${JSON.stringify(hit.reply)}\n`)));
      }
    },
  };
  return self;
}

describe('the S1 Kiosk app, as recorded', () => {
  it("answers Kiln's setup flow the way the client expects", async () => {
    const app = recordedApp(transcript as Entry[]);
    const c = new SetupChannel(app);
    const first = await c.getState();
    expect(first.ha.url).toBe('');
    expect(first.radio.options.map((o) => o.id)).toEqual(['off', 'zigbee_tcp', 'thread', 'zigbee_router', 'thread_router']);
    expect(first.radio.helper).toBe(false);

    const ha = await c.setHa('http://homeassistant.local:8123');
    expect(ha.ha.url).toBe('http://homeassistant.local:8123');
    expect(ha.ha.signinUrl).toBe('http://192.0.2.10:8099/signin');
    expect((await c.getState()).ha.signedIn).toBe(false);

    // No root helper on this panel: only Off works, and the refusal says why.
    await expect(c.setRadio('zigbee_tcp')).rejects.toThrow(/root helper/);
    expect((await c.setRadio('off')).radio.mode).toBe('off');

    // Every request the client sent is one the app answered (ids and arguments as recorded).
    expect(app.sent).toEqual((transcript as Entry[]).map((e) => e.request));
  });
});
