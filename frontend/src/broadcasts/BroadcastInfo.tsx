import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { apiErrorMessage } from '../api/client'
import { deleteBroadcast, renameBroadcast, type Broadcast } from '../api/broadcasts'
import { formatDateTime } from '../format'
import { Field, FormError, inputClass } from '../forms/Field'
import { validateTitle } from '../forms/title'
import { useCurrentMember } from '../members/currentMemberContext'
import { LiveBadge } from './LiveBadge'

/**
 * A 방송's title, 소유자 닉네임 and times on the 시청 screen, plus the 소유자's own controls: 제목 수정 (inline, on
 * any 방송), 삭제 (on a 종료 방송, after confirmation, then home) and, while 방송 중, the link to the 송출 screen.
 * The controls are hidden from everyone else; the server stays the authority, and its refusal (e.g. 403 or 409) is
 * shown as a message.
 */
export function BroadcastInfo({
  broadcast,
  onUpdated,
}: {
  broadcast: Broadcast
  onUpdated: (broadcast: Broadcast) => void
}) {
  const { member } = useCurrentMember()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isOwner = member !== null && broadcast.owner.id === member.id
  const live = broadcast.status === 'LIVE'

  async function handleDelete() {
    if (deleting || !window.confirm('이 방송을 삭제할까요? 댓글도 함께 삭제됩니다.')) return
    setDeleting(true)
    setError(null)
    try {
      await deleteBroadcast(broadcast.id)
      navigate('/')
    } catch (failure) {
      setDeleting(false)
      setError(apiErrorMessage(failure))
    }
  }

  return (
    <div className="mt-4">
      {editing ? (
        <RenameForm
          broadcast={broadcast}
          onCancel={() => setEditing(false)}
          onSaved={(renamed) => {
            setEditing(false)
            onUpdated(renamed)
          }}
        />
      ) : (
        <>
          <FormError message={error} />
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div className="flex items-center gap-2">
              {live && <LiveBadge />}
              <h1 className="text-2xl font-bold">{broadcast.title}</h1>
            </div>
            {isOwner && (
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => {
                    setError(null)
                    setEditing(true)
                  }}
                  className="rounded border border-zinc-700 px-3 py-1.5 text-sm hover:bg-zinc-800"
                >
                  제목 수정
                </button>
                {!live && (
                  <button
                    type="button"
                    onClick={handleDelete}
                    disabled={deleting}
                    className="rounded border border-red-500/50 px-3 py-1.5 text-sm text-red-300 hover:bg-red-500/10 disabled:opacity-50"
                  >
                    삭제
                  </button>
                )}
              </div>
            )}
          </div>
        </>
      )}
      <p className="mt-1 text-zinc-300">{broadcast.owner.nickname}</p>
      {isOwner && live && (
        <Link to="/broadcasts/new" className="text-sm text-emerald-400 hover:underline">
          송출 화면으로
        </Link>
      )}
      <p className="flex gap-3 text-sm text-zinc-400">
        <time dateTime={broadcast.startedAt}>시작 {formatDateTime(broadcast.startedAt)}</time>
        {broadcast.endedAt && <time dateTime={broadcast.endedAt}>종료 {formatDateTime(broadcast.endedAt)}</time>}
      </p>
    </div>
  )
}

function RenameForm({
  broadcast,
  onCancel,
  onSaved,
}: {
  broadcast: Broadcast
  onCancel: () => void
  onSaved: (broadcast: Broadcast) => void
}) {
  const [title, setTitle] = useState(broadcast.title)
  const [saving, setSaving] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const titleError = validateTitle(title)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (titleError || saving) return
    setSaving(true)
    setSubmitError(null)
    try {
      onSaved(await renameBroadcast(broadcast.id, title))
    } catch (failure) {
      setSaving(false)
      setSubmitError(apiErrorMessage(failure))
    }
  }

  return (
    <form onSubmit={handleSubmit} aria-label="방송 제목 수정" className="space-y-3">
      <FormError message={submitError} />
      <Field id="broadcast-rename-title" label="제목" error={titleError}>
        {(a11y) => <input {...a11y} className={inputClass} value={title} onChange={(e) => setTitle(e.target.value)} />}
      </Field>
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={titleError !== undefined || saving}
          className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          저장
        </button>
        <button type="button" onClick={onCancel} className="rounded border border-zinc-700 px-4 py-2 hover:bg-zinc-800">
          취소
        </button>
      </div>
    </form>
  )
}
