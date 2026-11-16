import { useId, useState, type FormEvent } from 'react'
import type { Broadcast } from '../api/broadcasts'
import { apiErrorMessage, apiFieldErrors } from '../api/client'
import { failDonation, startDonation } from '../api/donations'
import { Field, FormError, inputClass } from '../forms/Field'
import { PaymentError, requestPayment } from '../payments/payments'
import { amountError, MESSAGE_MAX_CHARS, messageError, PRESET_AMOUNTS } from './validateDonation'

function won(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}

const PRESET_LABELS: Record<(typeof PRESET_AMOUNTS)[number], string> = {
  1_000: '1천원',
  5_000: '5천원',
  10_000: '1만원',
  50_000: '5만원',
}

/**
 * The 후원 form: a preset or custom 후원 금액 and an optional 후원 메시지, validated like the server does. On submit
 * the server creates the 후원 order (fixing the amount and the recipient), then the payments module opens the Toss
 * 결제창 with the server's values. Toss then navigates the page to `/donations/success` or `/donations/fail`.
 */
export function DonationModal({ broadcast, onClose }: { broadcast: Broadcast; onClose: () => void }) {
  const titleId = useId()
  const [amountText, setAmountText] = useState('5000')
  const [message, setMessage] = useState('')
  const [errors, setErrors] = useState<Record<string, string | undefined>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const amount = amountText.trim() === '' ? null : Number(amountText)

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (submitting) return
    const found = { amount: amountError(amount), message: messageError(message) }
    setErrors(found)
    setFormError(null)
    if (found.amount || found.message || amount === null) return

    setSubmitting(true)
    let orderId: string | null = null
    try {
      const order = await startDonation(broadcast.id, amount, message)
      orderId = order.orderId
      await requestPayment(order)
      // Toss normally navigates away; resolving here means the window closed without a redirect.
      setSubmitting(false)
    } catch (failure) {
      setSubmitting(false)
      if (failure instanceof PaymentError) {
        if (orderId) failDonation(orderId, failure.code, failure.message).catch(() => {})
        setFormError(failure.code === 'USER_CANCEL' ? '결제를 취소했습니다.' : failure.message)
        return
      }
      const fieldErrors = apiFieldErrors(failure)
      setErrors({ amount: fieldErrors.amount, message: fieldErrors.message })
      if (!fieldErrors.amount && !fieldErrors.message) setFormError(apiErrorMessage(failure))
    }
  }

  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-black/70 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-lg border border-zinc-700 bg-zinc-900 p-5"
      >
        <h2 id={titleId} className="text-lg font-bold">
          후원하기
        </h2>
        <p className="mt-1 text-sm text-zinc-300">
          <span className="font-semibold text-zinc-100">{broadcast.owner.nickname}</span>님에게 후원 ·{' '}
          {broadcast.title}
        </p>
        <p className="mt-3 rounded border border-amber-400/40 bg-amber-400/10 px-3 py-2 text-sm text-amber-200">
          테스트 결제 — 실제 돈은 나가지 않습니다
        </p>
        <form noValidate onSubmit={handleSubmit} className="mt-4 space-y-4">
          <FormError message={formError} />
          <div className="flex flex-wrap gap-2" role="group" aria-label="후원 금액 선택">
            {PRESET_AMOUNTS.map((preset) => (
              <button
                key={preset}
                type="button"
                aria-pressed={amount === preset}
                onClick={() => setAmountText(String(preset))}
                className={`rounded border px-3 py-1.5 text-sm ${
                  amount === preset ? 'border-amber-400 bg-amber-400/20' : 'border-zinc-700 hover:bg-zinc-800'
                }`}
              >
                {PRESET_LABELS[preset]}
              </button>
            ))}
          </div>
          <Field id="donation-amount" label="후원 금액 (원)" error={errors.amount}>
            {(a11y) => (
              <input
                {...a11y}
                type="number"
                inputMode="numeric"
                min={1000}
                max={1000000}
                step={1000}
                value={amountText}
                onChange={(e) => setAmountText(e.target.value)}
                className={inputClass}
              />
            )}
          </Field>
          <Field id="donation-message" label={`후원 메시지 (선택, ${MESSAGE_MAX_CHARS}자 이하)`} error={errors.message}>
            {(a11y) => (
              <textarea
                {...a11y}
                rows={2}
                value={message}
                onChange={(e) => setMessage(e.target.value)}
                className={inputClass}
              />
            )}
          </Field>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onClose}
              className="rounded border border-zinc-700 px-3 py-1.5 text-sm hover:bg-zinc-800"
            >
              닫기
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="rounded bg-amber-400 px-3 py-1.5 text-sm font-semibold text-zinc-950 hover:bg-amber-300 disabled:opacity-60"
            >
              {amount !== null && !amountError(amount) ? `${won(amount)} 결제하기` : '결제하기'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
