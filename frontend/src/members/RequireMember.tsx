import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useCurrentMember } from './currentMemberContext'
import { withNext } from './next'

/**
 * The "requires login" route guard: renders `children` for a 회원 and sends a visitor to the login page, which
 * brings them back here afterwards. Wrap a route's element with it:
 *
 * `<Route path="/vods/new" element={<RequireMember><RegisterVodPage /></RequireMember>} />`
 *
 * The server stays the authority (it answers 401 regardless); this only spares visitors a form they can't submit.
 */
export function RequireMember({ children }: { children: ReactNode }) {
  const { member, loading } = useCurrentMember()
  const location = useLocation()
  if (loading) return null
  if (!member) return <Navigate replace to={withNext('/login', location.pathname + location.search)} />
  return children
}
