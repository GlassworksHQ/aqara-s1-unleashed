import { globSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

// The recipes in ../frames are workspaces outside kiln/, so their dependencies resolve from kiln/
// (the same fix as kiln/vite.config.ts; next.config.ts gets it from tsconfig's paths).
const kiln = fileURLToPath(new URL('../..', import.meta.url));
const frameDeps = globSync('../frames/*/installer/package.json', { cwd: kiln }).flatMap((f) =>
  Object.keys((JSON.parse(readFileSync(join(kiln, f), 'utf8')) as { dependencies?: Record<string, string> }).dependencies ?? {}));

export default defineConfig({
  resolve: { dedupe: [...new Set(frameDeps)] },
  // the recipes embed files from tools/ (s1kiosk-switch.sh), outside kiln/
  server: { fs: { allow: [join(kiln, '..')] } },
  test: { include: ['test/**/*.test.{ts,tsx}'] },
});
