import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import * as media from './media/media'
import { BACKEND, server } from './test/server'

interface Card {
  id: number
  title: string
  status: 'LIVE' | 'ENDED'
  startedAt: string
  endedAt: string | null
  owner: { id: number; nickname: string }
}

const OWNER = { id: 9, nickname: '방송인' }

function live(id: number, title: string): Card {
  return { id, title, status: 'LIVE', startedAt: '2026-11-15T10:00:00Z', endedAt: null, owner: OWNER }
}

function ended(id: number, title: string): Card {
  return { id, title, status: 'ENDED', startedAt: '2026-11-15T09:00:00Z', endedAt: '2026-11-15T09:30:00Z', owner: OWNER }
}

function pageOf(content: Card[]) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

/** The backend's 방송 list API, one (mutable) list per status. */
function serveBroadcasts(byStatus: { LIVE: Card[]; ENDED: Card[] }) {
  server.use(
    http.get(`${BACKEND}/api/broadcasts`, ({ request }) => {
      const status = new URL(request.url).searchParams.get('status') as 'LIVE' | 'ENDED'
      return HttpResponse.json(pageOf(byStatus[status]))
    }),
  )
}

// The app reaches WebRTC only through the media module, so it is faked whole.
vi.mock('./media/media', async (importOriginal) => {
  const actual = await importOriginal<typeof media>()
  return { MediaError: actual.MediaError, watch: vi.fn() }
})

const mocked = vi.mocked(media)

/** Stands in for a received stream; the app only hands it to a `<video>`. */
let fakeStream: MediaStream

