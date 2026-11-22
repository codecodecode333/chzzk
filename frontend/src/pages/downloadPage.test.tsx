import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { AppRoutes } from '../AppRoutes'
import { DESKTOP_VERSION } from '../download/desktopRelease'
import { server } from '../test/server'

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AppRoutes />
    </MemoryRouter>,
  )
}

const RELEASES = 'https://github.com/codecodecode333/chzzk/releases/latest/download'

beforeEach(() => {
  // The menu test starts on the home page, which lists 방송; the list itself doesn't matter here.
  server.use(
    http.get('/api/broadcasts', () =>
      HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
    ),
  )
})

describe('데스크톱 앱 download page', () => {
  it('is linked from the top menu as "데스크톱 앱"', async () => {
    const user = userEvent.setup()
    renderAt('/')

    await user.click(screen.getByRole('link', { name: '데스크톱 앱' }))

    expect(await screen.findByRole('heading', { level: 1, name: /데스크톱 앱/ })).toBeInTheDocument()
  })

  it('says the app is for watching only: the 방송 list and 시청', () => {
    renderAt('/download')

    expect(screen.getByRole('main')).toHaveTextContent(/시청 전용/)
    expect(screen.getByRole('main')).toHaveTextContent(/방송 목록과 시청/)
  })

  it('offers the Windows x64 installer from the latest Release, with its version and steps', () => {
    renderAt('/download')

    const card = screen.getByRole('region', { name: 'Windows' })
    expect(within(card).getByRole('link', { name: /다운로드/ })).toHaveAttribute(
      'href',
      `${RELEASES}/chzzk-desktop-setup-x64.exe`,
    )
    expect(card).toHaveTextContent(/x64/)
    expect(card).toHaveTextContent(/Windows 10/)
    expect(card).toHaveTextContent(/v0\.1\.0/)
    expect(card).toHaveTextContent(/설치 프로그램/)
    // SmartScreen blocks the unsigned installer until 추가 정보 → 실행.
    expect(card).toHaveTextContent(/SmartScreen/)
    expect(card).toHaveTextContent(/추가 정보/)
  })

  it('offers the macOS universal dmg for Apple Silicon and Intel, with its version and Gatekeeper steps', () => {
    renderAt('/download')

    const card = screen.getByRole('region', { name: 'macOS' })
    expect(within(card).getByRole('link', { name: /다운로드/ })).toHaveAttribute(
      'href',
      `${RELEASES}/chzzk-desktop-mac-universal.dmg`,
    )
    expect(card).toHaveTextContent(/Apple Silicon/)
    expect(card).toHaveTextContent(/Intel/)
    expect(card).toHaveTextContent(/macOS 12/)
    expect(card).toHaveTextContent(/v0\.1\.0/)
    expect(card).toHaveTextContent(/Applications/)
    expect(card).toHaveTextContent(/Gatekeeper/)
    expect(card).toHaveTextContent(/xattr -dr com\.apple\.quarantine/)
  })
})

describe('데스크톱 앱 version', () => {
  it('matches desktop/package.json, which names the Release the links point to', () => {
    // Not a static import: the production image builds frontend/ alone, without desktop/.
    const desktopPackage = import.meta.glob<{ version: string }>('../../../desktop/package.json', {
      eager: true,
      import: 'default',
    })
    const versions = Object.values(desktopPackage).map((pkg) => pkg.version)

    expect(versions).toEqual([DESKTOP_VERSION])
  })
})
