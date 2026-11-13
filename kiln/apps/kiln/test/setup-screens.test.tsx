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
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net', 'Home Net 5G') } }));
    fireEvent.click(screen.getByRole('radio', { name: /Home Net 5G/ }));
    expect(checked()).toMatch(/Home Net 5G/);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'wifi', status: '', networks: nets('Home Net') } }));
    expect(checked()).toMatch(/^Home Net(?! 5G)/);
    s.close();
  });

  it('Wi-Fi: the replaced pick\'s password goes with it', () => {
    const s = demoSession('connect');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
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
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
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
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
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

  it('Firing, after an earlier try wrote: no claim that nothing is written yet', () => {
    const s = demoSession('connect');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    act(() => {
      s.store.dispatch({ type: 'changed', by: 'written' });
      s.store.dispatch({ type: 'start', flow: 'install', now: Date.now() });
    });
    expect(document.body.textContent).not.toMatch(/Nothing is written/);
    expect(document.body.textContent).toMatch(/Your backup comes before any write\./);
    s.close();
  });

  it('the rail lists set-up\'s steps; the ones done link back, and not while something runs', () => {
    const s = demoSession('connect');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'ha', ha: { signedIn: false } } }));
    const rail = screen.getByRole('navigation', { name: 'Steps' });
    const links = () => [...rail.querySelectorAll('.sub a')].map((a) => [a.textContent, a.getAttribute('href')]);
    expect(links()).toEqual([['App', '/aqara-s1-plus/set-up'], ['Wi-Fi', '/aqara-s1-plus/set-up/wifi']]);
    expect(rail.querySelector('.sub [aria-current=step]')?.textContent).toBe('Home Assistant');
    act(() => s.store.dispatch({ type: 'busy', busy: 'save-ha' }));
    expect(links()).toEqual([]);
    s.close();
  });

  it('Settings, gone back to after the app went in: shows what was chosen', async () => {
    const s = demoSession('app');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('resume'));
    await act(() => s.installApp({ locale: 'de-DE', timezone: 'Europe/Berlin', clock24: true, keyboard: false }));
    expect(s.store.state.setup.phase).toBe('wifi');
    await act(() => s.route('resume')); // Back, or the rail's App
    expect(screen.getByRole('heading').textContent).toBe('Update S1 Kiosk.'); // the app is on the panel now
    const [language, zone] = screen.getAllByRole('combobox') as HTMLSelectElement[];
    expect([language!.value, zone!.value]).toEqual(['de-DE', 'Europe/Berlin']);
    expect(screen.getByRole('radio', { name: '24-hour' }).getAttribute('data-state')).toBe('checked');
    expect((screen.getByRole('checkbox') as HTMLInputElement).checked).toBe(false);
    s.close();
  }, 20_000);

  it('Wi-Fi: a panel already on Wi-Fi says which first; another network is the other way, not the default', async () => {
    const s = demoSession('wifi');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('wifi'));
    expect(screen.getByRole('heading').textContent).toBe('Connected to Home Net.');
    expect(screen.queryByRole('radio')).toBeNull(); // no network list, no Connect
    expect(document.querySelectorAll('.btn.ember')).toHaveLength(1);
    expect(screen.getByRole('button', { name: 'Continue' }).className).toMatch(/ember/);
    await act(() => s.scanWifi()); // Use another network
    expect(screen.getByRole('heading').textContent).toBe('Which Wi-Fi?');
    expect(screen.getByRole('radio', { name: /Home Net 5G/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Keep Home Net' })).toBeTruthy();
    s.close();
  }, 20_000);

  it('Wi-Fi: a panel not on Wi-Fi goes straight to the networks it can see', async () => {
    const s = demoSession('wifi-new');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('wifi'));
    expect(screen.getByRole('heading').textContent).toBe('Which Wi-Fi?');
    expect(screen.getByRole('radio', { name: /Home Net 5G/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Skip, do it on the panel' })).toBeTruthy();
    s.close();
  }, 20_000);

  it('Installing the app: each phase the panel reports, in order, then Wi-Fi; restarts called expected', async () => {
    const s = demoSession('app');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('resume'));
    act(() => s.store.dispatch({ type: 'setup', setup: { phase: 'settings', update: false } })); // a first install
    const seen: string[] = [];
    const off = s.store.subscribe(() => {
      const st = s.store.state.setup;
      const at = st.phase === 'settings' ? st.installing : undefined;
      if (at && seen.at(-1) !== at) seen.push(at);
    });
    const going = s.installApp({ clock24: true, keyboard: true });
    await act(() => new Promise((r) => setTimeout(r, 1200)));
    expect(s.store.state.setup).toMatchObject({ phase: 'settings', installing: 'install' });
    expect(screen.getByRole('heading').textContent).toBe('Installing S1 Kiosk.');
    expect(document.querySelector('.join li.cur')?.textContent).toBe('Android installs it and optimises it for this panel');
    expect(document.querySelectorAll('.join li.done')).toHaveLength(1);
    expect(screen.getByRole('status').textContent).toMatch(/restarts during this\. That's expected\./);
    await act(() => going);
    off();
    expect(seen).toEqual(['copy', 'install', 'settings', 'start']);
    expect(s.store.state.setup.phase).toBe('wifi');
    s.close();
  }, 20_000);
});
