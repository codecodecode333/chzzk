/**
 * Registration form rules, mirroring the server (which stays authoritative).
 * Pure and independent of how the form is submitted, so the submit mechanism can change freely.
 */

export const TITLE_MAX_CHARS = 100
export const DESCRIPTION_MAX_CHARS = 2_000
/** Mirrors the server's default `vod.upload.max-file-size`. */
export const FILE_MAX_BYTES = 1024 ** 3

export interface VodFormValues {
  title: string
  description: string
  file: File | null
}

export type VodFormField = keyof VodFormValues

export type VodFormErrors = Partial<Record<VodFormField, string>>

/** Characters as the server counts them (code points), so an emoji is one char. */
function charCount(value: string): number {
  return [...value].length
}

export function validateVodForm(values: VodFormValues): VodFormErrors {
  const errors: VodFormErrors = {}
  const title = values.title.trim()
  if (title === '') {
    errors.title = '제목을 입력해 주세요.'
  } else if (charCount(title) > TITLE_MAX_CHARS) {
    errors.title = `제목은 ${TITLE_MAX_CHARS}자 이하로 입력해 주세요.`
  }
  if (charCount(values.description) > DESCRIPTION_MAX_CHARS) {
    errors.description = `설명은 ${DESCRIPTION_MAX_CHARS.toLocaleString('en-US')}자 이하로 입력해 주세요.`
  }
  if (!values.file) {
    errors.file = '영상 파일을 선택해 주세요.'
  } else if (!values.file.name.toLowerCase().endsWith('.mp4')) {
    errors.file = 'MP4(.mp4) 파일만 등록할 수 있습니다.'
  } else if (values.file.size > FILE_MAX_BYTES) {
    errors.file = '영상 파일은 1GB 이하만 등록할 수 있습니다.'
  }
  return errors
}
