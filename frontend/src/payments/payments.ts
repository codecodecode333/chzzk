import { ANONYMOUS, loadTossPayments } from '@tosspayments/tosspayments-sdk'

/**
 * The payments module: the only code that touches the Toss Payments SDK (v2, 결제창 / API 개별 연동, ADR-0004).
 * Pages depend only on these functions, which is what lets page tests mock this whole module.
 *
 * Everything here runs in Toss **test mode** with the client key the server hands out with each 후원 order: no real
 * money moves. The secret key never reaches the browser; the server confirms the payment itself.
 */

/** What the server's 후원 order fixes before payment: who pays what, and where Toss sends the browser back to. */
export interface PaymentOrder {
  clientKey: string
  orderId: string
  orderName: string
  amount: number
  /** Paths on this site (e.g. `/donations/success`), resolved against the page's own origin. */
  successUrl: string
  failUrl: string
}

/** The payment window was closed or refused before redirecting; `code` is Toss's error code. */
export class PaymentError extends Error {
  readonly code: string

  constructor(code: string, message: string) {
    super(message)
    this.name = 'PaymentError'
    this.code = code
  }
}

/**
 * Opens the Toss 결제창 for a card payment of the order. On success (or a failure inside the window) Toss navigates
 * the whole page to `successUrl` / `failUrl` with its query parameters, so this normally never resolves. It rejects
 * with a {@link PaymentError} when the window is closed or cannot open (e.g. `USER_CANCEL`).
 */
export async function requestPayment(order: PaymentOrder): Promise<void> {
  try {
    const tossPayments = await loadTossPayments(order.clientKey)
    // An anonymous buyer: Toss needs no 회원 identity for a one-off card payment, and none is leaked to it.
    const payment = tossPayments.payment({ customerKey: ANONYMOUS })
    await payment.requestPayment({
      method: 'CARD',
      amount: { currency: 'KRW', value: order.amount },
      orderId: order.orderId,
      orderName: order.orderName,
      successUrl: new URL(order.successUrl, window.location.origin).href,
      failUrl: new URL(order.failUrl, window.location.origin).href,
      card: { useEscrow: false, flowMode: 'DEFAULT', useCardPoint: false, useAppCardOnly: false },
    })
  } catch (error) {
    throw toPaymentError(error)
  }
}

function toPaymentError(error: unknown): PaymentError {
  if (error instanceof PaymentError) return error
  const code =
    typeof error === 'object' && error !== null && 'code' in error && typeof error.code === 'string'
      ? error.code
      : 'PAYMENT_WINDOW_FAILED'
  const message =
    error instanceof Error && error.message ? error.message : '결제창을 열지 못했습니다. 잠시 후 다시 시도해 주세요.'
  return new PaymentError(code, message)
}
