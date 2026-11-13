import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { server } from '../test/server'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

function pageOf(content: Array<{ id: number; title: string; createdAt: string }>) {
  return { content, page: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 }
}

describe('VOD list page', () => {
  it('shows registered VODs as cards with title and registration date', async () => {
    server.use(
      http.get('/api/vods', () =>
        HttpResponse.json(
          pageOf([
            { id: 2, title: '두 번째 다시보기', createdAt: '2026-11-12T10:00:00Z' },
            { id: 1, title: '첫 다시보기', createdAt: '2026-11-01T10:00:00Z' },
          ]),
        ),
      ),
    )

    renderAt('/')

    expect(screen.getByRole('heading', { name: '다시보기' })).toBeInTheDocument()
    const cards = await screen.findAllByRole('article')
    expect(cards).toHaveLength(2)
    expect(cards[0]).toHaveTextContent('두 번째 다시보기')
    expect(cards[0]).toHaveTextContent('2026. 11. 12.')
    expect(cards[1]).toHaveTextContent('첫 다시보기')
  })
})

describe('VOD registration page', () => {
  it('submits title, description and file, then shows the new VOD on the list', async () => {
    const user = userEvent.setup()
    let received: { title: unknown; description: unknown; fileName: unknown } | undefined
    const listed: Array<{ id: number; title: string; createdAt: string }> = []

    server.use(
      http.post('/api/vods', async ({ request }) => {
        const form = await request.formData()
        const file = form.get('file')
        received = {
          title: form.get('title'),
          description: form.get('description'),
          fileName: file instanceof Blob ? (file as File).name : file,
        }
        const vod = { id: 7, title: String(form.get('title')), createdAt: '2026-11-13T09:00:00Z' }
        listed.unshift(vod)
        return HttpResponse.json(
          { ...vod, description: form.get('description'), streamUrl: '/api/vods/7/stream' },
          { status: 201, headers: { Location: '/api/vods/7' } },
        )
      }),
      http.get('/api/vods', () => HttpResponse.json(pageOf(listed))),
    )

    renderAt('/vods/new')

    await user.type(screen.getByLabelText('제목'), '새 다시보기')
    await user.type(screen.getByLabelText('설명'), '설명입니다')
    const file = new File([new Uint8Array([0, 0, 0, 0x18])], 'clip.mp4', { type: 'video/mp4' })
    await user.upload(screen.getByLabelText('영상 파일'), file)
    await user.click(screen.getByRole('button', { name: '등록' }))

    const card = await screen.findByRole('article')
    expect(card).toHaveTextContent('새 다시보기')
    expect(received).toEqual({ title: '새 다시보기', description: '설명입니다', fileName: 'clip.mp4' })
  })
})

const detail = {
  id: 3,
  title: '상세 다시보기',
  description: '상세 설명',
  createdAt: '2026-11-10T10:00:00Z',
  streamUrl: '/api/vods/3/stream',
}

describe('VOD detail page', () => {
  it('opens from a list card and plays the stream with title, description and date', async () => {
    const user = userEvent.setup()
    server.use(
      http.get('/api/vods', () => HttpResponse.json(pageOf([detail]))),
      http.get('/api/vods/3', () => HttpResponse.json(detail)),
    )

    renderAt('/')
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
          { type: 'about:blank', title: 'Not Found', status: 404, detail: 'VOD 404 not found' },
          { status: 404, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderAt('/vods/404')

    expect(await screen.findByRole('heading', { name: '다시보기를 찾을 수 없습니다' })).toBeInTheDocument()
    expect(screen.queryByTestId('vod-player')).not.toBeInTheDocument()
  })

  it('replaces the player with an error message when the video fails to play', async () => {
    server.use(http.get('/api/vods/3', () => HttpResponse.json(detail)))

    renderAt('/vods/3')
    fireEvent.error(await screen.findByTestId('vod-player'))

    expect(await screen.findByText('영상을 재생할 수 없습니다')).toBeInTheDocument()
    expect(screen.queryByTestId('vod-player')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '상세 다시보기' })).toBeInTheDocument()
  })
})
