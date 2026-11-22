import { useState } from 'react'
import { BroadcastList } from './screens/BroadcastList'
import { BroadcastWatch } from './screens/BroadcastWatch'

/** The 데스크톱 앱: the 방송 목록, and the 시청 screen of one 방송 중 방송. */
export function App({ backendUrl }: { backendUrl: string }) {
  const [watching, setWatching] = useState<number | null>(null)
  return (
    <main className="mx-auto max-w-5xl p-6">
      {watching === null ? (
        <BroadcastList backendUrl={backendUrl} onWatch={setWatching} />
      ) : (
        <BroadcastWatch backendUrl={backendUrl} id={watching} onBack={() => setWatching(null)} />
      )}
    </main>
  )
}
