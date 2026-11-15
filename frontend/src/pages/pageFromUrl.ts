/** The URL carries a one-based `?page=`; anything that is not a positive integer means the first page. */
export function pageFromUrl(raw: string | null): number {
  const page = Number(raw)
  return Number.isInteger(page) && page >= 1 ? page : 1
}
