import axios from 'axios'

/**
 * The single data-access module for the VOD API. Pages call these functions only;
 * extend this file (not the pages) when the API grows.
 */

const client = axios.create({ baseURL: '/api' })

export interface VodSummary {
  id: number
  title: string
  createdAt: string
}

export interface Vod {
  id: number
  title: string
  description: string | null
  createdAt: string
  streamUrl: string
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface RegisterVodInput {
  title: string
  description: string
  file: File
}

/** @param page zero-based page index, as the API counts it */
export async function listVods(page = 0): Promise<Page<VodSummary>> {
  const { data } = await client.get<Page<VodSummary>>('/vods', { params: { page } })
  return data
}

export async function registerVod(input: RegisterVodInput): Promise<Vod> {
  const form = new FormData()
  form.append('title', input.title)
  if (input.description) {
    form.append('description', input.description)
  }
  form.append('file', input.file)
  const { data } = await client.post<Vod>('/vods', form)
  return data
}