beforeEach(() => {
  fakeStream = new MediaStream()
  mocked.watch.mockReset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('방송 목록', () => {
  it('shows 방송 중 broadcasts first and 종료 ones below, marked 종료 and not playable', async () => {
    serveBroadcasts({ LIVE: [live(3, '지금 방송')], ENDED: [ended(1, '끝난 방송')] })

    render(<App backendUrl={BACKEND} />)

    const liveSection = await screen.findByRole('region', { name: '방송 중' })
    const endedSection = screen.getByRole('region', { name: '종료된 방송' })
    expect(liveSection.compareDocumentPosition(endedSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    const liveCard = await within(liveSection).findByRole('button', { name: /지금 방송/ })
    expect(liveCard).toHaveTextContent('방송 중')
    expect(liveCard).toHaveTextContent('방송인')

    const endedItem = await within(endedSection).findByText('끝난 방송')
    const endedCard = endedItem.closest('li')!
    expect(endedCard).toHaveTextContent('종료')
    expect(endedCard).not.toHaveTextContent('방송 중')
    expect(within(endedCard).queryByRole('button')).toBeNull()
  })

  it('refreshes by itself every 10 seconds, picking up newly started and newly ended 방송', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const lists = { LIVE: [live(5, '곧 끝날 방송')], ENDED: [] as Card[] }
    serveBroadcasts(lists)

    render(<App backendUrl={BACKEND} />)

    const liveSection = await screen.findByRole('region', { name: '방송 중' })
    expect(await within(liveSection).findByText('곧 끝날 방송')).toBeInTheDocument()

    lists.LIVE = [live(6, '새로 시작한 방송')]
    lists.ENDED = [ended(5, '곧 끝날 방송')]
    await vi.advanceTimersByTimeAsync(9_000)
    expect(within(liveSection).getByText('곧 끝날 방송')).toBeInTheDocument()
    await vi.advanceTimersByTimeAsync(1_000)

    expect(await within(liveSection).findByText('새로 시작한 방송')).toBeInTheDocument()
    expect(within(liveSection).queryByText('곧 끝날 방송')).not.toBeInTheDocument()
    const endedSection = screen.getByRole('region', { name: '종료된 방송' })
    expect(within(endedSection).getByText('곧 끝날 방송').closest('li')).toHaveTextContent('종료')
  })

  it('says the server cannot be reached while the backend is down, and recovers on the next refresh', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    server.use(http.get(`${BACKEND}/api/broadcasts`, () => HttpResponse.error()))

    render(<App backendUrl={BACKEND} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
    expect(screen.getByRole('alert')).toHaveTextContent(BACKEND)

    serveBroadcasts({ LIVE: [live(3, '지금 방송')], ENDED: [] })
    await vi.advanceTimersByTimeAsync(10_000)

    expect(await screen.findByRole('button', { name: /지금 방송/ })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    // A later failed refresh keeps the last list on screen and says so.
    server.use(http.get(`${BACKEND}/api/broadcasts`, () => new HttpResponse(null, { status: 502 })))
    await vi.advanceTimersByTimeAsync(10_000)

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
    expect(screen.getByRole('button', { name: /지금 방송/ })).toBeInTheDocument()
  })
})

describe('방송 시청', () => {
  const LIVE_DETAIL = { ...live(7, '저녁 산책'), whepUrl: '/live/broadcast-7/whep' }
  const ENDED_DETAIL = { ...LIVE_DETAIL, status: 'ENDED', endedAt: '2026-11-15T10:42:00Z', whepUrl: null }

  /** The 방송 list with 방송 7 방송 중, and 방송 7's own status from `current()`. */
  function serveLive7(current: () => object) {
    serveBroadcasts({ LIVE: [live(7, '저녁 산책')], ENDED: [] })
    server.use(http.get(`${BACKEND}/api/broadcasts/7`, () => HttpResponse.json(current())))
  }

  it('opens a 방송 중 방송 from the list and plays it over WHEP from the backend', async () => {
    const stop = vi.fn()
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop })
    serveLive7(() => LIVE_DETAIL)
    const user = userEvent.setup()

    render(<App backendUrl={BACKEND} />)
    await user.click(await screen.findByRole('button', { name: /저녁 산책/ }))

    expect(await screen.findByRole('heading', { name: '저녁 산책' })).toBeInTheDocument()
    expect(screen.getByText('방송인')).toBeInTheDocument()
    await screen.findByRole('button', { name: '소리 켜기' })
    expect(mocked.watch).toHaveBeenCalledWith(`${BACKEND}/live/broadcast-7/whep`, expect.anything())
    expect((screen.getByTestId('live-player') as HTMLVideoElement).srcObject).toBe(fakeStream)

    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(await screen.findByRole('region', { name: '방송 중' })).toBeInTheDocument()
    expect(stop).toHaveBeenCalled()
  })

  it('stops playback and says the 방송 has ended when the 5-second status check finds it 종료', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const stop = vi.fn()
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop })
    let current: object = LIVE_DETAIL
    serveLive7(() => current)
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    render(<App backendUrl={BACKEND} />)
    await user.click(await screen.findByRole('button', { name: /저녁 산책/ }))
    await screen.findByRole('button', { name: '소리 켜기' })

    current = ENDED_DETAIL
    await act(() => vi.advanceTimersByTimeAsync(5000))

    expect(await screen.findByText('방송이 종료되었습니다')).toBeInTheDocument()
    expect(screen.queryByTestId('live-player')).not.toBeInTheDocument()
    expect(stop).toHaveBeenCalled()
    expect(screen.getByRole('heading', { name: '저녁 산책' }).parentElement).toHaveTextContent('종료')
  })

  it('checks the status right away when the connection drops', async () => {
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    let current: object = LIVE_DETAIL
    serveLive7(() => current)
    const user = userEvent.setup()

    render(<App backendUrl={BACKEND} />)
    await user.click(await screen.findByRole('button', { name: /저녁 산책/ }))
    await screen.findByRole('button', { name: '소리 켜기' })
    const { onDisconnect } = mocked.watch.mock.calls[0][1]!

    current = ENDED_DETAIL
    act(() => onDisconnect?.())

    expect(await screen.findByText('방송이 종료되었습니다')).toBeInTheDocument()
  })

  it('says the server cannot be reached when the 방송 cannot be loaded, and plays once it can', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: fakeStream, stop: vi.fn() })
    let reachable = false
    serveBroadcasts({ LIVE: [live(7, '저녁 산책')], ENDED: [] })
    server.use(
      http.get(`${BACKEND}/api/broadcasts/7`, () => (reachable ? HttpResponse.json(LIVE_DETAIL) : HttpResponse.error())),
    )
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })

    render(<App backendUrl={BACKEND} />)
    await user.click(await screen.findByRole('button', { name: /저녁 산책/ }))

    expect(await screen.findByRole('alert')).toHaveTextContent('서버에 연결할 수 없습니다')
    expect(mocked.watch).not.toHaveBeenCalled()

    reachable = true
    await act(() => vi.advanceTimersByTimeAsync(5000))

    expect(await screen.findByRole('button', { name: '소리 켜기' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('says so when the 방송 no longer exists', async () => {
    serveBroadcasts({ LIVE: [live(7, '저녁 산책')], ENDED: [] })
    server.use(http.get(`${BACKEND}/api/broadcasts/7`, () => new HttpResponse(null, { status: 404 })))
    const user = userEvent.setup()

    render(<App backendUrl={BACKEND} />)
    await user.click(await screen.findByRole('button', { name: /저녁 산책/ }))

    expect(await screen.findByText('방송을 찾을 수 없습니다')).toBeInTheDocument()
    expect(mocked.watch).not.toHaveBeenCalled()
  })
})
