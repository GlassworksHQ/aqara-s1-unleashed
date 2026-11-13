// A shell command on the panel that a signal can cancel at any point, the socket's open included.
// @yume-chan/adb 2.6.4's spawn() awaits createSocket() before it looks at the signal, and that open
// has no timeout of its own, so the signal alone can't end a wedged open.
import { ConcatStringStream, TextDecoderStream, type ReadableStream } from '@yume-chan/stream-extra';

/** What run needs of adb's none-protocol subprocess service. */
export interface Spawner {
  spawn(command: string, signal?: AbortSignal): Promise<{ output: ReadableStream<Uint8Array>; kill(): unknown }>;
}

const RC = '__S1RC=';

/** Settles with `p`, or rejects with the signal's reason as soon as it aborts. `late` gets a `p` that settles after. */
function untilAbort<T>(p: Promise<T>, signal: AbortSignal | undefined, late: (v: T) => void = () => {}): Promise<T> {
  if (!signal) return p;
  return new Promise<T>((resolve, reject) => {
    const abort = () => { reject(signal.reason); void p.then(late, () => {}); };
    if (signal.aborted) { abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    p.then(
      (v) => { signal.removeEventListener('abort', abort); resolve(v); },
      (e) => { signal.removeEventListener('abort', abort); reject(e); },
    );
  });
}

/** Runs `cmd` and returns its output (stdout + stderr) and exit code; `signal` rejects it at once. */
export async function run(spawner: Spawner, cmd: string, signal?: AbortSignal): Promise<{ out: string; code: number }> {
  signal?.throwIfAborted();
  // a process that arrives after the abort is closed, so nothing keeps running on the panel
  const proc = await untilAbort(spawner.spawn(`${cmd}; echo "${RC}$?"`, signal), signal, (p) => { void Promise.resolve(p.kill()).catch(() => {}); });
  const text = await untilAbort(proc.output.pipeThrough(new TextDecoderStream()).pipeThrough(new ConcatStringStream()), signal);
  const i = text.lastIndexOf(RC);
  if (i < 0) return { out: text, code: -1 };
  return { out: text.slice(0, i), code: Number.parseInt(text.slice(i + RC.length), 10) };
}
