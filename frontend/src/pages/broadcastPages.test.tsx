import { act, render, screen } from '@testing-library/react'
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
      expect(screen.getByText(/종료 .*오후 7:42/)).toBeInTheDocument()
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
