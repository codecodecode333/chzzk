import { setupServer } from 'msw/node'

/** Network boundary for page-component tests; each test registers its own handlers with server.use(). */
export const server = setupServer()
