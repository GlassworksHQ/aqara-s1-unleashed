import { kilnPlates } from '@glassworks/brand';

/**
 * Kiln's mark: the Glassworks plates with the bottom one fired. Drawn from the brand's own geometry
 * (packages/brand/src/mark.ts, synced from glassworks.dev), like the site's <Mark variant="ember">.
 */
export function Mark({ width = 26, title }: { width?: number; title?: string }) {
  const a11y = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': true };
  return (
    <svg viewBox="0 0 120 120" width={width} {...a11y}>
      {kilnPlates(width < 40 ? 'small' : 'full').map((p, i) =>
        p.lit ? (
          <path key={i} d={p.d} fill="url(#gw-ember)" />
        ) : (
          <path key={i} d={p.d} fill="none" stroke="currentColor" strokeOpacity={p.opacity} strokeWidth={p.strokeWidth} strokeLinejoin="round" />
        ),
      )}
    </svg>
  );
}

/** The brand's two gradients, from the tokens. Render once, in the root layout. */
export function MarkDefs() {
  return (
    <svg aria-hidden="true" focusable="false" style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }}>
      <defs>
        <linearGradient id="gw-lit" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" style={{ stopColor: 'var(--sky)' }} />
          <stop offset="1" style={{ stopColor: 'var(--violet)' }} />
        </linearGradient>
        <linearGradient id="gw-ember" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" style={{ stopColor: 'var(--ember-a)' }} />
          <stop offset="1" style={{ stopColor: 'var(--ember-b)' }} />
        </linearGradient>
        <filter id="gw-soft" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="10" />
        </filter>
      </defs>
    </svg>
  );
}
