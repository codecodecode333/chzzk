const dateFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeZone: 'Asia/Seoul' })

/** 등록일 as shown on screen, e.g. "2026. 11. 13." (KST). */
export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}

const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  hourCycle: 'h23',
  timeZone: 'Asia/Seoul',
})

/** A moment as shown on screen, e.g. "2026. 11. 15. 19:00" (KST). */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}

/** An amount of money as shown on screen, e.g. "5,000원". */
export function formatWon(amount: number): string {
  return `${amount.toLocaleString('ko-KR')}원`
}
