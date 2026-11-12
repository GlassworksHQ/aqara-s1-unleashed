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
    const f = explain(e, 2, false);
    expect(f.title).toBe(title);
    expect(f.body.startsWith('Nothing was written.')).toBe(true);
    expect(f.wrote).toBe(false);
  });

  it('after a verified write, says so and points to Undo, whatever the error', () => {
    const f = explain(new Error('transferIn: the device was disconnected'), 2, true);
    expect(f.title).toBe('It disconnected.');
    expect(f.body).toBe('Part of the new software was written. Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.');
    expect(f.wrote).toBe(true);
    expect(f.tips[0]![0]).toMatch(/Don't unplug/);
  });

  it('during setup, the new software is already in place', () => {
    const f = explain(new Error('the panel did not finish starting up'), 3, true, true);
    expect(f.body).toBe('The new software is in place. Unplug it and plug it back in, then use Finish setting up.');
    expect(f.wrote).toBe(false);
  });

  it('keeps the raw message for More help, but not for a cancelled picker', () => {
    expect(explain(new Error('boom'), 2, false).raw).toBe('boom');
    expect(explain(new DOMException('No device selected.', 'NotFoundError'), 1, false).raw).toBe('');
  });
});
