import type { ReactElement } from 'react'

export const inputClass = 'w-full rounded border border-zinc-700 bg-zinc-900 px-3 py-2'

export interface FieldA11yProps {
  id: string
  'aria-invalid': true | undefined
  'aria-describedby': string | undefined
}

/** A labelled form control with its validation error wired up for assistive tech. */
export function Field({
  id,
  label,
  error,
  children,
}: {
  id: string
  label: string
  error: string | undefined
  children: (a11y: FieldA11yProps) => ReactElement
}) {
  const errorId = `${id}-error`
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium">
        {label}
      </label>
      {children({
        id,
        'aria-invalid': error ? true : undefined,
        'aria-describedby': error ? errorId : undefined,
      })}
      {error && (
        <p id={errorId} className="mt-1 text-sm text-red-400">
          {error}
        </p>
      )}
    </div>
  )
}

/** A form-level error (e.g. the server's message), announced to assistive tech. */
export function FormError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p role="alert" className="rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300">
      {message}
    </p>
  )
}
