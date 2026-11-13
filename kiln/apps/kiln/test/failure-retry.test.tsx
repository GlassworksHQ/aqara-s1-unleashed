// @vitest-environment jsdom
// "Try again" after a failed write starts the next attempt clean, but keeps the panel's history: a
// device list closed on the retry asks for recovery mode, says this attempt wrote nothing and an
// earlier one did, and keeps Undo.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoSession, enterDemo } from '../src/wizard/demo';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {}); // Session.fail logs the error
});
afterEach(() => cleanup());

describe('Try again after a failed write', () => {
  it('a device list closed on the retry asks for recovery mode, and keeps Undo', async () => {
    const s = demoSession('error-written'); // its Connect is a picker closed with nothing picked
    render(<Wizard session={s} frame={s1PlusUi} />);
    await act(() => enterDemo(s, 'error-written'));
    expect(screen.getByRole('heading', { name: 'Something went wrong while writing.' })).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText(/An earlier try already changed the panel\./)).toBeTruthy(); // not "Nothing is written yet."
    expect(screen.queryByText(/Nothing is written yet/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }));

    expect(await screen.findByRole('heading', { name: 'No panel in recovery mode.' })).toBeTruthy();
    expect(screen.getByText(/hold the board button first/, { selector: '.lede' }).textContent)
      .toBe('This attempt wrote nothing; an earlier try did. Unplug it, hold the board button first, then plug in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(screen.getAllByRole('button', { name: 'Undo with your backup' })[0]!.className).toMatch(/ember/);
    expect(s.store.state.failure).toMatchObject({ at: 1, wrote: true });
    s.close();
  });
});
