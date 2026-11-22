// Runs sandboxed with contextIsolation: the renderer sees only `window.chzzk`, never Node or ipcRenderer.
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('chzzk', ipcRenderer.sendSync('chzzk:config'))
