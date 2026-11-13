import { describe, expect, it } from 'vitest';
import { BackupRefused } from '@glassworks/kiln-core';
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

  it('a backup undo refuses (As it came from Aqara): the refusal, not "something went wrong"', () => {
    const why = 'this backup\'s splash is already ours, not the original: use the one from your first install';
    const f = explain(new BackupRefused(why), 2, 'none');
    expect(f.title).toBe('This backup can\'t undo the install.');
    expect(f.body).toBe('Nothing was written. Use the backup from your first install, or undo by hand.');
    expect(f.raw).toBe(why);
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
