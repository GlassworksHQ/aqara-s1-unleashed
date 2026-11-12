'use client';
// Screen 1: pick the panel. That's the whole screen; undo and "finish setting up" stay one quiet
// link away. Each frame that ships has its own wizard at /<frame id>.
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { STEPS } from '../wizard/state';
import { Shell, useBrowserChip } from './Shell';

interface Frame { id: string; name: string; stage: 'beta' | 'soon' }

/** Matches the frames on glassworks.dev/frames. A frame gets a card here once it has a wizard. */
const FRAMES: Frame[] = [
  { id: 'aqara-s1-plus', name: 'Aqara S1 Plus', stage: 'beta' },
  { id: 'lululemon-mirror', name: 'lululemon Studio Mirror', stage: 'soon' },
];

export function Picker() {
  const chip = useBrowserChip();
  const router = useRouter();
  const [frame, setFrame] = useState('aqara-s1-plus');
  // s1.edm.sh's links (docs/install.md: #/resume, #/wifi, …) keep working once it serves Kiln.
  useEffect(() => {
    if (/^#\/(install|undo|resume|wifi|home-assistant|radio)$/.test(location.hash)) location.replace(`/aqara-s1-plus${location.hash}`);
  }, []);
  const steps = STEPS.install.map((name, i) => ({ name, mark: i === 0 ? 'now' as const : 'todo' as const }));
  return (
    <Shell steps={steps} level={0} chip={chip}>
      <section className="card" aria-labelledby="pick">
        <h2 id="pick">Pick your panel.</h2>
        <RadioGroup.Root className="grid3" value={frame} onValueChange={setFrame} aria-labelledby="pick">
          {FRAMES.map((f) => (
            <RadioGroup.Item key={f.id} value={f.id} className="dev" disabled={f.stage === 'soon'} aria-disabled={f.stage === 'soon'}>
              <span className="ph" aria-hidden="true">21:42</span>
              <span className="spread">
                <span className="n">{f.name}</span>
                <span className={`pill ${f.stage}`}>{f.stage === 'beta' ? 'Beta' : 'Soon'}</span>
              </span>
            </RadioGroup.Item>
          ))}
          <a className="dev yours" href="https://www.glassworks.dev/frames">
            <span aria-hidden="true">＋</span>
            <span className="n">Yours?</span>
            <span className="small">How a panel gets a frame</span>
          </a>
        </RadioGroup.Root>
        <div className="foot">
          <span className="links">
            <a className="link" href={`/${frame}#/undo`}>Undo an install</a>
            <span aria-hidden="true">·</span>
            <a className="link" href={`/${frame}#/resume`}>Finish setting up</a>
          </span>
          <button type="button" className="btn ember" onClick={() => router.push(`/${frame}`)}>Continue</button>
        </div>
      </section>
    </Shell>
  );
}
