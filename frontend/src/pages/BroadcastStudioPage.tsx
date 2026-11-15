import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { startBroadcast } from '../api/broadcasts'
import { validateTitle } from '../forms/title'
import { ElapsedTimer } from '../broadcasts/ElapsedTimer'
import { LiveBadge } from '../broadcasts/LiveBadge'
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
}

/** 송출 screen (`/broadcasts/new`): camera preview → title → 방송 시작 → 방송 중. */
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
  const publication = useRef<Session | null>(null)
  const [facing, setFacing] = useState<Facing>('user')
  const [switching, setSwitching] = useState(false)
  const [switchError, setSwitchError] = useState<string | null>(null)
  /** The camera stream to release on leaving; it changes with each camera switch. */
  const currentCamera = useRef<MediaStream | null>(null)
  const mounted = useRef(false)

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
      try {
        publication.current = await publish(camera, started.whipUrl, started.publishKey, {})
      } catch (error) {
        setStartError(error instanceof MediaError ? error.message : '송출을 시작하지 못했습니다.')
        return
      }
      setOnAir({ id: started.id, startedAt: started.startedAt })
    } catch (error) {
      setStartError(apiErrorMessage(error))
    } finally {
      setStarting(false)
    }
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
