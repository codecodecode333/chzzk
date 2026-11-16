import { useEffect, useState, type FormEvent } from 'react'
import { Link, useLocation } from 'react-router'
import { apiErrorMessage, apiFieldErrors } from '../api/client'
import {
  COMMENT_MAX_CHARS,
  deleteComment,
  editComment,
  listComments,
  writeComment,
  type Comment,
  type CommentTarget,
} from '../api/comments'
import { formatDateTime } from '../format'
import { useCurrentMember } from '../members/currentMemberContext'
import { withNext } from '../members/next'

/** The content rule, as the server applies it: 1-500 characters (code points) after trimming. */
function contentError(text: string): string | null {
  const trimmed = text.trim()
  if (!trimmed) return '댓글 내용을 입력해 주세요.'
  if ([...trimmed].length > COMMENT_MAX_CHARS) return `댓글은 ${COMMENT_MAX_CHARS}자 이하로 입력해 주세요.`
  return null
}

function errorMessage(error: unknown): string {
  return apiFieldErrors(error).content ?? apiErrorMessage(error)
}

/**
 * The 댓글 section for one target (a VOD or a 방송): the list newest first with "더 보기", the write box for 회원
 * (or "로그인하고 댓글 달기" for visitors), and inline 수정 / 삭제 on the current 회원's own 댓글.
 */
export function CommentSection({ target }: { target: CommentTarget }) {
  const { kind, id } = target
  const { member, loading: memberLoading } = useCurrentMember()
  const location = useLocation()
  const targetKey = `${kind}:${id}`
  /** The shown 댓글 and the cursor, for the target they were loaded for. */
  const [loaded, setLoaded] = useState<{ targetKey: string; comments: Comment[]; nextBefore: number | null }>()
  const [loadError, setLoadError] = useState<{ targetKey: string; message: string }>()
  const [loadingMore, setLoadingMore] = useState(false)
  const current = loaded?.targetKey === targetKey ? loaded : undefined
  const comments = current?.comments
  const nextBefore = current?.nextBefore ?? null
  const shownLoadError = loadError?.targetKey === targetKey ? loadError.message : null

  useEffect(() => {
    let active = true
    listComments({ kind, id } as CommentTarget).then(
      (page) => {
        if (active) setLoaded({ targetKey, comments: page.content, nextBefore: page.nextBefore })
      },
      (error: unknown) => {
        if (active) setLoadError({ targetKey, message: apiErrorMessage(error) })
      },
    )
    return () => {
      active = false
    }
  }, [kind, id, targetKey])

  /** Changes the shown 댓글 of this target (no-op before the first page arrives). */
  function update(change: (shown: Comment[]) => Comment[], cursor?: number | null) {
    setLoaded((prev) =>
      prev && prev.targetKey === targetKey
        ? { ...prev, comments: change(prev.comments), nextBefore: cursor === undefined ? prev.nextBefore : cursor }
        : prev,
    )
  }

  async function loadMore() {
    if (nextBefore === null || loadingMore) return
    setLoadingMore(true)
    try {
      const page = await listComments(target, nextBefore)
      // A 댓글 written meanwhile can't reappear here: older pages only hold ids below the cursor.
      update((shown) => [...shown, ...page.content], page.nextBefore)
      setLoadError(undefined)
    } catch (error) {
      setLoadError({ targetKey, message: apiErrorMessage(error) })
    } finally {
      setLoadingMore(false)
    }
  }

  const replace = (updated: Comment) => update((shown) => shown.map((c) => (c.id === updated.id ? updated : c)))
  const remove = (removedId: number) => update((shown) => shown.filter((c) => c.id !== removedId))

  return (
    <section aria-labelledby="comments-heading" className="mt-8">
      <h2 id="comments-heading" className="text-lg font-bold">
        댓글
      </h2>

      {memberLoading ? null : member ? (
        <WriteBox target={target} onWritten={(written) => update((shown) => [written, ...shown])} />
      ) : (
        <Link
          to={withNext('/login', location.pathname + location.search)}
          className="mt-3 inline-block text-emerald-400 hover:underline"
        >
          로그인하고 댓글 달기
        </Link>
      )}

      {shownLoadError && (
        <p role="alert" className="mt-3 text-sm text-red-400">
          {shownLoadError}
        </p>
      )}

      {comments && comments.length === 0 && <p className="mt-4 text-sm text-zinc-400">첫 댓글을 남겨 보세요.</p>}
      {comments && comments.length > 0 && (
        <ul className="mt-4 space-y-4">
          {comments.map((c) => (
            <CommentItem
              key={c.id}
              comment={c}
              mine={member?.id === c.author.id}
              onEdited={replace}
              onDeleted={() => remove(c.id)}
            />
          ))}
        </ul>
      )}

      {nextBefore !== null && (
        <button
          type="button"
          onClick={loadMore}
          disabled={loadingMore}
          className="mt-4 w-full rounded border border-zinc-700 py-2 text-sm text-zinc-300 hover:bg-zinc-800 disabled:opacity-50"
        >
          더 보기
        </button>
      )}
    </section>
  )
}

