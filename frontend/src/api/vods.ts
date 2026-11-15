import { isAxiosError } from 'axios'
import { client, type Page } from './client'

export { apiErrorMessage, type Page, type ProblemDetail } from './client'

/**
 * The data-access module for the VOD API. Pages call these functions only;
 * extend this file (not the pages) when the API grows.
 */

export interface VodSummary {
  id: number
  title: string
  createdAt: string
}

export interface Vod {
  id: number
  title: string
  description: string | null
  createdAt: string
  streamUrl: string
}

export interface RegisterVodInput {
  title: string
  description: string
  file: File
}

/** @param page zero-based page index, as the API counts it */
export async function listVods(page = 0): Promise<Page<VodSummary>> {
  const { data } = await client.get<Page<VodSummary>>('/vods', { params: { page } })
  return data
}

export interface RegisterVodOptions {
  /** Called as the 영상 파일 is sent, with the whole-number percentage (0-100) uploaded so far. */
  onUploadProgress?: (percent: number) => void
}

export async function registerVod(input: RegisterVodInput, options: RegisterVodOptions = {}): Promise<Vod> {
  const form = new FormData()
  form.append('title', input.title)
  if (input.description) {
    form.append('description', input.description)
  }
  form.append('file', input.file)
  const { onUploadProgress } = options
  const { data } = await client.post<Vod>('/vods', form, {
    onUploadProgress: onUploadProgress
      ? ({ loaded, total }) => {
          if (total) onUploadProgress(Math.min(100, Math.round((loaded / total) * 100)))
        }
      : undefined,
  })
  return data
}

/** A VOD id as the server stores it: a positive 64-bit integer. */
const VOD_ID = /^[1-9]\d{0,18}$/
const MAX_VOD_ID = 2n ** 63n - 1n

/**
 * Resolves to `null` when there is no such VOD: an id that cannot be a VOD id, or a server 404.
 * Any other failure rejects; show it with {@link apiErrorMessage}.
 */
export async function getVod(id: string): Promise<Vod | null> {
  if (!VOD_ID.test(id) || BigInt(id) > MAX_VOD_ID) return null
  try {
    const { data } = await client.get<Vod>(`/vods/${id}`)
    return data
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 404) return null
    throw error
  }
}
