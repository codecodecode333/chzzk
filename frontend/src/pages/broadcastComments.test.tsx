import { act, render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import * as media from '../media/media'
import { server } from '../test/server'

/** 방송 댓글 on the 시청 screen: the shared 댓글 section, refreshed every 5s while the 방송 is 방송 중. */

vi.mock('../media/media', async (importOriginal) => {
  const actual = await importOriginal<typeof media>()
  return { ...actual, watch: vi.fn() }
})
const mocked = vi.mocked(media)

const OWNER = { id: 1, nickname: '방송인' }
const VIEWER = { id: 8, nickname: '시청자' }

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

function comment(id: number, content: string) {
  return { id, content, author: VIEWER, createdAt: '2026-11-15T10:05:00Z', edited: false }
}
type ApiComment = ReturnType<typeof comment>

/**
 * Serves 방송 7 (whatever `state.broadcast` is) and its 댓글 from `state.comments` (newest first), paging by
 * `before` like the server; counts the 댓글 list requests.
 */
function broadcastServer(broadcast: object, comments: ApiComment[], pageSize = 20) {
  const state = { broadcast, comments, commentRequests: 0 }
  server.use(
    http.get('/api/broadcasts/7', () => HttpResponse.json(state.broadcast)),
    http.get('/api/broadcasts/7/comments', ({ request }) => {
      state.commentRequests++
      const before = new URL(request.url).searchParams.get('before')
      const older = state.comments.filter((c) => before === null || c.id < Number(before))
      const page = older.slice(0, pageSize)
      const nextBefore = older.length > pageSize ? page[page.length - 1].id : null
      return HttpResponse.json({ content: page, nextBefore })
    }),
  )
  return state
}

function renderWatch() {
  return render(
    <MemoryRouter initialEntries={['/broadcasts/7']}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

async function shownComments() {
  const section = await screen.findByRole('region', { name: '댓글' })
  return within(section)
    .queryAllByRole('listitem')
    .map((item) => item.querySelector('p')?.textContent)
}

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('방송 댓글 on the 시청 screen', () => {
  it('picks up new 댓글 every 5s while 방송 중, on top of the loaded older pages, without duplicates', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    const state = broadcastServer(live, [comment(3, '셋째'), comment(2, '둘째'), comment(1, '첫째')], 2)

    renderWatch()
    const section = await screen.findByRole('region', { name: '댓글' })
    await within(section).findByText('셋째')
    await act(async () => within(section).getByRole('button', { name: '더 보기' }).click())
    await within(section).findByText('첫째')
    expect(await shownComments()).toEqual(['셋째', '둘째', '첫째'])

    state.comments = [comment(5, '다섯째'), comment(4, '넷째'), ...state.comments]
    await act(() => vi.advanceTimersByTimeAsync(5000))

    expect(await within(section).findByText('다섯째')).toBeInTheDocument()
    expect(await shownComments()).toEqual(['다섯째', '넷째', '셋째', '둘째', '첫째'])

    // a poll with nothing new changes nothing
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(await shownComments()).toEqual(['다섯째', '넷째', '셋째', '둘째', '첫째'])
  })

  it('stops refreshing 댓글 once the 방송 ends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    const state = broadcastServer(live, [comment(1, '첫째')])

    renderWatch()
    await screen.findByText('첫째')
    state.broadcast = ended
    await act(() => vi.advanceTimersByTimeAsync(5000))
    await screen.findByText('종료된 방송입니다')

    const requestsWhenEnded = state.commentRequests
    state.comments = [comment(2, '늦은 댓글'), ...state.comments]
    await act(() => vi.advanceTimersByTimeAsync(20000))

    expect(state.commentRequests).toBe(requestsWhenEnded)
    expect(screen.queryByText('늦은 댓글')).not.toBeInTheDocument()
  })

  it('shows the 댓글 of an ended 방송 without polling, and asks visitors to log in', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const state = broadcastServer(ended, [comment(2, '잘 봤어요'), comment(1, '첫째')])

    renderWatch()
    await screen.findByText('잘 봤어요')
    expect(await shownComments()).toEqual(['잘 봤어요', '첫째'])
    const section = screen.getByRole('region', { name: '댓글' })
    expect(await within(section).findByRole('link', { name: '로그인하고 댓글 달기' })).toHaveAttribute(
      'href',
      '/login?next=%2Fbroadcasts%2F7',
    )
    expect(within(section).queryByRole('textbox', { name: '댓글 쓰기' })).not.toBeInTheDocument()

    state.comments = [comment(3, '새 댓글'), ...state.comments]
    await act(() => vi.advanceTimersByTimeAsync(20000))

    expect(state.commentRequests).toBe(1)
    expect(screen.queryByText('새 댓글')).not.toBeInTheDocument()
  })
})
