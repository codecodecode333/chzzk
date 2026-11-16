import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './server'

// The whole suite runs files in parallel; under that load a page can take longer than the default 1s to settle, so
// findBy/waitFor wait longer before failing (they still resolve as soon as the UI is there).
configure({ asyncUtilTimeout: 4000 })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  server.resetHandlers()
  cleanup()
})
afterAll(() => server.close())
