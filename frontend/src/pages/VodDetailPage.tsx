import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { apiErrorMessage, getVod, type Vod } from '../api/vods'
import { LoadError } from './LoadError'
import { VodInfo } from '../vods/VodInfo'
import { CommentSection } from '../comments/CommentSection'

export function VodDetailPage() {
  const { id = '' } = useParams()
  /** `undefined` while loading, `null` for a VOD that does not exist. */
  const [vod, setVod] = useState<Vod | null>()
  const [loadError, setLoadError] = useState<string | null>(null)
  const [failedStreamUrl, setFailedStreamUrl] = useState<string>()

  useEffect(() => {
    let active = true
    getVod(id).then(
      (found) => {
        if (!active) return
        setLoadError(null)
        setVod(found)
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

  if (vod === undefined) return null

  if (vod === null) {
    return (
      <section className="py-16 text-center">
        <h1 className="text-2xl font-bold">다시보기를 찾을 수 없습니다</h1>
        <Link to="/" className="mt-4 inline-block text-emerald-400 hover:underline">
          목록으로 돌아가기
        </Link>
      </section>
    )
  }

  return (
    <article className="mx-auto max-w-4xl">
      {failedStreamUrl === vod.streamUrl ? (
        <div
          role="alert"
          className="flex aspect-video w-full items-center justify-center rounded-lg bg-zinc-900 text-zinc-300"
        >
          다시보기를 재생할 수 없습니다
        </div>
      ) : (
        <video
          data-testid="vod-player"
          src={vod.streamUrl}
          controls
          preload="metadata"
          onError={() => setFailedStreamUrl(vod.streamUrl)}
          className="aspect-video w-full rounded-lg bg-black"
        />
      )}
      <VodInfo vod={vod} onUpdated={setVod} />
      <CommentSection target={{ kind: 'vod', id: vod.id }} />
    </article>
  )
}
