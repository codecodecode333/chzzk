/**
 * The 방송 this browser tab is sending, kept in `sessionStorage` so a reload of the 송출 screen can offer
 * "이어서 송출" into the same 방송. Saved on 방송 시작, cleared on 방송 종료 (or when it turns out to have ended).
 * Storage can be unavailable (private mode, blocked site data); then resuming just isn't offered.
 */
export interface StoredBroadcast {
  id: number
  publishKey: string
  whipUrl: string
}

const KEY = 'chzzk.studio.broadcast'

export function saveStoredBroadcast(broadcast: StoredBroadcast): void {
  try {
    sessionStorage.setItem(KEY, JSON.stringify(broadcast))
  } catch {
    // Resuming after a reload won't be offered.
  }
}

export function loadStoredBroadcast(): StoredBroadcast | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return null
    const value = JSON.parse(raw) as Partial<StoredBroadcast>
    if (typeof value.id !== 'number' || typeof value.publishKey !== 'string' || typeof value.whipUrl !== 'string') {
      return null
    }
    return { id: value.id, publishKey: value.publishKey, whipUrl: value.whipUrl }
  } catch {
    return null
  }
}

export function clearStoredBroadcast(): void {
  try {
    sessionStorage.removeItem(KEY)
  } catch {
    // Nothing to clear.
  }
}
