import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Member } from '../api/auth'

/** The CSRF token `GET /api/auth/csrf` hands out in tests; writes must echo it as `X-XSRF-TOKEN`. */
export const CSRF_TOKEN = 'test-csrf-token'

const visitorMe = () =>
  HttpResponse.json(
    { type: 'about:blank', title: 'Unauthorized', status: 401, detail: '로그인이 필요합니다.', instance: '/api/me' },
    { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
  )

/**
 * Network boundary for page-component tests; each test registers its own handlers with server.use().
 * By default everyone is a visitor (`/api/me` → 401), the CSRF token is available and every VOD or 방송 has no 댓글
 * (so pages embedding the 댓글 section need no handler for it); see {@link loggedInAs}.
 */
export const server = setupServer(
  http.get('/api/me', visitorMe),
  http.get('/api/vods/:id/comments', () => HttpResponse.json({ content: [], nextBefore: null })),
  http.get('/api/broadcasts/:id/comments', () => HttpResponse.json({ content: [], nextBefore: null })),
  http.get('/api/auth/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', token: CSRF_TOKEN })),
)

/**
 * Makes `/api/me` answer as `member` (or as a visitor for `null`) for the rest of this test. The returned controls
 * switch it later, e.g. from a login or logout handler, so the app's refresh sees the new state.
 */
export function loggedInAs(member: Member | null) {
  let current = member
  server.use(http.get('/api/me', () => (current ? HttpResponse.json(current) : visitorMe())))
  return {
    logIn(next: Member) {
      current = next
    },
    logOut() {
      current = null
    },
  }
}
