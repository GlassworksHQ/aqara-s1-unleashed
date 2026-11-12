'use client';
// Screen 1: pick your panel, with the frames drawn as on glassworks.dev/frames. Each card's data is
// the frame's own (frames/<id>/design/frame.json). Undo and "finish setting up" stay one quiet link
// away. A running install shows here too, with the way back to it.
import * as RadioGroup from '@radix-ui/react-radio-group';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { FRAMES, ships } from '../frames/catalog';
import { registry } from '../wizard/registry';
import { ROUTES, canonical, fromHash, status } from '../wizard/routes';
import { STEPS } from '../wizard/state';
import { FrameCard } from './frame-card/FrameCard';
import { LastRun } from './LastRun';
import { Shell, useBrowserChip } from './Shell';

const INSTALLABLE = FRAMES.filter((f) => ships(f.status) && f.kiln);
/** s1.edm.sh's old links (/#/wifi, …) belong to the S1, the only frame that had them. */
const OLD_LINKS_FRAME = '/aqara-s1-plus';

function useRunning(): { href: string; name: string } | undefined {
  const entry = useSyncExternalStore(registry.subscribe, registry.get, () => undefined);
  const [, tick] = useState(0);
  useEffect(() => entry?.session.store.subscribe(() => tick((n) => n + 1)), [entry]);
  if (!entry) return undefined;
  const s = entry.session.store.state;
  if (status(s) !== 'running' && s.screen !== 'setup') return undefined;
  return { href: entry.base + ROUTES[canonical(s)], name: entry.name };
}

export function Picker() {
  const chip = useBrowserChip();
  const router = useRouter();
  const running = useRunning();
  const [picked, setPicked] = useState(INSTALLABLE[0]?.id ?? '');
  const frame = FRAMES.find((f) => f.id === picked)?.kiln ?? '';
  useEffect(() => {
    const old = fromHash(location.hash);
    if (old) router.replace(OLD_LINKS_FRAME + ROUTES[old]);
  }, [router]);
  const steps = STEPS.install.map((name, i) => ({ name, mark: i === 0 ? 'now' as const : 'todo' as const }));
  return (
    <Shell steps={steps} level={0} chip={chip}>
      <section className="picker" aria-labelledby="pick">
        <div className="picker-head">
          <h2 id="pick">Pick your panel.</h2>
          <p className="lede">Panels Glassworks runs on, or will. Each one is a frame.</p>
        </div>
        {running && (
          <div className="running" role="status">
            <span>Kiln is still working on your {running.name}.</span>
            <Link className="link" href={running.href}>Back to it</Link>
          </div>
        )}
        <LastRun />
        <RadioGroup.Root className="frames" value={picked} onValueChange={setPicked} aria-labelledby="pick">
          {FRAMES.map((f) => <FrameCard key={f.id} f={f} />)}
          <a className="yours" href="https://github.com/GlassworksHQ">
            <span className="plus" aria-hidden="true">+</span>
            <b>Your panel</b>
            <p>Any Android panel can be a frame. A guide to writing one comes with the first release; until then, follow along on GitHub.</p>
          </a>
        </RadioGroup.Root>
        <div className="foot">
          <span className="links">
            <Link className="link" href={`${frame}${ROUTES.undo}`}>Undo an install</Link>
            <span aria-hidden="true">·</span>
            <Link className="link" href={`${frame}${ROUTES['set-up']}`}>Finish setting up</Link>
          </span>
          <button type="button" className="btn ember" disabled={!frame} onClick={() => router.push(running?.href.startsWith(`${frame}/`) || running?.href === frame ? running.href : frame)}>Continue</button>
        </div>
      </section>
    </Shell>
  );
}
