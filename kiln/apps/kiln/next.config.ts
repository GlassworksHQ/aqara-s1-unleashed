import { fileURLToPath } from 'node:url';
import type { NextConfig } from 'next';

// Kiln builds from three places outside this folder: kiln/packages (the protocol modules and
// kiln-core), frames/*/installer (the recipes) and tools/, docs/ (files the recipes embed). So the
// root is the repo.
const repo = fileURLToPath(new URL('../../..', import.meta.url));

const config: NextConfig = {
  turbopack: {
    root: repo,
    rules: {
      // `import script from './x.sh?raw'`, as the recipes do under Vite
      '*.sh': { condition: { query: /^\?raw$/ }, type: 'raw' },
    },
  },
  outputFileTracingRoot: repo,
  // The same caching as kiln/vercel.json: the manifest is always fresh, a blob for five minutes.
  async headers() {
    return [
      { source: '/assets/manifest.json', headers: [{ key: 'cache-control', value: 'no-cache' }] },
      { source: '/assets/blobs/:path*', headers: [{ key: 'cache-control', value: 'public, max-age=300' }] },
    ];
  },
};

export default config;
