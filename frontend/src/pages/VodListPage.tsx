import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listVods, type Page, type VodSummary } from '../api/vods'
import { formatDate } from '../format'

/** The URL carries a one-based `?page=`; anything that is not a positive integer means the first page. */
function pageFromUrl(raw: string | null): number {
  const page = Number(raw)
  return Number.isInteger(page) && page >= 1 ? page : 1
}

export function VodListPage() {
  const [searchParams] = useSearchParams()
  const currentPage = pageFromUrl(searchParams.get('page'))
  const [page, setPage] = useState<Page<VodSummary> | null>(null)

  useEffect(() => {
    let active = true
    listVods(currentPage - 1).then((result) => {
      if (active) setPage(result)
    })
    return () => {
      active = false
    }
  }, [currentPage])

  return (
    <section>
      <h1 className="mb-4 text-2xl font-bold">다시보기</h1>
      {page && page.totalElements === 0 ? (
        <EmptyState />
      ) : (
        <>
          {page && page.content.length === 0 && (
            <p className="py-16 text-center text-zinc-400">
              이 페이지에는 다시보기가 없습니다.{' '}
              <Link to="/" className="text-emerald-400 underline">
                첫 페이지로
              </Link>
            </p>
          )}
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {page?.content.map((vod) => (
              <li key={vod.id}>
                <Link
                  to={`/vods/${vod.id}`}
                  className="block rounded-lg focus-visible:outline-2 focus-visible:outline-emerald-400"
                >
                  <article className="overflow-hidden rounded-lg bg-zinc-900 hover:bg-zinc-800">
                    <div aria-hidden="true" className="aspect-video bg-zinc-800" />
                    <div className="p-3">
                      <h2 className="truncate font-semibold">{vod.title}</h2>
                      <time dateTime={vod.createdAt} className="text-sm text-zinc-400">
                        {formatDate(vod.createdAt)}
                      </time>
                    </div>
                  </article>
                </Link>
              </li>
            ))}
          </ul>
          {page && page.totalPages > 1 && <Pagination current={currentPage} total={page.totalPages} />}
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

const PAGE_WINDOW = 2

/** One-based page links; shows the pages within PAGE_WINDOW of the current one. */
function Pagination({ current, total }: { current: number; total: number }) {
  const first = Math.max(1, current - PAGE_WINDOW)
  const last = Math.min(total, current + PAGE_WINDOW)
  const pages = Array.from({ length: Math.max(0, last - first + 1) }, (_, i) => first + i)
  const linkClass = 'rounded px-3 py-1.5 text-sm hover:bg-zinc-800'

  return (
    <nav aria-label="페이지" className="mt-6 flex items-center justify-center gap-1">
      {current > 1 && (
        <Link to={`/?page=${Math.min(current - 1, total)}`} className={linkClass}>
          이전
        </Link>
      )}
      {pages.map((n) => (
        <Link
          key={n}
          to={`/?page=${n}`}
          aria-current={n === current ? 'page' : undefined}
          className={`${linkClass} ${n === current ? 'bg-emerald-500 font-semibold text-zinc-950 hover:bg-emerald-400' : ''}`}
        >
          {n}
        </Link>
      ))}
      {current < total && (
        <Link to={`/?page=${current + 1}`} className={linkClass}>
          다음
        </Link>
      )}
    </nav>
  )
}
