import { isAxiosError } from 'axios'
import { client } from './client'

/**
 * The data-access module for 회원 auth. Pages use the current 회원 through `members/CurrentMember` rather than
 * calling `getMe` themselves.
 */

/** A 회원 as the API shows one (`/api/me`, and later as a 소유자 or 작성자). */
export interface Member {
  id: number
  nickname: string
}

/** The logged-in 회원, or `null` for a visitor (401). Any other failure rejects. */
export async function getMe(): Promise<Member | null> {
  try {
    const { data } = await client.get<Member>('/me')
    return data
  } catch (error) {
    if (isAxiosError(error) && error.response?.status === 401) return null
    throw error
  }
}

export interface SignupInput {
  email: string
  password: string
  nickname: string
}

/**
 * 회원가입; the browser is logged in afterwards. Rejects with Problem Details: 400 or 409 with `errors` naming the
 * fields (read them with `apiFieldErrors`).
 */
export async function signUp(input: SignupInput): Promise<Member> {
  const { data } = await client.post<Member>('/auth/signup', input)
  return data
}

/** 로그인. Rejects with a 401 Problem Details on bad credentials; show it with `apiErrorMessage`. */
export async function logIn(email: string, password: string): Promise<void> {
  await client.post('/auth/login', { email, password })
}

/** 로그아웃: the session ends on the server. */
export async function logOut(): Promise<void> {
  await client.post('/auth/logout')
}
