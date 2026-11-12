/* Glassworks perf grade (MIT): the letter, computed from a perf summary and rubric.json.
 *
 * The letter is derived, so it is computed where it is shown: the marketing site (and later the
 * shared frame package, #114) imports this module with a frame's summary (tools/perf/summary.py,
 * glassworks.perf-summary/1) and tools/perf/page/rubric.json. tools/perf/grade.py computes the same
 * letter from the same two files; test/grade-cases.json and test/test_grade.py's parity test keep
 * them equal. rubric.json is the single source of the thresholds and the letter rules: don't copy a
 * rule into this file.
 *
 * Plain TypeScript with erasable types only, so node runs it as it is (node --test).
 */

export type Band = "good" | "needs-improvement" | "poor";
export type Level = string | null;

export interface Rule { good_max: number; poor_above?: number; poor_at?: number }

export interface Rubric {
  version: number;
  protocol_version: number;
  metrics: Record<string, Rule>;
  grade: {
    pages: string[];
    tasks: Record<string, string>;
    levels: { level: string; accepts: Record<string, Band[]> }[];
    letters: { letter: string; meaning: string; needs: Record<string, string> }[];
    fallback: { letter: string; meaning: string };
    start_cap: { band: Band; letter: string };
  };
}

export interface FrameRun { p95_ms?: number | null; jank_pct?: number | null; frozen_pct?: number | null; n?: number | null }

export interface PageSummary {
  inp_ms?: number | null;
  open_ms?: number | null;
  scroll?: { runs?: FrameRun[] } & Record<string, unknown>;
  updates?: { runs?: FrameRun[] } & Record<string, unknown>;
}

export interface Summary {
  protocol_version?: number | null;
  pages?: Record<string, PageSummary>;
  restart_ms?: number | null;
  gaps?: string[];
  not_comparable?: string | null;
}

export interface TierInputs { inp_ms?: number | null; load_ms?: number | null; scroll_band?: Band | null; updates_band?: Band | null }
export type Bands = Record<string, Band | null>;

export interface Grade {
  status: "graded" | "re-measure" | "not-comparable";
  grade: string | null;
  meaning: string | null;
  why?: string;
  rubric_version?: number;
  capped_by_start?: boolean;
  provisional?: boolean;
  missing?: string[];
  bands?: Record<string, Bands>;
  levels?: Record<string, Level>;
  start?: { value: number | null; band: Band | null };
  limits?: { page: string; needs: string; tasks: string[] }[];
}

const ORDER: Record<Band, number> = { good: 0, "needs-improvement": 1, poor: 2 };

export function band(value: number | null | undefined, rule: Rule): Band | null {
  if (value === null || value === undefined) return null;
  if (rule.poor_at !== undefined && value >= rule.poor_at) return "poor";
  if (rule.poor_above !== undefined && value > rule.poor_above) return "poor";
  return value <= rule.good_max ? "good" : "needs-improvement";
}

export function worst(bands: (Band | null)[]): Band | null {
  const bs = bands.filter((b): b is Band => !!b);
  return bs.length ? bs.reduce((a, b) => (ORDER[b] > ORDER[a] ? b : a)) : null;
}

/** One run's frames band: the worst of p95, jank and frozen frames. */
export function frameBand(st: FrameRun | null | undefined, rubric: Rubric): Band | null {
  if (!st || !st.n) return null;
  const m = rubric.metrics;
  return worst([band(st.p95_ms, m.frame_p95_ms), band(st.jank_pct, m.frame_jank_pct), band(st.frozen_pct, m.frame_frozen_pct)]);
}

/** The middle band of several runs, the worse of the two middles for an even count. */
export function medianBand(bands: (Band | null)[]): Band | null {
  const bs = bands.filter((b): b is Band => !!b).sort((a, b) => ORDER[a] - ORDER[b]);
  return bs.length ? bs[Math.floor(bs.length / 2)] : null;
}

export function tierBands(tier: TierInputs | undefined, rubric: Rubric): Bands {
  const t = tier || {};
  return {
    tap: band(t.inp_ms, rubric.metrics.inp_ms),
    scroll: t.scroll_band ?? null,
    updates: t.updates_band ?? null,
    load: band(t.load_ms, rubric.metrics.load_ms),
  };
}

export function pageLevel(bands: Bands, rubric: Rubric): Level {
  for (const lv of rubric.grade.levels) {
    if (Object.entries(lv.accepts).every(([task, ok]) => bands[task] !== null && ok.includes(bands[task] as Band))) return lv.level;
  }
  return null;
}

