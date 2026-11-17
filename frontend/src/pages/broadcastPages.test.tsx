import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import * as media from '../media/media'
import { loggedInAs, server } from '../test/server'

// Pages reach WebRTC and the camera only through the media module, so it is mocked whole.
vi.mock('../media/media', async (importOriginal) => {
  const actual = await importOriginal<typeof media>()
  return {
    MediaError: actual.MediaError,
    canPublish: vi.fn(() => true),
    openCamera: vi.fn(),
    closeStream: vi.fn(),
    publish: vi.fn(),
    switchCamera: vi.fn(),
    watch: vi.fn(),
  }
})

const mocked = vi.mocked(media)

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

/** Stands in for a camera or received stream; the pages only hand it to a `<video>`. */
let fakeStream: MediaStream

beforeEach(() => {
  fakeStream = new MediaStream()
})

afterEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

/** The logged-in 송출자 of the 송출 screen tests. */
const OWNER = { id: 1, nickname: '방송인' }

const emptyPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }

/**
 * Logs in as {@link OWNER}, whose 방송 중 방송 (by default none) the 송출 screen looks up on load. Returns how many
 * times it was looked up.
 */
function asOwnerWithLive(live: object[] = []) {
  loggedInAs(OWNER)
  let lookups = 0
  server.use(
    http.get('/api/broadcasts', ({ request }) => {
      const params = new URL(request.url).searchParams
      if (params.get('status') !== 'LIVE' || params.get('ownerId') !== String(OWNER.id)) {
        return HttpResponse.json(emptyPage)
      }
      lookups++
      return HttpResponse.json({ ...emptyPage, content: live, totalElements: live.length, totalPages: 1 })
    }),
  )
  return () => lookups
}

