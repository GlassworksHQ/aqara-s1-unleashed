// The recovery-mode player from docs/design/kiln/recovery/player.js, typed. It draws a frame's device
// face down at the brand's 30° angle and animates the steps into recovery mode from data alone, so
// every frame gets this without its own art. Moves: screws, cover, hold, plug; between them it adds
// its own transitions (light over the board, a spotlight on the button, then on the port).
import { MATERIAL as M } from './materials';

type Mm = [number, number];
interface Rect { kind: string; at: Mm; size: Mm }

export interface RecoveryData {
  frame: string;
  name: string;
  body: { w: number; h: number; thickness: number; radius?: number };
  cover?: { screws: Mm[]; features: Rect[] };
  board?: (Rect | { kind: 'window'; at: Mm; r: number })[];
  button?: { at: Mm; what: string };
  port?: { edge: 'top' | 'bottom' | 'left' | 'right'; at: number; kind: string };
  steps: ('screws' | 'cover' | 'hold' | 'plug')[];
  ready?: { seconds: number; says: string };
  labels?: Partial<Record<string, string>>;
}

export const LABELS: Record<string, string> = { screws: 'Screws out', cover: 'Back off', hold: 'Hold the button', plug: 'Plug in' };

const C = Math.cos(Math.PI / 6);
const SN = 0.5;
const VB = { w: 640, h: 400 };

const clamp = (v: number) => Math.max(0, Math.min(1, v));
const seg = (t: number, a: number, b: number) => (b <= a ? (t >= b ? 1 : 0) : clamp((t - a) / (b - a)));
const ease = (x: number) => x * x * (3 - 2 * x);
const outBack = (x: number) => 1 + 2.5 * Math.pow(x - 1, 3) + 1.5 * Math.pow(x - 1, 2);
const lerp = (a: number, b: number, k: number) => a + (b - a) * k;

interface Span { start: number; end: number }
interface Timeline {
  beats: [number, number][];
  screws?: { start: number; each: number; dur: number };
  cover?: Span; reveal?: Span; focus?: Span; hold?: Span; toPort?: Span; plug?: Span; click?: Span;
  ready?: { start: number };
  out: number;
  T: number;
}

/** The timeline, built from the frame's steps. Each move gets a window; transitions are folded in. */
export function timeline(f: RecoveryData): Timeline {
  const tl: Timeline = { beats: [], out: 0, T: 0 };
  let t = 0.3;
  for (const step of f.steps) {
    const start = t;
    if (step === 'screws') {
      const n = f.cover?.screws.length ?? 0;
      tl.screws = { start: t, each: 0.32, dur: 0.6 };
      t += n * 0.32 + 0.6;
    } else if (step === 'cover') {
      tl.cover = { start: t, end: t + 1.6 };
      tl.reveal = { start: t + 1.4, end: t + 2.2 };
      t += 1.8;
    } else if (step === 'hold') {
      tl.focus = { start: t, end: t + 0.8 };
      tl.hold = { start: t + 0.6, end: 0 };
      t += 2.6;
    } else if (step === 'plug') {
      tl.toPort = { start: t, end: t + 0.8 };
      tl.plug = { start: t + 0.8, end: t + 1.5 };
      tl.click = { start: t + 1.45, end: t + 2.0 };
      t += 1.5;
      tl.ready = { start: t + Math.min(2, f.ready?.seconds ?? 2) * 0.4 + 0.2 };
      t += 2.3;
    }
    tl.beats.push([start, t]);
  }
  if (tl.hold) tl.hold.end = tl.ready ? tl.ready.start : t;
  tl.out = t;
  tl.T = t + 0.6;
  return tl;
}

export interface Player { draw(t: number): void; play(): void; stop(): void; duration: number; beat(t: number): number }

