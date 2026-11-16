import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { getBroadcast, type Broadcast } from '../api/broadcasts'
import { LiveBadge } from '../broadcasts/LiveBadge'
import { formatDateTime } from '../format'
import { useCurrentMember } from '../members/currentMemberContext'
import { watch } from '../media/media'
import { LoadError } from './LoadError'

/** How often the 시청 screen re-reads the 방송 status while it is 방송 중. */
const STATUS_POLL_MS = 5000

/**
 * 시청 screen (`/broadcasts/:id`). While 방송 중 it polls the status every 5s, and right away when the WebRTC
 * connection fails or drops; once the 방송 is 종료 the player is torn down (stopping playback and its retries)
 * and the ended view shows.
 */
export function BroadcastWatchPage() {
  const { id = '' } = useParams()
  /** `undefined` while loading, `null` for a 방송 that does not exist. */
  const [broadcast, setBroadcast] = useState<Broadcast | null>()
  const [loadError, setLoadError] = useState<string | null>(null)
  /** Re-reads the 방송 status; replaced on every id change so a stale response never lands. */
  const refreshRef = useRef<() => void>(() => {})

  useEffect(() => {
    let active = true
    let loaded = false
    const refresh = () => {
      getBroadcast(id).then(
        (found) => {
          if (!active) return
          loaded = true
          setLoadError(null)
          setBroadcast(found)
        },
        (error: unknown) => {
          // A failed poll keeps the last known state; only a failed first load replaces the page.
          if (active && !loaded) setLoadError(apiErrorMessage(error))
        },
      )
    }
    refreshRef.current = refresh
    refresh()
    return () => {
      active = false
    }
  }, [id])

  const { member } = useCurrentMember()
  const live = broadcast?.status === 'LIVE'
  /** The 소유자's controls show only to the 소유자 (the server stays the authority). */
  const isOwner = member !== null && broadcast?.owner.id === member.id
  useEffect(() => {
    if (!live) return
    const timer = setInterval(() => refreshRef.current(), STATUS_POLL_MS)
    return () => clearInterval(timer)
  }, [live])

  const refreshNow = useCallback(() => refreshRef.current(), [])

  if (loadError) return <LoadError message={loadError} />

  if (broadcast === undefined) return null

  if (broadcast === null) {
    return (
      <section className="py-16 text-center">
        <h1 className="text-2xl font-bold">방송을 찾을 수 없습니다</h1>
        <Link to="/" className="mt-4 inline-block text-emerald-400 hover:underline">
          홈으로 돌아가기
        </Link>
      </section>
    )
  }

  return (
    <article className="mx-auto max-w-4xl">
      {live && broadcast.whepUrl ? (
        <LivePlayer whepUrl={broadcast.whepUrl} onDisconnect={refreshNow} />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-zinc-900 text-zinc-300">
          종료된 방송입니다
        </div>
      )}
      <div className="mt-4 flex items-center gap-2">
        {broadcast.status === 'LIVE' && <LiveBadge />}
        <h1 className="text-2xl font-bold">{broadcast.title}</h1>
      </div>
      <p className="mt-1 text-zinc-300">{broadcast.owner.nickname}</p>
      {isOwner && live && (
        <Link to="/broadcasts/new" className="text-sm text-emerald-400 hover:underline">
          송출 화면으로
        </Link>
      )}
      <p className="flex gap-3 text-sm text-zinc-400">
        <time dateTime={broadcast.startedAt}>시작 {formatDateTime(broadcast.startedAt)}</time>
        {broadcast.endedAt && <time dateTime={broadcast.endedAt}>종료 {formatDateTime(broadcast.endedAt)}</time>}
      </p>
    </article>
  )
}

/** How long the player waits before trying WHEP again while the media isn't flowing. */
const RETRY_MS = 3000

/**
 * The player's connection to the media server. This is not a 방송 상태 (the 방송 is 방송 중 throughout): it only says
 * whether media reaches this player yet. `awaiting-media` is shown as "송출 준비 중…".
 */
type PlayerConnection =
  | { state: 'connecting' }
  | { state: 'playing'; stream: MediaStream }
  | { state: 'awaiting-media' }

/**
 * Plays a 방송 over WHEP: autoplays muted (browsers block unmuted autoplay) with a 소리 켜기 overlay.
 * While the connection fails or drops (media not flowing yet, or the 송출자 reconnecting) it shows "송출 준비 중…"
 * and tries again every 3s; the page unmounts it once the 방송 is 종료, which stops the retries and the playback.
 * `onDisconnect` fires on every failure or drop, so the page can re-check the status right away.
 */
function LivePlayer({ whepUrl, onDisconnect }: { whepUrl: string; onDisconnect: () => void }) {
  const [connection, setConnection] = useState<PlayerConnection>({ state: 'connecting' })
  const stream = connection.state === 'playing' ? connection.stream : null
  /** Bumped to make another connection attempt. */
  const [attempt, setAttempt] = useState(0)
  const [muted, setMuted] = useState(true)
  const videoRef = useRef<HTMLVideoElement>(null)
  const onDisconnectRef = useRef(onDisconnect)
  useEffect(() => {
    onDisconnectRef.current = onDisconnect
  }, [onDisconnect])

  useEffect(() => {
    let active = true
    let failed = false
    let stop: (() => void) | undefined
    let retry: ReturnType<typeof setTimeout> | undefined
    const fail = () => {
      if (!active || failed) return
      failed = true
      stop?.()
      stop = undefined
      setConnection({ state: 'awaiting-media' })
      onDisconnectRef.current()
      retry = setTimeout(() => setAttempt((n) => n + 1), RETRY_MS)
    }
    watch(whepUrl, { onDisconnect: fail }).then((playback) => {
      if (!active || failed) return playback.stop()
      stop = () => playback.stop()
      setConnection({ state: 'playing', stream: playback.stream })
    }, fail)
    return () => {
      active = false
      clearTimeout(retry)
      stop?.()
    }
  }, [whepUrl, attempt])

  useEffect(() => {
    const video = videoRef.current
    if (!video) return
    video.muted = muted // the property, not just the attribute: React doesn't keep `muted` in sync
    if (video.srcObject !== stream) video.srcObject = stream
  }, [stream, muted])

  return (
    <div className="relative">
      <video
        data-testid="live-player"
        ref={videoRef}
        autoPlay
        muted
        playsInline
        className="aspect-video w-full rounded-lg bg-black"
      />
      {connection.state === 'awaiting-media' && (
        <p role="status" className="absolute inset-0 flex items-center justify-center text-zinc-300">
          송출 준비 중…
        </p>
      )}
      {stream && muted && (
        <button
          type="button"
          onClick={() => {
            setMuted(false)
            const video = videoRef.current
            if (video) {
              video.muted = false // within the user gesture, so the browser lets sound play
              video.play().catch(() => {})
            }
          }}
          className="absolute bottom-3 left-3 rounded bg-black/70 px-3 py-1.5 text-sm font-semibold text-white hover:bg-black/90"
        >
          소리 켜기
        </button>
      )}
    </div>
  )
}
