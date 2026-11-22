/**
 * The media module: the only code that touches WebRTC and WHEP signaling (ADR-0002), trimmed from the web frontend's
 * `frontend/src/media/media.ts` to playback only (the 데스크톱 앱 never 송출s). Screens depend only on these
 * functions, which is what lets the app tests fake this whole module.
 *
 * Signaling is non-trickle: wait for ICE gathering, then POST the full offer once. The WHEP URL is absolute
 * (`<backendUrl>/live/.../whep`), so this is a CORS request from `app://chzzk`; MediaMTX allows that origin.
 * The resource `Location` the server answers with is DELETEd on stop.
 */

export class MediaError extends Error {
  /** The media server's HTTP status when it refused the request. */
  readonly status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.name = 'MediaError'
    this.status = status
  }
}

export interface Playback {
  /** The received media; attach it to a `<video>`'s `srcObject`. */
  stream: MediaStream
  /** Ends the WebRTC session and tells the media server (best effort). Safe to call more than once. */
  stop(): void
}

export interface WatchOptions {
  /** Called once when the connection drops (`disconnected`, `failed` or closed by the server). */
  onDisconnect?: () => void
}

/** Receives a 방송 over WHEP from an absolute URL. */
export async function watch(whepUrl: string, { onDisconnect }: WatchOptions = {}): Promise<Playback> {
  const pc = new RTCPeerConnection()
  const stream = new MediaStream()
  pc.addTransceiver('video', { direction: 'recvonly' })
  pc.addTransceiver('audio', { direction: 'recvonly' })
  pc.addEventListener('track', (event) => stream.addTrack(event.track))
  let resource: string | null
  try {
    resource = await signal(pc, whepUrl)
  } catch (error) {
    pc.close()
    throw error
  }
  let stopped = false
  let reported = false
  pc.addEventListener('connectionstatechange', () => {
    const state = pc.connectionState
    if (!stopped && !reported && (state === 'disconnected' || state === 'failed' || state === 'closed')) {
      reported = true
      onDisconnect?.()
    }
  })
  return {
    stream,
    stop() {
      if (stopped) return
      stopped = true
      pc.close()
      if (resource) fetch(resource, { method: 'DELETE', credentials: 'omit' }).catch(() => {})
    },
  }
}

/** Offer → wait for ICE → POST → answer. Resolves to the session's resource URL, if the server gave one. */
async function signal(pc: RTCPeerConnection, url: string): Promise<string | null> {
  await pc.setLocalDescription(await pc.createOffer())
  await iceGatheringComplete(pc)
  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      credentials: 'omit',
      // A dummy Bearer, as on the web: a 401 without credentials would carry a Basic challenge.
      headers: { 'Content-Type': 'application/sdp', Authorization: 'Bearer viewer' },
      body: pc.localDescription!.sdp,
    })
  } catch {
    throw new MediaError('미디어 서버에 연결하지 못했습니다.')
  }
  if (response.status !== 201) {
    throw new MediaError('미디어 서버가 연결을 거절했습니다.', response.status)
  }
  const location = response.headers.get('Location')
  await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() })
  return location ? new URL(location, url).toString() : null
}

function iceGatheringComplete(pc: RTCPeerConnection, timeoutMs = 3000): Promise<void> {
  if (pc.iceGatheringState === 'complete') return Promise.resolve()
  return new Promise((resolve) => {
    const timer = setTimeout(done, timeoutMs)
    function done() {
      clearTimeout(timer)
      pc.removeEventListener('icegatheringstatechange', onChange)
      resolve()
    }
    function onChange() {
      if (pc.iceGatheringState === 'complete') done()
    }
    pc.addEventListener('icegatheringstatechange', onChange)
  })
}
