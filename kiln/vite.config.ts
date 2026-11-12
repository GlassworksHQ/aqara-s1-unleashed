import { defineConfig } from 'vite';

// WebUSB needs a secure context: http://localhost is one, so `npm run dev` works without TLS.
export default defineConfig({
  server: { host: 'localhost', port: 5173, strictPort: true, fs: { allow: ['..'] } },
  build: { target: 'es2022', outDir: 'dist', assetsInlineLimit: 0 },
  // The page's own tests only. Each workspace runs its own, so use `npm test` at the repo root
  // rather than a bare `vitest run`, which skips them.
  test: { include: ['test/**/*.test.ts'] },
});
