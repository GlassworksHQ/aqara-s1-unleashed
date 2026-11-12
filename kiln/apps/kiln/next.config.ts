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
  // Kiln has no next/image, and this keeps Next's image optimizer, the code that loads sharp, off.
  // It doesn't change the traces: with or without it, sharp, its LGPL libvips (@img/*), detect-libc,
  // semver and @emnapi/runtime appear only in the full `next start` server's trace
  // (.next/next-server.js.nft.json), not in the minimal server's or any route's. What guards hard
  // rule 4 is checking the deployed function's files (PR #104, "Before merge").
  images: { unoptimized: true },
  // The same caching as kiln/vercel.json: the manifest is always fresh, a blob for five minutes.
  async headers() {
    return [
      { source: '/assets/manifest.json', headers: [{ key: 'cache-control', value: 'no-cache' }] },
      { source: '/assets/blobs/:path*', headers: [{ key: 'cache-control', value: 'public, max-age=300' }] },
    ];
  },
};

export default config;
