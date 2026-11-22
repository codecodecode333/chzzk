/** The 방송 중 status badge. */
export function LiveBadge() {
  return <span className="rounded bg-red-600 px-2 py-0.5 text-xs font-bold text-white">방송 중</span>
}

/** The 종료 status badge. */
export function EndedBadge() {
  return <span className="rounded bg-zinc-700 px-2 py-0.5 text-xs font-bold text-zinc-200">종료</span>
}
