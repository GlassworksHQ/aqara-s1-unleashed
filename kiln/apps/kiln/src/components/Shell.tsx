'use client';
// The frame around every screen (docs/design/kiln): the header with Kiln's mark and the browser
// chip, the rail with the steps and the stack, and on a phone a slim progress line and "3 of 5".
import { useEffect, useState, type ReactNode } from 'react';
import { Mark } from './Mark';
import { Stack } from './Stack';
import type { RailMark } from '../wizard/state';

export type Chip = { kind: 'ok' | 'work' | 'bad'; text: string };

/** WebUSB needs Chrome or Edge, and a secure page. Known only in the browser. */
export function useBrowserChip(): Chip | undefined {
  const [chip, setChip] = useState<Chip>();
  useEffect(() => {
    const usb = 'usb' in navigator;
    setChip(usb && isSecureContext ? { kind: 'ok', text: 'Ready' } : { kind: 'bad', text: usb ? 'Needs https' : 'Use Chrome or Edge' });
  }, []);
  return chip;
}

export function Shell({ steps, level, error, chip, children }: {
  steps: { name: string; mark: RailMark }[];
  level: number;
  error?: boolean;
  chip?: Chip;
  children: ReactNode;
}) {
  const now = Math.max(0, steps.findIndex((s) => s.mark !== 'done'));
  const allDone = steps.every((s) => s.mark === 'done');
  const at = allDone ? steps.length : now + 1;
  return (
    <div className="app">
      <div className="pline" aria-hidden="true"><i style={{ width: `${allDone ? 100 : ((now + 0.5) / steps.length) * 100}%` }} /></div>
      <header className="top">
        <a className="brand" href="/" aria-label="Glassworks Kiln, pick another panel">
          <Mark />
          <span className="wide">Glassworks <b>Kiln</b></span>
          <span className="narrow">Kiln</span>
        </a>
        <span className="narrow small">{allDone ? 'Done' : `${at} of ${steps.length}`}</span>
        {chip && <span className={`chip ${chip.kind}`}><span className="dot" />{chip.text}</span>}
      </header>
      <div className="body">
        <nav className="rail" aria-label="Steps">
          <ol className="steps">
            {steps.map((s) => (
              <li key={s.name} className={s.mark} aria-current={s.mark === 'now' || s.mark === 'turn' || s.mark === 'bad' ? 'step' : undefined}>
                <span className="mk" />
                <span>{s.name}</span>
              </li>
            ))}
          </ol>
          <div className="rail-stack"><Stack level={level} error={error} /></div>
        </nav>
        <div className="phone-stack"><Stack level={level} error={error} width={150} /></div>
        <main className="main">{children}</main>
      </div>
    </div>
  );
}
