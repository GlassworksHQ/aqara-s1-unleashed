'use client';
// A frame in Kiln's picker: the shared card (@glassworks/frame-card) as a radio item. Picking it is
// what Continue installs; a frame Kiln can't install yet is shown but can't be picked.
import { FrameCardBody, STAGE_TEXT, frameCardClass, ships, useTilt, type FrameDesign } from '@glassworks/frame-card';
import * as RadioGroup from '@radix-ui/react-radio-group';
import { useId } from 'react';
import { RENDERS } from '../frames/catalog';
import s from './FrameCard.module.css';

export function FrameCard({ f }: { f: FrameDesign }) {
  const tilt = useTilt<HTMLButtonElement>();
  const installable = ships(f.status) && !!f.kiln;
  const id = useId();
  return (
    <RadioGroup.Item {...tilt} value={f.id} disabled={!installable} className={`glass ${frameCardClass} ${s.pick}`}
      aria-label={`${f.name}, ${STAGE_TEXT[f.status]}${installable ? '' : ', not installable from Kiln yet'}`}
      aria-describedby={`${id}-summary ${id}-tags`}>
      <FrameCardBody f={f} idPrefix={id} screen={RENDERS[f.id]} />
    </RadioGroup.Item>
  );
}
