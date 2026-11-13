// The stack Kiln is firing (docs/design/kiln/sketch.html, `stack()`): Kiln's ember floor at the
// bottom, then hardware, frame, core and panes setting one by one. `level` is how many have set.
// A failure shows the frame layer dashed in rose.
const C = Math.cos(Math.PI / 6);
const S = 0.5;
const SC = 0.62;
const OX = 20 + C * 120 * SC;
const GAP = 30;
const at = (i: number) => 8 + GAP * i;
const M = (oy: number) => `matrix(${C * SC} ${S * SC} ${-C * SC} ${S * SC} ${OX} ${oy})`;
const CELLS = [10, 73, 136].flatMap((x) => [10, 56].map((y) => [x, y] as const)).sort((a, b) => a[1] - b[1] || a[0] - b[0]);
const LAYERS = [
  { kind: 'panes', from: 4 },
  { kind: 'core', from: 3 },
  { kind: 'frame', from: 2 },
  { kind: 'hw', from: 1 },
] as const;
const LIT = [0, 2, 3, 5];
const H = at(4) + S * 320 * SC + 14;

export function Stack({ level, error = false, width = 220 }: { level: number; error?: boolean; width?: number }) {
  return (
    <svg className="stack" viewBox={`0 0 ${C * 320 * SC + 40} ${H}`} width={width} aria-hidden="true">
      <ellipse cx={OX + C * 40 * SC} cy={at(4) + S * 160 * SC} rx="110" ry="36" className={error ? 'st-glow bad' : 'st-glow'} filter="url(#gw-soft)" />
      <g transform={M(at(4) + 5)}><rect width="200" height="120" rx="9" className="st-floor-side" /></g>
      <g transform={M(at(4))}>
        <rect width="200" height="120" rx="9" className="st-floor" />
        {[0, 1, 2, 3].map((i) => (
          <rect key={i} x={10 + i * 46.6} y="10" width="42.5" height="100" rx="5" fill="url(#gw-ember)"
            opacity={i < level || level >= 4 ? 0.35 + i * 0.16 : 0.12} />
        ))}
      </g>
      {level > 0 && level < 4 && !error && [37, 163].map((x) => {
        const X = OX + C * (x - 30) * SC;
        return <path key={x} className="st-heat" d={`M${X} ${at(4) + S * (x + 30) * SC}V${at(4 - level) + S * (x + 30) * SC + 6}`} />;
      })}
      {[...LAYERS].reverse().map((L, j) => {
        const y = at(3 - j);
        if (level < L.from) return <g key={L.kind} transform={M(y)}><rect width="200" height="120" rx="9" className="st-unset" /></g>;
        return (
          <g key={L.kind}>
            <g transform={M(y + 5)}><rect width="200" height="120" rx="9" className="st-side" /></g>
            <g transform={M(y)}>
              <rect width="200" height="120" rx="9" className="st-top" />
              {CELLS.map(([x, yy], k) => {
                const lit = L.kind === 'panes' && LIT.includes(k);
                return <rect key={k} x={x} y={yy} width="54" height="40" rx="5" className={lit ? 'st-lit' : L.kind === 'hw' ? 'st-chip' : 'st-cell'} />;
              })}
            </g>
          </g>
        );
      })}
      {error && <g transform={M(at(2))}><rect width="200" height="120" rx="9" className="st-bad" /></g>}
    </svg>
  );
}
