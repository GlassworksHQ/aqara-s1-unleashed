'use client';
// A frame in Kiln's picker, drawn like glassworks.dev's device cards (DeviceCard.tsx, DeviceBits.tsx,
// TiltCard.tsx at fbfd364): the 3D mockup that rises and floats on hover, leaning toward the pointer;
// the name and stage; the summary; the three install tags. Here it is a radio item: picking it is
// what Continue installs. The data is the frame's own (frames/<id>/design/frame.json).
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useId, useRef } from 'react';
import { DeviceModel } from '../device/DeviceModel';
import { STAGE_TEXT, cardTags, ships, type FrameDesign, type Stage } from '../../frames/catalog';
import s from './FrameCard.module.css';

/* 24-grid glyphs at 1.6px stroke (glassworks.dev Glyphs.tsx) */
const G = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true, className: s.tagIcon };
const Undo = () => <svg {...G}><path d="M9 14 4 9l5-5" /><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></svg>;
const Edit = () => <svg {...G}><path d="M4 20h4L19 9l-4-4L4 16z" /><path d="m13.5 6.5 4 4" /></svg>;
const Check = () => <svg {...G}><circle cx="12" cy="12" r="8.5" /><path d="m8.5 12.2 2.4 2.4 4.6-5" /></svg>;
const Meter = ({ level }: { level: number }) => (
  <svg {...G}>{[0, 1, 2].map((i) => <rect key={i} x={4.5 + i * 6} y={16 - i * 4.5} width="3.5" height={4.5 + i * 4.5} rx="1.2" fill={i < level ? 'currentColor' : 'none'} />)}</svg>
);
const LEVEL: Record<string, number> = { preinstalled: 0, easy: 1, medium: 2, hard: 3 };

function TagIcon({ k, value }: { k: string; value: string }) {
  if (k === 'difficulty') return value === 'preinstalled' ? <Check /> : <Meter level={LEVEL[value] ?? 0} />;
  if (k === 'modifiesAndroid' && value === 'no') return <Check />;
  return k === 'reversible' ? <Undo /> : <Edit />;
}

export function StatusPill({ stage }: { stage: Stage }) {
  return (
    <span className={`${s.pill} ${s[stage]}`}>
      <span className={`${s.mark} ${s[`m_${stage}`]}`} aria-hidden="true" />
      {STAGE_TEXT[stage]}
    </span>
  );
}

export function FrameCard({ f }: { f: FrameDesign }) {
  const ref = useRef<HTMLButtonElement>(null);
  const frame = useRef(0);
  const tilt = (x: number, z: number) => {
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      ref.current?.style.setProperty('--tilt-x', `${x}deg`);
      ref.current?.style.setProperty('--tilt-z', `${z}deg`);
    });
  };
  const onMove = (e: React.PointerEvent<HTMLElement>) => {
    if (e.pointerType !== 'mouse' || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const r = e.currentTarget.getBoundingClientRect();
    tilt(((e.clientY - r.top) / r.height - 0.5) * -10, ((e.clientX - r.left) / r.width - 0.5) * 14);
  };
  const installable = ships(f.status) && !!f.kiln;
  const id = useId();
  return (
    <RadioGroup.Item ref={ref} value={f.id} disabled={!installable} className={`glass ${s.card}`} onPointerMove={onMove} onPointerLeave={() => tilt(0, 0)}
      aria-label={`${f.name}, ${STAGE_TEXT[f.status]}${installable ? '' : ', not installable from Kiln yet'}`}
      aria-describedby={`${id}-summary ${id}-tags`}>
      <span className={s.model}>
        <span className={s.float}><span className={s.bob}><DeviceModel id={f.id} model={f.model} label={`${f.name} running Glassworks`} /></span></span>
        <span className={s.shadow} aria-hidden="true" />
      </span>
      <span className={s.text}>
        <span className={s.top}>
          <span className={s.name}>{f.name}</span>
          <StatusPill stage={f.status} />
        </span>
        {f.aka && <span className={s.aka}>{f.aka}</span>}
        <span id={`${id}-summary`} className={s.summary}>{f.summary}</span>
        <span id={`${id}-tags`} className={s.tags}>
          {cardTags(f.ratings).map((t) => (
            <span key={t.text} className={t.severity ? s[t.severity] : undefined}>
              <TagIcon k={t.key} value={t.value} />
              {t.text}
            </span>
          ))}
        </span>
      </span>
    </RadioGroup.Item>
  );
}
