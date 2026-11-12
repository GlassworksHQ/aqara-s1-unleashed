// What a failure means for the person at the panel: what happened, whether anything was written, and
// the one most likely fix. Matched on the engine's messages, first match wins.
import type { Failure } from './state';

interface Rule {
  test: (e: unknown, msg: string) => boolean;
  title: string;
  /** the most likely fix, in one sentence */
  fix: string;
}

const named = (name: string) => (e: unknown) => e instanceof DOMException && e.name === name;

const RULES: Rule[] = [
  { test: named('NotFoundError'), title: 'No panel in recovery mode.',
    fix: 'Unplug it, hold the board button first, then plug in and keep holding for 10 seconds.' },
  { test: (_, m) => /secure boot is enabled/.test(m), title: 'This panel only runs signed software.',
    fix: 'Its chip has secure boot on, so Kiln can\'t start on it.' },
  { test: (_, m) => /does not look like/.test(m), title: 'This isn\'t a panel Kiln knows.',
    fix: 'Check that you picked the right panel on the first screen.' },
  { test: (_, m) => /two reads of .* differ/.test(m), title: 'The USB connection isn\'t reliable.',
    fix: 'Try a cable straight into this computer, not through a hub.' },
  { test: (e, m) => named('NetworkError')(e) || /disconnect|transfer|LIBUSB|stall|No device selected|device was lost/i.test(m),
    title: 'It disconnected.', fix: 'Try a cable straight into this computer.' },
  { test: (_, m) => /SHA-256|installer files are missing|could not download|not in the manifest|HTTP \d/.test(m),
    title: 'A download failed.', fix: 'Check this computer\'s internet connection, then try again.' },
  { test: (_, m) => /did not finish starting/.test(m), title: 'The panel didn\'t finish starting.',
    fix: 'Unplug it and plug it back in, then use Finish setting up.' },
  { test: (_, m) => /adb kill-server|another program holds/.test(m), title: 'Another program has the panel.',
    fix: 'Run "adb kill-server" on this computer, then try again.' },
];

const WRITTEN_TIPS: [string, string][] = [
  ['Don\'t unplug it if it was writing.', 'If a write failed its check, the panel may not start. Put it in recovery mode again and use Undo with your backup.'],
  ['Your backup is safe.', 'It is kept in this browser, and you can download it from the Undo screen.'],
];

const CLEAN_TIPS: [string, string][] = [
  ['Try another cable or port.', 'Plug straight into this computer, not through a hub or dock.'],
  ['Is adb running on this computer?', 'Only one program can hold the panel. Run "adb kill-server" and try again.'],
  ['Windows or Linux?', 'Check the one-time driver step.'],
];

/**
 * `wrote`: whether a write was flashed and verified before it stopped. `installed`: the new software
 * was already in place (the setup step), so nothing more could break.
 */
export function explain(e: unknown, at: number, wrote: boolean, installed = false): Failure {
  const raw = e instanceof Error ? e.message : String(e);
  const rule = RULES.find((r) => r.test(e, raw));
  const written = installed ? 'The new software is in place.' : wrote ? 'Part of the new software was written.' : 'Nothing was written.';
  const fix = !installed && wrote
    ? 'Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.'
    : rule?.fix ?? (installed ? 'Unplug it, plug it back in, and use Finish setting up.' : 'Unplug it and start again from recovery mode.');
  return {
    title: rule?.title ?? (wrote && !installed ? 'Something went wrong while writing.' : 'Something went wrong.'),
    body: `${written} ${fix}`,
    wrote: wrote && !installed,
    tips: wrote && !installed ? WRITTEN_TIPS : CLEAN_TIPS,
    raw: rule && named('NotFoundError')(e) ? '' : raw,
    at,
  };
}
