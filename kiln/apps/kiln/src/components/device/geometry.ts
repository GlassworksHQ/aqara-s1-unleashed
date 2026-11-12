// The 3D device mockup's geometry, ported from glassworks.dev `src/components/deviceGeometry.ts`
// (fbfd364), so Kiln's frame cards draw devices exactly as the site does. The site keeps its models in
// a table there; here each frame's model comes from its own `frames/<id>/design/frame.json` (the
// `model` section in planning's decisions.md, "frame.json describes the device's look in millimetres").
// The maths is the site's, unchanged: fit the body to the stage, pose it for its mount, shade the
// sides that face the viewer, and give the CSS-3D front glass the same transform.
import type { FrameModel } from '../../frames/catalog';

type V3 = [number, number, number];

/* viewing angle per mount: wall panels from a little below eye level, floor mirrors nearly face-on */
const VIEWS = {
  wall: { rx: 9, ry: -22 },
  floor: { rx: 3, ry: -24 },
  lean: { rx: 3, ry: -24 },
} as const;
const PERSPECTIVE = 170;

const rad = (deg: number) => (deg * Math.PI) / 180;

/* CSS "rotateX(rx) rotateY(ry) rotateX(lean)": p' = Rx(Ry(Rlean(p))). y down; positive lean tips the top away. */
function rotate([x0, y0, z0]: V3, view: { rx: number; ry: number }, lean = 0): V3 {
  const l = rad(lean);
  const x = x0;
  const y = Math.cos(l) * y0 - Math.sin(l) * z0;
  const z = Math.sin(l) * y0 + Math.cos(l) * z0;
  const a = rad(view.ry);
  const x1 = Math.cos(a) * x + Math.sin(a) * z;
  const z1 = -Math.sin(a) * x + Math.cos(a) * z;
  const b = rad(view.rx);
  return [x1, Math.cos(b) * y - Math.sin(b) * z1, Math.sin(b) * y + Math.cos(b) * z1];
}

/* the rounded-rectangle outline, clockwise on screen, with outward normals */
function outline(w: number, h: number, r: number, steps = 6) {
  const hw = w / 2;
  const hh = h / 2;
  const corners: [number, number, number][] = [[hw - r, -hh + r, -90], [hw - r, hh - r, 0], [-hw + r, hh - r, 90], [-hw + r, -hh + r, 180]];
  const pts: { x: number; y: number; nx: number; ny: number }[] = [];
  for (const [cx, cy, start] of corners) {
    for (let i = 0; i <= steps; i++) {
      const t = rad(start + (90 * i) / steps);
      pts.push({ x: cx + r * Math.cos(t), y: cy + r * Math.sin(t), nx: Math.cos(t), ny: Math.sin(t) });
    }
  }
  return pts;
}

const LIGHT: V3 = (() => {
  const v: V3 = [-0.45, -0.75, 0.5];
  const n = Math.hypot(...v);
  return [v[0] / n, v[1] / n, v[2] / n];
})();

/** Mixes two of the model's paint colours, as `#rrggbb`. */
function mix(a: string, b: string, t: number) {
  const pa = [1, 3, 5].map((i) => parseInt(a.slice(i, i + 2), 16));
  const pb = [1, 3, 5].map((i) => parseInt(b.slice(i, i + 2), 16));
  return `#${pa.map((v, i) => Math.round(v + (pb[i]! - v) * t).toString(16).padStart(2, '0')).join('')}`;
}

const f3 = (n: number) => +n.toFixed(3);

export type Face = { points: string; fill: string };
export type Line = { x1: number; y1: number; x2: number; y2: number; width: number; color: string };
export type Layout = 'landscape' | 'square' | 'portrait';

export type Geometry = {
  /** stage height in stage units (width is 100) */
  stageH: number;
  faces: Face[];
  parts: Line[];
  floorShadow?: string;
  /** CSS custom properties for the front glass */
  vars: Record<string, string>;
  /** 2D shift that centres what's drawn */
  transform: string;
  layout: Layout;
  finish: FrameModel['finish'];
  mount: FrameModel['mount'];
};