describe('송출 screen', () => {
  beforeEach(() => {
    asOwnerWithLive()
  })

  it('sends a visitor to the login page and back', async () => {
    loggedInAs(null)

    renderAt('/broadcasts/new')

    expect(await screen.findByRole('heading', { name: '로그인' })).toBeInTheDocument()
    expect(mocked.openCamera).not.toHaveBeenCalled()
  })

  it('starts a 방송 from the camera preview and publishes it with the issued 송출 키', async () => {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    let requestedTitle: unknown
    server.use(
      http.post('/api/broadcasts', async ({ request }) => {
        requestedTitle = ((await request.json()) as { title: string }).title
        return HttpResponse.json(
          {
            id: 7,
            title: '저녁 산책',
            status: 'LIVE',
            startedAt: new Date().toISOString(),
            publishKey: 'the-publish-key',
            whipUrl: '/live/broadcasts/7/whip',
          },
          { status: 201, headers: { Location: '/api/broadcasts/7' } },
        )
      }),
    )

    renderAt('/broadcasts/new')

    const preview = await screen.findByTestId('camera-preview')
    await waitFor(() => expect((preview as HTMLVideoElement).srcObject).toBe(fakeStream))
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))

    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(requestedTitle).toBe('저녁 산책')
    expect(mocked.publish).toHaveBeenCalledWith(fakeStream, '/live/broadcasts/7/whip', 'the-publish-key', expect.anything())
    expect(screen.getByRole('timer')).toHaveTextContent('00:00')
    expect(screen.getByRole('link', { name: /시청 링크/ })).toHaveAttribute('href', '/broadcasts/7')
    expect(screen.queryByRole('button', { name: '방송 시작' })).not.toBeInTheDocument()
  })

  it('validates the title inline with the VOD rule and blocks 방송 시작 while it is invalid', async () => {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)

    renderAt('/broadcasts/new')

    await screen.findByTestId('camera-preview')
    const start = screen.getByRole('button', { name: '방송 시작' })
    const titleInput = screen.getByLabelText('제목')
    expect(start).toBeDisabled()
    expect(screen.queryByText('제목을 입력해 주세요.')).not.toBeInTheDocument()

    await user.type(titleInput, '   ')
    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(titleInput).toHaveAttribute('aria-invalid', 'true')
    expect(start).toBeDisabled()

    await user.clear(titleInput)
    await user.click(titleInput)
    await user.paste('가'.repeat(101))
    expect(screen.getByText('제목은 100자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(start).toBeDisabled()

    await user.type(titleInput, '{Backspace}')
    expect(screen.queryByText('제목은 100자 이하로 입력해 주세요.')).not.toBeInTheDocument()
    expect(start).toBeEnabled()
  })

  it('switches between the front and back camera on the preview', async () => {
    const user = userEvent.setup()
    const backCamera = new MediaStream()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.switchCamera
      .mockResolvedValueOnce({ stream: backCamera, facing: 'environment' })
      .mockResolvedValueOnce({ stream: fakeStream, facing: 'user' })

    renderAt('/broadcasts/new')

    const preview = (await screen.findByTestId('camera-preview')) as HTMLVideoElement
    await user.click(await screen.findByRole('button', { name: '후면 카메라로 전환' }))

    expect(mocked.switchCamera).toHaveBeenLastCalledWith(fakeStream, 'environment', null)
    expect(preview.srcObject).toBe(backCamera)

    await user.click(screen.getByRole('button', { name: '전면 카메라로 전환' }))

    expect(mocked.switchCamera).toHaveBeenLastCalledWith(backCamera, 'user', null)
    expect(preview.srcObject).toBe(fakeStream)
  })

  it('switches the camera while 방송 중 on the running publication, without starting a new 방송', async () => {
    const user = userEvent.setup()
    const backCamera = new MediaStream()
    const publication = { stop: vi.fn() }
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue(publication)
    mocked.switchCamera.mockResolvedValue({ stream: backCamera, facing: 'environment' })
    let starts = 0
    server.use(
      http.post('/api/broadcasts', () => {
        starts++
        return HttpResponse.json(
          {
            id: 7,
            title: '저녁 산책',
            status: 'LIVE',
            startedAt: new Date().toISOString(),
            publishKey: 'the-publish-key',
            whipUrl: '/live/broadcasts/7/whip',
          },
          { status: 201 },
        )
      }),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    await screen.findByText('방송 중')

    await user.click(await screen.findByRole('button', { name: '후면 카메라로 전환' }))

    expect(mocked.switchCamera).toHaveBeenCalledWith(fakeStream, 'environment', publication)
    expect((screen.getByTestId('camera-preview') as HTMLVideoElement).srcObject).toBe(backCamera)
    expect(screen.getByText('방송 중')).toBeInTheDocument()
    expect(publication.stop).not.toHaveBeenCalled()
    expect(mocked.publish).toHaveBeenCalledTimes(1)
    expect(starts).toBe(1)
  })

  it.each([
    ['permission-denied', /권한이 거부되었습니다.*사이트 설정.*허용/],
    ['insecure-context', /HTTPS.*https:\/\//],
    ['unsupported', /이 브라우저는 실시간 송출을 지원하지 않습니다/],
  ] as const)('explains a %s camera failure and keeps 방송 시작 disabled', async (kind, message) => {
    const user = userEvent.setup()
    mocked.openCamera.mockRejectedValue(new media.MediaError(kind, 'module wording'))

    renderAt('/broadcasts/new')

    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    expect(screen.getByRole('button', { name: '방송 시작' })).toBeDisabled()
  })

  it('ends the 방송 after confirmation, releases the camera and shows 종료됨', async () => {
    const user = userEvent.setup()
    const stopPublishing = vi.fn()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: stopPublishing })
    let endRequests = 0
    let endAuthorization: string | null = null
    server.use(
      http.post('/api/broadcasts', () =>
        HttpResponse.json(
          {
            id: 7,
            title: '저녁 산책',
            status: 'LIVE',
            startedAt: new Date().toISOString(),
            publishKey: 'the-publish-key',
            whipUrl: '/live/broadcasts/7/whip',
          },
          { status: 201 },
        ),
      ),
      http.post('/api/broadcasts/7/end', ({ request }) => {
        endRequests++
        endAuthorization = request.headers.get('Authorization')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const confirm = vi.fn<(message?: string) => boolean>()
    vi.stubGlobal('confirm', confirm)

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    const end = await screen.findByRole('button', { name: '방송 종료' })

    confirm.mockReturnValueOnce(false)
    await user.click(end)
    expect(endRequests).toBe(0)
    expect(screen.getByText('방송 중')).toBeInTheDocument()

    confirm.mockReturnValueOnce(true)
    await user.click(end)

    expect(await screen.findByText('종료됨')).toBeInTheDocument()
    expect(endRequests).toBe(1)
    expect(endAuthorization).toBeNull() // the login session ends it, not the 송출 키
    expect(stopPublishing).toHaveBeenCalled()
    expect(mocked.closeStream).toHaveBeenCalledWith(fakeStream)
    expect(screen.queryByText('방송 중')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '방송 종료' })).not.toBeInTheDocument()
  })

  it('asks to log in again when the session expired on 방송 종료, and keeps publishing', async () => {
    const user = userEvent.setup()
    const stopPublishing = vi.fn()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: stopPublishing })
    server.use(
      http.post('/api/broadcasts', () =>
        HttpResponse.json(
          {
            id: 7,
            title: '저녁 산책',
            status: 'LIVE',
            startedAt: new Date().toISOString(),
            owner: OWNER,
            publishKey: 'the-publish-key',
            whipUrl: '/live/broadcasts/7/whip',
          },
          { status: 201 },
        ),
      ),
      http.post('/api/broadcasts/7/end', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Unauthorized', status: 401, detail: '로그인이 필요합니다.' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    vi.stubGlobal('confirm', () => true)

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    await user.click(await screen.findByRole('button', { name: '방송 종료' }))

    const relogin = await screen.findByRole('link', { name: '다시 로그인' })
    // back to the 시청 page, not the 송출 screen, whose "이어서 송출" would take over from this tab
    expect(relogin).toHaveAttribute('href', '/login?next=%2Fbroadcasts%2F7')
    expect(relogin).toHaveAttribute('target', '_blank')
    expect(screen.getByText('방송 중')).toBeInTheDocument()
    expect(stopPublishing).not.toHaveBeenCalled()
    expect(mocked.closeStream).not.toHaveBeenCalled()
  })
})

