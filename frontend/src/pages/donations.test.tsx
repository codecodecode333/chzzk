import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import * as media from '../media/media'
import * as payments from '../payments/payments'
import { CSRF_TOKEN, loggedInAs, server } from '../test/server'

/** 후원: the 후원하기 entry point on the 시청 screen, the 후원 modal, and the success and fail pages. */

vi.mock('../media/media', async (importOriginal) => {
  const actual = await importOriginal<typeof media>()
  return { ...actual, watch: vi.fn() }
})
vi.mock('../payments/payments', async (importOriginal) => {
  const actual = await importOriginal<typeof payments>()
  return { ...actual, requestPayment: vi.fn() }
})
const mockedMedia = vi.mocked(media)
const mockedPayments = vi.mocked(payments)

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

const ORDER_ID = '0b6f5f3e-2f0c-4a43-9b8e-3c1f9d2a7e10'

function order(amount: number) {
  return {
    orderId: ORDER_ID,
    orderName: `후원 ${amount.toLocaleString('ko-KR')}원`,
    amount,
    clientKey: 'test_ck_public',
    successUrl: '/donations/success',
    failUrl: `/donations/fail?orderId=${ORDER_ID}&broadcastId=7`,
    recipient: OWNER,
  }
}

function confirmed(orderId: string, amount: number) {
  return {
    id: 31,
    orderId,
    broadcastId: 7,
    amount,
    message: '응원해요',
    status: 'CONFIRMED',
    donor: VIEWER,
    recipient: OWNER,
    confirmedAt: '2026-11-15T10:10:00Z',
  }
}

function serveBroadcast(broadcast: object) {
  server.use(http.get('/api/broadcasts/7', () => HttpResponse.json(broadcast)))
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  mockedMedia.watch.mockResolvedValue({ stream: new MediaStream(), stop: vi.fn() })
})

afterEach(() => {
  vi.clearAllMocks()
})

describe('후원하기 on the 시청 screen', () => {
  it('offers 후원하기 to a logged-in 회원 on someone else’s 방송 중 방송', async () => {
    loggedInAs(VIEWER)
    serveBroadcast(live)

    renderAt('/broadcasts/7')

    expect(await screen.findByRole('button', { name: '후원하기' })).toBeInTheDocument()
  })

  it('asks a visitor to log in first and come back to the 방송', async () => {
    serveBroadcast(live)

    renderAt('/broadcasts/7')

    const link = await screen.findByRole('link', { name: '로그인하고 후원하기' })
    expect(link).toHaveAttribute('href', '/login?next=%2Fbroadcasts%2F7')
    expect(screen.queryByRole('button', { name: '후원하기' })).not.toBeInTheDocument()
  })

  it('shows nothing to the 소유자', async () => {
    loggedInAs(OWNER)
    serveBroadcast(live)

    renderAt('/broadcasts/7')

    await screen.findByRole('button', { name: '제목 수정' })
    expect(screen.queryByRole('button', { name: '후원하기' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '로그인하고 후원하기' })).not.toBeInTheDocument()
  })

  it('shows nothing on a 종료 방송, to a 회원 or a visitor', async () => {
    for (const who of [VIEWER, null]) {
      loggedInAs(who)
      serveBroadcast(ended)

      const { unmount } = renderAt('/broadcasts/7')

      await screen.findByText('종료된 방송입니다')
      expect(screen.queryByRole('button', { name: '후원하기' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: '로그인하고 후원하기' })).not.toBeInTheDocument()
      unmount()
    }
  })
})

async function openModal() {
  loggedInAs(VIEWER)
  serveBroadcast(live)
  renderAt('/broadcasts/7')
  await userEvent.click(await screen.findByRole('button', { name: '후원하기' }))
  return screen.getByRole('dialog', { name: '후원하기' })
}

/** Serves `POST /api/broadcasts/7/donations` with the order for the requested amount; records each request body. */
function serveOrders() {
  const requests: Array<{ body: unknown; csrf: string | null }> = []
  server.use(
    http.post('/api/broadcasts/7/donations', async ({ request }) => {
      const body = (await request.json()) as { amount: number }
      requests.push({ body, csrf: request.headers.get('X-XSRF-TOKEN') })
      return HttpResponse.json(order(body.amount), { status: 201 })
    }),
  )
  return requests
}

