import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// Render sets RENDER=true in its builds. There, the API URL must come from VITE_API_URL:
// without it the site would build fine and then send every request to localhost.
if (process.env.RENDER && !process.env.VITE_API_URL) {
  throw new Error('VITE_API_URL is not set. Set it in the Render dashboard (ticket-flow-web > Environment).')
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    // Mantine + React are ~700 kB (~220 kB gzip) in the main chunk; the charts are split into
    // their own chunk (lazy /dashboard route). The default warning starts at 500 kB.
    chunkSizeWarningLimit: 800,
  },
  server: {
    port: 5173,
    strictPort: true,
    // Same as Render's /api rewrite: the refresh cookie routes are called on the site's own origin.
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Fixed time zone, so date tests give the same result on any machine (and match the CI).
    env: { TZ: 'America/Sao_Paulo' },
    // Component tests type into Mantine forms (one re-render per key). With every test file
    // running in parallel, or on a small CI machine, one test can pass 5 s (Vitest's default).
    testTimeout: 15_000,
  },
})