/** Draws `f` into `svg`. `onBeat` hears which step is playing (for the step chips). */
export function mount(svg: SVGSVGElement, f: RecoveryData, onBeat?: (i: number) => void): Player {
  const W = f.body.w, H = f.body.h;
  const k = Math.min(520 / W, 300 / H);
  const s = 0.78;
  const u = (mm: number) => mm * k;
  const Wu = u(W), Hu = u(H);
  const depth = f.body.thickness * k * s * 0.4;
  const proj0 = (x: number, y: number) => [C * s * (x - y), SN * s * (x + y)] as const;
  const cs = [proj0(0, 0), proj0(Wu, 0), proj0(Wu, Hu), proj0(0, Hu)];
  const xs = cs.map((c) => c[0]), ys = cs.map((c) => c[1]);
  const OX = VB.w / 2 - (Math.min(...xs) + Math.max(...xs)) / 2;
  const OY = VB.h / 2 + 14 - (Math.min(...ys) + Math.max(...ys) + depth) / 2;
  const P = (x: number, y: number): Mm => [OX + C * s * (x - y), OY + SN * s * (x + y)];
  const MX = `matrix(${C * s} ${SN * s} ${-C * s} ${SN * s} ${OX} ${OY})`;
  const R = u(f.body.radius ?? 3);

  const BTN = f.button ? (f.button.at.map(u) as Mm) : null;
  const SCREWS = (f.cover?.screws ?? []).map((p) => p.map(u) as Mm);

  let PORT: { top: Mm; at: Mm; angle: number; visible: boolean } | null = null;
  if (f.port) {
    const a = u(f.port.at);
    const edge = { top: [a, 0, -30, false], bottom: [a, Hu, 150, true], left: [0, a, -150, false], right: [Wu, a, 30, true] }[f.port.edge] as [number, number, number, boolean];
    const [x, y, angle, visible] = edge;
    const [px, py] = P(x, y);
    PORT = { top: [px, py], at: [px, py + depth / 2], angle, visible };
  }

  const rect = (r: Rect, extra: string) => `<rect x="${u(r.at[0])}" y="${u(r.at[1])}" width="${u(r.size[0])}" height="${u(r.size[1])}" rx="${u(1)}" ${extra}/>`;
  const board = (f.board ?? []).map((b) => {
    if (b.kind === 'window' && 'r' in b) return `<circle cx="${u(b.at[0])}" cy="${u(b.at[1])}" r="${u(b.r)}" fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".14"/>`;
    const r = b as Rect;
    if (r.kind === 'shield') return rect(r, `style="fill:var(--s-fg);stroke:var(--s-fg)" fill-opacity=".1" stroke-opacity=".15"`);
    if (r.kind === 'speaker') return rect(r, `fill="${M.dark}" style="stroke:var(--s-fg)" stroke-opacity=".1"`);
    return rect(r, `style="fill:var(--s-fg)" fill-opacity=".08"`);
  }).join('');
  const edge = 'style="stroke:var(--s-fg)" stroke-opacity=".1"';
  const coverArt = (f.cover?.features ?? []).map((c) => {
    if (c.kind === 'ribs') return rect(c, 'fill="url(#kr-rib)"');
    if (c.kind === 'plate') return rect(c, `fill="${M.plate}" ${edge}`);
    if (c.kind === 'hole') return `<ellipse cx="${u(c.at[0])}" cy="${u(c.at[1])}" rx="${u(c.size[0]) / 2}" ry="${u(c.size[1]) / 2}" fill="${M.hole}" ${edge}/>`;
    if (c.kind === 'clip') return rect(c, `fill="${M.clip}"`);
    return '';
  }).join('');

  const plug = PORT
    ? `<g transform="translate(${PORT.at[0]} ${PORT.at[1]}) rotate(${PORT.angle})"><clipPath id="kr-out"><rect x="0" y="-60" width="400" height="120"/></clipPath>
       <path id="kr-guide" d="M8 0H8" style="stroke:var(--ember-a)" stroke-opacity=".55" stroke-width="1" stroke-dasharray="4 4" fill="none"/>
       <g clip-path="url(#kr-out)"><g id="kr-plug">
         <path d="M42 0 C 87 0, 112 26, 182 40" stroke="${M.cable}" stroke-width="9" fill="none" stroke-linecap="round"/>
         <path d="M30 -4.5 L42 -3.6 L42 3.6 L30 4.5 Z" fill="${M.boot}"/>
         <rect x="-10" y="-3.2" width="11" height="6.4" rx="1.6" fill="${M.pins}"/>
         <rect x="0" y="-6.5" width="31" height="13" rx="3.5" fill="url(#kr-plugG)" style="stroke:var(--s-fg)" stroke-opacity=".25" stroke-width=".6"/>
       </g></g></g>`
    : '';

  const [bx, by] = BTN ? P(...BTN) : [VB.w / 2, VB.h / 2];
  const says = f.ready?.says ?? 'Ready.';
  svg.setAttribute('viewBox', `0 0 ${VB.w} ${VB.h}`);
  svg.innerHTML = `
<defs>
  <linearGradient id="kr-case" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${M.caseA}"/><stop offset="1" stop-color="${M.caseB}"/></linearGradient>
  <pattern id="kr-rib" width="5" height="10" patternUnits="userSpaceOnUse"><rect width="5" height="10" fill="${M.rib}"/><rect width="1.6" height="10" fill="${M.ribLine}"/></pattern>
  <linearGradient id="kr-plugG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${M.plugA}"/><stop offset=".5" stop-color="${M.plugB}"/><stop offset="1" stop-color="${M.plugC}"/></linearGradient>
  <linearGradient id="kr-sweepG" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="${M.sweep}" stop-opacity="0"/><stop offset=".5" stop-color="${M.sweep}" stop-opacity=".09"/><stop offset="1" stop-color="${M.sweep}" stop-opacity="0"/></linearGradient>
  <radialGradient id="kr-spot" gradientUnits="userSpaceOnUse" cx="${bx}" cy="${by}" r="80"><stop offset=".45" style="stop-color:var(--night)" stop-opacity="0"/><stop offset="1" style="stop-color:var(--night)" stop-opacity=".78"/></radialGradient>
  <radialGradient id="kr-warm" cx=".5" cy=".5" r=".5"><stop offset="0" style="stop-color:var(--ember-b)" stop-opacity=".14"/><stop offset="1" style="stop-color:var(--ember-b)" stop-opacity="0"/></radialGradient>
  <clipPath id="kr-board"><rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}"/></clipPath>
</defs>
<rect width="${VB.w}" height="${VB.h}" style="fill:var(--night)"/>
<ellipse cx="${VB.w / 2}" cy="${VB.h * 0.58}" rx="300" ry="170" fill="url(#kr-warm)"/>
<g id="kr-all"><g id="kr-zoom">
  ${PORT && !PORT.visible ? plug : ''}
  <g transform="translate(0 ${depth})"><g transform="${MX}"><rect width="${Wu}" height="${Hu}" rx="${R}" fill="${M.caseSide}" style="stroke:var(--s-fg)" stroke-opacity=".16"/></g></g>
  <g transform="${MX}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" style="stroke:var(--s-fg)" stroke-opacity=".22"/>
    <rect x="${u(3)}" y="${u(3)}" width="${Wu - u(6)}" height="${Hu - u(6)}" rx="${R * 0.6}" fill="${M.boardBed}" style="stroke:var(--s-fg)" stroke-opacity=".08"/>
    <g clip-path="url(#kr-board)">${board}<rect id="kr-sweep" x="-200" y="-60" width="160" height="${Hu + 120}" fill="url(#kr-sweepG)" transform="rotate(20 ${Wu / 2} ${Hu / 2})"/></g>
    ${BTN ? `${[0, 1, 2].map((i) => `<circle id="kr-r${i}" cx="${BTN[0]}" cy="${BTN[1]}" r="10" fill="none" style="stroke:var(--ember-b)" stroke-width="2"/>`).join('')}
    <rect x="${BTN[0] - 6}" y="${BTN[1] - 6}" width="12" height="12" rx="2" fill="${M.buttonCap}"/><circle id="kr-btn" cx="${BTN[0]}" cy="${BTN[1]}" r="3.6" style="fill:${M.button}"/>` : ''}
  </g>
  ${PORT ? `<ellipse id="kr-portGlow" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="16" ry="9" fill="none" style="stroke:var(--ember-b)" stroke-width="1.6"/>
  <ellipse id="kr-click" cx="${PORT.top[0]}" cy="${PORT.top[1]}" rx="9" ry="5" fill="none" style="stroke:var(--ember-a)" stroke-width="1.5"/>` : ''}
  ${PORT && PORT.visible ? plug : ''}
  ${f.cover ? `<g id="kr-cover"><g transform="${MX}">
    <rect width="${Wu}" height="${Hu}" rx="${R}" fill="url(#kr-case)" style="stroke:var(--s-fg)" stroke-opacity=".28"/>${coverArt}
    ${SCREWS.map(([x, y]) => `<circle class="kr-halo" cx="${x}" cy="${y}" r="12" fill="none" style="stroke:var(--ember-b)" stroke-width="1.5"/>`).join('')}
  </g></g>
  <g>${SCREWS.map(([x, y], i) => { const [X, Y] = P(x, y); return `<g id="kr-s${i}"><ellipse cx="${X}" cy="${Y}" rx="6.5" ry="4" fill="${M.screw}" stroke="${M.screwEdge}"/><path id="kr-slot${i}" d="M${X - 4} ${Y}H${X + 4}" stroke="${M.slot}" stroke-width="1.4"/></g>`; }).join('')}</g>` : ''}
  <rect id="kr-spotRect" width="${VB.w}" height="${VB.h}" fill="url(#kr-spot)"/>
</g>
<g id="kr-hold" opacity="0"><rect x="0" y="-16" width="132" height="32" rx="16" style="fill:var(--night);stroke:var(--ember-a)" fill-opacity=".92" stroke-opacity=".45"/><circle cx="16" cy="0" r="4" style="fill:var(--ember-a)"/><text x="28" y="4.5" font-family="var(--f-sans)" font-size="12.5" style="fill:var(--s-fg)">Keep holding</text></g>
<g id="kr-ready" opacity="0"><rect x="0" y="-16" width="${36 + 6.6 * says.length}" height="32" rx="16" style="fill:var(--night);stroke:var(--green)" fill-opacity=".92" stroke-opacity=".5"/><circle cx="16" cy="0" r="4" style="fill:var(--green)"/><text x="28" y="4.5" font-family="var(--f-sans)" font-size="12.5" style="fill:var(--s-fg)">${says}</text></g>
</g>`;

  const tl = timeline(f);
  const $ = (id: string) => svg.querySelector('#kr-' + id);
  const set = (id: string, a: string, v: string | number) => { $(id)?.setAttribute(a, String(v)); };
  const MID: Mm = PORT ? [(bx + PORT.top[0]) / 2, (by + PORT.top[1]) / 2] : [bx, by];
  const never: Span = { start: 1e9, end: 1e9 };
  const beat = (t: number) => tl.beats.findIndex(([a, e]) => t >= a && t < e);
  let lastBeat = -2;

  function draw(t: number) {
    const sc = tl.screws;
    SCREWS.forEach(([x, y], i) => {
      const kk = sc ? ease(seg(t, sc.start + i * sc.each, sc.start + i * sc.each + sc.dur)) : 1;
      const [X, Y] = P(x, y);
      set('s' + i, 'transform', `translate(0 ${-16 * kk})`);
      set('s' + i, 'opacity', (1 - kk).toFixed(3));
      set('slot' + i, 'transform', `rotate(${-540 * kk} ${X} ${Y})`);
    });
    const coverStart = tl.cover ? tl.cover.start : 0;
    svg.querySelectorAll('.kr-halo').forEach((h) => h.setAttribute('opacity', sc && t < coverStart ? '0.8' : '0'));
    if (tl.cover) {
      const c = ease(seg(t, tl.cover.start, tl.cover.end));
      set('cover', 'transform', `translate(${40 * c} ${-110 * c})`);
      set('cover', 'opacity', (1 - c).toFixed(3));
    }
    const rv = tl.reveal ?? never;
    set('sweep', 'x', lerp(-200, Wu + 40, ease(seg(t, rv.start, rv.end))).toFixed(1));
    const fo = tl.focus ?? never, tp = tl.toPort ?? never;
    const close = ease(seg(t, fo.start, fo.end)), open = ease(seg(t, tp.start, tp.end)), out = ease(seg(t, tl.out - 0.8, tl.out));
    const fx = lerp(bx, MID[0], open), fy = lerp(by, MID[1], open);
    const spot = $('spot')!;
    spot.setAttribute('cx', fx.toFixed(1)); spot.setAttribute('cy', fy.toFixed(1));
    spot.setAttribute('r', lerp(lerp(700, 90, close), 190, open).toFixed(1));
    set('spotRect', 'opacity', Math.min(close, 1 - out).toFixed(3));
    const z = 1 + 0.16 * close - 0.06 * open - 0.1 * out;
    set('zoom', 'transform', `translate(${fx} ${fy}) scale(${z.toFixed(4)}) translate(${-fx} ${-fy})`);
    const hd = tl.hold ?? never;
    const holding = t > hd.start && t < hd.end;
    [0, 1, 2].forEach((i) => {
      const ph = ((((t - hd.start) / 1.5 + i / 3) % 1) + 1) % 1; // also before the hold starts, so r stays positive
      set('r' + i, 'r', (8 + 30 * ph).toFixed(1));
      set('r' + i, 'opacity', holding ? (0.9 * (1 - ph)).toFixed(3) : 0);
    });
    ($('btn') as SVGElement | null)?.style.setProperty('fill', holding ? 'var(--ember-b)' : M.button);
    if (PORT && tl.plug && tl.click) {
      const g = ease(seg(t, tp.start + 0.1, tp.end));
      set('guide', 'd', `M8 0H${(8 + 122 * g).toFixed(1)}`);
      const pl = tl.plug;
      set('guide', 'opacity', t > tp.start && t < pl.start + 0.5 ? 1 - seg(t, pl.start + 0.2, pl.start + 0.5) : 0);
      set('portGlow', 'opacity', t > tp.start + 0.1 && t < pl.start + 0.55 ? (0.35 + 0.6 * (Math.sin((t - tp.start) * 7) + 1) / 2).toFixed(3) : 0);
      const pp = seg(t, pl.start, pl.end);
      const d = t < pl.start - 0.3 ? 170 : t < pl.start ? lerp(170, 120, ease(seg(t, pl.start - 0.3, pl.start))) : 120 * (1 - outBack(pp));
      set('plug', 'transform', `translate(${d.toFixed(2)} 0)`);
      set('plug', 'opacity', (t > pl.start - 0.3 && t < tl.T ? ease(seg(t, pl.start - 0.3, pl.start + 0.3)) * (1 - out) : 0).toFixed(3));
      const kc = seg(t, tl.click.start, tl.click.end);
      const [cx, cy] = PORT.top, scl = 1 + 2.4 * kc;
      set('click', 'transform', `translate(${cx} ${cy}) scale(${scl}) translate(${-cx} ${-cy})`);
      set('click', 'opacity', t > tl.click.start && t < tl.click.end ? (1 - kc).toFixed(3) : 0);
    }
    const lx = fx + (bx - fx) * z + 46, ly = fy + (by - fy) * z - 56;
    set('hold', 'transform', `translate(${lx} ${ly})`);
    set('ready', 'transform', `translate(${lx} ${ly})`);
    set('hold', 'opacity', holding ? 1 : 0);
    set('ready', 'opacity', tl.ready && t >= tl.ready.start && t < tl.out - 0.2 ? 1 : 0);
    set('all', 'opacity', (t < 0.4 ? seg(t, 0, 0.4) : 1 - seg(t, tl.out, tl.T)).toFixed(3));
    const b = beat(t);
    if (b !== lastBeat) { lastBeat = b; onBeat?.(b); }
  }

  let raf = 0;
  /** Loops; with reduced motion it shows one still frame (the ready moment). */
  function play() {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) { draw(tl.ready ? tl.ready.start - 0.4 : tl.T / 2); return; }
    const start = performance.now();
    const loop = (now: number) => { draw(((now - start) / 1000) % tl.T); raf = requestAnimationFrame(loop); };
    raf = requestAnimationFrame(loop);
  }
  draw(0.5);
  return { draw, play, stop: () => cancelAnimationFrame(raf), duration: tl.T, beat };
}
