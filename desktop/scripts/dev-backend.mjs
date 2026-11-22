// A local stand-in for the public site's routing (Caddy in production): one plain-HTTP origin that sends /api/* to
// the backend and /live/* to MediaMTX with the same rewrites as the Vite/nginx/Caddy proxies (strip /live, keep the
// session Location under /live, drop WWW-Authenticate). Point the 데스크톱 앱 at it in development:
//
//   node scripts/dev-backend.mjs            # http://localhost:8090 → backend :8080, MediaMTX 127.0.0.1:8889
//   CHZZK_BACKEND_URL=http://localhost:8090 npm start
//
// It passes requests through unchanged, CORS included: the backend and MediaMTX answer the app://chzzk origin
// themselves, as they do behind Caddy. (The web frontend's Vite dev server can't stand in here: its own CORS
// handling answers every preflight without allowing app://chzzk, so WHEP from the app fails there.)
// Settings: DEV_BACKEND_PORT (8090), API_TARGET (http://localhost:8080), MEDIAMTX_TARGET (http://127.0.0.1:8889).
import http from 'node:http'

const port = Number(process.env.DEV_BACKEND_PORT ?? 8090)
const apiTarget = new URL(process.env.API_TARGET ?? 'http://localhost:8080')
const mediaTarget = new URL(process.env.MEDIAMTX_TARGET ?? 'http://127.0.0.1:8889')

function route(path) {
  if (path === '/api' || path.startsWith('/api/')) return { target: apiTarget, path, live: false }
  if (path.startsWith('/live/')) return { target: mediaTarget, path: path.slice('/live'.length), live: true }
  return null
}

const server = http.createServer((req, res) => {
  const match = route(req.url ?? '/')
  if (!match) {
    res.writeHead(404).end()
    return
  }
  const upstream = http.request(
    {
      host: match.target.hostname,
      port: match.target.port,
      method: req.method,
      path: match.path,
      headers: { ...req.headers, host: match.target.host },
    },
    (upstreamRes) => {
      const headers = { ...upstreamRes.headers }
      if (match.live) {
        if (headers.location?.startsWith('/')) headers.location = `/live${headers.location}`
        delete headers['www-authenticate']
      }
      res.writeHead(upstreamRes.statusCode ?? 502, headers)
      upstreamRes.pipe(res)
    },
  )
  upstream.on('error', () => {
    if (!res.headersSent) res.writeHead(502)
    res.end()
  })
  req.pipe(upstream)
})

server.listen(port, () => {
  console.log(`dev backend on http://localhost:${port} (api → ${apiTarget.origin}, live → ${mediaTarget.origin})`)
})
