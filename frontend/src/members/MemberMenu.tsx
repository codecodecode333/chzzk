import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useCurrentMember } from './currentMemberContext'
import { withNext } from './next'

const linkClass = 'rounded px-3 py-1.5 text-sm text-zinc-300 hover:bg-zinc-800 hover:text-zinc-100'

/** The header's 회원 area: 닉네임 and 로그아웃 while logged in, 로그인 and 회원가입 (back to this page) otherwise. */
export function MemberMenu() {
  const { member, loading, logOut } = useCurrentMember()
  const location = useLocation()
  const [loggingOut, setLoggingOut] = useState(false)

  if (loading) return null
  if (!member) {
    const here = location.pathname + location.search
    return (
      <>
        <Link to={withNext('/login', here)} className={linkClass}>
          로그인
        </Link>
        <Link to={withNext('/signup', here)} className={linkClass}>
          회원가입
        </Link>
      </>
    )
  }

  async function handleLogOut() {
    setLoggingOut(true)
    try {
      await logOut()
    } finally {
      setLoggingOut(false)
    }
  }

  return (
    <>
      <span className="text-sm font-semibold text-zinc-200">{member.nickname}</span>
      <button type="button" onClick={handleLogOut} disabled={loggingOut} className={linkClass}>
        로그아웃
      </button>
    </>
  )
}
