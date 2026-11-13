import { useState, type FormEvent, type ReactElement } from 'react'
import { useNavigate } from 'react-router'
import { apiErrorMessage, registerVod } from '../api/vods'
import { validateVodForm, type VodFormField } from '../vods/validateVodForm'

const inputClass = 'w-full rounded border border-zinc-700 bg-zinc-900 px-3 py-2'

export function RegisterVodPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  /** Percentage of the 영상 파일 sent; `null` when no upload is in flight. */
  const [uploadProgress, setUploadProgress] = useState<number | null>(null)
  const uploading = uploadProgress !== null
  const errors = validateVodForm({ title, description, file })
  const invalid = Object.keys(errors).length > 0
  // Errors show once a field has been edited, but submit stays blocked from the start.
  const [touched, setTouched] = useState<ReadonlySet<VodFormField>>(new Set())
  const touch = (field: VodFormField) => setTouched((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
  const visibleError = (field: VodFormField) => (touched.has(field) ? errors[field] : undefined)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file || invalid || uploading) return
    setSubmitError(null)
    setUploadProgress(0)
    try {
      const vod = await registerVod({ title, description, file }, { onUploadProgress: setUploadProgress })
      navigate(`/vods/${vod.id}`)
    } catch (error) {
      // Inputs live in this component's state, so they stay as typed for a retry.
      setUploadProgress(null)
      setSubmitError(apiErrorMessage(error))
    }
  }

  return (
    <section className="max-w-xl">
      <h1 className="mb-4 text-2xl font-bold">다시보기 등록</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        {submitError && (
          <p role="alert" className="rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300">
            {submitError}
          </p>
        )}
        <Field id="vod-title" label="제목" error={visibleError('title')}>
          {(a11y) => (
            <input
              {...a11y}
              className={inputClass}
              value={title}
              onChange={(e) => {
                setTitle(e.target.value)
                touch('title')
              }}
              required
            />
          )}
        </Field>
        <Field id="vod-description" label="설명" error={visibleError('description')}>
          {(a11y) => (
            <textarea
              {...a11y}
              className={inputClass}
              rows={4}
              value={description}
              onChange={(e) => {
                setDescription(e.target.value)
                touch('description')
              }}
            />
          )}
        </Field>
        <Field id="vod-file" label="영상 파일" error={visibleError('file')}>
          {(a11y) => (
            <input
              {...a11y}
              type="file"
              accept="video/mp4,.mp4"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null)
                touch('file')
              }}
            />
          )}
        </Field>
        {uploading && (
          <div
            role="progressbar"
            aria-label="업로드 진행률"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={uploadProgress}
            className="h-2 w-full overflow-hidden rounded bg-zinc-800"
          >
            <div className="h-full bg-emerald-500 transition-[width]" style={{ width: `${uploadProgress}%` }} />
          </div>
        )}
        <button
          type="submit"
          disabled={invalid || uploading}
          className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {uploading ? `등록 중… ${uploadProgress}%` : '등록'}
        </button>
      </form>
    </section>
  )
}

interface FieldA11yProps {
  id: string
  'aria-invalid': true | undefined
  'aria-describedby': string | undefined
}

/** A labelled form control with its validation error wired up for assistive tech. */
function Field({
  id,
  label,
  error,
  children,
}: {
  id: string
  label: string
  error: string | undefined
  children: (a11y: FieldA11yProps) => ReactElement
}) {
  const errorId = `${id}-error`
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium">
        {label}
      </label>
      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': error ? errorId : undefined,
      })}
      {error && (
        <p id={errorId} className="mt-1 text-sm text-red-400">
          {error}
        </p>
      )}
    </div>
  )
}
