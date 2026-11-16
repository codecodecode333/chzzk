import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { loggedInAs, server } from '../test/server'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

const owner = { id: 5, nickname: '소유자닉' }

/** Logs in (as the 소유자 by default) and opens the registration screen, waiting past the login check. */
async function renderRegistration(member = owner) {
  loggedInAs(member)
  const view = renderAt('/vods/new')
  await screen.findByRole('heading', { name: '다시보기 등록' })
  return view
}

function pageOf(content: Array<{ id: number; title: string; createdAt: string; owner: { id: number; nickname: string } }>) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

describe('VOD list page', () => {
  it('shows registered VODs as cards with title, 소유자 닉네임 and registration date', async () => {
    server.use(
      http.get('/api/vods', () =>
        HttpResponse.json(
          pageOf([
            { id: 2, title: '두 번째 다시보기', createdAt: '2026-11-12T10:00:00Z', owner },
            { id: 1, title: '첫 다시보기', createdAt: '2026-11-01T10:00:00Z', owner: { id: 6, nickname: '다른회원' } },
          ]),
        ),
      ),
    )

    renderAt('/vods')

    expect(screen.getByRole('heading', { name: '다시보기' })).toBeInTheDocument()
    const cards = await screen.findAllByRole('article')
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveTextContent('두 번째 다시보기')
    expect(cards[0]).toHaveTextContent('2026. 11. 12.')
    expect(cards[0]).toHaveTextContent('소유자닉')
    expect(cards[1]).toHaveTextContent('첫 다시보기')
    expect(cards[1]).toHaveTextContent('다른회원')
    expect(screen.queryByRole('navigation', { name: '페이지' })).not.toBeInTheDocument()
  })

  it('reads the current page from the URL and moves between pages', async () => {
    const user = userEvent.setup()
    const requested: Array<string | null> = []
    server.use(
      http.get('/api/vods', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        requested.push(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json({
          content: [{ id: 100 - page, title: `${page + 1}쪽 다시보기`, createdAt: '2026-11-01T10:00:00Z', owner }],
          page,
          size: 20,
          totalElements: 45,
          totalPages: 3,
        })
      }),
    )

    renderAt('/vods?page=2')

    expect(await screen.findByRole('article')).toHaveTextContent('2쪽 다시보기')
    expect(requested).toEqual(['1'])
    const pager = screen.getByRole('navigation', { name: '페이지' })
    expect(within(pager).getByRole('link', { name: '2' })).toHaveAttribute('aria-current', 'page')

    await user.click(within(pager).getByRole('link', { name: '다음' }))

    expect(await screen.findByText('3쪽 다시보기')).toBeInTheDocument()
    expect(within(pager).getByRole('link', { name: '3' })).toHaveAttribute('href', '/vods?page=3')
    expect(within(pager).getByRole('link', { name: '3' })).toHaveAttribute('aria-current', 'page')
    expect(within(pager).queryByRole('link', { name: '다음' })).not.toBeInTheDocument()
  })

  it('shows the empty state with a register button when there are no VODs', async () => {
    server.use(http.get('/api/vods', () => HttpResponse.json(pageOf([]))))

    renderAt('/vods')

    expect(await screen.findByText('아직 등록된 다시보기가 없습니다')).toBeInTheDocument()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('link', { name: '다시보기 등록' })).toHaveAttribute('href', '/vods/new')
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  it('shows an error message instead of a blank page when the list cannot be loaded', async () => {
    server.use(http.get('/api/vods', () => HttpResponse.error()))

    renderAt('/vods')

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(screen.queryByText('아직 등록된 다시보기가 없습니다')).not.toBeInTheDocument()
  })
})

/**
 * happy-dom never fires XHR upload progress, so stand in for the network: the first
 * request body sent reports `loaded` of `total` bytes before going out to MSW.
 */
function emitUploadProgress(loaded: number, total: number) {
  const send = XMLHttpRequest.prototype.send
  return vi.spyOn(XMLHttpRequest.prototype, 'send').mockImplementation(function (
    this: XMLHttpRequest,
    body?: Document | XMLHttpRequestBodyInit | null,
  ) {
    send.call(this, body)
    if (body instanceof FormData) {
      this.upload.dispatchEvent(new ProgressEvent('progress', { lengthComputable: true, loaded, total }))
    }
  })
}

function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => (resolve = r))
  return { promise, resolve }
}

async function fillRegistrationForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText('제목'), '새 다시보기')
  await user.type(screen.getByLabelText('설명'), '설명입니다')
  const file = new File([new Uint8Array([0, 0, 0, 0x18])], 'clip.mp4', { type: 'video/mp4' })
  await user.upload(screen.getByLabelText('영상 파일'), file)
}

