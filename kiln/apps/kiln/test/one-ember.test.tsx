// @vitest-environment jsdom
// docs/design/kiln: one ember button per screen, for its one main action; everything else is a ghost
// button or a link. Checked on every demo screen that has an action.
import { act, cleanup, render } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

vi.mock('next/link', () => ({ default: ({ href, children, ...p }: { href: string; children: ReactNode }) => <a href={href} {...p}>{children}</a> }));

import { s1PlusUi } from '../src/frames/aqara-s1-plus';
import { demoSession, enterDemo, type Demo } from '../src/wizard/demo';
import type { Session } from '../src/wizard/session';
import { Wizard } from '../src/wizard/Wizard';

beforeAll(() => {
  Object.defineProperty(navigator, 'usb', { configurable: true, value: {} });
  Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
  window.matchMedia = ((q: string) => ({ matches: q.includes('reduce'), media: q, addEventListener() {}, removeEventListener() {} })) as never;
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as never;
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
const open: Session[] = [];
afterEach(() => { cleanup(); for (const s of open.splice(0)) s.close(); });

const embers = () => [...document.querySelectorAll('.btn.ember')].map((b) => b.textContent);

async function show(which: Demo, then?: (s: Session) => Promise<void>) {
  const s = demoSession(which);
  open.push(s);
  render(<Wizard session={s} frame={s1PlusUi} base="/aqara-s1-plus" />);
  await act(() => enterDemo(s, which));
  if (then) await act(() => then(s));
  return s;
}

describe('one ember action per screen', () => {
  it.each<[Demo, string]>([
    ['connect', 'Connect'],
    ['app', 'Install S1 Kiosk'],
    ['wifi', 'Continue'],
    ['wifi-new', 'Connect'],
    ['home-assistant', 'Continue'],
    ['radio', 'Finish'],
    ['radio-next', 'Finish'],
    ['undo', 'Continue'],
    ['undo-connect', 'Connect'],
    ['error', 'Try again'],
    ['error-written', 'Undo with your backup'],
  ])('%s: %s', async (which, action) => {
    await show(which);
    expect(embers()).toEqual([action]);
  }, 20_000);

  it('Done with a backup: the download is the one ember action', async () => {
    await show('done');
    expect(embers()).toEqual(['Download']);
    expect([...document.querySelectorAll('a')].find((a) => a.textContent === 'Back to Kiln')!.className).toMatch(/ghost/);
  });

  it('Done without a backup (undo): Back to Kiln is', async () => {
    await show('undo-done');
    expect(embers()).toEqual(['Back to Kiln']);
  });

  it('Thread router: the panel\'s page leads, the dataset is the how-to under it, Finish is quieter', async () => {
    await show('radio', (s) => s.applyRadio('thread_router'));
    expect(embers()).toEqual(['Open the panel in Home Assistant']);
    const steps = [...document.querySelectorAll('ol.next > li')];
    expect(steps[0]!.querySelector('b')!.textContent).toBe('Give it your Thread network\'s dataset');
    expect(steps[0]!.querySelector('.how b')!.textContent).toBe('Where to copy the dataset');
    expect(steps[0]!.textContent).toMatch(/The dataset contains the network key: paste it only there\./);
    expect([...document.querySelectorAll('button')].find((b) => b.textContent === 'Finish')!.className).toMatch(/ghost/);
  }, 20_000);
});
