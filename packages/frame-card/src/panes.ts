// The pane catalog (issue 231): every pane in the app, from its pane.json and icon.svg, and what a frame
// says about running it. Kiln and glassworks.dev read it here; the files live next to each pane's code
// in app/kiosk/src/main/java, and scripts/panes.mjs collects them into panes.generated.ts.
import { ships, type FrameDesign, type Stage, type Support } from './frame';
import { PANE_FILES } from './panes.generated';

export interface PaneInfo {
  /** the app's pane id */
  id: string;
  name: string;
  /** one line: what it does */
  does: string;
  /** hardware it needs, in the words a frame's `provides` uses ({@link CAPABILITIES}) */
  requires: string[];
  status: Stage;
  /** a frame pane: the frame it belongs to (the S1's wall base) */
  frame?: string;
  /** its doc page, a path in the repo */
  docs?: string;
  /** icon.svg: 24 × 24, stroke currentColor at 1.6, no fills or colours */
  icon: string;
  /** the folder its files are in */
  source: string;
}

export const PANES: readonly PaneInfo[] = PANE_FILES;
export const PANE_IDS: readonly string[] = PANES.map((p) => p.id);

/** Old pane ids that frame.json files may still use: the site called the wall base `base`. */
export const PANE_ALIASES: Readonly<Record<string, string>> = { base: 'wall-base' };

/**
 * The hardware words a pane `requires` and a frame `provides`, with how to say them. A frame without
 * `provides` doesn't have one fixed set (Generic Android), so what it can run depends on the device.
 */
export const CAPABILITIES: Readonly<Record<string, string>> = {
  microphone: 'a microphone',
  speaker: 'a speaker',
  'silabs-radio': 'a Silicon Labs radio',
  's1-wall-base': "the S1's wall base",
  'bluetooth-le': 'Bluetooth LE',
};

/** Own keys only: a plain `in` or index would also match Object.prototype's (`constructor`, `toString`). */
export const isPaneAlias = (id: string) => Object.hasOwn(PANE_ALIASES, id);
export const isCapability = (word: string) => Object.hasOwn(CAPABILITIES, word);

export const paneById = (id: string) => PANES.find((p) => p.id === (isPaneAlias(id) ? PANE_ALIASES[id] : id));

/** "a microphone and a speaker" */
export function capabilityText(words: readonly string[]) {
  const t = words.map((w) => (isCapability(w) ? CAPABILITIES[w] : w));
  return t.length < 2 ? (t[0] ?? '') : `${t.slice(0, -1).join(', ')} and ${t[t.length - 1]}`;
}

export interface PaneSupport {
  support: Support;
  /** the frame's own note, for a rating it gives */
  note?: string;
  /** true when the frame doesn't rate the pane and this is worked out from requires and provides */
  derived: boolean;
  /** why, for a derived rating */
  reason?: string;
}

/** The frame's rating of a pane, under the pane's id or an old alias of it. */
function rated(f: FrameDesign, id: string) {
  if (Object.hasOwn(f.panes, id)) return f.panes[id];
  const alias = Object.keys(PANE_ALIASES).find((a) => PANE_ALIASES[a] === id);
  return alias && Object.hasOwn(f.panes, alias) ? f.panes[alias] : undefined;
}

/**
 * How the frame runs the pane. Its explicit rating in frame.json wins. Otherwise it is worked out:
 * - a frame pane runs on its own frame only (say which with `frameName`, e.g. the frame's name by id);
 * - a frame that lacks something the pane needs can't run it;
 * - a frame without a fixed `provides` list: it depends on the device;
 * - otherwise it runs at the pane's stage, or is planned while the frame itself doesn't ship.
 */
export function paneSupport(f: FrameDesign, pane: PaneInfo, frameName?: (id: string) => string | undefined): PaneSupport {
  const own = rated(f, pane.id);
  if (own) return { support: own.support, note: own.note, derived: false };
  if (pane.frame && pane.frame !== f.id) {
    const name = frameName?.(pane.frame);
    return { support: 'no', derived: true, reason: name ? `Only on the ${name}.` : `Only on the ${pane.frame} frame.` };
  }
  if (pane.requires.length === 0) {
    return { support: ships(f.status) ? pane.status : 'planned', derived: true, reason: 'Needs no special hardware.' };
  }
  const needs = capabilityText(pane.requires);
  if (!f.provides) return { support: 'depends', derived: true, reason: `Needs ${needs}.` };
  const missing = pane.requires.filter((r) => !f.provides!.includes(r));
  if (missing.length) return { support: 'no', derived: true, reason: `Needs ${capabilityText(missing)}.` };
  return { support: ships(f.status) ? pane.status : 'planned', derived: true, reason: `Has ${needs}.` };
}

/** Every pane, with how the frame runs it. */
export const framePanes = (f: FrameDesign, frameName?: (id: string) => string | undefined) => PANES.map((pane) => ({ pane, ...paneSupport(f, pane, frameName) }));
