// @vitest-environment jsdom
// "Try again" after a failed write starts the next attempt clean: a picker closed on the retry asks
// for recovery mode again, it doesn't repeat the last run's "use Undo".
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
  it('a picker closed on the retry says recovery mode, not Undo', async () => {
    const s = demoSession('error-written'); // its Connect is a picker closed with nothing picked
    render(<Wizard session={s} frame={s1PlusUi} />);
    await act(() => enterDemo(s, 'error-written'));
    expect(screen.getByRole('heading', { name: 'Something went wrong while writing.' })).toBeTruthy();
    expect(screen.getAllByRole('button', { name: 'Undo with your backup' })[0]!.className).toMatch(/ember/);

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Connect' }));

    expect(await screen.findByRole('heading', { name: 'No panel in recovery mode.' })).toBeTruthy();
    expect(screen.getByText(/hold the board button first/, { selector: '.lede' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Try again' }).className).toMatch(/ember/);
    expect(s.store.state.failure).toMatchObject({ at: 1, wrote: false });
    s.close();
  });
});
