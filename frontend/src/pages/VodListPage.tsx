import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { listVods, type VodSummary } from '../api/vods'
import { formatDate } from '../format'

export function VodListPage() {
  const [vods, setVods] = useState<VodSummary[]>([])

  useEffect(() => {
    let active = true
    listVods().then((page) => {
      if (active) setVods(page.content)
    })
    return () => {
      active = false
    }
  }, [])

  return (
    <section>
      <h1 className="mb-4 text-2xl font-bold">다시보기</h1>
      <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {vods.map((vod) => (
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
    </section>
  )
}
