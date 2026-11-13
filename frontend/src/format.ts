const dateFormat = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeZone: 'Asia/Seoul' })

/** 등록일 as shown on screen, e.g. "2026. 11. 13." (KST). */
export function formatDate(iso: string): string {
  return dateFormat.format(new Date(iso))
}
