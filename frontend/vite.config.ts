import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

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
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Fixed time zone, so date tests give the same result on any machine (and match the CI).
    env: { TZ: 'America/Sao_Paulo' },
  },
})
