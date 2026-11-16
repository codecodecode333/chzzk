import { isAxiosError } from 'axios'
import { client, type Page, type ProblemDetail } from './client'

/**
 * The data-access module for the 방송 API. Pages call these functions only; extend this file when the API grows.
 */

export type BroadcastStatus = 'LIVE' | 'ENDED'

/** The 회원 who started the 방송: its 소유자 and 송출자. */
export interface BroadcastOwner {
  id: number
  nickname: string
}

export interface Broadcast {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  endedAt: string | null
  owner: BroadcastOwner
  /** Present only while the 방송 is 방송 중. */
  whepUrl: string | null
}

/** Where and with which 송출 키 to publish. The key is handed out only by 방송 시작 and 이어서 송출, never stored. */
export interface PublishTarget {
  publishKey: string
  whipUrl: string
}

/** The start response: with {@link issuePublishKey}'s, the only place a 송출 키 ever appears. */
export interface StartedBroadcast extends PublishTarget {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  owner: BroadcastOwner
}

/**
 * "방송 시작" as the logged-in 회원, who becomes its 소유자. Rejects with a Problem Details error (an invalid title,
 * 401, or a 409 when the 회원 already has a 방송 중 방송: see {@link liveBroadcastConflict}); show it with
 * `apiErrorMessage`.
 */
export async function startBroadcast(title: string): Promise<StartedBroadcast> {
  const { data } = await client.post<StartedBroadcast>('/broadcasts', { title })
  return data
}

/** The id of the 회원's 방송 중 방송 when {@link startBroadcast} was refused because of it, otherwise `null`. */
export function liveBroadcastConflict(error: unknown): number | null {
  if (!isAxiosError<Partial<ProblemDetail> & { broadcastId?: unknown }>(error)) return null
  const { status, data } = error.response ?? {}
  return status === 409 && typeof data?.broadcastId === 'number' ? data.broadcastId : null
}

/**
 * "이어서 송출": a fresh 송출 키 for the 소유자's 방송 중 방송. The previous key stops working, and whoever still
 * publishes with it is cut off. Rejects with a Problem Details error (401, 403, 404, or 409 once it has ended).
 */
export async function issuePublishKey(id: number): Promise<PublishTarget> {
  const { data } = await client.post<PublishTarget>(`/broadcasts/${id}/publish-key`)
  return data
}

/** The 회원's 방송 중 방송 (a 회원 has at most one), or `null`. */
export async function findLiveBroadcastOf(ownerId: number): Promise<BroadcastSummary | null> {
  const { data } = await client.get<Page<BroadcastSummary>>('/broadcasts', {
    params: { status: 'LIVE', ownerId, size: 1 },
  })
  return data.content[0] ?? null
}

/** A 방송 id as the server stores it: a positive 64-bit integer. */
const BROADCAST_ID = /^[1-9]\d{0,18}$/
const MAX_BROADCAST_ID = 2n ** 63n - 1n

/**
 * Resolves to `null` when there is no such 방송: an id that cannot be a 방송 id, or a server 404.
 * Any other failure rejects; show it with `apiErrorMessage`.
 */
export async function getBroadcast(id: string): Promise<Broadcast | null> {
  if (!BROADCAST_ID.test(id) || BigInt(id) > MAX_BROADCAST_ID) return null
  try {
    const { data } = await client.get<Broadcast>(`/broadcasts/${id}`)
    return data
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 404) return null
    throw error
  }
}

/**
 * "방송 종료" by the 소유자's login session. Ending an already-ended 방송 succeeds. Rejects with a Problem Details error
 * (401 not logged in, e.g. the session expired; 403 not the 소유자; 404 unknown 방송); show it with `apiErrorMessage`.
 */
export async function endBroadcast(id: number): Promise<void> {
  await client.post(`/broadcasts/${id}/end`)
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

/**
 * The 방송 목록 for one status: 방송 중 newest started first, 종료 most recently ended first.
 * @param page zero-based page index, as the API counts it
 * @param size page size; the server's default (20) when omitted, capped at 100
 */
export async function listBroadcasts(
  status: BroadcastStatus,
  page = 0,
  size?: number,
): Promise<Page<BroadcastSummary>> {
  const { data } = await client.get<Page<BroadcastSummary>>('/broadcasts', { params: { status, page, size } })
  return data
}
