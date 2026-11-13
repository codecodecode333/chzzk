import { useEffect, useState } from 'react'
import { listVods, type VodSummary } from '../api/vods'

const dateFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeZone: 'Asia/Seoul' })

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
            <article className="overflow-hidden rounded-lg bg-zinc-900">
              <div aria-hidden="true" className="aspect-video bg-zinc-800" />
              <div className="p-3">
                <h2 className="truncate font-semibold">{vod.title}</h2>
                <time dateTime={vod.createdAt} className="text-sm text-zinc-400">
                  {dateFormat.format(new Date(vod.createdAt))}
                </time>
              </div>
            </article>
          </li>
        ))}
      </ul>
    </section>
  )
}
