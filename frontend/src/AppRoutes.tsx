import { Link, NavLink, Route, Routes } from 'react-router'
import { CurrentMemberProvider } from './members/CurrentMember'
import { MemberMenu } from './members/MemberMenu'
import { RequireMember } from './members/RequireMember'
import { BroadcastListPage } from './pages/BroadcastListPage'
import { BroadcastStudioPage } from './pages/BroadcastStudioPage'
import { BroadcastWatchPage } from './pages/BroadcastWatchPage'
import { LoginPage } from './pages/LoginPage'
import { RegisterVodPage } from './pages/RegisterVodPage'
import { SignupPage } from './pages/SignupPage'
import { VodDetailPage } from './pages/VodDetailPage'
import { VodListPage } from './pages/VodListPage'

function navLinkClass({ isActive }: { isActive: boolean }) {
  return `text-sm ${isActive ? 'font-semibold text-zinc-100' : 'text-zinc-400 hover:text-zinc-200'}`
}

/** The whole app below the router: the current 회원 state, the header and every route. */
export function AppRoutes() {
  return (
    <CurrentMemberProvider>
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
              <MemberMenu />
            </div>
          </nav>
        </header>
        <main className="mx-auto max-w-6xl px-4 py-6">
          <Routes>
            <Route path="/" element={<BroadcastListPage />} />
            <Route path="/vods" element={<VodListPage />} />
            <Route
              path="/vods/new"
              element={
                <RequireMember>
                  <RegisterVodPage />
                </RequireMember>
              }
            />
            <Route path="/vods/:id" element={<VodDetailPage />} />
            <Route path="/broadcasts/new" element={<BroadcastStudioPage />} />
            <Route path="/broadcasts/:id" element={<BroadcastWatchPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route path="/signup" element={<SignupPage />} />
          </Routes>
        </main>
      </div>
    </CurrentMemberProvider>
  )
}
