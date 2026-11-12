import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

const repo = fileURLToPath(new URL('../../..', import.meta.url));

export default defineConfig({
  // the recipes embed files from tools/ (s1kiosk-switch.sh), outside kiln/
  server: { fs: { allow: [repo] } },
  test: { include: ['test/**/*.test.{ts,tsx}'] },
});
