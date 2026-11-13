import { Link, Route, Routes } from 'react-router'
import { RegisterVodPage } from './pages/RegisterVodPage'
import { VodListPage } from './pages/VodListPage'

export function AppRoutes() {
  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100">
      <header className="border-b border-zinc-800">
        <nav className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
          <Link to="/" className="text-lg font-bold text-emerald-400">
            치지직
          </Link>
          <Link
            to="/vods/new"
            className="rounded bg-emerald-500 px-3 py-1.5 text-sm font-semibold text-zinc-950 hover:bg-emerald-400"
          >
            다시보기 등록
          </Link>
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Routes>
          <Route path="/" element={<VodListPage />} />
          <Route path="/vods/new" element={<RegisterVodPage />} />
        </Routes>
      </main>
    </div>
  )
}
