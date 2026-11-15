const dateFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeZone: 'Asia/Seoul' })

/** 등록일 as shown on screen, e.g. "2026. 11. 13." (KST). */
export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}

const dateTimeFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Seoul' })

/** A moment as shown on screen, e.g. "2026. 11. 15. 오후 7:42" (KST). */
export function formatDateTime(iso: string): string {
  return dateTimeFormat.format(new Date(iso))
}
