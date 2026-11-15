import axios, { isAxiosError } from 'axios'

/** The HTTP client and error shape every feature's data-access module shares. */
export const client = axios.create({ baseURL: '/api' })

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
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

// The server's message is the source of truth whenever a Problem body arrives; this copy of its generic 500 text
// is deliberate, for failures that never reach the server (network down, connection reset, non-Problem body).
const FALLBACK_ERROR_MESSAGE = '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.'

/** A user-facing message for a failed API call: the Problem `detail` when the server sent one. */
export function apiErrorMessage(error: unknown): string {
  if (isAxiosError<Partial<ProblemDetail>>(error) && typeof error.response?.data?.detail === 'string') {
    return error.response.data.detail
  }
  return FALLBACK_ERROR_MESSAGE
}
