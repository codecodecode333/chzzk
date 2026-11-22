/** Shown while the configured backend cannot be reached; the screen keeps retrying on its own. */
export function Unreachable({ backendUrl }: { backendUrl: string }) {
  return (
    <p role="alert" className="mb-6 rounded border border-red-500/40 bg-red-500/10 px-3 py-4 text-center text-red-300">
      서버에 연결할 수 없습니다 ({backendUrl}). 잠시 후 자동으로 다시 시도합니다.
    </p>
  )
}
