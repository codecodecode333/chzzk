import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { logIn } from '../api/auth'
import { apiErrorMessage } from '../api/client'
import { Field, FormError, inputClass } from '../forms/Field'
import { useCurrentMember } from '../members/currentMemberContext'
import { safeNext, withNext } from '../members/next'
import { validateLoginForm, type LoginFormField, type LoginFormValues } from '../members/validateMemberForms'

export function LoginPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const next = safeNext(searchParams.get('next'))
  const { refresh } = useCurrentMember()
  const [values, setValues] = useState<LoginFormValues>({ email: '', password: '' })
  const [submitting, setSubmitting] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [touched, setTouched] = useState<ReadonlySet<LoginFormField>>(new Set())
  const errors = validateLoginForm(values)
  const invalid = Object.keys(errors).length > 0
  const visibleError = (field: LoginFormField) => (touched.has(field) ? errors[field] : undefined)

  function change(field: LoginFormField, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }))
    setTouched((prev) => (prev.has(field) ? prev : new Set(prev).add(field)))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (invalid || submitting) return
    setSubmitError(null)
    setSubmitting(true)
    try {
      await logIn(values.email, values.password)
      await refresh()
      navigate(next, { replace: true })
    } catch (error) {
      setSubmitError(apiErrorMessage(error))
      setSubmitting(false)
    }
  }

  return (
    <section className="mx-auto max-w-sm">
      <h1 className="mb-4 text-2xl font-bold">로그인</h1>
      <form onSubmit={handleSubmit} className="space-y-4" noValidate>
        <FormError message={submitError} />
        <Field id="login-email" label="이메일" error={visibleError('email')}>
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
        <Field id="login-password" label="비밀번호" error={visibleError('password')}>
          {(a11y) => (
            <input
              {...a11y}
              className={inputClass}
              type="password"
              autoComplete="current-password"
              value={values.password}
              onChange={(e) => change('password', e.target.value)}
            />
          )}
        </Field>
        <button
          type="submit"
          disabled={invalid || submitting}
          className="w-full rounded bg-emerald-500 px-4 py-2 font-semibold text-zinc-950 hover:bg-emerald-400 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {submitting ? '로그인 중…' : '로그인'}
        </button>
      </form>
      <p className="mt-4 text-sm text-zinc-400">
        처음인가요?{' '}
        <Link to={withNext('/signup', next)} className="text-emerald-400 hover:underline">
          회원가입하기
        </Link>
      </p>
    </section>
  )
}