describe('송출 screen when publishing fails or drops', () => {
  beforeEach(() => {
    asOwnerWithLive()
  })

  /** "방송 시작" answers with a new id (7, 8, ...) and key `key-{id}` each time; ended ids are recorded. */
  function serveStartsAndEnds() {
    let nextId = 7
    const ends: string[] = []
    let starts = 0
    server.use(
      http.post('/api/broadcasts', () => {
        starts++
        const id = nextId++
        return HttpResponse.json(
          {
            id,
            title: '저녁 산책',
            status: 'LIVE',
            startedAt: new Date().toISOString(),
            publishKey: `key-${id}`,
            whipUrl: `/live/broadcasts/${id}/whip`,
          },
          { status: 201 },
        )
      }),
      http.post('/api/broadcasts/:id/end', ({ params }) => {
        ends.push(String(params.id))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    return { ends, starts: () => starts }
  }

  function serveDetail(status: 'LIVE' | 'ENDED') {
    let requests = 0
    server.use(
      http.get('/api/broadcasts/7', () => {
        requests++
        return HttpResponse.json({
          id: 7,
          title: '저녁 산책',
          status,
          startedAt: '2026-11-15T10:00:00Z',
          endedAt: status === 'ENDED' ? '2026-11-15T10:05:00Z' : null,
          whepUrl: status === 'LIVE' ? '/live/broadcasts/7/whep' : null,
        })
      }),
    )
    return () => requests
  }

  async function startBroadcastOnScreen(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByTestId('camera-preview')
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
  }

  it('ends the just-started 방송 when publishing fails, so a retry leaves no ghost 방송 중', async () => {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish
      .mockRejectedValueOnce(new media.MediaError('signaling-failed', '미디어 서버에 연결하지 못했습니다.'))
      .mockResolvedValueOnce({ stop: vi.fn() })
    const { ends } = serveStartsAndEnds()

    renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)

    expect(await screen.findByRole('alert')).toHaveTextContent('미디어 서버에 연결하지 못했습니다.')
    await waitFor(() => expect(ends).toEqual(['7']))

    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(mocked.publish).toHaveBeenLastCalledWith(fakeStream, '/live/broadcasts/8/whip', 'key-8', expect.anything())
    expect(ends).toEqual(['7'])
  })

  it('shows 종료됨 and releases the camera when the publication drops because the 방송 ended', async () => {
    const user = userEvent.setup()
    const stopPublishing = vi.fn()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: stopPublishing })
    serveStartsAndEnds()
    serveDetail('ENDED')

    renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)
    await screen.findByText('방송 중')
    const { onDisconnect } = mocked.publish.mock.calls[0][3]!
    act(() => onDisconnect!())

    expect(await screen.findByText('종료됨')).toBeInTheDocument()
    expect(stopPublishing).toHaveBeenCalled()
    expect(mocked.closeStream).toHaveBeenCalledWith(fakeStream)
    expect(screen.queryByRole('timer')).not.toBeInTheDocument()
  })

  it('says 송출이 끊겼습니다 when the publication drops while still 방송 중, and reconnects to the same 방송', async () => {
    const user = userEvent.setup()
    const dropped = { stop: vi.fn() }
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValueOnce(dropped).mockResolvedValueOnce({ stop: vi.fn() })
    const { starts } = serveStartsAndEnds()
    serveDetail('LIVE')

    renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)
    await screen.findByText('방송 중')
    const { onDisconnect } = mocked.publish.mock.calls[0][3]!
    act(() => onDisconnect!())

    expect(await screen.findByRole('alert')).toHaveTextContent('송출이 끊겼습니다')
    expect(dropped.stop).toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: '다시 연결' }))

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(mocked.publish).toHaveBeenCalledTimes(2)
    expect(mocked.publish).toHaveBeenLastCalledWith(fakeStream, '/live/broadcasts/7/whip', 'key-7', expect.anything())
    expect(screen.getByText('방송 중')).toBeInTheDocument()
    expect(starts()).toBe(1)
  })

  it('says another tab took over when its old 송출 키 is refused, and takes it back only on request', async () => {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish
      .mockResolvedValueOnce({ stop: vi.fn() })
      .mockRejectedValueOnce(new media.MediaError('signaling-failed', '송출 키가 거부되었습니다.', 401))
      .mockResolvedValueOnce({ stop: vi.fn() })
    serveStartsAndEnds()
    serveDetail('LIVE')
    let issued = 0
    server.use(
      http.post('/api/broadcasts/7/publish-key', () => {
        issued++
        return HttpResponse.json({ publishKey: 'fresh-key', whipUrl: '/live/broadcasts/7/whip' })
      }),
    )

    renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)
    await screen.findByText('방송 중')
    const { onDisconnect } = mocked.publish.mock.calls[0][3]!
    act(() => onDisconnect!())
    await user.click(await screen.findByRole('button', { name: '다시 연결' }))

    expect(await screen.findByText(/다른 탭이나 기기에서 이 방송을 이어서 송출/)).toBeInTheDocument()
    expect(issued).toBe(0)
    expect(screen.queryByText('종료됨')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '여기서 이어서 송출' }))

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(issued).toBe(1)
    expect(mocked.publish).toHaveBeenLastCalledWith(fakeStream, '/live/broadcasts/7/whip', 'fresh-key', expect.anything())
  })

  it('closes a publication that connects after the 송출 screen was left', async () => {
    const user = userEvent.setup()
    const late = { stop: vi.fn() }
    let connect: (session: media.Session) => void = () => {}
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockReturnValue(new Promise((resolve) => (connect = resolve)))
    serveStartsAndEnds()

    const page = renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)
    await waitFor(() => expect(mocked.publish).toHaveBeenCalled())
    page.unmount()
    await act(async () => connect(late))

    expect(late.stop).toHaveBeenCalled()
  })
})