function WriteBox({ target, onWritten }: { target: CommentTarget; onWritten: (comment: Comment) => void }) {
  const [text, setText] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    const invalid = contentError(text)
    if (invalid) {
      setError(invalid)
      return
    }
    setSending(true)
    setError(null)
    try {
      onWritten(await writeComment(target, text))
      setText('')
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSending(false)
    }
  }

  return (
    <form onSubmit={submit} className="mt-3">
      <textarea
        aria-label="댓글 쓰기"
        value={text}
        onChange={(e) => setText(e.target.value)}
        rows={3}
        placeholder="댓글을 남겨 보세요"
        className="w-full rounded border border-zinc-700 bg-zinc-900 p-2 text-sm"
      />
      {error && (
        <p role="alert" className="text-sm text-red-400">
          {error}
        </p>
      )}
      <div className="mt-1 flex justify-end">
        <button
          type="submit"
          disabled={sending || !text.trim()}
          className="rounded bg-emerald-500 px-4 py-1.5 text-sm font-semibold text-black disabled:opacity-50"
        >
          등록
        </button>
      </div>
    </form>
  )
}

function CommentItem({
  comment,
  mine,
  onEdited,
  onDeleted,
}: {
  comment: Comment
  mine: boolean
  onEdited: (comment: Comment) => void
  onDeleted: () => void
}) {
  /** The draft while editing, `null` otherwise. */
  const [draft, setDraft] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function save(event: FormEvent) {
    event.preventDefault()
    if (draft === null) return
    const invalid = contentError(draft)
    if (invalid) {
      setError(invalid)
      return
    }
    setBusy(true)
    setError(null)
    try {
      onEdited(await editComment(comment.id, draft))
      setDraft(null)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (busy || !window.confirm('댓글을 삭제할까요?')) return
    setBusy(true)
    setError(null)
    try {
      await deleteComment(comment.id)
      onDeleted()
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <li className="border-b border-zinc-800 pb-3">
      <div className="flex items-baseline gap-2 text-sm">
        <span className="font-semibold">{comment.author.nickname}</span>
        <time dateTime={comment.createdAt} className="text-zinc-400">
          {formatDateTime(comment.createdAt)}
        </time>
        {comment.edited && <span className="text-zinc-500">(수정됨)</span>}
        {mine && draft === null && (
          <span className="ml-auto flex gap-2 text-zinc-400">
            <button type="button" onClick={() => setDraft(comment.content)} disabled={busy} className="hover:underline">
              수정
            </button>
            <button type="button" onClick={remove} disabled={busy} className="hover:underline">
              삭제
            </button>
          </span>
        )}
      </div>
      {draft === null ? (
        <p className="mt-1 whitespace-pre-wrap break-words text-zinc-200">{comment.content}</p>
      ) : (
        <form onSubmit={save} className="mt-1">
          <textarea
            aria-label="댓글 수정"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            rows={3}
            className="w-full rounded border border-zinc-700 bg-zinc-900 p-2 text-sm"
          />
          <div className="flex justify-end gap-2 text-sm">
            <button
              type="button"
              onClick={() => {
                setDraft(null)
                setError(null)
              }}
              className="px-3 py-1 text-zinc-300"
            >
              취소
            </button>
            <button
              type="submit"
              disabled={busy}
              className="rounded bg-emerald-500 px-3 py-1 font-semibold text-black disabled:opacity-50"
            >
              저장
            </button>
          </div>
        </form>
      )}
      {error && (
        <p role="alert" className="text-sm text-red-400">
          {error}
        </p>
      )}
    </li>
  )
}
