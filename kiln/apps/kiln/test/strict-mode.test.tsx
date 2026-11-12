// @vitest-environment jsdom
// React's StrictMode (on in `next dev`) runs component setup and effects twice. The page must still
// load one adb key (two would race into IndexedDB). Routes under StrictMode: navigation.test.tsx.
import { StrictMode } from 'react';
import { act, cleanup, render } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

const loadKey = vi.hoisted(() => vi.fn(async () => ({ publicKey: 'QAAAA test@example' })));
vi.mock('@glassworks/adb', async (orig) => ({ ...(await orig<object>()), loadOrCreateAdbKey: loadKey }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: () => {} }), usePathname: () => '/aqara-s1-plus' }));

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
});
