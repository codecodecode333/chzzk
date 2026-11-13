import { render, screen } from '@testing-library/react'
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

describe('VOD registration form validation', () => {
  function mp4(name = 'clip.mp4') {
    return new File([new Uint8Array([0, 0, 0, 0x18])], name, { type: 'video/mp4' })
  }

  it('shows a title error and blocks submit while the title is over 100 characters', async () => {
    const user = userEvent.setup()
    renderAt('/vods/new')

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.type(screen.getByLabelText('제목'), '가'.repeat(101))

    expect(screen.getByText('제목은 100자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()

    await user.type(screen.getByLabelText('제목'), '{Backspace}')

    expect(screen.queryByText('제목은 100자 이하로 입력해 주세요.')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeEnabled()
  })

  it('blocks submit on an untouched form without showing errors yet', () => {
    renderAt('/vods/new')

    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
    expect(screen.queryByText('제목을 입력해 주세요.')).not.toBeInTheDocument()
  })

  it('asks for a title when only whitespace was typed', async () => {
    const user = userEvent.setup()
    renderAt('/vods/new')

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.type(screen.getByLabelText('제목'), '   ')

    expect(screen.getByText('제목을 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('shows a description error and blocks submit while the description is over 2,000 characters', async () => {
    const user = userEvent.setup()
    renderAt('/vods/new')

    await user.upload(screen.getByLabelText('영상 파일'), mp4())
    await user.type(screen.getByLabelText('제목'), '제목')
    await user.click(screen.getByLabelText('설명'))
    await user.paste('a'.repeat(2001))

    expect(screen.getByText('설명은 2,000자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('rejects a file that is not an .mp4', async () => {
    const user = userEvent.setup({ applyAccept: false })
    renderAt('/vods/new')

    await user.type(screen.getByLabelText('제목'), '제목')
    await user.upload(screen.getByLabelText('영상 파일'), new File(['x'], 'clip.avi', { type: 'video/x-msvideo' }))

    expect(screen.getByText('MP4(.mp4) 파일만 등록할 수 있습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록' })).toBeDisabled()
  })

  it('rejects a file larger than 1GB', async () => {
    const user = userEvent.setup()
    renderAt('/vods/new')
    const huge = mp4('huge.mp4')
    Object.defineProperty(huge, 'size', { value: 1024 ** 3 + 1 })

    await user.type(screen.getByLabelText('제목'), '제목')
    await user.upload(screen.getByLabelText('영상 파일'), huge)

    expect(screen.getByText('영상 파일은 1GB 이하만 등록할 수 있습니다.')).toBeInTheDocument()
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
    renderAt('/vods/new')

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('MP4 형식의 영상 파일이 아닙니다.')
    expect(screen.getByLabelText('제목')).toHaveValue('내 다시보기')
    expect(screen.getByLabelText('설명')).toHaveValue('설명입니다')
  })

  it('shows a retry message when the upload fails without a problem body', async () => {
    const user = userEvent.setup()
    server.use(http.post('/api/vods', () => HttpResponse.error()))
    renderAt('/vods/new')

    await fillValidForm(user)
    await user.click(screen.getByRole('button', { name: '등록' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('잠시 후 다시 시도해 주세요.')
    expect(screen.getByLabelText('제목')).toHaveValue('내 다시보기')
  })
})
