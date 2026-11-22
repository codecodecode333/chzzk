import { useEffect, useState } from 'react'
import { listBroadcasts, type BroadcastSummary } from '../api/broadcasts'
import { formatDateTime } from '../format'
import { EndedBadge, LiveBadge } from './badges'
import { Unreachable } from './Unreachable'

/** 방송 중 is not paged: the API's maximum page size, far more than are ever live at once. */
const LIVE_PAGE_SIZE = 100
/** The most recently ended 방송 only; the app has no paging. */
const ENDED_PAGE_SIZE = 20
/** How often the list refreshes so newly started and newly ended 방송 show up by themselves. */
const REFRESH_MS = 10_000

interface Lists {
  live: BroadcastSummary[]
  ended: BroadcastSummary[]
}

/** The 방송 목록: 방송 중 on top (each opens the 시청 screen), recent 종료 below, refreshed every 10 seconds. */
export function BroadcastList({ backendUrl, onWatch }: { backendUrl: string; onWatch: (id: number) => void }) {
  const [lists, setLists] = useState<Lists | null>(null)
  const [unreachable, setUnreachable] = useState(false)

  useEffect(() => {
    let active = true
    const load = () =>
      Promise.all([
        listBroadcasts(backendUrl, 'LIVE', LIVE_PAGE_SIZE),
        listBroadcasts(backendUrl, 'ENDED', ENDED_PAGE_SIZE),
      ]).then(
        ([live, ended]) => {
          if (!active) return
          setUnreachable(false)
          setLists({ live: live.content, ended: ended.content })
        },
        () => {
          // A failed refresh keeps the last list on screen.
          if (active) setUnreachable(true)
        },
      )
    void load()
    const timer = setInterval(() => void load(), REFRESH_MS)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [backendUrl])

  return (
    <>
      <h1 className="sr-only">방송 목록</h1>
      {unreachable && <Unreachable backendUrl={backendUrl} />}
      {lists && (
        <>
          <section aria-labelledby="live-heading" className="mb-10">
            <h2 id="live-heading" className="mb-4 text-2xl font-bold">
              방송 중
            </h2>
            {lists.live.length === 0 ? (
              <p className="rounded-lg border border-dashed border-zinc-700 py-10 text-center text-zinc-400">
                지금 방송 중인 방송이 없습니다
              </p>
            ) : (
              <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {lists.live.map((b) => (
                  <li key={b.id}>
                    <button
                      type="button"
                      onClick={() => onWatch(b.id)}
                      className="block w-full rounded-lg text-left focus-visible:outline-2 focus-visible:outline-emerald-400"
                    >
                      <Card broadcast={b} />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section aria-labelledby="ended-heading">
            <h2 id="ended-heading" className="mb-4 text-xl font-bold">
              종료된 방송
            </h2>
            {lists.ended.length === 0 ? (
              <p className="py-6 text-center text-zinc-400">아직 종료된 방송이 없습니다</p>
            ) : (
              <ul className="grid grid-cols-1 gap-4 opacity-70 sm:grid-cols-2 lg:grid-cols-3">
                {lists.ended.map((b) => (
                  <li key={b.id}>
                    <Card broadcast={b} />
                  </li>
                ))}
              </ul>
            )}
          </section>
        </>
      )}
    </>
  )
}

function Card({ broadcast: b }: { broadcast: BroadcastSummary }) {
  const isLive = b.status === 'LIVE'
  return (
    <article className="overflow-hidden rounded-lg bg-zinc-900 hover:bg-zinc-800">
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
  )
}
