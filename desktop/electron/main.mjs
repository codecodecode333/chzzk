// The 데스크톱 앱's main process (ADR-0006): one window showing the bundled renderer from app://chzzk/.
//
// Settings (first match wins):
//   backend address   --backend-url=<url>  >  CHZZK_BACKEND_URL  >  "backendUrl" in <userData>/settings.json
//                     >  https://live.codecodecode333.dev
//   remote debugging  --remote-debugging-port=<port> (Chromium's own switch) or CHZZK_REMOTE_DEBUGGING_PORT
import { app, BrowserWindow, ipcMain, net, protocol, shell } from 'electron'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const DEFAULT_BACKEND_URL = 'https://live.codecodecode333.dev'
const SCHEME = 'app'
const HOST = 'chzzk'
const APP_ORIGIN = `${SCHEME}://${HOST}`

const here = path.dirname(fileURLToPath(import.meta.url))
const rendererRoot = path.join(here, '..', 'dist')

const debugPort = process.env.CHZZK_REMOTE_DEBUGGING_PORT
if (debugPort && /^\d+$/.test(debugPort)) app.commandLine.appendSwitch('remote-debugging-port', debugPort)

// A standard, secure scheme: the renderer gets a real origin (app://chzzk, sent as `Origin` on CORS requests to the
// backend and MediaMTX) and a secure context, so fetch and WebRTC behave as on an HTTPS page.
protocol.registerSchemesAsPrivileged([
  {
    scheme: SCHEME,
    privileges: { standard: true, secure: true, supportFetchAPI: true, corsEnabled: true, stream: true, codeCache: true },
  },
])

function readSettingsFile() {
  try {
    return JSON.parse(readFileSync(path.join(app.getPath('userData'), 'settings.json'), 'utf8'))
  } catch {
    return {}
  }
}

/** The backend address, normalised to an http(s) origin (plus path, if any) without a trailing slash. */
function resolveBackendUrl() {
  const flag = process.argv.find((arg) => arg.startsWith('--backend-url='))?.slice('--backend-url='.length)
  const candidates = [flag, process.env.CHZZK_BACKEND_URL, readSettingsFile().backendUrl, DEFAULT_BACKEND_URL]
  for (const candidate of candidates) {
    if (typeof candidate !== 'string' || !candidate.trim()) continue
    try {
      const url = new URL(candidate.trim())
      if (url.protocol === 'http:' || url.protocol === 'https:') return url.toString().replace(/\/$/, '')
    } catch {
      // not a URL: try the next one
    }
    console.warn(`Ignoring invalid backend address: ${candidate}`)
  }
  return DEFAULT_BACKEND_URL
}

const config = { backendUrl: resolveBackendUrl() }
ipcMain.on('chzzk:config', (event) => {
  event.returnValue = config
})

/** Serves dist/ at app://chzzk/; anything outside it (or another host) is a 404. */
function serveRenderer(request) {
  const url = new URL(request.url)
  if (url.host !== HOST) return new Response('Not found', { status: 404 })
  const relative = decodeURIComponent(url.pathname) === '/' ? 'index.html' : decodeURIComponent(url.pathname).slice(1)
  const file = path.resolve(rendererRoot, relative)
  if (file !== rendererRoot && !file.startsWith(rendererRoot + path.sep)) return new Response('Not found', { status: 404 })
  return net.fetch(pathToFileURL(file).toString())
}

function createWindow() {
  const window = new BrowserWindow({
    width: 1280,
    height: 800,
    title: '치지직',
    backgroundColor: '#09090b',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(here, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  })
  // The app never navigates away from its own origin; external links open in the system browser.
  window.webContents.setWindowOpenHandler(({ url }) => {
    if (url.startsWith('https://')) void shell.openExternal(url)
    return { action: 'deny' }
  })
  window.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== APP_ORIGIN) event.preventDefault()
  })
  void window.loadURL(`${APP_ORIGIN}/index.html`)
}

app.whenReady().then(() => {
  protocol.handle(SCHEME, serveRenderer)
  createWindow()
  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