describe('VOD registration page', () => {
  afterEach(() => vi.restoreAllMocks())

  it('sends a visitor to login, coming back to registration afterwards', async () => {
    renderAt('/vods/new')

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(screen.queryByLabelText('영상 파일')).not.toBeInTheDocument()
    expect(await screen.findByRole('link', { name: '회원가입하기' })).toHaveAttribute(
      'href',
      `/signup?next=${encodeURIComponent('/vods/new')}`,
    )
  })

  it('shows upload progress with submit disabled, then lands on the new VOD detail page', async () => {
    const user = userEvent.setup()
    emitUploadProgress(40, 80)
    const uploaded = deferred()
    let received: { title: unknown; description: unknown; fileName: unknown } | undefined
    const created = {
      id: 7,
      title: '새 다시보기',
      description: '설명입니다',
      createdAt: '2026-11-13T09:00:00Z',
      streamUrl: '/api/vods/7/stream',
      owner,
    }

    server.use(
      http.post('/api/vods', async ({ request }) => {
        const form = await request.formData()
        const file = form.get('file')
        received = {
          title: form.get('title'),
          description: form.get('description'),
          fileName: file instanceof Blob ? (file as File).name : file,
        }
        await uploaded.promise
        return HttpResponse.json(created, { status: 201, headers: { Location: '/api/vods/7' } })
      }),
      http.get('/api/vods/7', () => HttpResponse.json(created)),
    )

    await renderRegistration()
    await fillRegistrationForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    const bar = await screen.findByRole('progressbar', { name: '업로드 진행률' })
    await waitFor(() => expect(bar).toHaveAttribute('aria-valuenow', '50'))
    expect(screen.getByRole('button', { name: /등록/ })).toBeDisabled()

    uploaded.resolve()

    expect(await screen.findByRole('heading', { name: '새 다시보기' })).toBeInTheDocument()
    expect(screen.getByTestId('vod-player')).toHaveAttribute('src', '/api/vods/7/stream')
    expect(received).toEqual({ title: '새 다시보기', description: '설명입니다', fileName: 'clip.mp4' })
  })

  it('resets progress and re-enables submit with inputs kept when the upload fails', async () => {
    const user = userEvent.setup()
    emitUploadProgress(40, 80)
    server.use(
      http.post('/api/vods', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Internal Server Error', status: 500 },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    await renderRegistration()
    await fillRegistrationForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '등록' })).toBeEnabled())
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(screen.getByLabelText('제목')).toHaveValue('새 다시보기')
    expect(screen.getByLabelText('설명')).toHaveValue('설명입니다')
    expect(screen.getByRole('heading', { name: '다시보기 등록' })).toBeInTheDocument()
  })
})

const detail = {
  id: 3,
  title: '상세 다시보기',
  description: '상세 설명',
  createdAt: '2026-11-10T10:00:00Z',
  streamUrl: '/api/vods/3/stream',
  owner,
}

