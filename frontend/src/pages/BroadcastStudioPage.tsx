import { isAxiosError } from 'axios'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiErrorMessage } from '../api/client'
import {
  endBroadcast,
  findLiveBroadcastOf,
  getBroadcast,
  issuePublishKey,
  liveBroadcastConflict,
  startBroadcast,
} from '../api/broadcasts'
import { validateTitle } from '../forms/title'
import { ElapsedTimer } from '../broadcasts/ElapsedTimer'
import { LiveBadge } from '../broadcasts/LiveBadge'
import { useCurrentMember } from '../members/currentMemberContext'
import { withNext } from '../members/next'
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

/** The 회원's 방송 중 방송 that this screen isn't sending: "이어서 송출" publishes into it with a fresh 송출 키. */
interface Resumable {
  id: number
  title: string
  startedAt: string
}

const PUBLISH_FAILED = '송출을 시작하지 못했습니다.'

function publishErrorMessage(error: unknown): string {
  return error instanceof MediaError ? error.message : PUBLISH_FAILED
}

function isUnauthenticated(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 401
}

/**
 * 송출 screen (`/broadcasts/new`, 회원 only): camera preview → title → 방송 시작 → 방송 중 → 방송 종료 → 종료됨.
 *
 * The 송출 키 lives only in this screen's memory. When the 회원 already has a 방송 중 방송 (found on load, or a 409 on
 * 방송 시작) it offers "이어서 송출": a fresh 송출 키 is issued (the old one, in another tab or device, stops working)
 * and the camera republishes into that same 방송. Leaving the page while 방송 중 asks first. When the publication
 * drops it re-reads the 방송: 종료 shows 종료됨, still 방송 중 offers "다시 연결" with the same key; if that key was
 * replaced meanwhile, another tab took over and "여기서 이어서 송출" takes it back. 방송 종료 uses the login session:
 * when it has expired, it asks to log in again (in a new tab) while publishing goes on.
 */
