import { client } from './client'
import type { Member } from './auth'

/**
 * The data-access module for the 댓글 API, for any target: a VOD or a 방송. Listing and writing go through the
 * target (`/vods/{id}/comments`, `/broadcasts/{id}/comments`); editing and deleting address the 댓글 itself.
 */

export type CommentTarget = { kind: 'vod'; id: number | string } | { kind: 'broadcast'; id: number | string }

export interface Comment {
  id: number
  content: string
  /** The 작성자. */
  author: Member
  createdAt: string
  /** Marked "(수정됨)" on screen. */
  edited: boolean
}

export interface CommentPage {
  /** Newest first. */
  content: Comment[]
  /** Pass as `before` for the next (older) page; `null` when there are no older 댓글. */
  nextBefore: number | null
}

/** The server's rule, counted the same way (code points of the trimmed text). */
export const COMMENT_MAX_CHARS = 500

function commentsPath(target: CommentTarget): string {
  return `/${target.kind === 'vod' ? 'vods' : 'broadcasts'}/${target.id}/comments`
}

/** @param before only 댓글 older than this id (a previous page's `nextBefore`); omit for the newest */
export async function listComments(target: CommentTarget, before?: number): Promise<CommentPage> {
  const { data } = await client.get<CommentPage>(commentsPath(target), { params: { before } })
  return data
}

export async function writeComment(target: CommentTarget, content: string): Promise<Comment> {
  const { data } = await client.post<Comment>(commentsPath(target), { content })
  return data
}

export async function editComment(id: number, content: string): Promise<Comment> {
  const { data } = await client.patch<Comment>(`/comments/${id}`, { content })
  return data
}

export async function deleteComment(id: number): Promise<void> {
  await client.delete(`/comments/${id}`)
}
