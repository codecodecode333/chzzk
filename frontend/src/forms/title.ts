/**
 * The title rule shared by the VOD form and the 송출 screen, mirroring the server's `common.text.Title`
 * (which stays authoritative): trimmed with JavaScript's `trim()`, then 1–100 characters.
 */

export const TITLE_MAX_CHARS = 100

/** Characters as the server counts them (code points), so an emoji is one char. */
export function charCount(value: string): number {
  return [...value].length
}

/** The error message for an invalid title, or `undefined` when it is valid. */
export function validateTitle(value: string): string | undefined {
  const title = value.trim()
  if (title === '') return '제목을 입력해 주세요.'
  if (charCount(title) > TITLE_MAX_CHARS) return `제목은 ${TITLE_MAX_CHARS}자 이하로 입력해 주세요.`
  return undefined
}
