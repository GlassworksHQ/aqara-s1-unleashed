'use client';
// A frame's card, drawn like glassworks.dev's device cards (DeviceCard.tsx, DeviceBits.tsx,
// TiltCard.tsx at fbfd364): the 3D mockup that rises and floats on hover, leaning toward the pointer;
// the name and stage; the summary; the three install tags. The data is the frame's own
// (frames/<id>/design/frame.json).
//
// The card's outer element is the caller's: Kiln's is a radio item, the site's a link. Give it
// `frameCardClass` (with the global `glass` surface) and `useTilt()`'s props, and put `FrameCardBody`
// inside. By default the body's text is all spans, so it is valid inside a button or a link; with a
// heading level (`as`), the name is that heading and its wrappers are divs, for a link on a page.
import { useRef } from 'react';
import { DeviceModel } from './DeviceModel';
import { STAGE_TEXT, cardTags, type FrameDesign, type Stage } from './frame';
import s from './FrameCard.module.css';

/** The card's own class: the hover lift, the floating model and the tags. */
export const frameCardClass = s.card;

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

/**
 * Leans the card's model a few degrees toward a mouse pointer (--tilt-x, --tilt-z), one style write
 * per frame at most, and nothing on touch or with reduced motion. Spread the result on the card.
 */
export function useTilt<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const frame = useRef(0);
  const tilt = (x: number, z: number) => {
    cancelAnimationFrame(frame.current);
    frame.current = requestAnimationFrame(() => {
      ref.current?.style.setProperty('--tilt-x', `${x}deg`);
      ref.current?.style.setProperty('--tilt-z', `${z}deg`);
    });
  };
  const onPointerMove = (e: React.PointerEvent<HTMLElement>) => {
    if (e.pointerType !== 'mouse' || matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const r = e.currentTarget.getBoundingClientRect();
    tilt(((e.clientY - r.top) / r.height - 0.5) * -10, ((e.clientX - r.left) / r.width - 0.5) * 14);
  };
  return { ref, onPointerMove, onPointerLeave: () => tilt(0, 0) };
}

/**
 * The card's contents: the model, then the name, stage, aka, summary and tags. With `idPrefix`, the
 * summary and the tags get the ids `<idPrefix>-summary` and `<idPrefix>-tags`, for aria-describedby.
 * `as` makes the name a heading (`h2`…`h4`); leave it out inside a button.
 */
export function FrameCardBody({ f, idPrefix, as: Name = 'span' }: { f: FrameDesign; idPrefix?: string; as?: 'span' | 'h2' | 'h3' | 'h4' }) {
  const Box = Name === 'span' ? 'span' : 'div';
  return (
    <>
      <span className={s.model}>
        <span className={s.float}><span className={s.bob}><DeviceModel id={f.id} model={f.model} label={`${f.name} running Glassworks`} /></span></span>
        <span className={s.shadow} aria-hidden="true" />
      </span>
      <Box className={s.text}>
        <Box className={s.top}>
          <Name className={s.name}>{f.name}</Name>
          <StatusPill stage={f.status} />
        </Box>
        {f.aka && <span className={s.aka}>{f.aka}</span>}
        <span id={idPrefix ? `${idPrefix}-summary` : undefined} className={s.summary}>{f.summary}</span>
        <span id={idPrefix ? `${idPrefix}-tags` : undefined} className={s.tags}>
          {cardTags(f.ratings).map((t) => (
            <span key={t.text} className={t.severity ? s[t.severity] : undefined}>
              <TagIcon k={t.key} value={t.value} />
              {t.text}
            </span>
          ))}
        </span>
      </Box>
    </>
  );
}
