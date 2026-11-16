/**
 * Registration form rules, mirroring the server (which stays authoritative).
 * Pure and independent of how the form is submitted, so the submit mechanism can change freely.
 */

import { charCount, validateTitle } from '../forms/title'

export { TITLE_MAX_CHARS } from '../forms/title'
export const DESCRIPTION_MAX_CHARS = 2_000
/**
 * The 영상 파일 size limit, from `VITE_VOD_MAX_FILE_SIZE` (same format and 1GB default as the server's
 * `VOD_MAX_FILE_SIZE`, e.g. `512MB`). Read per call so the configured value is always the current one.
 */
export function fileMaxSize(): DataSize {
  return parseDataSize(import.meta.env.VITE_VOD_MAX_FILE_SIZE ?? '1GB')
}

export interface DataSize {
  bytes: number
  /** As the server words it: the largest whole unit, e.g. `1GB`, `512MB`, `16KB`. */
  label: string
}

const UNITS = [
  ['GB', 1024 ** 3],
  ['MB', 1024 ** 2],
  ['KB', 1024],
  ['B', 1],
] as const

/** Parses a size the way Spring's `DataSize` does (`1GB`, `512MB`, `16KB`, `100B` or plain bytes; 1KB = 1024B). */
export function parseDataSize(text: string): DataSize {
  const match = /^\s*(\d+)\s*(GB|MB|KB|B)?\s*$/i.exec(text)
  if (!match) throw new Error(`Invalid VITE_VOD_MAX_FILE_SIZE: ${text}`)
  const unit = (match[2] ?? 'B').toUpperCase()
  const bytes = Number(match[1]) * UNITS.find(([name]) => name === unit)![1]
  const [name, factor] = UNITS.find(([, f]) => bytes % f === 0)!
  return { bytes, label: `${bytes / factor}${name}` }
}

export interface VodFormValues {
  title: string
  description: string
  file: File | null
}

export type VodFormField = keyof VodFormValues

export type VodFormErrors = Partial<Record<VodFormField, string>>

/** The title and description rules alone: registration's, and the 소유자's edit form's. */
export function validateVodDetails(values: Pick<VodFormValues, 'title' | 'description'>): VodFormErrors {
  const errors: VodFormErrors = {}
  const title = validateTitle(values.title)
  if (title) errors.title = title
  if (charCount(values.description) > DESCRIPTION_MAX_CHARS) {
    errors.description = `설명은 ${DESCRIPTION_MAX_CHARS.toLocaleString('en-US')}자 이하로 입력해 주세요.`
  }
  return errors
}

export function validateVodForm(values: VodFormValues): VodFormErrors {
  const errors = validateVodDetails(values)
  if (!values.file) {
    errors.file = '영상 파일을 선택해 주세요.'
  } else if (!values.file.name.toLowerCase().endsWith('.mp4')) {
    errors.file = 'MP4(.mp4) 파일만 등록할 수 있습니다.'
  } else if (values.file.size > fileMaxSize().bytes) {
    errors.file = `영상 파일은 ${fileMaxSize().label} 이하만 등록할 수 있습니다.`
  }
  return errors
}
