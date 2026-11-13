/** Shown in place of a page's content when its data could not be loaded. */
export function LoadError({ message }: { message: string }) {
  return (
    <p role="alert" className="rounded border border-red-500/40 bg-red-500/10 px-3 py-6 text-center text-red-300">
      {message}
    </p>
  )
}
