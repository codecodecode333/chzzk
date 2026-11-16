/**
 * The 후원 금액 and 후원 메시지 rules, mirroring the server's (which stays the authority): 1,000원 to 1,000,000원 in
 * steps of 1,000원, and an optional message of up to 100 characters.
 */

export const MIN_AMOUNT = 1_000
export const MAX_AMOUNT = 1_000_000
export const AMOUNT_STEP = 1_000
export const MESSAGE_MAX_CHARS = 100

/** The preset amounts the 후원 modal offers: 1천·5천·1만·5만원. */
export const PRESET_AMOUNTS = [1_000, 5_000, 10_000, 50_000] as const

/** What is wrong with the amount, or `undefined` when it is valid. */
export function amountError(amount: number | null): string | undefined {
  if (amount === null || !Number.isFinite(amount)) return '후원 금액을 입력해 주세요.'
  if (!Number.isInteger(amount) || amount < MIN_AMOUNT || amount > MAX_AMOUNT || amount % AMOUNT_STEP !== 0) {
    return '후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.'
  }
  return undefined
}

/** What is wrong with the message, or `undefined` when it is valid (blank counts as no message). */
export function messageError(message: string): string | undefined {
  if ([...message.trim()].length > MESSAGE_MAX_CHARS) {
    return `후원 메시지는 ${MESSAGE_MAX_CHARS}자 이하로 입력해 주세요.`
  }
  return undefined
}
