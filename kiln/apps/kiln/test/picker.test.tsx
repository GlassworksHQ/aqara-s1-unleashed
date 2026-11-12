// @vitest-environment jsdom
// The picker: s1.edm.sh's old #/ links, the running install's way back, and Continue during one.
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const router = vi.hoisted(() => ({ replace: vi.fn(), push: vi.fn() }));
vi.mock('next/navigation', () => ({ useRouter: () => router }));
vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { Picker } from '../src/components/Picker';
import { registry } from '../src/wizard/registry';
import { Store } from '../src/wizard/session';
import { initial } from '../src/wizard/state';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: false, media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
});
beforeEach(() => { router.replace.mockClear(); router.push.mockClear(); });
afterEach(() => { cleanup(); registry.clear(); history.replaceState(null, '', '/'); });

function installing() {
  const store = new Store(initial());
  store.dispatch({ type: 'start', flow: 'install', now: 0 });
  registry.set({ base: '/aqara-s1-plus', name: 'Aqara S1 Plus', session: { store, close: () => {} } as never });
  return store;
}

describe('the picker', () => {
  it.each([
    ['#/install', '/aqara-s1-plus'], ['#/undo', '/aqara-s1-plus/undo'], ['#/resume', '/aqara-s1-plus/set-up'],
    ['#/wifi', '/aqara-s1-plus/set-up/wifi'], ['#/home-assistant', '/aqara-s1-plus/set-up/home-assistant'], ['#/radio', '/aqara-s1-plus/set-up/radio'],
  ])('sends s1.edm.sh\'s %s to %s', (hash, to) => {
    history.replaceState(null, '', `/${hash}`);
    render(<Picker />);
    expect(router.replace).toHaveBeenCalledWith(to);
  });

  it('shows the frames, and Continue goes to the picked one\'s start', () => {
    render(<Picker />);
    expect(screen.getByRole('radio', { name: /Aqara S1 Plus, Beta/ })).toHaveProperty('dataset.state', 'checked');
    expect(screen.getByRole('radio', { name: /lululemon Studio Mirror, Planned, not installable/ })).toHaveProperty('disabled', true);
    expect(screen.queryByText(/still working/)).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenCalledWith('/aqara-s1-plus');
  });

  it('during an install: says so, leads back to it, and Continue goes there too', () => {
    const store = installing();
    render(<Picker />);
    expect(screen.getByText('Kiln is still working on your Aqara S1 Plus.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Back to it' }).getAttribute('href')).toBe('/aqara-s1-plus/install');
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(router.push).toHaveBeenCalledWith('/aqara-s1-plus/install');
    act(() => store.dispatch({ type: 'done', done: { title: 'Done.', body: '' } }));
    expect(screen.queryByText(/still working/)).toBeNull();
  });

  it('keeps the last stopped run\'s details', () => {
    const store = installing();
    store.dispatch({ type: 'fail', failure: { title: 'It disconnected.', body: 'Nothing was written.', wrote: false, tips: [], raw: 'x', at: 2 } });
    store.dispatch({ type: 'go', screen: 'connect' });
    render(<Picker />);
    expect(screen.getByRole('status').textContent).toMatch(/The last run stopped: It disconnected\. Download its log/);
  });
});
