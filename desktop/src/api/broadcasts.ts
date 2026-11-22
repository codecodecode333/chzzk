/**
 * The 방송 read API of the configured backend, in the web frontend's shapes (frontend/src/api/broadcasts.ts).
 * The app is never same-origin with the backend: every call goes to `<backendUrl>/api/...` as a CORS `GET`
 * without credentials (the backend allows only the app's own origin, `app://chzzk`).
 */

export type BroadcastStatus = 'LIVE' | 'ENDED'

export interface BroadcastOwner {
  id: number
  nickname: string
}

/** A card in the 방송 목록. */
export interface BroadcastSummary {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  endedAt: string | null
  owner: BroadcastOwner
}

export interface Broadcast extends BroadcastSummary {
  /** Present only while the 방송 is 방송 중; relative to the backend (`/live/.../whep`). */
  whepUrl: string | null
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

/** The backend could not be reached, or answered with something other than what the app expects. */
export class BackendUnavailableError extends Error {
  constructor(cause?: unknown) {
    super('backend unavailable', { cause })
    this.name = 'BackendUnavailableError'
  }
}

async function getJson<T>(url: URL): Promise<T | null> {
  let response: Response
  try {
    response = await fetch(url, { credentials: 'omit' })
  } catch (error) {
    throw new BackendUnavailableError(error)
  }
  if (response.status === 404) return null
  if (!response.ok) throw new BackendUnavailableError(response.status)
  try {
    return (await response.json()) as T
  } catch (error) {
    throw new BackendUnavailableError(error)
  }
}

/** The 방송 of one status: 방송 중 newest started first, 종료 most recently ended first. */
export async function listBroadcasts(backendUrl: string, status: BroadcastStatus, size: number) {
  const url = new URL('/api/broadcasts', backendUrl)
  url.searchParams.set('status', status)
  url.searchParams.set('page', '0')
  url.searchParams.set('size', String(size))
  const page = await getJson<Page<BroadcastSummary>>(url)
  if (!page) throw new BackendUnavailableError(404)
  return page
}

/** One 방송, or `null` when the backend has no such 방송. */
export function getBroadcast(backendUrl: string, id: number): Promise<Broadcast | null> {
  return getJson<Broadcast>(new URL(`/api/broadcasts/${id}`, backendUrl))
}
