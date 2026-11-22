import { useId, type ReactNode } from 'react'
import { DESKTOP_VERSION, MAC_DMG_URL, WINDOWS_INSTALLER_URL } from '../download/desktopRelease'

function OsCard({
  os,
  href,
  fileName,
  facts,
  steps,
  warning,
}: {
  os: string
  href: string
  fileName: string
  facts: { label: string; value: string }[]
  steps: ReactNode[]
  warning: { title: string; body: ReactNode[] }
}) {
  const headingId = useId()
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4 rounded-lg border border-zinc-800 bg-zinc-900 p-5">
      <div className="flex items-baseline justify-between gap-2">
        <h2 id={headingId} className="text-xl font-bold">
          {os}
        </h2>
        <span className="text-sm text-zinc-400">v{DESKTOP_VERSION}</span>
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        {facts.map((fact) => (
          <div key={fact.label} className="contents">
            <dt className="text-zinc-400">{fact.label}</dt>
            <dd>{fact.value}</dd>
          </div>
        ))}
      </dl>
      <a
        href={href}
        className="self-start rounded bg-emerald-500 px-4 py-2 text-sm font-semibold text-zinc-950 hover:bg-emerald-400"
      >
        {os}용 다운로드 <span className="font-normal">({fileName})</span>
      </a>
      <div>
        <h3 className="mb-1 font-semibold">설치와 실행</h3>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-zinc-300">
          {steps.map((step, i) => (
            <li key={i}>{step}</li>
          ))}
        </ol>
      </div>
      <div className="rounded border border-amber-700/50 bg-amber-950/30 p-3 text-sm text-zinc-300">
        <h3 className="mb-1 font-semibold text-amber-300">{warning.title}</h3>
        {warning.body.map((paragraph, i) => (
          <p key={i} className="mt-1">
            {paragraph}
          </p>
        ))}
      </div>
    </section>
  )
}

function Code({ children }: { children: string }) {
  return <code className="rounded bg-zinc-800 px-1 py-0.5 font-mono text-xs text-zinc-100">{children}</code>
}

/** `/download`: where to get the 데스크톱 앱 for each OS, and how to install and run the unsigned builds. */
export function DownloadPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-2xl font-bold">데스크톱 앱</h1>
        <p className="mt-2 text-zinc-300">
          브라우저 없이 방송을 보는 시청 전용 앱입니다. 방송 목록과 시청만 할 수 있고, 로그인·댓글·후원·다시보기는 웹에서
          이용해 주세요.
        </p>
        <p className="mt-1 text-sm text-zinc-400">
          코드 서명을 하지 않은 앱이라 처음 실행할 때 OS가 경고를 띄웁니다. 아래 방법으로 실행할 수 있습니다.
        </p>
      </div>
      <div className="grid gap-6 md:grid-cols-2">
        <OsCard
          os="Windows"
          href={WINDOWS_INSTALLER_URL}
          fileName="chzzk-desktop-setup-x64.exe"
          facts={[
            { label: '아키텍처', value: 'x64 (64비트 Intel·AMD)' },
            { label: '지원 OS', value: 'Windows 10, Windows 11' },
            { label: '형식', value: 'NSIS 설치 프로그램 (.exe)' },
          ]}
          steps={[
            '설치 프로그램(chzzk-desktop-setup-x64.exe)을 내려받아 실행합니다.',
            '설치가 끝나면 시작 메뉴나 바탕 화면의 "치지직"을 실행합니다.',
          ]}
          warning={{
            title: 'SmartScreen 경고가 뜨면',
            body: [
              'Windows의 SmartScreen이 "Windows의 PC 보호" 창을 띄우면 추가 정보 → 실행을 누릅니다.',
            ],
          }}
        />
        <OsCard
          os="macOS"
          href={MAC_DMG_URL}
          fileName="chzzk-desktop-mac-universal.dmg"
          facts={[
            { label: '아키텍처', value: 'Universal: Apple Silicon (M 시리즈) + Intel' },
            { label: '지원 OS', value: 'macOS 12 Monterey 이상' },
            { label: '형식', value: '디스크 이미지 (.dmg)' },
          ]}
          steps={[
            'dmg 파일(chzzk-desktop-mac-universal.dmg)을 내려받아 엽니다.',
            'Chzzk 아이콘을 Applications 폴더로 끌어다 놓습니다.',
            'Applications 폴더에서 Chzzk를 실행합니다.',
          ]}
          warning={{
            title: 'Gatekeeper가 막으면',
            body: [
              'Gatekeeper가 "확인되지 않은 개발자" 경고로 열지 않으면 Finder에서 Chzzk를 오른쪽 클릭(Control+클릭) → 열기 → 열기를 누릅니다.',
              <>
                그래도 열리지 않으면 터미널에서 <Code>xattr -dr com.apple.quarantine /Applications/Chzzk.app</Code>
                을 실행한 뒤 다시 엽니다.
              </>,
            ],
          }}
        />
      </div>
    </div>
  )
}