describe('송출 screen with a 방송 already 방송 중', () => {
  const myLive = {
    id: 7,
    title: '저녁 산책',
    status: 'LIVE',
    startedAt: '2026-11-15T10:00:00Z',
    endedAt: null,
    owner: OWNER,
  }
  const detail = { ...myLive, whepUrl: '/live/broadcasts/7/whep' }

  /** "이어서 송출" answers fresh keys `fresh-1`, `fresh-2`, ...; returns how many were issued. */
  function servePublishKeys() {
    let issued = 0
    server.use(
      http.post('/api/broadcasts/7/publish-key', () => {
        issued++
        return HttpResponse.json({ publishKey: `fresh-${issued}`, whipUrl: '/live/broadcasts/7/whip' })
      }),
    )
    return () => issued
  }

  it('offers 이어서 송출 on load, which gets a fresh 송출 키 and republishes to that same 방송', async () => {
    const user = userEvent.setup()
    asOwnerWithLive([myLive])
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    const issued = servePublishKeys()
    let starts = 0
    server.use(
      http.post('/api/broadcasts', () => {
        starts++
        return new HttpResponse(null, { status: 500 })
      }),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    expect(await screen.findByText(/방송 중인/)).toHaveTextContent('저녁 산책')
    expect(screen.queryByRole('button', { name: '방송 시작' })).not.toBeInTheDocument()
    expect(mocked.publish).not.toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: '이어서 송출' }))

    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(issued()).toBe(1)
    expect(mocked.publish).toHaveBeenCalledWith(fakeStream, '/live/broadcasts/7/whip', 'fresh-1', expect.anything())
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /시청 링크/ })).toHaveAttribute('href', '/broadcasts/7')
    expect(starts).toBe(0)
  })

  it('offers the same 이어서 송출 when 방송 시작 answers 409 with the 방송 already 방송 중', async () => {
    const user = userEvent.setup()
    const lookups = asOwnerWithLive()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    const issued = servePublishKeys()
    server.use(
      http.post('/api/broadcasts', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Conflict',
            status: 409,
            detail: '이미 방송 중인 방송이 있습니다. 진행 중인 방송을 이어서 송출해 주세요.',
            broadcastId: 7,
          },
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
      http.get('/api/broadcasts/7', () => HttpResponse.json(detail)),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await waitFor(() => expect(lookups()).toBe(1))
    await user.type(screen.getByLabelText('제목'), '또 다른 방송')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))

    await user.click(await screen.findByRole('button', { name: '이어서 송출' }))

    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(issued()).toBe(1)
    expect(mocked.publish).toHaveBeenCalledWith(fakeStream, '/live/broadcasts/7/whip', 'fresh-1', expect.anything())
    expect(mocked.publish).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
  })

  it('새 방송으로 시작 ends the 방송 중 방송 by the session, then offers a fresh start', async () => {
    const user = userEvent.setup()
    asOwnerWithLive([myLive])
    mocked.openCamera.mockResolvedValue(fakeStream)
    let ends = 0
    server.use(
      http.post('/api/broadcasts/7/end', () => {
        ends++
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.click(await screen.findByRole('button', { name: '새 방송으로 시작' }))

    expect(await screen.findByRole('button', { name: '방송 시작' })).toBeInTheDocument()
    expect(ends).toBe(1)
    expect(screen.queryByRole('button', { name: '이어서 송출' })).not.toBeInTheDocument()
  })

  it('closes a resumed publication that connects after the 송출 screen was left', async () => {
    const user = userEvent.setup()
    const late = { stop: vi.fn() }
    let connect: (session: media.Session) => void = () => {}
    asOwnerWithLive([myLive])
    servePublishKeys()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockReturnValue(new Promise((resolve) => (connect = resolve)))

    const page = renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.click(await screen.findByRole('button', { name: '이어서 송출' }))
    await waitFor(() => expect(mocked.publish).toHaveBeenCalled())
    page.unmount()
    await act(async () => connect(late))

    expect(late.stop).toHaveBeenCalled()
  })

  it('warns before leaving the page while 방송 중, and not before', async () => {
    const leaving = () => {
      const event = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(event)
      return event.defaultPrevented
    }
    const user = userEvent.setup()
    asOwnerWithLive()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    server.use(
      http.post('/api/broadcasts', () =>
        HttpResponse.json({ ...myLive, publishKey: 'k', whipUrl: '/live/broadcasts/7/whip' }, { status: 201 }),
      ),
    )
    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    expect(leaving()).toBe(false)

    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    await screen.findByText('방송 중')

    expect(leaving()).toBe(true)
  })
})

describe('시청 screen', () => {
  it('connects to a 방송 중 방송, plays it muted and offers 소리 켜기', async () => {
    const user = userEvent.setup({ delay: null })
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    server.use(
      http.get('/api/broadcasts/7', () =>
        HttpResponse.json({
          id: 7,
          title: '저녁 산책',
          status: 'LIVE',
          startedAt: '2026-11-15T10:00:00Z',
          endedAt: null,
          owner: OWNER,
          whepUrl: '/live/broadcasts/7/whep',
        }),
      ),
    )

    renderAt('/broadcasts/7')

    expect(await screen.findByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    const unmute = await screen.findByRole('button', { name: '소리 켜기' })
    expect(mocked.watch).toHaveBeenCalledWith('/live/broadcasts/7/whep', expect.anything())
    const player = screen.getByTestId('live-player') as HTMLVideoElement
    expect(player.srcObject).toBe(fakeStream)
    expect(player.muted).toBe(true)

    await user.click(unmute)

    expect(player.muted).toBe(false)
    expect(screen.queryByRole('button', { name: '소리 켜기' })).not.toBeInTheDocument()
  })

  const live = {
    id: 7,
    title: '저녁 산책',
    status: 'LIVE',
    startedAt: '2026-11-15T10:00:00Z',
    endedAt: null,
    owner: OWNER,
    whepUrl: '/live/broadcasts/7/whep',
  }

  it('shows the 소유자 닉네임, and the link to the 송출 screen only to the 소유자 while 방송 중', async () => {
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(live)))
    loggedInAs({ id: 2, nickname: '시청자' })

    const asOther = renderAt('/broadcasts/7')
    expect(await screen.findByText('방송인')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '송출 화면으로' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '방송 종료' })).not.toBeInTheDocument()
    asOther.unmount()

    loggedInAs(OWNER)
    renderAt('/broadcasts/7')
    expect(await screen.findByRole('link', { name: '송출 화면으로' })).toHaveAttribute('href', '/broadcasts/new')
  })
  const ended = { ...live, status: 'ENDED', endedAt: '2026-11-15T10:42:00Z', whepUrl: null }

  it('switches to 종료된 방송입니다 when the 5-second status poll finds the 방송 ended', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const stopWatching = vi.fn()
      mocked.watch.mockResolvedValue({ stream: fakeStream, stop: stopWatching })
      let current: object = live
      server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(current)))

      renderAt('/broadcasts/7')
      await screen.findByRole('button', { name: '소리 켜기' })

      current = ended
      await act(() => vi.advanceTimersByTimeAsync(5000))

      expect(await screen.findByText('종료된 방송입니다')).toBeInTheDocument()
      expect(screen.queryByTestId('live-player')).not.toBeInTheDocument()
      expect(stopWatching).toHaveBeenCalled()
      expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
      expect(screen.getByText(/종료 .*19:42/)).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('checks the status right away when the connection drops', async () => {
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    let current: object = live
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(current)))

    renderAt('/broadcasts/7')
    await screen.findByRole('button', { name: '소리 켜기' })
    const { onDisconnect } = mocked.watch.mock.calls[0][1]!

    current = ended
    act(() => onDisconnect?.())

    expect(await screen.findByText('종료된 방송입니다')).toBeInTheDocument()
  })

  it('shows 송출 준비 중… while the connection fails and retries every 3 seconds until it plays', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const notYet = new media.MediaError('signaling-failed', 'no stream yet', 404)
      mocked.watch
        .mockRejectedValueOnce(notYet)
        .mockRejectedValueOnce(notYet)
        .mockResolvedValueOnce({ stream: fakeStream, stop: vi.fn() })
      server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(live)))

      renderAt('/broadcasts/7')

      expect(await screen.findByText('송출 준비 중…')).toBeInTheDocument()
      expect(mocked.watch).toHaveBeenCalledTimes(1)

      await act(() => vi.advanceTimersByTimeAsync(3000))
      expect(mocked.watch).toHaveBeenCalledTimes(2)
      expect(screen.getByText('송출 준비 중…')).toBeInTheDocument()

      await act(() => vi.advanceTimersByTimeAsync(3000))
      expect(await screen.findByRole('button', { name: '소리 켜기' })).toBeInTheDocument()
      expect(mocked.watch).toHaveBeenCalledTimes(3)
      expect(screen.queryByText('송출 준비 중…')).not.toBeInTheDocument()
      expect((screen.getByTestId('live-player') as HTMLVideoElement).srcObject).toBe(fakeStream)
    } finally {
      vi.useRealTimers()
    }
  })

  it('stops retrying once the 방송 has ended', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mocked.watch.mockRejectedValue(new media.MediaError('signaling-failed', 'no stream yet', 404))
      let current: object = live
      server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(current)))

      renderAt('/broadcasts/7')
      expect(await screen.findByText('송출 준비 중…')).toBeInTheDocument()

      current = ended
      await act(() => vi.advanceTimersByTimeAsync(5000))
      expect(await screen.findByText('종료된 방송입니다')).toBeInTheDocument()
      const attempts = mocked.watch.mock.calls.length

      await act(() => vi.advanceTimersByTimeAsync(10000))

      expect(mocked.watch).toHaveBeenCalledTimes(attempts)
      expect(screen.queryByText('송출 준비 중…')).not.toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows an ended 방송 with its times and no player, without connecting', async () => {
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(ended)))

    renderAt('/broadcasts/7')

    expect(await screen.findByText('종료된 방송입니다')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.getByText(/시작 /)).toBeInTheDocument()
    expect(screen.getByText(/종료 /)).toBeInTheDocument()
    expect(screen.queryByTestId('live-player')).not.toBeInTheDocument()
    expect(screen.queryByText('방송 중')).not.toBeInTheDocument()
    expect(mocked.watch).not.toHaveBeenCalled()
  })

  it('shows a not-found view for an unknown 방송', async () => {
    server.use(http.get('/api/broadcasts/404', () => HttpResponse.json({ status: 404 }, { status: 404 })))

    renderAt('/broadcasts/404')

    expect(await screen.findByRole('heading', { name: '방송을 찾을 수 없습니다' })).toBeInTheDocument()
    expect(mocked.watch).not.toHaveBeenCalled()
  })
})

