import { Link, NavLink, Route, Routes } from 'react-router'
import { BroadcastListPage } from './pages/BroadcastListPage'
import { BroadcastStudioPage } from './pages/BroadcastStudioPage'
import { BroadcastWatchPage } from './pages/BroadcastWatchPage'
import { RegisterVodPage } from './pages/RegisterVodPage'
import { VodDetailPage } from './pages/VodDetailPage'
import { VodListPage } from './pages/VodListPage'

function navLinkClass({ isActive }: { isActive: boolean }) {
  return `text-sm ${isActive ? 'font-semibold text-zinc-100' : 'text-zinc-400 hover:text-zinc-200'}`
}

export function AppRoutes() {
  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100">
      <header className="border-b border-zinc-800">
        <nav className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-2 px-4 py-3">
          <div className="flex items-center gap-4">
            <Link to="/" className="text-lg font-bold text-emerald-400">
              치지직
            </Link>
            <NavLink to="/" end className={navLinkClass}>
              라이브
            </NavLink>
            <NavLink to="/vods" end className={navLinkClass}>
              다시보기
            </NavLink>
          </div>
          <div className="flex items-center gap-2">
            <Link
              to="/broadcasts/new"
              className="rounded bg-red-600 px-3 py-1.5 text-sm font-semibold text-white hover:bg-red-500"
            >
              방송 시작
            </Link>
            <Link
              to="/vods/new"
              className="rounded bg-emerald-500 px-3 py-1.5 text-sm font-semibold text-zinc-950 hover:bg-emerald-400"
            >
              다시보기 등록
            </Link>
          </div>
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Routes>
          <Route path="/" element={<BroadcastListPage />} />
          <Route path="/vods" element={<VodListPage />} />
          <Route path="/vods/new" element={<RegisterVodPage />} />
          <Route path="/vods/:id" element={<VodDetailPage />} />
          <Route path="/broadcasts/new" element={<BroadcastStudioPage />} />
          <Route path="/broadcasts/:id" element={<BroadcastWatchPage />} />
        </Routes>
      </main>
    </div>
  )
}
