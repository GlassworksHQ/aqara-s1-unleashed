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

  it('Installing the app: every APK and phase the panel reports, in order, then Wi-Fi', async () => {
    const s = demoSession('app');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('resume')); // the demo panel has no S1 Kiosk: a first install
    const rows = () => [...document.querySelectorAll('.join li')].map((li) => `${li.className || '-'} ${li.firstChild?.textContent}`);
    const going = s.installApp({ clock24: true, keyboard: true });
    await act(() => new Promise((r) => setTimeout(r, 1200))); // S1 Kiosk's pm install (the demo holds it 2.5 s)
    expect(screen.getByRole('heading').textContent).toBe('Installing S1 Kiosk.');
    expect(rows()).toEqual(['cur Install S1 Kiosk', '- Make it the panel\'s home screen', '- Install the on-screen keyboard', '- Apply your time zone, language and clock', '- First start']);
    expect(document.querySelector('.join li.cur .sub-step')?.textContent).toBe('Android installs it and optimises it for this panel');
    expect(screen.getByRole('status').textContent).toBe('Install S1 Kiosk. Android installs it and optimises it for this panel'); // what a screen reader hears
    expect(document.body.textContent).not.toMatch(/restart/); // no app runs yet, so nothing restarts
    await act(async () => { // the keyboard's turn
      for (let i = 0; i < 300 && !document.querySelector('.join li.cur')?.textContent?.startsWith('Install the on-screen keyboard'); i++) await new Promise((r) => setTimeout(r, 10));
    });
    expect(rows()).toEqual(['done Install S1 Kiosk', 'done Make it the panel\'s home screen', 'cur Install the on-screen keyboard', '- Apply your time zone, language and clock', '- First start']);
    expect(screen.getByRole('status').textContent).toMatch(/^Install the on-screen keyboard\. (Copying it to the panel|Android installs it)$/); // each step is announced
    const phases = (s.store.state.setup as { installing?: { step: string; apk?: string; stage?: string }[] }).installing!.map((p) => p.apk ? `${p.apk} ${p.stage}` : p.step);
    expect(phases.slice(0, 4)).toEqual(['kiosk copy', 'kiosk install', 'home', 'keyboard copy']);
    await act(() => going);
    expect(s.store.state.setup.phase).toBe('wifi');
    s.close();
  }, 20_000);

  it('Updating the app already on the panel: its restarts are called expected', async () => {
    const s = demoSession('app-update');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('resume'));
    expect(screen.getByRole('heading').textContent).toBe('Update S1 Kiosk.');
    const going = s.installApp({ clock24: true, keyboard: false });
    await act(() => new Promise((r) => setTimeout(r, 1200))); // S1 Kiosk's pm install
    expect(screen.getByRole('heading').textContent).toBe('Updating S1 Kiosk.');
    expect(screen.getByRole('status').textContent).toBe('Install S1 Kiosk. Android installs it and optimises it for this panel. The app on the panel closes and restarts during this. That\'s expected.');
    expect([...document.querySelectorAll('.join li')].map((li) => li.firstChild?.textContent)).not.toContain('Install the on-screen keyboard'); // not asked for
    await act(() => going);
    s.close();
  }, 20_000);

  it('Settings: an edit not yet installed survives going to another step and back', async () => {
    const s = demoSession('app-update');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('resume'));
    fireEvent.change(screen.getAllByRole('combobox')[0]!, { target: { value: 'fr-FR' } });
    await act(() => s.route('wifi')); // Forward (the rail), which unmounts the form
    await act(() => s.route('resume')); // Back
    expect((screen.getAllByRole('combobox')[0] as HTMLSelectElement).value).toBe('fr-FR');
    s.close();
  }, 20_000);

  it('Home Assistant: a typed address survives going to another step and back', async () => {
    const s = demoSession('home-assistant');
    render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
    await act(() => s.route('home-assistant'));
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'http://192.0.2.20:8123' } });
    await act(() => s.route('wifi')); // Back (the rail), which unmounts the step
    await act(() => s.route('home-assistant')); // Forward
    expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('http://192.0.2.20:8123');
    s.close();
  }, 20_000);
});
