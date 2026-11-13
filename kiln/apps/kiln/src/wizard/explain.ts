// What a failure means for the person at the panel: what happened, whether anything was written, and
// the one most likely fix. Matched on the engine's messages, first match wins.
import { BackupRefused } from '@glassworks/kiln-core';
import type { Changed, Failure } from './state';

interface Rule {
  test: (e: unknown, msg: string) => boolean;
  title: string;
  /** the most likely fix, in one sentence */
  fix: string;
}

const named = (name: string) => (e: unknown) => e instanceof DOMException && e.name === name;

const RULES: Rule[] = [
  // undo's checkRestorable, before any write; the reason is in `raw`, under More help. First, because
  // its words ("install") match the disconnect rule's /stall/.
  { test: (e) => e instanceof BackupRefused, title: 'This backup can\'t undo the install.',
    fix: 'Use the backup from your first install, or undo by hand.' },
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
 * What this attempt wrote before it stopped: `verified`, a write was flashed and read back; `maybe`, a
 * write started and didn't finish; `none`. `installed`: the new software was already in place (the
 * setup step), so nothing more could break. `earlier`: an earlier step may have changed the panel
 * (WizardState.changed), so `none` isn't "nothing was written".
 */
export type Written = 'none' | 'maybe' | 'verified';

export function explain(e: unknown, at: number, written: Written, installed = false, earlier?: Changed): Failure {
  const raw = e instanceof Error ? e.message : String(e);
  // On an installed panel a cancelled picker is the adb one: the recovery-mode advice doesn't apply.
  const rule = installed && named('NotFoundError')(e)
    ? { title: 'Nothing was picked.', fix: 'Try again, and pick the panel in the browser\'s list.' }
    : RULES.find((r) => r.test(e, raw));
  const before = written === 'none' && !!earlier;
  const changed = !installed && (written !== 'none' || !!earlier);
  const said = installed ? 'The new software is in place.'
    : written === 'verified' ? 'Part of the new software was written.'
    : written === 'maybe' ? 'Part of the new software may have been written.'
    : before ? (earlier === 'written' ? 'This attempt wrote nothing; an earlier try did.' : 'This attempt wrote nothing; the panel\'s apps were changed earlier.')
    : 'Nothing was written.';
  // a refused backup: the way on is another backup or undo by hand, not this one again.
  // A closed device list: nothing runs, so the way back starts with recovery mode.
  const fix = before && e instanceof BackupRefused
    ? rule!.fix
    : before && named('NotFoundError')(e) && at === 1
    ? `${rule!.fix} Then use Undo with your backup.`
    : changed
      ? 'Don\'t unplug it yet: put it in recovery mode again and use Undo with your backup.'
      : rule?.fix ?? (installed ? 'Unplug it, plug it back in, and use Finish setting up.' : 'Unplug it and start again from recovery mode.');
  return {
    title: rule?.title ?? (changed ? 'Something went wrong while writing.' : 'Something went wrong.'),
    body: `${said} ${fix}`,
    wrote: changed,
    tips: changed ? WRITTEN_TIPS : CLEAN_TIPS,
    raw: rule && named('NotFoundError')(e) ? '' : raw,
    at,
  };
}
