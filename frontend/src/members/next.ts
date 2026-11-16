/**
 * Where to go after logging in: the `?next=` the login and signup pages receive. Only in-app paths are honoured, so
 * a crafted link can't send a 회원 to another site right after they typed their password.
 */

/** `next` when it is an in-app path (`/...`, not `//host` or `/\host`), otherwise the home page. */
export function safeNext(next: string | null): string {
  if (!next || !next.startsWith('/') || next.startsWith('//')) return '/'
  // Browsers treat `\` like `/` and drop tabs and newlines in URLs, so `/\host` or `/\t/host` could still leave.
  const leavesTheApp = [...next].some((c) => c === '\\' || c.charCodeAt(0) < 0x20 || c.charCodeAt(0) === 0x7f)
  return leavesTheApp ? '/' : next
}

/** A link to `page` (`/login` or `/signup`) that comes back to `returnTo` (a path with its query) afterwards. */
export function withNext(page: '/login' | '/signup', returnTo: string): string {
  return `${page}?next=${encodeURIComponent(returnTo)}`
}
