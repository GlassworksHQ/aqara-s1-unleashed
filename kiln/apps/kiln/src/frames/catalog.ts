// The frames Kiln lists, read from each frame's design data (`frames/<id>/design/frame.json`, the
// format in frames/README.md). test/frame-designs.test.ts fails if a frame folder is missing here.
import type { FrameDesign } from '@glassworks/frame-card';
import s1Plus from '../../../../../frames/aqara-s1-plus/design/frame.json';
import mirror from '../../../../../frames/lululemon-mirror/design/frame.json';

export const FRAMES = [s1Plus, mirror] as FrameDesign[];
