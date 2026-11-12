// What lets frame recipes outside kiln/ build: tsconfig.base.json's `paths` must reach every export of
// every package (Node's lookup from frames/ can't), and vite.config.ts needs fs.globSync (Node 22).
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, normalize } from 'node:path';
import { describe, expect, it } from 'vitest';

const KILN = join(__dirname, '..');
const json = (f: string) => JSON.parse(readFileSync(join(KILN, f), 'utf8'));
const paths: Record<string, string[]> = json('tsconfig.base.json').compilerOptions.paths;

/** TypeScript's choice: an exact key, else the wildcard key with the longest prefix. */
function mapped(spec: string): string | undefined {
  if (paths[spec]) return paths[spec][0];
  const key = Object.keys(paths).filter((k) => k.endsWith('*') && spec.startsWith(k.slice(0, -1)))
    .sort((a, b) => b.length - a.length)[0];
  return key ? paths[key]![0]!.replace('*', spec.slice(key.length - 1)) : undefined;
}

const exportsOf = readdirSync(join(KILN, 'packages')).flatMap((dir) => {
  const p = json(`packages/${dir}/package.json`) as { name: string; exports: Record<string, string> };
  return Object.entries(p.exports).map(([sub, file]) => [`${p.name}${sub.slice(1)}`, join('packages', dir, file)] as const);
});

describe('workspace resolution for code outside kiln/', () => {
  it.each(exportsOf)('tsconfig paths map %s to its export', (spec, file) => {
    const to = mapped(spec);
    expect(to && normalize(to)).toBe(normalize(file));
    expect(existsSync(join(KILN, file))).toBe(true);
  });

  it('engines.node asks for Node 22 or later (fs.globSync in vite.config.ts)', () => {
    const min = /^>=\s*(\d+)/.exec(json('package.json').engines?.node ?? '')?.[1];
    expect(Number(min)).toBeGreaterThanOrEqual(22);
  });
});
