/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import basicSsl from '@vitejs/plugin-basic-ssl'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  // HTTPS with a self-signed certificate: a phone's camera works only in a secure context (ADR-0002).
  plugins: [react(), tailwindcss(), basicSsl()],
  server: {
    host: true, // all interfaces, so a phone on the same Wi-Fi can open https://<PC IP>:5173
    proxy: {
      '/api': 'http://localhost:8080',
      // WHIP/WHEP signaling to MediaMTX on the page's own origin (no mixed content, no CORS). Media itself goes
      // straight to MediaMTX over UDP 8189. The backend's /internal/** is deliberately not proxied.
      '/live': {
        // 127.0.0.1, not localhost: compose publishes MediaMTX's signaling port on IPv4 loopback only.
        target: 'http://127.0.0.1:8889',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/live/, ''),
        configure: (proxy) =>
          proxy.on('proxyRes', (res) => {
            // MediaMTX answers with an origin-rooted session Location; keep it under /live so DELETE reaches it.
            const location = res.headers.location
            if (location?.startsWith('/')) res.headers.location = `/live${location}`
            // A 401 without credentials carries a Basic challenge that would pop the browser's login dialog.
            delete res.headers['www-authenticate']
          }),
      },
    },
  },
  test: {
    environment: 'happy-dom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
  },
})
