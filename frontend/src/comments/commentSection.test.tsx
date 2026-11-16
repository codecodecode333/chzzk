import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { loggedInAs, server } from '../test/server'

/** The 댓글 section, through the VOD detail page it is embedded in (the same component serves 방송). */

const ME = { id: 7, nickname: '나회원' }
const OTHER = { id: 8, nickname: '남회원' }

interface ApiComment {
  id: number
  content: string
  author: { id: number; nickname: string }
  createdAt: string
  edited: boolean
}

function comment(id: number, content: string, author = OTHER, edited = false): ApiComment {
  return { id, content, author, createdAt: '2026-11-15T10:00:00Z', edited }
}

function renderVod(path = '/vods/1') {
  server.use(
    http.get('/api/vods/1', () =>
      HttpResponse.json({
        id: 1,
        title: '댓글 달린 다시보기',
        description: null,
        createdAt: '2026-11-15T09:00:00Z',
        streamUrl: '/api/vods/1/stream',
      }),
    ),
  )
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

/** Serves `/api/vods/1/comments` from `comments` (newest first), paging by `before` like the server. */
function commentsServer(initial: ApiComment[], pageSize = 20) {
  const comments = [...initial]
  server.use(
    http.get('/api/vods/1/comments', ({ request }) => {
      const before = new URL(request.url).searchParams.get('before')
      const older = comments.filter((c) => before === null || c.id < Number(before))
      const page = older.slice(0, pageSize)
      const nextBefore = older.length > pageSize ? page[page.length - 1].id : null
      return HttpResponse.json({ content: page, nextBefore })
    }),
  )
  return comments
}

async function commentSection() {
  return screen.findByRole('region', { name: '댓글' })
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('comment section', () => {
  it('shows 댓글 newest first with 닉네임, time and (수정됨), and asks visitors to log in', async () => {
    commentsServer([comment(2, '두 번째', OTHER, true), comment(1, '첫 번째')])

    renderVod()

    const section = await commentSection()
    const items = await within(section).findAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('두 번째')
    expect(items[0]).toHaveTextContent('남회원')
    expect(items[0]).toHaveTextContent('2026. 11. 15. 19:00')
    expect(items[0]).toHaveTextContent('(수정됨)')
    expect(items[1]).toHaveTextContent('첫 번째')
    expect(items[1]).not.toHaveTextContent('(수정됨)')

    const login = within(section).getByRole('link', { name: '로그인하고 댓글 달기' })
    expect(login).toHaveAttribute('href', '/login?next=%2Fvods%2F1')
    expect(within(section).queryByRole('textbox')).not.toBeInTheDocument()
    expect(within(section).queryByRole('button', { name: '수정' })).not.toBeInTheDocument()
  })

  it('shows an empty state when there are no 댓글', async () => {
    commentsServer([])

    renderVod()

    expect(await within(await commentSection()).findByText('첫 댓글을 남겨 보세요.')).toBeInTheDocument()
  })

  it('loads older 댓글 with 더 보기 until there are none', async () => {
    const user = userEvent.setup()
    commentsServer([comment(3, '셋'), comment(2, '둘'), comment(1, '하나')], 2)

    renderVod()

    const section = await commentSection()
    expect(await within(section).findByText('둘')).toBeInTheDocument()
    expect(within(section).queryByText('하나')).not.toBeInTheDocument()

    await user.click(within(section).getByRole('button', { name: '더 보기' }))

    expect(await within(section).findByText('하나')).toBeInTheDocument()
    expect(within(section).getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent)).toEqual([
      '셋',
      '둘',
      '하나',
    ])
    expect(within(section).queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })

  it('lets a 회원 write a 댓글 that appears at the top', async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([comment(1, '먼저 단 댓글')])
    const posted: unknown[] = []
    server.use(
      http.post('/api/vods/1/comments', async ({ request }) => {
        const body = (await request.json()) as { content: string }
        posted.push(body)
        return HttpResponse.json(comment(2, body.content.trim(), ME), { status: 201 })
      }),
    )

    renderVod()

    const section = await commentSection()
    const box = await within(section).findByRole('textbox', { name: '댓글 쓰기' })
    expect(within(section).queryByRole('link', { name: '로그인하고 댓글 달기' })).not.toBeInTheDocument()
    expect(within(section).getByRole('button', { name: '등록' })).toBeDisabled()

    await user.type(box, '  재밌어요 ')
    await user.click(within(section).getByRole('button', { name: '등록' }))

    await waitFor(() => expect(within(section).getAllByRole('listitem')).toHaveLength(2))
    expect(posted).toEqual([{ content: '  재밌어요 ' }])
    const items = within(section).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('재밌어요')
    expect(items[0]).toHaveTextContent('나회원')
    expect(box).toHaveValue('')
  })

  it("shows the server's message when a 댓글 is refused", async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([])
    server.use(
      http.post('/api/vods/1/comments', () =>
        HttpResponse.json(
          {
            type: 'about:blank',
            title: 'Bad Request',
            status: 400,
            detail: '댓글은 500자 이하로 입력해 주세요.',
            errors: [{ field: 'content', message: '댓글은 500자 이하로 입력해 주세요.' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    renderVod()

    const section = await commentSection()
    await user.type(await within(section).findByRole('textbox', { name: '댓글 쓰기' }), '안녕')
    await user.click(within(section).getByRole('button', { name: '등록' }))

    expect(await within(section).findByRole('alert')).toHaveTextContent('댓글은 500자 이하로 입력해 주세요.')
  })

  it('keeps 댓글 over 500 characters from being sent', async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([])
    let posts = 0
    server.use(
      http.post('/api/vods/1/comments', () => {
        posts++
        return HttpResponse.json(comment(9, 'x', ME), { status: 201 })
      }),
    )

    renderVod()

    const section = await commentSection()
    const box = await within(section).findByRole('textbox', { name: '댓글 쓰기' })
    await user.click(box)
    await user.paste('가'.repeat(501))
    await user.click(within(section).getByRole('button', { name: '등록' }))

    expect(await within(section).findByRole('alert')).toHaveTextContent('댓글은 500자 이하로 입력해 주세요.')
    expect(posts).toBe(0)
  })

  it('shows 수정 and 삭제 only on my own 댓글, and editing marks it (수정됨)', async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([comment(2, '남의 댓글', OTHER), comment(1, '오타 잇음', ME)])
    server.use(
      http.patch('/api/comments/1', async ({ request }) => {
        const { content } = (await request.json()) as { content: string }
        return HttpResponse.json(comment(1, content.trim(), ME, true))
      }),
    )

    renderVod()

    const section = await commentSection()
    const [others, mine] = await within(section).findAllByRole('listitem')
    expect(within(others).queryByRole('button', { name: '수정' })).not.toBeInTheDocument()
    expect(within(others).queryByRole('button', { name: '삭제' })).not.toBeInTheDocument()

    await user.click(within(mine).getByRole('button', { name: '수정' }))
    const editor = within(mine).getByRole('textbox', { name: '댓글 수정' })
    expect(editor).toHaveValue('오타 잇음')
    await user.clear(editor)
    await user.type(editor, '오타 있음')
    await user.click(within(mine).getByRole('button', { name: '저장' }))

    await waitFor(() => expect(mine).toHaveTextContent('(수정됨)'))
    expect(mine).toHaveTextContent('오타 있음')
    expect(within(mine).queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('cancelling an edit keeps the 댓글 as it was', async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([comment(1, '그대로', ME)])

    renderVod()

    const mine = await within(await commentSection()).findByRole('listitem')
    await user.click(within(mine).getByRole('button', { name: '수정' }))
    await user.type(within(mine).getByRole('textbox', { name: '댓글 수정' }), ' 바꿈')
    await user.click(within(mine).getByRole('button', { name: '취소' }))

    expect(mine).toHaveTextContent('그대로')
    expect(mine).not.toHaveTextContent('바꿈')
    expect(within(mine).queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('deletes my 댓글 only after confirmation', async () => {
    const user = userEvent.setup()
    loggedInAs(ME)
    commentsServer([comment(1, '지울 댓글', ME)])
    let deletes = 0
    server.use(
      http.delete('/api/comments/1', () => {
        deletes++
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const confirm = vi.fn<(message?: string) => boolean>()
    vi.stubGlobal('confirm', confirm)

    renderVod()

    const section = await commentSection()
    const mine = await within(section).findByRole('listitem')

    confirm.mockReturnValueOnce(false)
    await user.click(within(mine).getByRole('button', { name: '삭제' }))
    expect(confirm).toHaveBeenCalledWith('댓글을 삭제할까요?')
    expect(deletes).toBe(0)
    expect(within(section).getByText('지울 댓글')).toBeInTheDocument()

    confirm.mockReturnValueOnce(true)
    await user.click(within(mine).getByRole('button', { name: '삭제' }))

    await waitFor(() => expect(within(section).queryByText('지울 댓글')).not.toBeInTheDocument())
    expect(deletes).toBe(1)
  })
})
