import { isAxiosError } from 'axios'
import { client, type Page } from './client'

/**
 * The data-access module for the 방송 API. Pages call these functions only; extend this file when the API grows.
 */

export type BroadcastStatus = 'LIVE' | 'ENDED'

export interface Broadcast {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  endedAt: string | null
  /** Present only while the 방송 is 방송 중. */
  whepUrl: string | null
}

/** The start response: the only place the 송출 키 ever appears. */
export interface StartedBroadcast {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  publishKey: string
  whipUrl: string
}

/** "방송 시작". Rejects with a Problem Details error (e.g. an invalid title); show it with `apiErrorMessage`. */
export async function startBroadcast(title: string): Promise<StartedBroadcast> {
  const { data } = await client.post<StartedBroadcast>('/broadcasts', { title })
  return data
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

/** A card in the 방송 목록. */
export interface BroadcastSummary {
  id: number
  title: string
  status: BroadcastStatus
  startedAt: string
  endedAt: string | null
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
