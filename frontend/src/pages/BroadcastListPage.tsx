import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { listBroadcasts, type BroadcastSummary } from '../api/broadcasts'
import { apiErrorMessage, type Page } from '../api/client'
import { EndedBadge } from '../broadcasts/EndedBadge'
import { LiveBadge } from '../broadcasts/LiveBadge'
import { formatDateTime } from '../format'
import { LoadError } from './LoadError'
import { pageFromUrl } from './pageFromUrl'
import { Pagination } from './Pagination'

/** How often the list refreshes so newly started and newly ended 방송 show up without a reload. */
const LIST_POLL_INTERVAL_MS = 10_000

/** 방송 중 is not paged: ask for the API's maximum page size, far more than are ever live at once. */
const LIVE_PAGE_SIZE = 100

interface Lists {
  live: Page<BroadcastSummary>
  ended: Page<BroadcastSummary>
}

/** The home page: 방송 중 on top, paged 종료 below (`?page=` is the 종료 page), refreshed every 10 seconds. */
export function BroadcastListPage() {
  const [searchParams] = useSearchParams()
  const pageNumber = pageFromUrl(searchParams.get('page'))
  const [lists, setLists] = useState<Lists | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    const load = () =>
      Promise.all([listBroadcasts('LIVE', 0, LIVE_PAGE_SIZE), listBroadcasts('ENDED', pageNumber - 1)]).then(
        ([live, ended]) => {
          if (!active) return
          setLoadError(null)
          setLists({ live, ended })
        },
        (error: unknown) => {
          // A failed refresh keeps the last list on screen; only a first load with nothing to show is an error.
          if (active) setLoadError(apiErrorMessage(error))
        },
      )
    void load()
    const timer = setInterval(() => void load(), LIST_POLL_INTERVAL_MS)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [pageNumber])

  if (!lists) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-bold">라이브</h1>
        {loadError && <LoadError message={loadError} />}
      </section>
    )
  }

  const { live, ended } = lists
  if (live.totalElements === 0 && ended.totalElements === 0) {
    return (
      <section>
        <h1 className="mb-4 text-2xl font-bold">라이브</h1>
        <EmptyState />
      </section>
    )
  }

  return (
    <>
      <h1 className="sr-only">라이브</h1>
      <section aria-labelledby="live-heading" className="mb-10">
        <h2 id="live-heading" className="mb-4 text-2xl font-bold">
          방송 중
        </h2>
        {live.content.length === 0 ? (
          <p className="rounded-lg border border-dashed border-zinc-700 py-10 text-center text-zinc-400">
            지금 방송 중인 방송이 없습니다
          </p>
        ) : (
          <CardGrid broadcasts={live.content} />
        )}
      </section>
      <section aria-labelledby="ended-heading">
        <h2 id="ended-heading" className="mb-4 text-xl font-bold">
          종료된 방송
        </h2>
        {ended.totalElements === 0 ? (
          <p className="py-6 text-center text-zinc-400">아직 종료된 방송이 없습니다</p>
        ) : ended.content.length === 0 ? (
          <p className="py-6 text-center text-zinc-400">
            이 페이지에는 종료된 방송이 없습니다.{' '}
            <Link to="/" className="text-emerald-400 underline">
              첫 페이지로
            </Link>
          </p>
        ) : (
          <CardGrid broadcasts={ended.content} />
        )}
        {ended.totalPages > 1 && <Pagination basePath="/" current={pageNumber} total={ended.totalPages} />}
      </section>
    </>
  )
}

function CardGrid({ broadcasts }: { broadcasts: BroadcastSummary[] }) {
  return (
    <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
      {broadcasts.map((b) => (
        <li key={b.id}>
          <BroadcastCard broadcast={b} />
        </li>
      ))}
    </ul>
  )
}

function BroadcastCard({ broadcast: b }: { broadcast: BroadcastSummary }) {
  const isLive = b.status === 'LIVE'
  return (
    <Link
      to={`/broadcasts/${b.id}`}
      className="block rounded-lg focus-visible:outline-2 focus-visible:outline-emerald-400"
    >
      <article className="overflow-hidden rounded-lg bg-zinc-900 hover:bg-zinc-800">
        <div aria-hidden="true" className="aspect-video bg-zinc-800" />
        <div className="space-y-1 p-3">
          <div className="flex items-center gap-2">
            {isLive ? <LiveBadge /> : <EndedBadge />}
            <h3 className="truncate font-semibold">{b.title}</h3>
          </div>
          <p className="truncate text-sm text-zinc-300">{b.owner.nickname}</p>
          <p className="flex flex-wrap gap-x-3 text-sm text-zinc-400">
            <span>
              <time dateTime={b.startedAt}>{formatDateTime(b.startedAt)}</time> 시작
            </span>
            {!isLive && b.endedAt && (
              <span>
                <time dateTime={b.endedAt}>{formatDateTime(b.endedAt)}</time> 종료
              </span>
            )}
          </p>
        </div>
      </article>
    </Link>
  )
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center gap-4 rounded-lg border border-dashed border-zinc-700 py-16 text-center">
      <p className="text-zinc-400">아직 방송이 없습니다. 첫 방송을 시작해 보세요!</p>
      <Link
        to="/broadcasts/new"
        className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400"
      >
        방송 시작
      </Link>
    </div>
  )
}
