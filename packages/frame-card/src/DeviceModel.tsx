'use client';
// A frame's 3D mockup, as on glassworks.dev (`DeviceModel.tsx`, fbfd364): the front glass is one CSS-3D
// plane with an example dashboard; the body's sides, any parts and a floor shadow are geometry.ts's
// projection of the frame's model, drawn as SVG behind the glass. Light moves, glass doesn't.
import { useEffect, useMemo, useRef, type CSSProperties } from 'react';
import type { FrameModel } from './frame';
import { geometry, type Layout } from './geometry';
import { PAINT } from './paint';
import s from './DeviceModel.module.css';

const PAINT_VARS = {
  '--dev-glass': PAINT.glass, '--dev-mirror-a': PAINT.mirrorA, '--dev-mirror-b': PAINT.mirrorB, '--dev-mirror-c': PAINT.mirrorC,
} as CSSProperties;

export function DeviceModel({ id, model, label }: { id: string; model: FrameModel; label: string }) {
  const stage = useRef<HTMLDivElement>(null);
  const g = useMemo(() => geometry(model, 2), [model]);
  // --u = 1% of the stage width, for the depth and perspective lengths
  useEffect(() => {
    const el = stage.current;
    if (!el) return;
    const set = () => el.style.setProperty('--u', `${el.clientWidth / 100}px`);
    set();
    const ro = new ResizeObserver(set);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const blur = `frame-floor-${id}`;
  return (
    <div ref={stage} className={s.stage} style={PAINT_VARS} role="img" aria-label={label}>
      <div className={s.model} data-layout={g.layout} data-finish={g.finish} data-mount={g.mount} style={{ transform: g.transform, ...g.vars } as CSSProperties}>
        {g.mount === 'wall' && <div className={s.shadow} aria-hidden="true" />}
        <svg className={s.sides} viewBox={`0 0 100 ${g.stageH}`} preserveAspectRatio="none" aria-hidden="true">
          {g.floorShadow && (
            <>
              <defs>
                <filter id={blur} x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="1.6" /></filter>
              </defs>
              <polygon points={g.floorShadow} fill="black" fillOpacity={0.5} filter={`url(#${blur})`} />
            </>
          )}
          {g.parts.map((p, i) => <line key={`p${i}`} x1={p.x1} y1={p.y1} x2={p.x2} y2={p.y2} stroke={p.color} strokeWidth={p.width} strokeLinecap="round" />)}
          {g.faces.map((f, i) => <polygon key={i} points={f.points} fill={f.fill} stroke={f.fill} strokeWidth="0.12" strokeLinejoin="round" />)}
        </svg>
        <div className={s.device} aria-hidden="true">
          <div className={s.front}>
            <div className={s.screen}><Dashboard layout={g.layout} /></div>
            <div className={s.glare} />
          </div>
        </div>
      </div>
    </div>
  );
}

/* An example dashboard, in the Glass Tile anatomy. Illustrative values only. */
function Dashboard({ layout }: { layout: Layout }) {
  const tiles = [
    { name: 'Living room', value: '3 lights on', lit: true },
    { name: 'Hallway', value: '21.5°' },
    { name: 'Front door', value: 'Locked' },
    { name: 'Kitchen', value: 'Playing' },
  ];
  const shown = layout === 'square' ? tiles.slice(0, 2) : tiles;
  return (
    <div className={s.dash}>
      <div className={s.clock}>
        <span className={s.time}>21:42</span>
        <span className={s.date}>Wednesday 11 November</span>
        {layout !== 'square' && <span className={s.weather}>12° · Cloudy</span>}
      </div>
      <div className={s.tiles}>
        {shown.map((t) => (
          <div key={t.name} className={`${s.tile} ${t.lit ? s.tileLit : ''}`}>
            <span className={s.tileName}>{t.name}</span>
            <span className={s.tileValue}>{t.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
