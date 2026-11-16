import { useEffect, useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { confirmDonation, failDonation, type Donation, type PaymentApproval } from '../api/donations'

/**
 * Where Toss sends the browser back to after the 결제창 (ADR-0004). The success page never trusts the redirect: it
 * asks the server to confirm the payment with Toss and shows "후원 완료" only once the server answers with the
 * CONFIRMED 후원. Confirming is idempotent on the server, so refreshing the page is safe.
 */

/** One confirm request per order per page load, so a double-run effect (React StrictMode) doesn't send two. */
const confirmations = new Map<string, Promise<Donation>>()

function confirmOnce(approval: PaymentApproval): Promise<Donation> {
  const key = `${approval.orderId}:${approval.paymentKey}:${approval.amount}`
  let confirmation = confirmations.get(key)
  if (!confirmation) {
    confirmation = confirmDonation(approval)
    confirmations.set(key, confirmation)
    // Once settled, a later visit (e.g. after a failure) asks the server again.
    confirmation.then(
      () => confirmations.delete(key),
      () => confirmations.delete(key),
    )
  }
  return confirmation
}

/** The successUrl's query, or `null` when something is missing or malformed. */
function approvalFrom(params: URLSearchParams): PaymentApproval | null {
  const paymentKey = params.get('paymentKey')
  const orderId = params.get('orderId')
  const amount = Number(params.get('amount'))
  if (!paymentKey || !orderId || !Number.isSafeInteger(amount) || amount <= 0) return null
  return { paymentKey, orderId, amount }
}

type Confirmation =
  | { state: 'confirming' }
  | { state: 'confirmed'; donation: Donation }
  | { state: 'failed'; message: string }

const MISSING_PAYMENT = '결제 정보가 올바르지 않습니다. 후원을 다시 시도해 주세요.'

/** `/donations/success`: "확인 중…" while the server confirms, then "후원 완료" or the failure. */
export function DonationSuccessPage() {
  const [params] = useSearchParams()
  const [confirmation, setConfirmation] = useState<Confirmation>(() =>
    approvalFrom(params) ? { state: 'confirming' } : { state: 'failed', message: MISSING_PAYMENT },
  )

  useEffect(() => {
    const approval = approvalFrom(params)
    if (!approval) return
    let active = true
    confirmOnce(approval).then(
      (donation) => active && setConfirmation({ state: 'confirmed', donation }),
      (error: unknown) => active && setConfirmation({ state: 'failed', message: apiErrorMessage(error) }),
    )
    return () => {
      active = false
    }
  }, [params])

  if (confirmation.state === 'confirming') {
    return (
      <ResultLayout title="후원 확인">
        <p role="status" className="text-zinc-300">
          확인 중…
        </p>
      </ResultLayout>
    )
  }
  if (confirmation.state === 'failed') {
    return <FailedResult message={confirmation.message} broadcastId={null} />
  }
  const { donation } = confirmation
  return (
    <ResultLayout title="후원 완료">
      <p className="text-zinc-300">
        <span className="font-semibold text-zinc-100">{donation.recipient.nickname}</span>님에게{' '}
        {donation.amount.toLocaleString('ko-KR')}원을 후원했습니다.
      </p>
      <p className="mt-1 text-sm text-zinc-400">테스트 결제라 실제 돈은 나가지 않았습니다.</p>
      <Link
        to={`/broadcasts/${donation.broadcastId}`}
        className="mt-6 inline-block rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400"
      >
        방송으로 돌아가기
      </Link>
    </ResultLayout>
  )
}

/**
 * `/donations/fail`: shows Toss's reason with "다시 시도", and tells the server the order failed. Toss sends no
 * `orderId` when the 후원자 cancelled; the `broadcastId` (and `orderId`) the server put in the failUrl come back too.
 */
export function DonationFailPage() {
  const [params] = useSearchParams()
  const orderId = params.get('orderId')
  const code = params.get('code') ?? 'UNKNOWN'
  const message = params.get('message') || '결제가 완료되지 않았습니다.'
  const broadcastId = params.get('broadcastId')

  useEffect(() => {
    if (orderId) failDonation(orderId, code, message).catch(() => {}) // best effort; expiry cleans up otherwise
  }, [orderId, code, message])

  return <FailedResult message={message} broadcastId={broadcastId && /^\d+$/.test(broadcastId) ? broadcastId : null} />
}

function FailedResult({ message, broadcastId }: { message: string; broadcastId: string | null }) {
  return (
    <ResultLayout title="후원하지 못했습니다">
      <p role="alert" className="rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300">
        {message}
      </p>
      <Link
        to={broadcastId ? `/broadcasts/${broadcastId}` : '/'}
        className="mt-6 inline-block rounded bg-amber-400 px-4 py-2 font-semibold text-zinc-950 hover:bg-amber-300"
      >
        다시 시도
      </Link>
    </ResultLayout>
  )
}

function ResultLayout({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mx-auto max-w-md py-16 text-center">
      <h1 className="mb-4 text-2xl font-bold">{title}</h1>
      {children}
    </section>
  )
}
