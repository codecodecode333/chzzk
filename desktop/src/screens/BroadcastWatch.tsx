import { useCallback, useEffect, useRef, useState } from 'react'
import { getBroadcast, type Broadcast } from '../api/broadcasts'
import { formatDateTime } from '../format'
import { watch } from '../media/media'
import { EndedBadge, LiveBadge } from './badges'
import { Unreachable } from './Unreachable'

/** How often the 시청 screen re-reads the 방송 status while it is 방송 중. */
const STATUS_POLL_MS = 5000

/**
 * The 시청 screen of one 방송. While 방송 중 it re-reads the status every 5s, and right away when the WebRTC
 * connection fails or drops; once the 방송 is 종료 the player is torn down and the ended message shows. While the
 * backend cannot be reached it says so and keeps checking every 5s (a running playback is left alone).
 */
export function BroadcastWatch({ backendUrl, id, onBack }: { backendUrl: string; id: number; onBack: () => void }) {
  /** `undefined` while loading, `null` for a 방송 that does not exist. */
  const [broadcast, setBroadcast] = useState<Broadcast | null>()
  const [unreachable, setUnreachable] = useState(false)
  /** Re-reads the 방송 status; replaced on every id change so a stale response never lands. */
  const refreshRef = useRef<() => void>(() => {})

  useEffect(() => {
    let active = true
    const refresh = () => {
      getBroadcast(backendUrl, id).then(
        (found) => {
          if (!active) return
          setUnreachable(false)
          setBroadcast(found)
        },
        () => {
          if (active) setUnreachable(true)
        },
      )
    }
    refreshRef.current = refresh
    refresh()
    return () => {
      active = false
    }
  }, [backendUrl, id])

  const live = broadcast?.status === 'LIVE'
  const keepChecking = live || unreachable
  useEffect(() => {
    if (!keepChecking) return
    const timer = setInterval(() => refreshRef.current(), STATUS_POLL_MS)
    return () => clearInterval(timer)
  }, [keepChecking])

  const refreshNow = useCallback(() => refreshRef.current(), [])

  return (
    <article>
      <button type="button" onClick={onBack} className="mb-4 text-emerald-400 hover:underline">
        <span aria-hidden="true">← </span>목록으로
      </button>
      {unreachable && <Unreachable backendUrl={backendUrl} />}
      {broadcast === null && (
        <p className="py-16 text-center text-xl font-bold">방송을 찾을 수 없습니다</p>
      )}
      {broadcast && (
        <>
          {live && broadcast.whepUrl ? (
            <LivePlayer whepUrl={new URL(broadcast.whepUrl, backendUrl).toString()} onDisconnect={refreshNow} />
          ) : (
            <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-zinc-900 text-zinc-300">
              방송이 종료되었습니다
            </div>
          )}
          <header className="mt-4 space-y-1">
            <div className="flex items-center gap-2">
              {live ? <LiveBadge /> : <EndedBadge />}
              <h1 className="text-xl font-bold">{broadcast.title}</h1>
            </div>
            <p className="text-zinc-300">{broadcast.owner.nickname}</p>
            <p className="text-sm text-zinc-400">
              <time dateTime={broadcast.startedAt}>{formatDateTime(broadcast.startedAt)}</time> 시작
            </p>
          </header>
        </>
      )}
    </article>
  )
}

/** How long the player waits before trying WHEP again while the media isn't flowing. */
const RETRY_MS = 3000

/** Whether media reaches this player yet (not a 방송 상태: the 방송 is 방송 중 throughout). */
type PlayerConnection =
  | { state: 'connecting' }
  | { state: 'playing'; stream: MediaStream }
  | { state: 'awaiting-media' }

/**
 * Plays a 방송 over WHEP, as the web's 시청 screen does: autoplays muted with a 소리 켜기 button, shows
 * "송출 준비 중…" and retries every 3s while the connection fails or drops. `onDisconnect` fires on every failure.
 */
function LivePlayer({ whepUrl, onDisconnect }: { whepUrl: string; onDisconnect?: () => void }) {
  const [connection, setConnection] = useState<PlayerConnection>({ state: 'connecting' })
  const stream = connection.state === 'playing' ? connection.stream : null
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
      onDisconnectRef.current?.()
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
              video.muted = false
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
