// The frames Kiln lists, read from each frame's design data (`frames/<id>/design/frame.json`, the
// format in frames/README.md). test/frame-designs.test.ts fails if a frame folder is missing here.
import type { FrameDesign } from '@glassworks/frame-card';
import s1Plus from '../../../../../frames/aqara-s1-plus/design/frame.json';
import mirror from '../../../../../frames/lululemon-mirror/design/frame.json';
import genericAndroid from '../../../../../frames/generic-android/design/frame.json';
import s1PlusRender from '../../../../../frames/aqara-s1-plus/design/render.webp';
import mirrorRender from '../../../../../frames/lululemon-mirror/design/render.webp';
import genericAndroidRender from '../../../../../frames/generic-android/design/render.webp';

export const FRAMES = [s1Plus, mirror, genericAndroid] as FrameDesign[];

/** An image import's URL: Next gives `{ src }`, Vite (the tests) a string. */
const url = (m: string | { src: string }) => (typeof m === 'string' ? m : m.src);

/** Each frame's render (frame.json `render`, made by tools/showcase/render.mjs), by frame id. */
export const RENDERS: Record<string, string> = {
  [s1Plus.id]: url(s1PlusRender),
  [mirror.id]: url(mirrorRender),
  [genericAndroid.id]: url(genericAndroidRender),
};
