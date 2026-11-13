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
    ['app-update', 'Update and continue'],
    ['radio-thread-old', 'Finish'],
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
    expect(document.querySelector('.well')!.textContent).toMatch(/The latest backup made in this browser tab\./); // which backup, if the tab saw several
    expect([...document.querySelectorAll('a')].find((a) => a.textContent === 'Back to Kiln')!.className).toMatch(/ghost/);
  });

  it('Done without a backup (undo): Back to Kiln is', async () => {
    await show('undo-done');
    expect(embers()).toEqual(['Back to Kiln']);
  });

  it('Thread router on an app that gets the network from Home Assistant: that leads, and no pasting', async () => {
    await show('radio-thread');
    expect(embers()).toEqual(['Open the panel in Home Assistant']);
    const first = document.querySelector('ol.next > li')!;
    expect(first.querySelector('b')!.textContent).toBe('Let the panel get it from Home Assistant');
    expect(first.textContent).toMatch(/press Join Home Assistant's Thread network\. Or tap Get it from Home Assistant/);
    expect(document.body.textContent).not.toMatch(/dataset|More help/); // no copy and paste, not even as an option
    expect([...document.querySelectorAll('button')].find((b) => b.textContent === 'Finish')!.className).toMatch(/ghost/);
  }, 20_000);

  it('Thread router on 0.4.3, which can\'t: says so; pasting the dataset only under More help', async () => {
    await show('radio-thread-old');
    expect(document.querySelector('.lede')!.textContent).toMatch(/S1 Kiosk 0\.4\.3 can't get your Thread network from Home Assistant itself/);
    expect(document.body.textContent).not.toMatch(/Active dataset TLVs/);
    await act(async () => { [...document.querySelectorAll('button')].find((b) => b.textContent === 'More help')!.click(); });
    const more = document.body.textContent!;
    expect(more).toMatch(/Active dataset TLVs/);
    expect(more).toMatch(/The dataset contains the network key: paste it only there\./);
    expect(embers()).toEqual(['Finish']); // the old way is help, not the main action
  }, 20_000);
});
