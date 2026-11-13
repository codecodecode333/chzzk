import axios, { isAxiosError } from 'axios'

/**
 * The single data-access module for the VOD API. Pages call these functions only;
 * extend this file (not the pages) when the API grows.
 */

const client = axios.create({ baseURL: '/api' })

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

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface RegisterVodInput {
  title: string
  description: string
  file: File
}

export async function listVods(): Promise<Page<VodSummary>> {
  const { data } = await client.get<Page<VodSummary>>('/vods')
  return data
}

export async function registerVod(input: RegisterVodInput): Promise<Vod> {
  const form = new FormData()
  form.append('title', input.title)
  if (input.description) {
    form.append('description', input.description)
  }
  form.append('file', input.file)
  const { data } = await client.post<Vod>('/vods', form)
  return data
}

/** The server's RFC 7807 error body (one shape for every API error). */
export interface ProblemDetail {
  type: string
  title: string
  status: number
  detail?: string
  instance?: string
  errors?: Array<{ field: string; message: string }>
}

const FALLBACK_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** A user-facing message for a failed API call: the Problem `detail` when the server sent one. */
export function apiErrorMessage(error: unknown): string {
  if (isAxiosError<Partial<ProblemDetail>>(error) && typeof error.response?.data?.detail === 'string') {
    return error.response.data.detail
  }
  return FALLBACK_ERROR_MESSAGE
}