describe('시청 screen controls for the 소유자', () => {
  const live = {
    id: 7,
    title: '저녁 산책',
    status: 'LIVE',
    startedAt: '2026-11-15T10:00:00Z',
    endedAt: null,
    owner: OWNER,
    whepUrl: '/live/broadcasts/7/whep',
  }
  const ended = { ...live, status: 'ENDED', endedAt: '2026-11-15T10:42:00Z', whepUrl: null }

  const problem = (status: number, detailText: string) =>
    HttpResponse.json(
      { type: 'about:blank', title: 'Error', status, detail: detailText, instance: '/api/broadcasts/7' },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    )

  beforeEach(() => {
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
  })

  it('shows 제목 수정 and 삭제 to no one but the 소유자', async () => {
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(ended)))

    const asVisitor = renderAt('/broadcasts/7')
    expect(await screen.findByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '제목 수정' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
    asVisitor.unmount()

    loggedInAs({ id: 2, nickname: '시청자' })
    const asOther = renderAt('/broadcasts/7')
    expect(await screen.findByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '제목 수정' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
    asOther.unmount()

    loggedInAs(OWNER)
    renderAt('/broadcasts/7')
    expect(await screen.findByRole('button', { name: '제목 수정' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '삭제' })).toBeInTheDocument()
  })

  it('offers 제목 수정 and the 송출 screen link while 방송 중, but no 삭제', async () => {
    loggedInAs(OWNER)
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(live)))

    renderAt('/broadcasts/7')

    expect(await screen.findByRole('button', { name: '제목 수정' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '송출 화면으로' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()
  })

  it('renames inline with the title rule, and can be cancelled', async () => {
    const user = userEvent.setup()
    loggedInAs(OWNER)
    let sent: unknown
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(live)),
      http.patch('/api/broadcasts/7', async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({ ...live, title: '아침 산책' })
      }),
    )
    renderAt('/broadcasts/7')

    await user.click(await screen.findByRole('button', { name: '제목 수정' }))
    await user.clear(screen.getByLabelText('제목'))
    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '저장' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '취소' }))
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '제목 수정' }))
    expect(screen.getByLabelText('제목')).toHaveValue('저녁 산책')
    await user.clear(screen.getByLabelText('제목'))
    await user.type(screen.getByLabelText('제목'), '아침 산책')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('heading', { name: '아침 산책' })).toBeInTheDocument()
    expect(screen.queryByLabelText('제목')).not.toBeInTheDocument()
    expect(sent).toEqual({ title: '아침 산책' })
  })

  it('shows the server 403 message when a rename is refused', async () => {
    const user = userEvent.setup()
    loggedInAs(OWNER)
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(ended)),
      http.patch('/api/broadcasts/7', () => problem(403, '내 방송만 제어할 수 있습니다.')),
    )
    renderAt('/broadcasts/7')

    await user.click(await screen.findByRole('button', { name: '제목 수정' }))
    await user.type(screen.getByLabelText('제목'), '!')
    await user.click(screen.getByRole('button', { name: '저장' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('내 방송만 제어할 수 있습니다.')
    expect(screen.getByLabelText('제목')).toHaveValue('저녁 산책!')
  })

  it('deletes an ended 방송 only after confirmation, then returns home', async () => {
    const user = userEvent.setup()
    loggedInAs(OWNER)
    let deletes = 0
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(ended)),
      http.delete('/api/broadcasts/7', () => {
        deletes++
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/broadcasts', () => HttpResponse.json(emptyPage)),
    )
    const confirm = vi.fn<(message?: string) => boolean>().mockReturnValueOnce(false).mockReturnValueOnce(true)
    vi.stubGlobal('confirm', confirm)
    renderAt('/broadcasts/7')

    await user.click(await screen.findByRole('button', { name: '삭제' }))
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(deletes).toBe(0)
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: '삭제' }))

    expect(await screen.findByText(/아직 방송이 없습니다/)).toBeInTheDocument()
    expect(deletes).toBe(1)
  })

  it('shows the server 409 message when a delete is refused', async () => {
    const user = userEvent.setup()
    loggedInAs(OWNER)
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(ended)),
      http.delete('/api/broadcasts/7', () => problem(409, '방송 중인 방송은 삭제할 수 없습니다. 방송을 먼저 종료하세요.')),
    )
    vi.stubGlobal('confirm', () => true)
    renderAt('/broadcasts/7')

    await user.click(await screen.findByRole('button', { name: '삭제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('방송을 먼저 종료하세요.')
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
  })

  it('stays on the 방송 and says why when it has 후원 and cannot be deleted', async () => {
    const user = userEvent.setup()
    loggedInAs(OWNER)
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(ended)),
      http.delete('/api/broadcasts/7', () => problem(409, '후원 내역이 있는 방송은 삭제할 수 없습니다')),
    )
    vi.stubGlobal('confirm', () => true)
    renderAt('/broadcasts/7')

    await user.click(await screen.findByRole('button', { name: '삭제' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('후원 내역이 있는 방송은 삭제할 수 없습니다')
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '삭제' })).toBeEnabled()
  })
})
