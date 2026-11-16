import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { CurrentMemberProvider } from '../members/CurrentMember'
import { RequireMember } from '../members/RequireMember'
import { CSRF_TOKEN, loggedInAs, server } from '../test/server'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

function header() {
  return within(screen.getByRole('banner'))
}

function problem(status: number, detail: string, errors?: Array<{ field: string; message: string }>) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail, instance: '/api', ...(errors ? { errors } : {}) },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Shows where the app ended up, for redirect assertions. */
function LocationProbe() {
  const location = useLocation()
  return <output aria-label="현재 위치">{location.pathname + location.search}</output>
}

describe('header', () => {
  it('offers 로그인 and 회원가입 to a visitor, returning to the current page', async () => {
    server.use(
      http.get('/api/vods', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    )

    renderAt('/vods?page=2')

    expect(await header().findByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      `/login?next=${encodeURIComponent('/vods?page=2')}`,
    )
    expect(header().getByRole('link', { name: '회원가입' })).toHaveAttribute(
      'href',
      `/signup?next=${encodeURIComponent('/vods?page=2')}`,
    )
    expect(header().queryByRole('button', { name: '로그아웃' })).not.toBeInTheDocument()
  })

  it('shows the 회원 닉네임 and 로그아웃 while logged in', async () => {
    loggedInAs({ id: 1, nickname: '치지직회원' })
    server.use(
      http.get('/api/vods', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    )

    renderAt('/vods')

    expect(await header().findByText('치지직회원')).toBeInTheDocument()
    expect(header().getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(header().queryByRole('link', { name: '로그인' })).not.toBeInTheDocument()
    expect(header().queryByRole('link', { name: '회원가입' })).not.toBeInTheDocument()
  })

  it('logs out with the CSRF token and switches back to the visitor header', async () => {
    const user = userEvent.setup({ delay: null })
    const session = loggedInAs({ id: 1, nickname: '치지직회원' })
    let csrfHeader: string | null = null
    server.use(
      http.get('/api/vods', () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
      http.post('/api/auth/logout', ({ request }) => {
        csrfHeader = request.headers.get('X-XSRF-TOKEN')
        session.logOut()
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderAt('/vods')

    await user.click(await header().findByRole('button', { name: '로그아웃' }))

    expect(await header().findByRole('link', { name: '로그인' })).toBeInTheDocument()
    expect(header().queryByText('치지직회원')).not.toBeInTheDocument()
    expect(csrfHeader).toBe(CSRF_TOKEN)
  })
})

describe('회원가입 page', () => {
  it('shows each field rule once the field is edited and blocks submit until all pass', async () => {
    const user = userEvent.setup({ delay: null })
    renderAt('/signup')

    const submit = await screen.findByRole('button', { name: '가입하기' })
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText('이메일'), 'not-an-email')
    await user.type(screen.getByLabelText('비밀번호'), 'short')
    await user.type(screen.getByLabelText('닉네임'), ' 김 ')

    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription('이메일 형식이 올바르지 않습니다.')
    expect(screen.getByLabelText('비밀번호')).toHaveAccessibleDescription(
      '비밀번호는 8자 이상 64자 이하로 입력해 주세요.',
    )
    expect(screen.getByLabelText('닉네임')).toHaveAccessibleDescription('닉네임은 2자 이상 20자 이하로 입력해 주세요.')
    expect(submit).toBeDisabled()

    await user.clear(screen.getByLabelText('이메일'))
    await user.type(screen.getByLabelText('이메일'), 'me@example.com')
    await user.type(screen.getByLabelText('비밀번호'), '1234')
    await user.clear(screen.getByLabelText('닉네임'))
    await user.type(screen.getByLabelText('닉네임'), '새회원')

    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('비밀번호')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText('닉네임')).not.toHaveAttribute('aria-invalid')
    expect(submit).toBeEnabled()
  })

  it('shows a taken email or 닉네임 under its field', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      http.post('/api/auth/signup', () =>
        problem(409, '이미 사용 중인 닉네임입니다.', [{ field: 'nickname', message: '이미 사용 중인 닉네임입니다.' }]),
      ),
    )
    renderAt('/signup')

    await user.type(await screen.findByLabelText('이메일'), 'me@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.type(screen.getByLabelText('닉네임'), '인기닉네임')
    await user.click(screen.getByRole('button', { name: '가입하기' }))

    expect(await screen.findByText('이미 사용 중인 닉네임입니다.')).toBeInTheDocument()
    expect(screen.getByLabelText('닉네임')).toHaveAccessibleDescription('이미 사용 중인 닉네임입니다.')
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-invalid')

    await user.type(screen.getByLabelText('닉네임'), '2')

    expect(screen.getByLabelText('닉네임')).not.toHaveAttribute('aria-invalid')
  })

  it('signs up with the CSRF token, logs in, and goes to next', async () => {
    const user = userEvent.setup({ delay: null })
    const session = loggedInAs(null)
    let sent: unknown
    let csrfHeader: string | null = null
    server.use(
      http.post('/api/auth/signup', async ({ request }) => {
        sent = await request.json()
        csrfHeader = request.headers.get('X-XSRF-TOKEN')
        session.logIn({ id: 5, nickname: '새회원' })
        return HttpResponse.json({ id: 5, nickname: '새회원' }, { status: 201 })
      }),
    )
    render(
      <MemoryRouter initialEntries={[`/signup?next=${encodeURIComponent('/broadcasts/7?tab=chat')}`]}>
        <AppRoutes />
        <LocationProbe />
      </MemoryRouter>,
    )

    await user.type(await screen.findByLabelText('이메일'), 'Me@Example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.type(screen.getByLabelText('닉네임'), ' 새회원 ')
    await user.click(screen.getByRole('button', { name: '가입하기' }))

    await waitFor(() => expect(screen.getByLabelText('현재 위치')).toHaveTextContent('/broadcasts/7?tab=chat'))
    expect(await header().findByText('새회원')).toBeInTheDocument()
    expect(sent).toEqual({ email: 'Me@Example.com', password: 'password1234', nickname: ' 새회원 ' })
    expect(csrfHeader).toBe(CSRF_TOKEN)
  })
})

describe('로그인 page', () => {
  it('asks for both fields before submitting', async () => {
    const user = userEvent.setup({ delay: null })
    renderAt('/login')

    const submit = await screen.findByRole('button', { name: '로그인' })
    expect(submit).toBeDisabled()

    await user.type(screen.getByLabelText('이메일'), 'a')
    await user.clear(screen.getByLabelText('이메일'))

    expect(screen.getByLabelText('이메일')).toHaveAccessibleDescription('이메일을 입력해 주세요.')
    expect(submit).toBeDisabled()
  })

  it('shows the single bad-credentials message from the server and keeps the input', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(http.post('/api/auth/login', () => problem(401, '이메일 또는 비밀번호가 올바르지 않습니다.')))
    renderAt('/login')

    await user.type(await screen.findByLabelText('이메일'), 'me@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('이메일 또는 비밀번호가 올바르지 않습니다.')
    expect(screen.getByLabelText('이메일')).toHaveValue('me@example.com')
    expect(header().getByRole('link', { name: '로그인' })).toBeInTheDocument()
  })

  it('logs in and returns to next', async () => {
    const user = userEvent.setup({ delay: null })
    const session = loggedInAs(null)
    let sent: unknown
    server.use(
      http.post('/api/auth/login', async ({ request }) => {
        sent = await request.json()
        session.logIn({ id: 3, nickname: '돌아온회원' })
        return new HttpResponse(null, { status: 204 })
      }),
    )
    render(
      <MemoryRouter initialEntries={[`/login?next=${encodeURIComponent('/vods/12')}`]}>
        <AppRoutes />
        <LocationProbe />
      </MemoryRouter>,
    )

    await user.type(await screen.findByLabelText('이메일'), 'me@example.com')
    await user.type(screen.getByLabelText('비밀번호'), 'password1234')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(screen.getByLabelText('현재 위치')).toHaveTextContent('/vods/12'))
    expect(await header().findByText('돌아온회원')).toBeInTheDocument()
    expect(sent).toEqual({ email: 'me@example.com', password: 'password1234' })
  })

  it.each(['https://evil.example/', '//evil.example/x', '/\\evil.example', 'vods'])(
    'goes home instead of to a next outside the app (%s)',
    async (next) => {
      const user = userEvent.setup({ delay: null })
      const session = loggedInAs(null)
      server.use(
        http.get('/api/broadcasts', () =>
          HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
        ),
        http.post('/api/auth/login', () => {
          session.logIn({ id: 3, nickname: '돌아온회원' })
          return new HttpResponse(null, { status: 204 })
        }),
      )
      render(
        <MemoryRouter initialEntries={[`/login?next=${encodeURIComponent(next)}`]}>
          <AppRoutes />
          <LocationProbe />
        </MemoryRouter>,
      )

      await user.type(await screen.findByLabelText('이메일'), 'me@example.com')
      await user.type(screen.getByLabelText('비밀번호'), 'password1234')
      await user.click(screen.getByRole('button', { name: '로그인' }))

      await waitFor(() => expect(screen.getByLabelText('현재 위치')).toHaveTextContent(/^\/$/))
    },
  )

  it('links to 회원가입 keeping next', async () => {
    renderAt(`/login?next=${encodeURIComponent('/vods/12')}`)

    expect(await screen.findByRole('link', { name: '회원가입하기' })).toHaveAttribute(
      'href',
      `/signup?next=${encodeURIComponent('/vods/12')}`,
    )
  })
})

describe('requires-login guard', () => {
  function renderGuarded(path: string) {
    return render(
      <MemoryRouter initialEntries={[path]}>
        <CurrentMemberProvider>
          <Routes>
            <Route path="/login" element={<p>로그인 화면</p>} />
            <Route
              path="/private"
              element={
                <RequireMember>
                  <p>회원 전용 화면</p>
                </RequireMember>
              }
            />
          </Routes>
          <LocationProbe />
        </CurrentMemberProvider>
      </MemoryRouter>,
    )
  }

  it('sends a visitor to login with next set to the protected screen', async () => {
    renderGuarded('/private?x=1')

    expect(await screen.findByText('로그인 화면')).toBeInTheDocument()
    expect(screen.getByLabelText('현재 위치')).toHaveTextContent(`/login?next=${encodeURIComponent('/private?x=1')}`)
    expect(screen.queryByText('회원 전용 화면')).not.toBeInTheDocument()
  })

  it('shows the protected screen to a 회원', async () => {
    loggedInAs({ id: 1, nickname: '치지직회원' })

    renderGuarded('/private')

    expect(await screen.findByText('회원 전용 화면')).toBeInTheDocument()
  })
})
