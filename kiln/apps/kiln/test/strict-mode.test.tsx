// @vitest-environment jsdom
// React's StrictMode (on in `next dev`) runs component setup and effects twice. The page must still
// load one adb key (two would race into IndexedDB) and follow its #/ route once.
import { StrictMode } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const loadKey = vi.hoisted(() => vi.fn(async () => ({ publicKey: 'QAAAA test@example' })));
vi.mock('@glassworks/adb', async (orig) => ({ ...(await orig<object>()), loadOrCreateAdbKey: loadKey }));

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: { requestDevice: () => new Promise(() => {}), getDevices: async () => [] } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
});
afterEach(() => cleanup());

const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));

describe('under StrictMode', () => {
  it('the S1 page loads its adb key once', async () => {
    const { S1 } = await import('../src/wizard/S1');
    render(<StrictMode><S1 /></StrictMode>);
    await settle();
    expect(loadKey).toHaveBeenCalledTimes(1);
  });

  it('a #/ route runs once', async () => {
    const [{ Session }, { Wizard }, { s1PlusUi }, { s1Plus }] = await Promise.all([
      import('../src/wizard/session'), import('../src/wizard/Wizard'), import('../src/frames/aqara-s1-plus'), import('@glassworks/aqara-s1-plus-installer'),
    ]);
    const knownAdb = vi.fn(async () => undefined);
    const session = new Session({
      recipe: s1Plus, frame: s1PlusUi, requestFel: () => new Promise(() => {}),
      asset: async () => new Uint8Array(0), hasAsset: async () => false,
      backups: { save: async () => {}, original: async () => undefined, files: async () => [] },
      loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
      devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb, requestAdb: () => new Promise(() => {}) },
    });
    const route = vi.spyOn(session, 'route');
    history.replaceState(null, '', '/aqara-s1-plus#/resume');
    render(<StrictMode><Wizard session={session} frame={s1PlusUi} /></StrictMode>);
    await settle();
    expect(route).toHaveBeenCalledTimes(1);
    expect(knownAdb).toHaveBeenCalledTimes(1);
    expect(session.store.state.ask?.kind).toBe('connect');
  });
});
