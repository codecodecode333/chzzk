import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './index.css'

declare global {
  interface Window {
    /** Exposed by electron/preload.cjs: the app's settings, resolved by the main process. */
    chzzk?: { backendUrl: string }
  }
}

/** Outside Electron (e.g. `vite preview` in a browser) there is no bridge: use the public backend. */
const backendUrl = window.chzzk?.backendUrl ?? 'https://live.codecodecode333.dev'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App backendUrl={backendUrl} />
  </StrictMode>,
)
