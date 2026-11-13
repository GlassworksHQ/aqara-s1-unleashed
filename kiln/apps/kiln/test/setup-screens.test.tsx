// @vitest-environment jsdom
// The set-up forms follow what the panel reports: a Wi-Fi pick a rescan dropped goes, with its
// password, and the Home Assistant address follows discovery until the person types one. The radio
// step's Skip waits for a radio change to finish.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoSession } from '../src/wizard/demo';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
afterEach(() => cleanup());

const nets = (...ssids: string[]) => ssids.map((ssid, i) => ({ ssid, rssi: -50 - i * 10, secure: true }));
const checked = () => screen.getAllByRole('radio').find((r) => r.getAttribute('data-state') === 'checked')?.textContent;

describe('the set-up forms', () => {
  it('Wi-Fi: a pick that a rescan no longer lists is replaced', () => {
    const s = demoSession('connect');
    render(<Wizard session={s} frame={s1PlusUi} />);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net', 'Home Net 5G') } }));
    fireEvent.click(screen.getByRole('radio', { name: /Home Net 5G/ }));
    expect(checked()).toMatch(/Home Net 5G/);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net') } }));
    expect(checked()).toMatch(/^Home Net(?! 5G)/);
    s.close();
  });

  it('Wi-Fi: the replaced pick\'s password goes with it', () => {
    const s = demoSession('connect');
    render(<Wizard session={s} frame={s1PlusUi} />);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net', 'Home Net 5G') } }));
    fireEvent.click(screen.getByRole('radio', { name: /Home Net 5G/ }));
    const password = () => document.querySelector<HTMLInputElement>('input[type=password]')!;
    fireEvent.change(password(), { target: { value: 'example-pass' } });
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net') } }));
    expect(checked()).toMatch(/^Home Net(?! 5G)/);
    expect(password().value).toBe('');
    fireEvent.change(password(), { target: { value: 'example-pass' } });
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net', 'Neighbours') } }));
    expect(password().value).toBe('example-pass'); // a rescan that keeps the pick keeps what was typed
    s.close();
  });

  it('Radio: Skip and Finish are off while a radio change runs', async () => {
    const s = demoSession('radio');
    render(<Wizard session={s} frame={s1PlusUi} />);
    await act(() => s.route('radio'));
    const skip = () => screen.getByRole('button', { name: 'Skip, leave it as it is' }) as HTMLButtonElement;
    const finish = () => screen.getByRole('button', { name: 'Finish' }) as HTMLButtonElement;
    expect(skip().disabled).toBe(false);
    const setup = s.store.state.setup;
    act(() => s.store.dispatch({ type: 'setup', setup: { ...setup, busy: true } as typeof setup })); // what applyRadio shows while the panel switches
    expect(s.store.state.busy).toBeUndefined(); // the step's own busy, not a click in flight
    expect(skip().disabled).toBe(true);
    expect(finish().disabled).toBe(true);
    s.close();
  });

  it('Home Assistant: the address follows discovery until the person types', () => {
    const s = demoSession('connect');
    const ha = (discovered = '') => ({ discovered, url: '', signedIn: false, signinUrl: '' });
    render(<Wizard session={s} frame={s1PlusUi} />);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'ha', ha: ha() } }));
    const input = () => screen.getByRole('textbox') as HTMLInputElement;
    expect(input().value).toBe('http://homeassistant.local:8123');
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'ha', ha: ha('http://192.0.2.10:8123') } }));
    expect(input().value).toBe('http://192.0.2.10:8123');
    fireEvent.change(input(), { target: { value: 'http://192.0.2.20:8123' } });
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'ha', ha: ha('http://192.0.2.30:8123') } }));
    expect(input().value).toBe('http://192.0.2.20:8123');
    s.close();
  });
});
