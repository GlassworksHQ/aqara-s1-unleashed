// A panel command a signal cancels at any point: while adb opens its socket (an open that never
// answers must not outlive the signal), and after. A process that arrives late is closed.
import { ReadableStream } from '@yume-chan/stream-extra';
import { describe, expect, it, vi } from 'vitest';
import { run, type Spawner } from '../src/run';

const output = (text: string) => new ReadableStream<Uint8Array>({ start(c) { c.enqueue(new TextEncoder().encode(text)); c.close(); } });

describe('run', () => {
  it('returns the output and the exit code', async () => {
    const spawner: Spawner = { spawn: async () => ({ output: output('package:/data/app/base.apk\n__S1RC=0\n'), kill: () => {} }) };
    expect(await run(spawner, 'pm path sh.edm.s1kiosk')).toEqual({ out: 'package:/data/app/base.apk\n', code: 0 });
  });

  it('rejects at once when the signal aborts while the socket is still opening, and closes it if it opens later', async () => {
    let opened!: (p: { output: ReadableStream<Uint8Array>; kill(): void }) => void;
    const spawner: Spawner = { spawn: () => new Promise((resolve) => { opened = resolve; }) }; // createSocket that never answers
    const ac = new AbortController();
    const going = run(spawner, 'dumpsys package sh.edm.s1kiosk', ac.signal);
    setTimeout(() => ac.abort(new Error('the panel did not answer in time')), 10);
    await expect(going).rejects.toThrow('the panel did not answer in time');
    const kill = vi.fn();
    opened({ output: output(''), kill });
    await new Promise((r) => setTimeout(r, 0));
    expect(kill).toHaveBeenCalledOnce();
  }, 2000);

  it('rejects at once when the signal aborts while the output is still coming', async () => {
    const spawner: Spawner = { spawn: async () => ({ output: new ReadableStream<Uint8Array>({ start() {} }), kill: () => {} }) };
    const ac = new AbortController();
    const going = run(spawner, 'dumpsys wifi', ac.signal);
    setTimeout(() => ac.abort(new Error('late')), 10);
    await expect(going).rejects.toThrow('late');
  }, 2000);
});
