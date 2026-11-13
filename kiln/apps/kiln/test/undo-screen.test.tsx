// @vitest-environment jsdom
// The Undo screen's backup links: off while a file is checked, off once the apps were given back
// against the chosen backup, and an unreadable browser backup is said on the screen.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoSession, enterDemo, type Demo } from '../src/wizard/demo';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

async function undo(which: Demo) {
  const s = demoSession(which);
  render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
  await act(() => enterDemo(s, which));
  return s;
}
const link = (name: RegExp) => screen.getByRole('button', { name }) as HTMLButtonElement;
const pick = (name: string) => fireEvent.change(document.querySelector('input[type=file]')!, { target: { files: [new File(['not a zip'], name)] } });

describe('the Undo screen', () => {
  it('turns "Use this browser\'s" off while a file is checked', async () => {
    const s = await undo('undo');
    expect(s.store.state.undo.source).toBe('browser');
    pick('mine.zip');
    expect(link(/Use this browser/).disabled).toBe(true);
    await vi.waitFor(() => expect(s.store.state.undo.checking).toBe(false)); // this file isn't a backup
    expect(link(/Use this browser/).disabled).toBe(false);
    expect(link(/Another file/).disabled).toBe(false); // a file is always one click away, whatever this browser holds
    s.close();
  });

  it('turns "Another file" off while a file is checked', async () => {
    const s = await undo('connect'); // no backup in this browser
    await act(() => s.openUndo());
    expect(link(/Use a backup file/).disabled).toBe(false);
    pick('mine.zip');
    expect(link(/Another file/).disabled).toBe(true);
    await vi.waitFor(() => expect(s.store.state.undo.checking).toBe(false));
    expect(link(/Another file/).disabled).toBe(false);
    s.close();
  });

  it('keeps the backup once the apps are back: the give-back was checked against it', async () => {
    const s = await undo('undo-apps-back');
    expect(screen.getByText('Aqara\'s apps are back.')).toBeTruthy();
    expect(link(/Use a backup file/).disabled).toBe(true);
    expect(link(/^Continue$/).disabled).toBe(false);
    fireEvent.click(screen.getByRole('radio', { name: /As it came from Aqara/ })); // a depth change doesn't unlock it
    expect(s.store.state.undo.appsBack).toBe(false);
    expect(link(/Use a backup file/).disabled).toBe(true);
    s.close();
  });

  it('turns the backup links off while an action runs (the give-back checks the backup in use)', async () => {
    const s = await undo('undo');
    expect(link(/Use a backup file/).disabled).toBe(false);
    act(() => s.store.dispatch({ type: 'busy', busy: 'undo-continue' }));
    expect(link(/Use a backup file/).disabled).toBe(true);
    s.close();
  });

  it('says when this browser\'s backup can\'t be read', async () => {
    const s = await undo('undo-unreadable');
    expect(screen.getByText('Couldn\'t read the backup in this browser.')).toBeTruthy();
    expect(screen.queryByText('No backup in this browser.')).toBeNull();
    expect(link(/Use a backup file/).disabled).toBe(false);
    expect(link(/^Continue$/).disabled).toBe(true);
    s.close();
  });
});
