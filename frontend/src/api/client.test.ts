import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '../test/server'
import { client } from './client'

/** The shared client's CSRF handling: the token the server set last is the one it sends. */

function setCsrfCookie(value: string | null) {
  document.cookie =
    value === null ? 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/' : `XSRF-TOKEN=${value}; path=/`
}

afterEach(() => setCsrfCookie(null))

describe('CSRF token on writes', () => {
  it('sends the token the server rotated to on login, not the one from before', async () => {
    const sent: Array<string | null> = []
    server.use(
      http.post('/api/anything', ({ request }) => {
        sent.push(request.headers.get('X-XSRF-TOKEN'))
        return new HttpResponse(null, { status: 204 })
      }),
    )

    setCsrfCookie('before-login')
    await client.post('/anything')
    setCsrfCookie('after-login') // what the login response's Set-Cookie does in a browser
    await client.post('/anything')

    expect(sent).toEqual(['before-login', 'after-login'])
  })
})