describe('VOD detail page', () => {
  it('opens from a list card and plays the stream with title, description and date', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/vods', () => HttpResponse.json(pageOf([detail]))),
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
    )

    renderAt('/vods')
    await user.click(await screen.findByRole('link', { name: /상세 다시보기/ }))

    expect(await screen.findByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
    expect(screen.getByText('상세 설명')).toBeInTheDocument()
    expect(screen.getByText('2026. 11. 10.')).toBeInTheDocument()
    const video = screen.getByTestId('vod-player')
    expect(video).toHaveAttribute('src', '/api/vods/3/stream')
    expect(video).toHaveAttribute('controls')
  })

  it('shows the not-found view for an unknown id', async () => {
    server.use(
      http.get('/api/vods/404', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Not Found', status: 404, detail: '404번 다시보기를 찾을 수 없습니다.' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderAt('/vods/404')

    expect(await screen.findByRole('heading', { name: '다시보기를 찾을 수 없습니다' })).toBeInTheDocument()
    expect(screen.queryByTestId('vod-player')).not.toBeInTheDocument()
  })

  it('shows the not-found view for an id that is not a VOD id', async () => {
    server.use(
      http.get('/api/vods/:id', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Bad Request', status: 400 },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderAt('/vods/abc')

    expect(await screen.findByRole('heading', { name: '다시보기를 찾을 수 없습니다' })).toBeInTheDocument()
  })

  it('shows the not-found view when the server cannot parse an over-long numeric id', async () => {
    server.use(
      http.get('/api/vods/:id', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Bad Request', status: 400 },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderAt('/vods/99999999999999999999999')

    expect(await screen.findByRole('heading', { name: '다시보기를 찾을 수 없습니다' })).toBeInTheDocument()
  })

  it('shows an error message when the VOD cannot be loaded', async () => {
    server.use(
      http.get('/api/vods/3', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Internal Server Error', status: 500, detail: '요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.' },
          { status: 500, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderAt('/vods/3')

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(screen.queryByTestId('vod-player')).not.toBeInTheDocument()
  })

  it('shows an error message when the network fails', async () => {
    server.use(http.get('/api/vods/3', () => HttpResponse.error()))

    renderAt('/vods/3')

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
  })

  it('replaces the player with an error message when the video fails to play', async () => {
    server.use(http.get('/api/vods/3', () => HttpResponse.json(detail)))

    renderAt('/vods/3')
    fireEvent.error(await screen.findByTestId('vod-player'))

    expect(await screen.findByText('다시보기를 재생할 수 없습니다')).toBeInTheDocument()
    expect(screen.queryByTestId('vod-player')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
  })
})

describe('VOD registration form validation', () => {
  afterEach(() => vi.unstubAllEnvs())

  function mp4(name = 'clip.mp4') {
    return new File([new Uint8Array([0, 0, 0, 0x18])], name, { type: 'video/mp4' })
  }

  it('shows a title error and blocks submit while the title is over 100 characters', async () => {
    const user = userEvent.setup()
    await renderRegistration()

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.click(screen.getByLabelText('제목'))
    await user.paste('가'.repeat(101))

    expect(screen.getByText('제목은 100자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()

    await user.type(screen.getByLabelText('제목'), '{Backspace}')

    expect(screen.queryByText('제목은 100자 이하로 입력해 주세요.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeEnabled()
  })

  it('blocks submit on an untouched form without showing errors yet', async () => {
    await renderRegistration()

    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
    expect(screen.queryByText('제목을 입력해 주세요.')).not.toBeInTheDocument()
  })

  it('asks for a title when only whitespace was typed', async () => {
    const user = userEvent.setup()
    await renderRegistration()

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.type(screen.getByLabelText('제목'), '   ')

    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('shows a description error and blocks submit while the description is over 2,000 characters', async () => {
    const user = userEvent.setup()
    await renderRegistration()

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.type(screen.getByLabelText('제목'), '제목')
    await user.click(screen.getByLabelText('설명'))
    await user.paste('a'.repeat(2001))

    expect(screen.getByText('설명은 2,000자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('rejects a file that is not an .mp4', async () => {
    const user = userEvent.setup({ applyAccept: false })
    await renderRegistration()

    await user.type(screen.getByLabelText('제목'), '제목')
    await user.upload(screen.getByLabelText('영상 파일'), new File(['x'], 'clip.avi', { type: 'video/x-msvideo' }))

    expect(screen.getByText('MP4(.mp4) 파일만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('rejects a file larger than 1GB', async () => {
    const user = userEvent.setup()
    await renderRegistration()
    const huge = mp4('huge.mp4')
    Object.defineProperty(huge, 'size', { value: 1024 ** 3 + 1 })

    await user.type(screen.getByLabelText('제목'), '제목')
    await user.upload(screen.getByLabelText('영상 파일'), huge)

    expect(screen.getByText('영상 파일은 1GB 이하만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('rejects a file larger than the limit configured with VITE_VOD_MAX_FILE_SIZE', async () => {
    vi.stubEnv('VITE_VOD_MAX_FILE_SIZE', '16KB')
    const user = userEvent.setup()
    await renderRegistration()
    const big = mp4('big.mp4')
    Object.defineProperty(big, 'size', { value: 16 * 1024 + 1 })

    await user.type(screen.getByLabelText('제목'), '제목')
    await user.upload(screen.getByLabelText('영상 파일'), big)

    expect(screen.getByText('영상 파일은 16KB 이하만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText('제목'), '내 다시보기')
    await user.type(screen.getByLabelText('설명'), '설명입니다')
    await user.upload(screen.getByLabelText('영상 파일'), mp4())
  }

  it('shows the server problem and keeps the typed title and description when registration fails', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/api/vods', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Unsupported Media Type',
            status: 415,
            detail: 'MP4 형식의 영상 파일이 아닙니다.',
            instance: '/api/vods',
          },
          { status: 415, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    await renderRegistration()

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('MP4 형식의 영상 파일이 아닙니다.')
    expect(screen.getByLabelText('제목')).toHaveValue('내 다시보기')
    expect(screen.getByLabelText('설명')).toHaveValue('설명입니다')
  })

  it('shows a retry message when the upload fails without a problem body', async () => {
    const user = userEvent.setup()
    server.use(http.post('/api/vods', () => HttpResponse.error()))
    await renderRegistration()

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(screen.getByLabelText('제목')).toHaveValue('내 다시보기')
  })
})

describe('VOD detail page 소유자 controls', () => {
  afterEach(() => vi.unstubAllGlobals())

  const forbidden = (detailText: string) =>
    HttpResponse.json(
      { type: 'about:blank', title: 'Forbidden', status: 403, detail: detailText, instance: '/api/vods/3' },
      { status: 403, headers: { 'Content-Type': 'application/problem+json' } },
    )

  it('shows the 소유자 닉네임 to everyone', async () => {
    server.use(http.get('/api/vods/3', () => HttpResponse.json(detail)))

    renderAt('/vods/3')

    expect(await screen.findByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
    expect(screen.getByText('소유자닉')).toBeInTheDocument()
  })

  it('hides 수정 and 삭제 from a visitor and from another 회원', async () => {
    server.use(http.get('/api/vods/3', () => HttpResponse.json(detail)))

    const visitor = renderAt('/vods/3')
    expect(await screen.findByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
    visitor.unmount()

    loggedInAs({ id: 6, nickname: '다른회원' })
    renderAt('/vods/3')
    expect(await screen.findByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
    await screen.findByText('다른회원') // the header knows who is logged in
    expect(screen.queryByRole('button', { name: '수정' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
  })

  it('lets the 소유자 edit the title and description inline', async () => {
    const user = userEvent.setup()
    loggedInAs(owner)
    let sent: unknown
    server.use(
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
      http.patch('/api/vods/3', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ ...detail, title: '바뀐 제목', description: '바뀐 설명' })
      }),
    )

    renderAt('/vods/3')
    await user.click(await screen.findByRole('button', { name: '수정' }))

    const title = screen.getByLabelText('제목')
    const description = screen.getByLabelText('설명')
    expect(title).toHaveValue('상세 다시보기')
    expect(description).toHaveValue('상세 설명')
    await user.clear(title)
    await user.type(title, '바뀐 제목')
    await user.clear(description)
    await user.type(description, '바뀐 설명')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('heading', { name: '바뀐 제목' })).toBeInTheDocument()
    expect(screen.getByText('바뀐 설명')).toBeInTheDocument()
    expect(screen.queryByLabelText('제목')).not.toBeInTheDocument()
    expect(sent).toEqual({ title: '바뀐 제목', description: '바뀐 설명' })
  })

  it('validates the edit like registration and can be cancelled', async () => {
    const user = userEvent.setup()
    loggedInAs(owner)
    server.use(http.get('/api/vods/3', () => HttpResponse.json(detail)))

    renderAt('/vods/3')
    await user.click(await screen.findByRole('button', { name: '수정' }))
    await user.clear(screen.getByLabelText('제목'))

    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '취소' }))

    expect(screen.getByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
    expect(screen.queryByLabelText('제목')).not.toBeInTheDocument()
  })

  it('shows the server 403 message when an edit is refused', async () => {
    const user = userEvent.setup()
    loggedInAs(owner)
    server.use(
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
      http.patch('/api/vods/3', () => forbidden('내 다시보기만 수정하거나 삭제할 수 있습니다.')),
    )

    renderAt('/vods/3')
    await user.click(await screen.findByRole('button', { name: '수정' }))
    await user.type(screen.getByLabelText('제목'), '!')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('내 다시보기만 수정하거나 삭제할 수 있습니다.')
    expect(screen.getByLabelText('제목')).toHaveValue('상세 다시보기!')
  })

  it('deletes only after confirmation, then returns to the list', async () => {
    const user = userEvent.setup()
    loggedInAs(owner)
    let deletes = 0
    server.use(
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
      http.delete('/api/vods/3', () => {
        deletes++
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/vods', () => HttpResponse.json(pageOf([]))),
    )
    const confirm = vi.fn<(message?: string) => boolean>().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('confirm', confirm)

    renderAt('/vods/3')
    await user.click(await screen.findByRole('button', { name: '삭제' }))

    expect(confirm).toHaveBeenCalledTimes(1)
    expect(deletes).toBe(0)
    expect(screen.getByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '삭제' }))

    expect(await screen.findByText('아직 등록된 다시보기가 없습니다')).toBeInTheDocument()
    expect(deletes).toBe(1)
  })

  it('shows the server 403 message when a delete is refused', async () => {
    const user = userEvent.setup()
    loggedInAs(owner)
    server.use(
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
      http.delete('/api/vods/3', () => forbidden('내 다시보기만 수정하거나 삭제할 수 있습니다.')),
    )
    vi.stubGlobal('confirm', () => true)

    renderAt('/vods/3')
    await user.click(await screen.findByRole('button', { name: '삭제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('내 다시보기만 수정하거나 삭제할 수 있습니다.')
    expect(screen.getByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
  })
})
