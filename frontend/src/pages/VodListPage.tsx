import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { apiErrorMessage, listVods, type Page, type VodSummary } from '../api/vods'
import { LoadError } from './LoadError'
import { formatDate } from '../format'
import { pageFromUrl } from './pageFromUrl'
import { Pagination } from './Pagination'

export function VodListPage() {
  const [searchParams] = useSearchParams()
  const pageNumber = pageFromUrl(searchParams.get('page'))
  const [vods, setVods] = useState<Page<VodSummary> | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    listVods(pageNumber - 1).then(
      (result) => {
        if (!active) return
        setLoadError(null)
        setVods(result)
      },
      (error: unknown) => {
        if (active) setLoadError(apiErrorMessage(error))
      },
    )
    return () => {
      active = false
    }
  }, [pageNumber])

  if (loadError) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-bold">다시보기</h1>
        <LoadError message={loadError} />
      </section>
    )
  }

  return (
    <section>
      <h1 className="mb-4 text-2xl font-bold">다시보기</h1>
      {vods && vods.totalElements === 0 ? (
        <EmptyState />
      ) : (
        <>
          {vods && vods.content.length === 0 && (
            <p className="py-16 text-center text-zinc-400">
              이 페이지에는 다시보기가 없습니다.{' '}
              <Link to="/vods" className="text-emerald-400 underline">
                첫 페이지로
              </Link>
            </p>
          )}
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {vods?.content.map((vod) => (
              <li key={vod.id}>
                <Link
                  to={`/vods/${vod.id}`}
                  className="block rounded-lg focus-visible:outline-2 focus-visible:outline-emerald-400"
                >
                  <article className="overflow-hidden rounded-lg bg-zinc-900 hover:bg-zinc-800">
                    <div aria-hidden="true" className="aspect-video bg-zinc-800" />
                    <div className="p-3">
                      <h2 className="truncate font-semibold">{vod.title}</h2>
                      <p className="truncate text-sm text-zinc-300">{vod.owner.nickname}</p>
                      <time dateTime={vod.createdAt} className="text-sm text-zinc-400">
                        {formatDate(vod.createdAt)}
                      </time>
                    </div>
                  </article>
                </Link>
              </li>
            ))}
          </ul>
          {vods && vods.totalPages > 1 && (
            <Pagination basePath="/vods" current={pageNumber} total={vods.totalPages} />
          )}
        </>
      )}
    </section>
  )
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-4 rounded-lg border border-dashed border-zinc-700 py-16 text-center">
      <p className="text-zinc-400">아직 등록된 다시보기가 없습니다</p>
      <Link
        to="/vods/new"
        className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400"
      >
        다시보기 등록
      </Link>
    </div>
  )
}
