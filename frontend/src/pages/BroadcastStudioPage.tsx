import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { endBroadcast, getBroadcast, startBroadcast, type Broadcast } from '../api/broadcasts'
import { validateTitle } from '../forms/title'
import { ElapsedTimer } from '../broadcasts/ElapsedTimer'
import { LiveBadge } from '../broadcasts/LiveBadge'
import {
  clearStoredBroadcast,
  loadStoredBroadcast,
  saveStoredBroadcast,
  type StoredBroadcast,
} from '../broadcasts/storedBroadcast'
import {
  closeStream,
  MediaError,
  openCamera,
  publish,
  switchCamera,
  type Facing,
  type MediaErrorKind,
  type Session,
} from '../media/media'

/** What to tell the 송출자 when the camera can't be opened, and how to fix it where they can. */
const CAMERA_ERROR_MESSAGES: Partial<Record<MediaErrorKind, string>> = {
  'permission-denied':
    '카메라와 마이크 권한이 거부되었습니다. 주소창의 자물쇠 아이콘(사이트 설정)에서 카메라와 마이크를 허용한 뒤 새로고침해 주세요.',
  'insecure-context':
    '보안 연결(HTTPS)이 아니어서 카메라를 사용할 수 없습니다. 주소가 https://로 시작하는지 확인하고 다시 접속해 주세요.',
  unsupported: '이 브라우저는 실시간 송출을 지원하지 않습니다. 최신 Chrome 또는 Safari로 접속해 주세요.',
}

function cameraErrorMessage(error: unknown): string {
  return (error instanceof MediaError && CAMERA_ERROR_MESSAGES[error.kind]) || '카메라를 열지 못했습니다.'
}

/** The 방송 this screen is sending: enough to show it, publish into it again and end it. */
interface LiveBroadcast {
  id: number
  startedAt: string
  publishKey: string
  whipUrl: string
}

/** A 방송 this tab started before a reload, still 방송 중, that can be resumed. */
interface Resumable {
  stored: StoredBroadcast
  broadcast: Broadcast
}

const PUBLISH_FAILED = '송출을 시작하지 못했습니다.'

function publishErrorMessage(error: unknown): string {
  return error instanceof MediaError ? error.message : PUBLISH_FAILED
}

/**
 * 송출 screen (`/broadcasts/new`): camera preview → title → 방송 시작 → 방송 중 → 방송 종료 → 종료됨.
 * After a reload while 방송 중 it offers "이어서 송출", which republishes into the same 방송 (the server auto-ends a
 * 방송 only after 60s without media); leaving the page while 방송 중 asks first. When the publication drops it
 * re-reads the 방송: 종료 shows 종료됨, still 방송 중 offers "다시 연결" into the same 방송.
 */
