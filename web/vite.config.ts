import { defineConfig } from 'vite';

// WebUSB needs a secure context: http://localhost is one, so `npm run dev` works without TLS.
export default defineConfig({
  server: { host: 'localhost', port: 5173, strictPort: true, fs: { allow: ['..'] } },
  build: { target: 'es2022', outDir: 'dist', assetsInlineLimit: 0 },
  test: { include: ['test/**/*.test.ts', 'packages/*/test/**/*.test.ts'] },
});
