import { useState } from 'react'
import { Link } from 'react-router'
import type { Broadcast } from '../api/broadcasts'
import { useCurrentMember } from '../members/currentMemberContext'
import { withNext } from '../members/next'
import { DonationModal } from './DonationModal'

/**
 * The 후원 entry point on the 시청 screen: "후원하기" (opening the {@link DonationModal}) for a logged-in 회원 on
 * someone else's 방송 중 방송, "로그인하고 후원하기" for a visitor, and nothing for the 소유자 or on a 종료 방송.
 * The server enforces the same rules.
 */
export function DonateButton({ broadcast }: { broadcast: Broadcast }) {
  const { member, loading } = useCurrentMember()
  const [open, setOpen] = useState(false)
  if (loading || broadcast.status !== 'LIVE') return null

  if (member === null) {
    return (
      <Link
        to={withNext('/login', `/broadcasts/${broadcast.id}`)}
        className="mt-3 inline-block rounded border border-amber-400/60 px-3 py-1.5 text-sm font-semibold text-amber-300 hover:bg-amber-400/10"
      >
        로그인하고 후원하기
      </Link>
    )
  }
  if (member.id === broadcast.owner.id) return null

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="mt-3 rounded bg-amber-400 px-3 py-1.5 text-sm font-semibold text-zinc-950 hover:bg-amber-300"
      >
        후원하기
      </button>
      {open && <DonationModal broadcast={broadcast} onClose={() => setOpen(false)} />}
    </>
  )
}
