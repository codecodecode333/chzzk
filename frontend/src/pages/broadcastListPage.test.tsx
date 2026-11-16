import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { server } from '../test/server'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

interface Card {
  id: number
  title: string
  status: 'LIVE' | 'ENDED'
  startedAt: string
  endedAt: string | null
  owner: { id: number; nickname: string }
}

const OWNER = { id: 9, nickname: '방송인' }

function live(id: number, title: string, startedAt = '2026-11-15T10:00:00Z'): Card {
  return { id, title, status: 'LIVE', startedAt, endedAt: null, owner: OWNER }
}

function ended(id: number, title: string, endedAt = '2026-11-15T09:30:00Z'): Card {
  return { id, title, status: 'ENDED', startedAt: '2026-11-15T09:00:00Z', endedAt, owner: OWNER }
}

function pageOf(content: Card[], page = 0, totalElements = content.length) {
  return { content, page, size: 20, totalElements, totalPages: Math.ceil(totalElements / 20) }
}

/** The 방송 list API as the server answers it: one handler per status, each from its own (mutable) list. */
function serveBroadcasts(byStatus: { LIVE: Card[]; ENDED: Card[] }) {
  const requests: string[] = []
  server.use(
    http.get('/api/broadcasts', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params.toString())
      const status = params.get('status') as 'LIVE' | 'ENDED'
      return HttpResponse.json(pageOf(byStatus[status], Number(params.get('page') ?? 0)))
    }),
  )
  return requests
}

afterEach(() => {
  vi.useRealTimers()
})

describe('방송 목록 home', () => {
  it('lists 방송 중 broadcasts above 종료 ones, each with its badge and times', async () => {
    serveBroadcasts({
      LIVE: [live(3, '지금 방송', '2026-11-15T10:00:00Z')],
      ENDED: [ended(1, '끝난 방송', '2026-11-15T09:30:00Z')],
    })

    renderAt('/')

    const liveSection = await screen.findByRole('region', { name: '방송 중' })
    const endedSection = screen.getByRole('region', { name: '종료된 방송' })
    expect(liveSection.compareDocumentPosition(endedSection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()

    const liveCard = await within(liveSection).findByRole('link', { name: /지금 방송/ })
    expect(liveCard).toHaveAttribute('href', '/broadcasts/3')
    expect(liveCard).toHaveTextContent('방송 중')
    expect(liveCard).toHaveTextContent('19:00') // started 10:00Z = 19:00 KST
    expect(liveCard).toHaveTextContent('방송인')

    const endedCard = await within(endedSection).findByRole('link', { name: /끝난 방송/ })
    expect(endedCard).toHaveAttribute('href', '/broadcasts/1')
    expect(endedCard).toHaveTextContent('종료')
    expect(endedCard).not.toHaveTextContent('방송 중')
    expect(endedCard).toHaveTextContent(/18:00.*시작/) // started 09:00Z = 18:00 KST
    expect(endedCard).toHaveTextContent(/18:30.*종료/) // ended 09:30Z = 18:30 KST
  })

  it('shows a friendly empty state with a way to start a 방송 when there are no 방송 at all', async () => {
    serveBroadcasts({ LIVE: [], ENDED: [] })

    renderAt('/')

    expect(await screen.findByText(/아직 방송이 없습니다/)).toBeInTheDocument()
    const main = screen.getByRole('main')
    expect(within(main).getByRole('link', { name: '방송 시작' })).toHaveAttribute('href', '/broadcasts/new')
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
  })

  it('says nobody is live when only 종료 broadcasts exist', async () => {
    serveBroadcasts({ LIVE: [], ENDED: [ended(1, '끝난 방송')] })

    renderAt('/')

    const liveSection = await screen.findByRole('region', { name: '방송 중' })
    expect(within(liveSection).getByText('지금 방송 중인 방송이 없습니다')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '종료된 방송' })).getByRole('article')).toHaveTextContent(
      '끝난 방송',
    )
  })

  it('reads the 종료 page from the URL and pages through ended broadcasts', async () => {
    const user = userEvent.setup()
    const requested: string[] = []
    server.use(
      http.get('/api/broadcasts', ({ request }) => {
        const params = new URL(request.url).searchParams
        if (params.get('status') === 'LIVE') return HttpResponse.json(pageOf([]))
        const page = Number(params.get('page'))
        requested.push(String(page))
        return HttpResponse.json(pageOf([ended(100 - page, `${page + 1}쪽 방송`)], page, 45))
      }),
    )

    renderAt('/?page=2')

    expect(await screen.findByText('2쪽 방송')).toBeInTheDocument()
    expect(requested).toEqual(['1'])
    const pager = screen.getByRole('navigation', { name: '페이지' })
    expect(within(pager).getByRole('link', { name: '2' })).toHaveAttribute('aria-current', 'page')

    await user.click(within(pager).getByRole('link', { name: '다음' }))

    expect(await screen.findByText('3쪽 방송')).toBeInTheDocument()
    expect(within(pager).getByRole('link', { name: '3' })).toHaveAttribute('href', '/?page=3')
    expect(within(pager).getByRole('link', { name: '3' })).toHaveAttribute('aria-current', 'page')
  })

  it('refreshes every 10 seconds, picking up newly started and newly ended 방송 without a reload', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const lists = { LIVE: [live(5, '곧 끝날 방송')], ENDED: [] as Card[] }
    serveBroadcasts(lists)

    renderAt('/')

    const liveSection = await screen.findByRole('region', { name: '방송 중' })
    expect(await within(liveSection).findByText('곧 끝날 방송')).toBeInTheDocument()

    // Meanwhile the server moves on: 5 ends and 6 starts.
    lists.LIVE = [live(6, '새로 시작한 방송', '2026-11-15T10:05:00Z')]
    lists.ENDED = [ended(5, '곧 끝날 방송', '2026-11-15T10:04:00Z')]
    await vi.advanceTimersByTimeAsync(9_000)
    expect(within(liveSection).getByText('곧 끝날 방송')).toBeInTheDocument()
    await vi.advanceTimersByTimeAsync(1_000)

    expect(await within(liveSection).findByText('새로 시작한 방송')).toBeInTheDocument()
    expect(within(liveSection).queryByText('곧 끝날 방송')).not.toBeInTheDocument()
    const endedSection = screen.getByRole('region', { name: '종료된 방송' })
    expect(within(endedSection).getByRole('link', { name: /곧 끝날 방송/ })).toHaveTextContent('종료')
  })

  it('shows an error message instead of a blank page when the list cannot be loaded', async () => {
    server.use(http.get('/api/broadcasts', () => HttpResponse.error()))

    renderAt('/')

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
  })
})

describe('navigation', () => {
  it('links to 라이브 and 다시보기, and to starting a 방송 and registering a VOD', async () => {
    serveBroadcasts({ LIVE: [], ENDED: [] })

    renderAt('/')

    const header = screen.getByRole('banner')
    expect(within(header).getByRole('link', { name: '라이브' })).toHaveAttribute('href', '/')
    expect(within(header).getByRole('link', { name: '다시보기' })).toHaveAttribute('href', '/vods')
    expect(within(header).getByRole('link', { name: '방송 시작' })).toHaveAttribute('href', '/broadcasts/new')
    expect(within(header).getByRole('link', { name: '다시보기 등록' })).toHaveAttribute('href', '/vods/new')
    await screen.findByText(/아직 방송이 없습니다/)
  })
})
