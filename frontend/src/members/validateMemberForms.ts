/**
 * Signup and login form rules, mirroring the server's `member.domain.MemberRules` (which stays authoritative).
 * Pure and independent of how the forms submit.
 */

import { charCount } from '../forms/title'

export const PASSWORD_MIN_CHARS = 8
export const PASSWORD_MAX_CHARS = 64
/** BCrypt reads at most 72 bytes, so the server refuses longer passwords (only possible with non-ASCII). */
const PASSWORD_MAX_BYTES = 72
export const NICKNAME_MIN_CHARS = 2
export const NICKNAME_MAX_CHARS = 20
const EMAIL_MAX_CHARS = 254
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

export interface SignupFormValues {
  email: string
  password: string
  nickname: string
}

export type SignupFormField = keyof SignupFormValues

export type SignupFormErrors = Partial<Record<SignupFormField, string>>

export function validateSignupForm(values: SignupFormValues): SignupFormErrors {
  const errors: SignupFormErrors = {}
  const email = values.email.trim()
  if (email === '') {
    errors.email = '이메일을 입력해 주세요.'
  } else if (charCount(email) > EMAIL_MAX_CHARS || !EMAIL.test(email)) {
    errors.email = '이메일 형식이 올바르지 않습니다.'
  }
  const passwordChars = charCount(values.password)
  if (passwordChars < PASSWORD_MIN_CHARS || passwordChars > PASSWORD_MAX_CHARS) {
    errors.password = `비밀번호는 ${PASSWORD_MIN_CHARS}자 이상 ${PASSWORD_MAX_CHARS}자 이하로 입력해 주세요.`
  } else if (new TextEncoder().encode(values.password).length > PASSWORD_MAX_BYTES) {
    errors.password = '비밀번호가 너무 깁니다. 한글 등은 더 짧게 입력해 주세요.'
  }
  const nicknameChars = charCount(values.nickname.trim())
  if (nicknameChars < NICKNAME_MIN_CHARS || nicknameChars > NICKNAME_MAX_CHARS) {
    errors.nickname = `닉네임은 ${NICKNAME_MIN_CHARS}자 이상 ${NICKNAME_MAX_CHARS}자 이하로 입력해 주세요.`
  }
  return errors
}

export interface LoginFormValues {
  email: string
  password: string
}

export type LoginFormField = keyof LoginFormValues

/** Login checks presence only: any other mistake gets the server's single bad-credentials message. */
export function validateLoginForm(values: LoginFormValues): Partial<Record<LoginFormField, string>> {
  const errors: Partial<Record<LoginFormField, string>> = {}
  if (values.email.trim() === '') errors.email = '이메일을 입력해 주세요.'
  if (values.password === '') errors.password = '비밀번호를 입력해 주세요.'
  return errors
}
