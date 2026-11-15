import { useEffect, useState } from 'react'

/** Time on air since `since` (ISO), as `mm:ss` or `h:mm:ss`, ticking every second. */
export function ElapsedTimer({ since }: { since: string }) {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])

  // Clamped: the start time is the server's clock, which may run slightly ahead of this device's.
  const seconds = Math.max(0, Math.floor((now - Date.parse(since)) / 1000))
  return (
    <span role="timer" className="rounded bg-black/60 px-2 py-0.5 font-mono text-xs text-white tabular-nums">
      {formatElapsed(seconds)}
    </span>
  )
}

function formatElapsed(total: number): string {
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const mmss = `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return h ? `${h}:${mmss}` : mmss
}
