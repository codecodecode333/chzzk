import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import * as media from '../media/media'
import { server } from '../test/server'

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
  sessionStorage.clear()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('송출 screen', () => {
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
    expect((preview as HTMLVideoElement).srcObject).toBe(fakeStream)
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
    await user.click(screen.getByRole('button', { name: '후면 카메라로 전환' }))

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

    await user.click(screen.getByRole('button', { name: '후면 카메라로 전환' }))

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
    expect(endAuthorization).toBeNull()
    expect(screen.getByText('방송 중')).toBeInTheDocument()

    confirm.mockReturnValueOnce(true)
    await user.click(end)

    expect(await screen.findByText('종료됨')).toBeInTheDocument()
    expect(endAuthorization).toBe('Bearer the-publish-key')
    expect(stopPublishing).toHaveBeenCalled()
    expect(mocked.closeStream).toHaveBeenCalledWith(fakeStream)
    expect(screen.queryByText('방송 중')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '방송 종료' })).not.toBeInTheDocument()
  })
})

describe('송출 screen when publishing fails or drops', () => {
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
      http.post('/api/broadcasts/:id/end', ({ params, request }) => {
        ends.push(`${String(params.id)} ${request.headers.get('Authorization')}`)
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
    await waitFor(() => expect(ends).toEqual(['7 Bearer key-7']))

    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(mocked.publish).toHaveBeenLastCalledWith(fakeStream, '/live/broadcasts/8/whip', 'key-8', expect.anything())
    expect(ends).toEqual(['7 Bearer key-7'])
  })

  it('forgets the failed 방송, so a reload offers no 이어서 송출 for it', async () => {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockRejectedValue(new media.MediaError('signaling-failed', '미디어 서버에 연결하지 못했습니다.'))
    serveStartsAndEnds()
    const detailRequests = serveDetail('LIVE')

    const first = renderAt('/broadcasts/new')
    await startBroadcastOnScreen(user)
    await screen.findByRole('alert')
    first.unmount()

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    expect(screen.getByRole('button', { name: '방송 시작' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이어서 송출' })).not.toBeInTheDocument()
    expect(detailRequests()).toBe(0)
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

describe('송출 screen after a reload', () => {
  const started = {
    id: 7,
    title: '저녁 산책',
    status: 'LIVE',
    startedAt: '2026-11-15T10:00:00Z',
    publishKey: 'the-publish-key',
    whipUrl: '/live/broadcasts/7/whip',
  }
  const detail = { ...started, endedAt: null, whepUrl: '/live/broadcasts/7/whep', publishKey: undefined }

  /** Starts a 방송 on a fresh 송출 screen, then leaves it as a reload would. */
  async function startThenReload(onAfterStart?: () => Promise<void>) {
    const user = userEvent.setup()
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    server.use(http.post('/api/broadcasts', () => HttpResponse.json(started, { status: 201 })))
    const first = renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.type(screen.getByLabelText('제목'), '저녁 산책')
    await user.click(screen.getByRole('button', { name: '방송 시작' }))
    await screen.findByText('방송 중')
    await onAfterStart?.()
    first.unmount()
    mocked.publish.mockClear()
    return user
  }

  it('offers 이어서 송출 for a 방송 still 방송 중 and republishes to that same 방송', async () => {
    let starts = 0
    const user = await startThenReload()
    server.use(
      http.post('/api/broadcasts', () => {
        starts++
        return HttpResponse.json(started, { status: 201 })
      }),
      http.get('/api/broadcasts/7', () => HttpResponse.json(detail)),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.click(await screen.findByRole('button', { name: '이어서 송출' }))

    expect(await screen.findByText('방송 중')).toBeInTheDocument()
    expect(mocked.publish).toHaveBeenCalledWith(fakeStream, '/live/broadcasts/7/whip', 'the-publish-key', expect.anything())
    expect(screen.getByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /시청 링크/ })).toHaveAttribute('href', '/broadcasts/7')
    expect(starts).toBe(0)
  })

  it('offers nothing for a stored 방송 that has ended, and a fresh start works', async () => {
    const user = await startThenReload()
    server.use(
      http.get('/api/broadcasts/7', () =>
        HttpResponse.json({ ...detail, status: 'ENDED', endedAt: '2026-11-15T10:01:00Z', whepUrl: null }),
      ),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')

    expect(await screen.findByRole('button', { name: '방송 시작' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이어서 송출' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('제목'), '새 방송')
    expect(screen.getByRole('button', { name: '방송 시작' })).toBeEnabled()
  })

  it('offers nothing after the 방송 was ended from the 송출 screen', async () => {
    vi.stubGlobal('confirm', () => true)
    server.use(http.post('/api/broadcasts/7/end', () => new HttpResponse(null, { status: 204 })))
    let detailRequests = 0
    await startThenReload(async () => {
      await userEvent.click(screen.getByRole('button', { name: '방송 종료' }))
      await screen.findByText('종료됨')
    })
    server.use(
      http.get('/api/broadcasts/7', () => {
        detailRequests++
        return HttpResponse.json(detail)
      }),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')

    expect(screen.getByRole('button', { name: '방송 시작' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '이어서 송출' })).not.toBeInTheDocument()
    expect(detailRequests).toBe(0)
  })

  it('새 방송으로 시작 ends the stored 방송 with its 송출 키 before forgetting it', async () => {
    let ended: string | null = null
    const user = await startThenReload()
    server.use(
      http.get('/api/broadcasts/7', () => HttpResponse.json(detail)),
      http.post('/api/broadcasts/7/end', ({ request }) => {
        ended = request.headers.get('Authorization')
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.click(await screen.findByRole('button', { name: '새 방송으로 시작' }))

    expect(await screen.findByRole('button', { name: '방송 시작' })).toBeInTheDocument()
    expect(ended).toBe('Bearer the-publish-key')
    expect(screen.queryByRole('button', { name: '이어서 송출' })).not.toBeInTheDocument()
  })

  it('closes a resumed publication that connects after the 송출 screen was left', async () => {
    const late = { stop: vi.fn() }
    let connect: (session: media.Session) => void = () => {}
    const user = await startThenReload()
    server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(detail)))
    mocked.publish.mockReturnValue(new Promise((resolve) => (connect = resolve)))

    const page = renderAt('/broadcasts/new')
    await screen.findByTestId('camera-preview')
    await user.click(await screen.findByRole('button', { name: '이어서 송출' }))
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
    mocked.openCamera.mockResolvedValue(fakeStream)
    mocked.publish.mockResolvedValue({ stop: vi.fn() })
    server.use(http.post('/api/broadcasts', () => HttpResponse.json(started, { status: 201 })))
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
    const user = userEvent.setup()
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    server.use(
      http.get('/api/broadcasts/7', () =>
        HttpResponse.json({
          id: 7,
          title: '저녁 산책',
          status: 'LIVE',
          startedAt: '2026-11-15T10:00:00Z',
          endedAt: null,
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
    whepUrl: '/live/broadcasts/7/whep',
  }
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