export function BroadcastStudioPage() {
  const [camera, setCamera] = useState<MediaStream | null>(null)
  const [cameraError, setCameraError] = useState<string | null>(null)
  const [title, setTitle] = useState('')
  // Like the VOD form: the error shows once the title has been edited, but start stays blocked from the start.
  const [titleTouched, setTitleTouched] = useState(false)
  const titleError = validateTitle(title)
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const [live, setLive] = useState<LiveBroadcast | null>(null)
  /** The publication dropped while the 방송 is still 방송 중; "다시 연결" publishes again. */
  const [dropped, setDropped] = useState(false)
  const [ending, setEnding] = useState(false)
  const [endError, setEndError] = useState<string | null>(null)
  const [ended, setEnded] = useState(false)
  const publication = useRef<Session | null>(null)
  const [facing, setFacing] = useState<Facing>('user')
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)
  /** The camera stream to release on leaving; it changes with each camera switch. */
  const currentCamera = useRef<MediaStream | null>(null)
  const mounted = useRef(false)
  const [resumable, setResumable] = useState<Resumable | null>(null)

  useEffect(() => {
    const stored = loadStoredBroadcast()
    if (!stored) return
    let active = true
    getBroadcast(String(stored.id)).then(
      (broadcast) => {
        if (!active) return
        if (broadcast?.status === 'LIVE') setResumable({ stored, broadcast })
        else clearStoredBroadcast()
      },
      () => {
        // Can't tell whether it is still 방송 중; offer a fresh start and keep it for the next load.
      },
    )
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    if (!live) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = '' // older browsers show the prompt only when this is set
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [live])

  useEffect(() => {
    let active = true
    mounted.current = true
    openCamera().then(
      (stream) => {
        if (!active) return closeStream(stream)
        currentCamera.current = stream
        setCamera(stream)
      },
      (error: unknown) => {
        if (active) setCameraError(cameraErrorMessage(error))
      },
    )
    return () => {
      active = false
      mounted.current = false
      publication.current?.stop()
      publication.current = null
      closeStream(currentCamera.current)
      currentCamera.current = null
    }
  }, [])

  function stopPublishing() {
    publication.current?.stop()
    publication.current = null
  }

  /**
   * Publishes the camera into `target`. Resolves to `false` when this screen was left while connecting: the late
   * publication is closed right away instead of running on unseen. Rejects like {@link publish}.
   */
  async function connect(camera: MediaStream, target: LiveBroadcast): Promise<boolean> {
    const session: Session = await publish(camera, target.whipUrl, target.publishKey, {
      onDisconnect: () => {
        if (publication.current === session) handleDropped(target.id)
      },
    })
    if (!mounted.current) {
      session.stop()
      return false
    }
    publication.current = session
    return true
  }

  /** The publication dropped: 종료 if the 방송 has ended (e.g. auto-ended), otherwise offer "다시 연결". */
  function handleDropped(id: number) {
    stopPublishing()
    getBroadcast(String(id)).then(
      (broadcast) => {
        if (!mounted.current) return
        if (broadcast?.status === 'LIVE') setDropped(true)
        else showEnded()
      },
      () => {
        // Can't tell; it may still be 방송 중, so offer to reconnect (a 401 then shows it has ended).
        if (mounted.current) setDropped(true)
      },
    )
  }

  /** 종료됨: forgets the 방송, stops publishing and releases the camera. */
  function showEnded() {
    clearStoredBroadcast()
    stopPublishing()
    closeStream(currentCamera.current)
    currentCamera.current = null
    setCamera(null)
    setLive(null)
    setDropped(false)
    setEnded(true)
  }

  /** Front ↔ back camera, on the preview or while 방송 중 (the running publication keeps going). */
  async function handleSwitchCamera() {
    if (!camera || switching) return
    const wanted: Facing = facing === 'user' ? 'environment' : 'user'
    setSwitching(true)
    setSwitchError(null)
    try {
      const switched = await switchCamera(camera, wanted, publication.current)
      if (!mounted.current) return closeStream(switched.stream)
      currentCamera.current = switched.stream
      setCamera(switched.stream)
      setFacing(switched.facing)
      if (switched.facing !== wanted) {
        setSwitchError(`${wanted === 'environment' ? '후면' : '전면'} 카메라를 열지 못했습니다.`)
      }
    } catch {
      if (mounted.current) setSwitchError('카메라를 열지 못했습니다.')
    } finally {
      if (mounted.current) setSwitching(false)
    }
  }

  async function handleStart(event: FormEvent) {
    event.preventDefault()
    if (!camera || starting || titleError) return
    setStarting(true)
    setStartError(null)
    try {
      let target: LiveBroadcast
      try {
        const started = await startBroadcast(title)
        target = { id: started.id, startedAt: started.startedAt, publishKey: started.publishKey, whipUrl: started.whipUrl }
      } catch (error) {
        if (mounted.current) setStartError(apiErrorMessage(error))
        return
      }
      saveStoredBroadcast({ id: target.id, publishKey: target.publishKey, whipUrl: target.whipUrl })
      try {
        if (!(await connect(camera, target))) return
      } catch (error) {
        // Nothing is being sent, so don't leave the 방송 listed as 방송 중 (best effort: without media the server
        // auto-ends it within ~70s anyway), and don't offer to resume it.
        clearStoredBroadcast()
        endBroadcast(target.id, target.publishKey).catch(() => {})
        if (mounted.current) setStartError(publishErrorMessage(error))
        return
      }
      setLive(target)
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  /**
   * Publishes into a 방송 that is already 방송 중 ("이어서 송출" after a reload, "다시 연결" after a drop): same id,
   * same start time. A 401 means it has ended meanwhile (e.g. auto-ended after 60s without media).
   */
  async function republish(target: LiveBroadcast): Promise<'published' | 'ended' | 'failed'> {
    if (!camera) return 'failed'
    setStarting(true)
    setStartError(null)
    try {
      return (await connect(camera, target)) ? 'published' : 'failed'
    } catch (error) {
      if (error instanceof MediaError && error.status === 401) return 'ended'
      if (mounted.current) setStartError(publishErrorMessage(error))
      return 'failed'
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  async function handleResume() {
    if (!resumable || starting) return
    const { stored, broadcast } = resumable
    const target = { id: broadcast.id, startedAt: broadcast.startedAt, publishKey: stored.publishKey, whipUrl: stored.whipUrl }
    const result = await republish(target)
    if (!mounted.current) return
    if (result === 'ended') {
      clearStoredBroadcast()
      setResumable(null)
      setStartError('이미 종료된 방송입니다. 새 방송을 시작해 주세요.')
    } else if (result === 'published') {
      setTitle(broadcast.title)
      setLive(target)
      setResumable(null)
    }
  }

  async function handleReconnect() {
    if (!live || starting) return
    const result = await republish(live)
    if (!mounted.current) return
    if (result === 'ended') showEnded()
    else if (result === 'published') setDropped(false)
  }

  /** "새 방송으로 시작": ends the stored 방송 first (best effort), so it doesn't linger as 방송 중, then forgets it. */
  async function handleStartOver() {
    if (!resumable || starting) return
    const { stored } = resumable
    setStarting(true)
    try {
      await endBroadcast(stored.id, stored.publishKey)
    } catch {
      // Without media the server auto-ends it within ~70s anyway.
    }
    clearStoredBroadcast()
    if (!mounted.current) return
    setStarting(false)
    setResumable(null)
  }

  /** Ends the 방송 (the server also cuts the media connection), then releases the camera. */
  async function handleEnd() {
    if (!live || ending || !window.confirm('방송을 종료할까요?')) return
    setEnding(true)
    setEndError(null)
    try {
      await endBroadcast(live.id, live.publishKey)
    } catch (error) {
      if (mounted.current) setEndError(apiErrorMessage(error))
      return
    } finally {
      if (mounted.current) setEnding(false)
    }
    if (mounted.current) showEnded()
  }

  if (ended) {
    return (
      <section className="mx-auto max-w-md py-16 text-center">
        <h1 className="text-2xl font-bold">종료됨</h1>
        <p className="mt-2 text-zinc-400">방송을 마쳤습니다. 카메라도 꺼졌습니다.</p>
        <Link to="/" className="mt-4 inline-block text-emerald-400 hover:underline">
          홈으로 돌아가기
        </Link>
      </section>
    )
  }

  return (
    <section className="mx-auto max-w-md">
      <div className="relative overflow-hidden rounded-lg bg-black">
        {cameraError ? (
          <p role="alert" className="flex aspect-[3/4] items-center justify-center px-6 text-center text-zinc-300">
            {cameraError}
          </p>
        ) : (
          <video
            data-testid="camera-preview"
            ref={(video) => {
              if (!video) return
              video.muted = true
              if (video.srcObject !== camera) video.srcObject = camera
            }}
            autoPlay
            muted
            playsInline
            className={`aspect-[3/4] w-full object-cover ${facing === 'user' ? '-scale-x-100' : ''}`}
          />
        )}
        {camera && !cameraError && (
          <button
            type="button"
            onClick={handleSwitchCamera}
            disabled={switching}
            className="absolute top-3 right-3 rounded-full bg-black/60 px-3 py-1.5 text-sm text-white hover:bg-black/80 disabled:opacity-50"
          >
            {facing === 'user' ? '후면 카메라로 전환' : '전면 카메라로 전환'}
          </button>
        )}
        {live && (
          <div className="absolute top-3 left-3 flex items-center gap-2">
            <LiveBadge />
            <ElapsedTimer since={live.startedAt} />
          </div>
        )}
      </div>
      {switchError && (
        <p role="status" className="mt-2 text-sm text-amber-400">
          {switchError}
        </p>
      )}

      {live ? (
        <div className="mt-4 space-y-2">
          <h1 className="text-xl font-bold">{title.trim()}</h1>
          <Link to={`/broadcasts/${live.id}`} target="_blank" className="text-emerald-400 hover:underline">
            시청 링크 열기
          </Link>
          {dropped && (
            <div role="alert" className="space-y-2 rounded border border-amber-700 bg-amber-950/40 p-3 text-sm text-amber-200">
              <p>송출이 끊겼습니다. 방송은 아직 방송 중이니 다시 연결해 주세요.</p>
              {startError && <p className="text-red-400">{startError}</p>}
              <button
                type="button"
                onClick={handleReconnect}
                disabled={!camera || starting}
                className="w-full rounded bg-emerald-500 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:opacity-50"
              >
                다시 연결
              </button>
            </div>
          )}
          {endError && (
            <p role="alert" className="text-sm text-red-400">
              {endError}
            </p>
          )}
          <button
            type="button"
            onClick={handleEnd}
            disabled={ending}
            className="w-full rounded bg-red-600 py-2.5 font-semibold text-white hover:bg-red-500 disabled:opacity-50"
          >
            방송 종료
          </button>
        </div>
      ) : resumable ? (
        <div className="mt-4 space-y-3">
          <p className="text-zinc-300">
            방송 중인 <strong className="text-zinc-100">{resumable.broadcast.title}</strong> 방송이 있습니다.
          </p>
          {startError && (
            <p role="alert" className="text-sm text-red-400">
              {startError}
            </p>
          )}
          <button
            type="button"
            onClick={handleResume}
            disabled={!camera || starting}
            className="w-full rounded bg-emerald-500 py-2.5 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:opacity-50"
          >
            이어서 송출
          </button>
          <button
            type="button"
            onClick={handleStartOver}
            disabled={starting}
            className="w-full rounded border border-zinc-700 py-2.5 text-zinc-300 hover:bg-zinc-900 disabled:opacity-50"
          >
            새 방송으로 시작
          </button>
        </div>
      ) : (
        <form onSubmit={handleStart} className="mt-4 space-y-3">
          <div>
            <label htmlFor="broadcast-title" className="mb-1 block text-sm text-zinc-300">
              제목
            </label>
            <input
              id="broadcast-title"
              value={title}
              onChange={(e) => {
                setTitle(e.target.value)
                setTitleTouched(true)
              }}
              aria-invalid={titleTouched && titleError ? true : undefined}
              aria-describedby={titleTouched && titleError ? 'broadcast-title-error' : undefined}
              className="w-full rounded border border-zinc-700 bg-zinc-900 px-3 py-2"
            />
            {titleTouched && titleError && (
              <p id="broadcast-title-error" className="mt-1 text-sm text-red-400">
                {titleError}
              </p>
            )}
          </div>
          {startError && (
            <p role="alert" className="text-sm text-red-400">
              {startError}
            </p>
          )}
          <button
            type="submit"
            disabled={!camera || starting || !!titleError}
            className="w-full rounded bg-emerald-500 py-2.5 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:opacity-50"
          >
            방송 시작
          </button>
        </form>
      )}
    </section>
  )
}
