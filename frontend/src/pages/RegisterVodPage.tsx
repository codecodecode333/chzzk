import { useState, type FormEvent } from 'react'
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
  const errors = validateVodForm({ title, description, file })
  const invalid = Object.keys(errors).length > 0
  // Errors show once a field has been edited, but submit stays blocked from the start.
  const [touched, setTouched] = useState<ReadonlySet<VodFormField>>(new Set())
  const touch = (field: VodFormField) => setTouched((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
  const shown = (field: VodFormField) => (touched.has(field) ? errors[field] : undefined)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file || invalid) return
    setSubmitError(null)
    try {
      await registerVod({ title, description, file })
    } catch (error) {
      // Inputs live in this component's state, so they stay as typed for a retry.
      setSubmitError(apiErrorMessage(error))
      return
    }
    navigate('/')
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
        <div>
          <label htmlFor="vod-title" className="mb-1 block text-sm font-medium">
            제목
          </label>
          <input
            id="vod-title"
            className={inputClass}
            value={title}
            onChange={(e) => {
              setTitle(e.target.value)
              touch('title')
            }}
            aria-invalid={shown('title') ? true : undefined}
            aria-describedby={shown('title') ? 'vod-title-error' : undefined}
            required
          />
          <FieldError id="vod-title-error" message={shown('title')} />
        </div>
        <div>
          <label htmlFor="vod-description" className="mb-1 block text-sm font-medium">
            설명
          </label>
          <textarea
            id="vod-description"
            className={inputClass}
            rows={4}
            value={description}
            onChange={(e) => {
              setDescription(e.target.value)
              touch('description')
            }}
            aria-invalid={shown('description') ? true : undefined}
            aria-describedby={shown('description') ? 'vod-description-error' : undefined}
          />
          <FieldError id="vod-description-error" message={shown('description')} />
        </div>
        <div>
          <label htmlFor="vod-file" className="mb-1 block text-sm font-medium">
            영상 파일
          </label>
          <input
            id="vod-file"
            type="file"
            accept="video/mp4,.mp4"
            onChange={(e) => {
              setFile(e.target.files?.[0] ?? null)
              touch('file')
            }}
            aria-invalid={shown('file') ? true : undefined}
            aria-describedby={shown('file') ? 'vod-file-error' : undefined}
          />
          <FieldError id="vod-file-error" message={shown('file')} />
        </div>
        <button
          type="submit"
          disabled={invalid}
          className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          등록
        </button>
      </form>
    </section>
  )
}

function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (!message) return null
  return (
    <p id={id} className="mt-1 text-sm text-red-400">
      {message}
    </p>
  )
}
