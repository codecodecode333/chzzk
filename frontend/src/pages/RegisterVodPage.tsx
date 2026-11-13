import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { registerVod } from '../api/vods'

const inputClass = 'w-full rounded border border-zinc-700 bg-zinc-900 px-3 py-2'

export function RegisterVodPage() {
  const navigate = useNavigate()
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [file, setFile] = useState<File | null>(null)
  /** Percentage of the 영상 파일 sent; `null` when no upload is in flight. */
  const [uploadProgress, setUploadProgress] = useState<number | null>(null)
  const uploading = uploadProgress !== null

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!file || uploading) return
    setUploadProgress(0)
    try {
      const vod = await registerVod({ title, description, file }, { onUploadProgress: setUploadProgress })
      navigate(`/vods/${vod.id}`)
    } catch {
      setUploadProgress(null)
    }
  }

  return (
    <section className="max-w-xl">
      <h1 className="mb-4 text-2xl font-bold">다시보기 등록</h1>
      <form onSubmit={handleSubmit} className="space-y-4">
        <div>
          <label htmlFor="vod-title" className="mb-1 block text-sm font-medium">
            제목
          </label>
          <input
            id="vod-title"
            className={inputClass}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            required
          />
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
            onChange={(e) => setDescription(e.target.value)}
          />
        </div>
        <div>
          <label htmlFor="vod-file" className="mb-1 block text-sm font-medium">
            영상 파일
          </label>
          <input
            id="vod-file"
            type="file"
            accept="video/mp4,.mp4"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
        </div>
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
          disabled={uploading}
          className="rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {uploading ? `등록 중… ${uploadProgress}%` : '등록'}
        </button>
      </form>
    </section>
  )
}
