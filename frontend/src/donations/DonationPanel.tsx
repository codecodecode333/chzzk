import { useEffect, useRef, useState } from 'react'
import { apiErrorMessage } from '../api/client'
import { listDonations, type DonationEntry } from '../api/donations'
import { formatDateTime, formatWon } from '../format'

/**
 * Adds freshly read newer 후원 that aren't shown yet, keeping newest first. Ids only grow, so the newcomers sort above
 * what is shown and older pages already loaded stay below.
 */
function mergeNewer(shown: DonationEntry[], newest: DonationEntry[]): DonationEntry[] {
  const shownIds = new Set(shown.map((d) => d.id))
  const added = newest.filter((d) => !shownIds.has(d.id))
  return added.length === 0 ? shown : [...added, ...shown].sort((a, b) => b.id - a.id)
}

interface Loaded {
  broadcastId: number
  donations: DonationEntry[]
  nextBefore: number | null
  total: number
}

/**
 * The 후원 panel of a 방송: the 후원 합계 and the confirmed 후원 내역, newest first with "더 보기". Anyone sees it.
 *
 * With `refreshEveryMs` (a 방송 중 방송) it reads, that often, every 후원 newer than the newest it has read (page by
 * page with the `after` cursor, so none is skipped) and adds them on top, keeping older pages already loaded; each read
 * also brings the current 합계. A failed first load is retried on the next tick, and a tick is skipped while the
 * previous read is still waiting. Without it nothing refreshes.
 */
export function DonationPanel({ broadcastId, refreshEveryMs }: { broadcastId: number; refreshEveryMs?: number }) {
  const [loaded, setLoaded] = useState<Loaded>()
  const [loadError, setLoadError] = useState<{ broadcastId: number; message: string }>()
  const [loadingMore, setLoadingMore] = useState(false)
  /** The newest 후원 id read for this 방송 (0: none yet), once its first page has loaded. */
  const newestRead = useRef<{ broadcastId: number; id: number }>(undefined)
  const current = loaded?.broadcastId === broadcastId ? loaded : undefined
  const shownLoadError = loadError?.broadcastId === broadcastId ? loadError.message : null

  useEffect(() => {
    let active = true
    let reading = false

    async function readFirstPage() {
      const page = await listDonations(broadcastId)
      if (!active) return
      newestRead.current = { broadcastId, id: page.content[0]?.id ?? 0 }
      setLoaded({ broadcastId, donations: page.content, nextBefore: page.nextBefore, total: page.total })
      setLoadError(undefined)
    }

    async function readNewer(after: number) {
      for (;;) {
        const page = await listDonations(broadcastId, { after })
        if (!active) return
        if (page.content.length > 0) {
          after = page.content[0].id
          newestRead.current = { broadcastId, id: after }
        }
        const newer = page.content
        setLoaded((prev) =>
          prev && prev.broadcastId === broadcastId
            ? { ...prev, donations: mergeNewer(prev.donations, newer), total: page.total }
            : prev,
        )
        if (page.nextAfter == null) return
      }
    }

    async function read() {
      if (reading) return // the previous read is still waiting
      reading = true
      const cursor = newestRead.current?.broadcastId === broadcastId ? newestRead.current.id : null
      try {
        if (cursor === null) await readFirstPage()
        else await readNewer(cursor)
      } catch (error) {
        // A failed refresh keeps what is shown; a failed first load shows why. The next tick tries again.
        if (active && cursor === null) setLoadError({ broadcastId, message: apiErrorMessage(error) })
      } finally {
        reading = false
      }
    }

    // The first page once per 방송; a change of `refreshEveryMs` alone keeps what is loaded.
    if (newestRead.current?.broadcastId !== broadcastId) void read()
    const timer = refreshEveryMs ? setInterval(() => void read(), refreshEveryMs) : undefined
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [broadcastId, refreshEveryMs])

  async function loadMore() {
    if (!current || current.nextBefore === null || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await listDonations(broadcastId, { before: current.nextBefore })
      // A 후원 confirmed meanwhile can't reappear here: older pages only hold ids below the cursor.
      setLoaded((prev) =>
        prev && prev.broadcastId === broadcastId
          ? { ...prev, donations: [...prev.donations, ...page.content], nextBefore: page.nextBefore }
          : prev,
      )
      setLoadError(undefined)
    } catch (error) {
      setLoadError({ broadcastId, message: apiErrorMessage(error) })
    } finally {
      setLoadingMore(false)
    }
  }

  return (
    <section aria-labelledby="donations-heading" className="mt-8">
      <h2 id="donations-heading" className="text-lg font-bold">
        후원
      </h2>

      {current && <p className="mt-2 text-emerald-400">{`후원 합계 ${formatWon(current.total)}`}</p>}

      {shownLoadError && (
        <p role="alert" className="mt-3 text-sm text-red-400">
          {shownLoadError}
        </p>
      )}

      {current && current.donations.length === 0 && (
        <p className="mt-3 text-sm text-zinc-400">아직 후원이 없습니다.</p>
      )}
      {current && current.donations.length > 0 && (
        <ul className="mt-3 space-y-3">
          {current.donations.map((d) => (
            <li key={d.id} className="border-b border-zinc-800 pb-3">
              <div className="flex items-baseline gap-2 text-sm">
                <span data-field="nickname" className="font-semibold">
                  {d.donor.nickname}
                </span>
                <span data-field="amount" className="font-semibold text-emerald-400">
                  {formatWon(d.amount)}
                </span>
                <time dateTime={d.confirmedAt} className="text-zinc-400">
                  {formatDateTime(d.confirmedAt)}
                </time>
              </div>
              {d.message && (
                <p data-field="message" className="mt-1 whitespace-pre-wrap break-words text-zinc-200">
                  {d.message}
                </p>
              )}
            </li>
          ))}
        </ul>
      )}

      {current && current.nextBefore !== null && (
        <button
          type="button"
          onClick={loadMore}
          disabled={loadingMore}
          className="mt-4 w-full rounded border border-zinc-700 py-2 text-sm text-zinc-300 hover:bg-zinc-800 disabled:opacity-50"
        >
          더 보기
        </button>
      )}
    </section>
  )
}
