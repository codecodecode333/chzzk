import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { apiErrorMessage, deleteVod, updateVod, type Vod } from '../api/vods'
import { formatDate } from '../format'
import { Field, FormError, inputClass } from '../forms/Field'
import { useCurrentMember } from '../members/currentMemberContext'
import { validateVodDetails } from './validateVodForm'

/**
 * A VOD's title, 소유자 닉네임, date and description, plus the 소유자's own controls: 수정 (an inline title and
 * description form, validated like registration) and 삭제 (after confirmation, then back to the list). The controls
 * are hidden from everyone else; the server stays the authority, and its refusal (e.g. 403) is shown as a message.
 */
export function VodInfo({ vod, onUpdated }: { vod: Vod; onUpdated: (vod: Vod) => void }) {
  const { member } = useCurrentMember()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const isOwner = member?.id === vod.owner.id

  async function handleDelete() {
    if (deleting || !window.confirm('이 다시보기를 삭제할까요? 댓글도 함께 삭제됩니다.')) return
    setDeleting(true)
    setError(null)
    try {
      await deleteVod(vod.id)
      navigate('/vods')
    } catch (failure) {
      setDeleting(false)
      setError(apiErrorMessage(failure))
    }
  }

  if (editing) {
    return (
      <VodEditForm
        vod={vod}
        onCancel={() => setEditing(false)}
        onSaved={(updated) => {
          setEditing(false)
          onUpdated(updated)
        }}
      />
    )
  }

  return (
    <div className="mt-4">
      <FormError message={error} />
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h1 className="text-2xl font-bold">{vod.title}</h1>
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
              수정
            </button>
            <button
              type="button"
              onClick={handleDelete}
              disabled={deleting}
              className="rounded border border-red-500/50 px-3 py-1.5 text-sm text-red-300 hover:bg-red-500/10 disabled:opacity-50"
            >
              삭제
            </button>
          </div>
        )}
      </div>
      <p className="text-sm text-zinc-300">{vod.owner.nickname}</p>
      <time dateTime={vod.createdAt} className="text-sm text-zinc-400">
        {formatDate(vod.createdAt)}
      </time>
      {vod.description && <p className="mt-4 whitespace-pre-wrap text-zinc-300">{vod.description}</p>}
    </div>
  )
}

function VodEditForm({ vod, onCancel, onSaved }: { vod: Vod; onCancel: () => void; onSaved: (vod: Vod) => void }) {
  const [title, setTitle] = useState(vod.title)
  const [description, setDescription] = useState(vod.description ?? '')
  const [saving, setSaving] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const errors = validateVodDetails({ title, description })
  const invalid = Object.keys(errors).length > 0

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (invalid || saving) return
    setSaving(true)
    setSubmitError(null)
    try {
      onSaved(await updateVod(vod.id, { title, description }))
    } catch (failure) {
      setSaving(false)
      setSubmitError(apiErrorMessage(failure))
    }
  }

  return (
    <form onSubmit={handleSubmit} aria-label="다시보기 수정" className="mt-4 space-y-4">
      <FormError message={submitError} />
      <Field id="vod-edit-title" label="제목" error={errors.title}>
        {(a11y) => (
          <input {...a11y} className={inputClass} value={title} onChange={(e) => setTitle(e.target.value)} />
        )}
      </Field>
      <Field id="vod-edit-description" label="설명" error={errors.description}>
        {(a11y) => (
          <textarea
            {...a11y}
            className={inputClass}
            rows={4}
            value={description}
            onChange={(e) => setDescription(e.target.value)}
          />
        )}
      </Field>
      <div className="flex gap-2">
        <button
          type="submit"
          disabled={invalid || saving}
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
