/**
 * The media module: the only code that touches the camera, WebRTC and WHIP/WHEP signaling (ADR-0002).
 * Pages depend only on these functions, which is what lets page tests mock this whole module.
 *
 * Signaling is non-trickle: wait for ICE gathering, then POST the full offer once. WHIP/WHEP URLs are same-origin
 * (`/live/...`, proxied to MediaMTX), and the resource `Location` the server answers with is DELETEd on stop.
 */

export type MediaErrorKind =
  /** The user (or the browser's settings) refused camera or microphone access. */
  | 'permission-denied'
  /** No secure context (HTTPS) or no camera/WebRTC support in this browser. */
  | 'unsupported'
  /** Anything else while opening the camera (no device, device busy, ...). */
  | 'camera-failed'
  /** The media server refused or could not answer the WHIP/WHEP request; `status` is its HTTP status. */
  | 'signaling-failed'

export class MediaError extends Error {
  readonly kind: MediaErrorKind
  readonly status?: number

  constructor(kind: MediaErrorKind, message: string, status?: number) {
    super(message)
    this.name = 'MediaError'
    this.kind = kind
    this.status = status
  }
}

/** A running WHIP publish or WHEP playback. */
export interface Session {
  /** Ends the WebRTC session and tells the media server (best effort). Safe to call more than once. */
  stop(): void
}

export interface Playback extends Session {
  /** The received media; attach it to a `<video>`'s `srcObject`. */
  stream: MediaStream
}

export interface SessionOptions {
  /** Called once when the connection drops (`disconnected`, `failed` or closed by the server). */
  onDisconnect?: () => void
}

const VIDEO_720P30: MediaTrackConstraints = {
  width: { ideal: 1280 },
  height: { ideal: 720 },
  frameRate: { ideal: 30 },
}

/** Whether this browser can publish at all (camera access needs a secure context). */
export function canPublish(): boolean {
  return (
    typeof window !== 'undefined' &&
    window.isSecureContext &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof RTCPeerConnection !== 'undefined'
  )
}

/** Opens the front camera (720p30 when possible) and the microphone. */
export async function openCamera(): Promise<MediaStream> {
  if (!canPublish()) {
    throw new MediaError('unsupported', '이 브라우저에서는 송출할 수 없습니다. HTTPS로 접속했는지 확인해 주세요.')
  }
  try {
    return await navigator.mediaDevices.getUserMedia({
      audio: true,
      video: { facingMode: 'user', ...VIDEO_720P30 },
    })
  } catch (error) {
    const name = error instanceof DOMException ? error.name : ''
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      throw new MediaError('permission-denied', '카메라와 마이크 권한을 허용해 주세요.')
    }
    throw new MediaError('camera-failed', '카메라를 열지 못했습니다.')
  }
}

/** Stops every track of a stream (releases the camera and microphone). */
export function closeStream(stream: MediaStream | null | undefined): void {
  stream?.getTracks().forEach((track) => track.stop())
}

/** Publishes the camera stream to the 방송 over WHIP, authenticated with its 송출 키. */
export async function publish(
  stream: MediaStream,
  whipUrl: string,
  publishKey: string,
  options: SessionOptions = {},
): Promise<Session> {
  const pc = new RTCPeerConnection()
  try {
    const [video] = stream.getVideoTracks()
    const [audio] = stream.getAudioTracks()
    if (video) preferH264(pc.addTransceiver(video, { direction: 'sendonly', streams: [stream] }))
    if (audio) pc.addTransceiver(audio, { direction: 'sendonly', streams: [stream] })
    const resource = await signal(pc, whipUrl, `Bearer ${publishKey}`)
    return session(pc, resource, options)
  } catch (error) {
    pc.close()
    throw error
  }
}

/** Receives a 방송 over WHEP. */
export async function watch(whepUrl: string, options: SessionOptions = {}): Promise<Playback> {
  const pc = new RTCPeerConnection()
  const stream = new MediaStream()
  pc.addTransceiver('video', { direction: 'recvonly' })
  pc.addTransceiver('audio', { direction: 'recvonly' })
  pc.addEventListener('track', (event) => stream.addTrack(event.track))
  try {
    // A dummy Bearer: a 401 to a request without credentials carries `WWW-Authenticate: Basic`, which would pop the
    // browser's login dialog for an ended 방송.
    const resource = await signal(pc, whepUrl, 'Bearer viewer')
    return { stream, ...session(pc, resource, options) }
  } catch (error) {
    pc.close()
    throw error
  }
}

function session(pc: RTCPeerConnection, resource: string | null, { onDisconnect }: SessionOptions): Session {
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
    stop() {
      if (stopped) return
      stopped = true
      pc.close()
      if (resource) fetch(resource, { method: 'DELETE' }).catch(() => {})
    },
  }
}

/** Offer → wait for ICE → POST → answer. Resolves to the session's resource URL, if the server gave one. */
async function signal(pc: RTCPeerConnection, url: string, authorization: string): Promise<string | null> {
  await pc.setLocalDescription(await pc.createOffer())
  await iceGatheringComplete(pc)
  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/sdp', Authorization: authorization },
      body: pc.localDescription!.sdp,
    })
  } catch {
    throw new MediaError('signaling-failed', '미디어 서버에 연결하지 못했습니다.')
  }
  if (response.status !== 201) {
    throw new MediaError('signaling-failed', '미디어 서버가 연결을 거절했습니다.', response.status)
  }
  const location = response.headers.get('Location')
  await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() })
  return location ? new URL(location, new URL(url, window.location.href)).toString() : null
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

/** H.264 first (hardware-encoded on phones, decodable on iPhone Safari); the browser default if it can't. */
function preferH264(transceiver: RTCRtpTransceiver): void {
  if (typeof transceiver.setCodecPreferences !== 'function' || !RTCRtpSender.getCapabilities) return
  const codecs = RTCRtpSender.getCapabilities('video')?.codecs ?? []
  const isH264 = (c: RTCRtpCodec) => c.mimeType.toLowerCase() === 'video/h264'
  const h264 = codecs.filter(isH264)
  if (!h264.length) return
  try {
    transceiver.setCodecPreferences([...h264, ...codecs.filter((c) => !isH264(c))])
  } catch {
    // Keep the browser's default order.
  }
}
