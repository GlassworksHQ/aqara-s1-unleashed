import { globSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

// Frame recipes (../frames/*/installer) are workspaces outside kiln/, so npm hoists their
// dependencies into kiln/node_modules, which Node's lookup from frames/ never reaches. Vite resolves
// the packages listed here from kiln/ instead. tsconfig.base.json's `paths` do the same for tsc.
const here = fileURLToPath(new URL('.', import.meta.url));
const frameDeps = globSync('../frames/*/installer/package.json', { cwd: here }).flatMap((f) => {
  const p = JSON.parse(readFileSync(join(here, f), 'utf8')) as { dependencies?: Record<string, string> };
  return Object.keys(p.dependencies ?? {});
});

// WebUSB needs a secure context: http://localhost is one, so `npm run dev` works without TLS.
export default defineConfig({
  server: { host: 'localhost', port: 5173, strictPort: true, fs: { allow: ['..'] } },
  resolve: { dedupe: [...new Set(frameDeps)] },
  build: { target: 'es2022', outDir: 'dist', assetsInlineLimit: 0 },
  // The page's own tests only. Each package in packages/ runs its own, so use `npm test`
  // (root, then every workspace) rather than a bare `vitest run`, which skips the packages.
  test: { include: ['test/**/*.test.ts'] },
});