describe('후원 modal', () => {
  it('shows the recipient, the 방송 and the test-mode notice', async () => {
    const dialog = await openModal()

    expect(within(dialog).getByText('방송인')).toBeInTheDocument()
    expect(within(dialog).getByText(/저녁 산책/)).toBeInTheDocument()
    expect(within(dialog).getByText('테스트 결제 — 실제 돈은 나가지 않습니다')).toBeInTheDocument()
  })

  it('creates the order, then opens the payment window with the server’s values', async () => {
    const requests = serveOrders()
    mockedPayments.requestPayment.mockReturnValue(new Promise(() => {})) // Toss navigates away
    const dialog = await openModal()

    await userEvent.click(within(dialog).getByRole('button', { name: '1만원' }))
    await userEvent.type(within(dialog).getByLabelText(/후원 메시지/), '응원해요')
    await userEvent.click(within(dialog).getByRole('button', { name: '10,000원 결제하기' }))

    await vi.waitFor(() => expect(mockedPayments.requestPayment).toHaveBeenCalledOnce())
    expect(requests).toEqual([{ body: { amount: 10000, message: '응원해요' }, csrf: CSRF_TOKEN }])
    expect(mockedPayments.requestPayment).toHaveBeenCalledWith(order(10000))
  })

  it('accepts a custom amount and sends no message when it is blank', async () => {
    const requests = serveOrders()
    mockedPayments.requestPayment.mockReturnValue(new Promise(() => {}))
    const dialog = await openModal()

    const amount = within(dialog).getByLabelText('후원 금액 (원)')
    await userEvent.clear(amount)
    await userEvent.type(amount, '23000')
    await userEvent.type(within(dialog).getByLabelText(/후원 메시지/), '   ')
    await userEvent.click(within(dialog).getByRole('button', { name: '23,000원 결제하기' }))

    await vi.waitFor(() => expect(mockedPayments.requestPayment).toHaveBeenCalledWith(order(23000)))
    expect(requests.map((r) => r.body)).toEqual([{ amount: 23000, message: null }])
  })

  it.each([
    ['', '후원 금액을 입력해 주세요.'],
    ['999', '후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.'],
    ['1500', '후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.'],
    ['1001000', '후원 금액은 1,000원부터 1,000,000원까지 1,000원 단위로 입력해 주세요.'],
  ])('refuses the amount "%s" before ordering', async (typed, error) => {
    const requests = serveOrders()
    const dialog = await openModal()

    const amount = within(dialog).getByLabelText('후원 금액 (원)')
    await userEvent.clear(amount)
    if (typed) await userEvent.type(amount, typed)
    await userEvent.click(within(dialog).getByRole('button', { name: /결제하기$/ }))

    expect(await within(dialog).findByText(error)).toBeInTheDocument()
    expect(amount).toHaveAttribute('aria-invalid', 'true')
    expect(requests).toEqual([])
    expect(mockedPayments.requestPayment).not.toHaveBeenCalled()
  })

  it('refuses a message over 100 characters before ordering', async () => {
    const requests = serveOrders()
    const dialog = await openModal()

    await userEvent.click(within(dialog).getByLabelText(/후원 메시지/))
    await userEvent.paste('가'.repeat(101))
    await userEvent.click(within(dialog).getByRole('button', { name: '5,000원 결제하기' }))

    expect(await within(dialog).findByText('후원 메시지는 100자 이하로 입력해 주세요.')).toBeInTheDocument()
    expect(requests).toEqual([])
  })

  it('shows the server’s refusal, e.g. once the 방송 has ended', async () => {
    server.use(
      http.post('/api/broadcasts/7/donations', () =>
        HttpResponse.json({ status: 409, detail: '종료된 방송에는 후원할 수 없습니다.' }, { status: 409 }),
      ),
    )
    const dialog = await openModal()

    await userEvent.click(within(dialog).getByRole('button', { name: '5,000원 결제하기' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('종료된 방송에는 후원할 수 없습니다.')
    expect(mockedPayments.requestPayment).not.toHaveBeenCalled()
  })

  it('reports a closed payment window as failed and lets the 후원자 try again', async () => {
    serveOrders()
    const failed: unknown[] = []
    server.use(
      http.post(`/api/donations/${ORDER_ID}/fail`, async ({ request }) => {
        failed.push(await request.json())
        return new HttpResponse(null, { status: 204 })
      }),
    )
    mockedPayments.requestPayment.mockRejectedValue(new payments.PaymentError('USER_CANCEL', '취소되었습니다'))
    const dialog = await openModal()

    await userEvent.click(within(dialog).getByRole('button', { name: '5,000원 결제하기' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('결제를 취소했습니다.')
    await vi.waitFor(() => expect(failed).toEqual([{ code: 'USER_CANCEL', message: '취소되었습니다' }]))
    expect(within(dialog).getByRole('button', { name: '5,000원 결제하기' })).toBeEnabled()
  })
})

const SUCCESS_QUERY = `paymentType=NORMAL&orderId=${ORDER_ID}&paymentKey=tgen_20261115abc&amount=10000`

describe('/donations/success', () => {
  it('shows 확인 중… until the server confirms, then 후원 완료 with a way back to the 방송', async () => {
    loggedInAs(VIEWER)
    let release = () => {}
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    const confirms: unknown[] = []
    server.use(
      http.post('/api/donations/confirm', async ({ request }) => {
        confirms.push(await request.json())
        await held
        return HttpResponse.json(confirmed(ORDER_ID, 10000))
      }),
    )

    renderAt(`/donations/success?${SUCCESS_QUERY}`)

    expect(await screen.findByText('확인 중…')).toBeInTheDocument()
    expect(screen.queryByText('후원 완료')).not.toBeInTheDocument()
    await act(async () => release())

    expect(await screen.findByRole('heading', { name: '후원 완료' })).toBeInTheDocument()
    expect(screen.getByText(/10,000원/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '방송으로 돌아가기' })).toHaveAttribute('href', '/broadcasts/7')
    expect(confirms).toEqual([{ paymentKey: 'tgen_20261115abc', orderId: ORDER_ID, amount: 10000 }])
  })

  it('shows 후원 완료 again on a refresh: the server answers the repeated confirm with the same 후원', async () => {
    loggedInAs(VIEWER)
    const confirms: unknown[] = []
    server.use(
      http.post('/api/donations/confirm', async ({ request }) => {
        confirms.push(await request.json())
        return HttpResponse.json(confirmed(ORDER_ID, 10000))
      }),
    )

    const first = renderAt(`/donations/success?${SUCCESS_QUERY}`)
    expect(await screen.findByRole('heading', { name: '후원 완료' })).toBeInTheDocument()
    first.unmount()
    renderAt(`/donations/success?${SUCCESS_QUERY}`) // the refresh

    expect(await screen.findByRole('heading', { name: '후원 완료' })).toBeInTheDocument()
    expect(screen.getByText(/10,000원/)).toBeInTheDocument()
    expect(confirms).toHaveLength(2)
  })

  it('shows the failure and never 후원 완료 when the server refuses to confirm', async () => {
    loggedInAs(VIEWER)
    server.use(
      http.post('/api/donations/confirm', () =>
        HttpResponse.json(
          { status: 400, detail: '승인된 금액이 후원 금액과 다릅니다.', code: 'AMOUNT_MISMATCH' },
          { status: 400 },
        ),
      ),
    )

    renderAt(`/donations/success?${SUCCESS_QUERY}`)

    expect(await screen.findByRole('heading', { name: '후원하지 못했습니다' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('승인된 금액이 후원 금액과 다릅니다.')
    expect(screen.queryByText('후원 완료')).not.toBeInTheDocument()
  })

  it('does not call the server without the payment details in the address', async () => {
    loggedInAs(VIEWER)
    let called = false
    server.use(
      http.post('/api/donations/confirm', () => {
        called = true
        return HttpResponse.json(confirmed(ORDER_ID, 10000))
      }),
    )

    renderAt(`/donations/success?orderId=${ORDER_ID}`)

    expect(await screen.findByRole('heading', { name: '후원하지 못했습니다' })).toBeInTheDocument()
    expect(called).toBe(false)
  })
})

describe('/donations/fail', () => {
  it('reports the failure to the server and shows the reason with 다시 시도', async () => {
    loggedInAs(VIEWER)
    const failed: unknown[] = []
    server.use(
      http.post(`/api/donations/${ORDER_ID}/fail`, async ({ request }) => {
        failed.push(await request.json())
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const reason = '카드 정보를 다시 확인해 주세요.'

    renderAt(
      `/donations/fail?orderId=${ORDER_ID}&code=REJECT_CARD_COMPANY&message=${encodeURIComponent(reason)}&broadcastId=7`,
    )

    expect(await screen.findByRole('heading', { name: '후원하지 못했습니다' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(reason)
    expect(screen.getByRole('link', { name: '다시 시도' })).toHaveAttribute('href', '/broadcasts/7')
    await vi.waitFor(() => expect(failed).toEqual([{ code: 'REJECT_CARD_COMPANY', message: reason }]))
  })

  it('shows the reason without calling the server when Toss sent no order (e.g. cancelled)', async () => {
    loggedInAs(VIEWER)
    let called = false
    server.use(
      http.post('/api/donations/:orderId/fail', () => {
        called = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    renderAt(`/donations/fail?code=PAY_PROCESS_CANCELED&message=${encodeURIComponent('사용자에 의해 결제가 취소되었습니다.')}`)

    expect(await screen.findByRole('alert')).toHaveTextContent('사용자에 의해 결제가 취소되었습니다.')
    expect(screen.getByRole('link', { name: '다시 시도' })).toHaveAttribute('href', '/')
    expect(called).toBe(false)
  })
})
