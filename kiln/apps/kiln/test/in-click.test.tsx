// @vitest-environment jsdom
// #27: WebUSB opens a new device only from a click, so every button that leads to requestDevice()
// must reach it while the click is still being dispatched, with no `await` in front. These click the
// real buttons, through the real device modules (FelDevice, Fastboot, ya-webadb's manager), and
// record whether navigator.usb.requestDevice ran inside the click.
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

let inClick = false;
const calls: boolean[] = [];

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', {
    configurable: true,
    value: {
      requestDevice: vi.fn(() => { calls.push(inClick); return new Promise(() => {}); }),
      getDevices: async () => [],
      addEventListener: () => {},
      removeEventListener: () => {},
    },
  });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
});
afterEach(() => { cleanup(); calls.length = 0; });

/** Clicks like a person: the flag is up only while the browser dispatches the event. */
async function click(el: HTMLElement) {
  inClick = true;
  try { fireEvent.click(el); } finally { inClick = false; }
  await new Promise((r) => setTimeout(r, 0)); // a late call would land here, and record false
}

async function setup(flow: 'install' | 'undo' = 'install') {
  // imported after navigator.usb exists: ya-webadb reads it when its module loads
  const [{ FelDevice }, { webUsbDevices }, { s1Plus }, { Session }, { Wizard }, { s1PlusUi }, { initial }] = await Promise.all([
    import('@glassworks/fel'), import('@glassworks/kiln-core'), import('@glassworks/aqara-s1-plus-installer'),
    import('../src/wizard/session'), import('../src/wizard/Wizard'), import('../src/frames/aqara-s1-plus'), import('../src/wizard/state'),
  ]);
  const key = { publicKey: 'QAAAA test@example', store: { generateKey: () => { throw new Error('unused'); }, iterateKeys: () => [] } };
  const meta = { id: 's1-backup-20261112-090000', created: '', bootWasPatched: false, files: [] };
  const session = new Session({
    recipe: s1Plus, frame: s1PlusUi, devices: webUsbDevices,
    requestFel: () => FelDevice.request(),
    asset: async () => new Uint8Array(0), hasAsset: async () => false,
    backups: { save: async () => {}, original: async () => meta, files: async () => [{ part: 'boot', data: new Uint8Array(4), sha256: '', crc: 0 }] },
    loadKey: async () => key as never,
  }, initial(flow));
  render(<Wizard session={session} frame={s1PlusUi} base="/aqara-s1-plus" />);
  await new Promise((r) => setTimeout(r, 0)); // the key loads
  return { session, webUsbDevices, key };
}

describe('requestDevice() runs inside the click', () => {
  it('Connect (recovery mode)', async () => {
    await setup();
    await click(screen.getByRole('button', { name: 'Connect' }));
    expect(calls).toEqual([true]);
  });

  it.each(['fastboot', 'adb'] as const)('the "your turn" sheet for %s', async (kind) => {
    const { session, webUsbDevices, key } = await setup();
    // what InstallFlow hands over (kiln-core flow.ts, findOrAsk)
    const request: () => Promise<unknown> = kind === 'fastboot' ? () => webUsbDevices.requestFastboot() : () => webUsbDevices.requestAdb(key as never);
    void session.asks.open(kind, request, new AbortController().signal);
    await click(await screen.findByRole('button', { name: 'Choose the panel' }));
    expect(calls).toEqual([true]);
  });

  it('Undo, keeping the settings: giving back Aqara\'s apps over adb', async () => {
    const { session } = await setup('undo');
    await session.undoFromBrowser();
    session.store.dispatch({ type: 'undo', patch: { depth: 'keep' } });
    await click(await screen.findByRole('button', { name: 'Give back Aqara’s apps' }));
    expect(calls).toEqual([true]);
  });
});
