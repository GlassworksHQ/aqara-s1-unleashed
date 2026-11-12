// The frames Kiln lists, read from each frame's design data (`frames/<id>/design/frame.json`, the
// format in frames/README.md). test/frame-designs.test.ts fails if a frame folder is missing here.
import s1Plus from '../../../../../frames/aqara-s1-plus/design/frame.json';
import mirror from '../../../../../frames/lululemon-mirror/design/frame.json';

type V3 = [number, number, number];

/** The device's look in millimetres; the site and Kiln draw the 3D mockup from it. */
export interface FrameModel {
  body: { shape: 'rect'; width: number; height: number; depth: number; radius: number };
  /** frame around the screen, mm */
  bezel: number;
  /** without width and height, the screen is the face minus the bezel */
  screen: { radius: number; width?: number; height?: number; top?: number };
  /** the body's sides: the colour facing away from the light, and facing it */
  sides: { dark: string; light: string };
  finish: 'glass' | 'mirror';
  mount: 'wall' | 'floor' | 'lean';
  /** for lean: how far the top tilts back, degrees */
  lean?: number;
  /** parts outside the body, mm from its centre (x right, y down, z towards you) */
  parts?: { kind: 'tube'; from: V3; to: V3; diameter: number; color: string }[];
}

/** The same stages as the Glassworks status badges, plus planned. */
export type Stage = 'planned' | 'development' | 'alpha' | 'beta' | 'stable';
export type Support = Stage | 'depends' | 'unknown' | 'no';
export const RATING_KEYS = ['difficulty', 'reversible', 'modifiesAndroid', 'root', 'opensDevice', 'vendorApps', 'wipes', 'backup', 'updates'] as const;
export type RatingKey = (typeof RATING_KEYS)[number];
export const PANE_IDS = ['assist', 'timers', 'sendspin', 'radio', 'sensors', 'base'] as const;

export interface FrameDesign {
  id: string;
  name: string;
  aka?: string;
  status: Stage;
  /** one sentence: why this device */
  summary: string;
  project?: { name: string; href: string };
  /** Kiln's route for the frame, once it installs from Kiln */
  kiln?: string;
  model: FrameModel;
  /** the recovery-mode drawing data, a path next to frame.json */
  recovery?: string;
  specs: { label: string; value: string }[];
  panes: Record<(typeof PANE_IDS)[number], { support: Support; note?: string }>;
  install: { how: string };
  ratings: Record<RatingKey, { value: string; detail?: string }>;
  ratingsNote?: string;
  notice?: string;
  unverified?: boolean;
}

export const FRAMES = [s1Plus, mirror] as FrameDesign[];

/** A card's three tags, phrased to stand alone (glassworks.dev `cardTags`). */
export function cardTags(r: FrameDesign['ratings']) {
  const tags: { key: 'difficulty' | 'reversible' | 'modifiesAndroid'; value: string; text: string; severity?: 'caution' | 'warn' }[] = [];
  const level: Record<string, string> = { preinstalled: 'Pre-installed', easy: 'Easy install', medium: 'Medium install', hard: 'Hard install' };
  const d = r.difficulty.value;
  if (d !== 'unknown') tags.push({ key: 'difficulty', value: d, text: level[d] ?? d, severity: d === 'hard' ? 'warn' : d === 'medium' ? 'caution' : undefined });
  const rev = r.reversible.value;
  if (rev !== 'unknown') {
    tags.push({ key: 'reversible', value: rev, text: rev === 'yes' ? 'Reversible' : rev === 'partly' ? 'Partly reversible' : 'Not reversible',
      severity: rev === 'no' ? 'warn' : rev === 'partly' ? 'caution' : undefined });
  }
  const m = r.modifiesAndroid.value;
  if (m !== 'unknown') tags.push({ key: 'modifiesAndroid', value: m, text: m === 'yes' ? 'Modifies Android' : 'Android untouched' });
  return tags;
}

export const STAGE_TEXT: Record<Stage, string> = { planned: 'Planned', development: 'Under development', alpha: 'Alpha', beta: 'Beta', stable: 'Stable' };

/** Kiln can install it now. */
export const ships = (s: Stage) => s === 'alpha' || s === 'beta' || s === 'stable';
