import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { getMe, logOut as logOutApi, type Member } from '../api/auth'
import { CurrentMemberContext } from './currentMemberContext'

/** Who is "me" (`/api/me`), unknown (`null`) when the server can't say: then the app acts as a visitor. */
async function loadMe(): Promise<Member | null> {
  try {
    return await getMe()
  } catch {
    // Its writes will answer 401 if that was wrong.
    return null
  }
}

/** Provides the current 회원 state (see `currentMemberContext.ts`) to everything below it. */
export function CurrentMemberProvider({ children }: { children: ReactNode }) {
  const [member, setMember] = useState<Member | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setMember(await loadMe())
    setLoading(false)
  }, [])

  const logOut = useCallback(async () => {
    await logOutApi()
    setMember(null)
  }, [])

  useEffect(() => {
    let active = true
    loadMe().then((found) => {
      if (!active) return
      setMember(found)
      setLoading(false)
    })
    return () => {
      active = false
    }
  }, [])

  const value = useMemo(() => ({ member, loading, refresh, logOut }), [member, loading, refresh, logOut])
  return <CurrentMemberContext.Provider value={value}>{children}</CurrentMemberContext.Provider>
}
