import { Link } from 'react-router'

const PAGE_WINDOW = 2

/** One-based `?page=` links under `basePath`; shows the pages within PAGE_WINDOW of the current one. */
export function Pagination({ basePath, current, total }: { basePath: string; current: number; total: number }) {
  const first = Math.max(1, current - PAGE_WINDOW)
  const last = Math.min(total, current + PAGE_WINDOW)
  const pages = Array.from({ length: Math.max(0, last - first + 1) }, (_, i) => first + i)
  const linkClass = 'rounded px-3 py-1.5 text-sm hover:bg-zinc-800'

  return (
    <nav aria-label="페이지" className="mt-6 flex items-center justify-center gap-1">
      {current > 1 && (
        <Link to={`${basePath}?page=${Math.min(current - 1, total)}`} className={linkClass}>
          이전
        </Link>
      )}
      {pages.map((n) => (
        <Link
          key={n}
          to={`${basePath}?page=${n}`}
          aria-current={n === current ? 'page' : undefined}
          className={`${linkClass} ${n === current ? 'bg-emerald-500 font-semibold text-zinc-950 hover:bg-emerald-400' : ''}`}
        >
          {n}
        </Link>
      ))}
      {current < total && (
        <Link to={`${basePath}?page=${current + 1}`} className={linkClass}>
          다음
        </Link>
      )}
    </nav>
  )
}
