import { describe, expect, it } from 'vitest';
import { explain } from '../src/wizard/explain';

describe('a failure, in words', () => {
  it.each([
    [new DOMException('No device selected.', 'NotFoundError'), 'No panel in recovery mode.'],
    [new Error('secure boot is enabled on this chip'), 'This panel only runs signed software.'],
    [new Error('this does not look like an Aqara S1 Plus (product sunxi, boot 1, super 2). Nothing was changed.'), 'This isn\'t a panel Kiln knows.'],
    [new Error('two reads of boot differ: USB is unreliable here.'), 'The USB connection isn\'t reliable.'],
    [new Error('transferIn: the device was disconnected'), 'It disconnected.'],
    [new Error('magisk failed its SHA-256 check (got 00…): refusing to use it'), 'A download failed.'],
    [new Error('something odd'), 'Something went wrong.'],
  ])('%s', (e, title) => {
    const f = explain(e, 2, 'none');
    expect(f.title).toBe(title);
    expect(f.body.startsWith('Nothing was written.')).toBe(true);
    expect(f.wrote).toBe(false);
  });

  it('after a verified write, says so and points to Undo, whatever the error', () => {
    const f = explain(new Error('transferIn: the device was disconnected'), 2, 'verified');
    expect(f.title).toBe('It disconnected.');
    expect(f.body).toBe('Part of the new software was written. Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.');
    expect(f.wrote).toBe(true);
    expect(f.tips[0]![0]).toMatch(/Don't unplug/);
  });

  it('during setup, the new software is already in place', () => {
    const f = explain(new Error('the panel did not finish starting up'), 3, 'verified', true);
    expect(f.body).toBe('The new software is in place. Unplug it and plug it back in, then use Finish setting up.');
    expect(f.wrote).toBe(false);
  });

  it('a closed device list, with no earlier write: nothing was written', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none');
    expect(f.body).toBe('Nothing was written. Unplug it, hold the board button first, then plug in and keep holding for 10 seconds.');
    expect(f.wrote).toBe(false);
  });

  it('after an earlier try wrote, an attempt that wrote nothing says so, and keeps Undo', () => {
    const closed = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none', false, 'written');
    expect(closed.title).toBe('No panel in recovery mode.');
    expect(closed.body).toBe('This attempt wrote nothing; an earlier try did. Unplug it, hold the board button first, then plug in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(closed.wrote).toBe(true);
    expect(closed.tips[0]![0]).toMatch(/Don't unplug/);
    const step2 = explain(new Error('transferIn: the device was disconnected'), 2, 'none', false, 'written');
    expect(step2.body).toBe('This attempt wrote nothing; an earlier try did. Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.');
    expect(step2.wrote).toBe(true);
    expect(explain(new Error('x'), 2, 'verified', false, 'written').body).toMatch(/^Part of the new software was written\./); // this attempt's write comes first
  });

  it('after the apps were given back (or partly), says so, not that software was written, and keeps Undo', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none', false, 'apps');
    expect(f.body).toBe('This attempt wrote nothing; the panel\'s apps were changed earlier. Unplug it, hold the board button first, then plug in and keep holding for 10 seconds. Then use Undo with your backup.');
    expect(f.wrote).toBe(true);
  });

  it('on an installed panel (the setup step), an earlier write changes nothing: the new software is in place', () => {
    const f = explain(new Error('the panel did not finish starting up'), 3, 'none', true, 'written');
    expect(f.body).toBe('The new software is in place. Unplug it and plug it back in, then use Finish setting up.');
    expect(f.wrote).toBe(false);
    expect(f.tips[0]![0]).toBe('Try another cable or port.');
  });

  it('keeps the raw message for More help, but not for a cancelled picker', () => {
    expect(explain(new Error('boom'), 2, 'none').raw).toBe('boom');
    expect(explain(new DOMException('No device selected.', 'NotFoundError'), 1, 'none').raw).toBe('');
  });

  it('after a write started but before it was verified, doesn\'t say nothing was written', () => {
    const f = explain(new Error('transferOut: the device was disconnected'), 2, 'maybe');
    expect(f.body).toBe('Part of the new software may have been written. Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.');
    expect(f.wrote).toBe(true);
    expect(f.tips[0]![0]).toMatch(/Don't unplug/);
  });

  it('on an installed panel, a cancelled picker is "nothing was picked", not recovery mode', () => {
    const f = explain(new DOMException('No device selected.', 'NotFoundError'), 3, 'verified', true);
    expect(f.title).toBe('Nothing was picked.');
    expect(f.body).toBe('The new software is in place. Try again, and pick the panel in the browser\'s list.');
    expect(f.body).not.toMatch(/recovery mode|board button/);
  });
});

describe('a failure during a write, through the session', () => {
  it('says the software may have been written once flashing began, before any write is verified', async () => {
    const { demoSession } = await import('../src/wizard/demo');
    const s = demoSession('connect');
    s.store.dispatch({ type: 'start', flow: 'install', now: 0 });
    s.store.dispatch({ type: 'progress', p: { overall: 0.62, phase: 'flash', phasePct: 0.1, title: 'Turning off verified boot', sub: 'Writing vbmeta' } });
    (s as unknown as { fail(at: number, e: unknown): void }).fail(2, new Error('transferOut: the device was disconnected'));
    expect(s.store.state.wrote).toBe(false);
    expect(s.store.state.failure?.body).toMatch(/^Part of the new software may have been written\./);
    s.close();
  });
});
