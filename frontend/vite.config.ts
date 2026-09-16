import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// https://vite.dev/config/ and https://vitest.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    // Localhost binding for local development (docs/03-technical-spec.md Section 20.1).
    host: '127.0.0.1',
    port: 5173,
  },
  test: {
    environment: 'jsdom',
    css: false,
  },
});
