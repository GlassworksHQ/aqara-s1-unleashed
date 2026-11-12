import { defineConfig } from 'vite';

// WebUSB needs a secure context: http://localhost is one, so `npm run dev` works without TLS.
export default defineConfig({
  server: { host: 'localhost', port: 5173, strictPort: true, fs: { allow: ['..'] } },
  build: { target: 'es2022', outDir: 'dist', assetsInlineLimit: 0 },
  // The page's own tests only. Each package in packages/ runs its own, so use `npm test`
  // (root, then every workspace) rather than a bare `vitest run`, which skips the packages.
  test: { include: ['test/**/*.test.ts'] },
});
