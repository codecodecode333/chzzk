import { act, render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import * as media from '../media/media'
import { loggedInAs, server } from '../test/server'

/** The 후원 panel on the 시청 screen: the 후원 합계 and the confirmed 후원 내역, refreshed every 5s while 방송 중. */

vi.mock('../media/media', async (importOriginal) => {
  const actual = await importOriginal<typeof media>()
  return { ...actual, watch: vi.fn() }
})
const mocked = vi.mocked(media)

const OWNER = { id: 1, nickname: '방송인' }
const DONOR = { id: 8, nickname: '후원자' }

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

/** A confirmed 후원; `seq` (its place in confirmation order) defaults to its id, as when orders are paid in order. */
function donation(id: number, amount: number, message: string | null, seq = id) {
  return { id, seq, donor: DONOR, amount, message, confirmedAt: '2026-11-15T10:10:00Z' }
}
type ApiDonation = ReturnType<typeof donation>

/**
 * Serves 방송 7 (whatever `state.broadcast` is) and its confirmed 후원 from `state.donations` (newest confirmed first),
 * paging by `before` and `after` on `seq` like the server, with the 합계 of all of them; counts the 후원 list requests.
 */
function broadcastServer(broadcast: object, donations: ApiDonation[], pageSize = 20) {
  const state = { broadcast, donations, donationRequests: 0 }
  server.use(
    http.get('/api/broadcasts/7', () => HttpResponse.json(state.broadcast)),
    http.get('/api/broadcasts/7/donations', ({ request }) => {
      state.donationRequests++
      const total = state.donations.reduce((sum, d) => sum + d.amount, 0)
      const params = new URL(request.url).searchParams
      const after = params.get('after')
      if (after !== null) {
        const newer = state.donations.filter((d) => d.seq > Number(after)).reverse() // oldest first
        const page = newer.slice(0, pageSize)
        const nextAfter = newer.length > pageSize ? page[page.length - 1].seq : null
        return HttpResponse.json({ content: page.reverse(), nextBefore: null, nextAfter, total })
      }
      const before = params.get('before')
      const older = state.donations.filter((d) => before === null || d.seq < Number(before))
      const page = older.slice(0, pageSize)
      const nextBefore = older.length > pageSize ? page[page.length - 1].seq : null
      return HttpResponse.json({ content: page, nextBefore, nextAfter: null, total })
    }),
  )
  return state
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

function panel() {
  return screen.findByRole('region', { name: '후원' })
}

/** Each shown 후원 as "닉네임 금액 메시지" (no message: "닉네임 금액"). */
async function shownDonations() {
  return within(await panel())
    .queryAllByRole('listitem')
    .map((item) =>
      ['nickname', 'amount', 'message']
        .map((field) => item.querySelector(`[data-field="${field}"]`)?.textContent)
        .filter(Boolean)
        .join(' '),
    )
}

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('후원 panel on the 시청 screen', () => {
  it('shows the 합계 and the 내역 newest first, with "더 보기" for older 후원', async () => {
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    broadcastServer(live, [donation(3, 50_000, '최고'), donation(2, 5_000, null), donation(1, 1_000, '첫 후원')], 2)

    renderAt('/broadcasts/7')
    const section = await panel()

    expect(await within(section).findByText('후원 합계 56,000원')).toBeInTheDocument()
    expect(await shownDonations()).toEqual(['후원자 50,000원 최고', '후원자 5,000원'])
    expect(within(section).getAllByText('2026. 11. 15. 19:10')).toHaveLength(2)

    await act(async () => within(section).getByRole('button', { name: '더 보기' }).click())

    await within(section).findByText('첫 후원')
    expect(await shownDonations()).toEqual(['후원자 50,000원 최고', '후원자 5,000원', '후원자 1,000원 첫 후원'])
    expect(within(section).queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('says there are no 후원 yet', async () => {
    broadcastServer(ended, [])

    renderAt('/broadcasts/7')

    expect(await within(await panel()).findByText('후원 합계 0원')).toBeInTheDocument()
    expect(within(await panel()).getByText('아직 후원이 없습니다.')).toBeInTheDocument()
  })

  it('picks up new 후원 and the 합계 every 5s while 방송 중, keeping loaded pages, without duplicates', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    const state = broadcastServer(
      live,
      [donation(3, 3_000, '셋'), donation(2, 2_000, '둘'), donation(1, 1_000, '하나')],
      2,
    )

    renderAt('/broadcasts/7')
    const section = await panel()
    await within(section).findByText('셋')
    await act(async () => within(section).getByRole('button', { name: '더 보기' }).click())
    await within(section).findByText('하나')

    state.donations = [donation(5, 50_000, '다섯'), donation(4, 4_000, '넷'), ...state.donations]
    await act(() => vi.advanceTimersByTimeAsync(5000))

    expect(await within(section).findByText('다섯')).toBeInTheDocument()
    expect(within(section).getByText('후원 합계 60,000원')).toBeInTheDocument()
    expect(await shownDonations()).toEqual([
      '후원자 50,000원 다섯',
      '후원자 4,000원 넷',
      '후원자 3,000원 셋',
      '후원자 2,000원 둘',
      '후원자 1,000원 하나',
    ])

    // a poll with nothing new changes nothing
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(await shownDonations()).toHaveLength(5)
  })

  it('picks up a 후원 whose order is older but was confirmed later, on top', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    // order 50 was paid first (seq 2); order 3, made earlier, is paid only now (seq 7)
    const state = broadcastServer(live, [donation(50, 5_000, '먼저 확정', 2)])

    renderAt('/broadcasts/7')
    const section = await panel()
    await within(section).findByText('먼저 확정')

    state.donations = [donation(3, 3_000, '나중에 확정', 7), ...state.donations]
    await act(() => vi.advanceTimersByTimeAsync(5000))

    expect(await within(section).findByText('나중에 확정')).toBeInTheDocument()
    expect(await shownDonations()).toEqual(['후원자 3,000원 나중에 확정', '후원자 5,000원 먼저 확정'])
    expect(within(section).getByText('후원 합계 8,000원')).toBeInTheDocument()
  })

  it('stops refreshing once the 방송 ends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    const state = broadcastServer(live, [donation(1, 1_000, '하나')])

    renderAt('/broadcasts/7')
    await within(await panel()).findByText('하나')
    state.broadcast = ended
    await act(() => vi.advanceTimersByTimeAsync(5000))
    await screen.findByText('종료된 방송입니다')

    const requestsWhenEnded = state.donationRequests
    state.donations = [donation(2, 2_000, '늦은 후원'), ...state.donations]
    await act(() => vi.advanceTimersByTimeAsync(20000))

    expect(state.donationRequests).toBe(requestsWhenEnded)
    expect(screen.queryByText('늦은 후원')).not.toBeInTheDocument()
  })

  it('shows an ended 방송’s 후원 without polling', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const state = broadcastServer(ended, [donation(1, 1_000, '잘 봤어요')])

    renderAt('/broadcasts/7')
    await within(await panel()).findByText('잘 봤어요')
    expect(within(await panel()).getByText('후원 합계 1,000원')).toBeInTheDocument()

    state.donations = [donation(2, 2_000, '새 후원'), ...state.donations]
    await act(() => vi.advanceTimersByTimeAsync(20000))

    expect(state.donationRequests).toBe(1)
    expect(screen.queryByText('새 후원')).not.toBeInTheDocument()
  })

  it('shows the new 후원 after returning to the 방송 from 후원 완료', async () => {
    mocked.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
    loggedInAs(DONOR)
    const state = broadcastServer(live, [])
    const orderId = '0b6f5f3e-2f0c-4a43-9b8e-3c1f9d2a7e10'
    server.use(
      http.post('/api/donations/confirm', () => {
        state.donations = [donation(31, 10_000, '응원해요')]
        return HttpResponse.json({
          ...donation(31, 10_000, '응원해요'),
          orderId,
          broadcastId: 7,
          status: 'CONFIRMED',
          recipient: OWNER,
        })
      }),
    )

    renderAt(`/donations/success?paymentType=NORMAL&orderId=${orderId}&paymentKey=tgen_1&amount=10000`)
    const back = await screen.findByRole('link', { name: '방송으로 돌아가기' })
    await act(async () => back.click())

    expect(await within(await panel()).findByText('응원해요')).toBeInTheDocument()
    expect(within(await panel()).getByText('후원 합계 10,000원')).toBeInTheDocument()
  })
})