export function BroadcastStudioPage() {
  const { member } = useCurrentMember()
  const memberId = member?.id
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
  /** "다시 연결" was refused because another tab or device resumed this 방송 with a fresh 송출 키. */
  const [takenOver, setTakenOver] = useState(false)
  const [ending, setEnding] = useState(false)
  const [endError, setEndError] = useState<string | null>(null)
  /** 방송 종료 found the login session expired: log in again, then end; publishing goes on meanwhile. */
  const [needsLogin, setNeedsLogin] = useState(false)
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
    if (memberId === undefined) return
    let active = true
    findLiveBroadcastOf(memberId).then(
      (found) => {
        if (active && found) setResumable({ id: found.id, title: found.title, startedAt: found.startedAt })
      },
      () => {
        // Can't tell; offer a fresh start (a 409 on 방송 시작 then offers 이어서 송출 after all).
      },
    )
    return () => {
      active = false
    }
  }, [memberId])

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

  /** 종료됨: stops publishing and releases the camera. */
  function showEnded() {
    stopPublishing()
    closeStream(currentCamera.current)
    currentCamera.current = null
    setCamera(null)
    setLive(null)
    setDropped(false)
    setTakenOver(false)
    setNeedsLogin(false)
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
        const liveId = liveBroadcastConflict(error)
        if (liveId !== null) await offerResume(liveId, apiErrorMessage(error))
        else if (mounted.current) setStartError(apiErrorMessage(error))
        return
      }
      try {
        if (!(await connect(camera, target))) return
      } catch (error) {
        // Nothing is being sent, so don't leave the 방송 listed as 방송 중 (best effort: without media the server
        // auto-ends it within ~70s anyway).
        endBroadcast(target.id).catch(() => {})
        if (mounted.current) setStartError(publishErrorMessage(error))
        return
      }
      setLive(target)
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  /** A 409 on 방송 시작: offer "이어서 송출" for the 회원's 방송 중 방송 instead. */
  async function offerResume(id: number, message: string) {
    try {
      const broadcast = await getBroadcast(String(id))
      if (!mounted.current) return
      if (broadcast?.status === 'LIVE') {
        setResumable({ id: broadcast.id, title: broadcast.title, startedAt: broadcast.startedAt })
      }
    } catch {
      // Show the server's message alone.
    }
    if (mounted.current) setStartError(message)
  }

  /**
   * Publishes into a 방송 that is already 방송 중 (same id, same start time). `refused` means the media server
   * refused the 송출 키: the 방송 has ended meanwhile, or another tab resumed it with a fresh key.
   */
  async function republish(target: LiveBroadcast): Promise<'published' | 'refused' | 'failed'> {
    if (!camera) return 'failed'
    try {
      return (await connect(camera, target)) ? 'published' : 'failed'
    } catch (error) {
      if (error instanceof MediaError && error.status === 401) return 'refused'
      if (mounted.current) setStartError(publishErrorMessage(error))
      return 'failed'
    }
  }

  /**
   * "이어서 송출" (and "여기서 이어서 송출"): gets a fresh 송출 키 for the 방송 중 방송 `resumed`, which cuts off
   * whoever published with the old one, then publishes the camera into it.
   */
  async function resume(resumed: Resumable) {
    if (!camera || starting) return
    setStarting(true)
    setStartError(null)
    try {
      let target: LiveBroadcast
      try {
        const issued = await issuePublishKey(resumed.id)
        target = { id: resumed.id, startedAt: resumed.startedAt, ...issued }
      } catch (error) {
        if (mounted.current) setStartError(apiErrorMessage(error))
        return
      }
      if ((await republish(target)) !== 'published' || !mounted.current) return
      setTitle(resumed.title)
      setLive(target)
      setResumable(null)
      setDropped(false)
      setTakenOver(false)
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  async function handleReconnect() {
    if (!live || starting) return
    setStarting(true)
    setStartError(null)
    try {
      const result = await republish(live)
      if (!mounted.current) return
      if (result === 'published') setDropped(false)
      else if (result === 'refused') await handleRefused(live.id)
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  /** The 송출 키 was refused: 종료됨 if the 방송 has ended, otherwise another tab or device took it over. */
  async function handleRefused(id: number) {
    try {
      const broadcast = await getBroadcast(String(id))
      if (!mounted.current) return
      if (broadcast?.status === 'LIVE') setTakenOver(true)
      else showEnded()
    } catch (error) {
      if (mounted.current) setStartError(apiErrorMessage(error))
    }
  }

  function handleTakeBack() {
    if (live) void resume({ id: live.id, title, startedAt: live.startedAt })
  }

  /** "새 방송으로 시작": ends the 방송 중 방송 first, so it doesn't linger, then offers a fresh start. */
  async function handleStartOver() {
    if (!resumable || starting) return
    setStarting(true)
    setStartError(null)
    try {
      await endBroadcast(resumable.id)
      if (mounted.current) setResumable(null)
    } catch (error) {
      if (mounted.current) setStartError(apiErrorMessage(error))
    } finally {
      if (mounted.current) setStarting(false)
    }
  }

  /**
   * Ends the 방송 by the login session (the server also cuts the media connection), then releases the camera.
   * An expired session asks to log in again; the 방송 keeps publishing meanwhile.
   */
  async function handleEnd() {
    if (!live || ending || !window.confirm('방송을 종료할까요?')) return
    setEnding(true)
    setEndError(null)
    setNeedsLogin(false)
    try {
      await endBroadcast(live.id)
    } catch (error) {
      if (!mounted.current) return
      if (isUnauthenticated(error)) setNeedsLogin(true)
      else setEndError(apiErrorMessage(error))
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
              {takenOver ? (
                <p>다른 탭이나 기기에서 이 방송을 이어서 송출하고 있어, 여기서는 송출이 멈췄습니다.</p>
              ) : (
                <p>송출이 끊겼습니다. 방송은 아직 방송 중이니 다시 연결해 주세요.</p>
              )}
              {startError && <p className="text-red-400">{startError}</p>}
              <button
                type="button"
                onClick={takenOver ? handleTakeBack : handleReconnect}
                disabled={!camera || starting}
                className="w-full rounded bg-emerald-500 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:opacity-50"
              >
                {takenOver ? '여기서 이어서 송출' : '다시 연결'}
              </button>
            </div>
          )}
          {needsLogin && (
            <p role="alert" className="text-sm text-amber-200">
              로그인이 만료되었습니다. 방송은 계속 송출 중입니다.{' '}
              <a
                // Back to the 시청 page, not here: a second 송출 screen would offer "이어서 송출" and take over.
                href={withNext('/login', `/broadcasts/${live.id}`)}
                target="_blank"
                rel="noopener"
                className="font-semibold text-emerald-400 hover:underline"
              >
                다시 로그인
              </a>
              한 뒤 방송 종료를 다시 눌러 주세요.
            </p>
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
            방송 중인 <strong className="text-zinc-100">{resumable.title}</strong> 방송이 있습니다.
          </p>
          {startError && (
            <p role="alert" className="text-sm text-red-400">
              {startError}
            </p>
          )}
          <button
            type="button"
            onClick={() => void resume(resumable)}
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
