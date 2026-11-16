import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { signUp } from '../api/auth'
import { apiErrorMessage, apiFieldErrors } from '../api/client'
import { Field, FormError, inputClass } from '../forms/Field'
import { useCurrentMember } from '../members/currentMemberContext'
import { safeNext, withNext } from '../members/next'
import { validateSignupForm, type SignupFormField, type SignupFormValues } from '../members/validateMemberForms'

export function SignupPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const next = safeNext(searchParams.get('next'))
  const { refresh } = useCurrentMember()
  const [values, setValues] = useState<SignupFormValues>({ email: '', password: '', nickname: '' })
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  /** The server's verdict per field (e.g. a taken 닉네임), until that field is edited again. */
  const [serverErrors, setServerErrors] = useState<Partial<Record<SignupFormField, string>>>({})
  const [touched, setTouched] = useState<ReadonlySet<SignupFormField>>(new Set())
  const errors = validateSignupForm(values)
  const invalid = Object.keys(errors).length > 0
  const visibleError = (field: SignupFormField) =>
    (touched.has(field) ? errors[field] : undefined) ?? serverErrors[field]

  function change(field: SignupFormField, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }))
    setTouched((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
    setServerErrors(({ [field]: _edited, ...rest }) => rest)
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (invalid || submitting) return
    setSubmitError(null)
    setSubmitting(true)
    try {
      await signUp(values)
      await refresh()
      navigate(next, { replace: true })
    } catch (error) {
      const fieldErrors = apiFieldErrors(error)
      setServerErrors(fieldErrors)
      setSubmitError(Object.keys(fieldErrors).length > 0 ? null : apiErrorMessage(error))
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-sm">
      <h1 className="mb-4 text-2xl font-bold">회원가입</h1>
      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <FormError message={submitError} />
        <Field id="signup-email" label="이메일" error={visibleError('email')}>
          {(a11y) => (
            <input
              {...a11y}
              className={inputClass}
              type="email"
              autoComplete="email"
              value={values.email}
              onChange={(e) => change('email', e.target.value)}
            />
          )}
        </Field>
        <Field id="signup-password" label="비밀번호" error={visibleError('password')}>
          {(a11y) => (
            <input
              {...a11y}
              className={inputClass}
              type="password"
              autoComplete="new-password"
              value={values.password}
              onChange={(e) => change('password', e.target.value)}
            />
          )}
        </Field>
        <Field id="signup-nickname" label="닉네임" error={visibleError('nickname')}>
          {(a11y) => (
            <input
              {...a11y}
              className={inputClass}
              autoComplete="nickname"
              value={values.nickname}
              onChange={(e) => change('nickname', e.target.value)}
            />
          )}
        </Field>
        <button
          type="submit"
          disabled={invalid || submitting}
          className="w-full rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? '가입 중…' : '가입하기'}
        </button>
      </form>
      <p className="mt-4 text-sm text-zinc-400">
        이미 회원인가요?{' '}
        <Link to={withNext('/login', next)} className="text-emerald-400 hover:underline">
          로그인하기
        </Link>
      </p>
    </section>
  )
}