/** The tasks that keep a page from `level`. */
export function limits(bands: Bands, level: string, rubric: Rubric): string[] {
  const lv = rubric.grade.levels.find((l) => l.level === level);
  if (!lv) return [];
  return Object.entries(lv.accepts).filter(([task, ok]) => bands[task] === null || !ok.includes(bands[task] as Band)).map(([task]) => task);
}

function levelRank(level: Level, rubric: Rubric): number {
  const order = rubric.grade.levels.map((l) => l.level);
  return level === null ? 0 : order.length - order.indexOf(level);
}

/** The letter from each page's inputs (median tap and load, median-of-runs frames bands) and the restart. */
export function gradeBands(tiers: Record<string, TierInputs>, startMs: number | null | undefined, rubric: Rubric): Grade {
  const R = rubric.grade;
  const bands: Record<string, Bands> = {};
  const levels: Record<string, Level> = {};
  for (const t of R.pages) {
    bands[t] = tierBands((tiers || {})[t], rubric);
    levels[t] = pageLevel(bands[t], rubric);
  }
  const start = band(startMs, rubric.metrics.start_ms);
  const missing: string[] = [];
  for (const t of R.pages) for (const [task, b] of Object.entries(bands[t])) if (b === null) missing.push(`${t}.${task}`);
  if (!start) missing.push("start");
  let letter = R.fallback.letter;
  for (const l of R.letters) {
    if (Object.entries(l.needs).every(([t, need]) => levelRank(levels[t] ?? null, rubric) >= levelRank(need, rubric))) {
      letter = l.letter;
      break;
    }
  }
  const capped = start === R.start_cap.band && letter < R.start_cap.letter;
  if (capped) letter = R.start_cap.letter;
  const meaning = (R.letters.find((l) => l.letter === letter) || R.fallback).meaning;
  return {
    status: "graded", grade: letter, meaning, rubric_version: rubric.version, capped_by_start: capped,
    provisional: missing.length > 0, missing, bands, levels, start: { value: startMs ?? null, band: start },
  };
}

/** What keeps a grade from the next letter up. Empty for the best letter. */
export function nextLimits(g: Grade, rubric: Rubric): { page: string; needs: string; tasks: string[] }[] {
  const letters = rubric.grade.letters.map((l) => l.letter);
  if (g.grade === null || g.grade === letters[0] || !g.bands || !g.levels) return [];
  const up = letters.includes(g.grade) ? letters[letters.indexOf(g.grade) - 1] : letters[letters.length - 1];
  const needs = rubric.grade.letters.find((l) => l.letter === up)!.needs;
  return rubric.grade.pages
    .filter((t) => t in needs && levelRank(g.levels![t] ?? null, rubric) < levelRank(needs[t], rubric))
    .map((t) => ({ page: t, needs: needs[t], tasks: limits(g.bands![t], needs[t], rubric) }));
}

/** A summary's measurements -> each page's inputs and the restart. */
export function summaryInputs(summary: Summary, rubric: Rubric): [Record<string, TierInputs>, number | null] {
  const tiers: Record<string, TierInputs> = {};
  for (const t of rubric.grade.pages) {
    const p = (summary.pages || {})[t];
    if (!p) continue;
    tiers[t] = {
      inp_ms: p.inp_ms ?? null,
      load_ms: p.open_ms ?? null,
      scroll_band: medianBand((p.scroll?.runs || []).map((r) => frameBand(r, rubric))),
      updates_band: medianBand((p.updates?.runs || []).map((r) => frameBand(r, rubric))),
    };
  }
  return [tiers, summary.restart_ms ?? null];
}

/**
 * The letter for a summary, at render time. status:
 * - "graded": show the letter (and "provisional" when it is set: something wasn't measured, or the
 *   summary lists gaps);
 * - "re-measure": the summary was measured with another protocol than these rules are for; show
 *   "re-measure", not a letter;
 * - "not-comparable": the measurement says why (e.g. not at the default zoom); show no letter.
 */
export function gradeSummary(summary: Summary, rubric: Rubric): Grade {
  if (summary.protocol_version !== rubric.protocol_version) {
    return { status: "re-measure", grade: null, meaning: null,
             why: `measured with protocol ${summary.protocol_version}, these rules are for protocol ${rubric.protocol_version}` };
  }
  if (summary.not_comparable) return { status: "not-comparable", grade: null, meaning: null, why: summary.not_comparable };
  const [tiers, start] = summaryInputs(summary, rubric);
  const g = gradeBands(tiers, start, rubric);
  g.provisional = (g.missing || []).length > 0 || (summary.gaps || []).length > 0;
  g.limits = nextLimits(g, rubric);
  return g;
}
