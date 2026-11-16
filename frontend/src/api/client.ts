import axios, { isAxiosError } from 'axios'

/**
 * The HTTP client and error shape every feature's data-access module shares.
 *
 * CSRF is handled here, once, for every write: before a POST/PUT/PATCH/DELETE the client takes the token from the
 * `XSRF-TOKEN` cookie (fetching it from `GET /api/auth/csrf` when there is none, e.g. first write or after logout)
 * and sends it as `X-XSRF-TOKEN`. Session cookies go along automatically (same origin), so data-access modules just
 * call `client.post(...)`.
 */
export const client = axios.create({ baseURL: '/api' })

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const SAFE_METHODS = new Set(['get', 'head', 'options'])

let csrfTokenRequest: Promise<string> | null = null

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const cookie = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined
}

async function csrfToken(): Promise<string> {
  const fromCookie = readCookie(CSRF_COOKIE)
  if (fromCookie) return fromCookie
  csrfTokenRequest ??= client
    .get<{ token: string }>('/auth/csrf')
    .then(({ data }) => data.token)
    .finally(() => {
      csrfTokenRequest = null
    })
  return csrfTokenRequest
}

client.interceptors.request.use(async (config) => {
  if (!SAFE_METHODS.has((config.method ?? 'get').toLowerCase())) {
    config.headers.set(CSRF_HEADER, await csrfToken())
  }
  return config
})

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

/** The per-field messages of a failed API call (400/409 `errors`), keyed by field; empty when there are none. */
export function apiFieldErrors(error: unknown): Record<string, string> {
  const fieldErrors: Record<string, string> = {}
  if (isAxiosError<Partial<ProblemDetail>>(error) && Array.isArray(error.response?.data?.errors)) {
    for (const { field, message } of error.response.data.errors) {
      fieldErrors[field] ??= message
    }
  }
  return fieldErrors
}
