import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { endBroadcast, startBroadcast } from '../api/broadcasts'
import { ElapsedTimer } from '../broadcasts/ElapsedTimer'
import { LiveBadge } from '../broadcasts/LiveBadge'
import { closeStream, MediaError, openCamera, publish, type Session } from '../media/media'

interface OnAir {
  id: number
  startedAt: string
  publishKey: string
}

/** 송출 screen (`/broadcasts/new`): camera preview → title → 방송 시작 → 방송 중 → 방송 종료 → 종료됨. */
export function BroadcastStudioPage() {
  const [camera, setCamera] = useState<MediaStream | null>(null)
  const [cameraError, setCameraError] = useState<string | null>(null)
  const [title, setTitle] = useState('')
  const [starting, setStarting] = useState(false)
  const [startError, setStartError] = useState<string | null>(null)
  const [onAir, setOnAir] = useState<OnAir | null>(null)
  const [ending, setEnding] = useState(false)
  const [endError, setEndError] = useState<string | null>(null)
  const [ended, setEnded] = useState(false)
  const publication = useRef<Session | null>(null)

  useEffect(() => {
    let active = true
    let opened: MediaStream | null = null
    openCamera().then(
      (stream) => {
        if (!active) return closeStream(stream)
        opened = stream
        setCamera(stream)
      },
      (error: unknown) => {
        if (active) setCameraError(error instanceof MediaError ? error.message : '카메라를 열지 못했습니다.')
      },
    )
    return () => {
      active = false
      publication.current?.stop()
      publication.current = null
      closeStream(opened)
    }
  }, [])

  async function handleStart(event: FormEvent) {
    event.preventDefault()
    if (!camera || starting) return
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
      setOnAir({ id: started.id, startedAt: started.startedAt, publishKey: started.publishKey })
    } catch (error) {
      setStartError(apiErrorMessage(error))
    } finally {
      setStarting(false)
    }
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
    publication.current?.stop()
    publication.current = null
    closeStream(camera)
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
            className="aspect-[3/4] w-full -scale-x-100 object-cover"
          />
        )}
        {onAir && (
          <div className="absolute top-3 left-3 flex items-center gap-2">
            <LiveBadge />
            <ElapsedTimer since={onAir.startedAt} />
          </div>
        )}
      </div>

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
      ) : (
        <form onSubmit={handleStart} className="mt-4 space-y-3">
          <label className="block">
            <span className="mb-1 block text-sm text-zinc-300">제목</span>
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              className="w-full rounded border border-zinc-700 bg-zinc-900 px-3 py-2"
            />
          </label>
          {startError && (
            <p role="alert" className="text-sm text-red-400">
              {startError}
            </p>
          )}
          <button
            type="submit"
            disabled={!camera || starting}
            className="w-full rounded bg-emerald-500 py-2.5 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:opacity-50"
          >
            방송 시작
          </button>
        </form>
      )}
    </section>
  )
}
