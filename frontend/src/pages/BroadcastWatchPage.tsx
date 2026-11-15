import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { getBroadcast, type Broadcast } from '../api/broadcasts'
import { LiveBadge } from '../broadcasts/LiveBadge'
import { formatDate } from '../format'
import { MediaError, watch } from '../media/media'
import { LoadError } from './LoadError'

/** 시청 screen (`/broadcasts/:id`). */
export function BroadcastWatchPage() {
  const { id = '' } = useParams()
  /** `undefined` while loading, `null` for a 방송 that does not exist. */
  const [broadcast, setBroadcast] = useState<Broadcast | null>()
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    getBroadcast(id).then(
      (found) => {
        if (!active) return
        setLoadError(null)
        setBroadcast(found)
      },
      (error: unknown) => {
        if (active) setLoadError(apiErrorMessage(error))
      },
    )
    return () => {
      active = false
    }
  }, [id])

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
      {broadcast.whepUrl ? (
        <LivePlayer whepUrl={broadcast.whepUrl} />
      ) : (
        <div className="flex aspect-video w-full items-center justify-center rounded-lg bg-zinc-900 text-zinc-300">
          종료된 방송입니다
        </div>
      )}
      <div className="mt-4 flex items-center gap-2">
        {broadcast.status === 'LIVE' && <LiveBadge />}
        <h1 className="text-2xl font-bold">{broadcast.title}</h1>
      </div>
      <time dateTime={broadcast.startedAt} className="text-sm text-zinc-400">
        {formatDate(broadcast.startedAt)}
      </time>
    </article>
  )
}

/** Plays a 방송 over WHEP: autoplays muted (browsers block unmuted autoplay) with a 소리 켜기 overlay. */
function LivePlayer({ whepUrl }: { whepUrl: string }) {
  const [stream, setStream] = useState<MediaStream | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [muted, setMuted] = useState(true)
  const videoRef = useRef<HTMLVideoElement>(null)

  useEffect(() => {
    let active = true
    let stop: (() => void) | undefined
    watch(whepUrl, {}).then(
      (playback) => {
        if (!active) return playback.stop()
        stop = () => playback.stop()
        setStream(playback.stream)
      },
      (failure: unknown) => {
        if (active) setError(failure instanceof MediaError ? failure.message : '방송에 연결하지 못했습니다.')
      },
    )
    return () => {
      active = false
      stop?.()
    }
  }, [whepUrl])

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
      {error && (
        <p role="alert" className="absolute inset-0 flex items-center justify-center text-zinc-300">
          {error}
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
