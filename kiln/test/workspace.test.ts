// The npm workspace root is the repo root (#114), so code in frames/ and packages/ finds its
// dependencies by Node's own lookup, with no `paths` or `resolve.dedupe` in between. From every
// workspace, each dependency it declares is found, and it is the one copy the repo root has.
import { existsSync, globSync, readFileSync, realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, expect, it } from 'vitest';

const REPO = join(__dirname, '..', '..');
const json = (f: string) => JSON.parse(readFileSync(join(REPO, f), 'utf8'));
const root = json('package.json') as { workspaces: string[] };

/** Node's lookup: the nearest node_modules/<name> from `dir` upward. */
function lookup(dir: string, name: string): string | undefined {
  for (let d = dir; ; d = dirname(d)) {
    const hit = join(d, 'node_modules', name);
    if (existsSync(hit)) return realpathSync(hit);
    if (dirname(d) === d) return undefined;
  }
}

const workspaces = root.workspaces.flatMap((w) => globSync(`${w}/package.json`, { cwd: REPO })).map(dirname);
const deps = workspaces.flatMap((w) => {
  const p = json(`${w}/package.json`) as { dependencies?: Record<string, string>; peerDependencies?: Record<string, string> };
  return Object.keys({ ...p.dependencies, ...p.peerDependencies }).map((d) => [w, d] as const);
});

describe('one workspace at the repo root', () => {
  it('lists Kiln, its packages and app, every frame recipe and the shared packages', () => {
    for (const w of ['kiln', 'kiln/packages/kiln-core', 'kiln/apps/kiln', 'frames/aqara-s1-plus/installer', 'packages/frame-card']) {
      expect(workspaces).toContain(w);
    }
  });

  it.each(deps)('%s finds %s, the same copy as the repo root', (w, dep) => {
    const found = lookup(join(REPO, w), dep);
    expect(found, `${dep} from ${w}`).toBeDefined();
    expect(found).toBe(lookup(REPO, dep));
  });
});
