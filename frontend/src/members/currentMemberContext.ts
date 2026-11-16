import { createContext, useContext } from 'react'
import type { Member } from '../api/auth'

/**
 * The current 회원, for the whole app: loaded from `/api/me` once at start (by `CurrentMemberProvider`, which
 * `AppRoutes` renders), and refreshed after signup, login and logout. Read it with {@link useCurrentMember}; never
 * call `/api/me` from a page.
 */
export interface CurrentMemberState {
  /** The logged-in 회원; `null` for a visitor or while {@link loading}. */
  member: Member | null
  /** `true` until the first `/api/me` answer, so pages don't flash the visitor view (or redirect) too early. */
  loading: boolean
  /** Re-reads `/api/me`; call it after anything that logs in. */
  refresh: () => Promise<void>
  /** Logs out on the server, then becomes a visitor. */
  logOut: () => Promise<void>
}

export const CurrentMemberContext = createContext<CurrentMemberState | null>(null)

/** The current 회원 state; must be used under `CurrentMemberProvider`. */
export function useCurrentMember(): CurrentMemberState {
  const state = useContext(CurrentMemberContext)
  if (!state) throw new Error('useCurrentMember must be used inside <CurrentMemberProvider>')
  return state
}
