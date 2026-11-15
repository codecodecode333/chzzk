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

interface OnAir {
  id: number
  startedAt: string
  publishKey: string
}

/** A 방송 this tab started before a reload, still 방송 중, that can be resumed. */
interface Resumable {
  stored: StoredBroadcast
  broadcast: Broadcast
}

/**
 * 송출 screen (`/broadcasts/new`): camera preview → title → 방송 시작 → 방송 중 → 방송 종료 → 종료됨.
 * After a reload while 방송 중 it offers "이어서 송출", which republishes into the same 방송 (the server auto-ends a
 * 방송 only after 60s without media); leaving the page while 방송 중 asks first.
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
  const [onAir, setOnAir] = useState<OnAir | null>(null)
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
    if (!onAir) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = '' // older browsers show the prompt only when this is set
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [onAir])

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
      const started = await startBroadcast(title)
      saveStoredBroadcast({ id: started.id, publishKey: started.publishKey, whipUrl: started.whipUrl })
      try {
        publication.current = await publish(camera, started.whipUrl, started.publishKey, {})
      } catch (error) {
        setStartError(error instanceof MediaError ? error.message : '송출을 시작하지 못했습니다.')
        return
      }
      setOnAir({ id: started.id, startedAt: started.startedAt, publishKey: started.publishKey })
    } catch (error) {
      setStartError(apiErrorMessage(error))
    } finally {
      setStarting(false)
    }
  }

  /** "이어서 송출": republishes the camera into the stored 방송, which carries on (same id, same start time). */
  async function handleResume() {
    if (!camera || !resumable || starting) return
    const { stored, broadcast } = resumable
    setStarting(true)
    setStartError(null)
    try {
      publication.current = await publish(camera, stored.whipUrl, stored.publishKey, {})
    } catch (error) {
      if (error instanceof MediaError && error.status === 401) {
        // It ended in the meantime (e.g. auto-ended after 60s without media).
        clearStoredBroadcast()
        setResumable(null)
        setStartError('이미 종료된 방송입니다. 새 방송을 시작해 주세요.')
      } else {
        setStartError(error instanceof MediaError ? error.message : '송출을 시작하지 못했습니다.')
      }
      return
    } finally {
      setStarting(false)
    }
    setTitle(broadcast.title)
    setOnAir({ id: broadcast.id, startedAt: broadcast.startedAt, publishKey: stored.publishKey })
    setResumable(null)
  }

  function handleStartOver() {
    clearStoredBroadcast()
    setResumable(null)
  }

  /** Ends the 방송 (the server also cuts the media connection), then releases the camera. */
  async function handleEnd() {
    if (!onAir || ending || !window.confirm('방송을 종료할까요?')) return
    setEnding(true)
    setEndError(null)
    try {
      await endBroadcast(onAir.id, onAir.publishKey)
    } catch (error) {
      setEndError(apiErrorMessage(error))
      return
    } finally {
      setEnding(false)
    }
    clearStoredBroadcast()
    publication.current?.stop()
    publication.current = null
    closeStream(currentCamera.current)
    currentCamera.current = null
    setCamera(null)
    setOnAir(null)
    setEnded(true)
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
        {onAir && (
          <div className="absolute top-3 left-3 flex items-center gap-2">
            <LiveBadge />
            <ElapsedTimer since={onAir.startedAt} />
          </div>
        )}
      </div>
      {switchError && (
        <p role="status" className="mt-2 text-sm text-amber-400">
          {switchError}
        </p>
      )}

      {onAir ? (
        <div className="mt-4 space-y-2">
          <h1 className="text-xl font-bold">{title.trim()}</h1>
          <Link to={`/broadcasts/${onAir.id}`} target="_blank" className="text-emerald-400 hover:underline">
            시청 링크 열기
          </Link>
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
