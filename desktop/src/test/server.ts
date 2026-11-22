import { setupServer } from 'msw/node'

/** The backend address the app is given in tests; every request must go there (the app is never same-origin). */
export const BACKEND = 'https://backend.test'

/** Network boundary for app tests; each test registers its own handlers with server.use(). */
export const server = setupServer()
