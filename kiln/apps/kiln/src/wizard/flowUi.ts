// kiln-core's FlowUi, as wizard actions. The engine calls these; the reducer turns them into screens.
import type { AskKind, FlowUi } from '@glassworks/kiln-core';
import type { Action } from './state';

const notPicked = (e: unknown) => e instanceof DOMException && e.name === 'NotFoundError';

/**
 * The "your turn" sheet's other half. WebUSB opens a new device only from a click, so the engine
 * hands over an `action` that calls `requestDevice()`, and the sheet's button calls `click()`.
 * `click()` starts `action` before anything else, with no `await` in between, so the browser still
 * counts the call as part of the click (test/in-click.test.tsx guards this).
 */
export class AskBridge {
  private pending?: { action: () => Promise<unknown>; resolve: (v: unknown) => void; signal: AbortSignal };

  constructor(private readonly dispatch: (a: Action) => void) {}

  open<T>(kind: AskKind, action: () => Promise<T>, signal: AbortSignal): Promise<T> {
    return new Promise<T>((resolve) => {
      const end = () => {
        if (this.pending?.signal !== signal) return;
        this.pending = undefined;
        this.dispatch({ type: 'ask-end' });
      };
      // Found by itself after all (the browser had allowed it before): the sheet just goes.
      signal.addEventListener('abort', end, { once: true });
      this.pending = { action, resolve: resolve as (v: unknown) => void, signal };
      this.dispatch({ type: 'ask', kind });
    });
  }

  /** Call straight from the button's onClick. */
  click(): Promise<void> {
    const p = this.pending;
    if (!p) return Promise.resolve();
    const run = p.action();
    this.dispatch({ type: 'ask-busy' });
    return run.then(
      (v) => {
        if (this.pending === p) { this.pending = undefined; this.dispatch({ type: 'ask-end' }); }
        p.resolve(v);
      },
      (e: unknown) => {
        this.dispatch({ type: 'ask-error', error: notPicked(e) ? 'Nothing was picked. Click the button again.' : String((e as Error)?.message ?? e) });
      },
    );
  }
}

const stamp = () => new Date().toTimeString().slice(0, 8);

/** The adapter InstallFlow talks to. */
export function flowUi(dispatch: (a: Action) => void, asks: AskBridge): FlowUi & { note(line: string): void } {
  return {
    log: (line) => dispatch({ type: 'log', line }),
    /** A line of the wizard's own, stamped like the engine's. */
    note: (line) => dispatch({ type: 'log', line: `${stamp()} ${line}` }),
    progress: (p) => dispatch({ type: 'progress', p }),
    partition: (part, state, info) => dispatch({ type: 'partition', part, state, info }),
    ask: (kind, action, signal) => asks.open(kind, action, signal),
  };
}