/** Fit, pose and project a model for a stage of the given aspect ratio (width / height). */
export function geometry(m: FrameModel, stageAspect: number): Geometry {
  const view = VIEWS[m.mount];
  const lean = m.mount === 'lean' ? (m.lean ?? 0) : 0;
  const stageH = 100 / stageAspect;
  const cx = 50;
  const cy = stageH / 2;
  const project = ([x, y, z]: V3): [number, number] => {
    const s = PERSPECTIVE / (PERSPECTIVE - z);
    return [cx + x * s, cy + y * s];
  };

  const { width: w, height: h, depth: d, radius: r } = m.body;
  const ring = outline(w, h, r);
  const silhouette: V3[] = [
    ...ring.map((p): V3 => [p.x, p.y, d / 2]),
    ...ring.map((p): V3 => [p.x, p.y, -d / 2]),
    ...(m.parts ?? []).flatMap((p) => [p.from, p.to]),
  ];

  // scale mm to stage units so the drawing fills the stage with a margin; perspective makes this
  // slightly non-linear, so refine a few times
  const maxW = 72;
  const maxH = stageH * 0.86;
  let k = Math.min(maxW / w, maxH / h);
  const measure = () => {
    const pts = silhouette.map((p) => project(rotate([p[0] * k, p[1] * k, p[2] * k], view, lean)));
    const xs = pts.map((p) => p[0]);
    const ys = pts.map((p) => p[1]);
    return { x0: Math.min(...xs), x1: Math.max(...xs), y0: Math.min(...ys), y1: Math.max(...ys) };
  };
  let box = measure();
  for (let i = 0; i < 4; i++) {
    box = measure();
    k *= Math.min(maxW / (box.x1 - box.x0), maxH / (box.y1 - box.y0));
  }
  box = measure();
  const P = (p: V3) => project(rotate([p[0] * k, p[1] * k, p[2] * k], view, lean));

  // side faces that face the viewer
  const camera: V3 = [0, 0, PERSPECTIVE];
  const faces: Face[] = [];
  for (let i = 0; i < ring.length; i++) {
    const a = ring[i]!;
    const b = ring[(i + 1) % ring.length]!;
    if (Math.hypot(b.x - a.x, b.y - a.y) < 1e-6) continue;
    const n = rotate([(a.nx + b.nx) / 2, (a.ny + b.ny) / 2, 0], view, lean);
    const mid = rotate([((a.x + b.x) / 2) * k, ((a.y + b.y) / 2) * k, 0], view, lean);
    const toCam: V3 = [camera[0] - mid[0], camera[1] - mid[1], camera[2] - mid[2]];
    if (n[0] * toCam[0] + n[1] * toCam[1] + n[2] * toCam[2] <= 0) continue;
    const quad: V3[] = [[a.x, a.y, d / 2], [b.x, b.y, d / 2], [b.x, b.y, -d / 2], [a.x, a.y, -d / 2]];
    const lit = Math.max(0, n[0] * LIGHT[0] + n[1] * LIGHT[1] + n[2] * LIGHT[2]);
    faces.push({
      points: quad.map(P).map(([x, y]) => `${f3(x)},${f3(y)}`).join(' '),
      fill: mix(m.sides.dark, m.sides.light, 0.25 + 0.75 * lit),
    });
  }

  // parts as thick projected lines, drawn back to front
  const parts: Line[] = (m.parts ?? [])
    .map((p) => {
      const [x1, y1] = P(p.from);
      const [x2, y2] = P(p.to);
      const z = rotate([0, ((p.from[1] + p.to[1]) / 2) * k, ((p.from[2] + p.to[2]) / 2) * k], view, lean)[2];
      return { line: { x1: f3(x1), y1: f3(y1), x2: f3(x2), y2: f3(y2), width: f3(p.diameter * k), color: p.color }, z };
    })
    .sort((a, b) => a.z - b.z)
    .map((p) => p.line);

  // a soft shadow on the level floor under a standing or leaning device
  let floorShadow: string | undefined;
  if (m.mount !== 'wall') {
    const leaned = silhouette.map((p) => rotate([p[0] * k, p[1] * k, p[2] * k], { rx: 0, ry: 0 }, lean));
    const floorY = Math.max(...leaned.map((p) => p[1]));
    const base = leaned.filter((p) => p[1] > floorY - 0.5);
    const zc = base.reduce((sum, p) => sum + p[2], 0) / base.length;
    const rx = w * 0.62 * k;
    const rz = 220 * k;
    floorShadow = Array.from({ length: 28 }, (_, i): V3 => {
      const t = (i / 28) * Math.PI * 2;
      return [Math.cos(t) * rx, floorY, zc - 60 * k + Math.sin(t) * rz];
    })
      .map((p) => project(rotate(p, view)))
      .map(([x, y]) => `${f3(x)},${f3(y)}`)
      .join(' ');
  }

  const dx = cx - (box.x0 + box.x1) / 2;
  const dy = cy - (box.y0 + box.y1) / 2;
  const ar = w / h;
  const sw = w * k;
  const sh = h * k;
  const pct = (n: number) => `${f3(n)}%`;
  return {
    stageH,
    faces,
    parts,
    floorShadow,
    vars: {
      '--wp': pct(sw),
      '--ar': `${f3(ar)} / 1`,
      '--rad': `${pct(((r * k) / sw) * 100)} / ${pct(((r * k) / sh) * 100)}`,
      ...screenBox(m),
      '--du': String(f3(d * k)),
      '--sr': String(f3(m.screen.radius * k)),
      '--rx': `${view.rx}deg`,
      '--ry': `${view.ry}deg`,
      '--lean': `${lean}deg`,
    },
    transform: `translate(${f3(dx)}%, ${f3((dy / stageH) * 100)}%)`,
    layout: ar >= 1.3 ? 'landscape' : ar <= 0.8 ? 'portrait' : 'square',
    finish: m.finish,
    mount: m.mount,
  };
}

/* The screen's box as % of the body: the given screen rectangle, or the face minus the bezel. */
function screenBox(m: FrameModel): Record<string, string> {
  const { width: w, height: h } = m.body;
  const sc = m.screen.width && m.screen.height
    ? { w: m.screen.width, h: m.screen.height, top: m.screen.top ?? m.bezel }
    : { w: w - 2 * m.bezel, h: h - 2 * m.bezel, top: m.bezel };
  const pct = (n: number) => `${f3(n)}%`;
  return {
    '--scr-x': pct(((w - sc.w) / 2 / w) * 100),
    '--scr-y': pct((sc.top / h) * 100),
    '--scr-w': pct((sc.w / w) * 100),
    '--scr-h': pct((sc.h / h) * 100),
  };
}
