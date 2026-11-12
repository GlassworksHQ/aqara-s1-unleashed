// @vitest-environment jsdom
// A double click starts one action. A second Connect used to open a second picker, whose rejection
// showed "Nothing was written" while the first flow went on underneath.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { s1Plus } from '@glassworks/aqara-s1-plus-installer';
import { FelDevice } from '@glassworks/fel';
import { FakeBrom, makeSpl } from '@glassworks/fel/fakes';
import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { Session } from '../src/wizard/session';
import { initial } from '../src/wizard/state';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: { requestDevice: () => new Promise(() => {}), getDevices: async () => [] } });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => cleanup());

const settle = () => act(() => new Promise((r) => setTimeout(r, 50)));
/** Chrome refuses a second picker while one is open. */
const refused = () => Promise.reject(new DOMException('A chooser is already open.', 'NotFoundError'));

function setup(flow: 'install' | 'undo' = 'install') {
  let felCalls = 0;
  const requestAdb = vi.fn(() => new Promise<never>(() => {}));
  const session = new Session({
    recipe: s1Plus, frame: s1PlusUi,
    requestFel: () => (felCalls++ === 0 ? Promise.resolve(new FelDevice(new FakeBrom())) : refused()),
    asset: async (n) => (n === 'uboot-spl' ? makeSpl(0x9000) : new Uint8Array(200_000).fill(7)),
    hasAsset: async () => true,
    backups: { save: async () => {}, original: async () => ({ id: 's1-backup-20261112-090000', created: '', bootWasPatched: false, files: [] }),
      files: async () => [{ part: 'boot', data: new Uint8Array(4), sha256: '', crc: 0 }] },
    loadKey: async () => ({ publicKey: 'QAAAA test@example' }) as never,
    sleep: () => new Promise((r) => setTimeout(r, 0)),
    devices: { knownFastboot: async () => undefined, requestFastboot: () => new Promise(() => {}), knownAdb: async () => undefined, requestAdb },
  }, initial(flow));
  render(<Wizard session={session} frame={s1PlusUi} routes={false} />);
  return { session, felCalls: () => felCalls, requestAdb };
}

describe('a double click starts one action', () => {
  it('Connect: one flow, and no error card', async () => {
    const { session, felCalls } = setup();
    await settle();
    const connect = screen.getByRole('button', { name: 'Connect' });
    fireEvent.click(connect);
    fireEvent.click(connect);
    await act(async () => { for (let i = 0; i < 400 && !session.store.state.ask; i++) await new Promise((r) => setTimeout(r, 5)); });
    expect(felCalls()).toBe(1);
    expect(session.store.state.screen).toBe('firing');
    expect(session.store.state.ask?.kind).toBe('fastboot');
    expect(screen.queryByText(/Nothing was written/)).toBeNull();
  });

  it('"Choose the panel": one picker', async () => {
    const { session } = setup();
    await settle();
    const action = vi.fn(() => new Promise(() => {}));
    void session.asks.open('fastboot', action, new AbortController().signal);
    await settle();
    const choose = screen.getByRole('button', { name: 'Choose the panel' });
    fireEvent.click(choose);
    fireEvent.click(choose);
    expect(action).toHaveBeenCalledTimes(1);
  });

  it('Undo, keeping the settings: one adb picker', async () => {
    const { session, requestAdb } = setup('undo');
    await act(() => session.undoFromBrowser());
    act(() => session.store.dispatch({ type: 'undo', patch: { depth: 'keep' } }));
    const give = screen.getByRole('button', { name: s1PlusUi.undo.keep.button });
    fireEvent.click(give);
    fireEvent.click(give);
    await settle();
    expect(requestAdb).toHaveBeenCalledTimes(1);
  });
});
