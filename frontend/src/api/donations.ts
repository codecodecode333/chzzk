import { client } from './client'

/**
 * The data-access module for the 후원 API. Pages call these functions only; extend this file when the API grows.
 * Errors reject as Problem Details; show them with `apiErrorMessage` (and `apiFieldErrors` for `amount`/`message`).
 */

/** A 회원 as the 후원 API shows one. */
export interface DonationMember {
  id: number
  nickname: string
}

/**
 * A 후원 order the server created before payment: the amount and the recipient (the 방송's 소유자) are fixed, and
 * the rest is what the payments module needs to open the Toss 결제창. `successUrl`/`failUrl` are paths on this site.
 */
export interface DonationOrder {
  orderId: string
  orderName: string
  amount: number
  clientKey: string
  successUrl: string
  failUrl: string
  recipient: DonationMember
}

export type DonationStatus = 'PENDING' | 'CONFIRMED' | 'FAILED' | 'EXPIRED'

/** A 후원 as the confirm endpoint returns it. */
export interface Donation {
  id: number
  orderId: string
  broadcastId: number
  amount: number
  message: string | null
  status: DonationStatus
  donor: DonationMember
  recipient: DonationMember
  confirmedAt: string | null
}

/**
 * "후원하기": the logged-in 회원 orders a 후원 to a 방송 중 방송 they don't own. Rejects with 400 (bad amount or
 * message, by field), 401, 403 (own 방송), 404 or 409 (방송 종료).
 */
export async function startDonation(broadcastId: number, amount: number, message: string): Promise<DonationOrder> {
  const { data } = await client.post<DonationOrder>(`/broadcasts/${broadcastId}/donations`, {
    amount,
    message: message.trim() === '' ? null : message,
  })
  return data
}

/** What Toss puts in the successUrl's query after payment. */
export interface PaymentApproval {
  paymentKey: string
  orderId: string
  amount: number
}

/**
 * Asks the server to confirm the payment with Toss; resolves to the CONFIRMED 후원. Safe to repeat for the same
 * order. Rejects with 400 (amount mismatch or provider rejection), 401, 403 (not the 후원자), 404 or 503.
 */
export async function confirmDonation(approval: PaymentApproval): Promise<Donation> {
  const { data } = await client.post<Donation>('/donations/confirm', approval)
  return data
}

/** Tells the server the payment failed or was cancelled (from the failUrl's query); the order becomes FAILED. */
export async function failDonation(orderId: string, code: string, message: string): Promise<void> {
  await client.post(`/donations/${encodeURIComponent(orderId)}/fail`, { code, message })
}
