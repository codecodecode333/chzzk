/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The renderer is bundled into the app and served from app://chzzk/ by the main process (electron/main.mjs).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: { outDir: 'dist', emptyOutDir: true },
  test: {
    environment: 'happy-dom',
    // The app is never same-origin with the backend; the backend's CORS is tested on the backend side.
    environmentOptions: { happyDOM: { settings: { fetch: { disableSameOriginPolicy: true } } } },
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    testTimeout: 15000,
  },
})
