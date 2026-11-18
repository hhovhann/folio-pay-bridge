/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Served by Spring Boot at /dashboard/. The build goes straight into the jar's static resources (never committed);
// `npm run dev` proxies /api to a bridge running on :8081.
export default defineConfig({
  base: '/dashboard/',
  plugins: [react()],
  build: {
    outDir: '../target/classes/static/dashboard',
    emptyOutDir: true,
    sourcemap: false,
    modulePreload: { polyfill: false }, // no inline script: the page's content security policy allows scripts from this origin only
  },
  server: { proxy: { '/api': 'http://127.0.0.1:8081', '/actuator': 'http://127.0.0.1:8081' } },
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'] },
});
