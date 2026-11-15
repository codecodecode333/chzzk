import { render, screen } from '@testing-library/react'
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

  it('shows a not-found view for an unknown 방송', async () => {
    server.use(http.get('/api/broadcasts/404', () => HttpResponse.json({ status: 404 }, { status: 404 })))

    renderAt('/broadcasts/404')

    expect(await screen.findByRole('heading', { name: '방송을 찾을 수 없습니다' })).toBeInTheDocument()
    expect(mocked.watch).not.toHaveBeenCalled()
  })
})
