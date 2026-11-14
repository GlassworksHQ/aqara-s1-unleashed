// A frame's design data, `frames/<id>/design/frame.json` (the format is in frames/README.md), and the
// card's words for it. Kiln and glassworks.dev read the same files through these types.

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

/** The same stages as the Glassworks status badges (planned, under development, alpha, beta, stable). */
export type Stage = 'planned' | 'development' | 'alpha' | 'beta' | 'stable';
export type Support = Stage | 'depends' | 'unknown' | 'no';
export const RATING_KEYS = ['difficulty', 'reversible', 'modifiesAndroid', 'root', 'opensDevice', 'vendorApps', 'wipes', 'backup', 'updates'] as const;
export type RatingKey = (typeof RATING_KEYS)[number];
/** Android's ABI names (`Build.SUPPORTED_ABIS`) */
export const ABIS = ['armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'] as const;
export type Abi = (typeof ABIS)[number];

export interface FrameDesign {
  id: string;
  name: string;
  /** the maker's part of `name`, set light in the frame's banner; the rest of `name` is bold. `name` starts with it and a space */
  brand?: string;
  aka?: string;
  status: Stage;
  /** one sentence: why this device */
  summary: string;
  /** the banner's line under `frames/<id>`, at most 50 characters (glassworks.dev scripts/brand/frame.mjs draws it) */
  tagline?: string;
  project?: { name: string; href: string };
  /** Kiln's route for the frame, once it installs from Kiln */
  kiln?: string;
  model: FrameModel;
  /** the recovery-mode drawing data, a path next to frame.json */
  recovery?: string;
  /** the screen's render, a `.webp` next to frame.json (tools/showcase/render.mjs); DeviceModel's `screen` */
  render?: string;
  /** the screen in px, as Android's boot animation sees it (branding/boot reads it) */
  display?: { width: number; height: number };
  /** the boot branding's format limits (branding/boot/target.py) */
  boot?: { androidApi?: number; splash?: 'allwinner-boot-resource'; zipBudget?: number };
  /** what the device's userspace runs, preferred first; the app's release builds every frame's (#98). Optional so code that builds a FrameDesign without it still compiles */
  abis?: Abi[];
  specs: { label: string; value: string }[];
  /** the panes it rates, by pane id (panes.ts PANE_IDS, or an old alias such as `base`); a pane it doesn't rate is worked out from `provides` */
  panes: Record<string, { support: Support; note?: string }>;
  /** the hardware it has, in panes.ts CAPABILITIES' words; leave it out when it varies by device */
  provides?: string[];
  /** href: where it installs from, only for a frame without `kiln` */
  install: { how: string; href?: string };
  ratings: Record<RatingKey, { value: string; detail?: string }>;
  ratingsNote?: string;
  notice?: string;
  unverified?: boolean;
}

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

/** Kiln can install it now, or the pane runs on the frame now. `depends`, `unknown` and `no` don't. */
export const ships = (s: Support) => s === 'alpha' || s === 'beta' || s === 'stable';
